(ns hyperopen.domain.account-activity
  "Sub-tab model for the portfolio Account Activity table.

   This is a port of the reference client's `LEDGER_UPDATE_TYPES_CONFIG`: a
   single static table keyed by the Hyperliquid non-funding ledger delta type,
   where each entry names the sub-tabs its rows appear on. The reference applies
   it as `filter == All || config[type].displayedInTabs.includes(filter)`, and
   that shape -- one table, not nine predicates -- is what makes the partition
   auditable.

   It departs from the reference in one respect, deliberately. The reference
   gives four types (subAccountTransfer, liquidation, spotGenesis, rewardsClaim)
   a label but no tab list, and its ingest mapper drops any such row before it
   reaches the store -- so those rows appear on no sub-tab at all, including the
   one labelled `All`. Five further types are absent from its type enum and are
   unparseable. In ledgers sampled while scoping this, that hid roughly a fifth
   of all rows, sub-account transfers being the third most common event overall.
   Here, an empty `:sub-tabs` set means `All only`, never `nowhere`, and an
   unrecognised future type behaves the same way. `All` is therefore a genuine
   superset of every row we can receive."
  (:require [clojure.string :as str]))

(def sub-tabs
  "Ordered sub-tab catalog. Labels and order match the reference exactly."
  [{:id :all :label "All"}
   {:id :account-transfers :label "Account Transfers"}
   {:id :deposits-withdrawals :label "Deposits and Withdrawals"}
   {:id :spot-transfers :label "Spot Transfers"}
   {:id :internal-transfers :label "Internal Transfers"}
   {:id :earn :label "Earn"}
   {:id :vaults :label "Vaults"}
   {:id :staking :label "Staking"}
   {:id :auctions :label "Auctions"}])

(def default-sub-tab :all)

(def sub-tab-ids
  (mapv :id sub-tabs))

(def ^:private sub-tab-id-set
  (set sub-tab-ids))

