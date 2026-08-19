(ns hyperopen.views.account-info.tabs.twap
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]
            [hyperopen.views.account-info.tabs.trade-history :as trade-history-tab]))

(def ^:private active-grid-template-style
  {:grid-template-columns
   "minmax(110px,1fr) minmax(90px,0.9fr) minmax(110px,1fr) minmax(90px,0.85fr) minmax(150px,1.25fr) minmax(120px,1fr) minmax(88px,0.72fr) minmax(150px,1.1fr) minmax(96px,0.8fr)"})

(def ^:private active-read-only-grid-template-style
  {:grid-template-columns
   "minmax(110px,1fr) minmax(90px,0.9fr) minmax(110px,1fr) minmax(90px,0.85fr) minmax(150px,1.25fr) minmax(120px,1fr) minmax(88px,0.72fr) minmax(150px,1.1fr)"})

(def ^:private history-grid-template-style
  {:grid-template-columns
   "minmax(150px,1.2fr) minmax(110px,0.9fr) minmax(90px,0.82fr) minmax(100px,0.9fr) minmax(90px,0.82fr) minmax(110px,0.92fr) minmax(120px,1fr) minmax(88px,0.72fr) minmax(88px,0.72fr) minmax(90px,0.82fr)"})

(def ^:private subtab-order
  [:active :history :fill-history])

(defn- empty-state
  [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div {:class ["mt-2" "text-sm" "text-trading-text-secondary"]} "No data available"]])

(defn- format-size
  [value]
  (if-let [num (shared/parse-optional-num value)]
    (or (fmt/format-intl-number num
                                {:minimumFractionDigits 0
                                 :maximumFractionDigits 6})
        "--")
    "--"))

(defn- format-average-price
  [value]
  (if-let [num (shared/parse-optional-num value)]
    (shared/format-trade-price num)
    "--"))

(defn- trigger-stop-node
  "Renders a TWAP's advanced settings by exception: a row shows nothing here unless the
   order actually carries a trigger or a termination price.

   The trigger reads as a comparison against the mark, because that is exactly what it is
   -- the venue starts working the order once the mark reaches the level. The termination
   price is labelled Stop, not Max/Min, because a single column serves buys and sells; it
   halts the order and does NOT cap the fill price."
  [row]
  (let [trigger-px (:trigger-px row)
        stop-price (:stop-price row)
        trigger-line (when (number? trigger-px)
                       (str (if (:trigger-above? row) "\u2265 " "\u2264 ")
                            (format-average-price trigger-px)))
        stop-line (when (number? stop-price)
                    (str "Stop " (format-average-price stop-price)))
        lines (remove nil? [trigger-line stop-line])]
    (if (seq lines)
      [:div {:class ["flex" "flex-col" "num" "whitespace-nowrap"]}
       (for [line lines]
         ^{:key line} [:span line])]
      "--")))

(defn- yes-no
  [value]
  (if value "Yes" "No"))

(defn- side-tone-class
  [side]
  (case side
    "B" "text-success"
    "A" "text-error"
    "S" "text-error"
    "text-trading-text"))

(defn- coin-node
  [coin side]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin {})
        tone-class (side-tone-class side)]
    (shared/coin-select-control
     coin
     [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
      [:span {:class ["truncate" "font-semibold" tone-class]} base-label]
      (when prefix-label
        [:span {:class shared/position-chip-classes} prefix-label])]
     {:extra-classes ["w-full" "justify-start" "text-left"]})))

