(ns hyperopen.vol-range.application.projections-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.vol-range-contracts :as vrc]
            [hyperopen.vol-range.application.projections :as projections]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def now sf/now-ms)

(defn- with-fees [f]
  (with-redefs [snapshots/fee-snapshot sf/with-synthetic-fees]
    (f)))

(defn- ticket [state] (projections/ticket-model state now))

(deftest seven-order-ticket-model-test
  (with-fees
    (fn []
      (let [state (sf/base-state)
            m (ticket state)]
        (is (vrc/ticket-model-valid? m) (pr-str m))
        (is (true? (:visible? m)))
        (is (true? (:eligible? m)))
        (is (= :ready (:status m)))
        (is (= {:note "Orders fill independently. Prices stay fixed until you update them."
                :review-label "Review 7 orders"
                :review-enabled? true}
               (:footer m)))
        (testing "identity strip"
          (is (= {:market-label "SYN/USDC" :market-type-label "Spot"
                  :account-label "0x1234…5678" :execution-label "Post-only"}
                 (:identity m))))
        (testing "model section"
          (let [{:keys [volatility anchor tilt]} (:model m)]
            (is (= "6.00%" (:display volatility)))
            (is (= :manual (:mode volatility)))
            (is (= "Manual" (:source-label volatility)))
            (is (= "Parkinson · 14-day lookback" (:caption volatility)))
            (is (= "6.00" (:manual-input volatility)))
            (is (= "80" (:price anchor)))
            (is (= "Custom anchor" (:caption anchor)))
            (is (= ["24h VWAP" "UTC open" "Market price" "Custom anchor"] (mapv :label (:options anchor))))
            (is (= [true false true true] (mapv :available? (:options anchor))))
            (is (= :custom (:id (first (filter :selected? (:options anchor))))))
            (is (= "0σ from anchor" (:excursion-text anchor)))
            (is (false? (:enabled? tilt)))))
        (testing "buy card"
          (let [buy (:buy m)]
            (is (= ["76.4" "74" "71.6" "69.2"] (mapv :price (:rows buy))))
            (is (= ["7.00" "9.00" "11.00" "13.00"] (mapv :size (:rows buy))))
            (is (= {:leg-id "buy-0" :price "76.4" :size "7.00" :notional "534.80" :sigma "−0.75σ"}
                   (first (:rows buy))))
            (is (= {:quantity "40.00" :gross "2,888.00" :fee-reserve "1.1552" :fee-reserve-token "USDC"
                    :max-debit "2,889.1552" :cap "2,900" :cap-headroom "10.8448" :vwap "72.20"}
                   (:totals buy)))
            (is (nil? (:use-plan-total-offer buy)))))
        (testing "sell card"
          (let [sell (:sell m)]
            (is (= ["86" "88.4" "90.8"] (mapv :price (:rows sell))))
            (is (= {:quantity "30.00" :base-committed "30.00" :fee-reserve "1.0608" :fee-reserve-token "USDC"
                    :gross "2,652.00" :est-net-proceeds "2,650.94" :vwap "88.40"}
                   (:totals sell)))
            (is (= "Sale proceeds don't fund the staged buys." (:note sell)))
            (is (nil? (:nothing-to-sell-hint sell)))
            (is (= [{:pct 25 :label "25%" :quantity "7.5" :enabled? true}
                    {:pct 50 :label "50%" :quantity "15" :enabled? true}
                    {:pct 100 :label "100%" :quantity "30" :enabled? true}]
                   (:percent-options sell)))))
        (testing "inventory section and track"
          (let [inv (:inventory m)
                markers (into {} (map (juxt :id identity)) (get-in inv [:track :markers]))]
            (is (= "SYN" (:base-symbol inv)))
            (is (= "150.00" (:current inv)))
            (is (= "150.00" (:available-to-sell inv)))
            (is (= ["120" "160" "190"] [(:core-input inv) (:target-input inv) (:maximum-input inv)]))
            (is (= "current inventory" (:core-placeholder inv)))
            (is (= "120.00" (get-in inv [:track :domain-low])))
            (is (= "190.00" (get-in inv [:track :domain-high])))
            (is (= [:all-sells-first :core :now :target :max :all-buys-first]
                   (mapv :id (get-in inv [:track :markers]))))
            (is (= 0 (:position (:all-sells-first markers))))
            (is (= 100 (:position (:all-buys-first markers))))
            (is (= 42.86 (:position (:now markers))))
            (is (nil? (:set-maximum-offer inv)))))
        (testing "outcomes"
          (is (= {:both-sides? true :base-symbol "SYN"
                  :all-buys-first "190.00" :all-sells-first "120.00" :everything-fills "160.00"
                  :vwap-gap {:per-unit "16.20" :percent "22.4377%"
                             :explanation "Assumes matched quantities. It compares the two quoted VWAPs and is not expected profit."
                             :collapsed? true}}
                 (:outcomes m))))
        (is (= [] (:errors m)))
        (is (= [] (:warnings m)))))))

