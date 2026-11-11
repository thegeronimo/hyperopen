(ns hyperopen.outcomes.domain.answer-chart-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.outcomes.domain.answer-chart :as answer-chart]
            [hyperopen.views.trading-chart.utils.theme-colors :as theme-colors]))

(defn- side
  [coin {:keys [bid ask prev volume]}]
  (cond-> {:coin coin
           :side-index 0
           :prevDayRaw (or prev "0.5")
           :volume24h (or volume 100)}
    bid (assoc :bid bid)
    ask (assoc :ask ask)))

(defn- option
  [id label quote]
  {:outcome-id id
   :label label
   :yes-coin (str "#" (* 10 id))
   :sides [(side (str "#" (* 10 id)) quote)
           {:coin (str "#" (inc (* 10 id))) :side-index 1}]})

(def ^:private never {:prev "0.0" :volume 0})

(defn- question
  [options & {:as extra}]
  (merge {:key "question:7"
          :market-type :outcome
          :outcome-kind :question
          :title "Albania vs Finland"
          :question-options options}
         extra))

(def ^:private albania (option 1 "Albania" {:bid 0.49 :ask 0.51}))
(def ^:private draw (option 2 "Draw" {:bid 0.27 :ask 0.29}))
(def ^:private finland (option 3 "Finland" {:bid 0.21 :ask 0.23}))

(def ^:private event (question [albania draw finland]
                               :fallback-option (option 9 "Other" never)))

(deftest qualifies-only-multi-answer-questions-test
  (is (true? (answer-chart/qualifies? event)))
  (is (true? (answer-chart/qualifies? (question [albania draw]))) "two named answers are four sides")
  (is (false? (answer-chart/qualifies? (question [albania]))))
  (is (false? (answer-chart/qualifies? (assoc event :outcome-kind :ladder :outcome-shape :ladder)))
      "ladder rungs are not mutually exclusive")
  (is (false? (answer-chart/qualifies? (assoc event :outcome-kind :binary :question-options []))))
  (is (false? (answer-chart/qualifies? {:market-type :perp :coin "BTC"})))
  (is (false? (answer-chart/qualifies? nil))))

