(ns hyperopen.portfolio.optimizer.application.execution-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.execution :as execution]))

(def sample-preview
  {:status :partially-blocked
   :summary {:ready-count 1
             :blocked-count 1
             :within-tolerance-count 1
             :gross-trade-notional-usd 2000
             :estimated-fees-usd 1.0
             :estimated-slippage-usd 2.5
             :margin {:after-used-usd 500}}
   :rows [{:instrument-id "perp:BTC"
           :instrument-type :perp
           :status :ready
           :side :buy
           :quantity 0.25
           :delta-notional-usd 1000
           :cost {:source :fallback-bps
                  :estimated-fee-usd 1.0
                  :estimated-slippage-usd 2.5}}
          {:instrument-id "spot:PURR"
           :instrument-type :spot
           :status :blocked
           :reason :spot-submit-unsupported
           :side :sell
           :delta-notional-usd -1000}
          {:instrument-id "perp:ETH"
           :instrument-type :perp
           :status :within-tolerance
           :side :none
           :delta-notional-usd 0.5}]})

(deftest build-execution-plan-classifies-ready-blocked-and-skipped-rows-test
  (let [plan (execution/build-execution-plan
              {:scenario-id "scn_1"
               :rebalance-preview sample-preview
               :execution-assumptions {:default-order-type :market
                                       :fee-mode :taker}})]
    (is (= :partially-blocked (:status plan)))
    (is (= "scn_1" (:scenario-id plan)))
    (is (= {:ready-count 1
            :blocked-count 1
            :skipped-count 1
            :gross-ready-notional-usd 1000
            :estimated-fees-usd 1.0
            :estimated-slippage-usd 2.5
            :margin {:after-used-usd 500}}
           (:summary plan)))
    (is (= {:kind :perp-order
            :instrument-id "perp:BTC"
            :side :buy
            :quantity 0.25
            :order-type :market
            :reduce-only? false}
           (get-in plan [:rows 0 :intent])))
    (is (= :market (get-in plan [:rows 0 :order-type])))
    (is (= :blocked (get-in plan [:rows 1 :status])))
    (is (= :spot-submit-unsupported (get-in plan [:rows 1 :reason])))
    (is (= :skipped (get-in plan [:rows 2 :status])))
    (is (= :within-tolerance (get-in plan [:rows 2 :reason])))))

(deftest build-execution-plan-keeps-spectate-mode-read-only-test
  (let [plan (execution/build-execution-plan
              {:scenario-id "scn_read_only"
               :rebalance-preview sample-preview
               :mutations-blocked-message "Spectate Mode is read-only."})]
    (is (= true (:execution-disabled? plan)))
    (is (= :read-only (:disabled-reason plan)))
    (is (= "Spectate Mode is read-only." (:disabled-message plan)))
    (is (= :partially-blocked (:status plan)))))

(deftest build-execution-attempt-attaches-order-request-for-ready-perp-row-test
  (let [plan (execution/build-execution-plan
              {:scenario-id "scn_submit"
               :rebalance-preview
               {:rows [{:instrument-id "perp:BTC"
                        :instrument-type :perp
                        :coin "BTC"
                        :status :ready
                        :side :buy
                        :price 100
                        :quantity 0.25
                        :delta-notional-usd 25}]}
               :execution-assumptions {:default-order-type :market}})
        attempt (execution/build-execution-attempt
                 {:plan plan
                  :market-by-key {"perp:BTC" {:coin "BTC"
                                              :market-type :perp
                                              :asset-id 0
                                              :szDecimals 4}}})
        row (first (:rows attempt))]
    (is (= :ready (:status attempt)))
    (is (= :ready (:status row)))
    (is (= {:type "order"
            :orders [{:a 0
                      :b true
                      :p "105"
                      :s "0.25"
                      :r false
                      :t {:limit {:tif "Ioc"}}}]
            :grouping "na"}
           (get-in row [:request :action])))))

