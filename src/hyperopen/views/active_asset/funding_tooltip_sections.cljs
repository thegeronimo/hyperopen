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
   [:h4 {:class (conj section-heading-classes "whitespace-nowrap")} text]
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
  [{:keys [kind move pct days mark target-price]} base-symbol expected?]
  (let [pct-text (str (fmt/format-fixed-number pct 2) "%")
        target-text (fmt/format-trade-price target-price)
        mark-text (fmt/format-trade-price mark)
        funding-word (if expected? "expected funding" "funding")]
    (if (= kind :cover)
      (str "To cover " days " days of " funding-word ", " base-symbol " needs to "
           (name move) " " pct-text ", from " mark-text " to about " target-text ".")
      (str "Over " days " days, " funding-word " offsets a " pct-text " move against you ("
           base-symbol " at about " target-text ")."))))

(defn- basis-button
  [basis current label]
  (let [active? (= basis current)]
    [:button {:type "button"
              :aria-pressed (if active? "true" "false")
              :data-role (str "funding-projection-basis-" (name basis))
              :class (into ["h-6" "whitespace-nowrap" "rounded" "px-2" "text-xs" "font-semibold" "transition-colors"
                            "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-ho-accent"]
                           (if active?
                             ["bg-ho-surface-raised" "text-ho-text"]
                             ["text-ho-text-muted" "hover:text-ho-text"]))
              :on {:click [[:actions/set-funding-projection-basis (name basis)]]}}
     label]))

(defn- basis-toggle
  [current]
  [:div {:role "group"
         :aria-label "Projection basis"
         :class ["flex" "gap-0.5" "rounded-md" "border" "border-ho-border-accent-muted"
                 "bg-ho-bg-deep" "p-0.5"]}
   (basis-button :expected current "Expected")
   (basis-button :today current "Today's rate")])

(defn- projection-note
  [data-role tone-classes icon text]
  [:div {:class (into ["flex" "items-start" "gap-2" "rounded-md" "px-2.5" "py-2"] tone-classes)
         :data-role data-role}
   [:svg {:viewBox "0 0 16 16" :width 14 :height 14 :fill "none" :stroke "currentColor"
          :stroke-width 1.6 :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"
          :class ["mt-0.5" "shrink-0" "text-ho-warn"]}
    icon]
   [:span {:class ["text-xs"]} text]])

(def ^:private fade-note-text
  "Rate spikes usually fade within a day, so 30-day and 1-year figures use the 30-day average instead of today's rate.")

(def ^:private scenario-note-text
  "A scenario, not a forecast. Over the past year, today's rate missed the next 30 days' funding by about twice as much as the 30-day average.")

(defn- rate-basis-text
  [{:keys [id basis rate]}]
  (str (if (= basis :average) "30-day avg · " "today's rate · ")
       (signed-pct-text rate (get projection-rate-decimals id 4))))

(defn projections-section
  "Expected (the default) projects 30 days and a year at the 30-day average,
  which beat today's rate out of sample; today's rate is offered as a labelled
  scenario."
  [{:keys [projection-rows expected-projection-rows verdict margin breakeven
           expected-breakeven position-base-symbol]}
   projection-basis]
  (let [basis (if (= :today projection-basis) :today :expected)
        expected? (= basis :expected)
        average-ready? (seq expected-projection-rows)
        rows (if (and expected? average-ready?) expected-projection-rows projection-rows)
        breakeven* (if (and expected? expected-breakeven) expected-breakeven breakeven)
        show-margin? (number? margin)
        grid (if show-margin?
               ["grid-cols-[minmax(0,1fr)_auto_4.5rem]"]
               ["grid-cols-[minmax(0,1fr)_auto]"])]
    [:div {:class ["flex" "flex-col" "gap-1.5"]
           :data-role "funding-tooltip-projections"
           :data-projection-basis (name basis)}
     (section-heading "Projections" (basis-toggle basis))
     [:div {:class (into ["grid" "gap-x-3" "text-xs" "text-ho-text-muted"] grid)}
      [:span]
      [:span {:class ["text-right"]}
       (case verdict :pays "You pay" :receives "You earn" "Payment")]
      (when show-margin? [:span {:class ["text-right"]} "Of margin"])]
     (for [{:keys [id label payment margin-pct] :as row} rows]
       ^{:key id}
       [:div {:class (into ["grid" "items-baseline" "gap-x-3" "border-b" "border-ho-border/60" "pb-1"]
                           grid)
              :data-role (str "funding-projection-" id)}
        [:div {:class ["flex" "min-w-0" "flex-col"]}
         [:span {:class ["text-sm" "text-ho-text"]} label]
         [:span {:class ["num" "text-xs" "text-ho-text-muted"]} (rate-basis-text row)]]
        [:span {:class ["num" "text-sm" "font-semibold" "text-right" "whitespace-nowrap"
                        (payment-tone-class payment)]}
         (usd-text payment)]
        (when show-margin?
          [:span {:class ["num" "text-sm" "text-right" "text-ho-text-secondary"]}
           (margin-pct-text margin-pct)])])
     (cond
       (and expected? average-ready?)
       (projection-note "funding-projection-fade-note"
                        ["bg-ho-surface/60" "text-ho-text-secondary"]
                        (list [:path {:d "M2 4 L6 10 L9 7 L14 12"}] [:path {:d "M10 12 H14 V8"}])
                        fade-note-text)

       (not expected?)
       (projection-note "funding-projection-scenario-note"
                        ["bg-ho-warn/10" "text-ho-text"]
                        (list [:circle {:cx 8 :cy 8 :r 6.25}] [:path {:d "M8 7.5 V11"}] [:path {:d "M8 5 V5.1"}])
                        scenario-note-text))
     (when breakeven*
       [:p {:class ["mt-1" "rounded-md" "border" "border-ho-border-accent-muted" "px-2.5" "py-1.5"
                    "text-xs" "text-ho-text-secondary"]
            :data-role "funding-tooltip-breakeven"}
        (breakeven-text breakeven* position-base-symbol
                        (and expected? (some? expected-breakeven)))])]))

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
