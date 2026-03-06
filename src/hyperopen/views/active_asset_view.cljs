(ns hyperopen.views.active-asset-view
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.asset-selector-view :as asset-selector]
            [hyperopen.views.autocorrelation-plot :as autocorrelation-plot]
            [hyperopen.views.funding-rate-plot :as funding-rate-plot]))

;; Pure presentation components

(defn get-available-assets [state]
  "Get list of available markets for the asset selector."
  (get-in state [:asset-selector :markets] []))



(defn tooltip
  ([content]
   (tooltip content "top" {}))
  ([content position]
   (tooltip content position {}))
  ([content position {:keys [click-pinnable? pin-id pinned?]}]
   (let [pos (or position "top")
         tooltip-body (second content)
         body-node (if (string? tooltip-body)
                     [:div {:class ["rounded-md"
                                    "bg-gray-800"
                                    "px-2"
                                    "py-1"
                                    "text-xs"
                                    "text-white"
                                    "whitespace-nowrap"]}
                      tooltip-body]
                     tooltip-body)
         placement-classes (case pos
                             "top" ["bottom-full" "left-1/2" "transform" "-translate-x-1/2" "mb-2"]
                             "bottom" ["top-full" "left-1/2" "transform" "-translate-x-1/2" "mt-2"]
                             "left" ["right-full" "top-1/2" "transform" "-translate-y-1/2" "mr-2"]
                             "right" ["left-full" "top-1/2" "transform" "-translate-y-1/2" "ml-2"])
         trigger-node (first content)
         pin-id* (let [text (some-> pin-id str str/trim)]
                   (if (seq text)
                     text
                     "funding-rate-tooltip-pin"))]
     (if click-pinnable?
       [:div {:class ["relative" "group" "inline-flex"]
              :on {:mouseenter [[:actions/set-funding-tooltip-visible pin-id* true]]
                   :mouseleave [[:actions/set-funding-tooltip-visible pin-id* false]]}}
        [:input {:id pin-id*
                 :type "checkbox"
                 :checked (boolean pinned?)
                 :class ["peer" "sr-only"]
                 :on {:change [[:actions/set-funding-tooltip-pinned pin-id* :event.target/checked]]
                      :focus [[:actions/set-funding-tooltip-visible pin-id* true]]
                      :blur [[:actions/set-funding-tooltip-visible pin-id* false]]}}]
        ;; Click outside target that only exists while pinned open.
        [:label {:for pin-id*
                 :class ["fixed"
                         "inset-0"
                         "z-40"
                         "hidden"
                         "cursor-default"
                         "peer-checked:block"]
                 :on {:click [[:actions/set-funding-tooltip-visible pin-id* false]]}}]
        [:label {:for pin-id*
                 :class ["relative" "z-50" "inline-flex" "cursor-pointer"]}
         trigger-node]
        [:div {:class (into ["absolute"
                             "z-50"
                             "opacity-0"
                             "group-hover:opacity-100"
                             "peer-checked:opacity-100"
                             "transition-opacity"
                             "duration-200"
                             "pointer-events-none"
                             "peer-checked:pointer-events-auto"]
                            placement-classes)
               :style {:min-width "max-content"
                       :max-width "22rem"}}
         body-node]]
       [:div {:class ["relative" "group" "inline-flex"]}
        [:div trigger-node]
        [:div {:class (into ["absolute" "opacity-0" "group-hover:opacity-100" "transition-opacity" "duration-200" "pointer-events-none" "z-50"]
                            placement-classes)
               :style {:min-width "max-content"
                       :max-width "22rem"}}
         body-node]]))))

(defn change-indicator [change-value change-pct & [change-raw]]
  (let [is-positive (and change-value (>= change-value 0))
        color-class (if is-positive "text-success" "text-error")]
    [:span {:class [color-class "num"]}
     (str (or (fmt/format-trade-price-delta change-value change-raw) "--")
          " / "
          (or (fmt/format-percentage change-pct) "--"))]))

(def active-asset-grid-template
  "md:grid-cols-[minmax(max-content,1.4fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,1.1fr)_minmax(0,1.1fr)_minmax(0,1.2fr)_minmax(0,1.6fr)]")

(def ^:private active-asset-chip-classes
  ["px-1.5"
   "py-0.5"
   "text-xs"
   "font-medium"
   "rounded"
   "border"
   "bg-emerald-500/20"
   "text-emerald-300"
   "border-emerald-500/30"])

(defn- non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- normalize-coin-key [coin]
  (some-> coin non-blank-text str/upper-case))

(defn- read-by-coin
  [by-coin coin]
  (let [normalized-coin (normalize-coin-key coin)]
    (or (and (seq normalized-coin)
             (get by-coin normalized-coin))
        (get by-coin coin))))

