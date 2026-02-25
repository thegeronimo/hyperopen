(ns hyperopen.startup.wiring
  (:require [hyperopen.startup.collaborators :as startup-collaborators]
            [hyperopen.startup.composition :as startup-composition]
            [hyperopen.wallet.address-watcher :as address-watcher]))

(defn startup-base-deps
  [deps]
  (startup-collaborators/startup-base-deps deps))

(defn store-cache-watcher-deps
  [{:keys [persist-active-market-display!
           persist-asset-selector-markets-cache!]}]
  {:persist-active-market-display! persist-active-market-display!
   :persist-asset-selector-markets-cache! persist-asset-selector-markets-cache!})

(defn websocket-watcher-deps
  [{:keys [store
           runtime-view
           append-diagnostics-event!
           sync-websocket-health!
           on-websocket-connected!
           on-websocket-disconnected!]}]
  {:store store
   :runtime-view runtime-view
   :append-diagnostics-event! append-diagnostics-event!
   :sync-websocket-health! sync-websocket-health!
   :on-websocket-connected! on-websocket-connected!
   :on-websocket-disconnected! on-websocket-disconnected!})

(defn schedule-startup-summary-log!
  [base-deps startup-summary-delay-ms]
  (startup-composition/schedule-startup-summary-log!
   (assoc base-deps :delay-ms startup-summary-delay-ms)))

(defn register-icon-service-worker!
  [base-deps]
  (startup-composition/register-icon-service-worker! base-deps))

(defn stage-b-account-bootstrap!
  [base-deps address dexs]
  (startup-composition/stage-b-account-bootstrap!
   base-deps
   address
   dexs))

(defn bootstrap-account-data!
  [base-deps address stage-b-account-bootstrap-fn]
  (startup-composition/bootstrap-account-data!
   (assoc base-deps
          :stage-b-account-bootstrap! stage-b-account-bootstrap-fn)
   address))

(defn reify-address-handler
  [on-address-changed-fn handler-name]
  (reify address-watcher/IAddressChangeHandler
    (on-address-changed [_ _ new-address]
      (on-address-changed-fn new-address))
    (get-handler-name [_]
      handler-name)))

(defn install-address-handlers!
  [base-deps bootstrap-account-data-fn]
  (startup-composition/install-address-handlers!
   (assoc base-deps
          :bootstrap-account-data! bootstrap-account-data-fn
          :address-handler-reify reify-address-handler
          :address-handler-name "startup-account-bootstrap-handler")))

(defn start-critical-bootstrap!
  [base-deps]
  (startup-composition/start-critical-bootstrap! base-deps))

(defn run-deferred-bootstrap!
  [base-deps]
  (startup-composition/run-deferred-bootstrap! base-deps))

(defn schedule-deferred-bootstrap!
  [base-deps run-deferred-bootstrap-fn]
  (startup-composition/schedule-deferred-bootstrap!
   (assoc base-deps
          :run-deferred-bootstrap! run-deferred-bootstrap-fn)))

(defn initialize-remote-data-streams!
  [base-deps
   {:keys [install-address-handlers-fn
           start-critical-bootstrap-fn
           schedule-deferred-bootstrap-fn]}]
  (startup-composition/initialize-remote-data-streams!
   (assoc base-deps
          :install-address-handlers! install-address-handlers-fn
          :start-critical-bootstrap! start-critical-bootstrap-fn
          :schedule-deferred-bootstrap! schedule-deferred-bootstrap-fn)))