(deftest build-execution-attempt-market-order-prices-to-cross-test
  (let [market-by-key {"perp:THIN" {:coin "THIN" :market-type :perp :asset-id 7 :szDecimals 0}}
        book {"THIN" {:render {:best-bid {:px "62.1"}
                               :best-ask {:px "62.5"}}
                      :timestamp 0}}
        order-for (fn [side]
                    (-> (execution/build-execution-plan
                         {:scenario-id "scn_mkt"
                          :rebalance-preview
                          {:rows [{:instrument-id "perp:THIN" :instrument-type :perp :coin "THIN"
                                   :status :ready :side side :price 62.856
                                   :quantity 5
                                   :delta-notional-usd (if (= side :buy) 314 -314)}]}
                          :execution-assumptions {:default-order-type :market}})
                        (#(execution/build-execution-attempt
                           {:plan % :market-by-key market-by-key :orderbooks book}))
                        (get-in [:rows 0 :request :action :orders 0])))
        buy (order-for :buy)
        sell (order-for :sell)]
    (is (= {:tif "Ioc"} (:limit (:t buy))))
    (is (= 58.995 (js/parseFloat (:p sell))))
    (is (= 65.625 (js/parseFloat (:p buy))))))

(deftest build-execution-attempt-floors-wire-size-to-sz-decimals-test
  (let [attempt-for (fn [coin sz-decimals quantity]
                      (let [plan (execution/build-execution-plan
                                  {:scenario-id "scn_sz"
                                   :rebalance-preview
                                   {:rows [{:instrument-id (str "perp:" coin)
                                            :instrument-type :perp
                                            :coin coin
                                            :status :ready
                                            :side :sell
                                            :price 0.005
                                            :quantity quantity
                                            :delta-notional-usd -40}]}
                                   :execution-assumptions {:default-order-type :market}})]
                        (execution/build-execution-attempt
                         {:plan plan
                          :market-by-key {(str "perp:" coin)
                                          {:coin coin
                                           :market-type :perp
                                           :asset-id 197
                                           :szDecimals sz-decimals}}})))
        wire-size (fn [attempt]
                    (get-in (first (:rows attempt))
                            [:request :action :orders 0 :s]))]
    (is (= "7968" (wire-size (attempt-for "SOPH" 0 7968.855385980289))))
    (is (= "1.08" (wire-size (attempt-for "SILVER" 2 1.089665624397716))))
    (is (= "0.574" (wire-size (attempt-for "BABA" 3 0.57497234024216))))))

(deftest build-execution-attempt-blocks-row-when-size-floors-below-one-lot-test
  (let [plan (execution/build-execution-plan
              {:scenario-id "scn_dust"
               :rebalance-preview
               {:rows [{:instrument-id "perp:SOPH"
                        :instrument-type :perp
                        :coin "SOPH"
                        :status :ready
                        :side :sell
                        :price 0.005
                        :quantity 0.7
                        :delta-notional-usd -0.0035}]}
               :execution-assumptions {:default-order-type :market}})
        attempt (execution/build-execution-attempt
                 {:plan plan
                  :market-by-key {"perp:SOPH" {:coin "SOPH"
                                               :market-type :perp
                                               :asset-id 197
                                               :szDecimals 0}}})
        row (first (:rows attempt))]
    (is (= :blocked (:status row)))
    (is (nil? (get-in row [:request :action])))))