(deftest vwap-gap-only-when-both-sides-enabled-test
  (with-fees
    (fn []
      (let [state (assoc-in (sf/base-state) [:order-form :scale :vol-range :sell :enabled?] false)
            m (ticket state)]
        (is (vrc/ticket-model-valid? m))
        (is (false? (get-in m [:outcomes :both-sides?])))
        (is (nil? (get-in m [:outcomes :vwap-gap])))
        (is (= "Review 4 orders" (get-in m [:footer :review-label])))))))

(deftest crossing-error-card-and-remediations-test
  (with-fees
    (fn []
      (let [state (assoc-in (sf/base-state) [:orderbooks "@107"]
                            {:bids [{:px "75.89"}] :asks [{:px "75.91"}]})
            m (ticket state)]
        (is (vrc/ticket-model-valid? m) (pr-str m))
        (is (= :invalid (:status m)))
        (is (false? (get-in m [:footer :review-enabled?])))
        (is (= "−0.85σ from anchor" (get-in m [:model :anchor :excursion-text])))
        (let [error (first (:errors m))]
          (is (= :plan/post-only-cross (:code error)))
          (is (= "Buy at 76.4 is above the best ask 75.91" (:message error)))
          (is (= [:anchor-to-market :start-buys-at] (mapv :id (:remediations error))))
          (is (= ["Anchor to market price" "Start buys at 0.90σ"] (mapv :label (:remediations error)))))))))

(deftest one-click-offers-and-nothing-to-sell-hint-test
  (with-fees
    (fn []
      (testing "blank maximum and cap offer Set maximum and Use plan total"
        (let [state (-> (sf/base-state)
                        (assoc-in [:order-form :scale :vol-range :inventory :maximum] "")
                        (assoc-in [:order-form :scale :vol-range :buy :max-usdc-debit] ""))
              m (ticket state)]
          (is (vrc/ticket-model-valid? m) (pr-str m))
          (is (= {:id :set-maximum-all-buys :label "Set maximum to 190 · all buys filled"}
                 (get-in m [:inventory :set-maximum-offer])))
          (is (= {:id :use-plan-total :label "Use plan total"}
                 (get-in m [:buy :use-plan-total-offer])))))
      (testing "sell enabled with the default core shows the hint and no shortcuts"
        (let [state (-> (sf/base-state)
                        (assoc-in [:order-form :scale :vol-range :inventory :protected-core] "")
                        (assoc-in [:order-form :scale :vol-range :inventory :target] ""))
              m (ticket state)]
          (is (vrc/ticket-model-valid? m) (pr-str m))
          (is (= "Nothing to sell above protected core. Lower protected core to distribute inventory."
                 (get-in m [:sell :nothing-to-sell-hint])))
          (is (every? (complement :enabled?) (get-in m [:sell :percent-options]))))))))

