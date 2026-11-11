(ns hyperopen.outcomes.names-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.outcomes.names :as names]))

(def game
  {:key "outcome:27037"
   :coin "#270370"
   :title "Detroit Pistons vs Washington Wizards"
   :market-type :outcome
   :outcome-shape :head-to-head
   :outcome-sides [{:side-index 0 :side-name "DET" :coin "#270370"}
                   {:side-index 1 :side-name "WAS" :coin "#270371"}]})

(def champion
  {:key "question:1"
   :coin "#10"
   :title "NBA champion 2026/2027"
   :market-type :outcome
   :outcome-sides [{:side-index 0 :side-name "Yes" :coin "#10"}]
   :question-options [{:label "Thunder"
                       :sides [{:side-index 0 :side-name "Yes" :coin "#10"}
                               {:side-index 1 :side-name "No" :coin "#11"}]}]})

(def ladder
  {:key "ladder:btc"
   :coin "#20"
   :title "Bitcoin above"
   :market-type :outcome
   :outcome-kind :ladder
   :question-options [{:label "$80"
                       :sides [{:side-index 0 :side-name "Yes" :coin "#20"}
                               {:side-index 1 :side-name "No" :coin "#21"}]}]})

(deftest coin-tokens-test
  (is (names/outcome-coin? "#270370"))
  (is (names/outcome-coin? "+270370"))
  (is (not (names/outcome-coin? "BTC")))
  (is (= "#270370" (names/canonical-coin "+270370"))))

(deftest short-title-test
  (is (= "Pistons vs Wizards" (names/short-title "Detroit Pistons vs Washington Wizards")))
  (is (= "France vs Italy" (names/short-title "France vs Italy")))
  (is (= "NBA champion 2026/2027" (names/short-title "NBA champion 2026/2027"))))

(deftest side-labels-read-event-answer-and-side-test
  (is (= "DET" (:side-label (names/describe game "#270370"))))
  (is (= "Pistons vs Wizards" (:event-short (names/describe game "#270370"))))
  (is (= "Detroit Pistons vs Washington Wizards · WAS" (:label (names/describe game "+270371"))))
  (is (= "Thunder Yes" (:side-label (names/describe champion "#10"))))
  (is (= "Thunder No" (:side-label (names/describe champion "#11"))))
  (is (= "No on $80" (:side-label (names/describe ladder "#21"))))
  (is (= "Yes on $80" (:side-label (names/describe ladder "#20")))))

(deftest resolve-coin-uses-the-catalog-test
  (let [catalog {"outcome:27037" game}]
    (is (= "DET · Pistons vs Wizards" (:short-label (names/resolve-coin catalog "#270370"))))
    (is (nil? (names/resolve-coin catalog "BTC")))
    (is (nil? (names/resolve-coin {} "#270370")))))

(deftest price-and-share-text-test
  (is (= "15.6¢" (names/cents 0.156325)))
  (is (= "122 shares" (names/shares-text 122)))
  (is (= "1 share" (names/shares-text "1"))))