(def ^:private sub-tab-id-by-label
  (into {}
        (map (fn [{:keys [id label]}]
               [(-> label str/lower-case (str/replace #"[^a-z0-9]+" "-")) id]))
        sub-tabs))

(defn normalize-sub-tab
  "Coerce a keyword, an id string, or a display label to a known sub-tab id.

   Accepts labels because the sub-tab is a candidate for the shareable route
   query, where `Deposits and Withdrawals` is the readable spelling."
  [value]
  (let [token (cond
                (keyword? value) value

                (string? value)
                (let [slug (-> value str/trim str/lower-case (str/replace #"[^a-z0-9]+" "-"))]
                  (or (get sub-tab-id-by-label slug)
                      (keyword slug)))

                :else nil)]
    (if (contains? sub-tab-id-set token)
      token
      default-sub-tab)))

(def ledger-type-config
  "Ledger delta type -> {:label, :sub-tabs}, keyed by the kebab-cased type token.

   An empty `:sub-tabs` set means the type is real and shown, but claims no
   sub-tab of its own, so it appears under `All` only."
  {;; --- Account Transfers: movements between the venues of one account -------
   "send" {:label "Send" :sub-tabs #{:account-transfers}}
   "account-class-transfer" {:label "Account Transfer" :sub-tabs #{:account-transfers}}
   "activate-dex-abstraction" {:label "Dex Abstraction Activation" :sub-tabs #{:account-transfers}}

   ;; --- Deposits and Withdrawals: movements across the bridge ---------------
   "deposit" {:label "Deposit" :sub-tabs #{:deposits-withdrawals}}
   "withdraw" {:label "Withdrawal" :sub-tabs #{:deposits-withdrawals}}

   ;; --- Spot Transfers -------------------------------------------------------
   "spot-transfer" {:label "Spot Transfer" :sub-tabs #{:spot-transfers}}

   ;; --- Internal Transfers ---------------------------------------------------
   "internal-transfer" {:label "Internal Transfer" :sub-tabs #{:internal-transfers}}
   ;; The reference drops this type entirely. A transfer between the user's own
   ;; sub-accounts is internal to their account tree, so it belongs here rather
   ;; than beside the cross-venue moves on Account Transfers -- and Internal
   ;; Transfers keeps its Action column, so it stays distinguishable.
   "sub-account-transfer" {:label "Sub Account Transfer" :sub-tabs #{:internal-transfers}}

   ;; --- Earn: the reference renders this tab and never routes anything to it -
   "borrow-lend" {:label "Borrow / Lend" :sub-tabs #{:earn}}
   "rewards-claim" {:label "Rewards Claim" :sub-tabs #{:earn}}

   ;; --- Vaults ---------------------------------------------------------------
   "vault-create" {:label "Vault Create" :sub-tabs #{:vaults}}
   "vault-deposit" {:label "Vault Deposit" :sub-tabs #{:vaults}}
   "vault-distribution" {:label "Vault Distribution" :sub-tabs #{:vaults}}
   "vault-withdraw" {:label "Vault Withdrawal" :sub-tabs #{:vaults}}
   "vault-leader-commission" {:label "Vault Leader Commission" :sub-tabs #{:vaults}}

   ;; --- Staking --------------------------------------------------------------
   ;; The reference recognises only cStakingTransfer; the spot<->staking deltas
   ;; Hyperliquid actually emits are added so the tab reflects the whole flow.
   "c-staking-transfer" {:label "Staking Transfer" :sub-tabs #{:staking}}
   "c-deposit" {:label "Staking Deposit" :sub-tabs #{:staking}}
   "c-withdraw" {:label "Staking Withdrawal" :sub-tabs #{:staking}}
   "delegate" {:label "Delegate" :sub-tabs #{:staking}}
   "undelegate" {:label "Undelegate" :sub-tabs #{:staking}}

   ;; --- Auctions -------------------------------------------------------------
   "deploy-gas-auction" {:label "Deploy Gas Auction" :sub-tabs #{:auctions}}

   ;; --- Real types with no natural sub-tab: All only, never dropped ----------
   "liquidation" {:label "Liquidation" :sub-tabs #{}}
   "spot-genesis" {:label "Genesis Distribution" :sub-tabs #{}}
   "welcome-bonus" {:label "Welcome Bonus" :sub-tabs #{}}
   "account-activation-gas" {:label "Account Activation Gas" :sub-tabs #{}}})

(defn type-label
  "Display label for a ledger type token, or nil when the type is unknown."
  [type-key]
  (get-in ledger-type-config [type-key :label]))

(defn sub-tabs-for-type
  "The set of sub-tabs a ledger type claims. Empty for known-but-unclassified
   types and for types we have never seen; both are visible under All."
  [type-key]
  (get-in ledger-type-config [type-key :sub-tabs] #{}))

(defn row-visible-in-sub-tab?
  [row sub-tab]
  (let [sub-tab* (normalize-sub-tab sub-tab)]
    (or (= :all sub-tab*)
        (contains? (sub-tabs-for-type (:type-key row)) sub-tab*))))

(defn rows-for-sub-tab
  "Rows visible on `sub-tab`, in the order given."
  [rows sub-tab]
  (into []
        (filter #(row-visible-in-sub-tab? % sub-tab))
        (or rows [])))

(defn sub-tab-counts
  "Row count per sub-tab id, including a zero entry for every empty sub-tab."
  [rows]
  (let [rows* (or rows [])]
    (into {}
          (map (fn [sub-tab-id]
                 [sub-tab-id (count (rows-for-sub-tab rows* sub-tab-id))]))
          sub-tab-ids)))

(def columns
  "The ten columns, in the reference's order."
  [:time :status :asset :action :from :to :destination :account-change :usd-value :fee])

(def ^:private column-labels
  {:time "Time"
   :status "Status"
   :asset "Asset"
   :action "Action"
   :from "From"
   :to "To"
   :destination "Destination"
   :account-change "Account Change"
   :usd-value "USD Value"
   :fee "Fee"})

(defn column-label
  [column]
  (get column-labels column (name column)))

(def ^:private hidden-columns-by-sub-tab
  ;; The reference also hides Action on Account Transfers. We do not: that
  ;; sub-tab carries send, accountClassTransfer and activateDexAbstraction, and
  ;; Action is the only column that tells them apart. On Spot Transfers every
  ;; row is a spotTransfer, so the column really is constant there.
  {:spot-transfers #{:action :destination}})

(defn visible-columns
  [sub-tab]
  (let [hidden (get hidden-columns-by-sub-tab (normalize-sub-tab sub-tab) #{})]
    (into [] (remove hidden) columns)))

(defn column-visible?
  [sub-tab column]
  (contains? (set (visible-columns sub-tab)) column))
