(ns hyperopen.startup.runtime
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hyperopen.account.surface-service :as account-surface-service]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.platform :as platform]
            [hyperopen.startup.runtime.account-state :as account-state]
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
(def ^:private default-route-aware-deferred-delay-ms
  1200)

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

(defn- normalize-delay-ms
  [value fallback]
  (if (number? value)
    (max 0 (js/Math.floor value))
    fallback))

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

(defn clear-disconnected-account-state!
  [{:keys [store
           address
           unsubscribe-user!
           stop-user-vault-equity-poller!]
    :as deps}]
  (when address
    (when (fn? unsubscribe-user!)
      (unsubscribe-user! address)))
  (when (fn? stop-user-vault-equity-poller!)
    (stop-user-vault-equity-poller!))
  (swap-startup-state! deps assoc :bootstrapped-address nil)
  (swap! store
         (fn [state]
           (-> state
               account-state/reset-account-surface-state
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
                          (cond
                            stop-spectate-shortcut?
                            (dispatch! store nil [[:actions/stop-spectate-mode]])

                            :else
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

(def ^:private clickaway-surface-selectors
  ["[data-position-tpsl-surface='true']" "[data-position-tpsl-trigger='true']"
   "[data-position-reduce-surface='true']" "[data-position-reduce-trigger='true']"
   "[data-position-margin-surface='true']" "[data-position-margin-trigger='true']"
   "[data-spectate-mode-surface='true']" "[data-spectate-mode-trigger='true']"
   "[data-trade-blotter-surface='true']"])

(defn- within-position-overlay-surface? [target]
  (boolean (some #(some-> target (.closest %)) clickaway-surface-selectors)))

(defn- expanded-trade-blotter-toast-id
  [state]
  (some (fn [{:keys [expanded? id toast-surface]}]
          (when (and id (true? expanded?) (= :trade-confirmation toast-surface))
            id))
        (get-in state [:ui :toasts])))

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
                            trade-blotter-id (expanded-trade-blotter-toast-id @store)
                            any-open? (or tpsl-open? reduce-open? margin-open? spectate-mode-open? trade-blotter-id)]
                        (when any-open?
                          (let [target (event-target-with-closest event)]
                            (when-not (within-position-overlay-surface? target)
                              (let [close-actions (cond-> []
                                                    tpsl-open? (conj [:actions/close-position-tpsl-modal])
                                                    reduce-open? (conj [:actions/close-position-reduce-popover])
                                                    margin-open? (conj [:actions/close-position-margin-modal])
                                                    spectate-mode-open? (conj [:actions/close-spectate-mode-modal])
                                                    trade-blotter-id (conj [:actions/dismiss-order-feedback-toast trade-blotter-id]))]
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
  [{:keys [store address fetch-historical-orders!
           startup-funding-history-lookback-ms
           non-visible-account-bootstrap-delay-ms
           stop-user-vault-equity-poller!]
    :as deps}]
  (when address
    (when-not (= address (:bootstrapped-address (startup-state deps)))
      (let [funding-request-opts
            (startup-funding-history-request-opts
             startup-funding-history-lookback-ms)
            portfolio-performance-route?
            (account-surface-service/portfolio-performance-metrics-route? @store)]
        (swap-startup-state! deps assoc :bootstrapped-address address)
        (when (fn? stop-user-vault-equity-poller!)
          (stop-user-vault-equity-poller!))
        (swap! store account-state/reset-account-surface-state)
        (when-not portfolio-performance-route?
          (prefetch-order-history! {:store store
                                    :fetch-historical-orders! fetch-historical-orders!}))
        (account-surface-service/bootstrap-account-surfaces!
         (cond-> (assoc deps
                        :startup-funding-request-opts funding-request-opts
                        :resolve-current-address account-context/effective-account-address)
           portfolio-performance-route?
           (assoc :defer-non-visible-account-surfaces? true
                  :non-visible-account-bootstrap-delay-ms
                  (normalize-delay-ms non-visible-account-bootstrap-delay-ms
                                      default-route-aware-deferred-delay-ms)
                  :set-timeout-fn
                  (fn [f delay-ms]
                    (platform/set-timeout! f delay-ms)))))))))

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
           dispatch!
           add-handler!
           remove-handler!
           sync-current-address!
           stop-watching!
           start-watching!
           create-user-handler
           subscribe-user!
           unsubscribe-user!
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
    ;; default-webdata2-handler-name stays in the cleanup list so hot reloads
    ;; drop any handler installed before the webData2 stream was retired.
    (doseq [handler-name [default-webdata2-handler-name
                          default-user-handler-name
                          address-handler-name]]
      (remove-handler! handler-name)))
  ;; The provider removed the webData2 subscription; the base-dex book now
  ;; arrives via the dex "" clearinghouseState stream managed by the user
  ;; subscription handler below.
  (when (fn? start-watching!)
    (start-watching! store))
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
  (-> (if (account-surface-service/portfolio-performance-metrics-route? @store)
        (js/Promise.resolve nil)
        (-> (js/Promise.all
             (clj->js [(fetch-asset-contexts! store {:priority :high})
                       (fetch-asset-selector-markets! store {:phase :bootstrap})]))
            (.then
             (fn [results]
               ;; The bootstrap catalog is perp-only. A cold landing on a
               ;; non-perp pair (spot/outcome trade route) resolves no
               ;; :active-market from it, and the trade form needs the market's
               ;; params — chase the full catalog immediately instead of
               ;; waiting for a demand path.
               (let [state @store]
                 (when (and (some? (:active-asset state))
                            (nil? (:active-market state)))
                   (fetch-asset-selector-markets! store {:phase :full})))
               results))))
      (.finally
       (fn []
         (mark-performance! "app:critical-data:ready")))))

(defn run-deferred-bootstrap!
  [{:keys [mark-performance!]}]
  (let [bootstrap-promise
        ;; Startup no longer owns full selector-market expansion. Keep the
        ;; deferred completion mark, but let explicit demand paths request the
        ;; full catalog when the user opens the selector or enters a route that
        ;; needs it.
        (js/Promise.resolve nil)]
    (-> bootstrap-promise
        (.finally
         (fn []
           (mark-performance! "app:full-bootstrap:ready"))))))

(defn schedule-deferred-bootstrap!
  [{:keys [store
           schedule-idle-or-timeout!
           run-deferred-bootstrap!
           deferred-bootstrap-delay-ms]
    :as deps}]
  (when-not (:deferred-scheduled? (startup-state deps))
    (swap-startup-state! deps assoc :deferred-scheduled? true)
    (if (and store
             (account-surface-service/portfolio-performance-metrics-route? @store))
      (platform/set-timeout! run-deferred-bootstrap!
                             (normalize-delay-ms deferred-bootstrap-delay-ms
                                                 default-route-aware-deferred-delay-ms))
      (schedule-idle-or-timeout! run-deferred-bootstrap!))))

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
           init-subscription-errors!
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
  (when (fn? init-subscription-errors!)
    (init-subscription-errors!))
  ;; Ensure active-asset market streams are requested on startup.
  (when-let [asset (:active-asset @store)]
    (dispatch! store nil [[:actions/subscribe-to-asset asset]]))
  ;; Keep startup route refreshes scoped to the actual current route only.
  (refresh-current-route! store dispatch! nil)
  (install-address-handlers!)
  ;; Keep startup scoped to the active route. Startup only hydrates the
  ;; bootstrap selector data; full selector-market expansion belongs to
  ;; explicit demand paths such as opening the selector or entering a route
  ;; that already requests the full catalog.
  (start-critical-bootstrap!)
  (when (fn? schedule-deferred-bootstrap!)
    (schedule-deferred-bootstrap!)))
