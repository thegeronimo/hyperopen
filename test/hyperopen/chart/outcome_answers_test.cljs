(ns hyperopen.chart.outcome-answers-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.chart.outcome-answers :as outcome-answers]
            [hyperopen.runtime.effect-order-contract :as effect-order-contract]
            [hyperopen.schema.contracts :as contracts]))

(defn- option
  [id label bid]
  {:outcome-id id
   :label label
   :sides [{:coin (str "#" (* 10 id)) :side-index 0 :prevDayRaw "0.4" :volume24h 10
            :bid bid :ask (+ bid 0.02)}
           {:coin (str "#" (inc (* 10 id))) :side-index 1}]})

(def ^:private event
  {:key "question:7"
   :coin "#10"
   :market-type :outcome
   :outcome-kind :question
   :outcome-option-id 1
   :question-options [(option 1 "Albania" 0.49) (option 2 "Draw" 0.27) (option 3 "Finland" 0.21)]})

(defn- state
  [& {:keys [mode plan candles timeframe market]
      :or {timeframe :1d market event}}]
  {:active-asset (:coin market)
   :active-market market
   :candles (or candles {})
   :chart-options (cond-> {:selected-timeframe timeframe :outcome-price-unit :cents}
                    mode (assoc :outcome-chart-mode mode)
                    plan (assoc :outcome-answers plan))})

(def ^:private answer-coins ["#10" "#20" "#30"])

(def ^:private plan-1d {:key ["question:7" :1d answer-coins] :coins answer-coins})

(def ^:private now 5000000)

(defn- fetch [coin bars]
  [:effects/fetch-candle-snapshot :coin coin :interval :1d :bars bars :priority :low])

(deftest turning-all-answers-on-plans-and-loads-in-one-action-test
  (let [effects (outcome-answers/set-outcome-chart-mode*
                 (state :candles {"#20" {:1d [{:t 1 :c "0.3"}]}})
                 :all now)]
    (is (= [:effects/save-many [[[:chart-options :outcome-chart-mode] :all]
                                [[:chart-options :outcome-answers]
                                 (assoc plan-1d :requested-at {"#10" {:1d now} "#20" {:1d now} "#30" {:1d now}})]]]
           (first effects)))
    (is (= [(fetch "#20" 5) (fetch "#30" 330)] (rest effects))
        "missing lines load in full, a cached one only refreshes its tail; the active coin is the candle chart's own")
    (is (= effects (effect-order-contract/assert-action-effect-order!
                    :actions/set-outcome-chart-mode effects {:phase :test}))
        "the fan-out passes the effect-order contract"))
  (testing "never touches the stored unit"
    (is (not-any? #(= :effects/local-storage-set (first %))
                  (outcome-answers/set-outcome-chart-mode (state) :all))))
  (testing "no-ops"
    (is (= [] (outcome-answers/set-outcome-chart-mode (state :mode :all :plan plan-1d) :all)))
    (is (= [] (outcome-answers/set-outcome-chart-mode (state :market {:market-type :perp :coin "BTC"}) :all)))
    (is (= [] (outcome-answers/set-outcome-chart-mode (state) :single)))
    (is (= [] (outcome-answers/set-outcome-chart-mode (state) :bogus))))
  (is (= [[:effects/save [:chart-options :outcome-chart-mode] :single]]
         (outcome-answers/set-outcome-chart-mode (state :mode :all) "single"))))

(deftest all-answers-needs-a-qualifying-active-market-test
  (is (true? (outcome-answers/all-answers? (state :mode :all))))
  (is (false? (outcome-answers/all-answers? (state :mode :single))))
  (is (false? (outcome-answers/all-answers? (assoc (state :mode :all) :active-asset "BTC")))
      "a lagging outcome market of another coin never counts")
  (is (false? (outcome-answers/all-answers?
               (state :mode :all :market (assoc event :question-options [(option 1 "A" 0.4)]))))))

