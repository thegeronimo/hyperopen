(ns hyperopen.schema.contracts.common
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]))

(defn validation-enabled?
  []
  ^boolean goog.DEBUG)

(defn non-empty-string?
  [value]
  (and (string? value)
       (seq (str/trim value))))

(defn non-negative-integer-value?
  [value]
  (and (integer? value)
       (>= value 0)))

(defn keyword-path?
  [path]
  (and (vector? path)
       (every? keyword? path)))

(defn parse-int-value
  [value]
  (cond
    (integer? value) value
    (and (number? value)
         (not (js/isNaN value))
         (= value (js/Math.floor value)))
    value
    (string? value)
    (let [text (str/trim value)]
      (when (re-matches #"[+-]?\\d+" text)
        (js/parseInt text 10)))
    :else nil))

(defn parse-number-value
  [value]
  (cond
    (number? value)
    (when-not (js/isNaN value)
      value)
    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        (let [parsed (js/Number text)]
          (when (and (number? parsed)
                     (not (js/isNaN parsed)))
            parsed))))
    :else nil))

(defn parseable-int?
  [value]
  (some? (parse-int-value value)))

(defn parseable-number?
  [value]
  (some? (parse-number-value value)))

(defn non-negative-int?
  [value]
  (when-let [parsed (parse-int-value value)]
    (>= parsed 0)))

(defn positive-int?
  [value]
  (when-let [parsed (parse-int-value value)]
    (> parsed 0)))

