(ns hyperopen.views.trade.order-form-twap-section
  "The TWAP order-type section of the trade ticket.

   Two ideas shape this section, both from the 2026-08-19 design review.

   The schedule is stated as a sentence rather than a table of derived rows, because the
   venue's slice spacing depends on the order's notional as well as its runtime: the
   interesting fact is 'how many pieces, how big, how far apart', and that reads better as
   prose than as four labelled numbers. Until a size is entered the schedule is genuinely
   unknowable, so the section says so instead of showing a bound.

   The price guards are stated as a band, because a trigger and a termination price are
   positions either side of the mark, and position is the one thing a drawing carries
   better than words. The termination price also carries a KILL SWITCH tag: it is named
   Max Price for parity with Hyperliquid's own ticket, and that name invites exactly the
   wrong reading."
  (:require [hyperopen.views.trade.order-form-component-primitives :as primitives]))

(def ^:private panel-classes
  ["rounded-lg" "border" "border-ho-border-accent" "bg-ho-bg-deep" "p-3"
   "flex" "flex-col" "gap-2"])

(def ^:private micro-type
  ;; The named scale bottoms out at 12px (text-xs and text-sm are both 12px in
  ;; tailwind.config.js), which is too loud for a tag sitting beside its own field. The
  ;; sanctioned way down is an inline font-size, as order_form_footer.cljs already does.
  {:font-size "10px" :letter-spacing "0.06em"})

(def ^:private tag-classes
  ["num" "text-ho-text-dim" "whitespace-nowrap"])

(defn- section-heading
  [label hint]
  [:div {:class ["flex" "items-baseline" "justify-between" "gap-2"]}
   [:span {:class ["text-xs" "text-gray-100"]} label]
   [:span {:class ["text-xs" "num" "text-ho-text-dim" "truncate"]} hint]])

(defn- runtime-preset-chip
  [{:keys [label]} active? on-click]
  [:button (cond-> {:type "button"
                    :class (into ["flex-1" "h-8" "rounded-md" "text-xs" "num"
                                  "transition-colors"]
                                 (if active?
                                   ["bg-ho-accent" "text-ho-bg-deep" "font-semibold"]
                                   ["border" "border-base-300" "text-gray-400"
                                    "hover:text-gray-200"]))}
             on-click
             (primitives/bind-event :click on-click))
   label])

(defn- compact-unit-input
  "One character of label, the value right-aligned. Three of these share the ticket column,
   so the label has to be a single letter or the number has nowhere to go."
  [unit aria-label value on-change]
  [:div {:class ["h-8" "border" "border-base-300" "rounded-lg" "flex" "items-center"
                 "px-2" "gap-1" "min-w-0"]}
   [:span {:class ["text-xs" "text-gray-400"]} unit]
   [:input (primitives/bind-event
            {:class ["flex-1" "min-w-0" "bg-transparent" "text-right" "text-xs" "num"
                     "text-gray-100" "appearance-none" "focus:outline-none"]
             :type "text"
             :aria-label aria-label
             :value (or value "")}
            :input
            on-change)]])

(defn- runtime-control
  [form {:keys [twap-schedule on-select-twap-runtime-preset on-select-twap-custom-runtime
                on-set-twap-days on-set-twap-hours on-set-twap-minutes]}]
  (let [{:keys [presets preset-key custom? runtime-window]} twap-schedule]
    [:div {:class ["flex" "flex-col" "gap-2"]}
     ;; The hint states the venue's allowed window, not the resolved schedule -- the
     ;; sentence directly below already says how many pieces and how far apart.
     (section-heading "Runtime" runtime-window)
     [:div {:class ["flex" "gap-1"]}
      (for [{:keys [key] :as preset} presets]
        ^{:key key}
        [:div {:class ["flex" "flex-1" "min-w-0"]}
         (runtime-preset-chip preset
                              (and (not custom?) (= key preset-key))
                              (when on-select-twap-runtime-preset
                                (on-select-twap-runtime-preset preset)))])
      [:div {:class ["flex" "flex-1" "min-w-0"]}
       (runtime-preset-chip {:label "Custom"} custom? on-select-twap-custom-runtime)]]
     (when custom?
       [:div {:class ["grid" "grid-cols-3" "gap-1.5"]}
        (compact-unit-input "D" "Days" (get-in form [:twap :days]) on-set-twap-days)
        (compact-unit-input "H" "Hours" (get-in form [:twap :hours]) on-set-twap-hours)
        (compact-unit-input "M" "Minutes" (get-in form [:twap :minutes])
                            on-set-twap-minutes)])]))

