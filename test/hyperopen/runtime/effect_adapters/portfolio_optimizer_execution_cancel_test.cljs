(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-execution-cancel-test
  "The execute effect must cancel the previous run's stale resting orders BEFORE
  releasing any new order — and refuse to release anything when that cancellation
  fails, since a stale order filling on top of the new run over-allocates the account."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]))

(def ^:private carryover-plan
  {:scenario-id "scn_carryover"
   :status :ready
   :execution-disabled? false
   :summary {:ready-count 1
             :blocked-count 0}
   :cancel-orders [{:oid 777 :asset-id 42 :coin "ZETA"
                    :instrument-id "perp:ZETA" :side :buy :quantity 681.7}]
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

(defn- carryover-store
  [address]
  (atom {:wallet {:address address
                  :agent {:status :ready}}
         :asset-selector {:market-by-key
                          {"perp:BTC" {:coin "BTC"
                                       :market-type :perp
                                       :asset-id 0
                                       :szDecimals 4}}}
         :portfolio {:optimizer
                     {:execution-resting-carryover
                      [{:oid 777 :asset-id 42 :coin "ZETA"}]
                      :active-scenario {:loaded-id "scn_carryover"
                                        :status :saved}
                      :execution-modal {:open? true
                                        :submitting? true
                                        :plan carryover-plan}}}}))

(deftest execute-portfolio-optimizer-plan-effect-cancels-stale-resting-orders-first-test
  (async done
    (let [submitted (atom [])
          ticks (atom [1000 1100])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          store (carryover-store address)]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn []
                      (let [t (first @ticks)]
                        (swap! ticks rest)
                        t))
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store _address action]
                      (swap! submitted conj action)
                      (js/Promise.resolve
                       {:status "ok"
                        :response {:data {:statuses ["success"]}}}))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [_ _ _])]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
             carryover-plan)
            (.then (fn [ledger]
                     (is (= ["cancel" "order"] (mapv :type @submitted))
                         "the batched cancel is submitted BEFORE any new order")
                     (is (= [{:a 42 :o 777}] (:cancels (first @submitted)))
                         "wire cancel built from the carryover's frozen asset index + oid")
                     (is (= :ok (get-in ledger [:cancellations :status])))
                     (is (= :executed (:status ledger)))
                     (is (= []
                            (get-in @store [:portfolio :optimizer
                                            :execution-resting-carryover]))
                         "cancelled oid pruned from the carryover on ledger apply")
                     (done))))))))

(deftest execute-portfolio-optimizer-plan-effect-halts-run-when-cancel-fails-test
  (async done
    (let [submitted (atom [])
          ticks (atom [1000 1100])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          store (carryover-store address)]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn []
                      (let [t (first @ticks)]
                        (swap! ticks rest)
                        t))
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store _address action]
                      (swap! submitted conj action)
                      (js/Promise.resolve
                       (if (= "cancel" (:type action))
                         {:status "err" :response "Exchange unavailable"}
                         {:status "ok"
                          :response {:data {:statuses ["success"]}}})))
                    portfolio-optimizer-adapters/*dispatch!*
                    (fn [_ _ _])]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil
             store
             carryover-plan)
            (.then (fn [ledger]
                     (is (= ["cancel"] (mapv :type @submitted))
                         "no new order is released after a failed cancellation")
                     (is (= :failed (get-in ledger [:cancellations :status])))
                     (is (= :failed (:status ledger)))
                     (is (= :failed (get-in ledger [:rows 0 :status])))
                     (is (re-find #"Couldn't cancel 1 resting order"
                                  (get-in ledger [:rows 0 :error :message])))
                     (is (= [{:oid 777 :asset-id 42 :coin "ZETA"}]
                            (get-in @store [:portfolio :optimizer
                                            :execution-resting-carryover]))
                         "a failed cancellation must NOT prune — the order may still be live")
                     (done))))))))

(def ^:private feed-recognized-plan
  {:scenario-id "scn_feed"
   :status :ready
   :execution-disabled? false
   :summary {:ready-count 1 :blocked-count 0}
   :cancel-orders [{:oid 900 :coin "ZETA" :side :buy}]
   :rows [{:row-id "perp:BTC"
           :instrument-id "perp:BTC"
           :instrument-type :perp
           :coin "BTC"
           :status :ready
           :side :buy
           :price 100
           :quantity 0.25
           :delta-notional-usd 25
           :intent {:kind :perp-order :instrument-id "perp:BTC" :side :buy
                    :quantity 0.25 :order-type :market :reduce-only? false}}]})

(deftest execute-effect-resolves-asset-id-for-feed-recognized-cancel-test
  (async done
    (let [submitted (atom [])
          ticks (atom [1000 1100])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          store (atom {:wallet {:address address :agent {:status :ready}}
                       :asset-selector {:market-by-key
                                        {"perp:BTC" {:coin "BTC" :market-type :perp
                                                     :asset-id 0 :szDecimals 4}
                                         "perp:ZETA" {:coin "ZETA" :market-type :perp
                                                      :asset-id 42 :szDecimals 2}}}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "scn_feed" :status :saved}
                                    :execution-modal {:open? true :submitting? true
                                                      :plan feed-recognized-plan}}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn [] (let [t (first @ticks)] (swap! ticks rest) t))
                    portfolio-optimizer-adapters/*submit-order!*
                    (fn [_store _address action]
                      (swap! submitted conj action)
                      (js/Promise.resolve {:status "ok" :response {:data {:statuses ["success"]}}}))
                    portfolio-optimizer-adapters/*dispatch!* (fn [_ _ _])]
        (-> (portfolio-optimizer-adapters/execute-portfolio-optimizer-plan-effect
             nil store feed-recognized-plan)
            (.then (fn [ledger]
                     (is (= ["cancel" "order"] (mapv :type @submitted)))
                     (is (= [{:a 42 :o 900}] (:cancels (first @submitted)))
                         "asset index 42 resolved from market-by-key for the feed-sourced oid")
                     (is (= :ok (get-in ledger [:cancellations :status])))
                     (done))))))))
