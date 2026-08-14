(ns hyperopen.views.staking-unstaking-block-test
  "Covers the /staking surfaces that describe HYPE sitting in the 7-day
  staking -> spot queue, and the pre-submit delegation-lock warning."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.staking.vm :as staking-vm]
            [hyperopen.views.staking-view :as staking-view]))

(def ^:private now-ms 1700000000000)

(def ^:private day-ms 86400000)

(def ^:private validator "0x1234567890abcdef1234567890abcdef12345678")

(defn- find-node
  [pred node]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-node pred %) (rest node)))

    (seq? node)
    (some #(find-node pred %) node)

    :else nil))

(defn- find-node-by-data-role
  [data-role node]
  (find-node #(= data-role (get-in % [1 :data-role])) node))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (rest node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- text-of
  [node]
  (clojure.string/join " " (collect-strings node)))

(defn- withdraw-row
  "An `initiated` queue entry, the shape live delegatorHistory actually returns."
  [amount started-at-ms]
  {:time-ms started-at-ms
   :hash (str "0xabc" started-at-ms)
   :delta {:kind :withdrawal :amount amount :phase :initiated}})

(defn- render
  [staking-overrides & [staking-ui-overrides]]
  (let [state {:wallet {:connected? true
                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :staking-ui (or staking-ui-overrides {})
               :staking (merge {:validator-summaries []} staking-overrides)}]
    (staking-view/staking-view state (staking-vm/staking-vm state now-ms))))

(deftest unstaking-block-is-always-present-and-reads-none-when-idle
  (let [view (render {:delegator-summary {:delegated 10
                                          :undelegated 5
                                          :total-pending-withdrawal 0}})
        block (find-node-by-data-role "staking-unstaking-block" view)]
    (testing "the slot is present in every state so the panel shape is stable"
      (is (some? block)))
    (is (clojure.string/includes? (text-of block) "Unstaking to Spot Balance"))
    (is (clojure.string/includes? (text-of block) "None"))
    (is (nil? (find-node-by-data-role "staking-unstaking-locked-pill" view))
        "nothing is locked, so no locked pill")))

(deftest unstaking-block-shows-amount-lock-and-arrival-when-history-reconciles
  (let [started (- now-ms (* 2 day-ms))
        view (render {:delegator-summary {:delegated 10
                                          :undelegated 0
                                          :total-pending-withdrawal 25
                                          :pending-withdrawals 1}
                      :history [(withdraw-row 25 started)]})
        block (find-node-by-data-role "staking-unstaking-block" view)
        text (text-of block)]
    (is (some? (find-node-by-data-role "staking-unstaking-locked-pill" view)))
    (is (clojure.string/includes? text "Locked"))
    (is (clojure.string/includes? text "25.00000000 HYPE"))
    (testing "the block states plainly that the amount cannot be used"
      (is (clojure.string/includes? text "Not tradable or transferable until it arrives.")))
    (testing "arrival is exactly seven days after the transfer started"
      (is (clojure.string/includes?
           text
           (fmt/format-local-date-time (+ started 604800000)))))
    (is (clojure.string/includes? text "about 5d 0h left"))
    (is (clojure.string/includes? text "1 transfer in the queue"))
    (testing "progress is exposed to assistive technology, not colour alone"
      (let [bar (find-node-by-data-role "staking-unstaking-progress" view)]
        (is (= "progressbar" (get-in bar [1 :role])))
        (is (= "29" (get-in bar [1 :aria-valuenow])))))
    (is (clojure.string/includes? text "2 of 7 days elapsed"))))

(deftest unstaking-block-names-both-transfers-when-several-are-queued
  (let [older (- now-ms (* 5 day-ms))
        newer (- now-ms (* 2 day-ms))
        view (render {:delegator-summary {:total-pending-withdrawal 25
                                          :pending-withdrawals 2}
                      :history [(withdraw-row 15 newer)
                                (withdraw-row 10 older)]})
        text (text-of (find-node-by-data-role "staking-unstaking-block" view))]
    (is (clojure.string/includes? text "2 transfers in the queue"))
    (testing "the headline is the soonest arrival, which is the oldest transfer"
      (is (clojure.string/includes?
           text
           (str "Next arrives " (fmt/format-local-date-time (+ older 604800000))))))
    (testing "the last arrival is stated too, so the full window is visible"
      (is (clojure.string/includes?
           text
           (str "last arrives " (fmt/format-local-date-time (+ newer 604800000))))))))

(deftest unstaking-block-declines-to-invent-an-arrival-without-history
  (let [view (render {:delegator-summary {:total-pending-withdrawal 25
                                          :pending-withdrawals 2}
                      :history []})
        text (text-of (find-node-by-data-role "staking-unstaking-block" view))]
    (is (clojure.string/includes? text "25.00000000 HYPE"))
    (is (clojure.string/includes? text "2 transfers in the queue"))
    (is (clojure.string/includes? text "Transfers to your spot balance take 7 days."))
    (is (clojure.string/includes? text "The exact arrival time is not available right now."))
    (is (not (clojure.string/includes? text "about"))
        "no approximate countdown is offered when nothing can be attributed")))

(deftest unstaking-block-qualifies-an-arrival-it-cannot-reconcile
  (let [older (- now-ms (* 5 day-ms))
        view (render {:delegator-summary {:total-pending-withdrawal 30
                                          :pending-withdrawals 2}
                      :history [(withdraw-row 15 (- now-ms (* 2 day-ms)))
                                (withdraw-row 10 older)]})
        text (text-of (find-node-by-data-role "staking-unstaking-block" view))]
    (is (clojure.string/includes? text "30.00000000 HYPE")
        "the summary stays authoritative for the amount")
    (is (clojure.string/includes?
         text
         (str "Estimated to arrive around " (fmt/format-local-date-time (+ older 604800000)))))
    (is (clojure.string/includes? text "treat this time as approximate"))))

(defn- unstake-popover-view
  [staking-overrides]
  (render staking-overrides
          {:action-popover {:open? true :kind :unstake}
           :selected-validator validator}))

(deftest unstake-popover-explains-that-unstaking-is-only-the-first-step
  (let [view (unstake-popover-view {:delegations [{:validator validator :amount 101}]
                                    :delegator-summary {:delegated 101 :undelegated 0}})
        guidance (find-node-by-data-role "staking-unstake-guidance" view)]
    (is (some? guidance))
    (is (clojure.string/includes?
         (text-of guidance)
         "Unstaking returns HYPE to your Staking Balance right away."))
    (is (clojure.string/includes?
         (text-of guidance)
         "separate transfer that then takes 7 days"))
    (is (nil? (find-node-by-data-role "staking-unstake-lock-notice" view))
        "an unlocked delegation gets no lock warning")))

(deftest unstake-popover-warns-about-a-live-lock-before-the-user-submits
  (let [unlock-ms (+ now-ms (* 6 3600000))
        view (unstake-popover-view {:delegations [{:validator validator
                                                   :amount 101
                                                   :locked-until-timestamp unlock-ms}]})
        notice (find-node-by-data-role "staking-unstake-lock-notice" view)]
    (is (some? notice))
    (is (clojure.string/includes?
         (text-of notice)
         (str "This delegation is locked until " (fmt/format-local-date-time unlock-ms))))
    (is (clojure.string/includes? (text-of notice) "about 6h 0m left"))
    (is (clojure.string/includes? (text-of notice) "You cannot unstake from this validator yet."))))

(deftest unstake-popover-lock-warning-matches-the-submit-guard-boundary
  (testing "a lock exactly at now is not warned about, matching the submit guard"
    (is (nil? (find-node-by-data-role
               "staking-unstake-lock-notice"
               (unstake-popover-view {:delegations [{:validator validator
                                                     :amount 101
                                                     :locked-until-timestamp now-ms}]}))))))

(deftest transfer-popover-projects-a-concrete-arrival-for-the-spot-direction
  (let [view (render {:delegator-summary {:undelegated 50 :total-pending-withdrawal 0}}
                     {:action-popover {:open? true :kind :transfer}
                      :transfer-direction :staking->spot})
        note (find-node-by-data-role "staking-transfer-projected-arrival" view)]
    (is (some? note))
    (is (clojure.string/includes?
         (text-of note)
         (str "A transfer started now would arrive around "
              (fmt/format-local-date-time (+ now-ms 604800000)))))))

(deftest transfer-popover-omits-the-queue-note-for-the-staking-direction
  (let [view (render {:delegator-summary {:undelegated 50}}
                     {:action-popover {:open? true :kind :transfer}
                      :transfer-direction :spot->staking})]
    (is (nil? (find-node-by-data-role "staking-transfer-projected-arrival" view)))
    (is (clojure.string/includes?
         (text-of view)
         "Transfers into your Staking Balance are available immediately."))))
