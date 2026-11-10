(ns hyperopen.outcomes.domain.events-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.domain.events :as events]
            [hyperopen.outcomes.test-support.fixture :as fixture]))

(def ^:private touch-key
  "ladder:price-touch|BTC|out|BTC-USDC perp mark|3|20261201-0000")

(defn- built-markets
  []
  (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs))

(defn- by-key
  [markets]
  (into {} (map (juxt :key identity)) markets))

(defn- visible-strings
  "Every string a person can read on a built market: titles, answers, sides."
  [market]
  (concat [(:title market) (:symbol market) (:outcome-summary market)]
          (map :label (:question-options market))
          (map :side-name (:outcome-sides market))
          (mapcat #(map :side-name (:sides %)) (:question-options market))
          [(get-in market [:fallback-option :label])]))

(deftest no-template-key-reaches-a-visible-string-test
  (let [strings (remove nil? (mapcat visible-strings (built-markets)))]
    (is (seq strings))
    (is (empty? (filter #(str/includes? % "template") strings)))
    (is (empty? (filter #(re-find #"\{[A-Za-z]+\}" %) strings)))))

(deftest events-group-into-their-shapes-test
  (let [markets (built-markets)
        market-by-key (by-key markets)]
    (is (= #{"outcome:27037"
             "question:614" "question:430" "question:545" "question:627"
             "outcome:30011" "outcome:5112" "outcome:29614" "outcome:99999"
             touch-key
             ;; fox and out settle the same strike time on different rules:
             ;; two singles, not one ladder.
             "outcome:30516" "outcome:30520"
             "ladder:company-ipo-market-cap|Anthropic|txyz|20261231-2359"
             "ladder:company-ipo-market-cap|Anthropic|out|20270101-0000"
             "ladder:company-ipo-market-cap|Anthropic|skew|20270501-0359"}
           (set (keys market-by-key))))
    (is (= {:head-to-head 1 :pick-one 3 :field 1 :ladder 4 :yes-no 6}
           (frequencies (map :outcome-shape markets))))))

(deftest head-to-head-market-test
  (let [game (get (by-key (built-markets)) "outcome:27037")]
    (is (= "Detroit Pistons vs Washington Wizards" (:title game)))
    (is (= ["DET" "WAS"] (mapv :side-name (:outcome-sides game))))
    (is (= :sports (:outcome-category game)))
    (is (= "NBA" (:outcome-competition game)))
    (is (= "out" (:outcome-venue game)))
    ;; No book has arrived: the summary never prints the mark or the mid.
    (is (= "DET —  *  WAS —" (:outcome-summary game)))
    (is (= 0.81894 (:mid (first (:outcome-sides game)))))))

(deftest pick-one-question-carries-its-fallback-test
  (let [fed (get (by-key (built-markets)) "question:545")]
    (is (= "Fed — December 2026 decision" (:title fed)))
    (is (= #{"Hike" "Hold" "Cut"} (set (map :label (:question-options fed)))))
    (is (= "Other" (get-in fed [:fallback-option :label])))
    (is (= "#199870" (get-in fed [:fallback-option :yes-coin])))
    (is (= :economics (:outcome-category fed)))
    (is (= :decision (get-in fed [:outcome-clock :kind])))))

(deftest default-answer-skips-untraded-placeholders-test
  (let [range-question (get (by-key (built-markets)) "question:627")]
    (is (= ["Below $72,967" "$72,967 – $75,945" "Above $75,945"]
           (mapv :label (:question-options range-question))))
    (is (not= 0.5 (:mark range-question)))))

(deftest price-touch-ladder-test
  (let [ladder (get (by-key (built-markets)) touch-key)
        thresholds (mapv :threshold (:question-options ladder))]
    (is (= :ladder (:outcome-kind ladder)))
    (is (= "Bitcoin reaches…" (:title ladder)))
    (is (= (vec (sort > thresholds)) thresholds))
    (is (= ["$90,000" "$85,000" "$70,000" "$65,000"]
           (mapv :label (:question-options ladder))))
    (is (= :touch (:outcome-ladder-kind ladder)))
    (is (= "out" (:outcome-venue ladder)))
    (is (= 8 (count (:outcome-subscription-coins ladder))))
    (testing "every rung coin resolves back to the ladder"
      (let [market-by-key (by-key (built-markets))]
        (doseq [coin (:outcome-subscription-coins ladder)]
          (is (= (:key ladder) (:key (markets/resolve-market-by-coin market-by-key coin)))))))
    (testing "selecting a rung's No side makes that rung the active option"
      (let [rung (last (:question-options ladder))
            active (markets/market-with-selected-outcome-coin ladder (:no-coin rung))]
        (is (= (:no-coin rung) (:coin active)))
        (is (= 1 (:outcome-side-index active)))
        (is (= (:outcome-id rung) (:outcome-option-id active)))))))

(deftest ladders-are-one-venue-source-window-and-deadline-test
  (let [market-by-key (by-key (built-markets))
        txyz (get market-by-key "ladder:company-ipo-market-cap|Anthropic|txyz|20261231-2359")
        skew (get market-by-key "ladder:company-ipo-market-cap|Anthropic|skew|20270501-0359")]
    (testing "different venues never merge"
      (is (nil? (some #(str/starts-with? % "ladder:binary-price") (keys market-by-key))))
      (is (= "fox" (:outcome-venue (get market-by-key "outcome:30516"))))
      (is (= "out" (:outcome-venue (get market-by-key "outcome:30520")))))
    (testing "each venue's IPO rungs are their own ladder with their own deadline"
      (is (= ["≥ $1.75T" "≥ $1.5T"] (mapv :label (:question-options txyz))))
      (is (= ["txyz"] (:outcome-venues txyz)))
      (is (= "txyz" (:outcome-venue txyz)))
      (is (= (js/Date.UTC 2027 4 1 3 59) (get-in skew [:outcome-clock :at-ms])))
      (is (= :companies (:outcome-category txyz))))
    (testing "rungs carry their rule-set key for the order check"
      (is (every? #(= (subs (:key txyz) 7) (:ladder-key %)) (:question-options txyz))))))

(deftest ladder-key-needs-source-and-window-to-match-test
  (let [entry (fn [id source seconds]
                {:outcome id :name "template:priceTouch"
                 :description (str "perp:BTC|priceDescription:" source "|seconds:" seconds
                                   "|target:" (+ 80000 id) "|time:20261201-0000")
                 :sideSpecs [{:name "template:Yes"} {:name "template:No"}] :quoteToken "USDC" :venue "skew"})
        built (markets/build-outcome-markets {:outcomes [(entry 1 "BTC perp trades" 90)
                                                         (entry 2 "BTC perp trades" 90)
                                                         (entry 3 "Pyth BTC/USD" 90)
                                                         (entry 4 "BTC perp trades" 60)]
                                              :questions []}
                                             [])]
    (is (= 1 (count (filter #(= :ladder (:outcome-shape %)) built))))
    (is (= 2 (count (:question-options (first (filter #(= :ladder (:outcome-shape %)) built))))))
    (is (= 3 (count built)))))

(deftest unknown-template-market-reads-as-words-test
  (let [weather (get (by-key (built-markets)) "outcome:99999")]
    (is (str/starts-with? (:title weather) "Weather high temperature"))
    (is (= :other (:outcome-category weather)))))

(deftest touch-direction-comes-from-the-live-price-test
  (is (= :up (events/rung-direction :touch 80 75)))
  (is (= :down (events/rung-direction :touch 70 75)))
  (is (nil? (events/rung-direction :touch 70 nil)) "no live price, no direction")
  (is (= :up (events/rung-direction :above 75000 nil)))
  (is (= :up (events/rung-direction :at-least 1500 nil)))
  (is (= "Rises to $80" (events/direction-label :touch :up "$80")))
  (is (= "Falls to $70" (events/direction-label :touch :down "$70")))
  (is (= "$80" (events/direction-label :touch nil "$80"))))

(deftest order-check-compares-every-easier-rung-test
  (testing "board 4d, txyz Anthropic column: $2.25T and $2.5T are both out of order"
    (let [rows [{:outcome-id 1 :threshold 1500 :chance 0.626 :label "≥ $1.5T" :venue "txyz" :ladder-key "k"}
                {:outcome-id 2 :threshold 1750 :chance 0.537 :label "≥ $1.75T" :venue "txyz" :ladder-key "k"}
                {:outcome-id 3 :threshold 2000 :chance 0.118 :label "≥ $2T" :venue "txyz" :ladder-key "k"}
                {:outcome-id 4 :threshold 2250 :chance 0.250 :label "≥ $2.25T" :venue "txyz" :ladder-key "k"}
                {:outcome-id 5 :threshold 2500 :chance 0.145 :label "≥ $2.5T" :venue "txyz" :ladder-key "k"}]
          flags (events/rung-order-flags rows :at-least nil)]
      (is (= #{4 5} (set (keys flags))))
      (is (= {:than-outcome-id 3 :than-label "≥ $2T" :than-chance 0.118 :text "Out of order"}
             (get flags 5))
          "$2.5T is compared with the least likely easier rung, not only its neighbour")))
  (testing "within half a point is not flagged"
    (is (empty? (events/rung-order-flags [{:outcome-id 1 :threshold 2250 :chance 0.027 :venue "out"}
                                          {:outcome-id 2 :threshold 2500 :chance 0.030 :venue "out"}]
                                         :at-least nil))))
  (testing "venues and rule sets are never compared"
    (is (empty? (events/rung-order-flags [{:outcome-id 1 :threshold 1500 :chance 0.10 :venue "out"}
                                          {:outcome-id 2 :threshold 1750 :chance 0.50 :venue "skew"}]
                                         :at-least nil)))
    (is (empty? (events/rung-order-flags [{:outcome-id 1 :threshold 1500 :chance 0.10 :venue "out" :ladder-key "a"}
                                          {:outcome-id 2 :threshold 1750 :chance 0.50 :venue "out" :ladder-key "b"}]
                                         :at-least nil))))
  (testing "a touch ladder checks each direction on its own (board 4d HYPE)"
    (let [rows [{:outcome-id 100 :threshold 100 :chance 0.04}
                {:outcome-id 95 :threshold 95 :chance 0.08}
                {:outcome-id 90 :threshold 90 :chance 0.18}
                {:outcome-id 80 :threshold 80 :chance 0.68}
                {:outcome-id 70 :threshold 70 :chance 0.68}
                {:outcome-id 60 :threshold 60 :chance 0.75}]]
      (is (= #{60} (set (keys (events/rung-order-flags rows :touch 75))))
          "$80 and $70 are both 68% and in order; $60 above $70's chance is out of order")
      (is (empty? (events/rung-order-flags rows :touch nil)) "no live price, no check")))
  (testing "rungs without a midpoint chance are skipped"
    (is (empty? (events/rung-order-flags [{:outcome-id 1 :threshold 1 :chance nil}
                                          {:outcome-id 2 :threshold 2 :chance 0.9}]
                                         :above nil)))))

(deftest selected-rung-drives-clock-rules-and-venue-test
  (let [ladder (get (by-key (built-markets)) touch-key)
        rung (last (:question-options ladder))
        selected (assoc ladder
                        :outcome-option-id (:outcome-id rung)
                        :question-options (assoc-in (:question-options ladder) [3 :rules] "Rung rules.")
                        :outcome-rules "Ladder rules.")
        facts (events/selected-rung-facts selected)]
    (is (= (str events/ladder-note " Rung rules.") (:rules facts)))
    (is (= (:clock rung) (:clock facts)))
    (is (= "out" (:venue facts)))))
