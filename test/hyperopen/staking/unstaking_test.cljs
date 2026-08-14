(ns hyperopen.staking.unstaking-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.staking.unstaking :as unstaking]
            [hyperopen.utils.formatting :as fmt]))

(def ^:private now-ms 1700000000000)

(def ^:private day-ms 86400000)

(def ^:private validator "0x1111111111111111111111111111111111111111")

(defn- days-ago
  [days]
  (- now-ms (* days day-ms)))

(defn- withdraw-row
  "An `initiated` queue entry, the shape live delegatorHistory actually returns."
  [amount started-at-ms]
  {:time-ms started-at-ms
   :hash (str "0xhash" started-at-ms)
   :delta {:kind :withdrawal :amount amount :phase :initiated}})

(defn- finalized-row
  [amount started-at-ms]
  {:time-ms started-at-ms
   :hash (str "0xfinal" started-at-ms)
   :delta {:kind :withdrawal :amount amount :phase :finalized}})

(defn- state
  [summary history]
  {:staking {:delegator-summary summary
             :history history}})

(deftest pending-unstake-reports-nothing-without-a-summary
  (let [model (unstaking/pending-unstake {} now-ms)]
    (is (= 0 (:amount model)))
    (is (false? (:in-flight? model)))
    (is (= :unknown (:timing model)))
    (is (= [] (:entries model)))
    (is (nil? (:count model)))
    (is (nil? (:next-arrival-ms model)))))

(deftest pending-unstake-treats-a-zero-amount-as-not-in-flight
  (let [model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 0 :pending-withdrawals 0} [])
               now-ms)]
    (is (= 0 (:amount model)))
    (is (false? (:in-flight? model)))
    (is (= :unknown (:timing model)))))

(deftest pending-unstake-attributes-arrivals-when-history-reconciles
  (let [older (days-ago 5)
        newer (days-ago 2)
        model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 25 :pending-withdrawals 2}
                      [(withdraw-row 15 newer)
                       (withdraw-row 10 older)])
               now-ms)]
    (is (true? (:in-flight? model)))
    (is (= 25 (:amount model)))
    (is (= 2 (:count model)))
    (is (= :known (:timing model)))
    (testing "entries are newest first and each lands exactly 7 days after it started"
      (is (= [{:amount 15
               :started-at-ms newer
               :arrives-at-ms (+ newer unstaking/queue-duration-ms)
               :remaining-ms (* 5 day-ms)
               :ready? false}
              {:amount 10
               :started-at-ms older
               :arrives-at-ms (+ older unstaking/queue-duration-ms)
               :remaining-ms (* 2 day-ms)
               :ready? false}]
             (:entries model))))
    (testing "next arrival is the oldest entry, last arrival is the newest"
      (is (= (+ older unstaking/queue-duration-ms) (:next-arrival-ms model)))
      (is (= (+ newer unstaking/queue-duration-ms) (:last-arrival-ms model))))))

(deftest pending-unstake-parses-string-amounts
  (let [model (unstaking/pending-unstake
               (state {:total-pending-withdrawal "25" :pending-withdrawals "2"}
                      [(withdraw-row "15" (days-ago 2))
                       (withdraw-row "10" (days-ago 5))])
               now-ms)]
    (is (= 25 (:amount model)))
    (is (= 2 (:count model)))
    (is (= :known (:timing model)))))

(deftest pending-unstake-downgrades-to-estimated-when-amounts-disagree
  (let [older (days-ago 5)
        model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 30 :pending-withdrawals 2}
                      [(withdraw-row 15 (days-ago 2))
                       (withdraw-row 10 older)])
               now-ms)]
    (is (= :estimated (:timing model)))
    (is (= 30 (:amount model)) "the summary stays authoritative for the amount")
    (is (= [] (:entries model)) "no per-entry arrival is offered when unreconciled")
    (is (= (+ older unstaking/queue-duration-ms) (:next-arrival-ms model)))))

