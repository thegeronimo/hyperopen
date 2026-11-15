(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-execution-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
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
                     (is (= :success (get-in @store [:ui :toasts 0 :kind])))
                     (is (= "Execution complete" (get-in @store [:ui :toasts 0 :headline])))
                     (is (= [[store nil [[:actions/load-user-data address]
                                          [:actions/refresh-order-history]]]]
                            @dispatches))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest execute-portfolio-optimizer-plan-effect-records-deciphered-rejection-error-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          ticks (atom [1000 1100])
          store (atom {:wallet {:address address :agent {:status :ready}}
                       :asset-selector {:market-by-key
                                        {"perp:BTC" {:coin "BTC"
                                                     :market-type :perp
                                                     :asset-id 0
                                                     :szDecimals 4}}}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "scn_submit" :status :saved}
                                    :execution-modal {:open? true
                                                      :submitting? true
                                                      :plan ready-plan}}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn [] (let [t (first @ticks)] (swap! ticks rest) t))
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store _address _action]
                      (js/Promise.resolve
                       {:status "ok"
                        :response {:type "order"
                                   :data {:statuses [{:error "Order must have minimum value of $10. asset=110023"}]}}}))
                    portfolio-optimizer-adapters/*dispatch!* (fn [_ _ _] nil)]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil store ready-plan)
            (.then (fn [ledger]
                     (let [row (get-in ledger [:rows 0])]
                       (is (= :failed (:status row)))
                       (is (= "Order must have minimum value of $10. asset=110023"
                              (get-in row [:error :message])))
                       (is (map? (:response row))))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(defn- near?
  [expected actual]
  (and (number? actual)
       (< (js/Math.abs (- expected actual)) 0.0000001)))

(deftest execute-portfolio-optimizer-plan-effect-records-realized-slippage-from-fill-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          ticks (atom [1000 1100])
          store (atom {:wallet {:address address :agent {:status :ready}}
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
                    (fn [_store _address _action]
                      (js/Promise.resolve
                       {:status "ok"
                        :response {:data {:statuses [{:filled {:oid 7
                                                               :totalSz "0.25"
                                                               :avgPx "101"}}]}}}))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [_ _ _] nil)]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
             ready-plan)
            (.then (fn [ledger]
                     (let [realized (get-in ledger [:rows 0 :realized])]
                       (is (= :submitted (get-in ledger [:rows 0 :status])))
                       (is (near? 101 (:avg-px realized)))
                       (is (near? 100 (:slippage-bps realized)))
                       (is (near? 0.25 (:slippage-usd realized))))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(def passive-ready-plan
  {:scenario-id "scn_passive"
   :status :ready
   :execution-disabled? false
   :summary {:ready-count 1 :blocked-count 0}
   :rows [{:row-id "perp:THIN"
           :instrument-id "perp:THIN"
           :instrument-type :perp
           :coin "THIN"
           :status :ready
           :side :buy
           :price 0.004822
           :quantity 3000
           :delta-notional-usd 14.5
           :intent {:kind :perp-order
                    :instrument-id "perp:THIN"
                    :side :buy
                    :quantity 3000
                    :order-type :passive
                    :limit-bps -2
                    :reduce-only? false}}]})

(deftest execute-portfolio-optimizer-plan-effect-reprices-crossed-post-only-and-rests-test
  (async done
    (let [submitted (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          ticks (atom [1000 1100])
          store (atom {:wallet {:address address :agent {:status :ready}}
                       :asset-selector {:market-by-key
                                        {"perp:THIN" {:coin "THIN"
                                                      :market-type :perp
                                                      :asset-id 197
                                                      :szDecimals 0}}}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "scn_passive"
                                                      :status :saved}
                                    :execution-modal {:open? true
                                                      :submitting? true
                                                      :plan passive-ready-plan}}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn []
                      (let [t (first @ticks)]
                        (swap! ticks rest)
                        t))
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store _address action]
                      (swap! submitted conj action)
                      (js/Promise.resolve
                       (if (= 1 (count @submitted))
                         {:status "ok"
                          :response {:type "order"
                                     :data {:statuses [{:error "Post only order would have immediately matched, bbo was 0.004815@0.004818. asset=197"}]}}}
                         {:status "ok"
                          :response {:data {:statuses [{:resting {:oid 42}}]}}})))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [_ _ _] nil)]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
             passive-ready-plan)
            (.then (fn [ledger]
                     (is (= 2 (count @submitted)))
                     (is (= "0.004815" (get-in (second @submitted) [:orders 0 :p])))
                     (is (= :resting (get-in ledger [:rows 0 :status])))
                     (is (= :resting (:status ledger)))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest execute-portfolio-optimizer-plan-effect-marks-resting-order-not-filled-test
  (async done
    (let [dispatches (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          ticks (atom [1000 1100])
          store (atom {:wallet {:address address :agent {:status :ready}}
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
                    (fn [_store _address _action]
                      (js/Promise.resolve
                       {:status "ok"
                        :response {:data {:statuses [{:resting {:oid 7}}]}}}))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [runtime-store ctx effects]
                      (swap! dispatches conj [runtime-store ctx effects]))]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
             ready-plan)
            (.then (fn [ledger]
                     (is (= :resting (get-in ledger [:rows 0 :status])))
                     (is (nil? (get-in ledger [:rows 0 :realized])))
                     (is (= :resting (:status ledger)))
                     (is (= :resting
                            (get-in @store [:portfolio :optimizer :execution :status])))
                     (is (= [[store nil [[:actions/load-user-data address]
                                         [:actions/refresh-order-history]]]]
                            @dispatches))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest execute-portfolio-optimizer-plan-effect-routes-selected-subaccount-test
  (async done
    (let [submitted (atom [])
          dispatches (atom [])
          ticks (atom [1000 1100])
          owner-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          subaccount-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          plan (dissoc ready-plan :scenario-id)
          store (atom {:wallet {:address owner-address
                                :agent {:status :ready}}
                       :account-context {:subaccounts
                                         {:selected-address subaccount-address
                                          :rows [{:sub-account-user subaccount-address
                                                  :master owner-address
                                                  :name "Desk A"}]}}
                       :asset-selector {:market-by-key
                                        {"perp:BTC" {:coin "BTC"
                                                     :market-type :perp
                                                     :asset-id 0
                                                     :szDecimals 4}}}
                       :portfolio {:optimizer
                                   {:execution-modal {:open? true
                                                      :submitting? true
                                                      :plan plan}}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn []
                      (let [t (first @ticks)]
                        (swap! ticks rest)
                        t))
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store submitted-address action & [options]]
                      (swap! submitted conj [submitted-address action options])
                      (js/Promise.resolve
                       {:status "ok"
                        :response {:data {:statuses ["success"]}}}))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [runtime-store ctx effects]
                      (swap! dispatches conj [runtime-store ctx effects]))]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
            plan)
            (.then (fn [ledger]
                     (let [[submitted-address action options] (first @submitted)]
                       (is (= :executed (:status ledger)))
                       (is (= owner-address submitted-address))
                       (is (= "order" (:type action)))
                       (is (= {:vault-address subaccount-address}
                              options)))
                     (is (= [[store nil [[:actions/load-user-data subaccount-address]
                                          [:actions/refresh-order-history]]]]
                            @dispatches))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest execute-portfolio-optimizer-plan-effect-executes-when-only-blocked-rows-remain-test
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
                                   :reason :spot-submit-unsupported}))
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
                     (is (= :executed (:status ledger)))
                     (is (= :submitted (get-in ledger [:rows 0 :status])))
                     (is (= :blocked (get-in ledger [:rows 1 :status])))
	                     (is (= :executed
	                            (get-in @store [:portfolio :optimizer :execution :status])))
	                     (done)))
	            (.catch (async-support/unexpected-error done)))))))

(deftest execute-portfolio-optimizer-plan-effect-persists-ledger-for-saved-scenario-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          scenario-record {:schema-version 1
                           :id "scn_submit"
                           :name "Submit Scenario"
                           :address address
                           :status :saved
                           :config {:id "scn_submit"
                                    :name "Submit Scenario"
                                    :status :saved
                                    :metadata {:dirty? false
                                               :updated-at-ms 900}}
                           :saved-run (fixtures/sample-last-successful-run
                                       {:result {:expected-return 0.2
                                                 :volatility 0.4
                                                 :rebalance-preview {:status :ready}}})
                           :execution-ledger []
                           :updated-at-ms 900}
          scenario-index {:ordered-ids ["scn_submit"]
                          :by-id {"scn_submit" {:id "scn_submit"
                                                :name "Submit Scenario"
                                                :status :saved
                                                :updated-at-ms 900}}}
          saved-scenarios (atom [])
          saved-indexes (atom [])
          ticks (atom [1000 1100])
          store (atom {:wallet {:address address
                                :agent {:status :ready}}
                       :asset-selector {:market-by-key
                                        {"perp:BTC" {:coin "BTC"
                                                     :market-type :perp
                                                     :asset-id 0
                                                     :szDecimals 4}}}
                       :portfolio {:optimizer {:active-scenario {:loaded-id "scn_submit"
                                                                 :status :saved}
                                               :scenario-index scenario-index
                                               :execution-modal {:open? true
                                                                 :submitting? true
                                                                 :plan ready-plan}}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn []
                      (let [t (first @ticks)]
                        (swap! ticks rest)
                        t))
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store _address _action]
                      (js/Promise.resolve
                       {:status "ok"
                        :response {:data {:statuses ["success"]}}}))
                    portfolio-optimizer-adapters/*dispatch!* (fn [& _])
                    portfolio-optimizer-adapters/*load-scenario!*
                    (fn [scenario-id]
                      (is (= "scn_submit" scenario-id))
                      (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [loaded-address]
                      (is (= address loaded-address))
                      (js/Promise.resolve scenario-index))
                    portfolio-optimizer-adapters/*save-scenario!*
                    (fn [scenario-id record]
                      (swap! saved-scenarios conj [scenario-id record])
                      (js/Promise.resolve nil))
                    portfolio-optimizer-adapters/*save-scenario-index!*
                    (fn [saved-address index]
                      (swap! saved-indexes conj [saved-address index])
                      (js/Promise.resolve nil))]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
             ready-plan)
            (.then (fn [ledger]
                     (let [[saved-scenario-id saved-record] (first @saved-scenarios)
                           [saved-address saved-index] (first @saved-indexes)]
                       (is (= :executed (:status ledger)))
                       (is (= "scn_submit" saved-scenario-id))
                       (is (= :executed (:status saved-record)))
                       (is (= :executed (get-in saved-record [:config :status])))
                       (is (= [ledger] (:execution-ledger saved-record)))
                       (is (= 1100 (:updated-at-ms saved-record)))
                       (is (= address saved-address))
                       (is (= :executed
                              (get-in saved-index [:by-id "scn_submit" :status])))
                       (is (= saved-index
                              (get-in @store [:portfolio :optimizer :scenario-index])))
                       (done))))
            (.catch (async-support/unexpected-error done)))))))

(deftest execute-portfolio-optimizer-plan-effect-refreshes-open-orders-when-resting-test
  (async done
    (let [refreshes (atom 0)
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          ticks (atom [1000 1100 2000 2100])
          base-store #(atom {:wallet {:address address :agent {:status :ready}}
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
                                                            :plan ready-plan}}}})
          resting-store (base-store)
          filled-store (base-store)
          execute-plan! (fn [store response]
                          (with-redefs [portfolio-optimizer-adapters/*now-ms*
                                        (fn []
                                          (let [t (first @ticks)]
                                            (swap! ticks rest)
                                            t))
                                        portfolio-optimizer-adapters/*submit-order!*
                                        (fn [_store _address _action]
                                          (js/Promise.resolve response))
                                        portfolio-optimizer-adapters/*dispatch!*
                                        (fn [_ _ _] nil)
                                        portfolio-optimizer-adapters/*refresh-account-open-orders!*
                                        (fn [_store] (swap! refreshes inc))]
                            (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
                             nil store ready-plan)))]
      (-> (execute-plan! resting-store
                         {:status "ok"
                          :response {:data {:statuses [{:resting {:oid 7}}]}}})
          (.then (fn [_]
                   (is (= 1 @refreshes) "a resting row forces the open-orders refresh")
                   (execute-plan! filled-store
                                  {:status "ok"
                                   :response {:data {:statuses
                                                     [{:filled {:avgPx "100"
                                                                :totalSz "0.25"}}]}}})))
          (.then (fn [_]
                   (is (= 1 @refreshes)
                       "a fully filled run rests nothing — no forced refresh")
                   (done)))
          (.catch (async-support/unexpected-error done))))))