(deftest build-execution-attempt-routes-selected-order-types-test
  (let [base (execution/build-execution-plan
              {:scenario-id "scn_types"
               :rebalance-preview
               {:rows [{:instrument-id "perp:BTC"
                        :instrument-type :perp
                        :coin "BTC"
                        :status :ready
                        :side :buy
                        :price 100
                        :quantity 0.25
                        :delta-notional-usd 25}]}
               :execution-assumptions {:default-order-type :market}})
        market-by-key {"perp:BTC" {:coin "BTC"
                                   :market-type :perp
                                   :asset-id 0
                                   :szDecimals 4}}
        action-for (fn [selections]
                     (-> base
                         (execution/apply-order-type-selections selections)
                         (#(execution/build-execution-attempt
                            {:plan % :market-by-key market-by-key}))
                         (get-in [:rows 0 :request :action])))]
    (let [action (action-for {:default-order-type :recommended
                              :overrides {"perp:BTC" :limit}
                              :params {"perp:BTC" {:limit-bps -5}}})
          order (first (:orders action))]
      (is (= "order" (:type action)))
      (is (= {:tif "Gtc"} (:limit (:t order))))
      (is (< (js/parseFloat (:p order)) 100)))
    (let [action (action-for {:default-order-type :recommended
                              :overrides {"perp:BTC" :passive}
                              :params {}})]
      (is (= {:tif "Alo"} (:limit (:t (first (:orders action)))))))
    (let [action (action-for {:default-order-type :recommended
                              :overrides {"perp:BTC" :twap}
                              :params {"perp:BTC" {:twap-min 20}}})]
      (is (= "twapOrder" (:type action)))
      (is (= 20 (get-in action [:twap :m]))))))

(deftest build-execution-attempt-passive-rests-at-book-touch-not-crossing-test
  (let [market-by-key {"perp:THIN" {:coin "THIN" :market-type :perp :asset-id 7 :szDecimals 0}}
        book {"THIN" {:render {:best-bid {:px "0.004777"}
                               :best-ask {:px "0.004781"}}
                      :timestamp 0}}
        order-for (fn [side]
                    (-> (execution/build-execution-plan
                         {:scenario-id "scn_thin"
                          :rebalance-preview
                          {:rows [{:instrument-id "perp:THIN" :instrument-type :perp :coin "THIN"
                                   :status :ready :side side :price 0.004782
                                   :quantity 5000
                                   :delta-notional-usd (if (= side :buy) 24 -24)}]}
                          :execution-assumptions {:default-order-type :passive}})
                        (#(execution/build-execution-attempt
                           {:plan % :market-by-key market-by-key :orderbooks book}))
                        (get-in [:rows 0 :request :action :orders 0])))
        buy (order-for :buy)
        sell (order-for :sell)]
    (is (= {:tif "Alo"} (:limit (:t buy))))
    (is (= 0.004777 (js/parseFloat (:p buy))))
    (is (= 0.004781 (js/parseFloat (:p sell))))
    (is (< (js/parseFloat (:p buy)) 0.004781))
    (is (> (js/parseFloat (:p sell)) 0.004777))))

(deftest build-execution-attempt-passive-falls-back-to-mark-offset-without-book-test
  (let [market-by-key {"perp:BTC" {:coin "BTC" :market-type :perp :asset-id 0 :szDecimals 4}}
        order-for (fn [side]
                    (-> (execution/build-execution-plan
                         {:scenario-id "scn_nobook"
                          :rebalance-preview
                          {:rows [{:instrument-id "perp:BTC" :instrument-type :perp :coin "BTC"
                                   :status :ready :side side :price 100
                                   :quantity 0.25
                                   :delta-notional-usd (if (= side :buy) 25 -25)}]}
                          :execution-assumptions {:default-order-type :market}})
                        (execution/apply-order-type-selections
                         {:default-order-type :recommended
                          :overrides {"perp:BTC" :passive}
                          :params {}})
                        (#(execution/build-execution-attempt
                           {:plan % :market-by-key market-by-key}))
                        (get-in [:rows 0 :request :action :orders 0])))
        buy (order-for :buy)
        sell (order-for :sell)]
    (is (= {:tif "Alo"} (:limit (:t buy))))
    (is (< (js/parseFloat (:p buy)) 100))
    (is (> (js/parseFloat (:p sell)) 100))))

