(ns hyperopen.vaults.infrastructure.preview-cache
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(def ^:private day-ms
  (* 24 60 60 1000))

(def ^:private protocol-vault-names
  #{"hyperliquidity provider (hlp)"
    "liquidator"})

(def vault-startup-preview-storage-key
  "vault-startup-preview:v1")

(def vault-startup-preview-version
  1)

(def ^:private vault-startup-preview-max-age-ms
  (* 60 60 1000))

(def ^:private vault-startup-preview-stale-threshold-ms
  (* 15 60 1000))

(def ^:private vault-startup-preview-protocol-row-limit
  4)

(def ^:private vault-startup-preview-user-row-limit
  8)

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- optional-number
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

(defn- parse-saved-at-ms
  [value]
  (let [candidate (optional-number value)]
    (when (number? candidate)
      (max 0 candidate))))

(defn- normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- normalize-snapshot-series
  [series]
  (if (sequential? series)
    (->> series
         (keep optional-number)
         (take 32)
         vec)
    []))

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

(defn- snapshot-range-keys
  [snapshot-range]
  (get snapshot-range-keys-by-range
       (vault-ui-state/normalize-vault-snapshot-range snapshot-range)
       default-snapshot-range-keys))

(defn- snapshot-series-for-range
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

(defn- normalize-age-days
  [create-time-ms now-ms]
  (if (and (number? create-time-ms)
           (number? now-ms)
           (>= now-ms create-time-ms))
    (js/Math.floor (/ (- now-ms create-time-ms) day-ms))
    0))

(defn- visible-vault-rows
  [state]
  (let [merged-index-rows (get-in state [:vaults :merged-index-rows])
        index-rows (get-in state [:vaults :index-rows])]
    (cond
      (seq merged-index-rows) merged-index-rows
      (seq index-rows) index-rows
      :else [])))

(defn- preview-row-model
  [row wallet-address equity-by-address snapshot-range now-ms]
  (let [vault-address (normalize-address (:vault-address row))
        name (or (some-> (:name row) str str/trim)
                 vault-address
                 "Unknown Vault")
        leader (normalize-address (:leader row))
        user-equity-row (get equity-by-address vault-address)
        your-deposit (or (optional-number (:equity user-equity-row)) 0)
        relationship-type (get-in row [:relationship :type] :normal)
        name-token (str/lower-case name)]
    (when (and vault-address
               (not= :child relationship-type)
               (not (true? (:is-closed? row))))
      {:name name
       :vault-address vault-address
       :leader leader
       :apr (normalize-percent-value (:apr row))
       :tvl (or (optional-number (:tvl row)) 0)
       :your-deposit your-deposit
       :age-days (normalize-age-days (:create-time-ms row) now-ms)
       :snapshot-series (snapshot-series-for-range row snapshot-range)
       :is-closed? (boolean (:is-closed? row))
       :is-protocol? (contains? protocol-vault-names name-token)
       :has-deposit? (pos? your-deposit)
       :is-leading? (and (seq wallet-address)
                         (= wallet-address leader))})))

(defn- sort-key
  [row column]
  (case column
    :vault (str/lower-case (or (:name row) ""))
    :leader (str/lower-case (or (:leader row) ""))
    :apr (or (:apr row) 0)
    :tvl (or (:tvl row) 0)
    :your-deposit (or (:your-deposit row) 0)
    :age (or (:age-days row) 0)
    :snapshot 0
    (or (:tvl row) 0)))

(defn- compare-preview-rows
  [left right column direction]
  (let [deposit-priority (compare (if (:has-deposit? left) 0 1)
                                  (if (:has-deposit? right) 0 1))]
    (if (not (zero? deposit-priority))
      deposit-priority
      (let [primary (compare (sort-key left column)
                             (sort-key right column))
            primary* (if (= :asc direction) primary (- primary))]
        (if (zero? primary*)
          (compare (or (:vault-address left) "")
                   (or (:vault-address right) ""))
          primary*)))))

(defn- sorted-preview-rows
  [rows]
  (let [sort-column vault-ui-state/default-vault-sort-column
        sort-direction vault-ui-state/default-vault-sort-direction]
    (sort (fn [left right]
            (compare-preview-rows left right sort-column sort-direction))
          rows)))

(defn- partition-preview-rows
  [rows]
  (reduce (fn [{:keys [protocol-rows user-rows]} row]
            (if (:is-protocol? row)
              {:protocol-rows (conj protocol-rows row)
               :user-rows user-rows}
              {:protocol-rows protocol-rows
               :user-rows (conj user-rows row)}))
          {:protocol-rows []
           :user-rows []}
          rows))

(defn- normalize-preview-row
  [row]
  (when (map? row)
    (let [vault-address (normalize-address (:vault-address row))]
      (when vault-address
        {:name (or (non-blank-text (:name row))
                   vault-address)
         :vault-address vault-address
         :leader (normalize-address (:leader row))
         :apr (or (optional-number (:apr row)) 0)
         :tvl (or (optional-number (:tvl row)) 0)
         :your-deposit (or (optional-number (:your-deposit row)) 0)
         :age-days (max 0 (or (optional-number (:age-days row)) 0))
         :snapshot-series (normalize-snapshot-series (:snapshot-series row))
         :is-closed? (boolean (:is-closed? row))}))))

(defn- normalize-preview-rows
  [rows limit]
  (if (sequential? rows)
    (->> rows
         (keep normalize-preview-row)
         (take limit)
         vec)
    []))

(defn- preview-total-visible-tvl
  [protocol-rows user-rows fallback]
  (or (optional-number fallback)
      (reduce (fn [acc row]
                (+ acc (or (:tvl row) 0)))
              0
              (concat (or protocol-rows [])
                      (or user-rows [])))))

(defn normalize-vault-startup-preview-record
  [raw]
  (when (map? raw)
    (let [version (:version raw)
          saved-at-ms (parse-saved-at-ms (:saved-at-ms raw))
          protocol-rows (normalize-preview-rows (:protocol-rows raw)
                                                vault-startup-preview-protocol-row-limit)
          user-rows (normalize-preview-rows (:user-rows raw)
                                            vault-startup-preview-user-row-limit)]
      (when (and (= vault-startup-preview-version version)
                 (some? saved-at-ms)
                 (or (seq protocol-rows)
                     (seq user-rows)))
        {:id vault-startup-preview-storage-key
         :version vault-startup-preview-version
         :saved-at-ms saved-at-ms
         :snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                          (:snapshot-range raw))
         :wallet-address (normalize-address (:wallet-address raw))
         :total-visible-tvl (preview-total-visible-tvl protocol-rows
                                                       user-rows
                                                       (:total-visible-tvl raw))
         :protocol-rows protocol-rows
         :user-rows user-rows}))))

