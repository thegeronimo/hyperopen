(ns hyperopen.views.account-info.tabs.positions
  (:require ["lucide/dist/esm/icons/pencil.js" :default lucide-pencil-node]
            [clojure.string :as str]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.mobile-cards :as mobile-cards]
            [hyperopen.views.account-info.position-margin-modal :as position-margin-modal]
            [hyperopen.views.account-info.position-reduce-popover :as position-reduce-popover]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.position-tpsl-modal :as position-tpsl-modal]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(def positions-direction-filter-options
  [[:all "All"]
   [:long "Long"]
   [:short "Short"]])

(def positions-direction-filter-labels
  (into {} positions-direction-filter-options))

(defn calculate-mark-price [position-data]
  (positions-vm/calculate-mark-price position-data))

(defn- display-coin [position-data]
  (positions-vm/display-coin position-data))

(defn- dex-chip-label [position-data]
  (if (and (map? position-data)
           (map? (:position position-data)))
    (positions-vm/dex-chip-label position-data)
    (positions-vm/dex-chip-label {:position {:coin (:coin position-data)}
                                  :dex (:dex position-data)})))

(defn positions-direction-filter-key [positions-state]
  (let [raw-direction (:direction-filter positions-state)
        direction-filter (cond
                           (keyword? raw-direction) raw-direction
                           (string? raw-direction) (keyword (str/lower-case raw-direction))
                           :else :all)]
    (if (contains? positions-direction-filter-labels direction-filter)
      direction-filter
      :all)))

(defn format-position-size [position-data]
  (positions-vm/format-position-size position-data))

(defn- explainable-value-node
  ([value-node explanation]
   (explainable-value-node value-node explanation {}))
  ([value-node explanation {:keys [underlined?]
                            :or {underlined? true}}]
   (if explanation
    [:span {:class (into ["group" "relative" "inline-flex" "items-center"]
                         (when underlined?
                           ["underline" "decoration-dashed" "underline-offset-2"]))}
     value-node
     [:span {:class ["pointer-events-none"
                     "absolute"
                     "left-1/2"
                     "-translate-x-1/2"
                     "top-full"
                     "z-[120]"
                     "mt-2"
                     "w-56"
                     "rounded-md"
                     "bg-gray-800"
                     "px-2.5"
                     "py-1.5"
                     "text-left"
                     "text-xs"
                     "leading-tight"
                     "text-gray-100"
                     "whitespace-normal"
                     "spectate-lg"
                     "opacity-0"
                     "transition-opacity"
                     "duration-200"
                     "group-hover:opacity-100"
                     "group-focus-within:opacity-100"]}
      explanation]]
    value-node)))

(defn- format-pnl-inline [pnl-num pnl-percent]
  (if (and (number? pnl-num) (number? pnl-percent))
    (let [value-prefix (cond
                         (pos? pnl-num) "+$"
                         (neg? pnl-num) "-$"
                         :else "$")
          pct-prefix (cond
                       (pos? pnl-percent) "+"
                       (neg? pnl-percent) "-"
                       :else "")
          value-text (str value-prefix (shared/format-currency (js/Math.abs pnl-num)))
          pct-text (str "(" pct-prefix (.toFixed (js/Math.abs pnl-percent) 1) "%)")]
      (str value-text " " pct-text))
    "--"))

(def ^:private max-liquidation-display-chars 6)

(defn- count-integer-digits [num]
  (let [abs-value (js/Math.abs num)]
    (if (< abs-value 1)
      1
      (inc (js/Math.floor (/ (js/Math.log abs-value)
                             (js/Math.log 10)))))))

(defn- format-liquidation-price [value]
  (if-let [num (shared/parse-optional-num value)]
    (let [integer-digits (count-integer-digits num)
          decimal-digits (if (>= integer-digits max-liquidation-display-chars)
                           0
                           (max 0 (- max-liquidation-display-chars integer-digits 1)))]
      (or (fmt/format-currency-with-digits num 0 decimal-digits)
          "N/A"))
    "N/A"))

