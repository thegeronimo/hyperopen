(ns hyperopen.vol-range.actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.api.projections.market :as market-projections]
            [hyperopen.config :as config]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.vol-range-draft :as draft]
            [hyperopen.vol-range.actions :as actions]
            [hyperopen.vol-range.application.draft-scope :as scope]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.fixtures :as fx]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def now sf/now-ms)

(defn- with-fees [f]
  (with-redefs [snapshots/fee-snapshot sf/with-synthetic-fees]
    (f)))

(defn- vr-draft [state] (scope/effective-draft state))

(defn- changed-paths
  "Draft paths whose values differ between two states."
  [before after]
  (let [a (vr-draft before) b (vr-draft after)]
    (vec (filter #(not= (get-in a %) (get-in b %))
                 (for [[section m] (draft/default-vol-range-draft) k (keys m)] [section k])))))

(defn- blank-limits-state []
  (-> (sf/base-state)
      (assoc-in [:order-form :scale :vol-range :inventory :maximum] "")
      (assoc-in [:order-form :scale :vol-range :buy :max-usdc-debit] "")))

(defn- crossing-state []
  (assoc-in (sf/base-state) [:orderbooks "@107"] {:bids [{:px "75.89"}] :asks [{:px "75.91"}]}))

(defn- apply-remediation [state id]
  (let [effects (actions/apply-vol-range-remediation state id now)]
    {:effects effects :state (sf/apply-effects state effects)}))

(deftest set-maximum-and-use-plan-total-test
  (with-fees
    (fn []
      (let [state (blank-limits-state)
            {joint :state joint-effects :effects} (apply-remediation state :fill-required-limits)]
        (testing "both blank: the single offered remediation fills both limits"
          (is (= "190" (get-in (vr-draft joint) [:inventory :maximum])))
          (is (= "2889.16" (get-in (vr-draft joint) [:buy :max-usdc-debit])))
          (is (= [[:inventory :maximum] [:buy :max-usdc-debit]] (changed-paths state joint)))
          (is (= [:effects/save-many] (mapv first joint-effects))))
        (testing "single-field remediations are not offered while both are blank (VR-23)"
          (is (= [] (actions/apply-vol-range-remediation state :set-maximum-all-buys now)))
          (is (= [] (actions/apply-vol-range-remediation state :use-plan-total now))))
        (testing "the remediation can also be applied by string id"
          (is (= "190" (get-in (vr-draft (:state (apply-remediation state "fill-required-limits")))
                               [:inventory :maximum]))))
        (testing "Set maximum alone is offered when the cap is already set"
          (let [only-max (assoc-in state [:order-form :scale :vol-range :buy :max-usdc-debit] "2900")
                {after :state} (apply-remediation only-max :set-maximum-all-buys)]
            (is (= "190" (get-in (vr-draft after) [:inventory :maximum])))
            (is (= [[:inventory :maximum]] (changed-paths only-max after)))))
        (testing "Use plan total alone is offered when maximum is already set"
          (let [only-cap (assoc-in state [:order-form :scale :vol-range :inventory :maximum] "190")
                {after :state} (apply-remediation only-cap :use-plan-total)]
            (is (= "2889.16" (get-in (vr-draft after) [:buy :max-usdc-debit])))))))))

(deftest crossing-remediations-write-exactly-the-documented-values-test
  (with-fees
    (fn []
      (let [state (crossing-state)
            {anchor :state} (apply-remediation state :anchor-to-market)
            {start :state} (apply-remediation state :start-buys-at)]
        (is (= :market-price (get-in (vr-draft anchor) [:anchor :mode])))
        (is (= [[:anchor :mode]] (changed-paths state anchor)))
        (is (= "0.90" (get-in (vr-draft start) [:buy :start-sigma])))
        (is (= [[:buy :start-sigma]] (changed-paths state start)))))))