(defn- emphasis
  [value]
  [:span {:class ["num" "font-semibold" "text-gray-100"]} value])

(defn- schedule-sentence
  "The whole schedule in one sentence. Deliberately not a table: the numbers only mean
   something in relation to each other."
  [{:keys [known? verb base-symbol runtime-phrase interval-phrase order-count
           order-notional slice-notional slice-size]}]
  (if known?
    [:div {:class panel-classes}
     [:div {:class ["text-xs" "leading-relaxed" "text-gray-100"]}
      verb " " (emphasis order-notional) " of " base-symbol " in "
      (emphasis (str order-count)) " pieces — "
      (emphasis slice-notional) " about every "
      (emphasis interval-phrase) " for "
      (emphasis runtime-phrase) "."]
     [:div {:class ["flex" "justify-between" "gap-2" "border-t"
                    "border-ho-border-accent" "pt-2"]}
      [:span {:class ["text-xs" "text-gray-400"]} "Per slice"]
      [:span {:class ["text-xs" "num" "text-gray-100"]}
       (str slice-size " · " slice-notional)]]
     [:div {:class ["leading-snug" "text-ho-text-dim"] :style {:font-size "11px"}}
      "Pieces are spaced by the venue's $10-per-slice minimum, so a smaller size means longer gaps - not a fixed 30s cadence."]]
    [:div {:class ["rounded-lg" "border" "border-dashed" "border-base-300" "p-2.5"]}
     [:div {:class ["text-xs" "leading-relaxed" "text-gray-400"]}
      verb " " [:span {:class ["num" "text-ho-text-dim"]} "—"]
      " of " base-symbol " in equal pieces over "
      (emphasis runtime-phrase) ". Enter a size to see the pieces."]]))

(defn- guard-band
  "Trigger and termination levels placed either side of the mark. The filled span is the
   range the run is allowed to work inside."
  [{:keys [mark-label mark-pct trigger-label trigger-pct stop-label stop-pct
           range-from range-to]}]
  [:div {:class ["flex" "flex-col" "gap-3"]}
   [:div {:class ["grid" "grid-cols-3" "items-baseline" "gap-1"]}
    [:span {:class ["num" "text-ho-accent" "truncate"] :style micro-type}
     (or trigger-label "")]
    [:span {:class ["num" "text-ho-text-secondary" "text-center" "truncate"]
            :style micro-type}
     mark-label]
    [:span {:class ["num" "text-ho-sell-hi" "text-right" "truncate"] :style micro-type}
     (or stop-label "")]]
   [:div {:class ["relative" "h-1.5" "rounded-full" "bg-ho-surface-raised"]}
    [:div {:class ["absolute" "top-0" "h-1.5" "rounded-full" "bg-ho-accent-soft"]
           :style {:left (str range-from "%")
                   :width (str (max 0 (- range-to range-from)) "%")}}]
    (when trigger-pct
      [:div {:class ["absolute" "w-0.5" "h-3.5" "-top-1" "bg-ho-accent"]
             :style {:left (str trigger-pct "%")}}])
    [:div {:class ["absolute" "w-0.5" "h-4" "-top-1.5" "bg-ho-text"]
           :style {:left (str mark-pct "%")}}]
    (when stop-pct
      [:div {:class ["absolute" "w-0.5" "h-3.5" "-top-1" "bg-ho-sell-hi"]
             :style {:left (str stop-pct "%")}}])]
   [:div {:class ["text-center" "num" "text-ho-text-dim"] :style micro-type}
    "order works inside this band"]])