(defn build-vault-startup-preview-record
  [state]
  (let [snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                        (get-in state [:vaults-ui :snapshot-range]))
        now-ms (platform/now-ms)
        wallet-address (normalize-address (get-in state [:wallet :address]))
        equity-by-address (or (get-in state [:vaults :user-equity-by-address]) {})
        rows (visible-vault-rows state)
        parsed-rows (->> rows
                         (keep #(preview-row-model %
                                                   wallet-address
                                                   equity-by-address
                                                   snapshot-range
                                                   now-ms))
                         sorted-preview-rows
                         vec)
        {:keys [protocol-rows user-rows]} (partition-preview-rows parsed-rows)
        visible-user-rows (->> user-rows
                               (take vault-ui-state/default-vault-user-page-size)
                               vec)
        preview-protocol-rows (->> protocol-rows
                                   (take vault-startup-preview-protocol-row-limit)
                                   vec)
        preview-user-rows (->> visible-user-rows
                               (take vault-startup-preview-user-row-limit)
                               vec)]
    (when (or (seq preview-protocol-rows)
              (seq preview-user-rows))
      {:id vault-startup-preview-storage-key
       :version vault-startup-preview-version
       :saved-at-ms now-ms
       :snapshot-range snapshot-range
       :wallet-address wallet-address
       :total-visible-tvl (reduce (fn [acc row]
                                    (+ acc (or (:tvl row) 0)))
                                  0
                                  parsed-rows)
       :protocol-rows preview-protocol-rows
       :user-rows preview-user-rows
       :stale? false})))

(defn load-vault-startup-preview-record!
  []
  (try
    (let [raw (platform/local-storage-get vault-startup-preview-storage-key)]
      (when (some? raw)
        (let [parsed (try
                       (js->clj (js/JSON.parse raw) :keywordize-keys true)
                       (catch :default _
                         ::invalid))
              normalized (when-not (= ::invalid parsed)
                           (normalize-vault-startup-preview-record parsed))]
          (when-not normalized
            (platform/local-storage-remove! vault-startup-preview-storage-key))
          normalized)))
    (catch :default error
      (js/console.warn "Failed to load vault startup preview cache:" error)
      nil)))

(defn restore-vault-startup-preview
  [preview-record {:keys [snapshot-range wallet-address now-ms]}]
  (let [preview* (normalize-vault-startup-preview-record preview-record)
        preview-snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                                (:snapshot-range preview*))
        current-snapshot-range (vault-ui-state/normalize-vault-snapshot-range snapshot-range)
        current-wallet-address (normalize-address wallet-address)
        preview-wallet-address (normalize-address (:wallet-address preview*))
        saved-at-ms (:saved-at-ms preview*)
        age-ms (when (and (number? now-ms)
                          (number? saved-at-ms))
                 (max 0 (- now-ms saved-at-ms)))
        wallet-match? (and (seq current-wallet-address)
                           (= current-wallet-address preview-wallet-address))
        protocol-rows (vec (or (:protocol-rows preview*) []))
        user-rows (if wallet-match?
                    (vec (or (:user-rows preview*) []))
                    [])]
    (when (and preview*
               (= preview-snapshot-range current-snapshot-range)
               (number? age-ms)
               (<= age-ms vault-startup-preview-max-age-ms)
               (or (seq protocol-rows)
                   (seq user-rows)))
      {:id vault-startup-preview-storage-key
       :version vault-startup-preview-version
       :saved-at-ms saved-at-ms
       :snapshot-range current-snapshot-range
       :wallet-address (when wallet-match?
                         current-wallet-address)
       :total-visible-tvl (preview-total-visible-tvl protocol-rows
                                                     user-rows
                                                     nil)
       :protocol-rows protocol-rows
       :user-rows user-rows
       :stale? (>= age-ms vault-startup-preview-stale-threshold-ms)})))

(defn clear-vault-startup-preview!
  []
  (try
    (platform/local-storage-remove! vault-startup-preview-storage-key)
    true
    (catch :default error
      (js/console.warn "Failed to clear vault startup preview cache:" error)
      false)))

(defn persist-vault-startup-preview-record!
  [state]
  (try
    (if-let [preview-record (build-vault-startup-preview-record state)]
      (do
        (platform/local-storage-set!
         vault-startup-preview-storage-key
         (js/JSON.stringify (clj->js preview-record)))
        true)
      false)
    (catch :default error
      (js/console.warn "Failed to persist vault startup preview cache:" error)
      false)))