(deftest pending-unstake-downgrades-to-estimated-when-counts-disagree
  (let [model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 25 :pending-withdrawals 3}
                      [(withdraw-row 15 (days-ago 2))
                       (withdraw-row 10 (days-ago 5))])
               now-ms)]
    (is (= :estimated (:timing model)))
    (is (= [] (:entries model)))))

(deftest pending-unstake-accepts-a-missing-count-when-the-amount-matches
  (let [model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 25}
                      [(withdraw-row 15 (days-ago 2))
                       (withdraw-row 10 (days-ago 5))])
               now-ms)]
    (is (= :known (:timing model)))
    (is (nil? (:count model)))
    (is (= 2 (count (:entries model))))))

(deftest pending-unstake-reports-unknown-timing-with-no-history
  (let [model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 25 :pending-withdrawals 2} [])
               now-ms)]
    (is (true? (:in-flight? model)))
    (is (= 25 (:amount model)) "amount and count survive without any history")
    (is (= 2 (:count model)))
    (is (= :unknown (:timing model)))
    (is (nil? (:next-arrival-ms model)))))

(deftest pending-unstake-excludes-rows-that-must-already-have-landed
  (let [model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 25 :pending-withdrawals 1}
                      [(withdraw-row 25 (days-ago 9))])
               now-ms)]
    (is (= :unknown (:timing model)))
    (is (nil? (:next-arrival-ms model)))))

(deftest pending-unstake-ignores-history-kinds-that-are-not-queue-entries
  (let [model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 25 :pending-withdrawals 1}
                      [{:time-ms (days-ago 2)
                        :delta {:kind :undelegate :amount 25}}
                       {:time-ms (days-ago 3)
                        :delta {:kind :deposit :amount 25}}
                       ;; cWithdraw is the action that starts the queue, but live
                       ;; delegatorHistory never returns it, so it is not an anchor.
                       {:time-ms (days-ago 4)
                        :delta {:kind :withdraw :amount 25}}])
               now-ms)]
    (is (= :unknown (:timing model))
        "undelegate moves HYPE to the staking balance and never enters the queue")))

(deftest pending-unstake-cancels-initiated-entries-that-have-finalized
  (testing "a finalized entry removes its initiated partner from the queue"
    (let [initiated (days-ago 9)
          model (unstaking/pending-unstake
                 (state {:total-pending-withdrawal 15 :pending-withdrawals 1}
                        [(withdraw-row 15 (days-ago 2))
                         (finalized-row 10 (days-ago 2))
                         (withdraw-row 10 initiated)])
                 now-ms)]
      (is (= :known (:timing model)))
      (is (= 1 (count (:entries model))))
      (is (= 15 (:amount (first (:entries model))))
          "the still-open 15 remains; the 10 initiated 9 days ago has landed")))
  (testing "pairing requires the finalized entry to come after the initiated one"
    (let [model (unstaking/pending-unstake
                 (state {:total-pending-withdrawal 10 :pending-withdrawals 1}
                        [(withdraw-row 10 (days-ago 2))
                         (finalized-row 10 (days-ago 5))])
                 now-ms)]
      (is (= :known (:timing model)))
      (is (= 1 (count (:entries model)))))))

(deftest pending-unstake-normalizes-phase-spelling
  (testing "a provider rename to the …Withdrawal forms still resolves"
    (let [model (unstaking/pending-unstake
                 (state {:total-pending-withdrawal 15 :pending-withdrawals 1}
                        [{:time-ms (days-ago 2)
                          :delta {:kind :withdrawal :amount 15 :phase :initiatedWithdrawal}}
                         {:time-ms (days-ago 3)
                          :delta {:kind :withdrawal :amount 10 :phase :finalizedWithdrawal}}
                         {:time-ms (days-ago 6)
                          :delta {:kind :withdrawal :amount 10 :phase :initiatedWithdrawal}}])
                 now-ms)]
      (is (= :known (:timing model)))
      (is (= 15 (:amount (first (:entries model)))))))
  (testing "an unrecognized phase is neither initiated nor finalized, so no date is claimed"
    (let [model (unstaking/pending-unstake
                 (state {:total-pending-withdrawal 25 :pending-withdrawals 1}
                        [{:time-ms (days-ago 2)
                          :delta {:kind :withdrawal :amount 25 :phase :ready}}])
                 now-ms)]
      (is (= :unknown (:timing model))))))

