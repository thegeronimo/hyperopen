(ns hyperopen.views.active-asset.funding-tooltip-sections
  "Section renderers for the funding tooltip panel. Every section reads the
  model built by hyperopen.active-asset.funding-policy/funding-tooltip-model."
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.active-asset.funding-history-chart :as history-chart]))

(def ^:private section-heading-classes
  ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-ho-text-muted"])

(def ^:private stat-card-classes
  ["min-w-0" "rounded-md" "bg-ho-surface/60" "px-2" "py-1.5"])

(defn- usd-text
  [value]
  (if (number? value)
    (str "$" (fmt/format-fixed-number (js/Math.abs value) 2))
    "—"))

(defn- signed-pct-text
  [value decimals]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 1e-10) 0 value)]
      (str (cond (pos? normalized) "+" (neg? normalized) "-" :else "")
           (fmt/format-fixed-number (js/Math.abs normalized) decimals)
           "%"))
    "—"))

(defn- margin-pct-text
  [value]
  (cond
    (not (number? value)) "—"
    (>= value 1000) (str (fmt/format-fixed-number value 0) "%")
    (>= value 10) (str (fmt/format-fixed-number value 1) "%")
    :else (str (fmt/format-fixed-number value 2) "%")))

(defn- leverage-text
  [leverage]
  (when (number? leverage)
    (str (fmt/format-fixed-number leverage (if (== leverage (js/Math.round leverage)) 0 1)) "×")))

(defn- verdict-tone-class
  [verdict]
  (case verdict
    :pays "text-error"
    :receives "text-success"
    "text-ho-text"))

(defn- payment-tone-class
  [payment]
  (cond
    (and (number? payment) (neg? payment)) "text-error"
    (and (number? payment) (pos? payment)) "text-success"
    :else "text-ho-text"))

(defn- direction-word
  [direction]
  (if (= direction :short) "short" "long"))

(defn section-heading
  [text & [right]]
  [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
   [:h4 {:class section-heading-classes} text]
   right])