(deftest sync-plans-only-when-event-or-timeframe-moved-test
  (testing "current plan: nothing to do, whatever answer is selected"
    (is (= [] (outcome-answers/sync-outcome-answer-chart (state :mode :all :plan plan-1d))))
    (is (= [] (outcome-answers/sync-outcome-answer-chart
               (state :mode :all :plan plan-1d :market (assoc event :outcome-option-id 3 :coin "#30"))))))
  (testing "a new timeframe plans again and loads what is missing"
    (let [effects (outcome-answers/sync-outcome-answer-chart* (state :mode :all :plan plan-1d :timeframe :1h) nil now)]
      (is (= ["question:7" :1h answer-coins] (get-in (first effects) [1 0 1 :key])))
      (is (= ["#20" "#30"] (mapv #(nth % 2) (rest effects))))
      (is (every? #(= :1h (nth % 4)) (rest effects)))))
  (testing "flipping back within the refresh interval asks for nothing"
    (let [plan-1h (assoc plan-1d :key ["question:7" :1h answer-coins]
                         :requested-at {"#10" {:1d (- now 10000)} "#20" {:1d (- now 10000)} "#30" {:1d (- now 10000)}})
          candles {"#10" {:1d [{:t 1 :c "0.5"}]} "#20" {:1d [{:t 1 :c "0.3"}]} "#30" {:1d [{:t 1 :c "0.2"}]}}
          effects (outcome-answers/sync-outcome-answer-chart* (state :mode :all :plan plan-1h :candles candles) nil now)]
      (is (= 1 (count effects)) "only the plan projection")
      (is (= :effects/save-many (ffirst effects)))))
  (testing "a newly traded answer re-plans the same event and timeframe"
    (let [market (update event :question-options conj (option 4 "Draw" 0.1))
          effects (outcome-answers/sync-outcome-answer-chart* (state :mode :all :plan plan-1d :market market) nil now)]
      (is (= ["#10" "#20" "#30" "#40"] (get-in (first effects) [1 0 1 :coins])))
      (is (= [(fetch "#20" 330) (fetch "#30" 330) (fetch "#40" 330)] (rest effects)))))
  (testing "the slow refresh re-reads drawn lines and retries a failed one"
    (let [candles {"#10" {:1d [{:t 1 :c "0.5"}]} "#20" {:1d {:rows [] :error "x"}} "#30" {:1d [{:t 1 :c "0.2"}]}}
          effects (outcome-answers/sync-outcome-answer-chart*
                   (state :mode :all :plan plan-1d :candles candles) {:refresh? true} now)]
      (is (= [[:effects/save [:chart-options :outcome-answers]
               (assoc plan-1d :requested-at {"#20" {:1d now} "#30" {:1d now}})]
              (fetch "#20" 330)
              (fetch "#30" 5)]
             effects))
      (is (= effects (effect-order-contract/assert-action-effect-order!
                      :actions/sync-outcome-answer-chart effects {:phase :test})))))
  (testing "nothing at all while the mode is off"
    (is (= [] (outcome-answers/sync-outcome-answer-chart (state :plan plan-1d :timeframe :1h))))
    (is (= [] (outcome-answers/sync-outcome-answer-chart (state :plan plan-1d) {:refresh? true})))))

(deftest select-asset-reset-path-value-test
  (is (= [[:chart-options :outcome-chart-mode] :single]
         (outcome-answers/reset-path-value (state :mode :all) {:market-type :perp :coin "BTC"})))
  (is (nil? (outcome-answers/reset-path-value (state :mode :all) event))
      "another answer or another multi-answer event keeps the mode")
  (is (nil? (outcome-answers/reset-path-value (state) {:market-type :perp}))))

(deftest action-args-test
  (is (= [:all] (contracts/assert-action-args! :actions/set-outcome-chart-mode [:all] {:phase :test})))
  (is (= ["single"] (contracts/assert-action-args! :actions/set-outcome-chart-mode ["single"] {:phase :test})))
  (is (thrown? js/Error (contracts/assert-action-args! :actions/set-outcome-chart-mode [:candles] {:phase :test})))
  (is (thrown? js/Error (contracts/assert-action-args! :actions/set-outcome-chart-mode [] {:phase :test})))
  (is (= [] (contracts/assert-action-args! :actions/sync-outcome-answer-chart [] {:phase :test})))
  (is (= [{:refresh? true}]
         (contracts/assert-action-args! :actions/sync-outcome-answer-chart [{:refresh? true}] {:phase :test})))
  (is (thrown? js/Error (contracts/assert-action-args! :actions/sync-outcome-answer-chart [{:coins ["#1"]}] {:phase :test})))
  (is (thrown? js/Error (contracts/assert-action-args! :actions/sync-outcome-answer-chart [true] {:phase :test}))))