(deftest unoffered-remediations-are-no-ops-test
  (with-fees
    (fn []
      (let [ready (sf/base-state)]
        (doseq [id [:set-maximum-all-buys :use-plan-total :anchor-to-market :start-buys-at
                    :widen-range "bogus" nil 42]]
          (is (= [] (actions/apply-vol-range-remediation ready id now)) (pr-str id))))
      (let [crossing (crossing-state)]
        (testing "offers that do not apply to the failing plan are not honored"
          (is (= [] (actions/apply-vol-range-remediation crossing :set-maximum-all-buys now)))
          (is (= [] (actions/apply-vol-range-remediation crossing :use-plan-total now)))
          (is (= [] (actions/apply-vol-range-remediation crossing :start-sells-at now))))))))

(deftest every-draft-write-reaches-ownership-and-clears-review-test
  (with-fees
    (fn []
      (let [state (-> (sf/base-state)
                      (assoc-in [:vol-range :frozen-review] {:status :ready})
                      (assoc-in [:vol-range :ui :review-open?] true))
            effects (actions/update-vol-range-draft state [:buy :quantity] "41")
            [[effect-id path-values]] effects
            paths (set (map first path-values))]
        (is (= :effects/save-many effect-id))
        (is (contains? paths [:order-form]))
        (is (contains? paths [:order-form-ui]))
        (is (contains? paths [:vol-range :frozen-review]))
        (is (contains? paths [:vol-range :ui :review-open?]))
        (let [next-state (sf/apply-effects state effects)]
          (is (nil? (get-in next-state [:vol-range :frozen-review])))
          (is (false? (get-in next-state [:vol-range :ui :review-open?])))
          (is (= "41" (get-in (trading/order-form-draft next-state) [:scale :vol-range :buy :quantity]))))))))

(deftest locale-aware-numeric-input-test
  (let [fr (assoc-in (sf/base-state) [:ui :locale] "fr-FR")
        written (fn [state path value]
                  (get-in (vr-draft (sf/apply-effects state (actions/update-vol-range-draft state path value)))
                          path))]
    (is (= "1234.5" (written fr [:buy :quantity] "1 234,5")))
    (is (= "1234.5" (written (sf/base-state) [:buy :quantity] "1,234.5")))
    (is (= "6.5" (written fr [:volatility :manual-daily-pct] "6,5")))
    (testing "text that does not normalize is kept as a draft state, never zero"
      (is (= "abc" (written fr [:buy :quantity] "abc")))
      (is (= "" (written fr [:buy :quantity] "")))
      (is (= "1,2,3" (written (sf/base-state) [:inventory :maximum] "1,2,3"))))
    (testing "numbers become canonical strings; integer counts stay numbers"
      (is (= "7" (written fr [:sell :quantity] 7)))
      (is (= 6 (written fr [:buy :count] 6)))
      (is (= "0.5" (written fr [:inventory :tilt-strength] 0.5))))))

(deftest update-draft-validation-test
  (let [state (sf/base-state)]
    (testing "enums and booleans are validated"
      (is (= :market-price (get-in (vr-draft (sf/apply-effects state (actions/update-vol-range-draft state [:anchor :mode] "market-price")))
                                   [:anchor :mode])))
      (is (= [] (actions/update-vol-range-draft state [:anchor :mode] "bogus")))
      (is (= [] (actions/update-vol-range-draft state [:volatility :mode] 7)))
      (is (= false (get-in (vr-draft (sf/apply-effects state (actions/update-vol-range-draft state [:sell :enabled?] "false")))
                           [:sell :enabled?])))
      (is (= [] (actions/update-vol-range-draft state [:sell :enabled?] "maybe"))))
    (testing "paths outside the whitelist are no-ops"
      (is (= [] (actions/update-vol-range-draft state [:buy :unknown] "1")))
      (is (= [] (actions/update-vol-range-draft state [:scale :mode] "price-range")))
      (is (= [] (actions/update-vol-range-draft state [] "1")))
      (is (= [] (actions/update-vol-range-draft state "buy" "1")))
      (is (= [] (actions/update-vol-range-draft state [:buy :quantity :deep] "1"))))))