(defn- lucide-node->hiccup [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn- edit-icon []
  (into [:svg {:class ["h-4" "w-4" "shrink-0"]
               :viewBox "0 0 24 24"
               :fill "none"
               :stroke "currentColor"
               :stroke-width "1.6"
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden true}]
        (map lucide-node->hiccup
             (array-seq lucide-pencil-node))))

(def ^:private position-detail-edit-button-classes
  ["inline-flex"
   "-ml-0.5"
   "h-6"
   "w-6"
   "items-center"
   "justify-center"
   "shrink-0"
   "bg-transparent"
   "p-0"
   "text-trading-green"
   "transition-colors"
   "hover:text-[#7fffe4]"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus-visible:outline-none"
   "focus-visible:ring-0"
   "focus-visible:ring-offset-0"
   "focus-visible:text-[#7fffe4]"])

(defn- position-detail-edit-button
  [aria-label action data-attr]
  [:button {:class position-detail-edit-button-classes
            :type "button"
            :aria-label aria-label
            data-attr "true"
            :on {:click action}}
   (edit-icon)])

(defn position-unique-key [position-data]
  (projections/position-unique-key position-data))

(defn collect-positions [webdata2 perp-dex-states]
  (projections/collect-positions webdata2 perp-dex-states))

(defn- position-value-copy [position-value-num]
  (if (number? position-value-num)
    (str (shared/format-currency position-value-num) " USDC")
    "--"))

(defn- funding-value-node [display-funding]
  [:span {:class [(cond
                    (and (number? display-funding) (neg? display-funding)) "text-error"
                    (and (number? display-funding) (pos? display-funding)) "text-success"
                    :else "text-trading-text")
                  "num"]}
   (if (number? display-funding)
     (str "$" (shared/format-currency display-funding))
     "--")])

(defn- mobile-position-coin-node [position-data side]
  (let [pos (:position position-data)
        chip-classes (shared/position-chip-classes-for-side side)
        coin-label (display-coin pos)
        dex-label (dex-chip-label {:coin (:coin pos)
                                   :dex (:dex position-data)})
        leverage (get-in pos [:leverage :value])]
    [:span {:class ["flex" "min-w-0" "items-center" "gap-1"]}
     [:span {:class ["truncate" "font-medium" "leading-4" "text-trading-text"]} coin-label]
     (when (some? leverage)
       [:span {:class chip-classes} (str leverage "x")])
     (when dex-label
       [:span {:class chip-classes} dex-label])]))

(defn- mobile-position-action-button [label action]
  [:button {:type "button"
            :class ["inline-flex"
                    "items-center"
                    "justify-start"
                    "bg-transparent"
                    "p-0"
                    "text-sm"
                    "font-medium"
                    "leading-none"
                    "text-trading-green"
                    "transition-colors"
                    "hover:text-[#7fffe4]"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus-visible:text-[#7fffe4]"
                    "whitespace-nowrap"]
            :on {:click action}}
   label])

(defn- mobile-position-detail-edit-button [aria-label action data-attr]
  [:button {:type "button"
            :class ["inline-flex"
                    "-ml-0.5"
                    "h-6"
                    "w-6"
                    "items-center"
                    "justify-center"
                    "shrink-0"
                    "bg-transparent"
                    "p-0"
                    "text-trading-green"
                    "transition-colors"
                    "hover:text-[#7fffe4]"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus-visible:text-[#7fffe4]"]
            :aria-label aria-label
            :on {:click action}
            data-attr "true"}
   (edit-icon)])

(defn- editable-mobile-detail-value
  [content edit-button]
  [:div {:class ["inline-flex" "max-w-full" "items-start" "gap-0.5" "align-top"]}
   [:div {:class ["min-w-0" "leading-5"]}
    content]
   edit-button])

(def ^:private mobile-position-overlay-breakpoint-px 640)
(def ^:private mobile-position-card-layout-breakpoint-px 1024)
(def ^:private mobile-position-overlay-trigger-size-px 24)
(def ^:private mobile-position-overlay-horizontal-padding-px 16)
(def ^:private mobile-position-overlay-bottom-padding-px 24)
(def ^:private mobile-position-overlay-fallback-width-px 430)
(def ^:private mobile-position-overlay-fallback-height-px 932)

(defn- current-viewport-number
  [value fallback]
  (if (and (number? value)
           (pos? value))
    value
    fallback))

(defn- current-viewport-width []
  (current-viewport-number (some-> js/globalThis .-innerWidth)
                           mobile-position-overlay-fallback-width-px))

(defn- current-viewport-height []
  (current-viewport-number (some-> js/globalThis .-innerHeight)
                           mobile-position-overlay-fallback-height-px))

(defn- active-card-layout?
  []
  (let [width (some-> js/globalThis .-innerWidth)]
    (and (number? width)
         (pos? width)
         (< width mobile-position-card-layout-breakpoint-px))))

(defn- active-desktop-table-layout?
  []
  (not (active-card-layout?)))

(defn- phone-overlay-trigger?
  []
  (<= (current-viewport-width)
      mobile-position-overlay-breakpoint-px))

(defn- mobile-position-overlay-anchor
  []
  (let [viewport-width (current-viewport-width)
        viewport-height (current-viewport-height)
        trigger-size mobile-position-overlay-trigger-size-px
        right (max trigger-size
                   (- viewport-width mobile-position-overlay-horizontal-padding-px))
        left (max 0 (- right trigger-size))
        bottom (max trigger-size
                    (- viewport-height mobile-position-overlay-bottom-padding-px))
        top (max 0 (- bottom trigger-size))]
    {:left left
     :right right
     :top top
     :bottom bottom
     :width trigger-size
     :height trigger-size
     :viewport-width viewport-width
     :viewport-height viewport-height}))

(defn- position-overlay-trigger
  [action-id position-data]
  [[action-id
    position-data
    (if (phone-overlay-trigger?)
      (mobile-position-overlay-anchor)
      :event.currentTarget/bounds)]])

(defn- mobile-position-margin-value-node
  [margin margin-mode-label]
  [:div {:class ["inline-grid"
                 "grid-cols-[max-content_auto]"
                 "items-start"
                 "gap-x-0.5"
                 "gap-y-0.5"]}
   [:span {:class ["num" "font-medium" "text-trading-text" "whitespace-nowrap"]}
    (str "$" (shared/format-currency margin))]
   (when margin-mode-label
     [:span {:class ["col-span-full"
                     "text-xs"
                     "font-medium"
                     "leading-4"
                     "text-trading-text-secondary"
                     "whitespace-nowrap"]}
      (str "(" margin-mode-label ")")])])

(defn- editable-mobile-margin-value
  [margin margin-mode-label edit-button]
  [:div {:class ["inline-grid"
                 "grid-cols-[max-content_auto]"
                 "items-start"
                 "gap-x-0.5"
                 "gap-y-0.5"
                 "align-top"]}
   (mobile-position-margin-value-node margin margin-mode-label)
   edit-button])

(def ^:private mobile-position-card-summary-grid-classes
  ["grid"
   "grid-cols-[minmax(0,1.75fr)_minmax(0,0.95fr)_minmax(0,1.05fr)_auto]"
   "items-start"
   "gap-x-2.5"
   "gap-y-2"])

(defn- position-row-from-vm
  [row-vm tpsl-modal reduce-popover margin-modal]
  (let [position-data (:row-data row-vm)
        pos (:position row-vm)
        side (:side row-vm)
        chip-classes (shared/position-chip-classes-for-side side)
        coin-cell-style (shared/position-coin-cell-style-for-side side)
        coin-tone-class (shared/position-side-tone-class side)
        size-tone-class (shared/position-side-size-class side)
        coin-label (:coin-label row-vm)
        dex-label (:dex-label row-vm)
        leverage (get-in pos [:leverage :value])
        position-value-num (:position-value-num row-vm)
        margin (:margin row-vm)
        margin-editable? (:margin-editable? row-vm)
        margin-mode-label (:margin-mode-label row-vm)
        display-funding (:funding-display row-vm)
        funding-tooltip (:funding-tooltip row-vm)
        liq-explanation (:liq-explanation row-vm)
        tpsl-copy (:tpsl-copy row-vm)
        row-key (:row-key row-vm)
        active-modal?
        (and (position-tpsl/open? tpsl-modal)
             (= row-key (:position-key tpsl-modal)))
        active-reduce-popover?
        (and (position-reduce/open? reduce-popover)
             (= row-key (:position-key reduce-popover)))
        active-margin-modal?
        (and (position-margin/open? margin-modal)
             (= row-key (:position-key margin-modal)))]
    [:div {:class ["grid"
                   shared/positions-grid-template-class
                   "gap-2"
                   "py-0"
                   "pr-3"
                   shared/positions-grid-min-width-class
                   "hover:bg-base-300"
                   "items-center"
                   "text-sm"]}
     [:div {:class ["flex" "min-w-0" "items-center" "gap-1.5" "self-stretch"]
            :style coin-cell-style}
      (shared/coin-select-control
       (:coin pos)
       [:span {:class ["flex" "w-full" "min-w-0" "items-center" "gap-1.5"]}
        [:span {:class ["block" "min-w-0" "truncate" "font-medium" coin-tone-class]
                :title coin-label}
         coin-label]
        (when (some? leverage)
          [:span {:class chip-classes} (str leverage "x")])
        (when dex-label
          [:span {:class chip-classes} dex-label])]
       {:extra-classes ["w-full" "justify-start" "overflow-hidden" "text-left"]})]
     [:div {:class ["min-w-0" "truncate" "text-left" "font-semibold" "num" size-tone-class]
            :title (:size-display row-vm)}
      (:size-display row-vm)]
     [:div.text-left.font-semibold.num
      (if (number? position-value-num)
        (str (shared/format-currency position-value-num) " USDC")
        "--")]
     [:div.text-left.font-semibold.num (shared/format-trade-price (:entry-price row-vm))]
     [:div.text-left.font-semibold.num (:mark-price-display row-vm)]
     [:div {:class ["text-left" "font-semibold" "num" (:pnl-color-class row-vm)]}
      (format-pnl-inline (:pnl-num row-vm) (:pnl-percent row-vm))]
     [:div.text-left.font-semibold.num
      (explainable-value-node
       (format-liquidation-price (:liq-price row-vm))
       liq-explanation)]
     [:div {:class ["text-left" "relative" "font-semibold" "num"]}
      [:div {:class ["inline-flex" "items-center" "gap-0.5" "whitespace-nowrap"]}
       [:span {:class ["inline-flex" "items-baseline" "gap-1" "whitespace-nowrap" "select-text"]}
        [:span {:class ["num"]}
         (str "$" (shared/format-currency margin))]
        (when margin-mode-label
          [:span {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
           (str "(" margin-mode-label ")")])]
       (when margin-editable?
         (position-detail-edit-button
          "Edit Margin"
          [[:actions/open-position-margin-modal position-data :event.currentTarget/bounds]]
          :data-position-margin-trigger))]
      (when active-margin-modal?
        (when (active-desktop-table-layout?)
          (position-margin-modal/position-margin-modal-view margin-modal)))]
     [:div.text-left.font-semibold.num
      (explainable-value-node
       [:span {:class [(:funding-tone-class row-vm) "num"]}
        (if (number? display-funding)
          (str "$" (shared/format-currency display-funding))
          "--")]
       funding-tooltip
       {:underlined? false})]
     [:div {:class ["text-left" "relative"]}
      [:button {:class ["inline-flex"
                        "w-full"
                        "justify-start"
                        "bg-transparent"
                        "p-0"
                        "font-semibold"
                        "text-trading-green"
                        "transition-colors"
                        "focus:outline-none"
                        "focus:ring-0"
                        "focus:ring-offset-0"
                        "focus:shadow-none"
                        "focus-visible:outline-none"
                        "focus-visible:ring-0"
                        "focus-visible:ring-offset-0"
                        "hover:text-[#7fffe4]"
                        "focus-visible:text-[#7fffe4]"
                        "whitespace-nowrap"]
                :type "button"
                :data-position-reduce-trigger "true"
                :on {:click [[:actions/open-position-reduce-popover position-data :event.currentTarget/bounds]]}}
       "Reduce"]
      (when active-reduce-popover?
        (when (active-desktop-table-layout?)
          (position-reduce-popover/position-reduce-popover-view reduce-popover)))]
     [:div {:class ["text-left" "relative"]}
      [:div {:class ["inline-flex" "items-center" "gap-0.5" "whitespace-nowrap"]}
       [:span {:class ["font-normal" "text-trading-text" "whitespace-nowrap" "select-text"]} tpsl-copy]
      (position-detail-edit-button
        "Edit TP/SL"
        [[:actions/open-position-tpsl-modal position-data :event.currentTarget/bounds]]
        :data-position-tpsl-trigger)]
      (when active-modal?
        (when (active-desktop-table-layout?)
          (position-tpsl-modal/position-tpsl-modal-view tpsl-modal)))]]))

(defn position-row
  ([position-data]
   (position-row position-data nil nil nil))
  ([position-data tpsl-modal]
   (position-row position-data tpsl-modal nil nil))
  ([position-data tpsl-modal reduce-popover]
   (position-row position-data tpsl-modal reduce-popover nil))
  ([position-data tpsl-modal reduce-popover margin-modal]
   (position-row-from-vm (positions-vm/position-row-vm position-data)
                         tpsl-modal
                         reduce-popover
                         margin-modal)))

(defn- mobile-position-card-from-vm
  [expanded-row-id row-vm tpsl-modal reduce-popover margin-modal]
  (let [position-data (:row-data row-vm)
        pos (:position row-vm)
        side (:side row-vm)
        row-id (some-> (:row-key row-vm) str str/trim)
        expanded? (= expanded-row-id row-id)
        position-value-num (:position-value-num row-vm)
        pnl-num (:pnl-num row-vm)
        pnl-percent (:pnl-percent row-vm)
        pnl-color-class (:pnl-color-class row-vm)
        margin-editable? (:margin-editable? row-vm)
        margin-mode-label (:margin-mode-label row-vm)
        display-funding (:funding-display row-vm)]
    (mobile-cards/expandable-card
     {:data-role (str "mobile-position-card-" row-id)
      :expanded? expanded?
      :toggle-actions [[:actions/toggle-account-info-mobile-card :positions row-id]]
      :summary-grid-classes mobile-position-card-summary-grid-classes
      :summary-items [(mobile-cards/summary-item "Coin"
                                                 (mobile-position-coin-node position-data side)
                                                 {:root-classes ["pr-1"]
                                                  :value-classes ["font-medium" "leading-4"]})
                      (mobile-cards/summary-item "Size"
                                                 (:size-display row-vm)
                                                 {:value-classes ["num" "font-medium" "leading-4" "whitespace-nowrap"]})
                      (mobile-cards/summary-item "PNL (ROE %)"
                                                 [:span {:class ["num" pnl-color-class]}
                                                  (format-pnl-inline pnl-num pnl-percent)]
                                                 {:value-classes ["font-medium" "leading-4" "whitespace-nowrap"]})]
      :detail-content
      [:div {:class ["space-y-3"]}
       (mobile-cards/detail-grid
        "grid-cols-3"
        [(mobile-cards/detail-item "Entry Price"
                                   (shared/format-trade-price (:entry-price row-vm))
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Mark Price"
                                   (:mark-price-display row-vm)
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Liq. Price"
                                   (format-liquidation-price (:liq-price row-vm))
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Position Value"
                                   (position-value-copy position-value-num)
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Margin"
                                   (if margin-editable?
                                     (editable-mobile-margin-value
                                      (:margin row-vm)
                                      margin-mode-label
                                      (mobile-position-detail-edit-button
                                       "Edit Margin"
                                       (position-overlay-trigger
                                        :actions/open-position-margin-modal
                                        position-data)
                                       :data-position-margin-trigger))
                                     (mobile-position-margin-value-node
                                      (:margin row-vm)
                                      margin-mode-label))
                                   {:value-classes ["font-medium"]})
         (mobile-cards/detail-item "TP/SL"
                                   (editable-mobile-detail-value
                                    [:span {:class ["font-medium" "text-trading-text" "whitespace-nowrap"]}
                                     (:tpsl-copy row-vm)]
                                    (mobile-position-detail-edit-button
                                     "Edit TP/SL"
                                     (position-overlay-trigger
                                      :actions/open-position-tpsl-modal
                                      position-data)
                                     :data-position-tpsl-trigger))
                                   {:value-classes ["font-medium"]})
         (mobile-cards/detail-item "Funding"
                                   (funding-value-node display-funding)
                                   {:value-classes ["font-medium" "whitespace-nowrap"]})])
       [:div {:class ["border-t" "border-[#17313d]" "pt-2.5"]}
        [:div {:class ["relative" "flex" "flex-wrap" "items-center" "gap-x-5" "gap-y-2"]}
         (mobile-position-action-button
          "Close"
          (position-overlay-trigger
           :actions/open-position-reduce-popover
           position-data))
         (when margin-editable?
           (mobile-position-action-button
            "Margin"
            (position-overlay-trigger
             :actions/open-position-margin-modal
             position-data)))
         (mobile-position-action-button
          "TP/SL"
          (position-overlay-trigger
           :actions/open-position-tpsl-modal
           position-data))]]]})))

(defn- active-position-key-visible?
  [visible-row-keys overlay]
  (contains? visible-row-keys (:position-key overlay)))

(defn- ensure-active-layout-anchor
  [overlay]
  (if (and (map? overlay)
           (active-card-layout?)
           (not (map? (:anchor overlay))))
    (assoc overlay :anchor (mobile-position-overlay-anchor))
    overlay))

(defn- mobile-position-overlay-outlet
  [visible-row-keys tpsl-modal reduce-popover margin-modal]
  (when (active-card-layout?)
    (let [margin-modal* (ensure-active-layout-anchor margin-modal)
          reduce-popover* (ensure-active-layout-anchor reduce-popover)
          tpsl-modal* (ensure-active-layout-anchor tpsl-modal)]
      (cond
        (and (position-margin/open? margin-modal*)
             (active-position-key-visible? visible-row-keys margin-modal*))
        (position-margin-modal/position-margin-modal-view margin-modal*)

        (and (position-reduce/open? reduce-popover*)
             (active-position-key-visible? visible-row-keys reduce-popover*))
        (position-reduce-popover/position-reduce-popover-view reduce-popover*)

        (and (position-tpsl/open? tpsl-modal*)
             (active-position-key-visible? visible-row-keys tpsl-modal*))
        (position-tpsl-modal/position-tpsl-modal-view tpsl-modal*)

        :else
        nil))))

(defn sort-positions-by-column [positions column direction]
  (positions-vm/sort-position-rows-by-column positions column direction))

(defn reset-positions-sort-cache! []
  nil)

(def ^:private pnl-header-explanation
  "Mark price is used to estimate unrealized PNL. Only trade prices are used for realized PNL.")

(def ^:private margin-header-explanation
  "For isolated positions, margin includes unrealized pnl.")

(def ^:private funding-header-explanation
  "Net funding payments since the position was opened. Hover for all-time and since changed.")

(defn sortable-header
  ([column-name sort-state]
   (sortable-header column-name sort-state nil))
  ([column-name sort-state explanation]
   (table/sortable-header-button column-name
                                 sort-state
                                 :actions/sort-positions
                                 {:explanation explanation})))

(defn position-table-header
  ([sort-state]
   (position-table-header sort-state []))
  ([sort-state extra-classes]
   [:div {:class (into ["grid"
                        shared/positions-grid-template-class
                        "gap-2"
                        "py-1"
                        "pr-3"
                        shared/positions-grid-min-width-class
                        "bg-base-200"]
                       extra-classes)}
    [:div.text-left.pl-3 (sortable-header "Coin" sort-state)]
    [:div.text-left (sortable-header "Size" sort-state)]
    [:div.text-left (sortable-header "Position Value" sort-state)]
    [:div.text-left (sortable-header "Entry Price" sort-state)]
    [:div.text-left (sortable-header "Mark Price" sort-state)]
    [:div.text-left (sortable-header "PNL (ROE %)" sort-state pnl-header-explanation)]
    [:div.text-left (sortable-header "Liq. Price" sort-state)]
    [:div.text-left (sortable-header "Margin" sort-state margin-header-explanation)]
    [:div.text-left (sortable-header "Funding" sort-state funding-header-explanation)]
    [:div.text-left
     [:button {:class (into ["w-full"
                             "text-left"
                             "focus:outline-none"
                             "focus:ring-1"
                             "focus:ring-[#8a96a6]/40"
                             "focus:ring-offset-0"
                             "focus:shadow-none"]
                            (concat table/header-base-text-classes
                                    table/sortable-header-interaction-classes))
               :type "button"
               :on {:click [[:actions/trigger-close-all-positions]]}}
      "Close All"]]
    [:div.text-left (table/non-sortable-header "TP/SL")]]))

(defn positions-tab-content-from-rows
  [{:keys [positions
           sort-state
           tpsl-modal
           reduce-popover
           margin-modal
           positions-state]
    :or {positions-state {}} :as options}]
  (when-not (map? options)
    (throw (ex-info "positions-tab-content-from-rows expects an options map"
                    {:received options})))
  (let [positions* (vec (or positions []))
        sort-state* (or sort-state {:column nil :direction :asc})
        direction-filter (positions-direction-filter-key positions-state)
        coin-search (:coin-search positions-state "")
        expanded-row-id (get-in positions-state [:mobile-expanded-card :positions])
        row-vms (positions-vm/position-row-vms positions*)
        filtered-row-vms (positions-vm/filter-row-vms row-vms direction-filter coin-search)
        sorted-row-vms (vec (positions-vm/sort-row-vms-by-column filtered-row-vms
                                                                  (:column sort-state*)
                                                                  (:direction sort-state*)))
        visible-row-keys (into #{}
                               (keep :row-key)
                               sorted-row-vms)]
    (if (seq sorted-row-vms)
      [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
       (position-table-header sort-state* ["hidden" "lg:grid"])
       (into [:div {:class ["hidden"
                            "lg:block"
                            "flex-1"
                            "min-h-0"
                            "min-w-0"
                            "overflow-auto"
                            "scrollbar-hide"]
                   :data-role "account-tab-rows-viewport"}]
             (map (fn [row-vm]
                    ^{:key (or (:row-key row-vm)
                               (hash (:row-data row-vm)))}
                    (position-row-from-vm row-vm
                                          tpsl-modal
                                          reduce-popover
                                          margin-modal))
                  sorted-row-vms))
       (into [:div {:class ["lg:hidden"
                            "flex-1"
                            "min-h-0"
                            "overflow-y-auto"
                            "scrollbar-hide"
                            "space-y-2.5"
                            "px-2.5"
                            "py-2"]
                   :data-role "positions-mobile-cards-viewport"}]
             (map (fn [row-vm]
                    ^{:key (str "mobile-" (or (:row-key row-vm)
                                              (hash (:row-data row-vm))))}
                    (mobile-position-card-from-vm expanded-row-id
                                                  row-vm
                                                  tpsl-modal
                                                  reduce-popover
                                                  margin-modal))
                  sorted-row-vms))
       (mobile-position-overlay-outlet visible-row-keys
                                       tpsl-modal
                                       reduce-popover
                                       margin-modal)]
      (empty-state (if (seq positions*)
                     "No matching positions"
                     "No active positions")))))

(defn positions-tab-content-from-webdata
  [{:keys [webdata2 perp-dex-states] :as options}]
  (when-not (map? options)
    (throw (ex-info "positions-tab-content-from-webdata expects an options map"
                    {:received options})))
  (positions-tab-content-from-rows
   (assoc options
          :positions (collect-positions webdata2 perp-dex-states))))

(defn positions-tab-content
  [options]
  (when-not (map? options)
    (throw (ex-info "positions-tab-content expects an options map"
                    {:received options})))
  (if (contains? options :positions)
    (positions-tab-content-from-rows options)
    (positions-tab-content-from-webdata options)))