(deftest pending-unstake-marks-an-elapsed-entry-ready
  (let [started (days-ago 7.5)
        model (unstaking/pending-unstake
               (state {:total-pending-withdrawal 25 :pending-withdrawals 1}
                      [(withdraw-row 25 started)])
               now-ms)
        entry (first (:entries model))]
    (is (= :known (:timing model)))
    (is (true? (:ready? entry)))
    (is (= 0 (:remaining-ms entry)) "remaining is floored at zero, never negative")))

(deftest queue-progress-fraction-spans-zero-to-one
  (is (= 0 (unstaking/queue-progress-fraction {:remaining-ms unstaking/queue-duration-ms})))
  (is (= 0.5 (unstaking/queue-progress-fraction
              {:remaining-ms (/ unstaking/queue-duration-ms 2)})))
  (is (= 1 (unstaking/queue-progress-fraction {:remaining-ms 0})))
  (is (nil? (unstaking/queue-progress-fraction {:remaining-ms nil}))))

(deftest projected-arrival-is-seven-days-out
  (is (= (+ now-ms unstaking/queue-duration-ms)
         (unstaking/projected-arrival-ms now-ms)))
  (is (nil? (unstaking/projected-arrival-ms nil))))

(defn- delegations-state
  [locked-until-timestamp]
  {:staking {:delegations [(cond-> {:validator validator :amount 101}
                             (some? locked-until-timestamp)
                             (assoc :locked-until-timestamp locked-until-timestamp))]}})

(deftest locked-delegation-reports-a-future-lock
  (let [unlock-ms (+ now-ms (* 6 3600000))
        locked (unstaking/locked-delegation (delegations-state unlock-ms) validator now-ms)]
    (is (= unlock-ms (:locked-until-timestamp locked)))
    (is (= (* 6 3600000) (:remaining-ms locked)))))

(deftest locked-delegation-matches-the-submit-guard-boundary
  (testing "a lock exactly equal to now is not locked, matching the undelegate guard"
    (is (nil? (unstaking/locked-delegation (delegations-state now-ms) validator now-ms))))
  (testing "an expired lock is not locked"
    (is (nil? (unstaking/locked-delegation (delegations-state (dec now-ms)) validator now-ms))))
  (testing "a delegation with no lock is not locked"
    (is (nil? (unstaking/locked-delegation (delegations-state nil) validator now-ms))))
  (testing "an unknown validator has no locked delegation"
    (is (nil? (unstaking/locked-delegation (delegations-state (+ now-ms 1000))
                                           "0x2222222222222222222222222222222222222222"
                                           now-ms)))))

(deftest locked-delegation-normalizes-validator-case
  (let [unlock-ms (+ now-ms 1000)]
    (is (some? (unstaking/locked-delegation (delegations-state unlock-ms)
                                            "0x1111111111111111111111111111111111111111"
                                            now-ms)))))

(deftest format-duration-approx-covers-every-band
  (is (= "6d 4h" (fmt/format-duration-approx (+ (* 6 day-ms) (* 4 3600000)))))
  (is (= "4h 12m" (fmt/format-duration-approx (+ (* 4 3600000) (* 12 60000)))))
  (is (= "12m" (fmt/format-duration-approx (* 12 60000))))
  (is (= "less than a minute" (fmt/format-duration-approx 59999)))
  (is (= "1m" (fmt/format-duration-approx 60000)))
  (is (= "1h 0m" (fmt/format-duration-approx 3600000)))
  (is (= "1d 0h" (fmt/format-duration-approx day-ms)))
  (is (= "less than a minute" (fmt/format-duration-approx -5000))
      "a negative span never renders as a negative duration")
  (is (nil? (fmt/format-duration-approx nil)))
  (is (nil? (fmt/format-duration-approx js/NaN))))