(deftest sell-quantity-percent-test
  (with-fees
    (fn []
      (let [state (sf/base-state)
            qty (fn [pct] (get-in (vr-draft (sf/apply-effects state (actions/set-vol-range-sell-quantity-percent state pct now)))
                                  [:sell :quantity]))]
        (is (= "7.5" (qty 25)))
        (is (= "15" (qty "50")))
        (is (= "30" (qty 100)))
        (is (= [] (actions/set-vol-range-sell-quantity-percent state 33 now)))
        (testing "no sellable excess above the core is a no-op"
          (let [no-excess (-> state
                              (assoc-in [:order-form :scale :vol-range :inventory :protected-core] "")
                              (assoc-in [:order-form :scale :vol-range :inventory :target] ""))]
            (is (= [] (actions/set-vol-range-sell-quantity-percent no-excess 100 now)))))
        (testing "the result is floored to the lot"
          (let [odd (assoc-in state [:spot :clearinghouse-state :balances]
                              [{:coin "USDC" :token 0 :total "3000" :hold "0"}
                               {:coin "SYN" :token 150 :total "120.33" :hold "0"}])
                odd (assoc-in odd [:order-form :scale :vol-range :inventory :protected-core] "120")]
            (is (= "0.08" (get-in (vr-draft (sf/apply-effects odd (actions/set-vol-range-sell-quantity-percent odd 25 now)))
                                  [:sell :quantity])))))))))

(deftest amount-unit-switch-test
  (with-fees
    (fn []
      (let [state (sf/base-state)
            switch (fn [st side unit] (sf/apply-effects st (actions/update-vol-range-draft st [side :amount-unit] unit now)))
            to-usdc (switch state :buy :quote)]
        (testing "switching to USDC carries the plan's maximum debit, rounded up to the cent"
          (is (= :quote (get-in (vr-draft to-usdc) [:buy :amount-unit])))
          (is (= "2889.16" (get-in (vr-draft to-usdc) [:buy :quote-amount]))))
        (testing "switching back carries the solved quantity"
          (let [back (switch (assoc-in to-usdc [:order-form :scale :vol-range :buy :quote-amount] "2889.15")
                             :buy "base")]
            (is (= :base (get-in (vr-draft back) [:buy :amount-unit])))
            (is (= "39.99" (get-in (vr-draft back) [:buy :quantity])))
            (is (= "2900" (get-in (vr-draft back) [:buy :max-usdc-debit])) "an existing cap is kept")))
        (testing "a blank cap takes the amount when switching back"
          (let [blank (-> to-usdc
                          (assoc-in [:order-form :scale :vol-range :buy :quote-amount] "1000")
                          (assoc-in [:order-form :scale :vol-range :buy :max-usdc-debit] ""))
                back (switch blank :buy :base)]
            (is (= "1000" (get-in (vr-draft back) [:buy :max-usdc-debit])))))
        (testing "sells carry gross order value"
          (is (= "2652" (get-in (vr-draft (switch state :sell :quote)) [:sell :quote-amount]))))
        (testing "an unknown unit is a no-op"
          (is (= [] (actions/update-vol-range-draft state [:buy :amount-unit] :eth now))))
        (testing "percent shortcuts set a base quantity"
          (let [usdc-sells (switch state :sell :quote)
                after (sf/apply-effects usdc-sells (actions/set-vol-range-sell-quantity-percent usdc-sells 50 now))]
            (is (= :base (get-in (vr-draft after) [:sell :amount-unit])))
            (is (= "15" (get-in (vr-draft after) [:sell :quantity])))))))))

(deftest review-open-and-close-test
  (with-fees
    (fn []
      (let [state (sf/base-state)
            effects (actions/open-vol-range-review state now)
            opened (sf/apply-effects state effects)]
        (is (= [:effects/save-many] (mapv first effects)))
        (is (= :ready (get-in opened [:vol-range :frozen-review :status])))
        (is (= 7 (count (get-in opened [:vol-range :frozen-review :legs]))))
        (is (true? (get-in opened [:vol-range :ui :review-open?])))
        (is (= {:bbo {:bid "79.99" :ask "80.01" :observed-at-ms sf/book-observed-ms}
                :frozen-at-ms now
                :provenance {:bbo-observed-at-ms sf/book-observed-ms
                             :vwap-observed-at-ms sf/ctx-received-ms
                             :fee-loaded-at-ms sf/fees-loaded-ms}}
               (get-in opened [:vol-range :frozen-context])))
        (let [closed (sf/apply-effects opened (actions/close-vol-range-review opened))]
          (is (false? (get-in closed [:vol-range :ui :review-open?])))
          (is (nil? (get-in closed [:vol-range :frozen-review]))))
        (testing "an invalid or unavailable plan never opens the review"
          (is (= [] (actions/open-vol-range-review (crossing-state) now)))
          (is (= [] (actions/open-vol-range-review (assoc-in state [:order-form :scale :vol-range :buy :quantity] "") now))))))))