(defn- funding-tooltip-pin-id
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

(defn- parse-optional-number [value]
  (let [num (cond
              (number? value) value
              (string? value) (parse-utils/parse-localized-currency-decimal value)
              :else js/NaN)]
    (when (and (number? num) (not (js/isNaN num)))
      num)))

(def ^:private default-hypothetical-position-value
  1000)

(defn- parse-decimal-input
  ([value]
   (parse-decimal-input value nil))
  ([value locale]
   (parse-utils/parse-localized-currency-decimal value locale)))

(defn- coin-prefix [coin]
  (let [coin* (non-blank-text coin)]
    (when (and coin* (str/includes? coin* ":"))
      (let [[prefix _suffix] (str/split coin* #":" 2)]
        (non-blank-text prefix)))))

(defn- market-dex-label [market]
  (or (non-blank-text (:dex market))
      (coin-prefix (:coin market))))

(defn- leverage-chip-label [market]
  (when-let [max-leverage (parse-optional-number (:maxLeverage market))]
    (when (pos? max-leverage)
      (let [whole-number? (= max-leverage (js/Math.floor max-leverage))
            leverage-text (if whole-number?
                            (str (js/Math.floor max-leverage))
                            (fmt/safe-to-fixed max-leverage 1))]
        (str leverage-text "x")))))

(defn- base-symbol-segment [value]
  (let [text (some-> value non-blank-text (str/replace #"^.*:" ""))]
    (some-> text
            (str/split #"/|-" 2)
            first
            non-blank-text)))

(defn- direction-from-size [size]
  (cond
    (and (number? size) (pos? size)) :long
    (and (number? size) (neg? size)) :short
    :else :flat))

(defn- direction-label [direction]
  (case direction
    :long "Long"
    :short "Short"
    "Flat"))

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

(defn- normalized-position-value [position mark]
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

(defn- signed-percentage-text [value decimals]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 1e-8) 0 value)
          sign (cond
                 (pos? normalized) "+"
                 (neg? normalized) "-"
                 :else "")]
      (str sign (fmt/format-percentage (js/Math.abs normalized) decimals)))
    "—"))

(defn- unsigned-percentage-text [value decimals]
  (if (number? value)
    (fmt/format-percentage (js/Math.abs value) decimals)
    "—"))

(defn- signed-decimal-text [value decimals]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 1e-10) 0 value)
          sign (cond
                 (pos? normalized) "+"
                 (neg? normalized) "-"
                 :else "")]
      (str sign (fmt/safe-to-fixed (js/Math.abs normalized) decimals)))
    "—"))

(defn- signed-usd-text [value]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 0.005) 0 value)
          sign (cond
                 (pos? normalized) "+"
                 (neg? normalized) "-"
                 :else "")]
      (str sign "$" (fmt/format-fixed-number (js/Math.abs normalized) 2)))
    "—"))

(defn- signed-tone-class [value]
  (cond
    (and (number? value) (pos? value)) "text-success"
    (and (number? value) (neg? value)) "text-error"
    :else "text-gray-100"))

(defn- funding-payment-estimate [direction position-value rate]
  (when (and (number? position-value)
             (number? rate)
             (not= direction :flat))
    (* position-value
       (/ rate 100)
       (case direction
         :long -1
         :short 1
         0))))

(def ^:private annualization-days
  365)

(defn- daily-decimal->annualized-percent
  [daily-decimal]
  (when (number? daily-decimal)
    (* daily-decimal annualization-days 100)))

(defn- daily-decimal-stddev->annualized-percent
  [daily-decimal-stddev]
  (when (number? daily-decimal-stddev)
    (* daily-decimal-stddev
       100
       (js/Math.sqrt annualization-days))))

(defn- payment-range-text
  [lower-payment upper-payment]
  (if (and (number? lower-payment)
           (number? upper-payment))
    (str (signed-usd-text lower-payment)
         " to "
         (signed-usd-text upper-payment))
    "—"))

(defn- predictability-rows
  [summary direction position-value]
  (let [annualized-mean (daily-decimal->annualized-percent (:mean summary))
        annualized-volatility (daily-decimal-stddev->annualized-percent (:stddev summary))
        expected-payment (funding-payment-estimate direction position-value annualized-mean)
        lower-rate (when (and (number? annualized-mean)
                              (number? annualized-volatility))
                     (- annualized-mean annualized-volatility))
        upper-rate (when (and (number? annualized-mean)
                              (number? annualized-volatility))
                     (+ annualized-mean annualized-volatility))
        lower-payment (funding-payment-estimate direction position-value lower-rate)
        upper-payment (funding-payment-estimate direction position-value upper-rate)]
    [{:id "mean-rate"
      :label "Mean APY"
      :rate annualized-mean
      :rate-kind :signed-percentage
      :payment expected-payment
      :payment-kind :signed-usd}
     {:id "volatility"
      :label "Volatility"
      :rate annualized-volatility
      :rate-kind :unsigned-percentage
      :payment {:lower lower-payment
                :upper upper-payment}
      :payment-kind :usd-range}]))

