(ns hyperopen.views.trading-chart.answers-vm-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.answers-chart :as answers-chart]
            [hyperopen.views.trading-chart.answers-vm :as answers-vm]))

(defn- option
  [id label]
  {:outcome-id id
   :label label
   :sides [{:coin (str "#" (* 10 id)) :side-index 0 :prevDayRaw "0.4"}
           {:coin (str "#" (inc (* 10 id))) :side-index 1}]})

(def ^:private event
  {:key "question:7"
   :title "Albania vs Finland"
   :coin "#20"
   :market-type :outcome
   :outcome-kind :question
   :outcome-option-id 2
   :selected-outcome-option-label "Draw"
   :question-options [(option 1 "Albania") (option 2 "Draw") (option 3 "Finland")]})

(defn- state
  [& {:keys [mode plan candles timeframe market] :or {timeframe :1d market event}}]
  {:active-asset (:coin market)
   :active-market market
   :candles (or candles {})
   :chart-options (cond-> {:selected-timeframe timeframe}
                    mode (assoc :outcome-chart-mode mode)
                    plan (assoc :outcome-answers plan))})

(def ^:private plan {:key ["question:7" :1d ["#10" "#20" "#30"]] :coins ["#10" "#20" "#30"]})

(def ^:private candles
  {"#10" {:1d [{:t 1 :c "0.5"}]}
   "#20" {:1d [{:t 1 :c "0.28"}]}})

(deftest chart-mode-control-test
  (is (= {:mode :single :selected-label "Draw"} (answers-vm/chart-mode-control (state))))
  (is (= {:mode :all :selected-label "Draw"} (answers-vm/chart-mode-control (state :mode :all))))
  (is (nil? (answers-vm/chart-mode-control (state :market {:market-type :perp :coin "BTC"}))))
  (is (nil? (answers-vm/chart-mode-control (assoc (state) :active-asset "BTC")))))

(deftest answers-view-model-test
  (let [vm (answers-vm/answers-view-model (state :mode :all :plan plan :candles candles) :dispatch)]
    (is (= ["question:7" :1d ["#10" "#20" "#30"]] (:request-key vm) (:plan-key vm)))
    (is (= "--ho-chart-answer-1" (:color (first (:lines vm)))) "lines carry theme tokens")
    (is (= ["Albania" "Draw" "Finland"] (mapv :label (:lines vm))))
    (is (= [false true false] (mapv :selected? (:lines vm))) "the selected answer is emphasised")
    (is (true? (:loading? vm)) "Finland has no slot yet")
    (testing "rendered legend and canvas"
      (let [view (answers-chart/answers-chart vm)]
        (is (= "outcome-answers-chart" (get-in view [1 :data-role])))
        (is (= "outcome-answers-question:7" (get-in view [1 :replicant/key]))
            "keyed on the event, never on the selected answer")
        (is (some #(= "(selected)" %) (flatten view)) "the selected answer is named for assistive tech"))))
  (testing "selecting another answer of the event keeps the plan and moves the emphasis"
    (let [vm (answers-vm/answers-view-model
              (state :mode :all :plan plan :candles candles :market (assoc event :outcome-option-id 1 :coin "#10"))
              :dispatch)]
      (is (= (:request-key vm) (:plan-key vm)))
      (is (= [true false false] (mapv :selected? (:lines vm))))))
  (testing "a new timeframe asks for a plan and keeps the event's lines meanwhile"
    (let [vm (answers-vm/answers-view-model (state :mode :all :plan plan :timeframe :1h) :dispatch)]
      (is (= ["question:7" :1h ["#10" "#20" "#30"]] (:request-key vm)))
      (is (not= (:request-key vm) (:plan-key vm)))
      (is (= 3 (count (:lines vm))))))
  (testing "another event never draws the last event's coins"
    (let [vm (answers-vm/answers-view-model
              (state :mode :all :plan plan :market (assoc event :key "question:8"))
              :dispatch)]
      (is (= [] (:lines vm))))))
