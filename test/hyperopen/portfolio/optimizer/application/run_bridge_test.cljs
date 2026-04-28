(ns hyperopen.portfolio.optimizer.application.run-bridge-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.portfolio.optimizer.application.run-bridge :as run-bridge]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.portfolio.optimizer.infrastructure.worker-client :as worker-client]
            [hyperopen.system :as system]))

(use-fixtures :each
  (fn [f]
    (reset! run-bridge/last-run-request nil)
    (f)
    (reset! run-bridge/last-run-request nil)))

(deftest request-run-posts-worker-message-and-preserves-existing-success-test
  (let [posted (atom [])
        store (atom {:portfolio {:optimizer {:last-successful-run
                                             (fixtures/sample-minimal-last-successful-run
                                              {:request-signature {:seed 0}
                                               :result {:old? true}})
                                             :run-state {:status :idle}}}})
        fake-worker #js {}]
    (set! (.-postMessage fake-worker)
          (fn [message]
            (swap! posted conj (js->clj message :keywordize-keys true))))
    (with-redefs [system/store store
                  worker-client/optimizer-worker fake-worker
                  run-bridge/next-run-id (fn [] "run-1")]
      (is (= "run-1"
             (run-bridge/request-run! {:request {:scenario-id "scenario-1"}
                                       :request-signature {:seed 1}
                                       :computed-at-ms 100})))
      (is (= [{:id "run-1"
               :type "run-optimizer"
               :payload {:scenario-id "scenario-1"}}]
             @posted))
      (is (= {:status :running
              :run-id "run-1"
              :scenario-id "scenario-1"
              :request-signature {:seed 1}
              :started-at-ms 100
              :error nil}
             (get-in @store [:portfolio :optimizer :run-state])))
      (is (= {:status :solved
              :old? true}
             (get-in @store [:portfolio :optimizer :last-successful-run :result]))))))

(deftest request-run-dedupes-identical-in-flight-signature-test
  (let [posted (atom [])
        store (atom {:portfolio {:optimizer {:run-state {:status :running
                                                         :run-id "run-1"
                                                         :request-signature {:seed 1}}}}})
        fake-worker #js {}]
    (set! (.-postMessage fake-worker)
          (fn [message]
            (swap! posted conj message)))
    (with-redefs [system/store store
                  worker-client/optimizer-worker fake-worker
                  run-bridge/next-run-id (fn [] "run-2")]
      (reset! run-bridge/last-run-request {:request-signature {:seed 1}
                                           :run-id "run-1"})
      (is (nil? (run-bridge/request-run! {:request {:scenario-id "scenario-1"}
                                          :request-signature {:seed 1}
                                          :computed-at-ms 101})))
      (is (empty? @posted))
      (is (= "run-1"
             (get-in @store [:portfolio :optimizer :run-state :run-id]))))))

(deftest request-run-uses-explicit-runtime-store-when-provided-test
  (let [posted (atom [])
        explicit-store (atom {:portfolio {:optimizer {:run-state {:status :idle}}}})
        default-store (atom {:portfolio {:optimizer {:run-state {:status :default}}}})
        fake-worker #js {}]
    (set! (.-postMessage fake-worker)
          (fn [message]
            (swap! posted conj (js->clj message :keywordize-keys true))))
    (with-redefs [system/store default-store
                  worker-client/optimizer-worker fake-worker
                  run-bridge/next-run-id (fn [] "run-explicit")]
      (is (= "run-explicit"
             (run-bridge/request-run! {:request {:scenario-id "scenario-explicit"}
                                       :request-signature {:seed :explicit}
                                       :computed-at-ms 111
                                       :store explicit-store})))
      (is (= :running
             (get-in @explicit-store [:portfolio :optimizer :run-state :status])))
      (is (= :default
             (get-in @default-store [:portfolio :optimizer :run-state :status])))
      (is (= [{:id "run-explicit"
               :type "run-optimizer"
               :payload {:scenario-id "scenario-explicit"}}]
             @posted)))))

(deftest worker-result-updates-last-successful-run-and-clears-draft-dirty-flag-test
  (let [store (atom {:portfolio {:optimizer {:draft {:metadata {:dirty? true}}
                                             :run-state {:status :running
                                                         :run-id "run-1"
                                                         :scenario-id "scenario-1"
                                                         :request-signature {:seed 1}}
                                             :last-successful-run {:result {:old? true}}}}})]
    (with-redefs [system/store store]
      (run-bridge/handle-worker-message! {:id "run-1"
                                          :type "optimizer-result"
                                          :payload {:status :solved
                                                    :scenario-id "scenario-1"}}
                                         {:computed-at-ms 200})
      (is (= {:status :succeeded
              :run-id "run-1"
              :scenario-id "scenario-1"
              :request-signature {:seed 1}
              :completed-at-ms 200
              :error nil}
             (get-in @store [:portfolio :optimizer :run-state])))
      (is (= {:request-signature {:seed 1}
              :result (fixtures/sample-minimal-solved-result
                       {:scenario-id "scenario-1"})
              :computed-at-ms 200}
             (get-in @store [:portfolio :optimizer :last-successful-run])))
      (is (= :computed
             (get-in @store [:portfolio :optimizer :active-scenario :status])))
      (is (false?
           (get-in @store [:portfolio :optimizer :draft :metadata :dirty?]))))))

(deftest normalized-worker-result-with-string-status-updates-successful-run-test
  (let [store (atom {:portfolio {:optimizer {:run-state {:status :running
                                                         :run-id "run-1"
                                                         :scenario-id "scenario-1"
                                                         :request-signature {:seed 1}}}}})
        message (worker-client/normalize-worker-message
                 (clj->js {:id "run-1"
                           :type "optimizer-result"
                           :payload {:status "solved"
                                     :scenario-id "scenario-1"
                                     :solver {:strategy "single-qp"}
                                     :return-decomposition-by-instrument
                                     {"perp:BTC" {:return-component 0.12
                                                  :funding-component 0.04
                                                  :funding-source "market-funding-history"}}
                                     :current-weights-by-instrument
                                     {"spot:PURR/USDC" 0.2}
                                     :target-weights-by-instrument
                                     {"perp:BTC" 0.35}
                                     :warnings [{:code "missing-funding-history"}]}}))]
    (with-redefs [system/store store]
      (run-bridge/handle-worker-message! message {:computed-at-ms 250})
      (is (= :succeeded
             (get-in @store [:portfolio :optimizer :run-state :status])))
      (is (= {:status :solved
              :scenario-id "scenario-1"
              :solver {:strategy :single-qp}
              :return-decomposition-by-instrument
              {"perp:BTC" {:return-component 0.12
                           :funding-component 0.04
                           :funding-source :market-funding-history}}
              :current-weights-by-instrument {"spot:PURR/USDC" 0.2}
              :target-weights-by-instrument {"perp:BTC" 0.35}
              :warnings [{:code :missing-funding-history}]}
             (get-in @store [:portfolio :optimizer :last-successful-run :result]))))))

(deftest worker-result-ignores-stale-run-id-and-route-changes-test
  (let [state {:portfolio {:optimizer {:active-scenario {:loaded-id "scenario-2"}
                                       :run-state {:status :running
                                                   :run-id "run-2"
                                                   :scenario-id "scenario-2"
                                                   :request-signature {:seed 2}}
                                       :last-successful-run {:result {:old? true}}}}}
        store (atom state)]
    (with-redefs [system/store store]
      (run-bridge/handle-worker-message! {:id "run-1"
                                          :type "optimizer-result"
                                          :payload {:status :solved
                                                    :scenario-id "scenario-1"}}
                                         {:computed-at-ms 300})
      (is (= state @store)))))

(deftest worker-error-preserves-last-successful-result-test
  (let [store (atom {:portfolio {:optimizer {:run-state {:status :running
                                                         :run-id "run-1"
                                                         :scenario-id "scenario-1"
                                                         :request-signature {:seed 1}}
                                             :last-successful-run {:result {:old? true}}}}})]
    (with-redefs [system/store store]
      (run-bridge/handle-worker-message! {:id "run-1"
                                          :type "optimizer-error"
                                          :payload {:code :boom}}
                                         {:computed-at-ms 400})
      (is (= {:status :failed
              :run-id "run-1"
              :scenario-id "scenario-1"
              :request-signature {:seed 1}
              :completed-at-ms 400
              :error {:code :boom}}
             (get-in @store [:portfolio :optimizer :run-state])))
      (is (= {:old? true}
             (get-in @store [:portfolio :optimizer :last-successful-run :result]))))))