(deftest ui-flag-test
  (let [state (sf/base-state)]
    (is (= [[:effects/save-many [[[:vol-range :ui :show-bands?] false]]]]
           (actions/set-vol-range-ui-flag state :show-bands? false)))
    (is (= [[:effects/save-many [[[:vol-range :ui :advanced-open?] true]]]]
           (actions/set-vol-range-ui-flag state "advanced-open?" "true")))
    (is (= [] (actions/set-vol-range-ui-flag state :review-open? true)))
    (is (= [] (actions/set-vol-range-ui-flag state :show-bands? "maybe")))))

(deftest select-scale-mode-test
  (let [legacy (-> (sf/base-state)
                   (assoc-in [:order-form :scale] {:start "" :end "" :count 5 :skew "1.00"})
                   (update :vol-range dissoc :draft-binding :daily-fetch))]
    (testing "selecting Volatility range saves first, then fetches 16 daily candles"
      (let [effects (actions/select-scale-mode legacy :volatility-range now)]
        (is (= [:effects/save-many :effects/fetch-candle-snapshot] (mapv first effects)))
        (is (= [:effects/fetch-candle-snapshot :coin "@107" :interval :1d :bars 16] (second effects)))
        (let [next-state (sf/apply-effects legacy effects)
              form (trading/order-form-draft next-state)]
          (is (= :volatility-range (get-in form [:scale :mode])))
          (is (= (draft/default-vol-range-draft) (get-in form [:scale :vol-range])))
          (is (= (scope/current-scope-key next-state) (scope/draft-binding next-state)))
          (is (= now (get-in next-state [:vol-range :daily-fetch "@107" :requested-at-ms])))
          (testing "a request that has not completed throttles retries for the retry window"
            (is (= [:effects/save-many]
                   (mapv first (actions/select-scale-mode next-state :volatility-range (+ now 29000)))))
            (is (= [:effects/save-many :effects/fetch-candle-snapshot]
                   (mapv first (actions/select-scale-mode next-state :volatility-range (+ now 30001))))
                "a failed fetch retries after 30s instead of waiting for the TTL"))
          (testing "a fresh successful fetch is not requested again, and keeps its stamp"
            (let [done (assoc-in next-state [:vol-range :daily-fetch "@107" :completed-at-ms] now)]
              (is (= [:effects/save-many] (mapv first (actions/select-scale-mode done :volatility-range (+ now 840000)))))
              (is (= [:effects/save-many :effects/fetch-candle-snapshot]
                     (mapv first (actions/select-scale-mode done :volatility-range (+ now 900001)))))
              (let [refreshed (sf/apply-effects done (actions/refresh-vol-range-daily-candles done (+ now 5000)))]
                (is (= now (get-in refreshed [:vol-range :daily-fetch "@107" :completed-at-ms]))
                    "recording a request never erases the success stamp"))))
          (testing "a UTC day rollover fetches even inside the TTL"
            (let [day-end (+ (* 86400000 (js/Math.floor (/ now 86400000))) 86400000)
                  late (assoc-in next-state [:vol-range :daily-fetch "@107"]
                                 {:completed-at-ms (- day-end 60000) :requested-at-ms (- day-end 60000)})]
              (is (= [:effects/save-many :effects/fetch-candle-snapshot]
                     (mapv first (actions/select-scale-mode late :volatility-range (+ day-end 60000))))))))))
    (testing "selecting Price range only writes the mode"
      (let [effects (actions/select-scale-mode (sf/base-state) "price-range" now)]
        (is (= [:effects/save-many] (mapv first effects)))
        (is (= :price-range (get-in (trading/order-form-draft (sf/apply-effects (sf/base-state) effects))
                                    [:scale :mode])))))
    (testing "invalid modes are no-ops"
      (is (= [] (actions/select-scale-mode legacy :bogus now)))
      (is (= [] (actions/select-scale-mode legacy nil now))))
    (testing "non-spot markets never fetch"
      (let [perp (assoc legacy :active-market {:coin "BTC" :market-type :perp :base "BTC" :quote "USDC"})]
        (is (= [:effects/save-many] (mapv first (actions/select-scale-mode perp :volatility-range now))))))))

