(ns hyperopen.startup.runtime
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hyperopen.account.surface-service :as account-surface-service]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.platform :as platform]
            [hyperopen.startup.route-refresh :as route-refresh]
            [hyperopen.wallet.address-watcher :as address-watcher]))
(defn default-startup-runtime-state
  []
  {:deferred-scheduled? false
   :bootstrapped-address nil
   :summary-logged? false})
(def default-address-handler-name
  "startup-account-bootstrap-handler")
(def ^:private default-user-handler-name
  "user-ws-subscription-handler")
(def ^:private default-webdata2-handler-name
  "webdata2-subscription-handler")

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
    (platform/set-timeout! f delay-ms)))

(defn schedule-post-render-startup!
  [f]
  ;; Yield one macrotask so browser can paint before startup fetch/subscription work.
  (platform/set-timeout! f 0))

(defn yield-to-main!
  []
  (let [scheduler-object (some-> (when (exists? js/globalThis) js/globalThis)
                                 (.-scheduler))
        yield-fn (some-> scheduler-object (gobj/get "yield"))
        timeout-fallback (fn []
                           (js/Promise.
                            (fn [resolve _reject]
                              (platform/set-timeout!
                               (fn []
                                 (resolve nil))
                               0))))]
    (if (fn? yield-fn)
      (try
        (.call yield-fn scheduler-object)
        (catch :default _
          (timeout-fallback)))
      (timeout-fallback))))

