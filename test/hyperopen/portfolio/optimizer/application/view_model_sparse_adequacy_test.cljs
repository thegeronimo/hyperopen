(ns hyperopen.portfolio.optimizer.application.view-model-sparse-adequacy-test
  "The user-visible half of the fix. A sparse member's row COUNT is a sample count,
  not a day count, so comparing 31 samples to the ~1-year daily bar produced the
  false \"31 days of native history - too short to model on its own\" card the user
  reported. Adequacy must read cadence, not just the count."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.view-model.universe :as universe]))

(defn- readiness
  [instrument-id cadence observations]
  {:request {:history {:cadence-by-instrument (when cadence {instrument-id cadence})
                       :raw-price-series-by-instrument
                       {instrument-id (vec (repeat observations {:close 1}))}}}})

(def ^:private growi {:instrument-id "vault:growi"})

(deftest sparse-long-history-is-not-thin-history-test
  (testing "31 samples across 365 days reads :sparse, never :short"
    (let [adequacy (universe/history-adequacy
                    :sufficient
                    {}
                    (readiness "vault:growi"
                               {:sparse? true :elapsed-days 365 :observations 31
                                :off-calendar? true}
                               31)
                    growi)]
      (is (= :sparse adequacy)))))

(deftest genuinely-thin-history-still-reads-short-test
  (testing "65 DAILY rows over 65 days is a real thin-history asset"
    (let [adequacy (universe/history-adequacy
                    :sufficient
                    {}
                    (readiness "vault:growi"
                               {:sparse? false :elapsed-days 65 :observations 65}
                               65)
                    growi)]
      (is (= :short adequacy)))))

(deftest sparse-but-recent-is-still-short-test
  (testing "sparse yet spanning under a year is not the lane case"
    (let [adequacy (universe/history-adequacy
                    :sufficient
                    {}
                    (readiness "vault:new"
                               {:sparse? true :elapsed-days 90 :observations 31}
                               31)
                    {:instrument-id "vault:new"})]
      (is (= :short adequacy)))))

(deftest a-full-history-asset-is-unaffected-test
  (let [adequacy (universe/history-adequacy
                  :sufficient
                  {}
                  (readiness "perp:BTC" {:sparse? false :elapsed-days 1079} 1079)
                  {:instrument-id "perp:BTC"})]
    (is (= :ok adequacy))))

(deftest sparse-member-is-not-told-it-needs-a-proxy-test
  (testing "assumption-required-ids skips off-calendar members"
    (let [state {}
          ;; 12 rows, deliberately BELOW the 30-observation gate, so this
          ;; assertion fails if the off-calendar skip is removed. At 31 rows the
          ;; `(< n 30)` test alone would exclude it and the test would be vacuous.
          readiness* {:request {:history
                                {:off-calendar-instrument-ids ["vault:growi"]
                                 :raw-price-series-by-instrument
                                 {"vault:growi" (vec (repeat 12 {:close 1}))
                                  "perp:BTC" (vec (repeat 900 {:close 1}))}}}}
          ids (universe/assumption-required-ids
               state
               readiness*
               [growi {:instrument-id "perp:BTC"}])]
      (is (= #{} ids))))
  (testing "a genuinely thin member IS still told, so the gate is not simply disabled"
    (let [readiness* {:request {:history
                                {:raw-price-series-by-instrument
                                 {"perp:NEW" (vec (repeat 12 {:close 1}))
                                  "perp:BTC" (vec (repeat 900 {:close 1}))}}}}
          ids (universe/assumption-required-ids
               {}
               readiness*
               [{:instrument-id "perp:NEW"} {:instrument-id "perp:BTC"}])]
      (is (= #{"perp:NEW"} ids)))))

(deftest on-calendar-sparse-member-does-not-claim-off-calendar-treatment-test
  (testing "a sparse member still ON the shared calendar must not take the :sparse
            branch - the badge tooltip promises it is 'kept out of the shared daily
            window', which would be false for it"
    (let [adequacy (universe/history-adequacy
                    :sufficient
                    {}
                    (readiness "perp:WEEKLY"
                               ;; sparse and long, but NO :off-calendar? flag
                               {:sparse? true :elapsed-days 400 :observations 57}
                               57)
                    {:instrument-id "perp:WEEKLY"})]
      (is (= :short adequacy)))))
