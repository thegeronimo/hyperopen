(ns hyperopen.portfolio.optimizer.application.setup-readiness-served-observations-test
  "Regression coverage for the 2026-07-08 live collapse, at the readiness and
  badge layer: when one young listing's disjoint calendar degrades alignment,
  the assumption gate must flag THAT asset (from its pre-alignment served
  observation count) and must not smear thin-history/needs-assumption states
  onto full-history assets."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.view-model.universe :as universe]))

(def ^:private day-ms 86400000)

(defn- native-points
  [start-day n]
  (mapv (fn [idx]
          {:time-ms (* (+ start-day idx) day-ms)
           :close (+ 100 idx)
           :return (when (pos? idx) 0.01)})
        (range n)))

(defn- native-series
  [local-id backend-id points]
  {:local-instrument-id local-id
   :instrument-id backend-id
   :lineage-kind :native
   :series-kind :market-price
   :points points
   :funding {:status :available :annualized-carry 0}
   :warnings []})

(def ^:private btc {:instrument-id "perp:BTC"
                    :market-type :perp
                    :coin "BTC"
                    :name "Bitcoin"})

(def ^:private gram {:instrument-id "perp:GRAM"
                     :market-type :perp
                     :coin "GRAM"
                     :name "Gram"})

(defn- poisoned-state
  "One deep native series (400 days) and one 6-day listing whose window starts
  after the deep series' data - the live shape that used to exclude everyone."
  []
  {:portfolio
   {:optimizer
    {:draft
     {:universe [btc gram]
      :objective {:kind :minimum-variance}
      :return-model {:kind :historical-mean}
      :risk-model {:kind :ledoit-wolf-dense}
      :constraints {:long-only? true}}
     :runtime {:as-of-ms (* 460 day-ms)
               :stale-after-ms (* 400 day-ms)
               :funding-periods-per-year 1095}
     :history-data
     {:api-v2-history
      {:status :partial
       :common-calendar []
       :return-calendar []
       :aligned-returns-by-instrument {"perp:BTC" {:returns []}
                                       "perp:GRAM" {:returns []}}
       :series-by-instrument
       {"perp:BTC" (native-series "perp:BTC" "hl:perp:BTC"
                                  (native-points 0 400))
        "perp:GRAM" (native-series "perp:GRAM" "hl:perp:GRAM"
                                   (native-points 450 6))}}}}}})

(deftest readiness-flags-the-young-asset-not-the-deep-one-test
  (let [readiness (setup-readiness/build-readiness (poisoned-state))]
    (is (= ["perp:BTC"]
           (mapv :instrument-id (get-in readiness [:request :universe]))))
    (is (= :missing-history-assumptions (:reason readiness)))
    (is (= [{:code :history-assumption-required
             :instrument-id "perp:GRAM"
             :observations 6}]
           (mapv #(select-keys % [:code :instrument-id :observations])
                 (:blocking-warnings readiness))))
    (is (= {"perp:BTC" 400 "perp:GRAM" 6}
           (get-in readiness [:request :history
                              :served-observations-by-instrument])))))

(deftest badges-source-served-counts-for-excluded-assets-test
  (let [state (poisoned-state)
        readiness (setup-readiness/build-readiness state)
        status-by-id (setup-readiness/history-status-by-instrument readiness)]
    (is (= 400 (universe/native-history-observations state readiness btc)))
    (is (= 6 (universe/native-history-observations state readiness gram)))
    (is (= #{"perp:GRAM"}
           (universe/assumption-required-ids state readiness [btc gram])))
    (is (= :ok (universe/history-adequacy (get status-by-id "perp:BTC")
                                          state readiness btc)))
    (is (= :short (universe/history-adequacy (get status-by-id "perp:GRAM")
                                             state readiness gram)))))
