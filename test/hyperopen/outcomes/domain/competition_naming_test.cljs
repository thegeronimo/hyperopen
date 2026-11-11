(ns hyperopen.outcomes.domain.competition-naming-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.outcomes.domain.labels :as labels]
            [hyperopen.outcomes.domain.templates :as templates]))

(deftest competition-label-reads-naturally-test
  (is (= "NFL" (labels/competition-label "USA National Football League (NFL)")))
  (is (= "MLB" (labels/competition-label "MLB Championship")))
  (is (= "UEFA Champions League" (labels/competition-label "UEFA Champions League")))
  (is (nil? (labels/competition-label "  "))))

(deftest season-placeholder-is-no-season-test
  (is (= "2026/2027" (labels/season-label "2026/2027")))
  (is (nil? (labels/season-label "season")))
  (is (nil? (labels/season-label nil))))

(defn- facts
  [description]
  (templates/describe-question {:name "template:sportsTournamentWinner"
                                :description description}))

(deftest tournament-winner-titles-test
  (is (= "MLB champion 2026" (:title (facts "competition:MLB Championship|season:2026"))))
  (is (= "NFL champion 2027"
         (:title (facts "competition:USA National Football League (NFL)|season:2027"))))
  (is (= "Tournament champion" (:title (facts "season:season"))))
  (is (some #{"MLB Championship"} (:search-terms (facts "competition:MLB Championship|season:2026")))
      "the full name stays searchable"))
