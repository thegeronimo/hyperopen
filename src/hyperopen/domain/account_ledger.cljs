(ns hyperopen.domain.account-ledger
  "Normalisation of Hyperliquid non-funding ledger deltas into Account Activity
   rows.

   Rows carry `:type-key`, the kebab-cased ledger delta type, because that is
   what `hyperopen.domain.account-activity` routes on. Keeping only the English
   action label -- as this namespace previously did -- makes the partition
   unrecoverable, since several distinct types share one label.

   The per-type derivation of endpoints, sign, USD value and fee lives in
   `hyperopen.domain.account-ledger.derive`."
  (:require [clojure.string :as str]
            [hyperopen.domain.account-activity :as account-activity]
            [hyperopen.domain.account-ledger.derive :as derive]))

(def ^:private default-status-label
  "Completed")

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-ms
  [value]
  (when-let [num (derive/parse-decimal value)]
    (js/Math.floor num)))

(defn- normalized-token
  [value]
  (some-> value
          derive/non-blank-text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          (str/replace #"[_\s]+" "-")
          str/lower-case))

(defn- title-case-label
  [value]
  (let [text (or (normalized-token value) "")]
    (->> (str/split text #"-+")
         (remove str/blank?)
         (map (fn [part]
                (str (str/upper-case (subs part 0 1))
                     (subs part 1))))
         (str/join " "))))

(defn- action-label
  "Display label for a ledger type.

   The catalog in `account-activity` is the single source of truth; an
   unrecognised type falls back to a title-cased version of its own token so a
   future delta type is still legible rather than blank."
  [type-key]
  (or (account-activity/type-label type-key)
      (title-case-label type-key)))

(defn- ledger-delta
  [row]
  (let [delta (derive/field row :delta)]
    (if (map? delta)
      delta
      row)))

(defn- strip-trailing-zeroes
  [value]
  (-> value
      (str/replace #"(\.\d*?[1-9])0+$" "$1")
      (str/replace #"\.0+$" "")))

(defn- format-number
  [value]
  (if (finite-number? value)
    (strip-trailing-zeroes (.toFixed value 8))
    "--"))

(defn- format-signed-amount
  [amount asset]
  (if (finite-number? amount)
    (str (if (neg? amount) "-" "+")
         (format-number (js/Math.abs amount))
         " "
         (or asset derive/collateral-asset))
    "--"))

(defn- format-usd-value
  [value]
  (if (and (finite-number? value) (not (zero? value)))
    (str "$" (.toLocaleString (js/Math.abs value)
                              "en-US"
                              #js {:minimumFractionDigits 2
                                   :maximumFractionDigits 2}))
    "--"))

(defn- format-fee
  [fee asset]
  (if (finite-number? fee)
    (str (format-number fee) " " (or asset derive/collateral-asset))
    "--"))

(defn- status-label
  [row delta]
  (or (some-> (or (derive/field row :status)
                  (derive/field delta :status))
              title-case-label
              derive/non-blank-text)
      default-status-label))

(defn- explorer-url
  [hash]
  (when-let [hash* (derive/non-blank-text hash)]
    (str "https://app.hyperliquid.xyz/explorer/tx/" hash*)))

(defn- ledger-row-id
  [time-ms type-key hash asset amount]
  (str (or (derive/non-blank-text hash) "no-hash")
       "|"
       (or time-ms 0)
       "|"
       (or type-key "")
       "|"
       (or asset "")
       "|"
       (format-number (or amount 0))))

(defn normalize-ledger-row
  "Normalise one raw ledger row into an Account Activity row.

   `viewer-address` is the account whose ledger is being viewed; it decides
   whether a two-party transfer is a credit or a debit. Without it, transfers
   are reported as outgoing.

   A row is kept whenever it has a type and a timestamp. It is NOT dropped for
   want of a resolvable amount -- `vaultWithdraw` and `liquidation` carry their
   value in fields the old amount probe never looked at, so requiring one made
   those two labels unreachable by real data. Such a row renders `--` in the
   Account Change column instead of vanishing."
  ([row]
   (normalize-ledger-row row nil))
  ([row viewer-address]
   (when (map? row)
     (let [delta (ledger-delta row)
           raw-type-key (normalized-token (derive/field delta :type))
           time-ms (or (parse-ms (derive/field row :time))
                       (parse-ms (derive/field row :timestamp)))]
       (when (and raw-type-key time-ms)
         (let [[type-key overrides] (derive/retype raw-type-key delta)
               overrides* (or overrides {})
               asset (derive/asset delta)
               signed (derive/signed-amount type-key delta viewer-address overrides*)
               {:keys [from-label to-label destination-address]}
               (derive/endpoints type-key delta overrides*)
               {:keys [fee fee-asset]} (derive/fee type-key delta)
               usd-value (derive/usd-value type-key delta signed asset)
               hash (derive/non-blank-text (or (derive/field row :hash)
                                               (derive/field delta :hash)))]
           {:id (ledger-row-id time-ms type-key hash asset signed)
            :time-ms time-ms
            :time time-ms
            :type-key type-key
            :status-label (status-label row delta)
            :action-label (action-label type-key)
            :asset asset
            :from-label from-label
            :to-label to-label
            :destination-address destination-address
            :amount (derive/parse-decimal (derive/field delta :amount))
            :signed-amount signed
            :amount-text (format-signed-amount signed asset)
            :usd-value usd-value
            :usd-value-text (format-usd-value usd-value)
            :fee fee
            :fee-asset fee-asset
            :fee-text (format-fee fee fee-asset)
            :hash hash
            :explorer-url (explorer-url hash)}))))))

(defn- sort-ledger-rows
  [rows]
  (->> rows
       (sort-by (fn [row]
                  [(- (or (:time-ms row) 0))
                   (or (:id row) "")]))
       vec))

(defn normalize-ledger-rows
  ([rows]
   (normalize-ledger-rows rows nil))
  ([rows viewer-address]
   (sort-ledger-rows
    (into []
          (comp
           (map #(normalize-ledger-row % viewer-address))
           (keep identity))
          (or rows [])))))

(defn merge-ledger-rows
  "Merge the REST snapshot and the live websocket mirror of the same ledger,
   de-duplicated by row id and sorted newest first."
  ([primary secondary]
   (merge-ledger-rows primary secondary nil))
  ([primary secondary viewer-address]
   (->> (concat (or primary []) (or secondary []))
        (#(normalize-ledger-rows % viewer-address))
        (reduce (fn [acc row]
                  (if (seq (:id row))
                    (assoc acc (:id row) row)
                    acc))
                {})
        vals
        sort-ledger-rows)))