(defn- countdown-text
  [countdown]
  (when (string? countdown)
    (str/replace countdown #"^00:" "")))

(defn header-section
  [{:keys [position-base-symbol hourly-rate]} countdown]
  [:div {:class ["flex" "items-baseline" "justify-between" "gap-2"]
         :data-role "funding-tooltip-header"}
   [:h3 {:class ["text-sm" "font-semibold" "text-ho-text"]}
    (str position-base-symbol " funding")]
   [:span {:class ["num" "text-xs" "text-ho-text-muted" "whitespace-nowrap"]}
    (str (signed-pct-text hourly-rate 5) " / hr")
    (when-let [text (countdown-text countdown)]
      [:span " · next in "
       [:span {:class ["font-semibold" "text-ho-text"]} text]])]])

;; ---------------------------------------------------------------------------
;; Verdict, so-far, projections

(defn verdict-section
  [{:keys [verdict direction margin-per-day-pct leverage]}]
  (when verdict
    [:div {:class (into ["flex" "flex-col" "gap-0.5" "rounded-lg" "px-3" "py-2"]
                        (case verdict
                          :pays ["bg-ho-sell/10"]
                          :receives ["bg-ho-buy/10"]
                          ["bg-ho-surface/60"]))
           :data-role "funding-tooltip-verdict"
           :data-verdict (name verdict)}
     [:span {:class ["text-xs" "text-ho-text-secondary"]}
      (str "At today's rate, a " (direction-word direction) " position")]
     [:span {:class ["text-base" "font-semibold" (verdict-tone-class verdict)]}
      (case verdict
        :pays "Pays funding every hour"
        :receives "Earns funding every hour"
        "Pays no funding right now")]
     (when (and (number? margin-per-day-pct) (not= verdict :neutral))
       [:span {:class ["num" "text-xs" "text-ho-text"]}
        "≈ "
        [:strong {:class [(verdict-tone-class verdict)]}
         (str (margin-pct-text margin-per-day-pct) " of your margin")]
        (str " per day" (when-let [lev (leverage-text leverage)] (str " at " lev)))])]))

(defn since-open-section
  [{:keys [since-open-funding]}]
  (when (number? since-open-funding)
    [:div {:class ["flex" "items-baseline" "justify-between" "gap-3" "rounded-md"
                   "bg-ho-surface/60" "px-3" "py-2"]
           :data-role "funding-tooltip-since-open"}
     [:div {:class ["flex" "flex-col"]}
      [:span {:class ["text-sm" "text-ho-text"]} "Funding so far"]
      [:span {:class ["text-xs" "text-ho-text-muted"]} "Since this position opened"]]
     [:span {:class ["num" "text-sm" "font-semibold" "whitespace-nowrap"
                     (payment-tone-class since-open-funding)]}
      (cond
        (< (js/Math.abs since-open-funding) 0.005) "None yet"
        (neg? since-open-funding) (str "Paid " (usd-text since-open-funding))
        :else (str "Received " (usd-text since-open-funding)))]]))

(def ^:private projection-rate-decimals
  {"next-24h" 4 "next-30d" 3 "apy" 2})

(defn- breakeven-text
  [{:keys [kind move pct days mark target-price]} base-symbol]
  (let [pct-text (str (fmt/format-fixed-number pct 2) "%")
        target-text (fmt/format-trade-price target-price)
        mark-text (fmt/format-trade-price mark)]
    (if (= kind :cover)
      (str "To cover " days " days of funding, " base-symbol " needs to "
           (name move) " " pct-text ", from " mark-text " to about " target-text ".")
      (str "Over " days " days, funding offsets a " pct-text " move against you ("
           base-symbol " at about " target-text ")."))))

(defn projections-section
  [{:keys [projection-rows verdict margin breakeven position-base-symbol]}]
  (let [show-margin? (number? margin)
        grid (if show-margin?
               ["grid-cols-[minmax(0,1fr)_auto_4.5rem]"]
               ["grid-cols-[minmax(0,1fr)_auto]"])]
    [:div {:class ["flex" "flex-col" "gap-1.5"]
           :data-role "funding-tooltip-projections"}
     (section-heading "Projected at today's rate")
     [:div {:class (into ["grid" "gap-x-3" "text-xs" "text-ho-text-muted"] grid)}
      [:span]
      [:span {:class ["text-right"]}
       (case verdict :pays "You pay" :receives "You earn" "Payment")]
      (when show-margin? [:span {:class ["text-right"]} "Of margin"])]
     (for [{:keys [id label rate payment margin-pct]} projection-rows]
       ^{:key id}
       [:div {:class (into ["grid" "items-baseline" "gap-x-3" "border-b" "border-ho-border/60" "pb-1"]
                           grid)
              :data-role (str "funding-projection-" id)}
        [:div {:class ["flex" "min-w-0" "flex-col"]}
         [:span {:class ["text-sm" "text-ho-text"]} label]
         [:span {:class ["num" "text-xs" "text-ho-text-muted"]}
          (str (signed-pct-text rate (get projection-rate-decimals id 4))
               (when (= id "apy") " APY"))]]
        [:span {:class ["num" "text-sm" "font-semibold" "text-right" "whitespace-nowrap"
                        (payment-tone-class payment)]}
         (usd-text payment)]
        (when show-margin?
          [:span {:class ["num" "text-sm" "text-right" "text-ho-text-secondary"]}
           (margin-pct-text margin-pct)])])
     (when breakeven
       [:p {:class ["mt-1" "rounded-md" "border" "border-ho-border-accent-muted" "px-2.5" "py-1.5"
                    "text-xs" "text-ho-text-secondary"]
            :data-role "funding-tooltip-breakeven"}
        (breakeven-text breakeven position-base-symbol)])]))

;; ---------------------------------------------------------------------------
;; Why this rate

(defn- premium-copy
  [premium-apy]
  (cond
    (< (js/Math.abs premium-apy) 0.005)
    "The perp is trading close to spot, so only the fixed baseline is charged."

    (pos? premium-apy)
    "The perp trades above spot, so longs pay extra to pull it back toward spot."

    :else
    "The perp trades below spot, which offsets the baseline."))

(defn rate-breakdown-section
  [{:keys [rate-breakdown]}]
  (when-let [{:keys [total-apy baseline-apy premium-apy]} rate-breakdown]
    (let [base-share (/ (js/Math.abs baseline-apy)
                        (max 1e-9 (+ (js/Math.abs baseline-apy) (js/Math.abs premium-apy))))]
      [:div {:class ["flex" "flex-col" "gap-1.5"]
             :data-role "funding-tooltip-rate-breakdown"}
       (section-heading "Why this rate"
                        [:span {:class ["num" "text-xs" "font-semibold" "text-ho-text"]}
                         (str (signed-pct-text total-apy 2) " APY")])
       [:div {:class ["flex" "h-2" "overflow-hidden" "rounded-full" "bg-ho-surface"]
              :aria-hidden "true"}
        [:div {:class ["bg-ho-text-muted"] :style {:width (str (* 100 base-share) "%")}}]
        [:div {:class ["bg-ho-warn"] :style {:width (str (* 100 (- 1 base-share)) "%")}}]]
       [:div {:class ["grid" "grid-cols-[minmax(0,1fr)_auto]" "gap-x-3" "gap-y-0.5" "text-xs"]}
        [:span {:class ["flex" "items-center" "gap-1.5" "text-ho-text-secondary"]}
         [:span {:class ["h-2" "w-2" "rounded-sm" "bg-ho-text-muted"]}]
         "Baseline interest (fixed)"]
        [:span {:class ["num" "text-right" "text-ho-text"]} (signed-pct-text baseline-apy 2)]
        [:span {:class ["flex" "items-center" "gap-1.5" "text-ho-text-secondary"]}
         [:span {:class ["h-2" "w-2" "rounded-sm" "bg-ho-warn"]}]
         "Premium (perp vs spot)"]
        [:span {:class ["num" "text-right" "text-ho-text"]} (signed-pct-text premium-apy 2)]]
       [:p {:class ["text-xs" "text-ho-text-muted"]} (premium-copy premium-apy)]])))

;; ---------------------------------------------------------------------------
;; 30-day history, 1-year outlook, lag strip

(defn- stat-card
  [label value]
  [:div {:class stat-card-classes}
   [:div {:class ["text-xs" "text-ho-text-muted"]} label]
   [:div {:class ["num" "text-sm" "font-semibold" "text-ho-text" "whitespace-nowrap"]} value]])

(defn history-section
  [{:keys [history-chart predictability-loading? predictability-error]} chart-style]
  [:div {:class ["flex" "flex-col" "gap-2"]
         :data-role "funding-tooltip-history"}
   (section-heading "Last 30 days"
                    [:div {:class ["flex" "items-center" "gap-2"]}
                     [:span {:class ["text-xs" "text-ho-text-muted"]} "APY, daily"]
                     (history-chart/chart-style-toggle chart-style)])
   (cond
     predictability-loading?
     [:div {:class ["text-xs" "text-ho-text-muted"]} "Loading 30d stats..."]

     (seq predictability-error)
     [:div {:class ["text-xs" "text-error"]} "Unable to load 30d stats"]

     (seq (keep :apy (:points history-chart)))
     (let [{:keys [min-apy max-apy sd-apy positive-days days-with-data]} history-chart]
       [:div {:class ["flex" "flex-col" "gap-2"]}
        (history-chart/funding-history-chart history-chart chart-style)
        [:div {:class ["grid" "grid-cols-3" "gap-1.5"]}
         (stat-card "Range" (str (fmt/format-fixed-number min-apy 1) "–"
                                 (fmt/format-fixed-number max-apy 1) "%"))
         (stat-card "Typical day" (if (number? sd-apy)
                                    (str "±" (fmt/format-fixed-number sd-apy 1) "%")
                                    "—"))
         (stat-card "Longs paid" (str positive-days " of " days-with-data " d"))]])

     :else
     [:div {:class ["num" "text-xs" "text-ho-text-muted"]} "—"])])

(defn year-outlook-section
  [{:keys [year-outlook predictability-loading? predictability-error]}]
  (when (and (map? year-outlook)
             (not predictability-loading?)
             (not (seq predictability-error)))
    (let [{:keys [expected lower upper]} year-outlook]
      [:div {:class ["flex" "flex-col" "gap-1"]
             :data-role "funding-tooltip-year-outlook"}
       (section-heading "If the last 30 days repeat for a year")
       [:div {:class ["flex" "items-baseline" "justify-between" "gap-3"]}
        [:span {:class ["text-sm" "text-ho-text"]}
         (if (neg? expected) "You'd pay about" "You'd earn about")]
        [:span {:class ["num" "text-base" "font-semibold" (payment-tone-class expected)]}
         (usd-text expected)]]
       (when (and (number? lower) (number? upper))
         [:span {:class ["num" "text-xs" "text-ho-text-muted"]}
          (if (= (neg? lower) (neg? upper))
            (str "Likely range " (usd-text (min (js/Math.abs lower) (js/Math.abs upper)))
                 " to " (usd-text (max (js/Math.abs lower) (js/Math.abs upper)))
                 " (2 in 3 odds)")
            (str "Likely anywhere from paying " (usd-text lower)
                 " to earning " (usd-text upper) " (2 in 3 odds)"))])
       [:p {:class ["text-xs" "text-ho-text-muted"]}
        "Daily swings mostly cancel out over a year. The bigger risk is the rate regime changing."]])))

(def ^:private lag-verdict-copy
  {:barely {:pill "Barely"
            :pill-classes ["bg-ho-warn" "text-ho-bg-deep"]
            :text "Today's rate tells you little about the rate a few days out. Treat it as a snapshot, not a forecast."}
   :somewhat {:pill "Somewhat"
              :pill-classes ["bg-ho-info/20" "text-ho-info"]
              :text "Rates tend to carry over for a day or so, then drift."}
   :strongly {:pill "Strongly"
              :pill-classes ["bg-ho-buy/20" "text-ho-buy"]
              :text "Rates tend to persist, so today's rate is a fair guide to the next few days."}})

(defn- lag-row
  [{:keys [lag-days label value noise significant?]}]
  (let [half-width (* 50 (min 1 (js/Math.abs value)))
        left (if (neg? value) (- 50 half-width) 50)]
    [:div {:class ["grid" "grid-cols-[5rem_minmax(0,1fr)_2.5rem]" "items-center" "gap-2"]
           :data-role (str "funding-lag-" lag-days "d")}
     [:span {:class ["text-xs" "text-ho-text-secondary"]} label]
     [:div {:class ["relative" "h-3.5"]}
      [:div {:class ["absolute" "inset-y-0" "rounded-sm" "bg-ho-surface"]
             :style {:left (str (- 50 (* 50 noise)) "%")
                     :width (str (* 100 noise) "%")}}]
      [:div {:class ["absolute" "-inset-y-0.5" "w-px" "bg-ho-border"]
             :style {:left "50%"}}]
      [:div {:class ["absolute" "inset-y-1" "rounded-sm"
                     (if significant? "bg-ho-text" "bg-ho-text-dim")]
             :style {:left (str left "%")
                     :width (str half-width "%")}}]]
     [:span {:class ["num" "text-right" "text-xs"
                     (if significant? "text-ho-text" "text-ho-text-muted")]}
      (str (when (pos? value) "+") (fmt/format-fixed-number value 2))]]))

(defn lag-strip-section
  [{:keys [lag-strip predictability-loading? predictability-error]}]
  (when (and (map? lag-strip)
             (not predictability-loading?)
             (not (seq predictability-error)))
    (let [{:keys [verdict rows]} lag-strip
          {:keys [pill pill-classes text]} (get lag-verdict-copy verdict)]
      [:div {:class ["flex" "flex-col" "gap-1.5"]
             :data-role "funding-tooltip-lag-strip"}
       (section-heading "Does today predict later?"
                        (when pill
                          [:span {:class (into ["rounded-full" "px-2" "py-0.5" "text-xs" "font-semibold"]
                                               pill-classes)}
                           pill]))
       (when text
         [:p {:class ["text-xs" "text-ho-text-secondary"]} text])
       (for [row rows]
         ^{:key (str "lag-" (:lag-days row))}
         (lag-row row))
       [:div {:class ["grid" "grid-cols-[5rem_minmax(0,1fr)_2.5rem]" "gap-2" "text-xs" "text-ho-text-muted"]}
        [:span]
        [:div {:class ["flex" "justify-between"]}
         [:span "reverses"] [:span "noise"] [:span "persists"]]
        [:span]]])))
