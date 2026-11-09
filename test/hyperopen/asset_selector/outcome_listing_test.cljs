(ns hyperopen.asset-selector.outcome-listing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.outcome-listing :as listing]
            [hyperopen.outcomes.test-support.fixture :as fixture]))

(def ^:private now-ms (js/Date.UTC 2026 10 9 16 25))
(def ^:private utc {:time-zone "UTC"})

(defn- built-markets []
  (markets/build-outcome-markets fixture/outcome-meta fixture/asset-ctxs))

(defn- row-by-key
  [sections k]
  (some #(when (= k (:key %)) %) (mapcat :rows sections)))

(deftest sections-follow-time-then-category-test
  (let [sections (listing/sections (built-markets) now-ms utc)]
    (is (= ["Tomorrow · NBA"
            "Thu, Nov 12 · UEFA Nations League"
            "Futures"
            "Crypto"
            "Stocks & commodities"
            "Economics"
            "Companies"
            "Other markets"]
           (mapv :title sections)))
    (is (= 12 (count (listing/ordered-keys sections))))))

(deftest head-to-head-row-answers-are-both-teams-test
  (let [row (row-by-key (listing/sections (built-markets) now-ms utc) "outcome:27037")]
    (is (= "Detroit Pistons vs Washington Wizards" (:title row)))
    (is (= "NBA" (:meta row)))
    (is (= "out" (:venue row)))
    (is (= "Starts in 7h 35m" (get-in row [:clock :label])))
    (is (= [["DET" "82%" "#270370"] ["WAS" "18%" "#270371"]]
           (mapv (juxt :label :text :coin) (:answers row))))
    (is (zero? (:more row)))))

(deftest pick-one-rows-keep-match-order-and-fields-rank-by-chance-test
  (let [sections (listing/sections (built-markets) now-ms utc)
        match (row-by-key sections "question:614")
        field (row-by-key sections "question:430")
        fed (row-by-key sections "question:545")]
    (is (= ["Italy" "Draw" "France"] (mapv :label (:answers match))))
    (is (= 3 (count (:answers field))))
    (is (= 5 (:more field)))
    (is (apply >= (map #(or (get-in % [:quote :chance]) -1) (:answers field))))
    (is (= #{"Hike" "Hold" "Cut"} (set (map :label (:answers fed)))))
    (is (apply >= (map #(or (get-in % [:quote :chance]) -1) (:answers fed))))))

(deftest ladder-rows-show-the-rungs-nearest-even-in-strike-order-test
  (let [row (row-by-key (listing/sections (built-markets) now-ms utc) "ladder:price-touch|BTC|20261201-0000")]
    (is (= 3 (count (:answers row))))
    (is (= 1 (:more row)))
    (let [labels (mapv :label (:answers row))]
      (is (= labels (filterv (set labels) ["$90,000" "$85,000" "$70,000" "$65,000"]))))))

(deftest answers-without-context-are-unknown-not-untraded-test
  (let [row (row-by-key (listing/sections (built-markets) now-ms utc) "outcome:99999")]
    (is (= ["—" "—"] (mapv :text (:answers row))))))

(deftest untraded-answers-say-so-test
  (let [match (row-by-key (listing/sections (built-markets) now-ms utc) "question:627")]
    ;; The native range question's buckets all have live contexts.
    (is (every? #(not= "—" (:text %)) (:answers match)))))

(deftest chip-counts-and-subscriptions-test
  (let [markets (built-markets)
        sections (listing/sections markets now-ms utc)
        coins (listing/visible-coins sections)]
    (is (= {:outcome 12
            :outcome-sports 3
            :outcome-crypto 4
            :outcome-stocks 1
            :outcome-economics 1
            :outcome-companies 2}
           (listing/chip-counts markets)))
    (testing "side 1 mirrors side 0, so only side-0 coins are subscribed"
      (is (contains? coins "#270370"))
      (is (not (contains? coins "#270371"))))))

(deftest day-labels-test
  (is (= "Today" (listing/day-label (js/Date.UTC 2026 10 9 23 0) now-ms utc)))
  (is (= "Tomorrow" (listing/day-label (js/Date.UTC 2026 10 10 1 0) now-ms utc)))
  (is (= "Earlier" (listing/day-label (js/Date.UTC 2026 10 8 1 0) now-ms utc)))
  (is (= "Thu, Nov 12" (listing/day-label (js/Date.UTC 2026 10 12 19 45) now-ms utc))))
