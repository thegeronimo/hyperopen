(ns hyperopen.app.startup
  (:require [hyperopen.account.history.funding-actions :as funding-actions]
            [hyperopen.account.history.order-actions :as order-actions]
            [hyperopen.account.history.surface-actions :as surface-actions]
            [hyperopen.asset-selector.settings :as asset-selector-settings]
            [hyperopen.chart.settings :as chart-settings]
            [nexus.registry :as nxr]
            [hyperopen.orderbook.settings :as orderbook-settings]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.router :as router]
            [hyperopen.runtime.action-adapters :as runtime-action-adapters]
            [hyperopen.runtime.effect-adapters :as runtime-effect-adapters]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.startup.collaborators :as startup-collaborators]
            [hyperopen.startup.init :as startup-init]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.startup.runtime :as startup-runtime-lib]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.ui.preferences :as ui-preferences]
            [hyperopen.vaults.infrastructure.persistence :as vault-persistence]
            [hyperopen.wallet.core :as wallet]))

(defn mark-performance!
  [mark-name]
  (startup-runtime-lib/mark-performance! mark-name))

(defn schedule-idle-or-timeout!
  [f]
  (startup-runtime-lib/schedule-idle-or-timeout! runtime-state/deferred-bootstrap-delay-ms f))

(defn schedule-post-render-startup!
  [f]
  (startup-runtime-lib/schedule-post-render-startup! f))

(defn startup-base-deps
  [{:keys [runtime store api]}]
  (startup-collaborators/startup-base-deps
   {:runtime runtime
    :store store
    :api api
    :icon-service-worker-path runtime-state/icon-service-worker-path
    :per-dex-stagger-ms runtime-state/per-dex-stagger-ms
    :startup-stream-backfill-delay-ms runtime-state/startup-stream-backfill-delay-ms
    :startup-funding-history-lookback-ms runtime-state/startup-funding-history-lookback-ms
    :schedule-idle-or-timeout! schedule-idle-or-timeout!
    :mark-performance! mark-performance!}))

(defn schedule-startup-summary-log!
  [system]
  (startup-runtime-lib/schedule-startup-summary-log!
   (assoc (startup-base-deps system)
          :delay-ms runtime-state/startup-summary-delay-ms)))

(defn register-icon-service-worker!
  [system]
  (startup-runtime-lib/register-icon-service-worker!
   (startup-base-deps system)))

(defn stage-b-account-bootstrap!
  [system address dexs]
  (startup-runtime-lib/stage-b-account-bootstrap!
   (assoc (startup-base-deps system)
          :address address
          :dexs dexs)))

(defn bootstrap-account-data!
  [system address]
  (let [base-deps (startup-base-deps system)
        stage-b-account-bootstrap-fn
        (or (:stage-b-account-bootstrap! base-deps)
            (fn [bootstrap-address dexs]
              (stage-b-account-bootstrap! system bootstrap-address dexs)))]
    (startup-runtime-lib/bootstrap-account-data!
     (assoc base-deps
            :address address
            :stage-b-account-bootstrap! stage-b-account-bootstrap-fn))))

(defn install-address-handlers!
  [system]
  (startup-runtime-lib/install-address-handlers!
   (assoc (startup-base-deps system)
          :bootstrap-account-data!
          (fn [address]
            (bootstrap-account-data! system address)))))

(defn start-critical-bootstrap!
  [system]
  (startup-runtime-lib/start-critical-bootstrap!
   (startup-base-deps system)))

(defn run-deferred-bootstrap!
  [system]
  (startup-runtime-lib/run-deferred-bootstrap!
   (startup-base-deps system)))

(defn schedule-deferred-bootstrap!
  [system]
  (startup-runtime-lib/schedule-deferred-bootstrap!
   (assoc (startup-base-deps system)
          :run-deferred-bootstrap!
          (fn []
            (run-deferred-bootstrap! system)))))

(defn initialize-remote-data-streams!
  [system]
  (startup-runtime-lib/initialize-remote-data-streams!
   (assoc (startup-base-deps system)
          :install-address-handlers!
          (fn []
            (install-address-handlers! system))
          :start-critical-bootstrap!
          (fn []
            (start-critical-bootstrap! system))
          :schedule-deferred-bootstrap!
          (fn []
            (schedule-deferred-bootstrap! system)))))

