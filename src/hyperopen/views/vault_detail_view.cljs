(ns hyperopen.views.vault-detail-view
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.views.vaults.detail-vm :as detail-vm]))

(defn- format-currency
  ([value]
   (format-currency value {:missing "—"}))
  ([value {:keys [missing]
           :or {missing "—"}}]
   (if (number? value)
     (or (fmt/format-currency value)
         "$0.00")
     missing)))

(defn- format-price
  [value]
  (if (number? value)
    (fmt/format-trade-price-plain value)
    "—"))

(defn- format-size
  [value]
  (if (number? value)
    (.toFixed value 4)
    "—"))

(defn- format-percent
  ([value]
   (format-percent value {:missing "—"}))
  ([value {:keys [missing
                  signed?
                  decimals]
           :or {missing "—"
                signed? true
                decimals 2}}]
   (if (number? value)
     (let [n value
           sign (cond
                  (and signed? (pos? n)) "+"
                  (neg? n) "-"
                  :else "")]
       (str sign (.toFixed (js/Math.abs n) decimals) "%"))
     missing)))

(defn- format-funding-rate
  [value]
  (if (number? value)
    (str (.toFixed (* 100 value) 4) "%")
    "—"))

(defn- format-time
  [time-ms]
  (or (fmt/format-local-date-time time-ms)
      "—"))

(def ^:private tooltip-currency-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:style "currency"
        :currency "USD"
        :maximumFractionDigits 0
        :minimumFractionDigits 0}))

(def ^:private tooltip-time-formatter
  (js/Intl.DateTimeFormat.
   "en-US"
   #js {:hour "2-digit"
        :minute "2-digit"
        :hour12 false}))

(def ^:private tooltip-date-parts-formatter
  (js/Intl.DateTimeFormat.
   "en-US"
   #js {:year "numeric"
        :month "short"
        :day "2-digit"}))

(defn- format-chart-axis-value
  [axis-kind value]
  (let [n (if (number? value) value 0)
        n* (if (== n -0) 0 n)]
    (case axis-kind
      :returns (let [rounded (/ (js/Math.round (* n* 100)) 100)
                     rounded* (if (== rounded -0) 0 rounded)
                     sign (cond
                            (pos? rounded*) "+"
                            (neg? rounded*) "-"
                            :else "")
                     magnitude (.toFixed (js/Math.abs rounded*) 2)]
                 (str sign magnitude "%"))
      :pnl (or (fmt/format-large-currency n*) "$0")
      :account-value (or (fmt/format-large-currency n*) "$0")
      (or (fmt/format-large-currency n*) "$0"))))

(defn- format-chart-tooltip-value
  [axis-kind value]
  (let [n (if (number? value) value 0)
        n* (if (== n -0) 0 n)]
    (case axis-kind
      :returns (format-chart-axis-value :returns n*)
      :pnl (format-currency n* {:missing "$0.00"})
      :account-value (format-currency n* {:missing "$0.00"})
      (format-currency n* {:missing "$0.00"}))))

(defn- date-part-value
  [parts token]
  (some (fn [{:keys [type value]}]
          (when (= type token)
            value))
        parts))

(defn- format-tooltip-date
  [time-ms]
  (if (number? time-ms)
    (let [parts (js->clj (.formatToParts tooltip-date-parts-formatter (js/Date. time-ms))
                         :keywordize-keys true)
          year (date-part-value parts "year")
          month (date-part-value parts "month")
          day (date-part-value parts "day")]
      (if (and year month day)
        (str year " " month " " day)
        "—"))
    "—"))

(defn- format-tooltip-time
  [time-ms]
  (if (number? time-ms)
    (.format tooltip-time-formatter (js/Date. time-ms))
    "--:--"))

(defn- tooltip-metric-label
  [axis-kind]
  (case axis-kind
    :account-value "Account Value"
    :pnl "PNL"
    :returns "Returns"
    "Value"))

(defn- tooltip-value-classes
  [axis-kind value]
  (let [n (if (number? value) value 0)
        positive-classes ["text-[#16d6a1]"]
        negative-classes ["text-[#ff7b72]"]
        neutral-classes ["text-[#e6edf2]"]]
    (case axis-kind
      :account-value ["text-[#ff9f1a]"]
      :pnl (cond
             (pos? n) positive-classes
             (neg? n) negative-classes
             :else neutral-classes)
      :returns (cond
                 (pos? n) positive-classes
                 (neg? n) negative-classes
                 :else neutral-classes)
      neutral-classes)))

(defn- chart-tooltip-model
  [summary-time-range axis-kind {:keys [time-ms value]}]
  {:timestamp (if (= summary-time-range :day)
                (format-tooltip-time time-ms)
                (format-tooltip-date time-ms))
   :metric-label (tooltip-metric-label axis-kind)
   :metric-value (format-chart-tooltip-value axis-kind value)
   :value-classes (tooltip-value-classes axis-kind value)})

(defn- chart-tooltip-benchmark-values
  [axis-kind hovered-index series]
  (if (and (= axis-kind :returns)
           (number? hovered-index))
    (->> (or series [])
         (keep (fn [{:keys [id coin label stroke points]}]
                 (when (and (keyword? id)
                            (not= id :strategy))
                   (let [point (get (or points []) hovered-index)
                         value (:value point)
                         coin* (or (some-> coin str str/trim)
                                   (name id))
                         label* (or (some-> label str str/trim)
                                    coin*
                                    "Benchmark")
                         stroke* (if (and (string? stroke)
                                          (seq stroke))
                                   stroke
                                   "#e6edf2")]
                     (when (number? value)
                       {:coin coin*
                        :label label*
                        :value (format-chart-tooltip-value :returns value)
                        :stroke stroke*})))))
         vec)
    []))

(defn- clamp-number
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(def ^:private axis-label-fallback-char-width-px
  7.5)

(def ^:private axis-label-horizontal-padding-px
  30)

(def ^:private axis-label-min-gutter-width-px
  56)

(def ^:private axis-label-measure-context
  (delay
    (when (and (exists? js/document)
               (some? js/document))
      (let [canvas (.createElement js/document "canvas")]
        (.getContext canvas "2d")))))

(defn- axis-label-width-px [text]
  (let [context @axis-label-measure-context]
    (if context
      (do
        ;; Match chart tick labels rendered at 12px for reliable gutter width.
        (set! (.-font context) "12px \"Inter Variable\", system-ui, -apple-system, \"Segoe UI\", sans-serif")
        (-> context
            (.measureText text)
            .-width))
      (* axis-label-fallback-char-width-px (count text)))))

(defn- y-axis-gutter-width [axis-kind y-ticks]
  (let [widest-label-px (->> y-ticks
                             (map (fn [{:keys [value]}]
                                    (axis-label-width-px (format-chart-axis-value axis-kind value))))
                             (reduce max 0))
        gutter-width (+ widest-label-px axis-label-horizontal-padding-px)]
    (js/Math.ceil (max axis-label-min-gutter-width-px gutter-width))))

(defn- short-hash
  [value]
  (if (and (string? value)
           (> (count value) 12))
    (str (subs value 0 8) "..." (subs value (- (count value) 6)))
    (or value "—")))

(defn- normalized-text
  [value]
  (some-> value str str/trim str/lower-case))

(defn- resolved-vault-name
  [name-value vault-address]
  (let [name* (some-> name-value str str/trim)]
    (when (and (seq name*)
               (not= (normalized-text name*)
                     (normalized-text vault-address)))
      name*)))

(defn- loading-skeleton-block
  [extra-classes]
  [:span {:aria-hidden true
          :class (into ["block"
                        "rounded"
                        "bg-[#1a363b]/80"
                        "animate-pulse"]
                       extra-classes)}])

(defn- metric-value-size-classes
  [value]
  (let [value-length (count (str (or value "")))]
    (cond
      (> value-length 16) ["text-[18px]" "sm:text-[22px]" "lg:text-[30px]"]
      (> value-length 12) ["text-[20px]" "sm:text-[24px]" "lg:text-[34px]"]
      :else ["text-[22px]" "sm:text-[28px]" "lg:text-[38px]"])))

(defn- metric-card
  [{:keys [label value accent]}]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-[#1a3a37]"
                 "bg-[#091a23]/88"
                 "min-w-0"
                 "px-3.5"
                 "py-3"
                 "shadow-[inset_0_0_0_1px_rgba(8,38,45,0.35)]"]}
   [:div {:class ["text-xs"
                  "uppercase"
                  "tracking-[0.08em]"
                  "text-[#8ba0a7]"]}
    label]
   [:div {:class (into ["mt-1.5"
                        "num"
                        "leading-[1.08]"
                        "font-semibold"]
                       (concat (metric-value-size-classes value)
                               (case accent
                                 :positive ["text-[#5de2c0]"]
                                 :negative ["text-[#e59ca8]"]
                                 ["text-trading-text"])))}
    value]])

