(ns hyperopen.views.account-info.projections.balances-staking
  "Keeps HYPE visible in the Balances tab while it is immobilised in the 7-day
  staking -> spot queue.

  Balance rows are built from the spot clearinghouse only, and rows whose total,
  available, USD value and P&L are all zero are filtered out. A user who has put
  all of their HYPE into the unstaking queue therefore sees no HYPE row at all
  and reasonably concludes the balance vanished.

  The annotation added here is display-only. The synthesized row carries no USD
  value, no P&L and a zero available balance, so USD totals, the tab count badge,
  and the send/transfer affordances are all left exactly as they were."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.staking.account-scope :as account-scope]
            [hyperopen.staking.unstaking :as unstaking]))

(def ^:private hype-coin
  "HYPE")

(defn- hype-row?
  [row]
  (= hype-coin
     (some-> (or (:selection-coin row) (:coin row))
             str
             str/trim
             str/upper-case)))

(defn- synthetic-hype-row
  [amount]
  {:key "staking-unstaking-hype"
   :selection-coin hype-coin
   :coin hype-coin
   :total-balance 0
   :available-balance 0
   :usdc-value nil
   :pnl-value nil
   :pnl-pct nil
   :amount-decimals nil
   :unstaking-hype amount
   :staking-only? true})

(defn with-unstaking-hype
  "Annotate the HYPE row with in-flight unstaking, synthesizing the row when the
  spot balance has dropped it entirely.

  Does nothing unless the loaded delegator summary demonstrably belongs to the
  account these rows describe: the summary is written by two fetch paths that can
  resolve different addresses, so an unverified figure must not be shown next to
  another account's balances."
  ([rows state]
   (with-unstaking-hype rows state (account-context/effective-account-address state)))
  ([rows state address]
   (let [rows* (vec (or rows []))]
     (if-not (account-scope/delegator-summary-describes? state address)
       rows*
       (let [amount (:amount (unstaking/pending-unstake state))]
         (if-not (and (number? amount) (pos? amount))
           rows*
           (if-let [idx (first (keep-indexed (fn [index row]
                                               (when (hype-row? row) index))
                                             rows*))]
             (assoc-in rows* [idx :unstaking-hype] amount)
             (conj rows* (synthetic-hype-row amount)))))))))
