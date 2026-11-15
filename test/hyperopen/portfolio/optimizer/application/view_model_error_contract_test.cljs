(ns hyperopen.portfolio.optimizer.application.view-model-error-contract-test
  "View-model surfacing of the history-bundle error contract additions
  (2026-07-09): the universe by-exception chips for excluded-from-alignment and
  serve-time staleness incidents, and the readiness-panel severity for the new
  warnings."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.view-model.setup :as setup]
            [hyperopen.portfolio.optimizer.application.view-model.universe :as universe]))

(def ^:private instrument
  {:instrument-id "perp:X"
   :market-type :perp
   :coin "X"
   :symbol "X-USDC"
   :name "Asset X"})

(defn- status-by [readiness-status]
  {"perp:X" readiness-status})

(deftest excluded-from-alignment-paints-shared-gap-chip-not-missing-test
  (is (= :shared-gap
         (universe/selected-history-status
          {} {} {} (status-by :loaded-but-misaligned) instrument)))
  (let [row (universe/selected-row-model
             {} {} {} (status-by :loaded-but-misaligned) instrument)]
    (is (= :shared-gap (:history-status row)))
    (is (= "shared gap" (:history-label row)))
    (is (= :warn (:history-tone row)))
    (is (not= :missing (:history-status row)))))

(deftest stale-incident-paints-visible-stale-chip-while-routine-stale-is-all-clear-test
  (let [incident (universe/selected-row-model
                  {} {} {} (status-by :stale-critical) instrument)
        routine (universe/selected-row-model
                 {} {} {} (status-by :stale) instrument)]
    (is (= :stale-critical (:history-status incident)))
    (is (= "stale" (:history-label incident)))
    (is (= :warn (:history-tone incident)))
    (is (= :stale (:history-status routine)))
    (is (nil? (:history-label routine)))))

(defn- readiness-with [warnings]
  {:request {:requested-universe []}
   :warnings warnings
   :blocking-warnings []})

(defn- severity-of [readiness code]
  (->> (setup/group-readiness-warnings readiness)
       (some #(when (= code (:code %)) %))
       :severity))

(deftest stale-incident-readiness-group-is-caution-routine-is-info-test
  (is (= :caution
         (severity-of (readiness-with [{:code :stale-history
                                        :instrument-id "perp:X"
                                        :details {:serve-age-days 9}}])
                      :stale-history)))
  (is (= :info
         (severity-of (readiness-with [{:code :stale-history
                                        :instrument-id "perp:X"
                                        :details {:serve-age-days 2}}])
                      :stale-history))))

(deftest excluded-and-common-window-empty-readiness-severities-test
  (is (= :info
         (severity-of (readiness-with [{:code :excluded-from-alignment
                                        :instrument-id "perp:X"
                                        :details {:reason :window-disjoint-from-majority}}])
                      :excluded-from-alignment)))
  (is (= :caution
         (severity-of (readiness-with [{:code :common-window-empty
                                        :details {:instrument-ids ["hl:perp:X"]}}])
                      :common-window-empty))))