(defn- format-activity-count [count]
  (cond
    (not (number? count)) nil
    (<= count 0) nil
    (>= count 100) "100+"
    :else (str count)))

(defn- detail-tab-button [{:keys [value label]} selected-tab]
  [:button {:type "button"
            :class (into ["border-b"
                          "px-3"
                          "py-2.5"
                          "text-sm"
                          "font-medium"
                          "transition-colors"]
                         (if (= value selected-tab)
                           ["border-[#66e3c5]" "text-trading-text"]
                           ["border-transparent" "text-[#8ea0a7]" "hover:text-trading-text"]))
            :on {:click [[:actions/set-vault-detail-tab value]]}}
   label])

(defn- chart-series-button [{:keys [value label]} selected-series]
  [:button {:type "button"
            :class (into ["rounded-md"
                          "border"
                          "px-2.5"
                          "py-1"
                          "text-xs"
                          "transition-colors"]
                         (if (= value selected-series)
                           ["border-[#2f5e58]" "bg-[#0d252f]" "text-trading-text"]
                           ["border-transparent" "text-[#8ea4ab]" "hover:text-trading-text"]))
            :on {:click [[:actions/set-vault-detail-chart-series value]]}}
   label])

(defn- timeframe-token
  [value]
  (let [token (cond
                (keyword? value) (name value)
                (string? value) (str/trim value)
                :else nil)]
    (when (seq token)
      token)))

(defn- chart-timeframe-menu [{:keys [timeframe-options selected-timeframe]}]
  (let [selected-token (or (timeframe-token selected-timeframe)
                           (timeframe-token (ffirst timeframe-options))
                           "day")]
    [:label {:class ["inline-flex"
                     "items-center"
                     "gap-1.5"
                     "rounded-md"
                     "border"
                     "border-[#1f3b3c]"
                     "bg-[#071e25]"
                     "px-2.5"
                     "py-1"
                     "text-xs"
                     "text-trading-text"]}
     [:span "Range "]
     [:select {:class ["bg-transparent"
                       "text-trading-text"
                       "outline-none"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:border-transparent"
                       "focus-visible:outline-none"
                       "focus-visible:ring-0"
                       "border-none"
                       "p-0"
                       "pr-4"
                       "text-xs"]
               :value selected-token
               :on {:change [[:actions/set-vaults-snapshot-range [:event.target/value]]]}}
      (for [{:keys [value label]} timeframe-options
            :let [option-token (or (timeframe-token value)
                                   "day")]]
        ^{:key (str "vault-chart-timeframe-" option-token)}
        [:option (cond-> {:value option-token}
                   (= option-token selected-token)
                   (assoc :selected true))
         label])]]))