(defn- status-node
  [{:keys [status-label status-tooltip]}]
  (let [label (or (shared/non-blank-text status-label) "--")
        tooltip-text (shared/non-blank-text status-tooltip)]
    (if tooltip-text
      [:span {:class ["group" "relative" "inline-flex" "min-h-4" "items-center"]}
       [:span {:class ["cursor-help"
                       "rounded"
                       "underline"
                       "decoration-dashed"
                       "underline-offset-2"
                       "focus-visible:outline-none"
                       "focus-visible:ring-2"
                       "focus-visible:ring-trading-green/70"
                       "focus-visible:ring-offset-1"
                       "focus-visible:ring-offset-base-100"]
               :tab-index 0}
        label]
       [:span {:class ["pointer-events-none"
                       "absolute"
                       "left-1/2"
                       "-translate-x-1/2"
                       "bottom-full"
                       "z-50"
                       "mb-2"
                       "opacity-0"
                       "transition-opacity"
                       "duration-200"
                       "group-hover:opacity-100"
                       "group-focus-within:opacity-100"]}
        [:div {:class ["relative"
                       "w-max"
                       "max-w-[calc(100vw-2rem)]"
                       "rounded-md"
                       "bg-gray-800"
                       "px-2.5"
                       "py-1.5"
                       "text-left"
                       "text-xs"
                       "leading-tight"
                       "text-gray-100"
                       "spectate-lg"
                       "whitespace-normal"]}
         tooltip-text
         [:span {:class ["absolute"
                         "top-full"
                         "left-1/2"
                         "-translate-x-1/2"
                         "h-0"
                         "w-0"
                         "border-4"
                         "border-transparent"
                         "border-t-gray-800"]}]]]]
      label)))

(defn- subtab-label
  [subtab counts]
  (let [base (case subtab
               :active "Active"
               :history "History"
               :fill-history "Fill History"
               "Active")
        count (get counts subtab)]
    (if (number? count)
      (str base " (" count ")")
      base)))

(defn- subtab-nav
  [selected-subtab counts]
  [:div {:class ["flex" "items-center" "gap-1" "border-b" "border-base-300" "bg-base-200" "px-2" "py-2"]
         :data-role "twap-subtab-nav"}
   (for [subtab subtab-order]
     ^{:key (name subtab)}
     [:button {:type "button"
               :class (into ["rounded-md"
                             "px-3"
                             "py-1.5"
                             "text-xs"
                             "font-medium"
                             "transition-colors"]
                            (if (= selected-subtab subtab)
                              ["bg-base-100" "text-trading-text"]
                              ["text-trading-text-secondary" "hover:bg-base-100" "hover:text-trading-text"]))
               :on {:click [[:actions/select-account-info-twap-subtab subtab]]}}
      (subtab-label subtab counts)])])

(defn- terminate-button
  [row]
  [:button {:type "button"
            :class ["inline-flex"
                    "w-full"
                    "justify-start"
                    "bg-transparent"
                    "p-0"
                    "font-medium"
                    "text-trading-red"
                    "transition-colors"
                    "hover:text-ho-sell-tint"
                    "whitespace-nowrap"]
            :on {:click [[:actions/cancel-twap row]]}}
   "Terminate"])

(defn- active-table
  [rows read-only?]
  (if (seq rows)
    (table/tab-table-content
     [:div {:class ["grid" "gap-2" "py-1" "px-3" "bg-base-200" "text-xs" "font-medium"]
            :style (if read-only?
                     active-read-only-grid-template-style
                     active-grid-template-style)}
      [:div.text-left (table/non-sortable-header "Coin")]
      [:div.text-left (table/non-sortable-header "Size")]
      [:div.text-left (table/non-sortable-header "Executed Size")]
      [:div.text-left (table/non-sortable-header "Average Price")]
      [:div.text-left (table/non-sortable-header "Running Time and Total")]
      [:div.text-left (table/non-sortable-header "Trigger / Stop")]
      [:div.text-left (table/non-sortable-header "Reduce Only")]
      [:div.text-left (table/non-sortable-header "Creation Time")]
      (when-not read-only?
        [:div.text-left (table/non-sortable-header "Terminate")])]
     (for [row rows]
       ^{:key (str "twap-active-" (:twap-id row) "-" (:creation-time-ms row))}
       [:div {:class ["account-table-row" "grid" "items-center" "gap-2" "py-px" "px-3" "hover:bg-base-300" "text-xs"]
              :style (if read-only?
                       active-read-only-grid-template-style
                       active-grid-template-style)}
        [:div.text-left (coin-node (:coin row) (:side row))]
        [:div.text-left.num (format-size (:size row))]
        [:div.text-left.num (format-size (:executed-size row))]
        [:div.text-left.num (format-average-price (:average-price row))]
        [:div.text-left.whitespace-nowrap (:running-label row)]
        [:div.text-left (trigger-stop-node row)]
        [:div.text-left (yes-no (:reduce-only? row))]
        [:div.text-left.whitespace-nowrap (shared/format-open-orders-time (:creation-time-ms row))]
        (when-not read-only?
          [:div.text-left (terminate-button row)])]))
    (empty-state "No active TWAPs")))