(deftest build-execution-attempt-passive-falls-back-when-book-crossed-test
  (let [market-by-key {"perp:BTC" {:coin "BTC" :market-type :perp :asset-id 0 :szDecimals 4}}
        crossed {"BTC" {:render {:best-bid {:px "101"} :best-ask {:px "100"}} :timestamp 0}}
        buy (-> (execution/build-execution-plan
                 {:scenario-id "scn_crossed"
                  :rebalance-preview
                  {:rows [{:instrument-id "perp:BTC" :instrument-type :perp :coin "BTC"
                           :status :ready :side :buy :price 100 :quantity 0.25
                           :delta-notional-usd 25}]}
                  :execution-assumptions {:default-order-type :market}})
                (execution/apply-order-type-selections
                 {:default-order-type :recommended
                  :overrides {"perp:BTC" :passive}
                  :params {}})
                (#(execution/build-execution-attempt
                   {:plan % :market-by-key market-by-key :orderbooks crossed}))
                (get-in [:rows 0 :request :action :orders 0]))]
    (is (= {:tif "Alo"} (:limit (:t buy))))
    (is (< (js/parseFloat (:p buy)) 100))))

(deftest build-execution-attempt-routes-ready-spot-row-test
  (let [plan (execution/build-execution-plan
              {:scenario-id "scn_spot"
               :rebalance-preview
               {:rows [{:instrument-id "spot:PURR"
                        :instrument-type :spot
                        :coin "PURR"
                        :status :ready
                        :side :sell
                        :price 2
                        :quantity 400
                        :delta-notional-usd -800}]}
               :execution-assumptions {:default-order-type :market}})
        attempt (execution/build-execution-attempt
                 {:plan plan
                  :market-by-key {"spot:PURR" {:coin "PURR"
                                               :market-type :spot
                                               :asset-id 11035
                                               :szDecimals 2}}})
        row (first (:rows attempt))
        order (first (get-in row [:request :action :orders]))]
    (is (= :ready (:status attempt)))
    (is (= :ready (:status row)))
    (is (= :spot-order (get-in plan [:rows 0 :intent :kind])))
    (is (= "order" (get-in row [:request :action :type])))
    (is (= 11035 (:a order)))
    (is (= false (:b order)))
    (is (= false (:r order)))))

(deftest build-execution-attempt-blocks-ready-row-without-market-metadata-test
  (let [plan (execution/build-execution-plan
              {:scenario-id "scn_missing_market"
               :rebalance-preview
               {:rows [{:instrument-id "perp:BTC"
                        :instrument-type :perp
                        :coin "BTC"
                        :status :ready
                        :side :buy
                        :price 100
                        :quantity 0.25
                        :delta-notional-usd 25}]}})
        attempt (execution/build-execution-attempt {:plan plan})]
    (is (= :blocked (:status attempt)))
    (is (= :blocked (get-in attempt [:rows 0 :status])))
    (is (= :market-metadata-missing (get-in attempt [:rows 0 :reason])))))

(deftest build-execution-plan-blocks-ready-row-with-non-executable-values-test
  (let [zero-quantity (execution/build-execution-plan
                       {:scenario-id "scn_zero_qty"
                        :rebalance-preview
                        {:rows [{:instrument-id "perp:BTC"
                                 :instrument-type :perp
                                 :status :ready
                                 :side :buy
                                 :quantity 0
                                 :delta-notional-usd 100}]}})
        no-side (execution/build-execution-plan
                 {:scenario-id "scn_no_side"
                  :rebalance-preview
                  {:rows [{:instrument-id "perp:BTC"
                           :instrument-type :perp
                           :status :ready
                           :side :none
                           :quantity 0.01
                           :delta-notional-usd 100}]}})]
    (is (= :blocked (:status zero-quantity)))
    (is (= :quantity-below-lot (get-in zero-quantity [:rows 0 :reason])))
    (is (= 0 (get-in zero-quantity [:summary :ready-count])))
    (is (= :blocked (:status no-side)))
    (is (= :zero-delta-notional (get-in no-side [:rows 0 :reason])))))

(defn- near?
  [expected actual]
  (and (number? actual)
       (< (js/Math.abs (- expected actual)) 0.0000001)))

