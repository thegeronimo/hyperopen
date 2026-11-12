(ns hyperopen.vol-range.application.waiting-note-test
  "The note under a disabled review or repair submit says why placement is
  waiting; the review header names the market type."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.vol-range.application.monitoring-copy :as monitoring-copy]
            [hyperopen.vol-range.application.review-model :as review-model]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]))

(def ^:private account "0xabc")

(defn- note [monitoring]
  (with-redefs [account-context/active-trading-account-address (constantly account)]
    (monitoring-copy/waiting-note
     {:vol-range {:execution {:enabled? true
                              :monitoring (merge {:account-address account} monitoring)}}})))

(deftest waiting-note-names-the-reason-test
  (is (= (str monitoring-copy/review-note " Connecting live updates…")
         (note {:status :stale :reasons [:awaiting-streams]})))
  (is (= (str monitoring-copy/review-note
              " Live updates paused while reconnecting. Your orders stay on the exchange.")
         (note {:status :stale :reasons [:transport-down]})))
  (is (= (str monitoring-copy/review-note " Catching up on fills and orders…")
         (note {:status :resyncing :reasons [:catching-up] :resync {:state :running :attempt 0}}))))

(deftest waiting-note-carries-the-last-catch-up-error-test
  (is (= (str monitoring-copy/review-note
              " Couldn't catch up yet. Retrying automatically. Last attempt: Timed out; retrying.")
         (note {:status :resyncing :reasons [:catching-up]
                :resync {:state :failed :attempt 2 :error "Timed out; retrying."}}))))

(deftest review-identity-line-names-the-market-type-test
  (let [line (fn [scope]
               (with-redefs [draft-scope/frozen-review-current? (constantly true)]
                 (:identity-line
                  (review-model/review-model
                   {:vol-range {:frozen-review {:scope (merge {:symbol "NVDA" :quote-symbol "USDC"
                                                               :account-address "0x1234567890abcdef1234567890abcdef12345678"
                                                               :sz-decimals 3}
                                                              scope)
                                                :legs []}
                                :frozen-context {}}}
                   0))))]
    (is (re-find #"^NVDA/USDC · xyz perp · " (line {:market-type :perp :dex "xyz"})))
    (is (re-find #"^NVDA/USDC · Perp · " (line {:market-type :perp :dex nil})))
    (is (re-find #"^NVDA/USDC · Spot · " (line {:market-type :spot})))))