(defn- history-table
  [rows]
  (if (seq rows)
    (table/tab-table-content
     [:div {:class ["grid" "gap-2" "py-1" "px-3" "bg-base-200" "text-xs" "font-medium"]
            :style history-grid-template-style}
      [:div.text-left (table/non-sortable-header "Time")]
      [:div.text-left (table/non-sortable-header "Coin")]
      [:div.text-left (table/non-sortable-header "Total Size")]
      [:div.text-left (table/non-sortable-header "Executed Size")]
      [:div.text-left (table/non-sortable-header "Average Price")]
      [:div.text-left (table/non-sortable-header "Total Runtime")]
      [:div.text-left (table/non-sortable-header "Trigger / Stop")]
      [:div.text-left (table/non-sortable-header "Reduce Only")]
      [:div.text-left (table/non-sortable-header "Randomize")]
      [:div.text-left (table/non-sortable-header "Status")]]
     (for [row rows]
       ^{:key (str "twap-history-" (:time-ms row) "-" (:coin row) "-" (:status-label row))}
       [:div {:class ["account-table-row" "grid" "items-center" "gap-2" "py-px" "px-3" "hover:bg-base-300" "text-xs"]
              :style history-grid-template-style}
        [:div.text-left.whitespace-nowrap (shared/format-open-orders-time (:time-ms row))]
        [:div.text-left (coin-node (:coin row) (:side row))]
        [:div.text-left.num (format-size (:size row))]
        [:div.text-left.num (if (= :activated (:status-key row))
                              "--"
                              (format-size (:executed-size row)))]
        [:div.text-left.num (if (= :activated (:status-key row))
                              "--"
                              (format-average-price (:average-price row)))]
        [:div.text-left.whitespace-nowrap (:total-runtime-label row)]
        [:div.text-left (trigger-stop-node row)]
        [:div.text-left (yes-no (:reduce-only? row))]
        [:div.text-left (yes-no (:randomize? row))]
        [:div.text-left (status-node row)]]))
    (empty-state "No TWAP history")))

(defn twap-tab-content
  [{:keys [twap-state
           twap-active-rows
           twap-history-rows
           twap-fill-rows
           twap-fill-state
           read-only?]}]
  (let [selected-subtab (or (:selected-subtab twap-state) :active)
        read-only?* (or (true? (:read-only? twap-state))
                        (true? read-only?))
        counts {:active (count (or twap-active-rows []))
                :history (count (or twap-history-rows []))
                :fill-history (count (or twap-fill-rows []))}]
    [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]
           :data-role "twap-tab-content"}
     (subtab-nav selected-subtab counts)
     [:div {:class ["flex-1" "min-h-0" "overflow-hidden"]}
     (case selected-subtab
        :history (history-table twap-history-rows)
        :fill-history (if (seq twap-fill-rows)
                        (trade-history-tab/trade-history-table twap-fill-rows twap-fill-state)
                        (empty-state "No TWAP slice fills"))
        (active-table twap-active-rows read-only?*))]]))
