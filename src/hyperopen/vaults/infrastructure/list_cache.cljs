(ns hyperopen.vaults.infrastructure.list-cache
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.vaults :as vault-endpoints]
            [hyperopen.platform :as platform]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.utils.parse :as parse-utils]))

(def vault-index-cache-key
  "vault-index-cache")

(def vault-index-cache-metadata-key
  "vault-index-cache:metadata")

(def vault-index-cache-version
  1)

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- parse-int-safe
  [value]
  (parse-utils/parse-int-value value))

(defn- parse-optional-num
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (let [text (str/trim value)]
                                (if (seq text)
                                  (js/Number text)
                                  js/NaN))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

(defn- normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- normalize-relationship-type
  [value]
  (let [token (cond
                (keyword? value) (name value)
                :else value)]
    (case (some-> token
                  non-blank-text
                  str/lower-case)
    "parent" :parent
    "child" :child
    :normal)))

(defn- normalize-relationship
  [relationship]
  (let [relationship* (if (map? relationship) relationship {})
        type* (normalize-relationship-type (:type relationship*))]
    (cond-> {:type type*}
      (= type* :parent)
      (assoc :child-addresses
             (->> (or (:child-addresses relationship*)
                      (:childAddresses relationship*)
                      [])
                  (keep normalize-address)
                  vec))

      (= type* :child)
      (assoc :parent-address
             (some (fn [value]
                     (normalize-address value))
                   [(:parent-address relationship*)
                    (:parentAddress relationship*)])))))

(defn- normalize-snapshot-preview-entry
  [entry]
  (when (map? entry)
    (let [series (->> (or (:series entry) [])
                      (keep parse-optional-num)
                      vec)
          last-value (parse-optional-num (or (:last-value entry)
                                             (:lastValue entry)))]
      (when (seq series)
        {:series series
         :last-value (or last-value
                         (peek series))}))))

(defn- normalize-preview-snapshot-key
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (keyword (str/lower-case (str/trim value)))
                :else nil)]
    (when (contains? #{:day :week :month :all-time} token)
      token)))

(defn- normalize-snapshot-preview-by-key
  [preview-by-key]
  (if (map? preview-by-key)
    (reduce-kv (fn [acc key entry]
                 (if-let [key* (normalize-preview-snapshot-key key)]
                   (if-let [entry* (normalize-snapshot-preview-entry entry)]
                     (assoc acc key* entry*)
                     acc)
                   acc))
               {}
               preview-by-key)
    {}))

(defn- normalize-vault-index-preview-row
  [row]
  (when (map? row)
    (let [vault-address (some (fn [value]
                                (normalize-address value))
                              [(:vault-address row)
                               (:vaultAddress row)])
          tvl-source (or (:tvl row) (:tvl-raw row))
          apr-source (or (:apr row) (:apr-raw row))]
      (when vault-address
        {:name (or (non-blank-text (:name row))
                   vault-address)
         :vault-address vault-address
         :leader (some (fn [value]
                         (normalize-address value))
                       [(:leader row)])
         :tvl (or (parse-optional-num tvl-source) 0)
         :tvl-raw tvl-source
         :is-closed? (boolean (true? (or (:is-closed? row)
                                         (:isClosed row))))
         :relationship (normalize-relationship (:relationship row))
         :create-time-ms (some parse-int-safe
                               [(:create-time-ms row)
                                (:createTimeMillis row)])
         :apr (or (parse-optional-num apr-source) 0)
         :apr-raw apr-source
         :snapshot-preview-by-key (normalize-snapshot-preview-by-key
                                   (:snapshot-preview-by-key row))}))))

(defn- normalize-vault-index-cache-row
  [row]
  (or (normalize-vault-index-preview-row row)
      (vault-endpoints/normalize-vault-index-row row)))

(defn- normalize-vault-index-cache-rows
  [rows]
  (if (sequential? rows)
    (->> rows
         (keep normalize-vault-index-cache-row)
         vec)
    []))

(defn- parse-saved-at-ms
  [value]
  (let [candidate (parse-int-safe value)]
    (when (number? candidate)
      (max 0 candidate))))

(defn normalize-vault-index-cache-record
  [raw]
  (cond
    (map? raw)
    (let [raw-rows (:rows raw)
          rows (normalize-vault-index-cache-rows raw-rows)
          saved-at-ms (parse-saved-at-ms (:saved-at-ms raw))]
      (when (and (some? saved-at-ms)
                 (sequential? raw-rows))
        {:id (or (non-blank-text (:id raw))
                 vault-index-cache-key)
         :version (or (parse-int-safe (:version raw))
                      vault-index-cache-version)
         :saved-at-ms saved-at-ms
         :etag (non-blank-text (:etag raw))
         :last-modified (non-blank-text (:last-modified raw))
         :rows (vec rows)}))

    (sequential? raw)
    {:id vault-index-cache-key
     :version 0
     :saved-at-ms 0
     :etag nil
     :last-modified nil
     :rows (normalize-vault-index-cache-rows raw)}

    :else
    nil))

(defn normalize-vault-index-cache-metadata
  [raw]
  (when (map? raw)
    (let [saved-at-ms (parse-saved-at-ms (:saved-at-ms raw))]
      (when (some? saved-at-ms)
        {:id (or (non-blank-text (:id raw))
                 vault-index-cache-metadata-key)
         :version (or (parse-int-safe (:version raw))
                      vault-index-cache-version)
         :saved-at-ms saved-at-ms
         :etag (non-blank-text (:etag raw))
         :last-modified (non-blank-text (:last-modified raw))}))))

(defn build-vault-index-cache-record
  [rows {:keys [etag last-modified]}]
  {:id vault-index-cache-key
   :version vault-index-cache-version
   :saved-at-ms (platform/now-ms)
   :etag (non-blank-text etag)
   :last-modified (non-blank-text last-modified)
   :rows (normalize-vault-index-cache-rows rows)})

(defn build-vault-index-cache-metadata
  [{:keys [saved-at-ms etag last-modified]}]
  {:id vault-index-cache-metadata-key
   :version vault-index-cache-version
   :saved-at-ms (or (parse-saved-at-ms saved-at-ms)
                    (platform/now-ms))
   :etag (non-blank-text etag)
   :last-modified (non-blank-text last-modified)})

(defn load-vault-index-cache-record!
  []
  (-> (indexed-db/get-json! indexed-db/vault-index-store
                            vault-index-cache-key)
      (.then normalize-vault-index-cache-record)))

(defn load-vault-index-cache-metadata!
  []
  (-> (indexed-db/get-json! indexed-db/vault-index-store
                            vault-index-cache-metadata-key)
      (.then normalize-vault-index-cache-metadata)))

(defn persist-vault-index-cache-record!
  [rows metadata]
  (let [record (build-vault-index-cache-record rows metadata)
        metadata-record (build-vault-index-cache-metadata record)]
    (-> (indexed-db/put-json! indexed-db/vault-index-store
                              vault-index-cache-key
                              record)
        (.then (fn [persisted?]
                 (if-not persisted?
                   false
                   (indexed-db/put-json! indexed-db/vault-index-store
                                         vault-index-cache-metadata-key
                                         metadata-record)))))))
