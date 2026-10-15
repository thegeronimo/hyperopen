(ns hyperopen.asset-selector.active-market-cache
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]))

(def ^:private active-market-display-local-storage-key
  "active-market-display")

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

(defn- normalize-keyword-value
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim str/lower-case keyword)
    :else nil))

(defn- normalize-display-text-list
  [values normalize-display-text]
  (->> values
       (keep normalize-display-text)
       vec))

(defn- normalize-index-list
  [values parse-index]
  (->> values
       (keep parse-index)
       vec))

(defn- normalize-outcome-side-display
  [side normalize-display-text parse-index]
  (when (map? side)
    (let [side-index (parse-index (:side-index side))
          side-name (normalize-display-text (:side-name side))
          side-label (normalize-display-text (:side-label side))
          coin (normalize-display-text (:coin side))
          asset-id (parse-index (:asset-id side))
          outcome-id (parse-index (:outcome-id side))
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

(defn- normalize-question-option-display
  [option normalize-display-text parse-index]
  (when (map? option)
    (let [label (normalize-display-text (:label option))
          outcome-id (parse-index (:outcome-id option))
          sides (->> (:sides option)
                     (keep #(normalize-outcome-side-display % normalize-display-text parse-index))
                     vec)
          yes-coin (normalize-display-text (:yes-coin option))
          yes-asset-id (parse-index (:yes-asset-id option))
          no-coin (normalize-display-text (:no-coin option))
          no-asset-id (parse-index (:no-asset-id option))]
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

(defn- normalize-outcome-side-alias-display
  [alias-key alias-entry normalize-display-text parse-index]
  (when (map? alias-entry)
    (let [coin (or (normalize-display-text (:coin alias-entry))
                   (normalize-display-text alias-key))
          outcome-id (parse-index (:outcome-id alias-entry))
          side-index (parse-index (:side-index alias-entry))
          option-label (normalize-display-text (:option-label alias-entry))
          sibling-coins (normalize-display-text-list (:sibling-coins alias-entry) normalize-display-text)
          question? (parse-optional-boolean (:question? alias-entry))]
      (cond-> {}
        (seq coin) (assoc :coin coin)
        (some? outcome-id) (assoc :outcome-id outcome-id)
        (some? side-index) (assoc :side-index side-index)
        (seq option-label) (assoc :option-label option-label)
        (seq sibling-coins) (assoc :sibling-coins sibling-coins)
        (some? question?) (assoc :question? question?)))))

(defn- normalize-outcome-side-aliases-display
  [aliases normalize-display-text parse-index]
  (when (map? aliases)
    (not-empty
     (reduce-kv (fn [normalized alias-key alias-entry]
                  (let [alias-key* (normalize-display-text alias-key)]
                    (if-let [entry (and (seq alias-key*)
                                        (normalize-outcome-side-alias-display alias-key*
                                                                              alias-entry
                                                                              normalize-display-text
                                                                              parse-index))]
                      (assoc normalized alias-key* entry)
                      normalized)))
                {}
                aliases))))

(defn normalize-active-market-display
  [market {:keys [normalize-display-text normalize-market-type parse-max-leverage parse-market-index]}]
  (when (map? market)
    (let [parse-index (or parse-market-index (fn [_] nil))
          coin (normalize-display-text (:coin market))
          key (normalize-display-text (:key market))
          symbol (normalize-display-text (:symbol market))
          display-name (normalize-display-text (:display-name market))
          display-symbol (normalize-display-text (:display-symbol market))
          base (normalize-display-text (:base market))
          quote (normalize-display-text (:quote market))
          dex (normalize-display-text (:dex market))
          market-type (normalize-market-type (:market-type market))
          only-isolated? (parse-optional-boolean
                          (or (:only-isolated? market)
                              (:onlyIsolated market)))
          margin-mode (normalize-margin-mode
                       (or (:margin-mode market)
                           (:marginMode market)))
          market-idx (parse-index (:idx market))
          perp-dex-index (some parse-index
                               [(:perp-dex-index market)
                                (:perpDexIndex market)])
          explicit-asset-id (some parse-index
                                  [(:asset-id market)
                                   (:assetId market)])
          asset-id (or explicit-asset-id
                       (when (and (some? market-idx)
                                  (not (seq dex)))
                         market-idx))
          max-leverage (parse-max-leverage (:maxLeverage market))
          outcome-id (parse-index (:outcome-id market))
          outcome-kind (normalize-keyword-value (:outcome-kind market))
          outcome-category (normalize-keyword-value (:outcome-category market))
          outcome-subcategory (normalize-keyword-value (:outcome-subcategory market))
          question-id (parse-index (:question-id market))
          fallback-outcome-id (parse-index (:fallback-outcome-id market))
          named-outcome-ids (normalize-index-list (:named-outcome-ids market) parse-index)
          question-options (->> (:question-options market)
                                (keep #(normalize-question-option-display % normalize-display-text parse-index))
                                vec)
          outcome-side-aliases (normalize-outcome-side-aliases-display (:outcome-side-aliases market)
                                                                       normalize-display-text
                                                                       parse-index)
          outcome-subscription-coins (normalize-display-text-list (:outcome-subscription-coins market)
                                                                  normalize-display-text)
          selected-outcome-id (parse-index (:selected-outcome-id market))
          selected-outcome-option-label (normalize-display-text (:selected-outcome-option-label market))
          outcome-option-id (parse-index (:outcome-option-id market))
          outcome-summary (normalize-display-text (:outcome-summary market))
          outcome-details (normalize-display-text (:outcome-details market))
          expiry-ms (parse-index (:expiry-ms market))
          target-price (normalize-display-text (:target-price market))
          period (normalize-display-text (:period market))
          outcome-sides (->> (:outcome-sides market)
                             (keep #(normalize-outcome-side-display % normalize-display-text parse-index))
                             vec)]
      (when (seq coin)
        (cond-> {:coin coin}
          (seq key) (assoc :key key)
          (seq symbol) (assoc :symbol symbol)
          (seq display-name) (assoc :display-name display-name)
          (seq display-symbol) (assoc :display-symbol display-symbol)
          (seq base) (assoc :base base)
          (seq quote) (assoc :quote quote)
          (seq dex) (assoc :dex dex)
          market-type (assoc :market-type market-type)
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
          (seq outcome-sides) (assoc :outcome-sides outcome-sides))))))

(defn persist-active-market-display!
  [market normalize-deps]
  (when-let [normalized (normalize-active-market-display market normalize-deps)]
    (try
      (platform/local-storage-set! active-market-display-local-storage-key
                                   (js/JSON.stringify (clj->js normalized)))
      (catch :default e
        (js/console.warn "Failed to persist active market display metadata:" e)))))

(defn load-active-market-display
  [active-asset normalize-deps]
  (when (seq active-asset)
    (try
      (let [raw (platform/local-storage-get active-market-display-local-storage-key)]
        (when (seq raw)
          (let [parsed (-> raw
                           js/JSON.parse
                           (js->clj :keywordize-keys true)
                           (normalize-active-market-display normalize-deps))]
            (when (= active-asset (:coin parsed))
              parsed))))
      (catch :default _
        nil))))
