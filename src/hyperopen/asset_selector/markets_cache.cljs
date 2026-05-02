(ns hyperopen.asset-selector.markets-cache
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.platform :as platform]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private asset-selector-markets-cache-local-storage-key
  "asset-selector-markets-cache")

(def ^:private asset-selector-markets-cache-version
  1)

(def ^:private supported-market-types
  #{:perp :spot :outcome})

(def ^:private supported-market-categories
  #{:spot :crypto :tradfi :outcome})

(defn normalize-market-type
  [value]
  (let [market-type (cond
                      (keyword? value) value
                      (string? value) (some-> value str/trim str/lower-case keyword)
                      :else nil)]
    (when (contains? supported-market-types market-type)
      market-type)))

(defn parse-max-leverage
  [value]
  (cond
    (number? value) value
    (string? value) (let [num (js/parseFloat value)]
                      (when-not (js/isNaN num)
                        num))
    :else nil))

(defn parse-market-index
  [value]
  (parse-utils/parse-int-value value))

(defn- normalize-market-category
  [value]
  (let [category (cond
                   (keyword? value) value
                   (string? value) (some-> value str/trim str/lower-case keyword)
                   :else nil)]
    (when (contains? supported-market-categories category)
      category)))

(defn- parse-optional-boolean
  [value]
  (cond
    (boolean? value) value
    (string? value) (= "true" (some-> value str/trim str/lower-case))
    :else nil))

(defn- normalize-margin-mode
  [value]
  (let [token (cond
                (keyword? value) (name value)
                (string? value) value
                :else nil)
        normalized (some-> token
                          str/trim
                          str/lower-case
                          (str/replace #"[_-]" ""))]
    (case normalized
      "normal" :normal
      "nocross" :no-cross
      "strictisolated" :strict-isolated
      nil)))

(defn normalize-display-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-outcome-side-cache-entry
  [side]
  (when (map? side)
    (let [side-index (parse-market-index (:side-index side))
          side-name (normalize-display-text (:side-name side))
          coin (normalize-display-text (:coin side))
          asset-id (parse-market-index (:asset-id side))]
      (when (and (some? side-index)
                 (seq side-name)
                 (seq coin)
                 (some? asset-id))
        {:side-index side-index
         :side-name side-name
         :coin coin
         :asset-id asset-id}))))

(defn normalize-asset-selector-market-cache-entry
  [market]
  (when (map? market)
    (let [market-key (normalize-display-text (:key market))
          coin (normalize-display-text (:coin market))
          symbol (or (normalize-display-text (:symbol market))
                     coin)
          base (or (normalize-display-text (:base market))
                   coin)
          quote (normalize-display-text (:quote market))
          dex (normalize-display-text (:dex market))
          market-type (normalize-market-type (:market-type market))
          category (normalize-market-category (:category market))
          hip3? (parse-optional-boolean (:hip3? market))
          hip3-eligible? (parse-optional-boolean (:hip3-eligible? market))
          only-isolated? (parse-optional-boolean
                          (or (:only-isolated? market)
                              (:onlyIsolated market)))
          margin-mode (normalize-margin-mode
                       (or (:margin-mode market)
                           (:marginMode market)))
          market-idx (parse-market-index (:idx market))
          perp-dex-index (some parse-market-index
                               [(:perp-dex-index market)
                                (:perpDexIndex market)])
          explicit-asset-id (some parse-market-index
                                  [(:asset-id market)
                                   (:assetId market)])
          asset-id (or explicit-asset-id
                       (when (and (some? market-idx)
                                  (not (seq dex)))
                         market-idx))
          max-leverage (parse-max-leverage (:maxLeverage market))
          cache-order (parse-utils/parse-int-value (:cache-order market))
          title (normalize-display-text (:title market))
          outcome-id (parse-market-index (:outcome-id market))
          expiry-ms (parse-market-index (:expiry-ms market))
          target-price (normalize-display-text (:target-price market))
          period (normalize-display-text (:period market))
          outcome-sides (->> (:outcome-sides market)
                             (keep normalize-outcome-side-cache-entry)
                             vec)]
      (when (and (seq market-key) (seq coin) (seq symbol))
        (cond-> {:key market-key
                 :coin coin
                 :symbol symbol
                 :base base}
          (seq title) (assoc :title title)
          (seq quote) (assoc :quote quote)
          (seq dex) (assoc :dex dex)
          market-type (assoc :market-type market-type)
          category (assoc :category category)
          (some? hip3?) (assoc :hip3? hip3?)
          (some? hip3-eligible?) (assoc :hip3-eligible? hip3-eligible?)
          (some? only-isolated?) (assoc :only-isolated? only-isolated?)
          margin-mode (assoc :margin-mode margin-mode)
          (some? market-idx) (assoc :idx market-idx)
          (some? perp-dex-index) (assoc :perp-dex-index perp-dex-index)
          (some? asset-id) (assoc :asset-id asset-id)
          (some? max-leverage) (assoc :maxLeverage max-leverage)
          (some? outcome-id) (assoc :outcome-id outcome-id)
          (some? expiry-ms) (assoc :expiry-ms expiry-ms)
          (seq target-price) (assoc :target-price target-price)
          (seq period) (assoc :period period)
          (seq outcome-sides) (assoc :outcome-sides outcome-sides)
          (some? cache-order) (assoc :cache-order cache-order))))))

(defn normalize-asset-selector-markets-cache
  [markets]
  (if (sequential? markets)
    (->> markets
         (keep normalize-asset-selector-market-cache-entry)
         vec)
    []))

(defn- market-by-key-from-markets
  [markets]
  (into {}
        (map (fn [market]
               [(:key market) market]))
        markets))

(defn- market-index-by-key-from-markets
  [markets]
  (reduce-kv (fn [acc idx market]
               (if-let [market-key (:key market)]
                 (assoc acc market-key idx)
                 acc))
             {}
             (vec (or markets []))))

(defn- parse-sort-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat value)
              :else js/NaN)]
    (if (or (not (number? num))
            (js/isNaN num))
      0
      num)))

(defn- sort-token
  [value]
  (str/lower-case (or (normalize-display-text value) "")))

(defn- selector-market-primary-sort-value
  [sort-by market]
  (case sort-by
    :name (sort-token (:symbol market))
    :price (parse-sort-number (:mark market))
    :change (parse-sort-number (:change24hPct market))
    :funding (parse-sort-number (:fundingRate market))
    :openInterest (parse-sort-number (:openInterest market))
    :volume (parse-sort-number (:volume24h market))
    (parse-sort-number (:volume24h market))))

(defn- selector-market-fallback-rank
  [market]
  [(or (parse-utils/parse-int-value (:cache-order market))
       js/Number.MAX_SAFE_INTEGER)
   (sort-token (:symbol market))
   (sort-token (:coin market))
   (sort-token (:key market))])

(defn- compare-selector-markets
  [sort-by sort-direction a b]
  (let [primary-cmp (compare (selector-market-primary-sort-value sort-by a)
                             (selector-market-primary-sort-value sort-by b))
        directional-primary (if (= :desc sort-direction)
                              (- primary-cmp)
                              primary-cmp)]
    (if (zero? directional-primary)
      (compare (selector-market-fallback-rank a)
               (selector-market-fallback-rank b))
      directional-primary)))

(defn- sort-selector-markets-for-cache
  [markets sort-by sort-direction]
  (->> markets
       (sort (fn [a b]
               (neg? (compare-selector-markets sort-by sort-direction a b))))
       vec))

(defn build-asset-selector-markets-cache
  [markets state]
  (let [sort-by (get-in state [:asset-selector :sort-by] :volume)
        sort-direction (get-in state [:asset-selector :sort-direction] :desc)
        ordered-markets (sort-selector-markets-for-cache markets sort-by sort-direction)]
    (->> ordered-markets
         (map-indexed (fn [idx market]
                        (some-> (normalize-asset-selector-market-cache-entry market)
                                (assoc :cache-order idx))))
         (keep identity)
         vec)))

(defn- parse-saved-at-ms
  [value]
  (or (parse-utils/parse-int-value value)
      0))

(defn- normalize-asset-selector-markets-cache-record
  [raw]
  (cond
    (sequential? raw)
    (when-let [rows (not-empty (normalize-asset-selector-markets-cache raw))]
      {:id asset-selector-markets-cache-local-storage-key
       :version 0
       :saved-at-ms 0
       :rows rows})

    (map? raw)
    (when-let [rows (not-empty (normalize-asset-selector-markets-cache (:rows raw)))]
      {:id (or (:id raw) asset-selector-markets-cache-local-storage-key)
       :version (or (parse-utils/parse-int-value (:version raw))
                    asset-selector-markets-cache-version)
       :saved-at-ms (parse-saved-at-ms (:saved-at-ms raw))
       :rows rows})

    :else
    nil))

(defn- build-asset-selector-markets-cache-record
  [markets state now-ms-fn]
  (when-let [rows (not-empty (build-asset-selector-markets-cache markets state))]
    {:id asset-selector-markets-cache-local-storage-key
     :version asset-selector-markets-cache-version
     :saved-at-ms (now-ms-fn)
     :rows rows}))

(defn- persist-asset-selector-markets-cache-record-to-local-storage!
  [record]
  (when record
    (try
      (platform/local-storage-set! asset-selector-markets-cache-local-storage-key
                                   (js/JSON.stringify (clj->js record)))
      true
      (catch :default e
        (js/console.warn "Failed to persist asset selector market cache to localStorage:" e)
        false))))

(defn- load-asset-selector-markets-cache-record-from-local-storage
  []
  (try
    (let [raw (platform/local-storage-get asset-selector-markets-cache-local-storage-key)]
      (when (seq raw)
        (normalize-asset-selector-markets-cache-record
         (js->clj (js/JSON.parse raw) :keywordize-keys true))))
    (catch :default _
      nil)))

(defn- persist-asset-selector-markets-cache-record-to-indexed-db!
  [record]
  (indexed-db/put-json! indexed-db/asset-selector-markets-store
                        asset-selector-markets-cache-local-storage-key
                        record))

(defn- load-asset-selector-markets-cache-record-from-indexed-db!
  []
  (-> (indexed-db/get-json! indexed-db/asset-selector-markets-store
                            asset-selector-markets-cache-local-storage-key)
      (.then normalize-asset-selector-markets-cache-record)))

(defn- newer-cache-record
  [a b]
  (cond
    (and a b)
    (if (>= (:saved-at-ms a 0)
            (:saved-at-ms b 0))
      a
      b)

    a
    a

    b
    b

    :else
    nil))

(defn- ->promise
  [result]
  (if (instance? js/Promise result)
    result
    (js/Promise.resolve result)))

(defn persist-asset-selector-markets-cache!
  ([markets]
   (persist-asset-selector-markets-cache! markets {} {}))
  ([markets state]
   (persist-asset-selector-markets-cache! markets state {}))
  ([markets state {:keys [now-ms-fn
                          persist-indexed-db-fn
                          persist-local-storage-fn]
   :or {now-ms-fn platform/now-ms
                        persist-indexed-db-fn persist-asset-selector-markets-cache-record-to-indexed-db!
                        persist-local-storage-fn persist-asset-selector-markets-cache-record-to-local-storage!}}]
   (if-let [record (build-asset-selector-markets-cache-record markets state now-ms-fn)]
     (-> (->promise (persist-indexed-db-fn record))
         (.then (fn [persisted?]
                  (when-not persisted?
                    (persist-local-storage-fn record))
                  persisted?))
         (.catch (fn [e]
                   (js/console.warn "Failed to persist asset selector market cache to IndexedDB:" e)
                   (persist-local-storage-fn record)
                   false)))
     (js/Promise.resolve false))))

(defn load-asset-selector-markets-cache
  ([]
   (load-asset-selector-markets-cache {}))
  ([{:keys [load-indexed-db-fn
            load-local-storage-fn
            persist-indexed-db-fn]
     :or {load-indexed-db-fn load-asset-selector-markets-cache-record-from-indexed-db!
          load-local-storage-fn load-asset-selector-markets-cache-record-from-local-storage
          persist-indexed-db-fn persist-asset-selector-markets-cache-record-to-indexed-db!}}]
   (let [local-record (load-local-storage-fn)]
     (-> (->promise (load-indexed-db-fn))
         (.catch (fn [error]
                   (js/console.warn "Failed to load asset selector market cache from IndexedDB:" error)
                   nil))
         (.then (fn [indexed-db-record]
                  (let [selected-record (newer-cache-record indexed-db-record local-record)]
                    (when (and local-record
                               (not= selected-record indexed-db-record))
                      (-> (->promise (persist-indexed-db-fn local-record))
                          (.catch (fn [_]
                                    nil))))
                    (vec (:rows selected-record [])))))))))

(defn restore-asset-selector-markets-cache-state
  [state cached-markets resolve-market-by-coin-fn]
  (if (seq (get-in state [:asset-selector :markets]))
    state
    (let [market-by-key (market-by-key-from-markets cached-markets)
          market-index-by-key (market-index-by-key-from-markets cached-markets)
          active-asset (:active-asset state)
          resolved-active-market (when (string? active-asset)
                                   (resolve-market-by-coin-fn market-by-key active-asset))]
      (cond-> (-> state
                  (assoc-in [:asset-selector :markets] cached-markets)
                  (assoc-in [:asset-selector :market-by-key] market-by-key)
                  (assoc-in [:asset-selector :market-index-by-key] market-index-by-key)
                  (assoc-in [:asset-selector :phase] :bootstrap)
                  (assoc-in [:asset-selector :cache-hydrated?] true))
        (map? resolved-active-market)
        (assoc :active-market resolved-active-market)))))

(defn restore-asset-selector-markets-cache!
  ([store]
   (restore-asset-selector-markets-cache!
    store
    {:load-cache-fn load-asset-selector-markets-cache
     :resolve-market-by-coin-fn markets/resolve-or-infer-market-by-coin}))
  ([store {:keys [load-cache-fn resolve-market-by-coin-fn]}]
   (-> (->promise (load-cache-fn))
       (.then (fn [cached-markets]
                (when (seq cached-markets)
                  (swap! store
                         restore-asset-selector-markets-cache-state
                         cached-markets
                         resolve-market-by-coin-fn))
                cached-markets))
       (.catch (fn [error]
                 (js/console.warn "Failed to restore asset selector market cache:" error)
                 [])))))
