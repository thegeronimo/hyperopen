(ns hyperopen.startup.runtime)

(defn default-startup-runtime-state
  []
  {:deferred-scheduled? false
   :bootstrapped-address nil
   :summary-logged? false})

(defn mark-performance!
  [mark-name]
  (when (and (exists? js/performance)
             (some? (.-mark js/performance)))
    (try
      (.mark js/performance mark-name)
      (catch :default _
        nil))))

(defn schedule-idle-or-timeout!
  [delay-ms f]
  (if (and (exists? js/window)
           (some? (.-requestIdleCallback js/window)))
    (.requestIdleCallback js/window
                          (fn [_]
                            (f))
                          #js {:timeout delay-ms})
    (js/setTimeout f delay-ms)))

(defn- startup-state
  [{:keys [startup-runtime runtime]}]
  (if startup-runtime
    @startup-runtime
    (:startup @runtime)))

(defn- swap-startup-state!
  [{:keys [startup-runtime runtime]} update-fn & args]
  (if startup-runtime
    (apply swap! startup-runtime update-fn args)
    (swap! runtime update :startup
           (fn [state]
             (apply update-fn state args)))))

(defn schedule-startup-summary-log!
  [{:keys [store get-request-stats delay-ms log-fn] :as deps}]
  (when-not (:summary-logged? (startup-state deps))
    (swap-startup-state! deps assoc :summary-logged? true)
    (js/setTimeout
     (fn []
       (let [stats (get-request-stats)
             ws-status (get-in @store [:websocket :status])
             selector (select-keys (get @store :asset-selector)
                                   [:loading? :phase :loaded-at-ms])]
         (log-fn "Startup summary (+5s):"
                 (clj->js {:request-stats stats
                           :websocket-status ws-status
                           :asset-selector selector}))))
     delay-ms)))

(defn register-icon-service-worker!
  [{:keys [icon-service-worker-path log-fn]}]
  (let [navigator-object (when (exists? js/globalThis)
                           (.-navigator js/globalThis))
        service-worker (some-> navigator-object (.-serviceWorker))
        register-fn (some-> service-worker (.-register))]
    (when (fn? register-fn)
      ;; Persist icon responses across reloads to avoid repeated broken-image flashes.
      (-> (.register service-worker icon-service-worker-path)
          (.then (fn [_registration]
                   (log-fn "Registered icon cache service worker.")))
          (.catch (fn [err]
                    (log-fn "Service worker registration failed:" err)))))))

(defn stage-b-account-bootstrap!
  [{:keys [store
           address
           dexs
           per-dex-stagger-ms
           fetch-frontend-open-orders!
           fetch-clearinghouse-state!]}]
  (doseq [[idx dex] (map-indexed vector (or dexs []))]
    (js/setTimeout
     (fn []
       ;; Guard against stale async callbacks for an old address.
       (when (= address (get-in @store [:wallet :address]))
         (fetch-frontend-open-orders! store address dex {:priority :low})
         (fetch-clearinghouse-state! store address dex {:priority :low})))
     (* per-dex-stagger-ms (inc idx)))))

(defn bootstrap-account-data!
  [{:keys [store
           address
           fetch-frontend-open-orders!
           fetch-user-fills!
           fetch-spot-clearinghouse-state!
           fetch-user-abstraction!
           fetch-and-merge-funding-history!
           ensure-perp-dexs!
           stage-b-account-bootstrap!
           log-fn]
    :as deps}]
  (when address
    (when-not (= address (:bootstrapped-address (startup-state deps)))
      (swap-startup-state! deps assoc :bootstrapped-address address)
      (swap! store assoc-in [:orders :open-orders-snapshot-by-dex] {})
      (swap! store assoc-in [:orders :fundings-raw] [])
      (swap! store assoc-in [:orders :fundings] [])
      (swap! store assoc-in [:orders :order-history] [])
      (swap! store assoc-in [:perp-dex-clearinghouse] {})
      ;; Stage A: critical account data.
      (fetch-frontend-open-orders! store address {:priority :high})
      (fetch-user-fills! store address {:priority :high})
      (fetch-spot-clearinghouse-state! store address {:priority :high})
      (fetch-user-abstraction! store address {:priority :high})
      (fetch-and-merge-funding-history! store address {:priority :high})
      ;; Stage B: low-priority, staggered per-dex data.
      (-> (ensure-perp-dexs! store {:priority :low})
          (.then (fn [dexs]
                   (stage-b-account-bootstrap! address dexs)))
          (.catch (fn [err]
                    (log-fn "Error bootstrapping per-dex account data:" err)))))))

(defn install-address-handlers!
  [{:keys [store
           bootstrap-account-data!
           init-with-webdata2!
           add-handler!
           sync-current-address!
           create-user-handler
           subscribe-user!
           unsubscribe-user!
           subscribe-webdata2!
           unsubscribe-webdata2!
           address-handler-reify
           address-handler-name]
    :as deps}]
  ;; Note: WebData2 subscriptions are managed by address-watcher.
  (init-with-webdata2! store subscribe-webdata2! unsubscribe-webdata2!)
  (add-handler! (create-user-handler subscribe-user! unsubscribe-user!))
  (add-handler!
   (address-handler-reify
    (fn [new-address]
      (if new-address
        (bootstrap-account-data! new-address)
        (do
          (swap-startup-state! deps assoc :bootstrapped-address nil)
          (swap! store assoc-in [:orders :open-orders-snapshot-by-dex] {})
          (swap! store assoc-in [:orders :fundings-raw] [])
          (swap! store assoc-in [:orders :fundings] [])
          (swap! store assoc-in [:orders :order-history] [])
          (swap! store assoc-in [:perp-dex-clearinghouse] {})
          (swap! store assoc-in [:spot :clearinghouse-state] nil)
          (swap! store assoc :account {:mode :classic
                                       :abstraction-raw nil}))))
    address-handler-name))
  ;; Ensure already-connected wallets are handled after handlers are in place.
  (sync-current-address! store))

(defn start-critical-bootstrap!
  [{:keys [store
           fetch-asset-contexts!
           fetch-asset-selector-markets!
           mark-performance!]}]
  (-> (js/Promise.all
       (clj->js [(fetch-asset-contexts! store {:priority :high})
                 (fetch-asset-selector-markets! store {:phase :bootstrap})]))
      (.finally
       (fn []
         (mark-performance! "app:critical-data:ready")))))

(defn run-deferred-bootstrap!
  [{:keys [store fetch-asset-selector-markets! mark-performance!]}]
  (-> (fetch-asset-selector-markets! store {:phase :full})
      (.finally
       (fn []
         (mark-performance! "app:full-bootstrap:ready")))))

(defn schedule-deferred-bootstrap!
  [{:keys [schedule-idle-or-timeout! run-deferred-bootstrap!] :as deps}]
  (when-not (:deferred-scheduled? (startup-state deps))
    (swap-startup-state! deps assoc :deferred-scheduled? true)
    (schedule-idle-or-timeout! run-deferred-bootstrap!)))

(defn initialize-remote-data-streams!
  [{:keys [store
           ws-url
           log-fn
           init-connection!
           init-active-ctx!
           init-orderbook!
           init-trades!
           init-user-ws!
           init-webdata2!
           dispatch!
           install-address-handlers!
           start-critical-bootstrap!
           schedule-deferred-bootstrap!]}]
  (log-fn "Initializing remote data streams...")
  ;; Initialize websocket client.
  (init-connection! ws-url)
  ;; Initialize WebSocket modules.
  (init-active-ctx! store)
  (init-orderbook! store)
  (init-trades! store)
  (init-user-ws! store)
  (init-webdata2! store)
  ;; Ensure active-asset market streams are requested on startup.
  (when-let [asset (:active-asset @store)]
    (dispatch! store nil [[:actions/subscribe-to-asset asset]]))
  (install-address-handlers!)
  (start-critical-bootstrap!)
  (schedule-deferred-bootstrap!))