(deftest realized-fill-scales-slippage-usd-to-filled-quantity-test
  (let [row {:side :buy :price 100 :quantity 1.0 :delta-notional-usd 100}
        partial (execution/realized-fill
                 row
                 {:status "ok"
                  :response {:data {:statuses [{:filled {:oid 1 :totalSz "0.5" :avgPx "101"}}]}}})
        full (execution/realized-fill
              row
              {:status "ok"
               :response {:data {:statuses [{:filled {:oid 1 :totalSz "1.0" :avgPx "101"}}]}}})]
    (is (near? 100 (:slippage-bps partial)))
    (is (near? 100 (:slippage-bps full)))
    (is (near? 0.5 (:slippage-usd partial)))
    (is (near? 1.0 (:slippage-usd full)))))

(deftest settled-row-status-classifies-resting-vs-filled-test
  (is (= :submitted
         (execution/settled-row-status
          {:status "ok"
           :response {:data {:statuses [{:filled {:oid 1 :avgPx "10" :totalSz "1"}}]}}}))
      "a filled entry -> :submitted")
  (is (= :resting
         (execution/settled-row-status
          {:status "ok" :response {:data {:statuses [{:resting {:oid 2}}]}}}))
      "a resting (open) entry, no fill -> :resting")
  (is (= :submitted
         (execution/settled-row-status
          {:status "ok" :response {:data {:statuses ["success"]}}}))
      "legacy/empty OK shape -> :submitted (immediate-cross behavior preserved)"))

(deftest post-only-cross-bbo-parses-live-touch-from-rejection-test
  (is (= {:bid "0.004815" :ask "0.004818"}
         (execution/post-only-cross-bbo
          {:status "ok"
           :response {:type "order"
                      :data {:statuses [{:error "Post only order would have immediately matched, bbo was 0.004815@0.004818. asset=197"}]}}})))
  (is (nil? (execution/post-only-cross-bbo
             {:status "ok" :response {:data {:statuses [{:error "Insufficient margin"}]}}})))
  (is (nil? (execution/post-only-cross-bbo
             {:status "ok" :response {:data {:statuses [{:resting {:oid 1}}]}}}))))

(deftest reprice-post-only-action-rests-at-rejection-touch-test
  (let [action (fn [is-buy]
                 {:type "order" :grouping "na"
                  :orders [{:a 197 :b is-buy :p "0.004822" :s "3000" :r false
                            :t {:limit {:tif "Alo"}}}]})
        bbo {:bid "0.004815" :ask "0.004818"}]
    (is (= "0.004815"
           (get-in (execution/reprice-post-only-action (action true) bbo) [:orders 0 :p])))
    (is (= "0.004818"
           (get-in (execution/reprice-post-only-action (action false) bbo) [:orders 0 :p])))
    (is (nil? (execution/reprice-post-only-action
               (assoc-in (action true) [:orders 0 :t] {:limit {:tif "Gtc"}}) bbo)))))

(deftest final-ledger-status-distinguishes-resting-from-filled-test
  (is (= :resting
         (execution/final-ledger-status [{:status :resting} {:status :resting}]))
      "all orders resting on the book -> :resting (never :executed)")
  (is (= :resting
         (execution/final-ledger-status [{:status :submitted} {:status :resting}]))
      "some filled, some still resting -> :resting (not fully executed)")
  (is (= :executed
         (execution/final-ledger-status [{:status :submitted} {:status :submitted}]))
      "every accepted order filled outright -> :executed (unchanged)")
  (is (= :partially-executed
         (execution/final-ledger-status [{:status :resting} {:status :failed}]))
      "a live resting order alongside a rejection -> partial run (halted)")
  (is (= :failed
         (execution/final-ledger-status [{:status :failed} {:status :failed}]))
      "pure failures unchanged"))