(defn reify-address-handler
  [on-address-changed-fn handler-name]
  (reify address-watcher/IAddressChangeHandler
    (on-address-changed [_ _ new-address]
      (on-address-changed-fn new-address))
    (get-handler-name [_]
      handler-name)))

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
    (platform/set-timeout!
     (fn []
       (let [stats (get-request-stats)
             hotspots (info-client/top-request-hotspots stats {:limit 5})
             ws-status (get-in @store [:websocket :health :transport :state])
             selector (select-keys (get @store :asset-selector)
                                   [:loading? :phase :loaded-at-ms])]
         (log-fn "Startup summary (+5s):"
                 (clj->js {:request-stats stats
                           :request-hotspots hotspots
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

(defonce ^:private asset-selector-shortcuts-cleanup
  (atom nil))

(defonce ^:private position-tpsl-clickaway-cleanup
  (atom nil))

(defn- editable-shortcut-target?
  [event]
  (let [target (some-> event .-target)
        tag-token (some-> target .-tagName str str/lower-case)
        input-like? (contains? #{"input" "textarea" "select"} tag-token)
        content-editable? (true? (some-> target .-isContentEditable))
        within-content-editable? (boolean
                                  (and (fn? (some-> target .-closest))
                                       (.closest target "[contenteditable='true']")))]
    (or input-like?
        content-editable?
        within-content-editable?)))

(defn- next-order-history-request-id
  [state]
  (inc (get-in state [:account-info :order-history :request-id] 0)))

(defn- invalidate-order-history-request
  [state]
  (-> state
      (assoc-in [:account-info :order-history :request-id]
                (next-order-history-request-id state))
      (assoc-in [:account-info :order-history :loading?] false)
      (assoc-in [:account-info :order-history :error] nil)
      (assoc-in [:account-info :order-history :loaded-at-ms] nil)
      (assoc-in [:account-info :order-history :loaded-for-address] nil)
      (assoc-in [:orders :order-history] [])))

(defn- invalidate-order-history-request!
  [store]
  (swap! store invalidate-order-history-request))

(defn- prefetch-order-history!
  [{:keys [store fetch-historical-orders!]}]
  (when (fn? fetch-historical-orders!)
    (let [request-id (next-order-history-request-id @store)]
      (swap! store
             (fn [state]
               (-> state
                   (assoc-in [:account-info :order-history :request-id] request-id)
                   (assoc-in [:account-info :order-history :loading?] true)
                   (assoc-in [:account-info :order-history :error] nil)
                   (assoc-in [:account-info :order-history :loaded-at-ms] nil)
                   (assoc-in [:account-info :order-history :loaded-for-address] nil))))
      (fetch-historical-orders! store request-id {:priority :low}))))

(def ^:private default-startup-funding-history-lookback-ms
  (* 7 24 60 60 1000))

(defn- normalize-startup-funding-history-lookback-ms
  [value]
  (if (number? value)
    (max 0 (js/Math.floor value))
    default-startup-funding-history-lookback-ms))

(defn- startup-funding-history-request-opts
  [startup-funding-history-lookback-ms]
  (let [end-time-ms (platform/now-ms)
        lookback-ms (normalize-startup-funding-history-lookback-ms
                     startup-funding-history-lookback-ms)]
    {:priority :high
     :start-time-ms (max 0 (- end-time-ms lookback-ms))
     :end-time-ms end-time-ms}))

(defn- reset-account-surface-state
  [state]
  ;; Clear every account-derived surface when the effective account changes so
  ;; disconnected, spectate, and connected transitions cannot drift apart.
  (-> state
      (assoc :webdata2 nil)
      (assoc-in [:orders :open-orders] [])
      (assoc-in [:orders :open-orders-hydrated?] false)
      (assoc-in [:orders :open-orders-snapshot] [])
      (assoc-in [:orders :open-orders-snapshot-by-dex] {})
      (assoc-in [:orders :open-error] nil)
      (assoc-in [:orders :open-error-category] nil)
      (assoc-in [:orders :fills] [])
      (assoc-in [:orders :fills-error] nil)
      (assoc-in [:orders :fills-error-category] nil)
      (assoc-in [:orders :fundings-raw] [])
      (assoc-in [:orders :fundings] [])
      (assoc-in [:orders :order-history] [])
      (assoc-in [:orders :ledger] [])
      (assoc-in [:orders :twap-states] [])
      (assoc-in [:orders :twap-history] [])
      (assoc-in [:orders :twap-slice-fills] [])
      (assoc-in [:orders :pending-cancel-oids] nil)
      (update-in [:account-info :funding-history]
                 (fn [funding-history]
                   (-> (or funding-history {})
                       (assoc :loading? false)
                       (assoc :error nil))))
      (update-in [:account-info :order-history]
                 (fn [order-history]
                   (-> (or order-history {})
                       (assoc :loading? false)
                       (assoc :error nil)
                       (assoc :loaded-at-ms nil)
                       (assoc :loaded-for-address nil))))
      (assoc-in [:spot :clearinghouse-state] nil)
      (assoc-in [:spot :loading-balances?] false)
      (assoc-in [:spot :error] nil)
      (assoc-in [:spot :error-category] nil)
      (assoc-in [:perp-dex-clearinghouse] {})
      (assoc-in [:perp-dex-clearinghouse-error] nil)
      (assoc-in [:perp-dex-clearinghouse-error-category] nil)
      (update :portfolio assoc
              :summary-by-key {} :user-fees nil :ledger-updates []
              :loading? false :user-fees-loading? false :user-fees-loading-for-address nil
              :error nil :user-fees-error nil :user-fees-error-for-address nil :ledger-error nil
              :loaded-at-ms nil :user-fees-loaded-at-ms nil :user-fees-loaded-for-address nil
              :ledger-loaded-at-ms nil)
      (assoc :account {:mode :classic :abstraction-raw nil})))

(defn clear-disconnected-account-state!
  [{:keys [store
           address
           unsubscribe-user!
           unsubscribe-webdata2!]
    :as deps}]
  (when address
    (when (fn? unsubscribe-user!)
      (unsubscribe-user! address))
    (when (fn? unsubscribe-webdata2!)
      (unsubscribe-webdata2! address)))
  (swap-startup-state! deps assoc :bootstrapped-address nil)
  (swap! store
         (fn [state]
           (-> state
               reset-account-surface-state
               invalidate-order-history-request))))

(defn install-asset-selector-shortcuts!
  [{:keys [store dispatch!]}]
  (let [window-object (when (exists? js/window) js/window)
        add-event-listener (some-> window-object (.-addEventListener))
        remove-event-listener (some-> window-object (.-removeEventListener))]
    (when (and (fn? add-event-listener)
               (fn? remove-event-listener)
               (some? store)
               (fn? dispatch!))
      (when-let [cleanup @asset-selector-shortcuts-cleanup]
        (cleanup)
        (reset! asset-selector-shortcuts-cleanup nil))
      (let [handler (fn [event]
                      (let [key (some-> event .-key)
                            meta-key? (true? (some-> event .-metaKey))
                            ctrl-key? (true? (some-> event .-ctrlKey))
                            shift-key? (true? (some-> event .-shiftKey))
                            key-token (some-> key str str/lower-case)
                            open-shortcut? (and (or meta-key? ctrl-key?)
                                                (= key-token "k"))
                            stop-shortcut? (and (or meta-key? ctrl-key?)
                                                shift-key?
                                                (= key-token "x"))
                            spectate-mode-active? (account-context/spectate-mode-active? @store)
                            editable-target? (editable-shortcut-target? event)
                            stop-spectate-shortcut? (and stop-shortcut?
                                                      spectate-mode-active?
                                                      (not editable-target?))
                            selector-visible? (= :asset-selector
                                                 (get-in @store [:asset-selector :visible-dropdown]))]
                        (when (or open-shortcut?
                                  stop-spectate-shortcut?
                                  (and selector-visible?
                                       (= key "Escape")))
                          (when (or open-shortcut?
                                    stop-spectate-shortcut?)
                            (.preventDefault event))
                          (if stop-spectate-shortcut?
                            (dispatch! store nil [[:actions/stop-spectate-mode]])
                            (dispatch! store nil [[:actions/handle-asset-selector-shortcut
                                                   key
                                                   meta-key?
                                                   ctrl-key?
                                                   nil]])))))]
        (.addEventListener window-object "keydown" handler)
        (reset! asset-selector-shortcuts-cleanup
                (fn []
                  (.removeEventListener window-object "keydown" handler)))))))

(defn- event-target-with-closest
  [event]
  (let [target (some-> event .-target)]
    (cond
      (fn? (some-> target .-closest)) target
      (fn? (some-> target .-parentElement .-closest)) (.-parentElement target)
      :else nil)))

(defn- within-position-overlay-surface?
  [target]
  (boolean
   (or (some-> target (.closest "[data-position-tpsl-surface='true']"))
       (some-> target (.closest "[data-position-tpsl-trigger='true']"))
       (some-> target (.closest "[data-position-reduce-surface='true']"))
       (some-> target (.closest "[data-position-reduce-trigger='true']"))
       (some-> target (.closest "[data-position-margin-surface='true']"))
       (some-> target (.closest "[data-position-margin-trigger='true']"))
       (some-> target (.closest "[data-spectate-mode-surface='true']"))
       (some-> target (.closest "[data-spectate-mode-trigger='true']")))))

(defn install-position-tpsl-clickaway!
  [{:keys [store dispatch!]}]
  (let [window-object (when (exists? js/window) js/window)
        add-event-listener (some-> window-object (.-addEventListener))
        remove-event-listener (some-> window-object (.-removeEventListener))]
    (when (and (fn? add-event-listener)
               (fn? remove-event-listener)
               (some? store)
               (fn? dispatch!))
      (when-let [cleanup @position-tpsl-clickaway-cleanup]
        (cleanup)
        (reset! position-tpsl-clickaway-cleanup nil))
      (let [handler (fn [event]
                      (let [tpsl-open? (true? (get-in @store [:positions-ui :tpsl-modal :open?]))
                            reduce-open? (true? (get-in @store [:positions-ui :reduce-popover :open?]))
                            margin-open? (true? (get-in @store [:positions-ui :margin-modal :open?]))
                            spectate-mode-open? (true? (get-in @store [:account-context :spectate-ui :modal-open?]))
                            any-open? (or tpsl-open? reduce-open? margin-open? spectate-mode-open?)]
                        (when any-open?
                          (let [target (event-target-with-closest event)]
                            (when-not (within-position-overlay-surface? target)
                              (let [close-actions (cond-> []
                                                    tpsl-open? (conj [:actions/close-position-tpsl-modal])
                                                    reduce-open? (conj [:actions/close-position-reduce-popover])
                                                    margin-open? (conj [:actions/close-position-margin-modal])
                                                    spectate-mode-open? (conj [:actions/close-spectate-mode-modal]))]
                                (when (seq close-actions)
                                  (dispatch! store nil close-actions))))))))]
        (.addEventListener window-object "mousedown" handler)
        (reset! position-tpsl-clickaway-cleanup
                (fn []
                  (.removeEventListener window-object "mousedown" handler)))))))

(defn stage-b-account-bootstrap!
  [deps]
  (account-surface-service/stage-b-account-bootstrap!
   (assoc deps :resolve-current-address account-context/effective-account-address)))

(defn bootstrap-account-data!
  [{:keys [store
           address
           fetch-frontend-open-orders!
           fetch-user-fills!
           fetch-spot-clearinghouse-state!
           fetch-user-abstraction!
           fetch-portfolio!
           fetch-user-fees!
           fetch-historical-orders!
           fetch-and-merge-funding-history!
           ensure-perp-dexs!
           stage-b-account-bootstrap!
           startup-stream-backfill-delay-ms
           startup-funding-history-lookback-ms
           log-fn]
    :as deps}]
  (when address
    (when-not (= address (:bootstrapped-address (startup-state deps)))
      (let [funding-request-opts
            (startup-funding-history-request-opts
             startup-funding-history-lookback-ms)]
      (swap-startup-state! deps assoc :bootstrapped-address address)
      (swap! store reset-account-surface-state)
      (prefetch-order-history! {:store store
                                :fetch-historical-orders! fetch-historical-orders!})
      (account-surface-service/bootstrap-account-surfaces!
       {:store store
        :address address
        :fetch-frontend-open-orders! fetch-frontend-open-orders!
        :fetch-user-fills! fetch-user-fills!
        :fetch-spot-clearinghouse-state! fetch-spot-clearinghouse-state!
        :fetch-user-abstraction! fetch-user-abstraction!
        :fetch-portfolio! fetch-portfolio!
        :fetch-user-fees! fetch-user-fees!
        :fetch-and-merge-funding-history! fetch-and-merge-funding-history!
        :ensure-perp-dexs! ensure-perp-dexs!
        :stage-b-account-bootstrap! stage-b-account-bootstrap!
        :startup-stream-backfill-delay-ms startup-stream-backfill-delay-ms
        :startup-funding-request-opts funding-request-opts
        :resolve-current-address account-context/effective-account-address
        :log-fn log-fn})))))

(defn- refresh-current-route!
  [store dispatch! new-address]
  (when (fn? dispatch!)
    (when-let [effects (seq (route-refresh/current-route-refresh-effects
                             @store
                             new-address))]
      (dispatch! store nil effects))))

(defn install-address-handlers!
  [{:keys [store
           bootstrap-account-data!
           init-with-webdata2!
           dispatch!
           add-handler!
           remove-handler!
           sync-current-address!
           stop-watching!
           create-user-handler
           subscribe-user!
           unsubscribe-user!
           subscribe-webdata2!
           unsubscribe-webdata2!
           address-handler-reify
           sync-current-address-on-install?
           address-handler-name]
    :or {address-handler-reify reify-address-handler
         sync-current-address-on-install? true
         address-handler-name default-address-handler-name}
    :as deps}]
  (when (fn? stop-watching!)
    (stop-watching! store))
  (when (fn? remove-handler!)
    (doseq [handler-name [default-webdata2-handler-name
                          default-user-handler-name
                          address-handler-name]]
      (remove-handler! handler-name)))
  ;; Note: WebData2 subscriptions are managed by address-watcher.
  (init-with-webdata2! store subscribe-webdata2! unsubscribe-webdata2!)
  (add-handler! (create-user-handler subscribe-user! unsubscribe-user!))
  (add-handler!
   (address-handler-reify
    (fn [new-address]
      (if new-address
        (bootstrap-account-data! new-address)
        (clear-disconnected-account-state! deps))
      (refresh-current-route! store dispatch! new-address))
    address-handler-name))
  ;; Ensure already-connected wallets are handled after handlers are in place.
  (when sync-current-address-on-install?
    (sync-current-address! store)))

(defn reload-address-handlers!
  [deps]
  (install-address-handlers!
   (assoc deps :sync-current-address-on-install? false)))

(defn start-critical-bootstrap!
  [{:keys [store
           fetch-asset-contexts!
           fetch-asset-selector-markets!
           mark-performance!]}]
  ;; Keep first-load trade bootstrap narrow, but still hydrate the bootstrap
  ;; selector metadata required by balances/account views.
  (-> (js/Promise.all
       (clj->js [(fetch-asset-contexts! store {:priority :high})
                 (fetch-asset-selector-markets! store {:phase :bootstrap})]))
      (.finally
       (fn []
         (mark-performance! "app:critical-data:ready")))))

(defn- parse-optional-number
  [value]
  (cond
    (number? value) value
    (string? (some-> value str str/trim))
    (let [parsed (js/parseFloat value)]
      (when-not (js/isNaN parsed)
        parsed))
    :else nil))

(defn- incomplete-active-perp-market?
  [store]
  (let [state @store
        active-market (:active-market state)]
    (and (= :perp (:market-type active-market))
         (nil? (parse-optional-number (:maxLeverage active-market))))))

(defn- skip-deferred-bootstrap?
  [store]
  (and (true? (get-in @store [:asset-selector :cache-hydrated?]))
       (not (incomplete-active-perp-market? store))))

(defn run-deferred-bootstrap!
  [{:keys [store fetch-asset-selector-markets! mark-performance!]}]
  (let [bootstrap-promise (if (skip-deferred-bootstrap? store)
                            ;; Restored selector markets are enough for immediate trade rendering.
                            ;; Keep the full fan-out on demand when the selector is opened.
                            (js/Promise.resolve nil)
                            (fetch-asset-selector-markets! store {:phase :full}))]
    (-> bootstrap-promise
        (.finally
         (fn []
           (mark-performance! "app:full-bootstrap:ready"))))))

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
           init-candles!
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
  (init-candles! store)
  (init-orderbook! store)
  (init-trades! store)
  (init-user-ws! store)
  (init-webdata2! store)
  ;; Ensure active-asset market streams are requested on startup.
  (when-let [asset (:active-asset @store)]
    (dispatch! store nil [[:actions/subscribe-to-asset asset]]))
  ;; Keep startup route refreshes scoped to the actual current route only.
  (refresh-current-route! store dispatch! nil)
  (install-address-handlers!)
  ;; Keep startup scoped to the active trade route. Full selector-market expansion
  ;; off the critical path, but restore the prefetch as deferred idle work so the
  ;; selector is usually warm by the time the user opens it.
  (start-critical-bootstrap!)
  (when (fn? schedule-deferred-bootstrap!)
    (schedule-deferred-bootstrap!)))