(defn- hex-color->rgba [hex alpha]
  (let [hex* (if (and (string? hex)
                      (= \# (first hex)))
               (subs hex 1)
               hex)]
    (when (and (string? hex*)
               (re-matches #"[0-9A-Fa-f]{6}" hex*))
      (let [r (js/Number.parseInt (subs hex* 0 2) 16)
            g (js/Number.parseInt (subs hex* 2 4) 16)
            b (js/Number.parseInt (subs hex* 4 6) 16)]
        (str "rgba(" r ", " g ", " b ", " alpha ")")))))

(defn- returns-benchmark-chip [{:keys [value label stroke]}]
  (let [display-label (let [raw-label (some-> label clojure.core/str str/trim)
                            label-without-suffix (some-> raw-label
                                                         (str/replace #"\s*\([^)]*\)\s*$" ""))
                            primary-token (some-> label-without-suffix
                                                  (str/split #"-" 2)
                                                  first
                                                  str/trim)]
                        (if (seq primary-token)
                          primary-token
                          (or raw-label "")))
        accent-color (or stroke "#9fb3be")
        border-color (or (hex-color->rgba stroke 0.58)
                         "rgba(120, 141, 154, 0.5)")
        background-color (or (hex-color->rgba stroke 0.14)
                             "rgba(120, 141, 154, 0.16)")]
    [:span {:class ["inline-flex"
                    "max-w-full"
                    "items-center"
                    "gap-1"
                    "rounded-md"
                    "border"
                    "px-1"
                    "py-0.5"]
            :style {:border-color border-color
                    :background-color background-color}
            :data-role (str "vault-detail-returns-benchmark-chip-" value)}
     [:span {:class ["h-1.5" "w-1.5" "shrink-0" "rounded-full"]
             :style {:background-color accent-color}}]
     [:span {:class ["min-w-0" "truncate" "text-xs" "leading-4" "text-trading-text"]}
      display-label]
     [:button {:type "button"
               :class ["inline-flex"
                       "h-6"
                       "w-6"
                       "items-center"
                       "justify-center"
                       "rounded"
                       "transition-colors"
                       "hover:bg-base-300"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :style {:color accent-color}
               :aria-label (str "Remove benchmark " label)
               :on {:click [[:actions/remove-vault-detail-returns-benchmark value]]}}
      "x"]]))

(defn- returns-benchmark-suggestion-row [{:keys [value label]}]
  [:button {:type "button"
            :class ["flex"
                    "w-full"
                    "items-center"
                    "justify-start"
                    "rounded"
                    "px-2"
                    "py-1.5"
                    "text-left"
                    "text-xs"
                    "text-trading-text"
                    "transition-colors"
                    "hover:bg-base-300"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"]
            :data-role (str "vault-detail-returns-benchmark-suggestion-" value)
            :on {:mousedown [[:actions/select-vault-detail-returns-benchmark value]]}}
   label])

(defn- returns-benchmark-selector [{:keys [coin-search
                                           suggestions-open?
                                           candidates
                                           top-coin
                                           empty-message]}]
  (let [candidates* (vec (or candidates []))]
    [:div {:class ["relative" "w-[320px]"]
           :data-role "vault-detail-returns-benchmark-selector"}
     [:div {:class ["rounded-md" "border" "border-[#1f3b3c]" "bg-[#071e25]" "px-2"]}
      [:input {:id "vault-detail-returns-benchmark-search"
               :class ["h-9"
                       "w-full"
                       "border-0"
                       "bg-transparent"
                       "px-1"
                       "text-xs"
                       "text-trading-text"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :type "search"
               :placeholder "Search benchmarks and press Enter"
               :aria-label "Search benchmark symbols"
               :autocomplete "off"
               :spellcheck false
               :value (or coin-search "")
               :on {:input [[:actions/set-vault-detail-returns-benchmark-search [:event.target/value]]]
                    :focus [[:actions/set-vault-detail-returns-benchmark-suggestions-open true]]
                    :blur [[:actions/set-vault-detail-returns-benchmark-suggestions-open false]]
                    :keydown [[:actions/handle-vault-detail-returns-benchmark-search-keydown [:event/key] top-coin]]}}]]
     (when suggestions-open?
       [:div {:class ["absolute"
                      "left-0"
                      "right-0"
                      "top-full"
                      "mt-1"
                      "max-h-44"
                      "overflow-y-auto"
                      "rounded-md"
                      "border"
                      "border-[#1f3b3c]"
                      "bg-[#081f29]"
                      "p-1"
                      "shadow-lg"
                      "z-40"]}
        (if (seq candidates*)
          (for [option candidates*]
            ^{:key (str "vault-detail-returns-benchmark-suggestion-" (:value option))}
            (returns-benchmark-suggestion-row option))
          [:div {:class ["px-2" "py-1.5" "text-xs" "text-[#8aa0a7]"]}
           (or empty-message "No matching symbols.")])])]))

(defn- returns-benchmark-chip-rail [{:keys [selected-options]}]
  (let [chips (vec (or selected-options []))]
    (when (seq chips)
      [:div {:class ["rounded-md"
                     "border"
                     "border-[#1f3b3c]"
                     "bg-[#081f29]"
                     "p-1.5"
                     "shadow-md"]
             :data-role "vault-detail-returns-benchmark-chip-rail"}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-1.5" "pr-1"]}
        (for [{:keys [value] :as option} chips]
          ^{:key (str "vault-detail-returns-benchmark-chip-rail-item-" value)}
          (returns-benchmark-chip option))]])))

(defn- benchmark-series-color-by-coin [series]
  (reduce (fn [acc {:keys [coin stroke]}]
            (if (and (string? coin)
                     (seq coin)
                     (string? stroke)
                     (seq stroke))
              (assoc acc coin stroke)
              acc))
          {}
          (or series [])))

(defn- add-benchmark-chip-colors [returns-benchmark series]
  (let [color-by-coin (benchmark-series-color-by-coin series)]
    (update returns-benchmark
            :selected-options
            (fn [options]
              (mapv (fn [{:keys [value] :as option}]
                      (assoc option :stroke (get color-by-coin value)))
                    (or options []))))))

(defn- chart-series-path [{:keys [id path stroke]}]
  (when (seq path)
    [:path {:d path
            :fill "none"
            :stroke stroke
            :stroke-width 1.0
            :vector-effect "non-scaling-stroke"
            :stroke-linecap "square"
            :stroke-linejoin "miter"
            :data-role (if (= id :strategy)
                         "vault-detail-chart-path"
                         (str "vault-detail-chart-path-" (name id)))}]))

(defn- chart-series-area-layers
  [{:keys [id area-path area-fill area-positive-fill area-negative-fill zero-y-ratio]}]
  (when (seq area-path)
    (cond
      (string? area-fill)
      [:path {:d area-path
              :fill area-fill
              :data-role (if (= id :strategy)
                           "vault-detail-chart-area"
                           (str "vault-detail-chart-area-" (name id)))}]

      (and (string? area-positive-fill)
           (string? area-negative-fill)
           (number? zero-y-ratio))
      (let [clip-ratio (clamp-number zero-y-ratio 0 1)
            clip-y (max 0 (min 100 (* 100 clip-ratio)))
            positive-clip-id (str "vault-detail-chart-area-clip-positive-" (name id))
            negative-clip-id (str "vault-detail-chart-area-clip-negative-" (name id))]
        [:g {:data-role (if (= id :strategy)
                          "vault-detail-chart-area-split"
                          (str "vault-detail-chart-area-split-" (name id)))}
         [:defs
          [:clipPath {:id positive-clip-id}
           [:rect {:x 0
                   :y 0
                   :width 100
                   :height clip-y}]]
          [:clipPath {:id negative-clip-id}
           [:rect {:x 0
                   :y clip-y
                   :width 100
                   :height (- 100 clip-y)}]]]
         [:path {:d area-path
                 :fill area-positive-fill
                 :clip-path (str "url(#" positive-clip-id ")")
                 :data-role (if (= id :strategy)
                              "vault-detail-chart-area-positive"
                              (str "vault-detail-chart-area-positive-" (name id)))}]
         [:path {:d area-path
                 :fill area-negative-fill
                 :clip-path (str "url(#" negative-clip-id ")")
                 :data-role (if (= id :strategy)
                              "vault-detail-chart-area-negative"
                              (str "vault-detail-chart-area-negative-" (name id)))}]])

      :else
      nil)))

(defn- chart-legend [series]
  (let [visible-series (->> series
                            (filter :has-data?)
                            vec)]
    (when (> (count visible-series) 1)
      [:div {:class ["flex"
                     "flex-wrap"
                     "items-center"
                     "gap-3"
                     "rounded-md"
                     "bg-[#081f29]"
                     "px-2"
                     "py-1.5"]}
       (for [{:keys [id label stroke]} visible-series]
         ^{:key (str "vault-detail-chart-legend-item-" (name id))}
         [:div {:class ["flex" "items-center" "gap-1.5"]}
          [:span {:class ["h-2" "w-2" "rounded-full"]
                  :style {:background-color stroke}}]
          [:span {:class ["text-xs" "text-[#8aa0a7]"]}
           label]])])))

(defn- finite-number? [value]
  (and (number? value)
       (js/isFinite value)))

(defn- format-signed-percent-from-decimal [value]
  (when (finite-number? value)
    (let [pct (* value 100)
          rounded (/ (js/Math.round (* pct 100)) 100)
          rounded* (if (== rounded -0) 0 rounded)
          sign (cond
                 (pos? rounded*) "+"
                 (neg? rounded*) "-"
                 :else "")
          magnitude (.toFixed (js/Math.abs rounded*) 2)]
      (str sign magnitude "%"))))

(defn- format-ratio-value [value]
  (when (finite-number? value)
    (.toFixed value 2)))

(defn- format-integer-value [value]
  (when (finite-number? value)
    (str (js/Math.round value))))

(defn- format-metric-value [kind value]
  (case kind
    :percent (or (format-signed-percent-from-decimal value) "--")
    :ratio (or (format-ratio-value value) "--")
    :integer (or (format-integer-value value) "--")
    :date (if (and (string? value)
                   (seq (str/trim value)))
            value
            "--")
    "--"))

(defn- performance-metric-value-cell
  ([kind value]
   (performance-metric-value-cell kind value nil))
  ([kind value attrs]
   [:span (merge {:class (into ["justify-self-start" "text-sm" "text-trading-text" "text-left"]
                               (when (not= kind :date)
                                 ["num"]))}
                 attrs)
    (format-metric-value kind value)]))

(defn- resolved-benchmark-metric-columns
  [{:keys [benchmark-columns benchmark-selected? benchmark-label benchmark-coin]}]
  (let [columns (->> (or benchmark-columns [])
                     (keep (fn [{:keys [coin label]}]
                             (let [coin* (some-> coin str str/trim)
                                   label* (some-> label str str/trim)]
                               (when (seq coin*)
                                 {:coin coin*
                                  :label (or label* coin*)}))))
                     vec)]
    (if (seq columns)
      columns
      [{:coin (or (some-> benchmark-coin str str/trim)
                  "__benchmark__")
        :label (if benchmark-selected?
                 (or benchmark-label "Benchmark")
                 "Benchmark")}]))
  )

(defn- benchmark-row-value
  [row coin]
  (let [values (:benchmark-values row)]
    (if (and (map? values)
             (contains? values coin))
      (get values coin)
      (:benchmark-value row))))

(defn- metric-value-present?
  [kind value]
  (not= "--" (format-metric-value kind value)))

(defn- performance-metric-row-visible?
  [{:keys [kind value] :as row} benchmark-columns]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)]
    (or (metric-value-present? kind portfolio-value)
        (some (fn [{:keys [coin]}]
                (metric-value-present? kind (benchmark-row-value row coin)))
              benchmark-columns))))

(defn- performance-metrics-grid-style
  [benchmark-column-count]
  {:grid-template-columns (str/join " "
                                    (concat ["220px"]
                                            (repeat benchmark-column-count "132px")
                                            ["132px"]))})

(defn- performance-metric-row [{:keys [key label kind value] :as row} benchmark-columns grid-style]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)]
    [:div {:class ["grid"
                   "items-center"
                   "justify-items-start"
                   "gap-3"
                   "hover:bg-[#0e2630]"]
           :style grid-style
           :data-role (str "vault-detail-performance-metric-" (name key))}
     [:span {:class ["text-sm"]
             :style {:color "#9CA3AF"}}
      label]
     (for [{:keys [coin]} benchmark-columns]
       ^{:key (str "vault-detail-performance-metric-" (name key) "-benchmark-" coin)}
       (performance-metric-value-cell kind
                                      (benchmark-row-value row coin)
                                      {:data-role (str "vault-detail-performance-metric-" (name key) "-benchmark-value-" coin)}))
     (performance-metric-value-cell kind portfolio-value)]))

(defn- performance-metrics-card [{:keys [benchmark-selected?
                                         benchmark-label
                                         benchmark-columns
                                         benchmark-coin
                                         groups
                                         timeframe-options
                                         selected-timeframe]}]
  (let [benchmark-columns* (resolved-benchmark-metric-columns {:benchmark-columns benchmark-columns
                                                               :benchmark-selected? benchmark-selected?
                                                               :benchmark-label benchmark-label
                                                               :benchmark-coin benchmark-coin})
        grid-style (performance-metrics-grid-style (count benchmark-columns*))
        visible-groups (->> (or groups [])
                            (keep (fn [{:keys [rows] :as group}]
                                    (let [rows* (->> (or rows [])
                                                     (filter #(performance-metric-row-visible? % benchmark-columns*))
                                                     vec)]
                                      (when (seq rows*)
                                        (assoc group :rows rows*)))))
                            vec)]
    [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
     [:div {:class ["grid"
                    "items-center"
                    "justify-items-start"
                    "gap-3"
                    "border-b"
                    "border-[#1f3b3c]"
                    "bg-[#0a232d]"
                    "px-4"
                    "py-2.5"]
            :style grid-style}
      [:div {:class ["flex" "min-w-0" "items-center" "justify-between" "gap-2"]}
       [:span {:class ["text-xs" "font-medium" "uppercase" "tracking-wide" "text-[#8aa0a7]"]}
        "Metric"]
       (when (and (seq timeframe-options)
                  (keyword? selected-timeframe))
         (chart-timeframe-menu {:timeframe-options timeframe-options
                                :selected-timeframe selected-timeframe}))]
      (for [[idx {:keys [coin label]}] (map-indexed vector benchmark-columns*)]
        ^{:key (str "vault-detail-performance-metrics-benchmark-label-" coin)}
        [:span {:class ["justify-self-start" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-left" "text-[#8aa0a7]"]
                :data-role (if (zero? idx)
                             "vault-detail-performance-metrics-benchmark-label"
                             (str "vault-detail-performance-metrics-benchmark-label-" coin))}
         label])
      [:span {:class ["justify-self-start" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-left" "text-[#8aa0a7]"]}
       "Vault"]]
     [:div {:class ["flex-1" "min-h-0" "space-y-2.5" "overflow-y-auto" "scrollbar-hide" "px-4" "py-3"]}
      (for [[idx {:keys [id rows]}] (map-indexed vector visible-groups)]
        ^{:key (str "vault-detail-performance-metrics-group-" (name id))}
        [:div {:class (into ["space-y-1.5"]
                            (when (pos? idx)
                              ["border-t" "border-[#1f3b3c]" "pt-2.5"]))}
         (for [{:keys [key] :as row} rows]
           ^{:key (str "vault-detail-performance-metric-row-" (name key))}
           (performance-metric-row row benchmark-columns* grid-style))])]]))

(defn- activity-tab-button [{:keys [value label count]} selected-tab]
  [:button {:type "button"
            :class (into ["whitespace-nowrap"
                          "border-b"
                          "px-4"
                          "py-2.5"
                          "text-sm"
                          "font-normal"
                          "transition-colors"]
                         (if (= value selected-tab)
                           ["border-[#303030]" "text-[#f6fefd]"]
                           ["border-[#303030]" "text-[#949e9c]" "hover:text-[#f6fefd]"]))
            :on {:click [[:actions/set-vault-detail-activity-tab value]]}}
   (if-let [count-label (format-activity-count count)]
     (str label " (" count-label ")")
     label)])

(defn- render-address-list [addresses]
  (when (seq addresses)
    [:div {:class ["space-y-1.5"]}
     [:div {:class ["text-[#8da0a6]"]}
      "This vault uses the following vaults as component strategies:"]
     (for [address addresses]
       ^{:key (str "component-vault-" address)}
       [:div {:class ["num" "break-all" "text-[#33d1b7]"]}
        address])]))

(defn- render-about-panel [{:keys [description leader relationship]}]
  (let [component-addresses (or (:child-addresses relationship) [])
        parent-address (:parent-address relationship)]
    [:div {:class ["space-y-3" "px-3" "pb-3" "pt-2" "text-sm"]}
     [:div
      [:div {:class ["text-[#8da0a6]"]} "Leader"]
      [:div {:class ["num" "font-medium" "text-trading-text"]}
       (or (wallet/short-addr leader) "—")]]
     [:div
      [:div {:class ["text-[#8da0a6]"]} "Description"]
      [:p {:class ["mt-1" "leading-5" "text-trading-text"]}
       (if (seq description)
         description
         "No vault description available.")]]
     (when parent-address
       [:div {:class ["text-[#8da0a6]"]}
        "Parent strategy: "
        [:button {:type "button"
                  :class ["num" "text-[#66e3c5]" "hover:underline"]
                  :on {:click [[:actions/navigate (str "/vaults/" parent-address)]]}}
         parent-address]])
     (render-address-list component-addresses)]))

(defn- render-vault-performance-panel [{:keys [snapshot]}]
  [:div {:class ["grid" "grid-cols-2" "gap-3" "px-3" "pb-3" "pt-2" "text-sm"]}
   [:div
    [:div {:class ["text-[#8da0a6]"]} "24H"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (format-percent (:day snapshot))]]
   [:div
    [:div {:class ["text-[#8da0a6]"]} "7D"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (format-percent (:week snapshot))]]
   [:div
    [:div {:class ["text-[#8da0a6]"]} "30D"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (format-percent (:month snapshot))]]
   [:div
    [:div {:class ["text-[#8da0a6]"]} "All-time"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (format-percent (:all-time snapshot))]]])

(defn- render-your-performance-panel [metrics]
  [:div {:class ["space-y-3" "px-3" "pb-3" "pt-2" "text-sm"]}
   [:div
    [:div {:class ["text-[#8da0a6]"]} "Your Deposits"]
    [:div {:class ["num" "font-medium" "text-trading-text"]}
     (format-currency (:your-deposit metrics))]]
   [:div
    [:div {:class ["text-[#8da0a6]"]} "All-time Earned"]
    [:div {:class ["num" "font-medium" "text-trading-text"]}
     (format-currency (:all-time-earned metrics))]]])

(defn- render-tab-panel [{:keys [selected-tab] :as vm}]
  (case selected-tab
    :vault-performance (render-vault-performance-panel vm)
    :your-performance (render-your-performance-panel (:metrics vm))
    (render-about-panel vm)))

(defn- relationship-links [{:keys [relationship]}]
  (case (:type relationship)
    :child
    (when-let [parent-address (:parent-address relationship)]
      [:div {:class ["mt-1.5" "text-xs" "text-[#8fa3aa]"]}
       "Parent strategy: "
       [:button {:type "button"
                 :class ["num" "text-[#66e3c5]" "hover:underline"]
                 :on {:click [[:actions/navigate (str "/vaults/" parent-address)]]}}
        (wallet/short-addr parent-address)]])

    nil))

(def ^:private activity-direction-filter-tabs
  #{:positions
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history})

(defn- activity-direction-filter-enabled?
  [activity-tab]
  (contains? activity-direction-filter-tabs activity-tab))

(defn- sort-header-button
  [tab label sort-state]
  (let [active? (= label (:column sort-state))
        direction (:direction sort-state)
        icon (when active?
               (if (= :asc direction) "↑" "↓"))]
    [:button {:type "button"
              :class (into ["group"
                            "inline-flex"
                            "items-center"
                            "gap-1"
                            "text-xs"
                            "font-medium"
                            "text-[#949e9c]"
                            "transition-colors"
                            "hover:text-[#f6fefd]"]
                           (when active?
                             ["text-[#f6fefd]"]))
              :on {:click [[:actions/sort-vault-detail-activity tab label]]}}
     [:span label]
     (when icon
       [:span {:class ["text-xs" "opacity-70"]}
        icon])]))

(defn- table-header [tab labels sort-state]
  [:thead
   [:tr {:class ["border-b" "border-[#1b3237]" "bg-transparent" "text-xs" "font-medium" "text-[#949e9c]"]}
    (for [label labels]
      ^{:key (str "activity-header-" label)}
      [:th {:class ["px-4" "py-2" "text-left" "whitespace-nowrap" "font-medium"]}
       (sort-header-button tab label sort-state)])]])

(defn- empty-table-row [col-span message]
  [:tr
   [:td {:col-span col-span
         :class ["px-4" "py-6" "text-left" "text-sm" "text-[#8f9ea5]"]}
    message]])

(defn- error-table-row [col-span message]
  [:tr
   [:td {:col-span col-span
         :class ["px-4" "py-6" "text-left" "text-sm" "text-red-300"]}
    message]])

(defn- position-pnl-class [pnl]
  (cond
    (and (number? pnl) (pos? pnl)) "text-[#1fa67d]"
    (and (number? pnl) (neg? pnl)) "text-[#ed7088]"
    :else "text-trading-text"))

(defn- normalize-side
  [value]
  (case (cond
          (keyword? value) (some-> value name str/lower-case)
          :else (some-> value str str/trim str/lower-case))
    ("long" "buy" "b") :long
    ("short" "sell" "a" "s") :short
    nil))

(defn- side-tone-class
  [value]
  (case (normalize-side value)
    :long "text-[#1fa67d]"
    :short "text-[#ed7088]"
    "text-trading-text"))

(defn- side-coin-tone-class
  [value]
  (case (normalize-side value)
    :long "text-[#97fce4]"
    :short "text-[#eaafb8]"
    "text-trading-text"))

(defn- side-coin-cell-style
  [value]
  (case (normalize-side value)
    :long {:background "linear-gradient(90deg,rgb(31,166,125) 0px,rgb(31,166,125) 4px,rgba(11,50,38,0.92) 4px,transparent 100%)"
           :padding-left "12px"}
    :short {:background "linear-gradient(90deg,rgb(237,112,136) 0px,rgb(237,112,136) 4px,rgba(52,36,46,0.92) 4px,transparent 100%)"
            :padding-left "12px"}
    nil))

(defn- interactive-value-class
  []
  ["underline" "decoration-dotted" "underline-offset-2"])

(defn- status-tone-class
  [status]
  (let [status* (some-> status str str/trim str/lower-case)]
    (cond
      (or (= status* "filled")
          (= status* "complete")
          (= status* "completed")
          (= status* "open")
          (= status* "active")) "text-[#1fa67d]"
      (or (= status* "rejected")
          (= status* "error")
          (= status* "failed")) "text-[#ed7088]"
      (or (= status* "canceled")
          (= status* "cancelled")
          (= status* "closed")) "text-[#9aa7ad]"
      :else "text-trading-text")))

(defn- ledger-type-tone-class
  [type-label]
  (case (some-> type-label str str/lower-case)
    "deposit" "text-[#1fa67d]"
    "withdraw" "text-[#ed7088]"
    "text-trading-text"))

(def ^:private activity-row-class
  ["border-b"
   "border-[#1b3237]"
   "text-sm"
   "text-[#f6fefd]"
   "transition-colors"
   "hover:bg-[#0d2028]/40"])

(def ^:private activity-cell-class
  ["px-4" "py-2.5"])

(def ^:private activity-cell-num-class
  ["px-4" "py-2.5" "num"])

(defn- balances-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[760px]" "border-collapse"]}
    (table-header :balances ["Coin" "Total Balance" "Available Balance" "USDC Value"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin total available usdc-value]} rows]
         ^{:key (str "balance-" coin "-" total)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (or coin "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (format-currency total)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])}
           [:span {:class (into ["text-[#f6fefd]"] (interactive-value-class))}
            (format-currency available)]]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (format-currency usdc-value)]])
       (empty-table-row 4 "No balances available."))]]])

(defn- positions-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1285px]" "border-collapse"]}
    (table-header :positions ["Coin" "Size" "Position Value" "Entry Price" "Mark Price" "PNL (ROE %)" "Liq. Price" "Margin" "Funding"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin leverage size position-value entry-price mark-price pnl roe liq-price margin funding]} rows]
         (let [side (if (number? size)
                      (if (neg? size) :short :long)
                      nil)]
           ^{:key (str "position-" coin "-" size "-" entry-price)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side)}
             [:span {:class [(side-coin-tone-class side)]}
              (or coin "—")]
             (when (number? leverage)
               [:span {:class ["ml-1" (side-tone-class side)]}
                (str leverage "x")])]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side)])}
             (format-size size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (if (number? position-value)
               (str (fmt/format-currency position-value) " USDC")
               "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (format-price entry-price)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (format-price mark-price)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class pnl)])}
             (if (number? pnl)
               (str (format-currency pnl {:missing "—"}) " (" (format-percent roe) ")")
               "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (if (number? liq-price)
               (format-price liq-price)
               "N/A")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (if (number? margin)
               (str (format-currency margin) " (Cross)")
               "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class funding)])}
             (format-currency funding)]]))
       (empty-table-row 9 "No active positions."))]]])

