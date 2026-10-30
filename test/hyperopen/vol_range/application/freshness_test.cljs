(ns hyperopen.vol-range.application.freshness-test
  "Regression (spec 5.1): automatic volatility must never be computed from a
  series that may hold a partial in-progress bar as a completed day."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.freshness :as freshness]
            [hyperopen.vol-range.application.plan-state :as plan-state]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.application.ticket-model :as ticket-model]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def ^:private day-ms 86400000)
(def ^:private day-start (* day-ms (js/Math.floor (/ sf/now-ms day-ms))))
(def ^:private rollover (+ day-start day-ms 1800000))

(deftest stale-reason-test
  (let [t (+ day-start 36000000)]
    (is (nil? (freshness/stale-reason t (+ t 1000))))
    (is (= :never-fetched (freshness/stale-reason nil t)))
    (is (= :previous-day (freshness/stale-reason t rollover)))
    (is (= :ttl-expired (freshness/stale-reason t (+ t 900001))))
    (is (nil? (freshness/stale-reason t (+ t 900000))))))

(deftest refetch-due-test
  (let [state (sf/base-state)]
    (is (false? (freshness/refetch-due? state "@107" sf/now-ms)))
    (is (true? (freshness/refetch-due? state "@107" rollover)))
    (let [requested (assoc-in state [:vol-range :daily-fetch "@107" :requested-at-ms] (- rollover 1000))]
      (is (false? (freshness/refetch-due? requested "@107" rollover)))
      (is (true? (freshness/refetch-due? requested "@107" (+ rollover 30001)))))))

(deftest stale-series-emits-volatility-stale-and-no-value-test
  (with-redefs [snapshots/fee-snapshot sf/with-synthetic-fees]
    (let [state (assoc-in (sf/base-state) [:order-form :scale :vol-range :volatility :mode] :auto)
          mid-day (assoc-in state [:vol-range :daily-fetch "@107" :completed-at-ms] (+ day-start 36000000))
          fresh-plan (plan-state/plan-for-state mid-day (+ day-start 36060000))
          stale-ctx (plan-state/context-for-state mid-day rollover)
          stale-plan (:plan stale-ctx)]
      (testing "fresh data plans normally"
        (is (not-any? #{:data/volatility-stale :data/volatility-unavailable} (map :code (:errors fresh-plan)))))
      (testing "the day after the fetch the plan is unavailable with :data/volatility-stale"
        (is (= :unavailable (:status stale-plan)))
        (is (some #{:data/volatility-stale} (map :code (:errors stale-plan))))
        (is (empty? (:legs stale-plan)))
        (is (nil? (get-in stale-plan [:model :volatility]))))
      (testing "the ticket says so instead of showing a value"
        (let [model (ticket-model/ticket-model mid-day rollover)]
          (is (nil? (get-in model [:model :volatility :display])))
          (is (= "Daily candles are stale and are being refreshed"
                 (get-in model [:model :volatility :unavailable-message]))))))))
