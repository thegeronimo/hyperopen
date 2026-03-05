(ns hyperopen.core-bootstrap.test-support.fixtures
  (:require [hyperopen.app.bootstrap :as app-bootstrap]
            [hyperopen.core :as app-core]
            [hyperopen.runtime.state :as runtime-state]))

(defn reset-startup-runtime! []
  (swap! runtime-state/runtime
         assoc
         :startup {:deferred-scheduled? false
                   :bootstrapped-address nil
                   :summary-logged? false}))

(def runtime-bootstrap-fixture
  {:before (fn []
             (app-bootstrap/ensure-runtime-bootstrapped!
              runtime-state/runtime
              #(app-bootstrap/bootstrap-runtime!
                {:runtime runtime-state/runtime
                 :store app-core/store})))
   :after (fn [])})

(def per-test-runtime-fixture
  {:before (fn []
             (reset-startup-runtime!)
             (swap! app-core/store assoc :active-asset nil))
   :after (fn []
            (reset-startup-runtime!))})

(defn clear-wallet-copy-feedback-timeout! []
  (when-let [timeout-id (get-in @runtime-state/runtime [:timeouts :wallet-copy])]
    (js/clearTimeout timeout-id)
    (swap! runtime-state/runtime assoc-in [:timeouts :wallet-copy] nil)))

(defn clear-order-feedback-toast-timeout! []
  (let [timeout-state (get-in @runtime-state/runtime [:timeouts :order-toast])
        timeout-ids (cond
                      (map? timeout-state) (vals timeout-state)
                      (some? timeout-state) [timeout-state]
                      :else [])]
    (doseq [timeout-id timeout-ids]
      (js/clearTimeout timeout-id))
    (swap! runtime-state/runtime assoc-in [:timeouts :order-toast] {})))
