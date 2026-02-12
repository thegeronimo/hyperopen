(ns hyperopen.startup.composition
  (:require [hyperopen.startup.init :as startup-init]
            [hyperopen.startup.runtime :as startup-runtime-lib]))

(defn schedule-startup-summary-log!
  [{:keys [startup-runtime
           runtime
           store
           get-request-stats
           delay-ms
           log-fn]}]
  (startup-runtime-lib/schedule-startup-summary-log!
   {:startup-runtime startup-runtime
    :runtime runtime
    :store store
    :get-request-stats get-request-stats
    :delay-ms delay-ms
    :log-fn log-fn}))

(defn register-icon-service-worker!
  [{:keys [icon-service-worker-path log-fn]}]
  (startup-runtime-lib/register-icon-service-worker!
   {:icon-service-worker-path icon-service-worker-path
    :log-fn log-fn}))

(defn stage-b-account-bootstrap!
  [{:keys [store
           per-dex-stagger-ms
           fetch-frontend-open-orders!
           fetch-clearinghouse-state!]}
   address
   dexs]
  (startup-runtime-lib/stage-b-account-bootstrap!
   {:store store
    :address address
    :dexs dexs
    :per-dex-stagger-ms per-dex-stagger-ms
    :fetch-frontend-open-orders! fetch-frontend-open-orders!
    :fetch-clearinghouse-state! fetch-clearinghouse-state!}))

(defn bootstrap-account-data!
  [{:keys [startup-runtime
           runtime
           store
           fetch-frontend-open-orders!
           fetch-user-fills!
           fetch-spot-clearinghouse-state!
           fetch-user-abstraction!
           fetch-and-merge-funding-history!
           ensure-perp-dexs!
           stage-b-account-bootstrap!
           log-fn]}
   address]
  (startup-runtime-lib/bootstrap-account-data!
   {:startup-runtime startup-runtime
    :runtime runtime
    :store store
    :address address
    :fetch-frontend-open-orders! fetch-frontend-open-orders!
    :fetch-user-fills! fetch-user-fills!
    :fetch-spot-clearinghouse-state! fetch-spot-clearinghouse-state!
    :fetch-user-abstraction! fetch-user-abstraction!
    :fetch-and-merge-funding-history! fetch-and-merge-funding-history!
    :ensure-perp-dexs! ensure-perp-dexs!
    :stage-b-account-bootstrap! stage-b-account-bootstrap!
    :log-fn log-fn}))

(defn install-address-handlers!
  [{:keys [store
           startup-runtime
           runtime
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
           address-handler-name]}]
  (startup-runtime-lib/install-address-handlers!
   {:store store
    :startup-runtime startup-runtime
    :runtime runtime
    :bootstrap-account-data! bootstrap-account-data!
    :init-with-webdata2! init-with-webdata2!
    :add-handler! add-handler!
    :sync-current-address! sync-current-address!
    :create-user-handler create-user-handler
    :subscribe-user! subscribe-user!
    :unsubscribe-user! unsubscribe-user!
    :subscribe-webdata2! subscribe-webdata2!
    :unsubscribe-webdata2! unsubscribe-webdata2!
    :address-handler-reify address-handler-reify
    :address-handler-name address-handler-name}))

(defn start-critical-bootstrap!
  [{:keys [store
           fetch-asset-contexts!
           fetch-asset-selector-markets!
           mark-performance!]}]
  (startup-runtime-lib/start-critical-bootstrap!
   {:store store
    :fetch-asset-contexts! fetch-asset-contexts!
    :fetch-asset-selector-markets! fetch-asset-selector-markets!
    :mark-performance! mark-performance!}))

(defn run-deferred-bootstrap!
  [{:keys [store
           fetch-asset-selector-markets!
           mark-performance!]}]
  (startup-runtime-lib/run-deferred-bootstrap!
   {:store store
    :fetch-asset-selector-markets! fetch-asset-selector-markets!
    :mark-performance! mark-performance!}))

(defn schedule-deferred-bootstrap!
  [{:keys [startup-runtime
           runtime
           schedule-idle-or-timeout!
           run-deferred-bootstrap!]}]
  (startup-runtime-lib/schedule-deferred-bootstrap!
   {:startup-runtime startup-runtime
    :runtime runtime
    :schedule-idle-or-timeout! schedule-idle-or-timeout!
    :run-deferred-bootstrap! run-deferred-bootstrap!}))

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
  (startup-runtime-lib/initialize-remote-data-streams!
   {:store store
    :ws-url ws-url
    :log-fn log-fn
    :init-connection! init-connection!
    :init-active-ctx! init-active-ctx!
    :init-orderbook! init-orderbook!
    :init-trades! init-trades!
    :init-user-ws! init-user-ws!
    :init-webdata2! init-webdata2!
    :dispatch! dispatch!
    :install-address-handlers! install-address-handlers!
    :start-critical-bootstrap! start-critical-bootstrap!
    :schedule-deferred-bootstrap! schedule-deferred-bootstrap!}))

(defn init!
  [deps]
  (startup-init/init! deps))