(deftest tilt-caption-and-chart-shifted-anchor-test
  (with-fees
    (fn []
      (let [state (assoc-in (sf/base-state) [:order-form :scale :vol-range :inventory :tilt-enabled?] true)
            m (ticket state)
            chart (projections/chart-model state now)]
        (is (true? (get-in m [:model :tilt :enabled?])))
        (is (str/includes? (get-in m [:model :tilt :caption]) "80.54"))
        (is (= "Effective anchor 80.54 · shift +0.1125σ" (get-in m [:model :tilt :caption])))
        (is (= {:price "80.54" :label "Shifted anchor 80.54"} (:shifted-anchor chart)))
        (is (= {:price "80" :label "Anchor 80"} (:anchor chart)))))))

(deftest chart-model-test
  (with-fees
    (fn []
      (let [state (sf/base-state)
            chart (projections/chart-model state now)]
        (is (vrc/chart-model-valid? chart) (pr-str chart))
        (is (= :draft (:stage chart)))
        (is (= "Draft" (:stage-label chart)))
        (is (= {:price "80" :label "Anchor 80"} (:anchor chart)))
        (is (nil? (:shifted-anchor chart)))
        (is (= [{:sigma 1 :lower "75.2" :upper "84.8"} {:sigma 2 :lower "70.4" :upper "89.6"}]
               (:bands chart)))
        (is (= 7 (count (:legs chart))))
        (is (= {:leg-id "buy-0" :side :buy :price "76.4" :size "7" :weight 0.5385 :label "Buy 7 @ 76.4"}
               (first (:legs chart))))
        (is (= 1 (:weight (nth (:legs chart) 3))) "the largest rung has weight 1")
        (is (= [:buy :buy :buy :buy :sell :sell :sell] (mapv :side (:legs chart))))
        (testing "nil when bands are hidden or the mode is Price range"
          (is (nil? (projections/chart-model (assoc-in state [:vol-range :ui :show-bands?] false) now)))
          (is (nil? (projections/chart-model (assoc-in state [:order-form :scale :mode] :price-range) now)))
          (is (nil? (projections/chart-model (assoc-in state [:order-form :type] :limit) now))))))))