(defn- predictability-lag-note
  [summary]
  (let [lag-order [:lag-1d :lag-5d :lag-15d]
        first-insufficient (some (fn [lag]
                                   (let [lag-stat (get-in summary [:autocorrelation lag])]
                                     (when (:insufficient? lag-stat)
                                       lag-stat)))
                                 lag-order)]
    (when first-insufficient
      (str "Lag "
           (:lag-days first-insufficient)
           "d needs at least "
           (:minimum-daily-count first-insufficient)
           " daily points"))))

(defn- funding-tooltip-model
  [position market coin mark funding-rate predictability-state hypothetical-input locale]
  (let [size-raw (:szi position)
        size (parse-optional-number size-raw)
        direction (direction-from-size size)
        position-value (normalized-position-value position mark)
        has-live-position? (and (number? size)
                                (not= direction :flat))
        hypothetical-model (when-not has-live-position?
                             (hypothetical-position-model coin mark hypothetical-input locale))
        effective-direction (if has-live-position?
                              direction
                              (:direction hypothetical-model))
        effective-position-value (if has-live-position?
                                   position-value
                                   (:position-value hypothetical-model))
        base-symbol (display-base-symbol market coin)
        next-24h-rate (when (number? funding-rate)
                        (* funding-rate 24))
        annual-rate (fmt/annualized-funding-rate funding-rate)
        predictability-summary (:summary predictability-state)
        predictability-loading? (true? (:loading? predictability-state))
        predictability-error (non-blank-text (:error predictability-state))]
    {:position-mode (if has-live-position? :live :hypothetical)
     :position-size-label (if has-live-position?
                            (str (direction-label direction)
                                 " "
                                 (unsigned-size-text size-raw size)
                                 " "
                                 base-symbol)
                            "No open position")
     :position-value effective-position-value
     :position-base-symbol base-symbol
     :hypothetical-size-input (:size-input hypothetical-model)
     :hypothetical-value-input (:value-input hypothetical-model)
     :hypothetical-coin (:coin hypothetical-model)
     :hypothetical-mark mark
     :projection-rows [{:id "next-24h"
                        :label "Next 24h"
                        :rate next-24h-rate
                        :payment (funding-payment-estimate effective-direction
                                                          effective-position-value
                                                          next-24h-rate)}
                       {:id "apy"
                        :label "APY"
                        :rate annual-rate
                        :payment (funding-payment-estimate effective-direction
                                                          effective-position-value
                                                          annual-rate)}]
     :predictability-loading? predictability-loading?
     :predictability-error predictability-error
     :predictability-rows (when (map? predictability-summary)
                            (predictability-rows predictability-summary
                                                 effective-direction
                                                 effective-position-value))
     :predictability-daily-rate-series (when (map? predictability-summary)
                                         (vec (or (:daily-funding-series predictability-summary)
                                                  [])))
     :predictability-autocorrelation-series (when (map? predictability-summary)
                                              (vec (or (:autocorrelation-series predictability-summary)
                                                       [])))
     :predictability-lag-note (when (map? predictability-summary)
                                (predictability-lag-note predictability-summary))}))

(defonce ^:private funding-tooltip-model-cache
  (atom nil))

(defn- funding-tooltip-cache-key
  [position market coin mark funding-rate predictability-state hypothetical-input locale]
  {:position {:szi (:szi position)
              :position-value (:positionValue position)}
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

(defn- memoized-funding-tooltip-model
  [position market coin mark funding-rate predictability-state hypothetical-input locale]
  (let [cache-key (funding-tooltip-cache-key position
                                             market
                                             coin
                                             mark
                                             funding-rate
                                             predictability-state
                                             hypothetical-input
                                             locale)
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
                                          locale)]
        (reset! funding-tooltip-model-cache {:key cache-key
                                             :result result})
        result))))

(defn- predictability-rate-text [{:keys [rate-kind rate]}]
  (case rate-kind
    :signed-percentage (signed-percentage-text rate 4)
    :unsigned-percentage (unsigned-percentage-text rate 4)
    "—"))

(defn- predictability-rate-class [{:keys [rate-kind rate]}]
  (if (= rate-kind :signed-percentage)
    (signed-tone-class rate)
    "text-gray-100"))

(defn- predictability-payment-text
  [{:keys [payment-kind payment]}]
  (case payment-kind
    :signed-usd
    (signed-usd-text payment)

    :usd-range
    (payment-range-text (:lower payment) (:upper payment))

    "—"))

(defn- predictability-payment-class
  [{:keys [payment-kind payment]}]
  (case payment-kind
    :signed-usd (signed-tone-class payment)
    :usd-range "text-gray-100"
    "text-gray-100"))