(deftest refresh-daily-candles-test
  (let [state (sf/base-state)
        effects (actions/refresh-vol-range-daily-candles state now)]
    (is (= [:effects/save-many :effects/fetch-candle-snapshot] (mapv first effects)))
    (is (= [:effects/fetch-candle-snapshot :coin "@107" :interval :1d :bars 16] (second effects)))
    (is (= now (get-in (sf/apply-effects state effects) [:vol-range :daily-fetch "@107" :requested-at-ms])))
    (is (= [] (actions/refresh-vol-range-daily-candles (dissoc state :active-market) now)))))

(deftest scope-rebind-happens-on-every-draft-write-test
  (with-fees
    (fn []
      (let [other (assoc (sf/base-state) :active-asset "@108" :active-market (sf/market "@108" 108 "OTH"))
            effects (actions/update-vol-range-draft other [:buy :quantity] "5")
            next-state (sf/apply-effects other effects)]
        (is (= (scope/current-scope-key other) (scope/draft-binding next-state)))
        (is (= "5" (get-in (vr-draft next-state) [:buy :quantity])))
        (is (= "" (get-in (vr-draft next-state) [:inventory :maximum])))
        (is (= "190" (get (get-in next-state [:vol-range :drafts (scope/current-scope-key (sf/base-state))])
                          [:inventory :maximum])))
        (is (some? fx/draft-13-2))))))

(deftest daily-refresh-due-test
  (let [day-start (* 86400000 (js/Math.floor (/ now 86400000)))
        rollover (+ day-start 86400000 1800000)
        state (sf/base-state)]
    (testing "fresh data is not due"
      (is (false? (actions/daily-refresh-due? state now))))
    (testing "a visible ticket with data fetched on the previous UTC day is due"
      (is (true? (actions/daily-refresh-due? state rollover))))
    (testing "an expired TTL is due"
      (is (true? (actions/daily-refresh-due? state (+ now 900001)))))
    (testing "a recent request suppresses the refetch until the retry window passes"
      (let [requested (assoc-in state [:vol-range :daily-fetch "@107" :requested-at-ms] (- rollover 10000))]
        (is (false? (actions/daily-refresh-due? requested rollover)))
        (is (true? (actions/daily-refresh-due? requested (+ rollover 25000))))))
    (testing "never due when the ticket is not visible or the market is not spot"
      (is (false? (actions/daily-refresh-due? (assoc-in state [:order-form :scale :mode] :price-range) rollover)))
      (is (false? (actions/daily-refresh-due?
                   (assoc state :active-market {:coin "BTC" :market-type :perp :base "BTC" :quote "USDC"})
                   rollover))))
    (testing "never due with the preview capability off"
      (with-redefs [config/vol-range-preview-enabled? (fn [] false)]
        (is (false? (actions/daily-refresh-due? state rollover)))))))

(deftest candle-success-stamps-completion-test
  (let [stamped (market-projections/apply-candle-snapshot-success {} "@107" :1d [] 12345)]
    (is (= 12345 (get-in stamped [:vol-range :daily-fetch "@107" :completed-at-ms])))
    (is (nil? (get-in (market-projections/apply-candle-snapshot-success {} "@107" :1h [] 12345)
                      [:vol-range :daily-fetch "@107"])))
    (is (= 12345 (get-in (market-projections/apply-candle-snapshot-error stamped "@107" :1d (js/Error. "x"))
                         [:vol-range :daily-fetch "@107" :completed-at-ms]))
        "a failed fetch neither stamps nor erases the previous success")))

(deftest preview-capability-gates-select-scale-mode-test
  (with-redefs [config/vol-range-preview-enabled? (fn [] false)]
    (is (= [] (actions/select-scale-mode (sf/base-state) :volatility-range now)))
    (is (= [:effects/save-many] (mapv first (actions/select-scale-mode (sf/base-state) :price-range now))))))
