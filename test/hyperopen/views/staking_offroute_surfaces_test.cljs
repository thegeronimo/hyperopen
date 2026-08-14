(ns hyperopen.views.staking-offroute-surfaces-test
  "Covers the surfaces outside /staking that must stop hiding in-flight HYPE:
  the Portfolio summary card and the Balances tab.

  Both are gated on the delegator summary demonstrably describing the account the
  surface is about, because that summary is written by two fetch paths which
  resolve different addresses."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.staking.account-scope :as account-scope]
            [hyperopen.views.account-info.projections.balances-staking :as balances-staking]
            [hyperopen.views.account-info.tabs.balances.shared :as balances-shared]
            [hyperopen.views.portfolio.summary-cards :as summary-cards]
            [hyperopen.views.portfolio.vm.equity :as vm-equity]))

(def ^:private owner "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def ^:private other "0x1111111111111111111111111111111111111111")

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (rest node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- summary-state
  [{:keys [stamp loaded-for pending]}]
  {:wallet {:connected? true :address owner}
   :staking (cond-> {:delegator-summary {:delegated 100
                                         :undelegated 0
                                         :total-pending-withdrawal (or pending 0)
                                         :pending-withdrawals 1}}
              (some? stamp) (assoc :delegator-summary-address stamp)
              (some? loaded-for) (assoc :loaded-for {:delegator-summary loaded-for}))})

(deftest delegator-summary-address-prefers-the-bootstrap-stamp
  (testing "the bootstrap stamp wins, because the route path clears it when it writes"
    (is (= other
           (account-scope/delegator-summary-address
            (summary-state {:stamp other :loaded-for owner})))))
  (testing "the route path's loaded-for marker is the fallback"
    (is (= owner
           (account-scope/delegator-summary-address
            (summary-state {:loaded-for owner})))))
  (testing "nothing loaded yields no address"
    (is (nil? (account-scope/delegator-summary-address (summary-state {}))))))

(deftest delegator-summary-describes-requires-a-verified-match
  (is (true? (account-scope/delegator-summary-describes?
              (summary-state {:stamp owner}) owner)))
  (is (true? (account-scope/delegator-summary-describes?
              (summary-state {:stamp (clojure.string/upper-case owner)}) owner))
      "addresses are compared normalized, not by raw string")
  (is (false? (account-scope/delegator-summary-describes?
               (summary-state {:stamp other}) owner))
      "a summary fetched for another account must not describe this one")
  (is (false? (account-scope/delegator-summary-describes?
               (summary-state {}) owner))
      "an unstamped summary vouches for nothing"))

(deftest portfolio-breaks-out-unstaking-only-for-a-verified-matching-account
  (is (= 25 (vm-equity/verified-staking-unstaking-hype
             (summary-state {:stamp owner :pending 25}) owner)))
  (is (nil? (vm-equity/verified-staking-unstaking-hype
             (summary-state {:stamp other :pending 25}) owner))
      "an unverified figure is withheld rather than shown against the wrong account")
  (is (nil? (vm-equity/verified-staking-unstaking-hype
             (summary-state {:stamp owner :pending 0}) owner))
      "nothing in flight renders no breakdown line"))

(deftest portfolio-headline-and-breakdown-are-gated-together
  (testing "both figures render for a verified matching account"
    (let [state (summary-state {:stamp owner :pending 25})]
      (is (= 125 (vm-equity/verified-staking-account-hype state owner))
          "delegated 100 + undelegated 0 + queued 25")
      (is (= 25 (vm-equity/verified-staking-unstaking-hype state owner)))))
  (testing "neither figure renders when the summary describes another account"
    (let [state (summary-state {:stamp other :pending 25})]
      (is (nil? (vm-equity/verified-staking-account-hype state owner))
          "gating only the breakdown would leave the card contradicting itself")
      (is (nil? (vm-equity/verified-staking-unstaking-hype state owner)))))
  (testing "an unstamped summary vouches for neither"
    (let [state (summary-state {:pending 25})]
      (is (nil? (vm-equity/verified-staking-account-hype state owner)))
      (is (nil? (vm-equity/verified-staking-unstaking-hype state owner))))))

(deftest portfolio-card-shows-an-explicit-unknown-rather-than-a-fabricated-zero
  (let [unknown (summary-cards/summary-card
                 {:summary {:show-staking-account? true
                            :staking-account-hype nil}})
        strings (set (collect-strings unknown))]
    (is (contains? strings "Staking Account"))
    (is (contains? strings "--"))
    (is (not (contains? strings "0 HYPE"))
        "an unverifiable figure must not render as zero staked HYPE")))

(deftest portfolio-summary-card-renders-the-queue-breakdown
  (let [with-queue (summary-cards/summary-card
                    {:summary {:show-staking-account? true
                               :staking-account-hype 126
                               :staking-unstaking-hype 25}})
        without-queue (summary-cards/summary-card
                       {:summary {:show-staking-account? true
                                  :staking-account-hype 101}})
        strings (set (collect-strings with-queue))]
    (is (contains? strings "Staking Account"))
    (is (contains? strings "In 7-day unstaking queue"))
    (is (contains? strings "25 HYPE"))
    (is (not (contains? (set (collect-strings without-queue))
                        "In 7-day unstaking queue"))
        "the breakdown line is absent when nothing is in flight")))

(defn- balances-state
  [{:keys [stamp pending]}]
  (assoc-in (summary-state {:stamp stamp :pending pending})
            [:account-context :subaccounts :selected-address]
            nil))

(defn- hype-row
  [rows]
  (some #(when (= "HYPE" (:coin %)) %) rows))

(deftest balances-keeps-a-hype-row-when-spot-has-dropped-it
  (let [rows (balances-staking/with-unstaking-hype
              [{:key "spot-0" :coin "USDC" :total-balance 5 :available-balance 5}]
              (balances-state {:stamp owner :pending 25})
              owner)
        row (hype-row rows)]
    (testing "the row exists instead of the balance silently disappearing"
      (is (some? row)))
    (is (= 25 (:unstaking-hype row)))
    (testing "it is inert: no value, no P&L, nothing available to send or transfer"
      (is (= 0 (:available-balance row)))
      (is (= 0 (:total-balance row)))
      (is (nil? (:usdc-value row)))
      (is (nil? (:pnl-value row)))
      (is (true? (:staking-only? row))))
    (testing "existing rows are untouched"
      (is (= {:key "spot-0" :coin "USDC" :total-balance 5 :available-balance 5}
             (first rows))))))

(deftest balances-annotates-an-existing-hype-row-rather-than-duplicating-it
  (let [rows (balances-staking/with-unstaking-hype
              [{:key "spot-1" :coin "HYPE" :total-balance 3 :available-balance 3 :usdc-value 90}]
              (balances-state {:stamp owner :pending 25})
              owner)]
    (is (= 1 (count rows)))
    (is (= 25 (:unstaking-hype (first rows))))
    (is (= 90 (:usdc-value (first rows))) "the real row keeps its own numbers")))

(deftest balances-leaves-rows-alone-without-a-verified-summary
  (let [rows [{:key "spot-0" :coin "USDC" :total-balance 5 :available-balance 5}]]
    (is (= rows (balances-staking/with-unstaking-hype
                 rows (balances-state {:stamp other :pending 25}) owner))
        "an unverified summary must not add a row next to another account's balances")
    (is (= rows (balances-staking/with-unstaking-hype
                 rows (balances-state {:stamp owner :pending 0}) owner))
        "nothing in flight adds nothing")
    (is (= rows (balances-staking/with-unstaking-hype
                 rows (balances-state {:pending 25}) owner))
        "an unstamped summary adds nothing")))

(deftest unstaking-chip-label-names-the-state-not-just-the-number
  (is (= "25.00 unstaking" (balances-shared/unstaking-chip-label 25)))
  (is (= "0.00010000 unstaking" (balances-shared/unstaking-chip-label 0.0001))
      "a real balance is never rounded away to zero")
  (is (nil? (balances-shared/unstaking-chip-label 0)))
  (is (nil? (balances-shared/unstaking-chip-label nil))))

(deftest balances-annotation-is-idempotent-so-the-tab-badge-can-count-the-delta
  (testing "a synthesized row adds exactly one, which is what the badge counts"
    (let [spot [{:coin "USDC"}]
          state (balances-state {:stamp owner :pending 25})]
      (is (= 1 (- (count (balances-staking/with-unstaking-hype spot state owner))
                  (count spot))))))
  (testing "annotating an existing HYPE row adds nothing to count"
    (let [spot [{:coin "HYPE"}]
          state (balances-state {:stamp owner :pending 25})]
      (is (= 0 (- (count (balances-staking/with-unstaking-hype spot state owner))
                  (count spot)))))))

(deftest unstaking-chip-renders-only-when-something-is-unstaking
  (is (contains? (set (collect-strings (balances-shared/unstaking-chip 25)))
                 "25.00 unstaking"))
  (is (nil? (balances-shared/unstaking-chip 0)))
  (is (nil? (balances-shared/unstaking-chip nil))))

(deftest coin-cell-stays-just-the-ticker
  (testing "the coin column is narrow; an annotation there squeezes out the ticker"
    (is (= ["HYPE"] (vec (collect-strings
                          (balances-shared/balance-coin-node {:base-label "HYPE"})))))))