(defn- open-orders-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[960px]" "border-collapse"]}
    (table-header :open-orders ["Time" "Coin" "Side" "Size" "Price" "Trigger"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [time-ms coin side size price trigger-price]} rows]
         (let [side* (normalize-side side)]
           ^{:key (str "open-order-" time-ms "-" coin "-" size "-" price)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side*)}
             [:span {:class [(side-coin-tone-class side*)]}
              (or coin "—")]]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side*)])} (or side "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side*)])} (format-size size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price price)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price trigger-price)]]))
       (empty-table-row 6 "No open orders."))]]])

(defn- twap-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1260px]" "border-collapse"]}
    (table-header :twap ["Coin" "Size" "Executed Size" "Average Price" "Running Time / Total" "Reduce Only" "Creation Time" "Terminate"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin size executed-size average-price running-label reduce-only? creation-time-ms]} rows]
         ^{:key (str "twap-" coin "-" creation-time-ms "-" size)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or coin "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-size size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-size executed-size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price average-price)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or running-label "—")]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" (if (true? reduce-only?) "text-[#ed7088]" "text-[#1fa67d]")])}
           (if (true? reduce-only?) "Yes" "No")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-time creation-time-ms)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#8f9ea5]"])} "—"]])
       (empty-table-row 8 "No TWAPs yet."))]]])

