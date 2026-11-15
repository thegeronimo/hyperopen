(ns hyperopen.portfolio.optimizer.application.history-merge-test
  "Bundle-fold merge rules (split from history-workflow-test with the
  history-merge namespace, 2026-07-08): delta merges must never mix
  aligned-returns across different calendars; full refreshes replace the
  api-v2 cache wholesale."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-merge :as history-merge]))

(deftest merge-history-bundle-preserves-existing-api-v2-instrument-maps-test
  (let [merged (history-merge/merge-history-bundle
                {:api-v2-history
                 {:status :partial
                  :common-calendar [1000 2000 3000 4000]
                  :return-calendar [2000 3000 4000]
                  :series-by-instrument
                  {"perp:BTC" {:instrument-id "hl:perp:BTC"
                               :points []}}
                  :aligned-returns-by-instrument
                  {"perp:BTC" {:instrument-id "hl:perp:BTC"
                               :returns [0.01 -0.02 0.03]}}
                  :warnings []}}
                {:api-v2-history
                 {:status :partial
                  :common-calendar [1000 2000 3000]
                  :return-calendar [2000 3000]
                  :series-by-instrument
                  {"perp:ETH" {:instrument-id "hl:perp:ETH"
                               :points []}}
                  :aligned-returns-by-instrument
                  {"perp:ETH" {:instrument-id "hl:perp:ETH"
                               :returns [0.02 0.03]}}
                  :warnings []}
                 :warnings []}
                5000)]
    (is (= #{"perp:BTC" "perp:ETH"}
           (set (keys (get-in merged
                              [:api-v2-history
                               :series-by-instrument])))))
    (is (= #{"perp:BTC"}
           (set (keys (get-in merged
                              [:api-v2-history
                               :aligned-returns-by-instrument]))))
        "Mismatched-calendar aligned rows from the delta are dropped.")
    (is (= [1000 2000 3000 4000]
           (get-in merged [:api-v2-history :common-calendar]))
        "The cached calendars survive a delta merge.")
    (is (= [2000 3000 4000]
           (get-in merged [:api-v2-history :return-calendar])))))

(deftest merge-history-bundle-merges-aligned-rows-on-matching-calendars-test
  (let [merged (history-merge/merge-history-bundle
                {:api-v2-history
                 {:status :partial
                  :common-calendar [1000 2000 3000]
                  :return-calendar [2000 3000]
                  :series-by-instrument
                  {"perp:BTC" {:instrument-id "hl:perp:BTC"}}
                  :aligned-returns-by-instrument
                  {"perp:BTC" {:returns [0.01 -0.02]}}
                  :warnings []}}
                {:api-v2-history
                 {:status :partial
                  :common-calendar [1000 2000 3000]
                  :return-calendar [2000 3000]
                  :series-by-instrument
                  {"perp:ETH" {:instrument-id "hl:perp:ETH"}}
                  :aligned-returns-by-instrument
                  {"perp:ETH" {:returns [0.02 0.03]}}
                  :warnings []}
                 :warnings []}
                5000)]
    (is (= #{"perp:BTC" "perp:ETH"}
           (set (keys (get-in merged
                              [:api-v2-history
                               :aligned-returns-by-instrument]))))
        "Identical calendars keep the aligned fast path for the merged set.")))

(deftest merge-history-bundle-full-refresh-replaces-api-v2-wholesale-test
  (let [merged (history-merge/merge-history-bundle
                {:api-v2-history
                 {:status :partial
                  :common-calendar [1000 2000]
                  :return-calendar [2000]
                  :series-by-instrument
                  {"perp:BTC" {:instrument-id "hl:perp:BTC"}}
                  :aligned-returns-by-instrument
                  {"perp:BTC" {:returns [0.01]}}
                  :warnings []}}
                {:api-v2-history
                 {:status :ok
                  :common-calendar [1000 2000 3000]
                  :return-calendar [2000 3000]
                  :series-by-instrument
                  {"perp:BTC" {:instrument-id "hl:perp:BTC"}
                   "perp:ETH" {:instrument-id "hl:perp:ETH"}}
                  :aligned-returns-by-instrument
                  {"perp:BTC" {:returns [0.01 0.02]}
                   "perp:ETH" {:returns [0.02 0.03]}}
                  :warnings []}
                 :warnings []}
                5000)]
    (is (= [1000 2000 3000]
           (get-in merged [:api-v2-history :common-calendar])))
    (is (= [0.01 0.02]
           (get-in merged [:api-v2-history
                           :aligned-returns-by-instrument
                           "perp:BTC"
                           :returns]))
        "The refresh's aligned rows replace the stale cached ones.")
    (is (= :ok (get-in merged [:api-v2-history :status])))))
