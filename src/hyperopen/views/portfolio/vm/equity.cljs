(ns hyperopen.views.portfolio.vm.equity
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.staking.account-scope :as account-scope]
            [hyperopen.staking.unstaking :as unstaking]
            [hyperopen.views.account-info.projections :as projections]))

(defn- optional-number
  [value]
  (projections/parse-optional-num value))

(defn- number-or-zero
  [value]
  (if-let [n (optional-number value)]
    n
    0))

(defn top-up-abstraction-enabled?
  [state]
  (= :unified (get-in state [:account :mode])))

(defn earn-balance
  [state]
  (number-or-zero (get-in state [:borrow-lend :total-supplied-usd])))

(defn vault-equity
  [state summary]
  (or (optional-number (get-in state [:webdata2 :totalVaultEquity]))
      (optional-number (:totalVaultEquity summary))
      0))

(defn perp-account-equity
  [state metrics]
  (or (optional-number (get-in state [:webdata2 :clearinghouseState :marginSummary :accountValue]))
      (optional-number (get-in state [:webdata2 :clearinghouseState :crossMarginSummary :accountValue]))
      (optional-number (:cross-account-value metrics))
      (optional-number (:perps-value metrics))
      0))

(defn spot-account-equity
  [metrics]
  (number-or-zero (:spot-equity metrics)))

(defn- sum-optional-numbers
  [values]
  (let [numbers (keep optional-number values)]
    (when (seq numbers)
      (reduce + numbers))))

(defn- delegator-summary-total-hype
  [summary]
  (when (map? summary)
    (sum-optional-numbers [(:delegated summary)
                           (:undelegated summary)
                           (:total-pending-withdrawal summary)])))

(defn- delegations-total-hype
  [delegations]
  (when (sequential? delegations)
    (sum-optional-numbers (map :amount delegations))))

(defn staking-account-hype
  "Sums whatever staking totals are currently loaded. Says nothing about WHICH
  account they belong to — prefer `verified-staking-account-hype` for anything
  user-facing."
  [state]
  (or (optional-number (get-in state [:staking :total-hype]))
      (optional-number (get-in state [:staking :total]))
      (delegator-summary-total-hype
       (get-in state [:staking :delegator-summary]))
      (delegations-total-hype
       (get-in state [:staking :delegations]))
      0))

;; The delegator summary is written by two fetch paths that resolve different
;; addresses (account bootstrap uses the effective/subaccount address; the
;; /staking route uses the owner), so an ungated read can report another
;; account's HYPE. Everything below returns nil unless the loaded summary can be
;; shown to describe the account the surface is about; nil means "render an
;; explicit unknown", never zero. The headline and its breakdown are gated
;; together on purpose — gating one and not the other lets the card contradict
;; itself.

(defn verified-staking-account-hype
  "Total staking-system HYPE for `address`, or nil when unverifiable."
  ([state]
   (verified-staking-account-hype state (account-context/effective-account-address state)))
  ([state address]
   (when (account-scope/delegator-summary-describes? state address)
     (staking-account-hype state))))

(defn verified-staking-unstaking-hype
  "The portion of `verified-staking-account-hype` stuck in the 7-day queue, or
  nil when unverifiable or when nothing is in flight."
  ([state]
   (verified-staking-unstaking-hype state (account-context/effective-account-address state)))
  ([state address]
   (when (account-scope/delegator-summary-describes? state address)
     (let [amount (:amount (unstaking/pending-unstake state))]
       (when (and (number? amount) (pos? amount))
         amount)))))

(defn staking-value-usd
  [_state _staking-hype]
  0)

(defn compute-total-equity
  [{:keys [top-up-enabled?
           vault-equity
           spot-equity
           staking-value-usd
           perp-equity
           earn-equity]}]
  (let [base-total (+ (number-or-zero vault-equity)
                      (number-or-zero spot-equity)
                      (number-or-zero staking-value-usd))]
    (if top-up-enabled?
      base-total
      (+ base-total
         (number-or-zero perp-equity)
         (number-or-zero earn-equity)))))