(defn- fills-table [rows loading? error sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1180px]" "border-collapse"]}
    (table-header :trade-history ["Time" "Coin" "Side" "Price" "Size" "Trade Value" "Fee" "Closed PNL"] sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row 8 error)

       loading?
       (empty-table-row 8 "Loading trade history...")

       (seq rows)
       (for [{:keys [time-ms coin side size price trade-value fee closed-pnl]} rows]
         (let [side* (normalize-side side)]
           ^{:key (str "fill-" time-ms "-" coin "-" size "-" price)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side*)}
             [:span {:class [(side-coin-tone-class side*)]}
              (or coin "—")]]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side*)])}
             (or side "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price price)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-size size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-currency trade-value)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-currency fee)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class closed-pnl)])}
             (format-currency closed-pnl)]]))

       :else
       (empty-table-row 8 "No recent fills."))]]])

(defn- funding-history-table [rows loading? error sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[920px]" "border-collapse"]}
    (table-header :funding-history ["Time" "Coin" "Funding Rate" "Position Size" "Payment"] sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row 5 error)

       loading?
       (empty-table-row 5 "Loading funding history...")

       (seq rows)
       (for [{:keys [time-ms coin funding-rate position-size payment]} rows]
         (let [side (if (number? position-size)
                      (if (neg? position-size) :short :long)
                      nil)]
           ^{:key (str "funding-" time-ms "-" coin "-" funding-rate "-" payment)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side)}
             [:span {:class [(side-coin-tone-class side)]}
              (or coin "—")]]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-funding-rate funding-rate)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side)])} (format-size position-size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class payment)])}
             (format-currency payment)]]))

       :else
       (empty-table-row 5 "No funding history available."))]]])