(defn- guard-fields
  [form {:keys [twap-guards on-set-twap-trigger-price on-set-twap-stop-price]}]
  (let [{:keys [trigger-note stop-note]} twap-guards
        stop-price-label (or (:stop-price-label twap-guards) "Max Price")]
    [:div {:class ["flex" "flex-col" "gap-2"]}
     [:div {:class ["h-8" "border" "border-base-300" "rounded-lg" "bg-ho-bg" "flex"
                    "items-center" "px-2.5" "gap-2" "min-w-0"]}
      [:span {:class ["text-xs" "text-gray-400" "whitespace-nowrap"]} "Trigger Price"]
      [:input (primitives/bind-event
               {:class ["flex-1" "min-w-0" "bg-transparent" "text-right" "text-xs" "num"
                        "text-gray-100" "appearance-none" "focus:outline-none"]
                :type "text"
                :aria-label "Trigger Price"
                :value (or (get-in form [:twap :trigger-px]) "")}
               :input
               on-set-twap-trigger-price)]]
     (when trigger-note
       [:div {:class ["leading-snug" "text-gray-400" "pl-0.5"]
              :style {:font-size "11px"}}
        trigger-note])
     [:div {:class ["h-8" "border" "border-ho-border-sell" "rounded-lg" "bg-ho-bg" "flex"
                    "items-center" "px-2.5" "gap-2" "min-w-0"]}
      [:span {:class ["text-xs" "text-gray-400" "whitespace-nowrap"]} stop-price-label]
      [:span {:class ["num" "text-ho-sell-hi" "border" "border-ho-border-sell" "rounded"
                      "px-1" "whitespace-nowrap"]
              :style micro-type}
       "KILL SWITCH"]
      [:input (primitives/bind-event
               {:class ["flex-1" "min-w-0" "bg-transparent" "text-right" "text-xs" "num"
                        "text-gray-100" "appearance-none" "focus:outline-none"]
                :type "text"
                :aria-label stop-price-label
                :value (or (get-in form [:twap :stop-px]) "")}
               :input
               on-set-twap-stop-price)]]
     [:div {:class ["rounded-md" "border" "border-ho-border-sell" "bg-ho-sell-soft-deep"
                    "px-2" "py-1.5" "leading-snug" "text-ho-sell-tint"]
            :style {:font-size "11px"}}
      (or stop-note (str "Setting " stop-price-label " cancels the rest of the run when the mark reaches it."))
      " It does " [:span {:class ["text-ho-sell"]} "not"]
      " cap your fill price - slices still cross the book."]]))

(defn- price-guards
  [form {:keys [twap-guards] :as controls}]
  (let [{:keys [summary any-set? band]} twap-guards]
    [:details (cond-> {:replicant/key "trade-twap-price-guards"
                       :class ["group" "rounded-lg" "border" "border-base-300"
                               "px-2.5" "py-1.5"]}
                any-set? (assoc :open true))
     [:summary {:class ["list-none" "cursor-pointer" "select-none" "flex" "items-center"
                        "justify-between" "gap-2" "focus:outline-none"]}
      [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
       [:span {:class ["text-xs" "text-gray-400" "transition-transform"
                       "group-open:rotate-90"]}
        "▸"]
       [:span {:class ["text-xs" "text-gray-100"]} "Price guards"]]
      [:span {:class (into ["truncate"] tag-classes) :style micro-type} summary]]
     [:div {:class ["mt-2.5" "flex" "flex-col" "gap-3"]}
      [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
       [:span {:class tag-classes :style micro-type} "OPTIONAL"]]
      (when band (guard-band band))
      (guard-fields form controls)]]))

(defn twap-section
  "Renders the whole TWAP block. Receives only the form and a callbacks map, so the
   side-dependent and schedule-dependent data (:schedule, :guards) rides in that map the
   same way :twap-preview always has."
  [form controls]
  (let [{:keys [twap-schedule on-toggle-twap-randomize]} controls]
    [:div {:class ["flex" "flex-col" "gap-3"]}
     (runtime-control form controls)
     (schedule-sentence twap-schedule)
     [:div {:class ["flex" "items-center" "gap-2"]}
      (primitives/row-toggle "Randomize slice size"
                             (get-in form [:twap :randomize])
                             on-toggle-twap-randomize
                             "trade-toggle-twap-randomize")
      [:span {:class tag-classes :style micro-type} "±20%"]]
     (price-guards form controls)]))