(defn reload-runtime-bindings!
  [system]
  (let [base-deps (startup-base-deps system)
        store (:store base-deps)
        dispatch! (:dispatch! base-deps)]
    ;; Rebind listener-held handlers without replaying startup fetch/bootstrap work.
    (when (and (some? store) (fn? dispatch!))
      (startup-runtime-lib/install-asset-selector-shortcuts!
       {:store store
        :dispatch! dispatch!})
      (startup-runtime-lib/install-position-tpsl-clickaway!
       {:store store
        :dispatch! dispatch!}))
    (doseq [init-fn [(:init-active-ctx! base-deps)
                     (:init-candles! base-deps)
                     (:init-orderbook! base-deps)
                     (:init-trades! base-deps)
                     (:init-user-ws! base-deps)
                     (:init-webdata2! base-deps)]]
      (when (fn? init-fn)
        (init-fn store)))))

(defn- route-change-effects
  [state path]
  (let [normalized-path (router/normalize-path path)]
    (cond-> []
      (some? (route-modules/route-module-id normalized-path))
      (conj [:effects/load-route-module normalized-path])

      (and (router/trade-route? normalized-path)
           (not (trade-modules/trade-chart-ready? state))
           (not (trade-modules/trade-chart-loading? state)))
      (conj [:effects/load-trade-chart-module]))))

(defn init!
  [system]
  (let [base-deps (startup-base-deps system)]
    (startup-init/init!
     (merge
      base-deps
      {:default-startup-runtime-state startup-runtime-lib/default-startup-runtime-state
       :schedule-startup-summary-log! (fn []
                                        (schedule-startup-summary-log! system))
       :restore-ui-font-preference! ui-preferences/restore-ui-font-preference!
       :restore-ui-locale-preference! startup-restore/restore-ui-locale-preference!
       :restore-asset-selector-sort-settings! asset-selector-settings/restore-asset-selector-sort-settings!
       :restore-chart-options! chart-settings/restore-chart-options!
       :restore-orderbook-ui! orderbook-settings/restore-orderbook-ui!
       :restore-portfolio-summary-time-range! portfolio-actions/restore-portfolio-summary-time-range!
       :restore-vaults-snapshot-range! vault-persistence/restore-vaults-snapshot-range!
       :restore-agent-storage-mode! startup-restore/restore-agent-storage-mode!
       :restore-trading-settings! startup-restore/restore-trading-settings!
       :restore-spectate-mode-preferences! startup-restore/restore-spectate-mode-preferences!
       :restore-spectate-mode-url! startup-restore/restore-spectate-mode-url!
       :restore-trade-route-tab! startup-restore/restore-trade-route-tab!
       :restore-active-asset! runtime-effect-adapters/restore-active-asset!
       :restore-asset-selector-markets-cache! runtime-effect-adapters/restore-asset-selector-markets-cache!
       :restore-open-orders-sort-settings! surface-actions/restore-open-orders-sort-settings!
       :restore-funding-history-pagination-settings! funding-actions/restore-funding-history-pagination-settings!
       :restore-trade-history-pagination-settings! order-actions/restore-trade-history-pagination-settings!
       :restore-order-history-pagination-settings! order-actions/restore-order-history-pagination-settings!
       :set-on-connected-handler! wallet/set-on-connected-handler!
       :handle-wallet-connected runtime-action-adapters/handle-wallet-connected
       :init-wallet! wallet/init-wallet!
       :init-router! (fn [startup-store]
                       (router/init!
                        startup-store
                        {:on-route-change
                         (fn [path]
                           (let [effects (route-change-effects @startup-store path)]
                             (when (seq effects)
                               (nxr/dispatch startup-store nil effects))))}))
       :install-asset-selector-shortcuts! (fn []
                                            (startup-runtime-lib/install-asset-selector-shortcuts!
                                             {:store (:store base-deps)
                                              :dispatch! (:dispatch! base-deps)}))
       :install-position-tpsl-clickaway! (fn []
                                           (startup-runtime-lib/install-position-tpsl-clickaway!
                                            {:store (:store base-deps)
                                             :dispatch! (:dispatch! base-deps)}))
       :register-icon-service-worker! (fn []
                                        (register-icon-service-worker! system))
       :initialize-remote-data-streams! (fn []
                                          (initialize-remote-data-streams! system))
       :schedule-post-render-startup! schedule-post-render-startup!
       :kick-render! (fn [runtime-store]
                       (swap! runtime-store identity))}))))
