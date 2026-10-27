(ns hyperopen.views.trade.vol-range.ticket-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vol-range.application.projections :as projections]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.state-fixtures :as sf]
            [hyperopen.views.trade.order-form-view :as order-form-view]
            [hyperopen.views.trade.vol-range.ticket :as ticket]))

(defn- with-fees [f]
  (with-redefs [snapshots/fee-snapshot sf/with-synthetic-fees]
    (f)))

(defn- render [state]
  (ticket/vol-range-ticket (projections/ticket-model state sf/now-ms)))

(defn- text [node] (str/join " " (hiccup/collect-strings node)))

(defn- click-actions [node]
  (->> (hiccup/find-all-nodes node #(some? (get-in % [1 :on :click])))
       (map #(get-in % [1 :on :click]))))

(defn- all-class-attrs [node]
  (keep #(get-in % [1 :class])
        (hiccup/find-all-nodes node #(contains? (or (hiccup/node-attrs %) {}) :class))))

(deftest ticket-renders-sections-and-labels-test
  (with-fees
    (fn []
      (let [html (render (-> (sf/base-state)
                             (assoc-in [:order-form :scale :vol-range :inventory :maximum] "")
                             (assoc-in [:order-form :scale :vol-range :buy :max-usdc-debit] "")))
            t (text html)]
        (doseq [s ["Accumulate buys" "Distribute sells" "Protected core" "Maximum USDC debit"
                   "Quoted buy VWAP" "Quoted sell VWAP"
                   "Set maximum to 190 and USDC cap to 2889.16"
                   "Orders fill independently. Prices stay fixed until you update them."
                   "Review 7 orders" "Model" "Inventory" "If orders fill" "Post-only"
                   "Current spot inventory" "Available to sell" "Daily volatility"
                   "Parkinson · 14-day lookback" "Sale proceeds don't fund the staged buys."]]
          (is (str/includes? t s) (str "missing: " s)))
        (is (some? (hiccup/find-by-data-role html "vol-range-use-plan-total")))
        (is (= [[:actions/apply-vol-range-remediation :fill-required-limits]]
               (get-in (hiccup/find-by-data-role html "vol-range-use-plan-total") [1 :on :click])))
        (is (= [[:actions/apply-vol-range-remediation :fill-required-limits]]
               (get-in (hiccup/find-by-data-role html "vol-range-set-maximum") [1 :on :click])))
        (is (= [[:actions/open-vol-range-review]]
               (get-in (hiccup/find-by-data-role html "vol-range-review-open") [1 :on :click])))))))

(deftest offers-render-only-when-offered-test
  (with-fees
    (fn []
      (let [html (render (sf/base-state))]
        (is (nil? (hiccup/find-by-data-role html "vol-range-use-plan-total")))
        (is (nil? (hiccup/find-by-data-role html "vol-range-set-maximum")))))))

(deftest vwap-gap-only-with-both-sides-inside-collapsed-details-test
  (with-fees
    (fn []
      (let [both (render (sf/base-state))
            gap (hiccup/find-by-data-role both "vol-range-vwap-gap")]
        (is (= :details (first gap)))
        (is (false? (get-in gap [1 :open])))
        (is (str/includes? (text gap) "Quoted VWAP gap"))
        (is (str/includes? (text gap) "is not expected profit"))
        (testing "never a headline outside the details element"
          (let [without-gap (hiccup/find-all-nodes both #(and (not= :details (first %))
                                                              (some #{"Quoted VWAP gap"} (hiccup/direct-texts %))))]
            (is (every? #(= :summary (first %)) without-gap))))
        (let [buys-only (render (assoc-in (sf/base-state) [:order-form :scale :vol-range :sell :enabled?] false))]
          (is (nil? (hiccup/find-by-data-role buys-only "vol-range-vwap-gap")))
          (is (not (str/includes? (text buys-only) "Quoted VWAP gap"))))))))

(deftest crossing-error-card-offers-remediations-test
  (with-fees
    (fn []
      (let [html (render (assoc-in (sf/base-state) [:orderbooks "@107"]
                                   {:bids [{:px "75.89"}] :asks [{:px "75.91"}]}))
            card (hiccup/find-by-data-role html "vol-range-error")]
        (is (some? card))
        (is (str/includes? (text card) "Buy at 76.4 is above the best ask 75.91"))
        (is (str/includes? (str (get-in card [1 :class])) "border-ho-warn"))
        (is (= [[[:actions/apply-vol-range-remediation :anchor-to-market]]
                [[:actions/apply-vol-range-remediation :start-buys-at]]]
               (vec (click-actions card))))
        (is (str/includes? (text card) "Anchor to market price"))
        (is (str/includes? (text card) "Start buys at 0.90σ"))
        (is (true? (get-in (hiccup/find-by-data-role html "vol-range-review-open") [1 :disabled])))))))

(deftest nothing-to-sell-hint-focuses-protected-core-test
  (with-fees
    (fn []
      (let [html (render (-> (sf/base-state)
                             (assoc-in [:order-form :scale :vol-range :inventory :protected-core] "150")))
            hint (hiccup/find-by-data-role html "vol-range-nothing-to-sell")
            core (hiccup/find-first-node html #(= "vol-range-protected-core" (get-in % [1 :id])))]
        (is (some? hint))
        (is (str/includes? (text hint) "Nothing to sell above protected core. Lower protected core to distribute inventory."))
        (is (= "vol-range-protected-core" (get-in hint [1 :for])))
        (is (= :input (first core)))))))

(deftest ticket-never-places-and-uses-vector-classes-test
  (with-fees
    (fn []
      (let [html (render (sf/base-state))
            actions (mapcat identity (concat (click-actions html)
                                             (map #(get-in % [1 :on :input])
                                                  (hiccup/find-all-nodes html #(some? (get-in % [1 :on :input]))))
                                             (map #(get-in % [1 :on :change])
                                                  (hiccup/find-all-nodes html #(some? (get-in % [1 :on :change]))))))]
        (is (seq actions))
        (is (not-any? #(#{:actions/submit-order :actions/confirm-order-submission} (first %)) actions))
        (is (not (re-find #"(?i)submit|place|commit" (pr-str (map first actions)))))
        (is (every? vector? (all-class-attrs html)))
        (testing "buttons are never submit buttons"
          (is (every? #(= "button" (get-in % [1 :type]))
                      (hiccup/find-all-nodes html #(= :button (first %))))))))))

(deftest mode-control-disables-volatility-range-with-reason-test
  (let [node (ticket/mode-control {:scale-mode :price-range :eligible? false
                                   :ineligible-reason "Volatility range is available on USDC spot markets."})
        vol (hiccup/find-by-data-role node "vol-range-mode-volatility-range")]
    (is (= "radiogroup" (get-in (hiccup/find-by-data-role node "vol-range-mode") [1 :role])))
    (is (true? (get-in vol [1 :disabled])))
    (is (str/includes? (text node) "Volatility range is available on USDC spot markets."))
    (is (= [[:actions/select-scale-mode :price-range]]
           (get-in (hiccup/find-by-data-role node "vol-range-mode-price-range") [1 :on :click])))))

(deftest inventory-track-markers-stay-within-the-bar-test
  (with-fees
    (fn []
      (let [html (render (sf/base-state))
            track (hiccup/find-by-data-role html "vol-range-inventory-track")
            markers (hiccup/find-all-nodes track #(some? (get-in % [1 :data-marker])))
            lefts (map #(js/parseFloat (get-in % [1 :style :left])) markers)]
        (is (seq lefts))
        (is (every? #(<= 0 % 100) lefts))
        (is (every? #(str/ends-with? (get-in % [1 :style :left]) "%") markers))))))

(deftest ineligible-market-shows-reason-and-no-plan-controls-test
  (with-fees
    (fn []
      (let [state (-> (sf/base-state)
                      (assoc-in [:active-market :market-type] :perp))
            model (projections/ticket-model state sf/now-ms)
            html (ticket/vol-range-ticket model)
            t (text html)]
        (is (false? (:eligible? model)))
        (is (= "Perp" (get-in model [:identity :market-type-label])))
        (is (not (str/includes? t "· Spot")))
        (is (some? (hiccup/find-by-data-role html "vol-range-ticket-ineligible")))
        (is (str/includes? t "Volatility range is available on USDC spot markets."))
        (is (nil? (hiccup/find-by-data-role html "vol-range-review-open")))
        (is (nil? (hiccup/find-by-data-role html "vol-range-model")))))))

(deftest read-only-account-shows-read-only-cue-test
  (with-fees
    (fn []
      (let [state (assoc (sf/base-state) :account-context {:spectate-mode {:active? true
                                                                          :address sf/other-owner}})
            model (projections/ticket-model state sf/now-ms)
            html (ticket/vol-range-ticket model)]
        (is (true? (:read-only? model)))
        (is (some? (hiccup/find-by-data-role html "vol-range-read-only")))))))

(deftest volatility-unavailable-offers-refresh-test
  (with-fees
    (fn []
      (let [html (render (-> (sf/base-state)
                               (assoc-in [:order-form :scale :vol-range :volatility :mode] :auto)
                               (assoc-in [:candles "@107" :1d] [])))
            button (hiccup/find-by-data-role html "vol-range-refresh-daily-candles")]
        (is (some? (hiccup/find-by-data-role html "vol-range-volatility-unavailable")))
        (is (= "button" (get-in button [1 :type])))
        (is (= [[:actions/refresh-vol-range-daily-candles]] (get-in button [1 :on :click])))))))

(deftest review-label-pluralizes-test
  (with-fees
    (fn []
      (let [state (-> (sf/base-state)
                      (assoc-in [:order-form :scale :vol-range :sell :enabled?] false)
                      (assoc-in [:order-form :scale :vol-range :buy :count] 1))
            model (projections/ticket-model state sf/now-ms)]
        (is (= 1 (count (:legs (projections/plan-for-state state sf/now-ms)))))
        (is (= "Review 1 order" (get-in model [:footer :review-label])))))))

(deftest order-form-view-shows-spectate-stop-in-volatility-range-mode-test
  (with-fees
    (fn []
      (let [state (assoc (sf/base-state) :account-context {:spectate-mode {:active? true
                                                                          :address sf/other-owner}})
            node (order-form-view/order-form-view state)
            stop (hiccup/find-by-data-role node "order-form-spectate-mode-stop")]
        (is (some? (hiccup/find-by-data-role node "vol-range-ticket")))
        (is (some? stop))
        (is (some (fn [n] (= [[:actions/stop-spectate-mode]] (get-in n [1 :on :click])))
                  (hiccup/find-all-nodes node #(some? (get-in % [1 :on :click])))))
        (is (nil? (hiccup/find-by-data-role (order-form-view/order-form-view (sf/base-state))
                                            "order-form-spectate-mode-stop")))))))

(deftest invalid-inputs-are-marked-and-described-test
  (with-fees
    (fn []
      (let [html (render (assoc-in (sf/base-state) [:order-form :scale :vol-range :inventory :maximum] "180"))
            input (hiccup/find-first-node html #(= "vol-range-maximum" (get-in % [1 :id])))
            described-by (get-in input [1 :aria-describedby])]
        (is (= "true" (get-in input [1 :aria-invalid])))
        (is (some? (hiccup/find-first-node html #(= described-by (get-in % [1 :id]))))
            "aria-describedby points at a rendered error card")
        (is (nil? (get-in (hiccup/find-first-node html #(= "vol-range-target" (get-in % [1 :id])))
                          [1 :aria-invalid])))))))

(deftest error-cards-are-not-per-card-alerts-test
  (with-fees
    (fn []
      (let [html (render (assoc-in (sf/base-state) [:orderbooks "@107"]
                                   {:bids [{:px "75.89"}] :asks [{:px "75.91"}]}))
            summary (hiccup/find-by-data-role html "vol-range-messages-summary")]
        (is (empty? (hiccup/find-all-nodes html #(= "alert" (get-in % [1 :role])))))
        (is (= "polite" (get-in summary [1 :aria-live])))
        (is (str/includes? (text summary) "to fix before review"))))))

(deftest inventory-track-values-are-readable-test
  (with-fees
    (fn []
      (let [track (hiccup/find-by-data-role (render (sf/base-state)) "vol-range-inventory-track")]
        (is (not= "img" (get-in track [1 :role])) "role=img would hide the labelled values")
        (is (str/includes? (text track) "Core 120.00"))))))

(deftest amount-unit-toggle-test
  (with-fees
    (fn []
      (testing "base mode: quantity field, unit toggle, debit field with its hint"
        (let [html (render (sf/base-state))
              unit-base (hiccup/find-by-data-role html "vol-range-buy-unit-base")
              unit-quote (hiccup/find-by-data-role html "vol-range-buy-unit-quote")]
          (is (= "true" (get-in unit-base [1 :aria-pressed])))
          (is (= "false" (get-in unit-quote [1 :aria-pressed])))
          (is (= [[:actions/update-vol-range-draft [:buy :amount-unit] :quote]]
                 (get-in unit-quote [1 :on :click])))
          (is (some? (hiccup/find-by-data-role html "vol-range-max-debit-hint")))
          (is (str/includes? (text html) "Maximum USDC debit"))))
      (testing "USDC mode: amount field, no separate debit field, note with the solved quantity"
        (let [html (render (-> (sf/base-state)
                               (assoc-in [:order-form :scale :vol-range :buy :amount-unit] :quote)
                               (assoc-in [:order-form :scale :vol-range :buy :quote-amount] "2889.16")))
              t (text html)]
          (is (= "true" (get-in (hiccup/find-by-data-role html "vol-range-buy-unit-quote") [1 :aria-pressed])))
          (is (not (str/includes? t "Maximum USDC debit")))
          (is (re-find #"Spend up to this amount, fee reserve included · 40(\.0+)? SYN" t))
          (is (some? (hiccup/find-all-nodes html #(= "vol-range-buy-quote-amount" (get-in % [1 :id]))))))))))

(deftest limit-unit-toggle-test
  (with-fees
    (fn []
      (let [html (render (sf/base-state))]
        (is (= "true" (get-in (hiccup/find-by-data-role html "vol-range-limit-unit-base") [1 :aria-pressed])))
        (is (= [[:actions/update-vol-range-draft [:inventory :limit-unit] :quote]]
               (get-in (hiccup/find-by-data-role html "vol-range-limit-unit-quote") [1 :on :click])))
        (is (nil? (hiccup/find-by-data-role html "vol-range-limit-note"))))
      (let [html (render (-> (sf/base-state)
                             (assoc-in [:order-form :scale :vol-range :inventory :limit-unit] :quote)
                             (assoc-in [:order-form :scale :vol-range :inventory :protected-core-quote] "9600")
                             (assoc-in [:order-form :scale :vol-range :inventory :target-quote] "12800")
                             (assoc-in [:order-form :scale :vol-range :inventory :maximum-quote] "15200")))
            note (text (hiccup/find-by-data-role html "vol-range-limit-note"))]
        (is (str/includes? note "Converted at the anchor price 80 ·"))
        (is (re-find #"max 190(\.0+)? SYN" note))
        (is (str/includes? (text html) "Review 7 orders"))))))
