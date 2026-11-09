(ns hyperopen.outcomes.domain.templates-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.outcomes.domain.templates :as templates]
            [hyperopen.outcomes.test-support.fixture :as fixture]))

(defn- outcome-entry
  [outcome-id]
  (some #(when (= outcome-id (:outcome %)) %) (:outcomes fixture/outcome-meta)))

(defn- question-entry
  [question-id]
  (some #(when (= question-id (:question %)) %) (:questions fixture/outcome-meta)))

(defn- describe
  [outcome-id]
  (let [{:keys [name description venue sideSpecs]} (outcome-entry outcome-id)]
    (templates/describe-outcome {:name name
                                 :description description
                                 :venue venue
                                 :side-specs sideSpecs})))

(deftest parse-params-splits-each-field-on-its-first-colon-test
  (is (= {"perp" "xyz:SP500" "threshold" "7376" "time" "20261109-2100"}
         (templates/parse-params "perp:xyz:SP500|threshold:7376|time:20261109-2100")))
  (is (= {"competition" "NBA"} (templates/parse-params "competition:NBA |other")))
  (is (= {} (templates/parse-params nil))))

(deftest template-id-normalizes-every-live-key-test
  (is (= :sports-contest-winner (templates/template-id "template:sportsContestWinner" {})))
  (is (= :fallback (templates/template-id "template fallback" {})))
  (is (= :fallback (templates/template-id "Recurring Fallback" {})))
  (is (= :recurring-bucket-option (templates/template-id "Recurring Named Outcome" {})))
  (is (= :recurring-price-binary (templates/template-id "Recurring" {"class" "priceBinary"})))
  (is (= :recurring-price-bucket (templates/template-id "Recurring" {"class" "priceBucket"})))
  (is (= :unknown (templates/template-id "template:somethingNew" {})))
  (is (= :plain (templates/template-id "2026 World Cup Champion" {}))))

(deftest head-to-head-game-reads-as-two-teams-test
  (let [facts (describe 27037)]
    (is (= :head-to-head (:shape facts)))
    (is (= "Detroit Pistons vs Washington Wizards" (:title facts)))
    (is (= ["DET" "WAS"] (:side-labels facts)))
    (is (= ["Detroit Pistons" "Washington Wizards"] (:side-long-labels facts)))
    (is (= :sports (:category facts)))
    (is (= :basketball (:subcategory facts)))
    (is (= "NBA" (:competition facts)))
    (is (= "out" (:venue facts)))
    (is (= {:kind :starts
            :at-ms (js/Date.UTC 2026 10 10 0 0)
            :deadline-ms (js/Date.UTC 2026 10 17 0 0)}
           (:clock facts)))
    (is (re-find #"wins the full game including overtime, as reported by National Basketball Association \(NBA\)"
                 (:rules facts)))
    (is (re-find #"Scheduled start Nov 10, 2026 00:00 UTC; must resolve by Nov 17, 2026 00:00 UTC\."
                 (:rules facts)))))

(deftest question-options-render-their-own-labels-test
  (is (= "Italy" (:option-label (describe 29069))))
  (is (= "Draw" (:option-label (describe 29070))))
  (is (= "Other" (:option-label (describe 29068))))
  (is (= "Hike" (:option-label (describe 19990))))
  (is (= "Hold" (:option-label (describe 19988))))
  (is (= "Cut" (:option-label (describe 19989))))
  (is (= "Oklahoma City Thunder" (:option-label (describe 11492)))))

(deftest questions-read-as-events-test
  (testing "soccer result"
    (let [facts (templates/describe-question (question-entry 614))]
      (is (= :pick-one (:shape facts)))
      (is (= "Italy vs France" (:title facts)))
      (is (= "UEFA Nations League" (:competition facts)))
      (is (= :starts (get-in facts [:clock :kind])))))
  (testing "season field"
    (let [facts (templates/describe-question (question-entry 430))]
      (is (= :field (:shape facts)))
      (is (= "NBA champion 2026/2027" (:title facts)))
      (is (= :resolves (get-in facts [:clock :kind])))))
  (testing "rate decision"
    (let [facts (templates/describe-question (question-entry 545))]
      (is (= "Fed — December 2026 decision" (:title facts)))
      (is (= :economics (:category facts)))
      (is (= :decision (get-in facts [:clock :kind])))
      (is (re-find #"Hike means an increase, Hold no change, Cut a decrease" (:rules facts)))))
  (testing "native daily range"
    (let [facts (templates/describe-question (question-entry 627))]
      (is (= "Bitcoin price range" (:title facts)))
      (is (= ["Below $72,967" "$72,967 – $75,945" "Above $75,945"] (:bucket-labels facts)))
      (is (= :daily (:subcategory facts))))))

(deftest price-contracts-name-the-asset-and-level-test
  (let [touch (describe 22079)
        nvda (describe 29614)
        daily (describe 30011)]
    (is (= :yes-no (:shape touch)))
    (is (re-find #"^Bitcoin reaches \$\d{2},000$" (:title touch)))
    (is (= "Bitcoin reaches…" (get-in touch [:ladder :title])))
    (is (= :touch (get-in touch [:ladder :kind])))
    (is (= :ends (get-in touch [:clock :kind])))
    (is (= :crypto (:category touch)))
    (is (= ["Yes" "No"] (:side-labels touch)))
    (is (= "NVDA above $183.33" (:title nvda)))
    (is (= :stocks (:category nvda)))
    (is (= "xyz:NVDA" (:subject nvda)))
    (is (re-find #"^Bitcoin above \$7\d,\d{3}$" (:title daily)))
    (is (= :settles (get-in daily [:clock :kind])))))

(deftest company-contracts-test
  (let [cap (describe 19821)
        ipo (describe 5112)]
    (is (re-find #"^Anthropic first-day market cap ≥ \$\d" (:title cap)))
    (is (= "Anthropic first-day market cap" (get-in cap [:ladder :title])))
    (is (= :companies (:category cap)))
    (is (= :listing (get-in cap [:clock :kind])))
    (is (= "Anthropic IPO" (:title ipo)))
    (is (= :ends (get-in ipo [:clock :kind])))))

(deftest unknown-templates-never-show-their-key-test
  (let [facts (describe 99999)]
    (is (= :unknown (:template facts)))
    (is (= "Weather high temperature — City: Austin · Threshold f: 95 · Date time: 20261115-2300"
           (:title facts)))
    (is (= :other (:category facts)))
    (is (= ["Yes" "No"] (:side-labels facts)))
    (is (= :ends (get-in facts [:clock :kind])))))

(deftest side-labels-fall-back-when-a-hole-cannot-be-filled-test
  (is (= ["Yes" "No"] (templates/side-labels [{:name "template:{shortNameA}"} {:name "template:{shortNameB}"}] {})))
  (is (= ["PHX" "SAS"] (templates/side-labels [{:name "template:{shortNameA}"} {:name "template:{shortNameB}"}]
                                              {"shortNameA" "PHX" "shortNameB" "SAS"}))))
