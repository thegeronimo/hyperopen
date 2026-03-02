(ns hyperopen.utils.parse
  (:require [clojure.string :as str]
            [hyperopen.i18n.locale :as i18n-locale]))

(defn parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- escape-regexp-literal
  [value]
  (str/replace (str value)
               #"[.*+?^${}()|\[\]\\]"
               "\\\\$&"))

(def ^:private locale-decimal-symbols*
  (memoize
   (fn [locale]
     (let [locale* (i18n-locale/coalesce-locale locale)
           formatter (js/Intl.NumberFormat. locale* #js {:useGrouping true})
           parts (js->clj (.formatToParts formatter -12345.6)
                          :keywordize-keys true)
           decimal-symbol (or (some (fn [{:keys [type value]}]
                                      (when (= type "decimal")
                                        value))
                                    parts)
                              ".")
           group-symbol (or (some (fn [{:keys [type value]}]
                                    (when (= type "group")
                                      value))
                                  parts)
                            ",")
           minus-symbol (or (some (fn [{:keys [type value]}]
                                    (when (= type "minusSign")
                                      value))
                                  parts)
                            "-")
           plus-symbol (or (some (fn [{:keys [type value]}]
                                   (when (= type "plusSign")
                                     value))
                                 parts)
                           "+")]
       {:decimal-symbol decimal-symbol
        :group-symbol group-symbol
        :minus-symbol minus-symbol
        :plus-symbol plus-symbol}))))

(defn- valid-grouping?
  [text group-symbol decimal-symbol]
  (if (and (seq group-symbol)
           (not= group-symbol decimal-symbol))
    (let [decimal-index (if (seq decimal-symbol)
                          (.indexOf text decimal-symbol)
                          -1)
          integer-part (if (neg? decimal-index)
                         text
                         (.slice text 0 decimal-index))
          fraction-part (if (neg? decimal-index)
                          ""
                          (.slice text (+ decimal-index (count decimal-symbol))))
          signless-integer (str/replace integer-part #"^[+-]" "")]
      (cond
        (str/includes? fraction-part group-symbol)
        false

        (str/includes? signless-integer group-symbol)
        (let [segments (js->clj (.split signless-integer group-symbol))]
          (and (> (count segments) 1)
               (every? #(re-matches #"^[0-9]+$" %) segments)
               (<= 1 (count (first segments)) 3)
               (every? #(= 3 (count %)) (rest segments))))

        :else true))
    true))

(defn normalize-localized-decimal-input
  "Normalize localized decimal input to canonical form used by parseFloat.
   Example: \"1 234,56\" -> \"1234.56\" for fr-FR."
  ([value]
   (normalize-localized-decimal-input value nil))
  ([value locale]
   (let [text (some-> value str str/trim)]
     (when (seq text)
       (let [{:keys [decimal-symbol
                     group-symbol
                     minus-symbol
                     plus-symbol]} (locale-decimal-symbols* locale)
             without-group (if (seq group-symbol)
                             (str/replace text
                                          (re-pattern (escape-regexp-literal group-symbol))
                                          "")
                             text)
             without-space-group (if (re-find #"\s" group-symbol)
                                   (str/replace without-group #"\s+" "")
                                   without-group)
             with-ascii-signs (-> without-space-group
                                  (str/replace minus-symbol "-")
                                  (str/replace plus-symbol "+")
                                  (str/replace "−" "-"))
             canonical (if (and (seq decimal-symbol)
                                (not= decimal-symbol "."))
                         (str/replace with-ascii-signs
                                      (re-pattern (escape-regexp-literal decimal-symbol))
                                      ".")
                         with-ascii-signs)]
         (when (and (valid-grouping? text group-symbol decimal-symbol)
                    (re-matches #"^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$" canonical))
           canonical))))))

(defn parse-localized-decimal
  "Parse localized decimal input into a JS number."
  ([value]
   (parse-localized-decimal value nil))
  ([value locale]
   (when-let [normalized (normalize-localized-decimal-input value locale)]
     (let [num (js/parseFloat normalized)]
       (when-not (js/isNaN num)
         num)))))
