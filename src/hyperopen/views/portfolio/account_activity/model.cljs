(ns hyperopen.views.portfolio.account-activity.model
  "Pure view-model for the Account Activity table: sub-tab filter, sort,
   pagination, and USD valuation of non-USDC rows.

   The USD valuation lives here rather than in `hyperopen.domain.account-ledger`
   because it needs live spot prices, and a `/domain/` namespace may not import
   `hyperopen.views.*` -- the boundary checker refuses that even with an
   exception entry."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.history.shared :as history-shared]
            [hyperopen.domain.account-activity :as account-activity]
            [hyperopen.domain.account-ledger :as account-ledger]
            [hyperopen.portfolio.account-activity-actions :as account-activity-actions]
            [hyperopen.ui.table.sort-kernel :as sort-kernel]
            [hyperopen.views.account-equity.pricing :as pricing]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.history-pagination :as history-pagination]))

(defn truncate-address
  "Render an address the way the reference does: 0xd866...3605.

   Short strings are returned unchanged, so a venue name that reached this
   column by mistake stays readable rather than being mangled."
  [address]
  (let [text (some-> address str str/trim)]
    (when (seq text)
      (if (and (str/starts-with? (str/lower-case text) "0x")
               (> (count text) 12))
        (str (subs text 0 6) "..." (subs text (- (count text) 4)))
        text))))

;; --- USD valuation --------------------------------------------------------

(defn- pricing-inputs
  [state]
  (let [market-by-key (get-in state [:asset-selector :market-by-key] {})]
    {:market-by-key market-by-key
     :balance-row-by-token
     (pricing/balance-rows-by-token
      (derived-cache/memoized-balance-rows (:webdata2 state)
                                           (:spot state)
                                           (:account state)
                                           market-by-key
                                           (:perp-dex-clearinghouse state)))}))

(defn with-usd-value
  "Fill in `:usd-value` for a row the domain layer could not value.

   `token-price-usd` returns nil rather than 0 when no price resolves, and that
   nil is preserved: the column renders `--`, never a fabricated $0.00."
  [{:keys [market-by-key balance-row-by-token]} row]
  (if (or (some? (:usd-value row))
          (nil? (:signed-amount row)))
    row
    (if-let [price (pricing/token-price-usd balance-row-by-token market-by-key (:asset row))]
      (let [value (js/Math.abs (* price (:signed-amount row)))]
        (if (and (js/isFinite value) (pos? value))
          (assoc row
                 :usd-value value
                 :usd-value-text (str "$" (.toLocaleString value
                                                            "en-US"
                                                            #js {:minimumFractionDigits 2
                                                                 :maximumFractionDigits 2}))
                 :usd-value-estimated? true)
          row))
      row)))

;; --- sorting --------------------------------------------------------------

(defn- text-key
  [value]
  (-> (or value "") str str/lower-case))

(defn- number-key
  [value]
  (if (and (number? value) (js/isFinite value))
    value
    ##-Inf))

(def sort-accessor-by-column
  {"Time" (fn [row] (number-key (:time-ms row)))
   "Status" (fn [row] (text-key (:status-label row)))
   "Asset" (fn [row] (text-key (:asset row)))
   "Action" (fn [row] (text-key (:action-label row)))
   "From" (fn [row] (text-key (:from-label row)))
   "To" (fn [row] (text-key (:to-label row)))
   "Destination" (fn [row] (text-key (:destination-address row)))
   "Account Change" (fn [row] (number-key (:signed-amount row)))
   "USD Value" (fn [row] (number-key (:usd-value row)))
   "Fee" (fn [row] (number-key (:fee row)))})

(defn sort-rows
  [rows {:keys [column direction]}]
  (vec
   (sort-kernel/sort-rows-by-column
    rows
    {:column column
     :direction direction
     :accessor-by-column sort-accessor-by-column
     :fallback-accessor (fn [row] (number-key (:time-ms row)))
     ;; Ties resolve on id so a live update cannot reshuffle equal rows under
     ;; the reader, which the trading-UI policy requires of live tables.
     :tie-breaker (fn [row] (or (:id row) ""))})))

;; --- assembly -------------------------------------------------------------

(defn account-activity-state
  [state]
  (merge (account-activity-actions/default-account-activity-state)
         (get-in state account-activity-actions/state-path {})))

(defn normalized-rows
  "Every Account Activity row for the viewed account, newest first.

   The REST snapshot and the live websocket mirror are merged and de-duplicated;
   the viewed address is threaded through so transfer direction is read from the
   payload rather than assumed."
  [state]
  (let [viewer (account-context/effective-account-address state)
        rows (account-ledger/merge-ledger-rows
              (get-in state [:portfolio :ledger-updates])
              (get-in state [:orders :ledger])
              viewer)
        inputs (pricing-inputs state)]
    (mapv #(with-usd-value inputs %) rows)))

(defn account-activity-model
  "Everything the Account Activity panel renders, derived once."
  [state]
  (let [{:keys [sub-tab sort page page-size page-input]} (account-activity-state state)
        sub-tab* (account-activity/normalize-sub-tab sub-tab)
        all-rows (normalized-rows state)
        filtered (account-activity/rows-for-sub-tab all-rows sub-tab*)
        sorted (sort-rows filtered sort)
        pagination (history-pagination/paginate-history-rows
                    sorted
                    {:page page
                     :page-size (history-shared/normalize-order-history-page-size page-size)
                     :page-input page-input})]
    {:sub-tab sub-tab*
     :sub-tabs account-activity/sub-tabs
     :sub-tab-counts (account-activity/sub-tab-counts all-rows)
     :columns (account-activity/visible-columns sub-tab*)
     :sort (or sort account-activity-actions/default-sort)
     :rows (:rows pagination)
     :pagination pagination
     :total-rows (count filtered)
     :loading? (boolean (get-in state [:portfolio :ledger-loading?]))
     :error (get-in state [:portfolio :ledger-error])}))