(defn- predictability-payment-cell-classes
  [{:keys [payment-kind]}]
  (if (= payment-kind :usd-range)
    ["text-left"
     "min-w-0"
     "break-words"
     "text-[0.78rem]"
     "leading-[1.05rem]"
     "font-medium"]
    ["text-left"
     "font-medium"
     "whitespace-nowrap"]))

(defn- funding-tooltip-panel
  [{:keys [position-mode
           position-size-label
           position-value
           position-base-symbol
           hypothetical-size-input
           hypothetical-value-input
           hypothetical-coin
           hypothetical-mark
           projection-rows
           predictability-loading?
           predictability-error
           predictability-rows
           predictability-daily-rate-series
           predictability-autocorrelation-series
           predictability-lag-note]}]
  [:div {:class ["w-[18rem]"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "px-3.5"
                 "py-3"
                 "text-xs"
                 "text-left"
                 "text-gray-100"
                 "spectate-xl"
                 "backdrop-blur-sm"]}
   [:div {:class ["mb-3"]}
    [:h4 {:class ["mb-1.5"
                  "text-[0.9rem]"
                  "font-semibold"
                  "leading-5"
                  "text-gray-100"]}
     (if (= position-mode :hypothetical)
       "Hypothetical Position"
       "Position")]
    (if (= position-mode :hypothetical)
      [:div
       [:div {:class ["grid"
                      "grid-cols-[minmax(3.75rem,auto)_minmax(0,1fr)]"
                      "gap-x-3.5"
                      "gap-y-1"
                      "text-[0.86rem]"
                      "leading-[1.2rem]"]}
        [:span {:class ["text-gray-300/95" "text-left"]} "Size"]
        [:div {:class ["relative"
                       "min-w-0"
                       "rounded-md"
                       "border"
                       "border-slate-600/70"
                       "bg-slate-900/45"
                       "focus-within:border-emerald-400/60"]}
         [:input {:type "text"
                  :inputmode "decimal"
                  :spellCheck false
                  :aria-label "Hypothetical position size"
                  :class ["w-full"
                          "min-w-0"
                          "border-0"
                          "bg-transparent"
                          "pl-2"
                          "pr-10"
                          "py-1"
                          "text-[0.82rem]"
                          "leading-5"
                          "text-gray-100"
                          "placeholder:text-slate-400"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"
                          "num"]
                  :placeholder "0.0000"
                  :value (or hypothetical-size-input "")
                  :on {:input [[:actions/set-funding-hypothetical-size
                                hypothetical-coin
                                hypothetical-mark
                                [:event.target/value]]]}}]
         [:span {:class ["pointer-events-none"
                         "absolute"
                         "right-2"
                         "top-1/2"
                         "-translate-y-1/2"
                         "num"
                         "text-[0.72rem]"
                         "font-medium"
                         "uppercase"
                         "tracking-wide"
                         "text-gray-400"]}
          position-base-symbol]]
        [:span {:class ["text-gray-300/95" "text-left"]} "Value"]
        [:div {:class ["relative"
                       "min-w-0"
                       "rounded-md"
                       "border"
                       "border-slate-600/70"
                       "bg-slate-900/45"
                       "focus-within:border-emerald-400/60"]}
         [:span {:class ["pointer-events-none"
                         "absolute"
                         "left-2"
                         "top-1/2"
                         "-translate-y-1/2"
                         "num"
                         "font-medium"
                         "text-gray-300/95"]}
          "$"]
         [:input {:type "text"
                  :inputmode "decimal"
                  :spellCheck false
                  :aria-label "Hypothetical position value"
                  :class ["w-full"
                          "min-w-0"
                          "border-0"
                          "bg-transparent"
                          "pl-6"
                          "pr-2"
                          "py-1"
                          "text-[0.82rem]"
                          "leading-5"
                          "text-gray-100"
                          "placeholder:text-slate-400"
                          "focus:border-emerald-400/60"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"
                          "num"]
                  :placeholder "1000.00"
                  :value (or hypothetical-value-input "")
                  :on {:input [[:actions/set-funding-hypothetical-value
                                hypothetical-coin
                                hypothetical-mark
                                [:event.target/value]]]}}]]]
       [:p {:class ["mt-1.5"
                    "text-[0.72rem]"
                    "leading-[1.05rem]"
                    "text-gray-400"]}
        "Edit size or value to estimate payments. Use negative size or value for short."]]
      [:div {:class ["grid"
                     "grid-cols-[minmax(3.75rem,auto)_minmax(0,1fr)]"
                     "gap-x-3.5"
                     "gap-y-1"
                     "text-[0.86rem]"
                     "leading-[1.2rem]"]}
       [:span {:class ["text-gray-300/95" "text-left"]} "Size"]
       [:span {:class ["num" "text-left" "text-emerald-300" "whitespace-nowrap" "font-medium"]}
        position-size-label]
       [:span {:class ["text-gray-300/95" "text-left"]} "Value"]
       [:span {:class ["num" "text-left" "font-medium" "text-gray-100"]}
        (if (number? position-value)
          (str "$" (fmt/format-fixed-number position-value 2))
          "—")]])]
   [:div {:class ["mb-2.5"
                  "h-px"
                  "w-full"
                  "bg-slate-600/70"]}]
   [:div {:class ["mb-2.5"]}
    [:div {:class ["grid"
                   "grid-cols-[minmax(0,1fr)_8ch_8ch]"
                   "gap-x-2.5"
                   "gap-y-1"
                   "text-[0.86rem]"
                   "leading-[1.2rem]"]}
     [:span {:class ["text-[0.9rem]" "font-semibold" "leading-5" "text-gray-100"]} "Projections"]
     [:span {:class ["text-left"
                     "text-[0.75rem]"
                     "font-medium"
                     "uppercase"
                     "tracking-wide"
                     "leading-5"
                     "text-gray-400"]}
      "Rate"]
     [:span {:class ["text-left"
                     "text-[0.75rem]"
                     "font-medium"
                     "uppercase"
                     "tracking-wide"
                     "leading-5"
                     "text-gray-400"]}
      "Payment"]
     (for [{:keys [id label rate payment]} projection-rows]
       ^{:key id}
       [:div {:class ["contents"]}
        [:span {:class ["text-gray-100/95" "text-left"]} label]
        [:span {:class ["num" "text-left" "whitespace-nowrap" "font-medium" (signed-tone-class rate)]}
         (signed-percentage-text rate 4)]
        [:span {:class ["num" "text-left" "whitespace-nowrap" "font-medium" (signed-tone-class payment)]}
         (signed-usd-text payment)]])]]
   [:div {:class ["mb-2.5"
                  "h-px"
                  "w-full"
                  "bg-slate-600/70"]}]
   [:div
    [:h4 {:class ["mb-1.5"
                  "text-[0.9rem]"
                  "font-semibold"
                  "leading-5"
                  "text-gray-100"]}
     "Predictability (30d)"]
    (cond
      predictability-loading?
      [:div {:class ["text-[0.82rem]" "leading-[1.2rem]" "text-gray-300/90"]}
       "Loading 30d stats..."]

      (seq predictability-error)
      [:div {:class ["text-[0.82rem]" "leading-[1.2rem]" "text-red-300/90"]}
       "Unable to load 30d stats"]

      (seq predictability-rows)
      [:div {:class ["grid"
                     "grid-cols-[minmax(0,1fr)_8ch_minmax(0,1fr)]"
                     "gap-x-2.5"
                     "gap-y-1"
                     "text-[0.86rem]"
                     "leading-[1.2rem]"]}
       (for [{:keys [id label] :as row} predictability-rows]
         ^{:key id}
         [:div {:class ["contents"]}
          [:span {:class ["text-gray-100/95" "text-left"]} label]
          [:span {:class ["num"
                          "text-left"
                          "whitespace-nowrap"
                          "font-medium"
                          (predictability-rate-class row)]}
           (predictability-rate-text row)]
          [:span {:class (into ["num"
                                (predictability-payment-class row)]
                               (predictability-payment-cell-classes row))}
           (predictability-payment-text row)]])]

      :else
      [:div {:class ["num" "text-[0.86rem]" "leading-[1.2rem]" "text-gray-300/90"]}
       "—"])
    (when (and (not predictability-loading?)
               (not (seq predictability-error))
               (seq predictability-daily-rate-series))
      (funding-rate-plot/funding-rate-plot predictability-daily-rate-series))
    (when (and (not predictability-loading?)
               (not (seq predictability-error))
               (seq predictability-autocorrelation-series))
      (autocorrelation-plot/autocorrelation-plot predictability-autocorrelation-series))
    (when (seq predictability-lag-note)
      [:p {:class ["mt-1.5"
                   "text-[0.72rem]"
                   "leading-[1.05rem]"
                   "text-gray-400"]}
       predictability-lag-note])]])

