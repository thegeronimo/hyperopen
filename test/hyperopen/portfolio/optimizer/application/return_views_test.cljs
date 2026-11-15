(ns hyperopen.portfolio.optimizer.application.return-views-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.return-views :as return-views]))

(def ^:private day-ms (* 24 60 60 1000))
(def ^:private now-ms 1751400000000)

(def ^:private universe
  [{:instrument-id "perp:BTC"}
   {:instrument-id "perp:ETH"}
   {:instrument-id "perp:SOL"}])

(def ^:private views
  [{:id "v1" :kind :absolute :instrument-id "perp:BTC"
    :return 0.2 :confidence-level :high :confidence 0.75 :weights {"perp:BTC" 1}}
   {:id "v2" :kind :relative :instrument-id "perp:ETH"
    :comparator-instrument-id "perp:BTC" :return 0.05 :confidence 0.5}])

(def ^:private library
  {"perp:BTC" {:instrument-id "perp:BTC" :return 0.2 :confidence-level :high
               :updated-at-ms (- now-ms (* 42 day-ms))}})

(defn- build-rows
  []
  (return-views/rows {:universe universe
                      :views views
                      :library-entries library
                      :implied-returns-by-instrument {"perp:ETH" 0.078
                                                      "perp:SOL" 0.31}
                      :now-ms now-ms}))

(deftest rows-carry-honest-provenance-test
  (let [[btc eth sol] (build-rows)]
    (is (= {:instrument-id "perp:BTC"
            :source :user
            :return 0.2
            :confidence-level :high
            :age-label "42d ago"
            :stale? true}
           btc)
        "An authored row: user source, its confidence, its library age, stale past 30d.")
    (is (= {:instrument-id "perp:ETH"
            :source :implied
            :return 0.078}
           eth)
        "A relative view does not make the row 'yours', and implied rows carry NO confidence.")
    (is (= {:instrument-id "perp:SOL"
            :source :implied
            :return 0.31}
           sol))))

(deftest age-labels-and-staleness-thresholds-test
  (is (= "today" (return-views/age-label now-ms now-ms)))
  (is (= "today" (return-views/age-label (- now-ms (* 23 60 60 1000)) now-ms)))
  (is (= "1d ago" (return-views/age-label (- now-ms (* 1 day-ms)) now-ms)))
  (is (= "29d ago" (return-views/age-label (- now-ms (* 29 day-ms)) now-ms)))
  (is (nil? (return-views/age-label nil now-ms))
      "Unknown timestamps (view restored on a fresh device) render no age.")
  (is (false? (return-views/stale? (- now-ms (* 29 day-ms)) now-ms)))
  (is (true? (return-views/stale? (- now-ms (* 30 day-ms)) now-ms)))
  (is (false? (return-views/stale? nil now-ms))
      "Unknown age is not flagged stale — stale is a claim, not a default."))

(deftest summary-counts-and-lines-test
  (let [rows (build-rows)
        summary (return-views/summary rows)]
    (is (= {:total 3 :yours 1 :implied 2 :stale 1} summary))
    (is (= "1 your view · 2 implied · 1 stale"
           (return-views/summary-line summary)))
    (is (= "Implied returns for all 3 assets"
           (return-views/summary-line {:total 3 :yours 0 :implied 3 :stale 0})))
    (is (= "1 your view · 2 implied"
           (return-views/returns-contract-label summary)))
    (is (= "0 your views · 3 implied"
           (return-views/returns-contract-label {:yours 0 :implied 3})))))

(deftest filters-match-rows-test
  (let [rows (build-rows)]
    (is (= ["perp:BTC"]
           (map :instrument-id (filter #(return-views/matches-filter? % :yours) rows))))
    (is (= ["perp:ETH" "perp:SOL"]
           (map :instrument-id (filter #(return-views/matches-filter? % :implied) rows))))
    (is (= ["perp:BTC"]
           (map :instrument-id (filter #(return-views/matches-filter? % :stale) rows))))
    (is (= 3 (count (filter #(return-views/matches-filter? % :all) rows))))
    (is (= :all (return-views/normalize-filter "bogus"))
        "Unknown filters normalize to :all instead of hiding every row.")))
