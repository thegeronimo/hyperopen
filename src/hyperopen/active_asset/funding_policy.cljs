(ns hyperopen.active-asset.funding-policy
  (:require [clojure.string :as str]
            [hyperopen.active-asset.funding-insights :as insights]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private default-hypothetical-position-value
  1000)

(defn- non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn funding-tooltip-pin-id
  [coin]
  (str "funding-rate-tooltip-pin-"
       (-> (or coin "asset")
           str
           str/lower-case
           (str/replace #"[^a-z0-9_-]" "-"))))

(defn- value-signature
  [value]
  {:hash (hash value)
   :count (when (counted? value)
            (count value))})

(defn parse-optional-number [value]
  (let [num (cond
              (number? value) value
              (string? value) (parse-utils/parse-localized-currency-decimal value)
              :else js/NaN)]
    (when (and (number? num) (not (js/isNaN num)))
      num)))

(defn- parse-decimal-input
  ([value]
   (parse-decimal-input value nil))
  ([value locale]
   (parse-utils/parse-localized-currency-decimal value locale)))

(defn- base-symbol-segment [value]
  (let [text (some-> value non-blank-text (str/replace #"^.*:" ""))]
    (some-> text
            (str/split #"/|-" 2)
            first
            non-blank-text)))

(defn direction-from-size [size]
  (cond
    (and (number? size) (pos? size)) :long
    (and (number? size) (neg? size)) :short
    :else :flat))

(defn- direction-label [direction]
  (case direction
    :long "Long"
    :short "Short"
    "Flat"))

(defn- signed-position-value
  [direction position-value]
  (when (number? position-value)
    (* (js/Math.abs position-value)
       (case direction
         :short -1
         :long 1
         0))))

(defn- unsigned-size-text [raw-size parsed-size]
  (let [size-text (non-blank-text raw-size)]
    (cond
      (and size-text
           (or (str/starts-with? size-text "-")
               (str/starts-with? size-text "+")))
      (subs size-text 1)

      size-text
      size-text

      (number? parsed-size)
      (fmt/safe-to-fixed (js/Math.abs parsed-size) 4)

      :else
      "0")))

(defn- live-size-input-text
  [raw-size parsed-size]
  (or (non-blank-text raw-size)
      (when (number? parsed-size)
        (fmt/safe-to-fixed parsed-size 4))
      ""))

(defn- live-value-input-text
  [direction position-value]
  (or (some-> (signed-position-value direction position-value)
              (fmt/safe-to-fixed 2))
      ""))

(defn normalized-position-value [position mark]
  (let [value (parse-optional-number (:positionValue position))
        size (parse-optional-number (:szi position))]
    (cond
      (number? value)
      (js/Math.abs value)

      (and (number? size)
           (number? mark))
      (js/Math.abs (* size mark))

      :else
      nil)))

(defn- default-hypothetical-size [mark]
  (let [mark* (parse-optional-number mark)]
    (when (and (number? mark*)
               (pos? mark*))
      (/ default-hypothetical-position-value mark*))))

(defn- hypothetical-position-model
  [coin mark hypothetical-input locale]
  (let [mark* (parse-optional-number mark)
        stored (if (map? hypothetical-input)
                 hypothetical-input
                 {})
        use-defaults? (and (not (contains? stored :size-input))
                           (not (contains? stored :value-input)))
        default-size (default-hypothetical-size mark*)
        size-input (if (contains? stored :size-input)
                     (str (or (:size-input stored) ""))
                     (if (number? default-size)
                       (fmt/safe-to-fixed default-size 4)
                       ""))
        value-input (if (contains? stored :value-input)
                      (str (or (:value-input stored) ""))
                      (fmt/safe-to-fixed default-hypothetical-position-value 2))
        size* (parse-decimal-input size-input locale)
        value-raw* (parse-decimal-input value-input locale)
        value-sign (cond
                     (and (number? value-raw*) (neg? value-raw*)) -1
                     (and (number? value-raw*) (pos? value-raw*)) 1
                     :else nil)
        value* (when (number? value-raw*)
                 (js/Math.abs value-raw*))
        resolved-size (cond
                        (number? size*) size*
                        (and (number? value*)
                             (number? mark*)
                             (pos? mark*))
                        (* (or value-sign 1)
                           (/ value* mark*))
                        (and use-defaults?
                             (number? default-size))
                        default-size
                        :else nil)
        resolved-value (cond
                         (number? value*) value*
                         (and (number? resolved-size)
                              (number? mark*)
                              (pos? mark*))
                         (* (js/Math.abs resolved-size) mark*)
                         :else nil)]
    {:coin coin
     :size-input size-input
     :value-input value-input
     :size resolved-size
     :position-value resolved-value
     :direction (direction-from-size resolved-size)}))

(defn- display-base-symbol [market coin]
  (or (non-blank-text (:base market))
      (base-symbol-segment (:symbol market))
      (base-symbol-segment coin)
      "ASSET"))

(defn funding-payment-estimate [direction position-value rate]
  (when (and (number? position-value)
             (number? rate)
             (not= direction :flat))
    (* position-value
       (/ rate 100)
       (case direction
         :long -1
         :short 1
         0))))

(defn- unsigned-input-text
  [text]
  (let [text* (str (or text ""))]
    (if (or (str/starts-with? text* "-")
            (str/starts-with? text* "−")
            (str/starts-with? text* "+"))
      (subs text* 1)
      text*)))

(defn- projection-row
  [id label basis rate direction position-value margin]
  (let [payment (funding-payment-estimate direction position-value rate)]
    {:id id
     :label label
     :basis basis
     :rate rate
     :payment payment
     :margin-pct (insights/share-of-margin-pct payment margin)}))

(defn- average-hourly-rate
  "The 30-day average rate in the context's units (percent per hour). The
  predictability summary's :mean is a daily decimal rate."
  [summary]
  (when (number? (:mean summary))
    (/ (* (:mean summary) 100) 24)))

(defn funding-tooltip-model
  ([position market coin mark funding-rate predictability-state hypothetical-input locale]
   (funding-tooltip-model position market coin mark funding-rate predictability-state hypothetical-input locale nil))
  ([position market coin mark funding-rate predictability-state hypothetical-input locale {:keys [order-leverage]}]
  (let [size-raw (:szi position)
        size (parse-optional-number size-raw)
        direction (direction-from-size size)
        position-value (normalized-position-value position mark)
        has-live-position? (and (number? size)
                                (not= direction :flat))
        hypothetical-active? (or (not has-live-position?)
                                 (map? hypothetical-input))
        hypothetical-model (when hypothetical-active?
                             (hypothetical-position-model coin mark hypothetical-input locale))
        effective-direction (if hypothetical-active?
                              (:direction hypothetical-model)
                              direction)
        effective-position-value (if hypothetical-active?
                                   (:position-value hypothetical-model)
                                   position-value)
        base-symbol (display-base-symbol market coin)
        live-seed-entry (when has-live-position?
                          {:size-input (live-size-input-text size-raw size)
                           :value-input (live-value-input-text direction position-value)})
        next-24h-rate (when (number? funding-rate)
                        (* funding-rate 24))
        next-30d-rate (when (number? funding-rate)
                        (* funding-rate 24 30))
        annual-rate (fmt/annualized-funding-rate funding-rate)
        margin-info (insights/margin-model {:live? (not hypothetical-active?)
                                            :position position
                                            :position-value effective-position-value
                                            :order-leverage order-leverage})
        predictability-summary (:summary predictability-state)
        predictability-loading? (true? (:loading? predictability-state))
        predictability-error (non-blank-text (:error predictability-state))
        average-rate (when (map? predictability-summary)
                       (average-hourly-rate predictability-summary))]
    {:position-mode (if hypothetical-active? :hypothetical :live)
     :position-title (if hypothetical-active?
                       "If you hold"
                       "Your position")
     :position-action-label (cond
                              (and hypothetical-active? has-live-position?) "Use live"
                              has-live-position? "Edit estimate"
                              :else nil)
     :position-action-coin coin
     :position-action-mark mark
     :position-action-entry live-seed-entry
     :position-pin-id (funding-tooltip-pin-id coin)
     :hypothetical-helper-text (if has-live-position?
                                 "Edit size or value to estimate payments. Use negative size or value for short."
                                 "Enter size or value to estimate payments. Use negative size or value for short.")
     :position-size-label (if has-live-position?
                            (str (direction-label direction)
                                 " "
                                 (unsigned-size-text size-raw size)
                                 " "
                                 base-symbol)
                            "No open position")
     :position-value (if hypothetical-active?
                       effective-position-value
                       position-value)
     :position-base-symbol base-symbol
     :hypothetical-size-input (:size-input hypothetical-model)
     :hypothetical-value-input (:value-input hypothetical-model)
     :hypothetical-coin (:coin hypothetical-model)
     :hypothetical-mark mark
     :hypothetical-side (if (= :short effective-direction) :short :long)
     :hypothetical-size-display (unsigned-input-text (:size-input hypothetical-model))
     :hypothetical-value-display (unsigned-input-text (:value-input hypothetical-model))
     :hourly-rate funding-rate
     :direction effective-direction
     :verdict (insights/payer-verdict effective-direction funding-rate)
     :leverage (:leverage margin-info)
     :margin (:margin margin-info)
     :margin-per-day-pct (insights/share-of-margin-pct
                          (funding-payment-estimate effective-direction
                                                    effective-position-value
                                                    next-24h-rate)
                          (:margin margin-info))
     :since-open-funding (when-not hypothetical-active?
                           (insights/since-open-funding position))
     :breakeven (insights/breakeven effective-direction funding-rate mark)
     :expected-breakeven (when (number? average-rate)
                           (insights/breakeven effective-direction average-rate mark))
     :rate-breakdown (insights/rate-breakdown coin funding-rate)
     :projection-rows (mapv (fn [[id label rate]]
                              (projection-row id label :today rate effective-direction
                                              effective-position-value (:margin margin-info)))
                            [["next-24h" "Next 24 hours" next-24h-rate]
                             ["next-30d" "Next 30 days" next-30d-rate]
                             ["apy" "Next year" annual-rate]])
     :expected-projection-rows (when (number? average-rate)
                                 (mapv (fn [[id label basis rate]]
                                         (projection-row id label basis rate effective-direction
                                                         effective-position-value (:margin margin-info)))
                                       [["next-24h" "Next 24 hours" :today next-24h-rate]
                                        ["next-30d" "Next 30 days" :average (* average-rate 24 30)]
                                        ["apy" "Next year" :average (fmt/annualized-funding-rate average-rate)]]))
     :history-chart (when (map? predictability-summary)
                      (insights/history-chart-model (:daily-funding-series predictability-summary)))
     :predictability-loading? predictability-loading?
     :predictability-error predictability-error})))

(defonce ^:private funding-tooltip-model-cache
  (atom nil))

(defn- funding-tooltip-cache-key
  [position market coin mark funding-rate predictability-state hypothetical-input locale extras]
  {:position {:szi (:szi position)
              :position-value (:positionValue position)
              :leverage (get-in position [:leverage :value])
              :margin-used (:marginUsed position)
              :cum-funding (:cumFunding position)}
   :extras extras
   :market-base (non-blank-text (:base market))
   :market-symbol (non-blank-text (:symbol market))
   :coin (non-blank-text coin)
   :mark mark
   :funding-rate funding-rate
   :predictability-loading? (true? (:loading? predictability-state))
   :predictability-error (non-blank-text (:error predictability-state))
   :predictability-summary-signature (value-signature (:summary predictability-state))
   :hypothetical-input hypothetical-input
   :locale locale})

(defn memoized-funding-tooltip-model
  ([position market coin mark funding-rate predictability-state hypothetical-input locale]
   (memoized-funding-tooltip-model position market coin mark funding-rate predictability-state hypothetical-input locale nil))
  ([position market coin mark funding-rate predictability-state hypothetical-input locale extras]
   (let [cache-key (funding-tooltip-cache-key position
                                              market
                                              coin
                                              mark
                                              funding-rate
                                              predictability-state
                                              hypothetical-input
                                              locale
                                              extras)
         cached @funding-tooltip-model-cache]
     (if (and (map? cached)
              (= cache-key (:key cached)))
       (:result cached)
       (let [result (funding-tooltip-model position
                                           market
                                           coin
                                           mark
                                           funding-rate
                                           predictability-state
                                           hypothetical-input
                                           locale
                                           extras)]
         (reset! funding-tooltip-model-cache {:key cache-key
                                              :result result})
         result)))))