(defn- order-history-table [rows loading? error sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1040px]" "border-collapse"]}
    (table-header :order-history ["Time" "Coin" "Side" "Type" "Size" "Price" "Status"] sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row 7 error)

       loading?
       (empty-table-row 7 "Loading order history...")

       (seq rows)
       (for [{:keys [time-ms coin side type size price status]} rows]
         (let [side* (normalize-side side)]
           ^{:key (str "order-history-" time-ms "-" coin "-" side "-" size)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side*)}
             [:span {:class [(side-coin-tone-class side*)]}
              (or coin "—")]]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side*)])} (or side "—")]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or type "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-size size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price price)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (status-tone-class status)])}
             (or status "—")]]))

       :else
       (empty-table-row 7 "No order history available."))]]])

(defn- ledger-table [rows loading? error sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[880px]" "border-collapse"]}
    (table-header :deposits-withdrawals ["Time" "Type" "Amount" "Tx Hash"] sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row 4 error)

       loading?
       (empty-table-row 4 "Loading deposits and withdrawals...")

       (seq rows)
       (for [{:keys [time-ms type-label amount hash]} rows]
         (let [signed-amount (if (= (some-> type-label str/lower-case) "withdraw")
                               (when (number? amount) (- (js/Math.abs amount)))
                               amount)]
           ^{:key (str "ledger-" time-ms "-" hash)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (ledger-type-tone-class type-label)])}
             (or type-label "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class signed-amount)])}
             (format-currency amount)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#97fce4]"])
                  :title hash}
             [:span {:class (interactive-value-class)}
              (short-hash hash)]]]))

       :else
       (empty-table-row 4 "No deposits or withdrawals available."))]]])

(defn- depositors-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[980px]" "border-collapse"]}
    (table-header :depositors ["Depositor" "Vault Amount" "Unrealized PNL" "All-time PNL" "Days Following"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [address vault-amount unrealized-pnl all-time-pnl days-following]} rows]
         ^{:key (str "depositor-" address)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or (wallet/short-addr address) "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-currency vault-amount)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class unrealized-pnl)])}
           (format-currency unrealized-pnl)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class all-time-pnl)])}
           (format-currency all-time-pnl)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (if (number? days-following)
             (str days-following)
             "—")]])
       (empty-table-row 5 "No depositors available."))]]])

(defn- activity-panel [{:keys [selected-activity-tab
                               activity-tabs
                               performance-metrics
                               activity-direction-filter
                               activity-filter-open?
                               activity-filter-options
                               activity-sort-state-by-tab
                               activity-loading
                               activity-errors
                               activity-balances
                               activity-positions
                               activity-open-orders
                               activity-twaps
                               activity-fills
                               activity-funding-history
                               activity-order-history
                               activity-deposits-withdrawals
                               activity-depositors]}]
  (let [filter-enabled? (activity-direction-filter-enabled? selected-activity-tab)
        sort-state-by-tab (or activity-sort-state-by-tab {})
        selected-filter* (or activity-direction-filter :all)]
    [:section {:class ["rounded-2xl"
                       "border"
                       "border-[#1b3237]"
                       "bg-[#071820]"
                       "overflow-hidden"
                       "w-full"]}
     [:div {:class ["flex" "items-center" "justify-between" "border-b" "border-[#1b3237]" "bg-transparent" "gap-2" "pr-3"]}
      [:div {:class ["min-w-0" "overflow-x-auto"]}
       [:div {:class ["flex" "min-w-max" "items-center"]}
        (for [tab activity-tabs]
          ^{:key (str "activity-tab-" (name (:value tab)))}
          (activity-tab-button tab selected-activity-tab))]]
      [:div {:class ["relative" "hidden" "md:flex" "items-center"]}
       [:button {:type "button"
                 :disabled (not filter-enabled?)
                 :class (into ["inline-flex"
                               "items-center"
                               "gap-1"
                               "text-xs"
                               "text-[#949e9c]"
                               "transition-colors"]
                              (if filter-enabled?
                                ["cursor-pointer" "hover:text-[#f6fefd]"]
                                ["cursor-not-allowed" "opacity-50"]))
                 :on {:click [[:actions/toggle-vault-detail-activity-filter-open]]}}
        "Filter"
        [:span "⌄"]]
       (when (and filter-enabled?
                  activity-filter-open?)
         [:div {:class ["absolute"
                        "right-0"
                        "top-full"
                        "z-30"
                        "mt-1.5"
                        "w-32"
                        "overflow-hidden"
                        "rounded-md"
                        "border"
                        "border-[#204046]"
                        "bg-[#081f29]"
                        "shadow-lg"]}
          (for [{:keys [value label]} activity-filter-options]
            ^{:key (str "vault-detail-activity-filter-" (name value))}
            [:button {:type "button"
                      :class (into ["flex"
                                    "w-full"
                                    "items-center"
                                    "justify-between"
                                    "px-3"
                                    "py-2"
                                    "text-left"
                                    "text-sm"
                                    "text-[#c7d5da]"
                                    "transition-colors"
                                    "hover:bg-[#0e2630]"
                                    "hover:text-[#f6fefd]"]
                                   (when (= value selected-filter*)
                                     ["bg-[#0e2630]" "text-[#f6fefd]"]))
                      :on {:click [[:actions/set-vault-detail-activity-direction-filter value]]}}
             [:span label]
             (when (= value selected-filter*)
               [:span {:class ["text-xs" "text-[#66e3c5]"]}
                "●"])])])]]
     (case selected-activity-tab
       :performance-metrics (performance-metrics-card performance-metrics)
       :balances (balances-table activity-balances (get sort-state-by-tab :balances))
       :positions (positions-table activity-positions (get sort-state-by-tab :positions))
       :open-orders (open-orders-table activity-open-orders (get sort-state-by-tab :open-orders))
       :twap (twap-table activity-twaps (get sort-state-by-tab :twap))
       :trade-history (fills-table activity-fills
                                   (true? (:trade-history activity-loading))
                                   (:trade-history activity-errors)
                                   (get sort-state-by-tab :trade-history))
       :funding-history (funding-history-table activity-funding-history
                                               (true? (:funding-history activity-loading))
                                               (:funding-history activity-errors)
                                               (get sort-state-by-tab :funding-history))
       :order-history (order-history-table activity-order-history
                                           (true? (:order-history activity-loading))
                                           (:order-history activity-errors)
                                           (get sort-state-by-tab :order-history))
       :deposits-withdrawals (ledger-table activity-deposits-withdrawals
                                           (true? (:deposits-withdrawals activity-loading))
                                           (:deposits-withdrawals activity-errors)
                                           (get sort-state-by-tab :deposits-withdrawals))
       :depositors (depositors-table activity-depositors (get sort-state-by-tab :depositors))
       [:div {:class ["px-4" "py-6" "text-sm" "text-[#8ea2aa]"]}
        "This activity stream is not available yet for vaults."])]))

