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

(def ^:private default-active-asset
  "BTC")

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

(defn- normalize-keyword-value
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim str/lower-case keyword)
    :else nil))

(defn- normalize-display-text-list
  [values]
  (->> values
       (keep normalize-display-text)
       vec))

(defn- normalize-index-list
  [values]
  (->> values
       (keep parse-market-index)
       vec))

(defn- normalize-outcome-side-cache-entry
  [side]
  (when (map? side)
    (let [side-index (parse-market-index (:side-index side))
          side-name (normalize-display-text (:side-name side))
          side-label (normalize-display-text (:side-label side))
          coin (normalize-display-text (:coin side))
          asset-id (parse-market-index (:asset-id side))
          outcome-id (parse-market-index (:outcome-id side))
          outcome-option-label (normalize-display-text (:outcome-option-label side))]
      (when (and (some? side-index)
                 (seq coin))
        (cond-> {:side-index side-index
                 :coin coin}
          (seq side-name) (assoc :side-name side-name)
          (seq side-label) (assoc :side-label side-label)
          (some? asset-id) (assoc :asset-id asset-id)
          (some? outcome-id) (assoc :outcome-id outcome-id)
          (seq outcome-option-label) (assoc :outcome-option-label outcome-option-label))))))

(defn- normalize-question-option-cache-entry
  [option]
  (when (map? option)
    (let [label (normalize-display-text (:label option))
          outcome-id (parse-market-index (:outcome-id option))
          sides (->> (:sides option)
                     (keep normalize-outcome-side-cache-entry)
                     vec)
          yes-coin (normalize-display-text (:yes-coin option))
          yes-asset-id (parse-market-index (:yes-asset-id option))
          no-coin (normalize-display-text (:no-coin option))
          no-asset-id (parse-market-index (:no-asset-id option))]
      (when (and (seq label)
                 (some? outcome-id)
                 (seq sides))
        (cond-> {:label label
                 :outcome-id outcome-id
                 :sides sides}
          (seq yes-coin) (assoc :yes-coin yes-coin)
          (some? yes-asset-id) (assoc :yes-asset-id yes-asset-id)
          (seq no-coin) (assoc :no-coin no-coin)
          (some? no-asset-id) (assoc :no-asset-id no-asset-id))))))

(defn- normalize-outcome-side-alias-entry
  [alias-key alias-entry]
  (when (map? alias-entry)
    (let [coin (or (normalize-display-text (:coin alias-entry))
                   (normalize-display-text alias-key))
          outcome-id (parse-market-index (:outcome-id alias-entry))
          side-index (parse-market-index (:side-index alias-entry))
          option-label (normalize-display-text (:option-label alias-entry))
          sibling-coins (normalize-display-text-list (:sibling-coins alias-entry))
          question? (parse-optional-boolean (:question? alias-entry))]
      (cond-> {}
        (seq coin) (assoc :coin coin)
        (some? outcome-id) (assoc :outcome-id outcome-id)
        (some? side-index) (assoc :side-index side-index)
        (seq option-label) (assoc :option-label option-label)
        (seq sibling-coins) (assoc :sibling-coins sibling-coins)
        (some? question?) (assoc :question? question?)))))

(defn- normalize-outcome-side-aliases
  [aliases]
  (when (map? aliases)
    (not-empty
     (reduce-kv (fn [normalized alias-key alias-entry]
                  (let [alias-key* (normalize-display-text alias-key)]
                    (if-let [entry (and (seq alias-key*)
                                        (normalize-outcome-side-alias-entry alias-key* alias-entry))]
                      (assoc normalized alias-key* entry)
                      normalized)))
                {}
                aliases))))

(defn normalize-asset-selector-market-cache-entry
  [market]
  (when (map? market)
    (let [market-key (normalize-display-text (:key market))
          coin (normalize-display-text (:coin market))
          symbol (or (normalize-display-text (:symbol market))
                     coin)
          display-name (normalize-display-text (:display-name market))
          display-symbol (normalize-display-text (:display-symbol market))
          search-keywords (normalize-display-text-list (:search-keywords market))
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
          outcome-kind (normalize-keyword-value (:outcome-kind market))
          outcome-category (normalize-keyword-value (:outcome-category market))
          outcome-subcategory (normalize-keyword-value (:outcome-subcategory market))
          question-id (parse-market-index (:question-id market))
          fallback-outcome-id (parse-market-index (:fallback-outcome-id market))
          named-outcome-ids (normalize-index-list (:named-outcome-ids market))
          question-options (->> (:question-options market)
                                (keep normalize-question-option-cache-entry)
                                vec)
          outcome-side-aliases (normalize-outcome-side-aliases (:outcome-side-aliases market))
          outcome-subscription-coins (normalize-display-text-list (:outcome-subscription-coins market))
          selected-outcome-id (parse-market-index (:selected-outcome-id market))
          selected-outcome-option-label (normalize-display-text (:selected-outcome-option-label market))
          outcome-option-id (parse-market-index (:outcome-option-id market))
          outcome-summary (normalize-display-text (:outcome-summary market))
          outcome-details (normalize-display-text (:outcome-details market))
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
          (seq display-name) (assoc :display-name display-name)
          (seq display-symbol) (assoc :display-symbol display-symbol)
          (seq search-keywords) (assoc :search-keywords search-keywords)
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
	          outcome-kind (assoc :outcome-kind outcome-kind)
	          outcome-category (assoc :outcome-category outcome-category)
	          outcome-subcategory (assoc :outcome-subcategory outcome-subcategory)
	          (some? question-id) (assoc :question-id question-id)
	          (some? fallback-outcome-id) (assoc :fallback-outcome-id fallback-outcome-id)
	          (seq named-outcome-ids) (assoc :named-outcome-ids named-outcome-ids)
	          (seq question-options) (assoc :question-options question-options)
	          (seq outcome-side-aliases) (assoc :outcome-side-aliases outcome-side-aliases)
	          (seq outcome-subscription-coins) (assoc :outcome-subscription-coins outcome-subscription-coins)
	          (some? selected-outcome-id) (assoc :selected-outcome-id selected-outcome-id)
	          (seq selected-outcome-option-label) (assoc :selected-outcome-option-label selected-outcome-option-label)
	          (some? outcome-option-id) (assoc :outcome-option-id outcome-option-id)
	          (seq outcome-summary) (assoc :outcome-summary outcome-summary)
	          (seq outcome-details) (assoc :outcome-details outcome-details)
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
                                   (resolve-market-by-coin-fn market-by-key active-asset))
          expired-active-outcome? (markets/expired-outcome-market?
                                   resolved-active-market
                                   (platform/now-ms))
          fallback-active-market (when expired-active-outcome?
                                   (resolve-market-by-coin-fn market-by-key
                                                              default-active-asset))]
      (cond-> (-> state
                  (assoc-in [:asset-selector :markets] cached-markets)
                  (assoc-in [:asset-selector :market-by-key] market-by-key)
                  (assoc-in [:asset-selector :market-index-by-key] market-index-by-key)
                  (assoc-in [:asset-selector :phase] :bootstrap)
                  (assoc-in [:asset-selector :cache-hydrated?] true))
        expired-active-outcome?
        (assoc :active-asset default-active-asset
               :selected-asset default-active-asset
               :active-market fallback-active-market)

        (and (not expired-active-outcome?)
             (map? resolved-active-market))
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
