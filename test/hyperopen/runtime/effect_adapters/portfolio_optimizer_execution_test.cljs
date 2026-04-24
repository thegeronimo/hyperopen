(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-execution-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]
            [hyperopen.test-support.async :as async-support]))

(def ready-plan
  {:scenario-id "scn_submit"
   :status :ready
   :execution-disabled? false
   :summary {:ready-count 1
             :blocked-count 0}
   :rows [{:row-id "perp:BTC"
           :instrument-id "perp:BTC"
           :instrument-type :perp
           :coin "BTC"
           :status :ready
           :side :buy
           :price 100
           :quantity 0.25
           :delta-notional-usd 25
           :intent {:kind :perp-order
                    :instrument-id "perp:BTC"
                    :side :buy
                    :quantity 0.25
                    :order-type :market
                    :reduce-only? false}}]})

(deftest execute-portfolio-optimizer-plan-effect-records-submitted-ledger-test
  (async done
    (let [submitted (atom [])
          dispatches (atom [])
          ticks (atom [1000 1100])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          store (atom {:wallet {:address address
                                :agent {:status :ready}}
                       :asset-selector {:market-by-key
                                        {"perp:BTC" {:coin "BTC"
                                                     :market-type :perp
                                                     :asset-id 0
                                                     :szDecimals 4}}}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "scn_submit"
                                                      :status :saved}
                                    :execution-modal {:open? true
                                                      :submitting? true
                                                      :plan ready-plan}}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn []
                      (let [t (first @ticks)]
                        (swap! ticks rest)
                        t))
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store submitted-address action]
                      (swap! submitted conj [submitted-address action])
                      (js/Promise.resolve
                       {:status "ok"
                        :response {:data {:statuses ["success"]}}}))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [runtime-store ctx effects]
                      (swap! dispatches conj [runtime-store ctx effects]))]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
             ready-plan)
            (.then (fn [ledger]
                     (is (= :executed (:status ledger)))
                     (is (= :submitted (get-in ledger [:rows 0 :status])))
                     (is (= "order" (get-in @submitted [0 1 :type])))
                     (is (= :executed
                            (get-in @store [:portfolio :optimizer :execution :status])))
                     (is (= :executed
                            (get-in @store [:portfolio :optimizer :active-scenario :status])))
                     (is (= false
                            (get-in @store
                                    [:portfolio :optimizer :execution-modal :submitting?])))
                     (is (= [[store nil [[:actions/load-user-data address]
                                          [:actions/refresh-order-history]]]]
                            @dispatches))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest execute-portfolio-optimizer-plan-effect-records-partial-when-blocked-rows-remain-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          plan (assoc ready-plan
                      :status :partially-blocked
                      :summary {:ready-count 1
                                :blocked-count 1}
                      :rows (conj (:rows ready-plan)
                                  {:row-id "spot:PURR"
                                   :instrument-id "spot:PURR"
                                   :instrument-type :spot
                                   :status :blocked
                                   :reason :spot-read-only}))
          store (atom {:wallet {:address address
                                :agent {:status :ready}}
                       :asset-selector {:market-by-key
                                        {"perp:BTC" {:coin "BTC"
                                                     :market-type :perp
                                                     :asset-id 0
                                                     :szDecimals 4}}}
                       :portfolio {:optimizer {:execution-modal {:open? true
                                                                 :submitting? true
                                                                 :plan plan}}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (constantly 1000)
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store _address _action]
                      (js/Promise.resolve
                       {:status "ok"
                        :response {:data {:statuses ["success"]}}}))
                    portfolio-optimizer-adapters/*dispatch!* (fn [& _])]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
             plan)
            (.then (fn [ledger]
                     (is (= :partially-executed (:status ledger)))
                     (is (= :submitted (get-in ledger [:rows 0 :status])))
                     (is (= :blocked (get-in ledger [:rows 1 :status])))
                     (is (= :partially-executed
                            (get-in @store [:portfolio :optimizer :execution :status])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))