(defn vault-detail-view
  [state]
  (let [{:keys [kind
                vault-address
                invalid-address?
                loading?
                error
                relationship
                tabs
                selected-tab
                metrics
                chart] :as vm} (detail-vm/vault-detail-vm state)
        resolved-name (resolved-vault-name (:name vm) vault-address)
        show-name-skeleton? (and loading?
                                 (nil? resolved-name))
        vault-name (or resolved-name
                       (wallet/short-addr vault-address)
                       "Vault")
        month-return (:past-month-return metrics)
        month-return-accent (cond
                              (and (number? month-return) (pos? month-return)) :positive
                              (and (number? month-return) (neg? month-return)) :negative
                              :else nil)]
    [:div
     {:class ["w-full" "app-shell-gutter" "py-4" "space-y-4"]
      :data-parity-id "vault-detail-root"}
     (cond
       invalid-address?
       [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4" "text-sm" "text-trading-text-secondary"]}
        "Invalid vault address."]

       (not= kind :detail)
       [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4" "text-sm" "text-trading-text-secondary"]}
        "Select a vault to view details."]

       :else
       [:div {:class ["space-y-4"]}
        [:section {:class ["rounded-2xl"
                           "border"
                           "border-[#19423e]"
                           "px-4"
                           "py-4"
                           "lg:px-6"
                           "bg-[radial-gradient(circle_at_82%_18%,rgba(41,186,147,0.20),transparent_42%),linear-gradient(180deg,#06382f_0%,#082029_56%,#051721_100%)]"]}
         [:div {:class ["flex" "flex-col" "gap-3" "lg:flex-row" "lg:items-start" "lg:justify-between"]}
          [:div {:class ["min-w-0"]}
           [:div {:class ["mb-2" "flex" "items-center" "gap-2" "text-xs" "text-[#8da5aa]"]}
            [:button {:type "button"
                      :class ["hover:text-trading-text"]
                      :on {:click [[:actions/navigate "/vaults"]]}}
             "Vaults"]
            [:span ">"]
            [:span {:class ["truncate"]}
             (if show-name-skeleton?
               [:span {:class ["inline-flex" "items-center"]
                       :data-role "vault-detail-breadcrumb-skeleton"}
                (loading-skeleton-block ["h-3"
                                         "w-24"
                                         "sm:w-28"])]
               vault-name)]]
           [:h1 {:class ["text-[34px]"
                         "leading-[1.02]"
                         "font-semibold"
                         "tracking-tight"
                         "text-trading-text"
                         "sm:text-[44px]"
                         "xl:text-[56px]"
                         "break-words"]}
            (if show-name-skeleton?
              [:span {:class ["inline-block" "align-baseline" "max-w-[18ch]"]
                      :data-role "vault-detail-title-skeleton"}
               (loading-skeleton-block ["h-[0.96em]"
                                        "w-[10ch]"
                                        "sm:w-[11ch]"])
               [:span {:class ["sr-only"]}
                "Loading vault name"]]
              vault-name)]
           [:div {:class ["mt-1.5" "num" "text-sm" "text-[#89a1a8]"]}
            (or (wallet/short-addr vault-address) vault-address)]
           (relationship-links {:relationship relationship})]
          [:div {:class ["grid" "w-full" "grid-cols-2" "gap-2" "lg:w-auto" "lg:flex"]}
           [:button
            {:type "button"
             :disabled true
             :class ["rounded-lg"
                     "border"
                     "border-[#2a4b4b]"
                     "bg-[#08202a]/55"
                     "px-4"
                     "py-2"
                     "text-sm"
                     "text-[#6c8e93]"
                     "opacity-70"
                     "cursor-not-allowed"]}
            "Withdraw"]
           [:button
            {:type "button"
             :disabled true
             :class ["rounded-lg"
                     "border"
                     "border-[#2a4b4b]"
                     "bg-[#08202a]/55"
                     "px-4"
                     "py-2"
                     "text-sm"
                     "text-[#6c8e93]"
                     "opacity-70"
                     "cursor-not-allowed"]}
            "Deposit"]]]
         [:div {:class ["mt-4" "grid" "grid-cols-2" "gap-2.5" "lg:mt-5" "lg:gap-3" "xl:grid-cols-4"]}
          (metric-card {:label "TVL"
                        :value (format-currency (:tvl metrics) {:missing "$0.00"})})
          (metric-card {:label "Past Month Return"
                        :value (format-percent month-return {:signed? false
                                                            :decimals 0})
                        :accent month-return-accent})
          (metric-card {:label "Your Deposits"
                        :value (format-currency (:your-deposit metrics))})
          (metric-card {:label "All-time Earned"
                        :value (format-currency (:all-time-earned metrics))})]]

        (when loading?
          [:div {:class ["rounded-xl" "border" "border-[#1f3d3d]" "bg-[#081820]" "px-4" "py-2.5" "text-sm" "text-[#8fa6ad]"]}
           "Loading vault details..."])

        (when error
          [:div {:class ["rounded-lg" "border" "border-red-500/40" "bg-red-900/20" "px-3" "py-2" "text-sm" "text-red-200"]}
           error])

        [:div {:class ["grid" "gap-3" "lg:grid-cols-[minmax(280px,1fr)_minmax(0,3fr)]"]}
         [:section {:class ["rounded-2xl"
                            "border"
                            "border-[#1b393a]"
                            "bg-[#071820]"]}
          [:div {:class ["flex" "items-center" "border-b" "border-[#1f3b3c]"]}
           (for [tab tabs]
             ^{:key (str "vault-detail-tab-" (name (:value tab)))}
             (detail-tab-button tab selected-tab))]
          (render-tab-panel vm)]

         (let [axis-kind (:axis-kind chart)
               y-ticks (:y-ticks chart)
               selected-series (:selected-series chart)
               series (:series chart)
               returns-benchmark* (add-benchmark-chip-colors (:returns-benchmark chart) series)
               y-axis-width (y-axis-gutter-width axis-kind y-ticks)
               plot-left (+ y-axis-width 10)
               point-count (count (:points chart))
               hovered-index (get-in chart [:hover :index])
               hovered-point (get-in chart [:hover :point])
               hover-active? (boolean (get-in chart [:hover :active?]))
               hover-line-left-pct (when hover-active?
                                    (* 100 (:x-ratio hovered-point)))
               hover-tooltip-top-pct (when hover-active?
                                      (clamp-number (- (* 100 (:y-ratio hovered-point)) 8)
                                                    8
                                                    92))
               hover-tooltip-right? (when hover-active?
                                      (> hover-line-left-pct 74))
               hover-benchmark-values (when hover-active?
                                       (chart-tooltip-benchmark-values axis-kind
                                                                       hovered-index
                                                                       series))
               hover-tooltip (when hover-active?
                               (chart-tooltip-model (:selected-timeframe chart)
                                                    axis-kind
                                                    hovered-point))]
           [:section {:class ["rounded-2xl"
                              "border"
                              "border-[#1b393a]"
                              "bg-[#071820]"
                              "p-3"]}
            [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2" "border-b" "border-[#1f3b3c]" "pb-2"]}
             [:div {:class ["flex" "items-center" "gap-2"]}
              (for [{:keys [value label]} (:series-tabs chart)]
                ^{:key (str "chart-series-" (name value))}
                (chart-series-button {:value value
                                      :label label}
                                     selected-series))]
             [:div {:class ["ml-auto" "flex" "items-center" "gap-2"]}
              (when (= selected-series :returns)
                (returns-benchmark-selector returns-benchmark*))
              (chart-timeframe-menu {:timeframe-options (:timeframe-options chart)
                                     :selected-timeframe (:selected-timeframe chart)})]]
            [:div {:class ["space-y-2"]}
             [:div {:class ["relative" "mt-3" "h-[260px]"]}
              [:div {:class ["absolute" "left-0" "top-0" "bottom-0"]
                     :style {:width (str y-axis-width "px")}}
               (for [{:keys [value y-ratio]} y-ticks]
                 ^{:key (str "vault-chart-tick-" y-ratio "-" value)}
                 [:span {:class ["absolute"
                                 "right-2"
                                 "-translate-y-1/2"
                                 "num"
                                 "text-right"
                                 "text-xs"
                                 "text-[#8aa0a7]"]
                         :style {:top (str (* 100 y-ratio) "%")}}
                  (format-chart-axis-value axis-kind value)])
               [:div {:class ["absolute"
                              "right-0"
                              "top-0"
                              "bottom-0"
                              "border-l"
                              "border-[#1f3b3c]"]}]
               (for [{:keys [y-ratio]} y-ticks]
                 ^{:key (str "vault-chart-axis-tick-" y-ratio)}
                 [:div {:class ["absolute"
                                "right-0"
                                "w-1.5"
                                "border-t"
                                "border-[#1f3b3c]"]
                        :style {:top (str (* 100 y-ratio) "%")}}])]
              [:div {:class ["absolute" "right-2" "top-0" "bottom-0" "cursor-crosshair"]
                     :style {:left (str plot-left "px")}
                     :on {:mousemove [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                          :mouseenter [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                          :pointermove [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                          :pointerenter [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                          :mouseleave [[:actions/clear-vault-detail-chart-hover]]
                          :pointerleave [[:actions/clear-vault-detail-chart-hover]]
                          :mouseout [[:actions/clear-vault-detail-chart-hover]]}}
               [:svg {:viewBox "0 0 100 100"
                      :preserveAspectRatio "none"
                      :class ["h-full" "w-full"]}
                [:line {:x1 0
                        :x2 100
                        :y1 100
                        :y2 100
                        :stroke "rgba(140, 157, 165, 0.30)"
                        :stroke-width 0.8
                        :vector-effect "non-scaling-stroke"}]
                (for [{series-id :id :as series-entry} (or series [])]
                  ^{:key (str "vault-detail-chart-area-" (name series-id))}
                  (chart-series-area-layers series-entry))
                (for [{series-id :id :as series-entry} (or series [])]
                  ^{:key (str "vault-detail-chart-path-" (name series-id))}
                  (chart-series-path series-entry))]
               (when hover-active?
                 [:div {:class ["absolute"
                                "top-0"
                                "bottom-0"
                                "w-px"
                                "-translate-x-1/2"
                                "pointer-events-none"
                                "bg-[#9fb3be]"
                                "z-10"]
                        :style {:left (str hover-line-left-pct "%")}}])
               (when hover-active?
                 [:div {:class ["absolute"
                                "pointer-events-none"
                                "min-w-[188px]"
                                "rounded-xl"
                                "border"
                                "px-3"
                                "py-2"
                                "shadow-lg"
                                "z-20"]
                        :data-role "vault-detail-chart-hover-tooltip"
                        :style {:left (str hover-line-left-pct "%")
                                :top (str hover-tooltip-top-pct "%")
                                :transform (if hover-tooltip-right?
                                             "translate(calc(-100% - 8px), -50%)"
                                             "translate(8px, -50%)")
                                :white-space "nowrap"
                                :border-color "rgba(98, 114, 130, 0.65)"
                                :background "linear-gradient(138deg, rgba(24, 35, 47, 0.95) 0%, rgba(16, 25, 38, 0.95) 56%, rgba(43, 36, 25, 0.86) 100%)"}}
                  [:div {:class ["num"
                                 "text-[12px]"
                                 "font-medium"
                                 "leading-4"
                                 "text-[#8ea1b3]"]}
                   (:timestamp hover-tooltip)]
                  [:div {:class ["mt-2"
                                 "grid"
                                 "grid-cols-[1fr_auto]"
                                 "items-center"
                                 "gap-3"]}
                   [:span {:class ["text-[12px]"
                                   "font-medium"
                                   "leading-4"
                                   "text-[#909fac]"]}
                    (:metric-label hover-tooltip)]
                   [:span {:class (into ["num"
                                         "text-[16px]"
                                         "font-semibold"
                                         "leading-[1]"
                                         "tracking-tight"]
                                        (:value-classes hover-tooltip))}
                    (:metric-value hover-tooltip)]]
                  (when (seq hover-benchmark-values)
                    [:div {:class ["mt-1.5" "space-y-1"]}
                     (for [{:keys [coin label value stroke]} hover-benchmark-values]
                       ^{:key (str "vault-detail-chart-hover-tooltip-benchmark-row-" coin)}
                       [:div {:class ["grid"
                                      "grid-cols-[1fr_auto]"
                                      "items-center"
                                      "gap-3"]
                              :data-role (str "vault-detail-chart-hover-tooltip-benchmark-row-" coin)}
                        [:span {:class ["text-[12px]"
                                        "font-medium"
                                        "leading-4"
                                        "text-[#909fac]"]}
                         label]
                        [:span {:class ["num"
                                        "text-sm"
                                        "font-semibold"
                                        "leading-[1.1]"
                                        "tracking-tight"]
                                :data-role (str "vault-detail-chart-hover-tooltip-benchmark-value-" coin)
                                :style {:color stroke}}
                         value]])])])]]
             (chart-legend series)
             (when (= selected-series :returns)
               (returns-benchmark-chip-rail returns-benchmark*))]])]

        (activity-panel vm)])]))
