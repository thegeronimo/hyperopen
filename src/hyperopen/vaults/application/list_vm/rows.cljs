(ns hyperopen.vaults.application.list-vm.rows
  (:require [clojure.string :as str]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(def day-ms
  (* 24 60 60 1000))

(def ^:private protocol-vault-names
  #{"hyperliquidity provider (hlp)"
    "liquidator"})

(defn optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else
    nil))

(defn normalize-address
  [value]
  (some-> value str str/trim str/lower-case))

(defn- snapshot-point-value
  [entry]
  (cond
    (number? entry) entry

    (and (sequential? entry)
         (>= (count entry) 2))
    (optional-number (second entry))

    (map? entry)
    (or (optional-number (:value entry))
        (optional-number (:pnl entry))
        (optional-number (:account-value entry))
        (optional-number (:accountValue entry)))

    :else
    nil))

(defn- snapshot-preview-entry
  [row snapshot-key]
  (let [entry (get-in row [:snapshot-preview-by-key snapshot-key])]
    (when (map? entry)
      entry)))

(defn- normalize-percent-value
  [value]
  (let [n (or (optional-number value) 0)]
    (if (<= (js/Math.abs n) 1)
      (* 100 n)
      n)))

(def ^:private default-snapshot-range-keys
  [:month :week :all-time :day])

(def ^:private extended-snapshot-range-keys
  [:all-time :month :week :day])

(def ^:private snapshot-range-keys-by-range
  {:day [:day :week :month :all-time]
   :week [:week :month :all-time :day]
   :month default-snapshot-range-keys
   :three-month extended-snapshot-range-keys
   :six-month extended-snapshot-range-keys
   :one-year extended-snapshot-range-keys
   :two-year extended-snapshot-range-keys
   :all-time extended-snapshot-range-keys})

(defn snapshot-range-keys
  [snapshot-range]
  (get snapshot-range-keys-by-range
       (vault-ui-state/normalize-vault-snapshot-range snapshot-range)
       default-snapshot-range-keys))

(defn snapshot-series-for-range
  [row snapshot-range]
  (or (some (fn [snapshot-key]
              (if-let [{:keys [series]} (snapshot-preview-entry row snapshot-key)]
                (when (sequential? series)
                  (let [normalized-values (->> series
                                               (keep optional-number)
                                               vec)]
                    (when (seq normalized-values)
                      normalized-values)))
                (let [snapshot-values (get-in row [:snapshot-by-key snapshot-key])]
                  (when (sequential? snapshot-values)
                    (let [normalized-values (->> snapshot-values
                                                 (keep snapshot-point-value)
                                                 (mapv normalize-percent-value))]
                      (when (seq normalized-values)
                        normalized-values))))))
            (snapshot-range-keys snapshot-range))
      []))

(defn snapshot-last-value
  [values]
  (if (sequential? values)
    (or (some->> values
                 (keep snapshot-point-value)
                 seq
                 last)
        0)
    0))

(defn snapshot-last-value-for-range
  [row snapshot-range]
  (or (some (fn [snapshot-key]
              (if-let [{:keys [last-value]} (snapshot-preview-entry row snapshot-key)]
                (optional-number last-value)
                (let [snapshot-values (get-in row [:snapshot-by-key snapshot-key])]
                  (when (sequential? snapshot-values)
                    (some-> snapshot-values
                            snapshot-last-value
                            normalize-percent-value)))))
            (snapshot-range-keys snapshot-range))
      0))

(defn normalize-age-days
  [create-time-ms now-ms]
  (if (and (number? create-time-ms)
           (number? now-ms)
           (>= now-ms create-time-ms))
    (js/Math.floor (/ (- now-ms create-time-ms) day-ms))
    0))

(defn row-search-text
  [{:keys [name leader vault-address]}]
  (str (or name "")
       " "
       (or leader "")
       " "
       (or vault-address "")))

(defn parse-vault-row
  [row wallet-address equity-by-address snapshot-range now-ms]
  (let [vault-address (normalize-address (:vault-address row))
        name (or (some-> (:name row) str str/trim)
                 vault-address
                 "Unknown Vault")
        name-token (str/lower-case name)
        leader (normalize-address (:leader row))
        tvl (or (optional-number (:tvl row)) 0)
        apr (normalize-percent-value (:apr row))
        user-equity-row (get equity-by-address vault-address)
        your-deposit (or (optional-number (:equity user-equity-row)) 0)
        snapshot-series (snapshot-series-for-range row snapshot-range)
        snapshot-value (snapshot-last-value-for-range row snapshot-range)
        is-leading? (and (seq wallet-address)
                         (= wallet-address leader))
        has-deposit? (pos? your-deposit)
        is-other? (and (not is-leading?)
                       (not has-deposit?))
        is-closed? (true? (:is-closed? row))
        create-time-ms (:create-time-ms row)
        search-text (str/lower-case (row-search-text {:name name
                                                      :leader leader
                                                      :vault-address vault-address}))
        relationship-type (get-in row [:relationship :type] :normal)]
    {:name name
     :vault-address vault-address
     :leader leader
     :tvl tvl
     :apr apr
     :your-deposit your-deposit
     :snapshot snapshot-value
     :snapshot-series snapshot-series
     :search-text search-text
     :vault-sort-key (str/lower-case name)
     :leader-sort-key (str/lower-case (or leader ""))
     :is-leading? is-leading?
     :has-deposit? has-deposit?
     :is-other? is-other?
     :is-closed? is-closed?
     :is-protocol? (contains? protocol-vault-names name-token)
     :create-time-ms create-time-ms
     :age-days (normalize-age-days create-time-ms now-ms)
     :relationship-type relationship-type}))
