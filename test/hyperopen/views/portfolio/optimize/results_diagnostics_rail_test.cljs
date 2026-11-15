(ns hyperopen.views.portfolio.optimize.results-diagnostics-rail-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.results-diagnostics-rail :as rail]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(deftest diversification-status-actually-evaluates-test
  (is (= :bad (rail/diversification-status 0.5 18)))
  (is (= :bad (rail/diversification-status 1.2 4)))
  (is (= :caution (rail/diversification-status 3 18)))
  (is (= :ok (rail/diversification-status 6 18)))
  (is (= :ok (rail/diversification-status 2 4)))
  (is (= :unknown (rail/diversification-status nil 18)))
  (is (= :unknown (rail/diversification-status js/NaN 18))))

(deftest warning-rows-group-per-code-with-plain-labels-test
  (let [rows (vec (rail/warning-rows
                   {}
                   [{:code :stale-history
                     :instrument-id "perp:XRP"
                     :message "Cached history is stale."}
                    {:code :stale-history
                     :instrument-id "perp:HYPE"
                     :message "Cached history is stale."}
                    {:code :proxy-history-used
                     :instrument-id "perp:PAXG"
                     :message "Proxy returns are not native Hyperliquid returns."}]))
        texts (mapv #(str/join " " (collect-strings %)) rows)]
    (is (= 2 (count rows)) "one row per code, not per asset")
    (let [stale-text (first (filter #(str/includes? % "Cached history is stale.") texts))]
      (is (some? stale-text))
      (is (str/includes? stale-text "XRP"))
      (is (str/includes? stale-text "HYPE"))
      (is (not (str/includes? stale-text "perp:")) "no raw namespaced ids")
      (is (= 1 (count (re-seq #"Cached history is stale\." stale-text)))))))

(deftest history-window-flags-stale-shared-calendar-test
  (is (= :ok (rail/history-window-status {:return-observations 350
                                          :stale? false})))
  (is (= :caution (rail/history-window-status {:return-observations 350
                                               :stale? true})))
  (is (= :caution (rail/history-window-status {:return-observations 12
                                               :stale? false}))
      "thin-observation caution is unchanged")
  (let [subtext (rail/history-window-subtext
                 {:labels-by-instrument {"perp:IMX" "IMX"}}
                 {:stale? true
                  :age-ms (* 22 86400000)
                  :limiting-instrument-id "perp:IMX"
                  :limiting-reason :ends-earlier})]
    (is (str/includes? subtext "22 days before the run date"))
    (is (str/includes? subtext "IMX ends earlier than the rest.")
        "the limiter sentence still follows the stale note"))
  (is (= "IMX ends earlier than the rest."
         (rail/history-window-subtext
          {:labels-by-instrument {"perp:IMX" "IMX"}}
          {:stale? false
           :limiting-instrument-id "perp:IMX"
           :limiting-reason :ends-earlier}))
      "fresh windows keep the plain limiter subtext"))

(deftest solver-fallback-warning-renders-with-plain-copy-test
  (let [rows (vec (rail/warning-rows
                   {}
                   [{:code :solver-fallback-used
                     :message "Every solve in this run (56) fell back."}]))
        text (str/join " " (collect-strings (first rows)))]
    (is (= 1 (count rows)))
    (is (str/includes? text "Backup solver used"))
    (is (not (str/includes? text "solver-fallback-used")))
    (is (str/includes? text "fell back"))))

(deftest solver-fallback-warning-reaches-the-mounted-rail-test
  (let [text (str/join " " (collect-strings
                            (rail/trust-diagnostics-rail
                             {:instrument-ids ["perp:BTC" "perp:ETH"]
                              :warnings [{:code :solver-fallback-used
                                          :message "Every solve fell back."}]})))]
    (is (str/includes? text "Backup solver used"))
    (is (str/includes? text "Every solve fell back."))))
