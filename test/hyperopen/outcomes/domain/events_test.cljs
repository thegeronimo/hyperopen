(ns hyperopen.outcomes.domain.events-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.test-support.fixture :as fixture]))

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
             "ladder:price-touch|BTC|20261201-0000"
             "ladder:binary-price|BTC|20261109-1630"
             "ladder:company-ipo-market-cap|Anthropic"}
           (set (keys market-by-key))))
    (is (= {:head-to-head 1 :pick-one 3 :field 1 :ladder 3 :yes-no 4}
           (frequencies (map :outcome-shape markets))))))

(deftest head-to-head-market-test
  (let [game (get (by-key (built-markets)) "outcome:27037")]
    (is (= "Detroit Pistons vs Washington Wizards" (:title game)))
    (is (= ["DET" "WAS"] (mapv :side-name (:outcome-sides game))))
    (is (= :sports (:outcome-category game)))
    (is (= "NBA" (:outcome-competition game)))
    (is (= "out" (:outcome-venue game)))
    (is (= "DET 82%  *  WAS 18%" (:outcome-summary game)))
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
  (let [ladder (get (by-key (built-markets)) "ladder:price-touch|BTC|20261201-0000")
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

(deftest multi-venue-ladders-name-the-venue-on-each-rung-test
  (let [market-by-key (by-key (built-markets))
        strikes (get market-by-key "ladder:binary-price|BTC|20261109-1630")
        ipo (get market-by-key "ladder:company-ipo-market-cap|Anthropic")]
    (is (= ["$75,150 · fox" "$75,101 · out"] (mapv :label (:question-options strikes))))
    (is (nil? (:outcome-venue strikes)))
    (is (= 6 (count (:question-options ipo))))
    (is (= #{"out" "skew" "txyz"} (set (:outcome-venues ipo))))
    (is (every? #(re-find #"^≥ \$\d.*· (out|skew|txyz)$" %) (map :label (:question-options ipo))))
    (is (= :companies (:outcome-category ipo)))))

(deftest unknown-template-market-reads-as-words-test
  (let [weather (get (by-key (built-markets)) "outcome:99999")]
    (is (str/starts-with? (:title weather) "Weather high temperature"))
    (is (= :other (:outcome-category weather)))))