(deftest plan-ranks-by-chance-skips-never-traded-and-caps-test
  (testing "likeliest first, never-traded answers left out"
    (let [plan (answer-chart/plan (question [finland (option 4 "Nobody" never) albania draw]) :1h)]
      (is (= ["question:7" :1h ["#10" "#20" "#30"]] (:key plan)))
      (is (= ["#10" "#20" "#30"] (:coins plan)))))
  (testing "at most eight lines"
    (let [options (mapv #(option % (str "A" %) {:bid (/ % 100) :ask (/ (+ % 2) 100)}) (range 1 13))
          plan (answer-chart/plan (question options) :1d)]
      (is (= 8 (count (:coins plan))))
      (is (= "#120" (first (:coins plan))) "highest chance first")
      (is (not (some #{"#10"} (:coins plan))) "the least likely are not drawn")))
  (testing "a custom rank"
    (is (= ["#30" "#20" "#10"]
           (:coins (answer-chart/plan event :1d (fn [a] (case (:label a) "Finland" 3 "Draw" 2 1))))))))

(deftest fetch-requests-load-missing-and-refresh-cached-test
  (let [coins ["#10" "#20" "#30" "#40"]
        candles {"#10" {:1d [{:t 1 :c "0.5"}]}
                 "#20" {:1d {:rows [] :error "boom"}}
                 "#30" {:1h [{:t 1 :c "0.2"}]}
                 "#40" {:1d []}}
        now 1000000
        tail answer-chart/refresh-bars
        full answer-chart/full-bars]
    (testing "a re-plan with nothing requested yet"
      (is (= [{:coin "#10" :bars tail} {:coin "#20" :bars full} {:coin "#30" :bars full} {:coin "#40" :bars tail}]
             (answer-chart/fetch-requests coins candles :1d {:now-ms now}))))
    (testing "fresh cache: a re-plan within the refresh interval asks for nothing"
      (let [requested {"#10" {:1d (- now 59000)} "#20" {:1d (- now 1000)} "#30" {:1d (- now 61000)}}]
        (is (= [{:coin "#30" :bars full} {:coin "#40" :bars tail}]
               (answer-chart/fetch-requests coins candles :1d {:now-ms now :requested-at requested})))
        (is (= [{:coin "#30" :bars full} {:coin "#40" :bars tail}]
               (answer-chart/fetch-requests coins candles :1d {:now-ms now :requested-at (assoc-in requested ["#30" :1h] now)}))
            "freshness is per timeframe")))
    (testing "periodic refresh: tails of drawn lines, one bounded retry of a failed line, nothing in flight"
      (let [requested {"#10" {:1d (- now 60000)} "#20" {:1d (- now 60000)} "#30" {:1d (- now 1000)}}]
        (is (= [{:coin "#10" :bars tail} {:coin "#20" :bars full} {:coin "#40" :bars tail}]
               (answer-chart/fetch-requests coins candles :1d {:refresh? true :now-ms now :requested-at requested})))
        (let [after (answer-chart/note-requests requested [{:coin "#20"}] :1d now)]
          (is (= now (get-in after ["#20" :1d])))
          (is (not-any? #(= "#20" (:coin %))
                        (answer-chart/fetch-requests coins candles :1d {:refresh? true :now-ms (+ now 5000) :requested-at after}))
              "a failed line is retried at most once per refresh"))
        (is (not-any? #(= "#30" (:coin %))
                      (answer-chart/fetch-requests coins {} :1d {:refresh? true :now-ms now}))
            "a never-requested loading slot is left to the re-plan")))
    (is (= [] (answer-chart/fetch-requests [] candles :1d {:now-ms now})))))

(deftest plan-key-tracks-the-drawable-answer-set-test
  (let [k (answer-chart/plan-key event :1d)]
    (is (= ["question:7" :1d ["#10" "#20" "#30"]] k))
    (is (= k (answer-chart/plan-key (assoc-in event [:question-options 0 :sides 0 :bid] 0.7) :1d))
        "quotes never change it")
    (is (not= k (answer-chart/plan-key (update event :question-options conj (option 4 "Abandoned" {:bid 0.1 :ask 0.11})) :1d))
        "a new answer re-plans")
    (is (not= k (answer-chart/plan-key (assoc event :question-options [albania draw (option 3 "Finland" never)]) :1d))
        "an answer that stops counting as traded re-plans")))

(deftest an-answer-without-an-identifiable-yes-side-is-not-drawn-test
  (let [no-index {:outcome-id 5 :label "Mystery" :sides [{:coin "#51" :prevDayRaw "0.4"} {:coin "#50" :prevDayRaw "0.4"}]}
        by-yes-coin (assoc no-index :outcome-id 6 :label "Known" :yes-coin "#50")]
    (is (= [] (answer-chart/answers (question [no-index]))) "never guesses the first side")
    (is (= ["#50"] (mapv :coin (answer-chart/answers (question [by-yes-coin]))))
        "the side holding :yes-coin counts")))

(deftest candle-status-test
  (is (= :loading (answer-chart/candle-status nil)))
  (is (= :empty (answer-chart/candle-status [])))
  (is (= :failed (answer-chart/candle-status {:rows [] :error "x"})))
  (is (= :ready (answer-chart/candle-status {:rows [{:t 1}] :error "x"})) "cached rows still draw")
  (is (= :ready (answer-chart/candle-status [{:t 1}]))))

(deftest zero-to-hundred-formatter-test
  (is (= "50%" (answer-chart/format-chance 0.5)))
  (is (= "50.3%" (answer-chart/format-chance 0.503)))
  (is (= "20.9%" (answer-chart/format-chance 0.209)) "no float noise")
  (is (= "0%" (answer-chart/format-chance 0)))
  (is (= "0%" (answer-chart/format-chance -0.00001)))
  (is (= "100%" (answer-chart/format-chance "1")))
  (is (nil? (answer-chart/format-chance nil)))
  (is (= ["100%" "75%" "50%" "25%" "0%"]
         (answer-chart/tick-labels [1 0.75 0.5 0.25 0]))))

(deftest palette-colours-differ-in-lightness-test
  (let [lightness (fn [hex]
                    (let [n (js/parseInt (subs hex 1) 16)
                          r (bit-and (bit-shift-right n 16) 255)
                          g (bit-and (bit-shift-right n 8) 255)
                          b (bit-and n 255)]
                      (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b))))
        colours (mapv #(get theme-colors/fallbacks %) answer-chart/palette)
        [a b c] (map lightness (take 3 colours))]
    (is (= answer-chart/max-lines (count (distinct (remove nil? colours)))))
    (is (= ["#50d2c1" "#e3a04f" "#7f9bb0"] (take 3 colours)))
    (is (> a b c) "teal lightest, orange mid, slate-blue darkest")
    (is (> (- a c) 25))))

(deftest legend-model-test
  (let [candles {"#10" {:1d [{:t 1 :c "0.46"} {:t 2 :c "0.50"}]}
                 "#20" {:1d [{:t 1 :c "0.28"}]}
                 "#30" {:1d {:rows [] :error "503"}}}
        {:keys [lines notes]} (answer-chart/legend-model {:market event
                                                          :coins ["#10" "#20" "#30"]
                                                          :candles candles
                                                          :timeframe :1d
                                                          :selected-outcome-id 2
                                                          :color-fn theme-colors/fallbacks})]
    (is (= ["Albania" "Draw" "Finland"] (mapv :label lines)) "by last close, unpriced last")
    (is (= ["50%" "28%" nil] (mapv :percent-text lines)))
    (is (= ["#50d2c1" "#e3a04f" "#7f9bb0"] (mapv :color lines)) "colour follows plan order")
    (is (= [2 3 2] (mapv :width lines)) "the selected answer's line is thickest")
    (is (= [:ready :ready :failed] (mapv :status lines)))
    (is (= ["Finland · failed to load, not drawn"
            "Other · not traded, not drawn"]
           notes)))
  (testing "more answers than lines, an answer with no trades in range, a traded fallback"
    (let [options (mapv #(option % (str "A" %) {:bid 0.1 :ask 0.12}) (range 1 11))
          market (question options :fallback-option (option 99 "Other" {:bid 0.1 :ask 0.12}))
          coins (vec (take 8 (map #(str "#" (* 10 %)) (range 1 11))))
          {:keys [notes]} (answer-chart/legend-model {:market market
                                                      :coins coins
                                                      :candles {"#10" {:1d []}}
                                                      :timeframe :1d})]
      (is (= ["+2 more not drawn" "A1 · no trades in this range" "Other · not drawn"] notes))))
  (testing "no fallback, everything traded: no notes"
    (is (= [] (:notes (answer-chart/legend-model {:market (question [albania draw])
                                                  :coins ["#10" "#20"]
                                                  :candles {}
                                                  :timeframe :1d}))))))

(deftest short-label-test
  (is (= "Draw" (answer-chart/short-label "Draw")))
  (is (= "Washing…" (answer-chart/short-label "Washington Wizards"))))