(deftest review-model-from-frozen-plan-test
  (with-fees
    (fn []
      (let [state (sf/base-state)
            plan (projections/plan-for-state state now)
            frozen (-> state
                       (assoc-in [:vol-range :frozen-review] plan)
                       (assoc-in [:vol-range :frozen-context] {:bbo {:bid "79.99" :ask "80.01"
                                                                     :observed-at-ms now}
                                                                :frozen-at-ms now})
                       (assoc-in [:vol-range :ui :review-open?] true))
            m (projections/review-model frozen now)]
        (is (= :ready (:status plan)))
        (is (vrc/review-model-valid? m) (pr-str m))
        (is (true? (:open? m)))
        (is (= "Review 7 post-only orders" (:title m)))
        (is (= "SYN/USDC · Spot · 0x1234…5678 · Post-only" (:identity-line m)))
        (is (= "Plan as of 14:00:00 UTC" (:plan-as-of m)))
        (is (= {:placement-available? false
                :text "Placement isn't available yet"
                :hint "Pressing Enter in a field never places orders."
                :back-label "Back to edit"}
               (:footer m)))
        (is (not (re-find #"(?i)commit" (pr-str m))))
        (is (not (re-find #"Place \d+" (pr-str m))))
        (is (= [:anchor :volatility :tilt :book] (mapv :id (:tiles m))))
        (is (= "79.99 / 80.01" (:value (last (:tiles m)))))
        (is (= "80" (:value (first (:tiles m)))))
        (is (= "6.00%" (:value (second (:tiles m)))))
        (is (= 7 (count (:legs m))))
        (is (= {:leg-id "buy-0" :side :buy :side-label "Buy" :price "76.4" :size "7.00"
                :notional "534.80" :distance "0.75σ"}
               (first (:legs m))))
        (is (= "Sell" (:side-label (last (:legs m)))))
        (is (some #(= "Maximum buy debit" (:label %)) (:cash m)))
        (is (some #(= "Existing buy orders included" (:label %)) (:inventory m)))
        (testing "the review does not re-anchor when the book moves"
          (let [moved (assoc-in frozen [:orderbooks "@107"] {:bids [{:px "70"}] :asks [{:px "71"}]})
                m2 (projections/review-model moved (+ now 600000))]
            (is (= m m2))))
        (testing "an account or market change invalidates the frozen review"
          (doseq [changed [(assoc-in frozen [:wallet :address] sf/other-owner)
                           (assoc frozen :active-market (sf/market "@108" 108 "SYN"))]]
            (let [m3 (projections/review-model changed now)]
              (is (vrc/review-model-valid? m3))
              (is (false? (:open? m3)))
              (is (false? (:available? m3)))
              (is (false? (get-in (projections/ticket-model changed now) [:ui :review-open?]))))))
        (testing "nothing frozen"
          (let [empty-model (projections/review-model (sf/base-state) now)]
            (is (vrc/review-model-valid? empty-model))
            (is (false? (:available? empty-model)))
            (is (= "Placement isn't available yet" (get-in empty-model [:footer :text])))))))))

(deftest invisible-and-ineligible-ticket-test
  (testing "Price range mode"
    (let [m (ticket (assoc-in (sf/base-state) [:order-form :scale :mode] :price-range))]
      (is (false? (:visible? m)))
      (is (vrc/ticket-model-valid? m))))
  (testing "other order types"
    (is (false? (:visible? (ticket (assoc-in (sf/base-state) [:order-form :type] :limit))))))
  (testing "perp market is ineligible with the documented reason"
    (let [state (assoc (sf/base-state) :active-market {:coin "BTC" :market-type :perp :base "BTC"
                                                       :quote "USDC" :szDecimals 5})
          m (ticket state)]
      (is (true? (:visible? m)))
      (is (false? (:eligible? m)))
      (is (= "Volatility range is available on USDC spot markets." (:eligibility-reason m)))
      (is (= :unavailable (:status m)))
      (is (vrc/ticket-model-valid? m) (pr-str m))))
  (testing "read-only accounts are flagged"
    (let [state (assoc-in (sf/base-state) [:account-context :spectate-mode]
                          {:active? true :address sf/other-owner})]
      (is (true? (:read-only? (ticket state)))))))

(deftest plan-for-state-memoizes-per-minute-and-injects-ids-test
  (with-fees
    (fn []
      (let [state (sf/base-state)
            a (projections/plan-for-state state now)
            b (projections/plan-for-state state (+ now 30000))
            c (projections/plan-for-state (assoc-in state [:order-form :scale :vol-range :buy :quantity] "41") now)]
        (is (identical? a b) "same inputs inside one minute reuse the plan")
        (is (not (identical? a c)))
        (is (str/starts-with? (:plan-id a) "vr-plan-"))
        (is (= (:plan-id a) (:plan-id b)))
        (is (not= (:plan-id a) (:plan-id c)))
        (is (contracts/plan-result-valid? a))
        (is (= :hyperliquid-mainnet (get-in a [:scope :network])))))))

(deftest dual-crossing-remediations-attach-to-their-own-side-test
  (let [rows (#'hyperopen.vol-range.application.ticket-model/error-rows
              {:errors [{:code :plan/post-only-cross :message "b" :data {:side :buy}}
                        {:code :plan/post-only-cross :message "s" :data {:side :sell}}]
               :remediations [{:id :anchor-to-market :label "A"}
                              {:id :start-buys-at :label "B"}
                              {:id :start-sells-at :label "S"}]})]
    (is (= [[:anchor-to-market :start-buys-at] [:start-sells-at]]
           (mapv #(mapv :id (:remediations %)) rows)))))