(deftest final-ledger-status-blocked-rows-do-not-halt-a-successful-run-test
  (is (= :resting
         (execution/final-ledger-status
          (into [{:status :resting} {:status :resting}]
                (repeat 18 {:status :blocked}))))
      "all sendable orders resting + only blocked exclusions -> :resting, not halted")
  (is (= :executed
         (execution/final-ledger-status
          [{:status :submitted} {:status :blocked} {:status :blocked}]))
      "sendable order filled + blocked exclusions -> :executed, not partial")
  (is (= :partially-executed
         (execution/final-ledger-status
          [{:status :submitted} {:status :failed} {:status :blocked}]))
      "a genuine exchange rejection DOES still halt as a partial run")
  (is (= :blocked
         (execution/final-ledger-status [{:status :blocked} {:status :blocked}]))
      "nothing sendable and nothing failed -> :blocked"))

(deftest build-resume-plan-skips-resting-rows-test
  (let [plan {:summary {} :rows []}
        ledger {:rows [{:row-id "perp:BTC" :status :resting :side :buy
                        :quantity 1 :delta-notional-usd 10}
                       {:row-id "perp:ETH" :status :failed :side :buy
                        :quantity 2 :delta-notional-usd 20}]}
        resumed (execution/build-resume-plan plan ledger)
        by-id (into {} (map (juxt :row-id identity) (:rows resumed)))]
    (is (= :skipped (get-in by-id ["perp:BTC" :status]))
        "resting row is demoted to :skipped, never re-submitted")
    (is (= :ready (get-in by-id ["perp:ETH" :status]))
        "the failed row is re-armed for retry")
    (is (= 1 (get-in resumed [:summary :ready-count]))
        "only the failed row counts toward the resume")))

(deftest build-restaged-plan-drops-resting-rows-test
  (let [plan {:summary {}
              :rows [{:row-id "perp:BTC" :status :ready :quantity 4
                      :delta-notional-usd 40 :intent {:quantity 4}}
                     {:row-id "perp:ETH" :status :ready :quantity 2
                      :delta-notional-usd 20 :intent {:quantity 2}}]}
        ledger {:rows [{:row-id "perp:BTC" :status :resting}
                       {:row-id "perp:ETH" :status :failed}]}
        restaged (execution/build-restaged-plan plan ledger 0.5)
        ids (set (map :row-id (:rows restaged)))]
    (is (not (contains? ids "perp:BTC"))
        "resting (live) row is dropped, not re-staged")
    (is (contains? ids "perp:ETH")
        "the unfilled/failed row is re-staged")
    (is (= 1 (count (:rows restaged))))))

(deftest build-revert-plan-excludes-resting-rows-test
  (let [plan {:summary {} :rows []}
        ledger {:rows [{:row-id "perp:BTC" :status :submitted :side :buy :instrument-type :perp
                        :instrument-id "perp:BTC" :price 100 :quantity 1 :delta-notional-usd 100}
                       {:row-id "perp:ETH" :status :resting :side :buy :instrument-type :perp
                        :instrument-id "perp:ETH" :price 50 :quantity 2 :delta-notional-usd 100}]}
        revert (execution/build-revert-plan plan ledger)
        ids (set (map :row-id (:rows revert)))]
    (is (contains? ids "perp:BTC") "the filled leg is reversed")
    (is (not (contains? ids "perp:ETH")) "the resting (unfilled) leg is NOT reversed")
    (is (= 1 (count (:rows revert))))))

(deftest build-execution-plan-skips-allocator-excluded-rows-test
  (let [plan (execution/build-execution-plan
              {:scenario-id "scn_1"
               :rebalance-preview
               {:status :ready
                :summary {}
                :rows [{:instrument-id "spot:@107"
                        :instrument-type :spot
                        :coin "HYPE"
                        :status :excluded
                        :reason :excluded-from-optimization
                        :side :none
                        :delta-notional-usd 0}]}
               :execution-assumptions {:default-order-type :market}})
        row (get-in plan [:rows 0])]
    (is (= :skipped (:status row)))
    (is (= :excluded-from-optimization (:reason row)))
    (is (nil? (:intent row)))
    (is (= 1 (get-in plan [:summary :skipped-count])))
    (is (= 0 (get-in plan [:summary :ready-count])))
    (is (= 0 (get-in plan [:summary :blocked-count])))))
