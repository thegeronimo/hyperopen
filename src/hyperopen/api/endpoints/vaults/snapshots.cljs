(ns hyperopen.api.endpoints.vaults.snapshots
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.vaults.common :as common]))

(def ^:private snapshot-preview-point-limit
  8)

(def ^:private snapshot-key-alias-groups
  {:day #{"day"}
   :week #{"week"}
   :month #{"month"}
   :three-month #{"3m" "3-m" "3month" "3-month" "threemonth" "three-month" "three-months" "quarter"}
   :six-month #{"6m" "6-m" "6month" "6-month" "sixmonth" "six-month" "six-months" "halfyear" "half-year"}
   :one-year #{"1y" "1-y" "1year" "1-year" "oneyear" "one-year" "one-years" "year"}
   :two-year #{"2y" "2-y" "2year" "2-year" "twoyear" "two-year" "two-years"}
   :all-time #{"alltime" "all-time"}})

(def ^:private snapshot-key-by-token
  (reduce-kv (fn [acc snapshot-key aliases]
               (reduce (fn [next-acc alias]
                         (assoc next-acc alias snapshot-key))
                       acc
                       aliases))
             {}
             snapshot-key-alias-groups))

(defn- normalize-snapshot-token
  [value]
  (some-> value
          common/non-blank-text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          str/lower-case
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"(^-+)|(-+$)" "")))

(defn normalize-snapshot-key
  [value]
  (some-> value
          normalize-snapshot-token
          snapshot-key-by-token))

(defn- normalize-pnl-values
  [values]
  (if (sequential? values)
    (->> values
         (keep common/parse-optional-num)
         vec)
    []))

(defn normalize-vault-snapshot-return
  [raw tvl]
  (cond
    (not (number? raw))
    nil

    (and (number? tvl)
         (pos? tvl)
         (> (js/Math.abs raw) 1000))
    (* 100 (/ raw tvl))

    (<= (js/Math.abs raw) 1)
    (* 100 raw)

    :else
    raw))

(defn sample-snapshot-preview-series
  [values]
  (let [values* (vec (or values []))
        value-count (count values*)]
    (cond
      (<= value-count snapshot-preview-point-limit)
      values*

      :else
      (let [last-idx (dec value-count)
            slot-count (dec snapshot-preview-point-limit)
            step (/ last-idx slot-count)]
        (mapv (fn [idx]
                (let [value-idx (if (= idx slot-count)
                                  last-idx
                                  (js/Math.round (* idx step)))
                      value-idx* (max 0 (min last-idx value-idx))]
                  (nth values* value-idx*)))
              (range snapshot-preview-point-limit))))))

(defn- preview-snapshot-key?
  [snapshot-key]
  (contains? #{:day :week :month :all-time} snapshot-key))

(defn normalize-vault-snapshot-preview
  [payload tvl]
  (reduce (fn [acc entry]
            (if (and (sequential? entry)
                     (= 2 (count entry)))
              (let [[range-key values] entry
                    snapshot-key (normalize-snapshot-key range-key)]
                (if (preview-snapshot-key? snapshot-key)
                  (let [normalized-values (->> values
                                               normalize-pnl-values
                                               (keep #(normalize-vault-snapshot-return % tvl))
                                               vec)]
                    (if (seq normalized-values)
                      (assoc acc snapshot-key
                             {:series (sample-snapshot-preview-series normalized-values)
                              :last-value (peek normalized-values)})
                      acc))
                  acc))
              acc))
          {}
          (if (sequential? payload) payload [])))

(defn normalize-vault-pnls
  [payload]
  (reduce (fn [acc entry]
            (if (and (sequential? entry)
                     (= 2 (count entry)))
              (let [[range-key values] entry
                    key* (normalize-snapshot-key range-key)]
                (if key*
                  (assoc acc key* (normalize-pnl-values values))
                  acc))
              acc))
          {}
          (if (sequential? payload) payload [])))

(defn- normalize-relationship-type
  [value]
  (case (some-> value common/non-blank-text str/lower-case)
    "parent" :parent
    "child" :child
    :normal))

(defn normalize-vault-relationship
  [relationship]
  (let [relationship* (if (map? relationship) relationship {})
        type* (normalize-relationship-type (:type relationship*))
        data (:data relationship*)]
    (cond-> {:type type*}
      (and (= type* :parent) (map? data))
      (assoc :child-addresses
             (->> (or (:childAddresses data) [])
                  (keep common/normalize-address)
                  vec))

      (and (= type* :child) (map? data))
      (assoc :parent-address (common/normalize-address (:parentAddress data))))))

(defn normalize-vault-summary
  [payload]
  (when (map? payload)
    (when-let [vault-address (common/normalize-address (:vaultAddress payload))]
      {:name (or (common/non-blank-text (:name payload))
                 vault-address)
       :vault-address vault-address
       :leader (common/normalize-address (:leader payload))
       :tvl (or (common/parse-optional-num (:tvl payload)) 0)
       :tvl-raw (:tvl payload)
       :is-closed? (boolean (or (common/boolean-value (:isClosed payload))
                                false))
       :relationship (normalize-vault-relationship (:relationship payload))
       :create-time-ms (common/parse-optional-int (:createTimeMillis payload))})))

(defn normalize-vault-index-row
  [row]
  (when (map? row)
    (let [summary-source (if (map? (:summary row))
                           (:summary row)
                           row)
          summary (normalize-vault-summary summary-source)
          apr-source (or (:apr row) (:apr summary-source))
          pnls-source (or (:pnls row) (:pnls summary-source))]
      (when summary
        (assoc summary
               :apr (or (common/parse-optional-num apr-source) 0)
               :apr-raw apr-source
               :snapshot-preview-by-key (normalize-vault-snapshot-preview pnls-source
                                                                         (:tvl summary)))))))

(defn normalize-vault-index-rows
  [payload]
  (if (sequential? payload)
    (->> payload
         (keep normalize-vault-index-row)
         vec)
    []))

(defn merge-vault-index-with-summaries
  [index-rows summary-rows]
  (let [index-rows* (normalize-vault-index-rows index-rows)
        summary-rows* (normalize-vault-index-rows summary-rows)
        max-create-time-ms (or (some->> index-rows*
                                         (keep :create-time-ms)
                                         seq
                                         (apply max))
                               -1)
        recent-summary-rows (filter (fn [row]
                                      (> (or (:create-time-ms row) -1)
                                         max-create-time-ms))
                                    summary-rows*)
        merged (concat index-rows* recent-summary-rows)]
    (->> merged
         (reduce (fn [{:keys [order row-by-address]} row]
                   (let [vault-address (:vault-address row)
                         existing (get row-by-address vault-address)
                         row-time (or (:create-time-ms row) -1)
                         existing-time (or (:create-time-ms existing) -1)]
                     (cond
                       (nil? vault-address)
                       {:order order
                        :row-by-address row-by-address}

                       (nil? existing)
                       {:order (conj order vault-address)
                        :row-by-address (assoc row-by-address vault-address row)}

                       (> row-time existing-time)
                       {:order order
                        :row-by-address (assoc row-by-address vault-address row)}

                       :else
                       {:order order
                        :row-by-address row-by-address})))
                 {:order []
                  :row-by-address {}})
         ((fn [{:keys [order row-by-address]}]
            (mapv row-by-address order))))))