(defn fetch-candle-snapshot-args?
  [args]
  (and (vector? args)
       (even? (count args))
       (every? keyword? (take-nth 2 args))
       (let [opts (apply hash-map args)]
         (and (every? #{:coin :interval :bars :active?-fn :detail-route-vault-address} (keys opts))
              (or (not (contains? opts :coin))
                  (non-empty-string? (:coin opts)))
              (or (not (contains? opts :interval))
                  (keyword? (:interval opts)))
              (or (not (contains? opts :bars))
                  (positive-int? (:bars opts)))
              (or (not (contains? opts :detail-route-vault-address))
                  (and (string? (:detail-route-vault-address opts))
                       (re-matches #"(?i)^0x[0-9a-f]{40}$"
                                   (str/trim (:detail-route-vault-address opts)))))
              (or (not (contains? opts :active?-fn))
                  (fn? (:active?-fn opts)))))))

(s/def ::any-args vector?)
(s/def ::non-empty-string non-empty-string?)
(s/def ::state-path keyword-path?)
(s/def ::path-value (s/tuple ::state-path any?))
(s/def ::path-values (s/and vector?
                            (s/coll-of ::path-value :kind vector?)))
(s/def ::intish parseable-int?)
(s/def ::numberish parseable-number?)
(s/def ::non-negative-int non-negative-int?)
(s/def ::positive-int positive-int?)

(s/def ::market-key ::non-empty-string)
(s/def ::icon-status #{:loaded :missing})
(s/def ::asset-icon-status (s/keys :req-un [::market-key ::icon-status]))

(s/def ::action map?)
(s/def ::nonce (s/and integer? #(>= % 0)))
(s/def ::r ::non-empty-string)
(s/def ::s ::non-empty-string)
(s/def ::v integer?)
(s/def ::signature (s/keys :req-un [::r ::s ::v]))
(s/def ::signed-exchange-payload
  (s/keys :req-un [::action ::nonce ::signature]))

(s/def ::exchange-response map?)

(s/def ::channel ::non-empty-string)
(s/def ::provider-message
  (s/keys :req-un [::channel]))

(s/def ::save-args (s/tuple ::state-path any?))
(s/def ::save-many-args (s/tuple ::path-values))
(s/def ::storage-args (s/tuple ::non-empty-string any?))
(s/def ::queue-asset-icon-status-args (s/tuple ::asset-icon-status))
(s/def ::path-args (s/tuple ::non-empty-string))
(s/def ::path-and-address-args (s/tuple ::non-empty-string ::non-empty-string))
(s/def ::coin-args (s/tuple ::non-empty-string))
(s/def ::address-args (s/tuple ::non-empty-string))
(s/def ::optional-address-args (s/tuple (s/nilable ::non-empty-string)))
(s/def ::address-and-optional-address-args
  (s/tuple ::non-empty-string (s/nilable ::non-empty-string)))
(s/def ::set-agent-storage-mode-args (s/tuple keyword?))
(s/def ::set-agent-local-protection-mode-args (s/tuple keyword?))

(s/def ::group #{:market_data :orders_oms :all})
(s/def ::source keyword?)
(s/def ::ws-reset-request
  (s/keys :opt-un [::group ::source]))
(s/def ::ws-reset-subscriptions-args (s/tuple ::ws-reset-request))
(s/def ::no-args empty?)

(s/def ::keyword-or-string (s/or :keyword keyword?
                                 :string ::non-empty-string))
(s/def ::tab ::keyword-or-string)
(s/def ::market-or-coin (s/or :coin ::non-empty-string
                              :market map?))
(s/def ::dropdown-target (s/or :keyword keyword?
                               :coin ::non-empty-string))
(s/def ::dropdown-target-args (s/tuple ::dropdown-target))
(s/def ::booleanish #(or (nil? %) (boolean? %)))
(s/def ::boolean-args (s/tuple boolean?))
(s/def ::keyword-args (s/tuple keyword?))
(s/def ::keyword-or-string-args (s/tuple ::keyword-or-string))
(s/def ::optional-string-args (s/or :none ::no-args
                                    :value (s/tuple (s/nilable string?))))
(s/def ::tab-and-input-args (s/tuple ::keyword-or-string any?))
(s/def ::single-input-args (s/tuple any?))
(s/def ::single-or-double-input-args (s/or :single (s/tuple any?)
                                           :double (s/tuple any? any?)))
(s/def ::coin-number-input-args
  (s/tuple ::non-empty-string
           (s/nilable ::numberish)
           any?))
(s/def ::tooltip-toggle-args
  (s/tuple ::non-empty-string ::booleanish))
(s/def ::tab-args (s/tuple ::tab))
(s/def ::asset-selector-shortcut-market-keys
  (s/and vector?
         (s/coll-of ::market-key :kind vector?)))
(s/def ::asset-selector-shortcut-args
  (s/tuple (s/nilable string?)
           ::booleanish
           ::booleanish
           (s/nilable ::asset-selector-shortcut-market-keys)))
(s/def ::market-or-coin-args (s/tuple ::market-or-coin))
(s/def ::market-key-args (s/tuple ::market-key))
(s/def ::max-page-args (s/tuple (s/nilable ::intish)))
(s/def ::page-and-max-page-args (s/tuple ::intish (s/nilable ::intish)))
(s/def ::address-and-mode-args (s/tuple ::non-empty-string ::keyword-or-string))
(s/def ::storage-mode-request-input #(or (keyword? %)
                                         (string? %)
                                         (boolean? %)))
(s/def ::storage-mode-request-args (s/tuple ::storage-mode-request-input))
(s/def ::local-protection-mode-request-input #(or (keyword? %)
                                                  (string? %)
                                                  (boolean? %)))
(s/def ::local-protection-mode-request-args (s/tuple ::local-protection-mode-request-input))
(s/def ::sort-column-args (s/tuple ::non-empty-string))
(s/def ::vault-detail-activity-sort-args (s/tuple ::keyword-or-string ::keyword-or-string))
(s/def ::key-args (s/tuple ::non-empty-string))
(s/def ::keydown-with-max-page-args (s/tuple ::non-empty-string (s/nilable ::intish)))
(s/def ::keydown-with-optional-coin-args
  (s/tuple ::non-empty-string (s/nilable ::non-empty-string)))
(s/def ::map-vector (s/and vector?
                           (s/coll-of map? :kind vector?)))
(s/def ::non-empty-map-vector (s/and ::map-vector seq))

(s/def ::left number?)
(s/def ::right number?)
(s/def ::top number?)
(s/def ::bottom number?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::viewport-width number?)
(s/def ::viewport-height number?)
(s/def ::position-tpsl-anchor
  (s/keys :opt-un [::left
                   ::right
                   ::top
                   ::bottom
                   ::width
                   ::height
                   ::viewport-width
                   ::viewport-height]))

(s/def ::fetch-candle-snapshot-args fetch-candle-snapshot-args?)
(s/def ::ws-reset-source-args (s/or :none ::no-args
                                    :source (s/tuple ::source)))