(defn- symbol-monogram [market symbol coin]
  (let [base-symbol (or (non-blank-text (:base market))
                        (base-symbol-segment symbol)
                        (base-symbol-segment coin)
                        "ASSET")
        upper-symbol (str/upper-case base-symbol)]
    (subs upper-symbol 0 (min 5 (count upper-symbol)))))

(defn- resolve-active-market [full-state active-asset]
  (let [projected-market (:active-market full-state)
        market-by-key (get-in full-state [:asset-selector :market-by-key] {})]
    (cond
      (and (map? projected-market)
           (= (:coin projected-market) active-asset))
      projected-market

      (string? active-asset)
      (markets/resolve-market-by-coin market-by-key active-asset)

      :else
      nil)))

(defn asset-icon [market dropdown-visible? missing-icons loaded-icons]
  (let [coin (:coin market)
        symbol (or (:symbol market) coin)
        dex-label (market-dex-label market)
        leverage-label (leverage-chip-label market)
        market-type (:market-type market)
        market-key (or (:key market) (markets/coin->market-key coin))
        missing-icon? (contains? missing-icons market-key)
        icon-src (when-not missing-icon?
                   (asset-icon/market-icon-url market))
        show-icon? (seq icon-src)
        show-monogram? (not show-icon?)
        monogram (symbol-monogram market symbol coin)]
    [:div {:class ["flex" "items-center" "gap-2" "cursor-pointer" "hover:bg-base-300"
                   "rounded" "pr-2" "py-1" "transition-colors" "min-w-0"]
           :on {:click [[:actions/toggle-asset-dropdown :asset-selector]]}}
     [:div {:class ["w-5" "h-5" "shrink-0" "overflow-hidden" "rounded-full"]}
      (if show-icon?
        [:img {:class ["block" "w-5" "h-5" "object-contain" "bg-white" "pointer-events-none"]
               :src icon-src
               :alt ""
               :aria-hidden true
               :on {:load [[:actions/mark-loaded-asset-icon market-key]]
                    :error [[:actions/mark-missing-asset-icon market-key]]}}]
        [:div {:class ["w-5" "h-5" "rounded-full" "border" "border-slate-500/40"
                       "bg-slate-800/80" "text-slate-300/70" "text-[7px]"
                       "font-semibold" "tracking-tight" "uppercase" "leading-none"
                       "flex" "items-center" "justify-center" "px-0.5"]
               :aria-hidden true}
         monogram])]
     [:div.flex.items-center.space-x-2.min-w-0
      [:span.font-medium.truncate symbol]
      (when (= market-type :spot)
        [:span {:class active-asset-chip-classes}
         "SPOT"])
      (when dex-label
        [:span {:class active-asset-chip-classes}
         dex-label])
      (when leverage-label
        [:span {:class active-asset-chip-classes}
         leverage-label])]
     [:svg {:fill "none"
            :stroke "currentColor"
            :viewBox "0 0 24 24"
            :class (into ["w-4" "h-4" "text-gray-400" "transition-transform" "shrink-0"]
                         (when dropdown-visible? ["rotate-180"]))}
      [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]))

(defn asset-selector-trigger [dropdown-visible?]
  [:button {:class ["flex" "items-center" "space-x-2" "cursor-pointer" "hover:bg-base-300"
                    "rounded" "pr-2" "py-1" "transition-colors"]
            :type "button"
            :on {:click [[:actions/toggle-asset-dropdown :asset-selector]]
                 :keydown [[:actions/handle-asset-selector-shortcut
                            [:event/key]
                            [:event/metaKey]
                            [:event/ctrlKey]
                            []]]}}
   [:div.w-6.h-6.rounded-full.bg-base-300.flex.items-center.justify-center
    [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "m21 21-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}]]]
   [:span.font-medium "Select Asset"]
   [:svg.w-4.h-4.text-gray-400.transition-transform {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"
                                                      :class (when dropdown-visible? "rotate-180")}
    [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]])

(defn data-column [label value & [options]]
  (let [underlined? (:underlined options)
        value-component (if (:change? options)
                          (change-indicator (:change-value options)
                                            (:change-pct options)
                                            (:change-raw options))
                          [:span {:class (into ["font-medium"]
                                               (when (:numeric? options) ["num"]))}
                           value])]
    [:div.text-center
     [:div {:class (into ["text-xs" "text-gray-400" "mb-1"]
                         (when underlined? ["border-b" "border-dashed" "border-gray-600"]))}
      label]
     [:div {:class (into ["text-xs"]
                         (when (:numeric? options) ["num"]))}
      value-component]]))

(defn active-asset-row [ctx-data market dropdown-state full-state]
  (let [coin (or (:coin market) (:coin ctx-data))
        icon-market (-> (or market {})
                        (assoc :coin (or (:coin market) coin))
                        (assoc :symbol (or (:symbol market) coin)))
        mark (or (:mark ctx-data) (:mark market))
        mark-raw (or (:markRaw ctx-data) (:markRaw market))
        oracle (:oracle ctx-data)
        oracle-raw (:oracleRaw ctx-data)
        change-24h (or (:change24h ctx-data) (:change24h market))
        change-24h-pct (or (:change24hPct ctx-data) (:change24hPct market))
        volume-24h (or (:volume24h ctx-data) (:volume24h market))
        open-interest-raw (:openInterest ctx-data)
        open-interest-usd (if (= :spot (:market-type market))
                            nil
                            (or (when (and open-interest-raw mark)
                                  (fmt/calculate-open-interest-usd open-interest-raw mark))
                                (:openInterest market)))
        funding-rate (parse-optional-number (:fundingRate ctx-data))
        countdown-text (fmt/format-funding-countdown)
        active-position (trading-state/position-for-active-asset full-state)
        funding-predictability-state {:summary (read-by-coin
                                                (get-in full-state [:active-assets :funding-predictability :by-coin] {})
                                                coin)
                                      :loading? (true? (read-by-coin
                                                        (get-in full-state [:active-assets :funding-predictability :loading-by-coin] {})
                                                        coin))
                                      :error (read-by-coin
                                              (get-in full-state [:active-assets :funding-predictability :error-by-coin] {})
                                              coin)}
        funding-hypothetical-input (read-by-coin
                                    (get-in full-state [:funding-ui :hypothetical-position-by-coin] {})
                                    coin)
        locale (get-in full-state [:ui :locale])
        funding-tooltip-ui (get-in full-state [:funding-ui :tooltip] {})
        funding-tooltip-id (funding-tooltip-pin-id coin)
        funding-tooltip-open? (or (= funding-tooltip-id
                                     (:visible-id funding-tooltip-ui))
                                  (= funding-tooltip-id
                                     (:pinned-id funding-tooltip-ui)))
        funding-tooltip-pinned? (= funding-tooltip-id
                                   (:pinned-id funding-tooltip-ui))
        funding-tooltip (when funding-tooltip-open?
                          (memoized-funding-tooltip-model (or active-position {})
                                                          market
                                                          coin
                                                          mark
                                                          funding-rate
                                                          funding-predictability-state
                                                          funding-hypothetical-input
                                                          locale))
        dropdown-visible? (= (:visible-dropdown dropdown-state) :asset-selector)
        is-spot (= :spot (:market-type market))
        ;; Handle missing data gracefully
        has-perp-data? (and mark oracle change-24h volume-24h open-interest-usd funding-rate)
        has-spot-data? (and mark change-24h volume-24h)]
    [:div {:class ["relative"
                   "grid"
                   "grid-cols-7"
                   "gap-2"
                   "md:gap-3"
                   "items-center"
                   "px-0"
                   "py-2"
                   active-asset-grid-template]}
      ;; Asset/Pair column
      [:div {:class ["flex" "justify-start" "app-shell-gutter-left" "min-w-fit"]}
       (asset-icon icon-market
                   dropdown-visible?
                   (get-in full-state [:asset-selector :missing-icons] #{})
                   (get-in full-state [:asset-selector :loaded-icons] #{}))]
      
      ;; Mark column
      [:div.flex.justify-center
       (data-column "Mark"
                    (if mark
                      (fmt/format-trade-price mark mark-raw)
                      "Loading...")
                    {:underlined true
                     :numeric? true})]
      
      ;; Oracle column
      [:div.flex.justify-center
       (data-column "Oracle"
                    (if (and (not is-spot) oracle)
                      (fmt/format-trade-price oracle oracle-raw)
                      (if is-spot "—" "Loading..."))
                    {:underlined true
                     :numeric? true})]
      
      ;; 24h Change column
      [:div.flex.justify-center
       (data-column "24h Change" 
                    (if (or has-perp-data? has-spot-data?) nil "Loading...")
                    {:change? (or has-perp-data? has-spot-data?)
                     :change-value change-24h
                     :change-pct change-24h-pct
                     :change-raw nil
                     :numeric? true})]
      
      ;; 24h Volume column
      [:div.flex.justify-center
       (data-column "24h Volume"
                    (if volume-24h (fmt/format-large-currency volume-24h) "Loading...")
                    {:numeric? true})]
      
      ;; Open Interest column 
      [:div.flex.justify-center 
       (data-column "Open Interest"
                    (cond
                      is-spot "—"
                      open-interest-usd (fmt/format-large-currency open-interest-usd)
                      :else "Loading...")
                    {:underlined true
                     :numeric? true})]
      
      ;; Funding / Countdown column
     [:div.flex.justify-center
      [:div.text-center
        [:div {:class ["text-xs" "text-gray-400" "mb-1"]} "Funding / Countdown"]
        [:div {:class ["text-xs" "flex" "items-center" "justify-center"]}
         (if (and (not is-spot) has-perp-data?)
           (tooltip 
             [[:span {:class ["cursor-help" "num" (signed-tone-class funding-rate)]}
               (signed-percentage-text funding-rate 4)]
              (when funding-tooltip-open?
                (funding-tooltip-panel funding-tooltip))]
             "bottom"
             {:click-pinnable? true
              :pin-id funding-tooltip-id
              :pinned? funding-tooltip-pinned?})
           [:span (if is-spot "—" "Loading...")])
         [:span.mx-1 "/"]
         [:span.num (if is-spot "—" countdown-text)]]]]]))

(defn select-asset-row [dropdown-state]
  (let [dropdown-visible? (= (:visible-dropdown dropdown-state) :asset-selector)]
    [:div {:class ["relative"
                   "grid"
                   "grid-cols-7"
                   "gap-2"
                   "md:gap-3"
                   "items-center"
                   "px-0"
                   "py-2"
                   active-asset-grid-template]}
     [:div {:class ["flex" "justify-start" "app-shell-gutter-left" "min-w-fit"]}
      (asset-selector-trigger dropdown-visible?)]

     [:div.flex.justify-center
      (data-column "Mark" "—" {:underlined true})]

     [:div.flex.justify-center
      (data-column "Oracle" "—" {:underlined true})]

     [:div.flex.justify-center
      (data-column "24h Change" "—")]

     [:div.flex.justify-center
      (data-column "24h Volume" "—")]

     [:div.flex.justify-center 
      (data-column "Open Interest" "—" {:underlined true})]

     [:div.flex.justify-center
      [:div.text-center
       [:div {:class ["text-xs" "text-gray-400" "mb-1"]} "Funding / Countdown"]
       [:div {:class ["text-xs" "text-gray-400"]} "— / —"]]]]))

(defn active-asset-list [contexts dropdown-state full-state]
  (let [active-asset (:active-asset full-state)
        ctx-data (when active-asset (get contexts active-asset))
        active-market (resolve-active-market full-state active-asset)]
    [:div.space-y-2
     (when active-asset
       ^{:key active-asset}
       (active-asset-row (or ctx-data {:coin active-asset}) active-market dropdown-state full-state))]))

(defn empty-state []
  [:div.flex.flex-col.items-center.justify-center.p-8.text-center
   [:div.text-gray-400.mb-4
    [:svg.w-12.h-12.mx-auto {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"}]]]
   [:h3.text-lg.font-medium.text-gray-300 "No active assets"]
   [:p.text-sm.text-gray-500 "Subscribe to assets to see their trading data"]])

(defn loading-state []
  [:div.flex.items-center.justify-center.p-8
   [:div.animate-spin.rounded-full.h-8.w-8.border-b-2.border-primary]])

(defn active-asset-panel [contexts loading? dropdown-state full-state]
  (let [active-asset (:active-asset full-state)
        active-market (resolve-active-market full-state active-asset)
        selected-key (or (:key active-market)
                         (when active-asset (markets/coin->market-key active-asset)))]
    [:div {:class ["relative" "bg-base-200" "border-b" "border-base-300" "rounded-none" "spectate-none"]
           :data-parity-id "market-strip"}
     [:div
      (if (:active-asset full-state)
        (active-asset-list contexts dropdown-state full-state)
        (select-asset-row dropdown-state))]
     ;; Asset Selector Dropdown positioned at panel level
     (when (:visible-dropdown dropdown-state)
       (asset-selector/asset-selector-wrapper
         {:visible? true
          :markets (get-available-assets full-state)
          :selected-market-key selected-key
          :loading? (:loading? dropdown-state false)
          :phase (:phase dropdown-state :bootstrap)
          :search-term (:search-term dropdown-state "")
          :sort-by (:sort-by dropdown-state :volume)
          :sort-direction (:sort-direction dropdown-state :asc)
          :favorites (:favorites dropdown-state #{})
          :favorites-only? (:favorites-only? dropdown-state false)
          :missing-icons (:missing-icons dropdown-state #{})
          :loaded-icons (:loaded-icons dropdown-state #{})
          :highlighted-market-key (:highlighted-market-key dropdown-state nil)
          :scroll-top (:scroll-top dropdown-state 0)
          :render-limit (:render-limit dropdown-state 120)
          :strict? (:strict? dropdown-state false)
          :active-tab (:active-tab dropdown-state :all)}))]))

;; Main component that takes state and renders the UI
(defn active-asset-view [state]
  (let [active-assets (:active-assets state)
        contexts (:contexts active-assets)
        loading? (:loading active-assets)
        dropdown-state (get-in state [:asset-selector] {:visible-dropdown nil
                                                         :search-term ""
                                                         :sort-by :volume
                                                         :sort-direction :desc
                                                         :loading? false
                                                         :phase :bootstrap
                                                         :favorites #{}
                                                         :missing-icons #{}
                                                         :loaded-icons #{}
                                                         :highlighted-market-key nil
                                                         :scroll-top 0
                                                         :render-limit 120
                                                         :last-render-limit-increase-ms nil
                                                         :favorites-only? false
                                                         :strict? false
                                                         :active-tab :all})]
    (active-asset-panel contexts loading? dropdown-state state))) 
