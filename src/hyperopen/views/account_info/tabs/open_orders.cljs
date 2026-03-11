(ns hyperopen.views.account-info.tabs.open-orders
  (:require [clojure.string :as str]
            [hyperopen.order.cancel-visible-confirmation :as cancel-visible-confirmation]
            [hyperopen.views.account-info.cache-keys :as cache-keys]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.table :as table]
            [hyperopen.views.ui.anchored-popover :as anchored-popover]))

(def ^:private long-coin-color "rgb(151, 252, 228)")
(def ^:private sell-coin-color "rgb(234, 175, 184)")
(def ^:private short-order-side-values #{"A" "S"})

(def open-orders-direction-filter-options
  [[:all "All"]
   [:long "Long"]
   [:short "Short"]])

(def open-orders-direction-filter-labels
  (into {} open-orders-direction-filter-options))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(defn format-side [side]
  (case side
    "B" "Buy"
    "A" "Sell"
    "S" "Sell"
    (or side "-")))

(defn normalize-open-order [order]
  (projections/normalize-open-order order))

(defn open-orders-seq [orders]
  (projections/open-orders-seq orders))

(defn open-orders-by-dex [orders-by-dex]
  (projections/open-orders-by-dex orders-by-dex))

(defn open-orders-source [orders snapshot snapshot-by-dex]
  (projections/open-orders-source orders snapshot snapshot-by-dex))

(defn normalized-open-orders [orders snapshot snapshot-by-dex]
  (projections/normalized-open-orders orders snapshot snapshot-by-dex))

(defn trigger-condition-label [trigger-condition]
  (case trigger-condition
    "Above" "≥"
    "Below" "≤"
    "N/A" nil
    trigger-condition))

(defn format-trigger-conditions [{:keys [is-trigger trigger-condition trigger-px]}]
  (if (and is-trigger (pos? (shared/parse-num trigger-px)))
    (let [label (trigger-condition-label trigger-condition)
          price (shared/format-trade-price trigger-px)]
      (if label
        (str label " " price)
        (str "Trigger @ " price)))
    "--"))

(defn- tpsl-type?
  [order]
  (let [type-text (some-> (:type order) str str/lower-case)]
    (boolean
     (or (and (string? (:tpsl order))
              (contains? #{"tp" "sl"} (str/lower-case (:tpsl order))))
         (and (string? type-text)
              (or (str/includes? type-text "take profit")
                  (str/includes? type-text "stop loss")
                  (str/includes? type-text "take market")
                  (str/includes? type-text "take limit")
                  (str/includes? type-text "stop market")
                  (str/includes? type-text "stop limit")))))))

(defn format-tp-sl [{:keys [is-position-tpsl reduce-only] :as order}]
  (if (or is-position-tpsl
          (and reduce-only (tpsl-type? order)))
    "TP/SL"
    "-- / --"))

(defn order-value [{:keys [sz px]}]
  (let [size (shared/parse-num sz)
        price (shared/parse-num px)]
    (when (and (pos? size) (pos? price))
      (* size price))))

(defn direction-label [side]
  (case side
    "B" "Long"
    "A" "Short"
    "S" "Short"
    (or side "-")))

(defn direction-class [side]
  (case side
    "B" "text-success"
    "A" "text-error"
    "S" "text-error"
    "text-base-content"))

(defn open-orders-direction-filter-key [open-orders-state]
  (let [raw-direction (:direction-filter open-orders-state)
        direction-filter (cond
                           (keyword? raw-direction) raw-direction
                           (string? raw-direction) (keyword (str/lower-case raw-direction))
                           :else :all)]
    (if (contains? open-orders-direction-filter-labels direction-filter)
      direction-filter
      :all)))

(defn filter-open-orders-by-direction [orders direction-filter]
  (let [orders* (or orders [])]
    (case direction-filter
      :long (filterv #(= "B" (:side %)) orders*)
      :short (filterv #(contains? short-order-side-values (:side %)) orders*)
      (vec orders*))))

(defn- open-order-coin-base-label
  [coin market-by-key]
  (or (some-> (shared/resolve-coin-display coin market-by-key)
              :base-label)
      ""))

(defn- build-open-orders-coin-search-index
  [rows market-by-key]
  (let [rows* (or rows [])
        candidates-by-coin (volatile! {})]
    (mapv (fn [row]
            (let [coin (:coin row)
                  cached (get @candidates-by-coin coin)
                  candidates (or cached
                                 (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin market-by-key)
                                       normalized (shared/normalized-coin-search-candidates
                                                   [coin base-label prefix-label])]
                                   (vswap! candidates-by-coin assoc coin normalized)
                                   normalized))]
              [row candidates]))
          rows*)))

(def ^:dynamic *build-open-orders-coin-search-index*
  build-open-orders-coin-search-index)

(defn- filter-open-orders-by-coin-search
  [rows indexed-rows coin-search]
  (let [query (shared/compile-coin-search-query coin-search)]
    (if (shared/coin-search-query-blank? query)
      (vec (or rows []))
      (into []
            (comp (filter (fn [[_ normalized-candidates]]
                            (shared/normalized-coin-candidates-match? normalized-candidates query)))
                  (map first))
            (or indexed-rows [])))))

(defn- coin-style [side]
  (case side
    "B" {:color long-coin-color}
    "A" {:color sell-coin-color}
    "S" {:color sell-coin-color}
    nil))

(defn- open-orders-coin-node [coin market-by-key side]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin market-by-key)
        node-style (coin-style side)]
    (shared/coin-select-control
     coin
     [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
      [:span (cond-> {:class (cond-> ["whitespace-nowrap"]
                                side
                                (conj "font-semibold"))}
               node-style
               (assoc :style node-style))
       base-label]
      (when prefix-label
        [:span {:class shared/position-chip-classes} prefix-label])]
     {:extra-classes ["w-full" "justify-start" "text-left"]})))

(defn sort-open-orders-by-column
  ([orders column direction]
   (sort-open-orders-by-column orders column direction {}))
  ([orders column direction market-by-key]
   (sort-kernel/sort-rows-by-column
    orders
    {:column column
     :direction direction
     :accessor-by-column
     {"Time" (fn [o] (shared/parse-num (:time o)))
      "Type" (fn [o] (or (:type o) ""))
      "Coin" (fn [o] (some-> (open-order-coin-base-label (:coin o) market-by-key)
                             str/lower-case))
      "Direction" (fn [o] (direction-label (:side o)))
      "Size" (fn [o] (shared/parse-num (:sz o)))
      "Original Size" (fn [o] (shared/parse-num (or (:orig-sz o) (:sz o))))
      "Order Value" (fn [o] (or (order-value o) 0))
      "Price" (fn [o] (shared/parse-num (:px o)))}})))

(defonce ^:private sorted-open-orders-cache (atom nil))

(defn reset-open-orders-sort-cache! []
  (reset! sorted-open-orders-cache nil))

(defn- memoized-sorted-open-orders [orders direction-filter sort-state market-by-key coin-search]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-open-orders-cache
        row-match (cache-keys/rows-match-state orders
                                               (:orders cache)
                                               (:orders-signature cache))
        market-match (cache-keys/value-match-state market-by-key
                                                   (:market-by-key cache)
                                                   (:market-signature cache))
        market-affects-base-sort? (= column "Coin")
        same-base? (and (map? cache)
                        (:same-input? row-match)
                        (= direction-filter (:direction-filter cache))
                        (= column (:column cache))
                        (= direction (:direction cache))
                        (or (not market-affects-base-sort?)
                            (:same-input? market-match)))
        same-index? (and same-base?
                         (:same-input? market-match))
        cache-hit? (and same-index?
                        (= coin-search (:coin-search cache)))]
    (if cache-hit?
      (:result cache)
      (let [base-sorted (if same-base?
                          (:base-sorted cache)
                          (vec (sort-open-orders-by-column
                                (filter-open-orders-by-direction orders direction-filter)
                                column
                                direction
                                market-by-key)))
            indexed-rows (if same-index?
                           (:indexed-rows cache)
                           (*build-open-orders-coin-search-index* base-sorted market-by-key))
            result (filter-open-orders-by-coin-search base-sorted indexed-rows coin-search)]
        (reset! sorted-open-orders-cache {:orders orders
                                          :orders-signature (:signature row-match)
                                          :direction-filter direction-filter
                                          :coin-search coin-search
                                          :column column
                                          :direction direction
                                          :market-by-key market-by-key
                                          :market-signature (:signature market-match)
                                          :base-sorted base-sorted
                                          :indexed-rows indexed-rows
                                          :result result})
        result))))

(defn sortable-open-orders-header [column-name sort-state]
  (table/sortable-header-button column-name sort-state :actions/sort-open-orders))

(def ^:private open-orders-grid-template-class
  "grid-cols-[minmax(130px,1.4fr)_minmax(64px,0.64fr)_minmax(90px,1.15fr)_minmax(64px,0.68fr)_minmax(56px,0.58fr)_minmax(80px,0.8fr)_minmax(96px,1fr)_minmax(68px,0.72fr)_minmax(76px,0.82fr)_minmax(112px,1.15fr)_minmax(64px,0.72fr)_minmax(72px,0.74fr)]")

(def ^:private cancel-all-header-button-classes
  ["flex"
   "w-full"
   "items-center"
   "justify-start"
   "min-h-6"
   "py-0.5"
   "text-m"
   "font-medium"
   "text-trading-red"
   "transition-colors"
   "hover:text-[#f2b8c5]"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus-visible:outline-none"
   "focus-visible:ring-0"
   "focus-visible:ring-offset-0"])

(defn- cancel-all-header-button
  [visible-orders]
  [:button {:type "button"
            :class cancel-all-header-button-classes
            :aria-label "Cancel all visible open orders"
            :on {:click [[:actions/confirm-cancel-visible-open-orders
                          visible-orders
                          :event.currentTarget/bounds]]}}
   "Cancel All"])

(def ^:private cancel-visible-confirmation-mobile-breakpoint-px
  768)

(def ^:private cancel-visible-confirmation-preferred-width-px
  392)

(def ^:private cancel-visible-confirmation-estimated-height-px
  248)

(defn- cancel-visible-confirmation-anchored?
  [confirmation]
  (let [anchor (or (:anchor confirmation) {})
        viewport-width (or (:viewport-width anchor) 1280)]
    (and (anchored-popover/complete-anchor? anchor)
         (> viewport-width cancel-visible-confirmation-mobile-breakpoint-px))))

(defn- visible-order-count-label
  [order-count]
  (str order-count " visible open order"
       (when (not= 1 order-count) "s")))

(defn- cancel-visible-open-orders-confirmation-view
  [confirmation]
  (let [confirmation* (or confirmation (cancel-visible-confirmation/default-state))]
    (when (cancel-visible-confirmation/open? confirmation*)
      (let [orders* (->> (or (:orders confirmation*) [])
                         (filter map?)
                         vec)
            order-count (count orders*)
            anchored? (cancel-visible-confirmation-anchored? confirmation*)
            popover-style (when anchored?
                            (anchored-popover/anchored-popover-layout-style
                             {:anchor (:anchor confirmation*)
                              :preferred-width-px cancel-visible-confirmation-preferred-width-px
                              :estimated-height-px cancel-visible-confirmation-estimated-height-px}))]
        [:div {:class (into ["fixed" "inset-0" "z-[220]"]
                            (if anchored?
                              ["pointer-events-none"]
                              ["flex" "items-center" "justify-center" "p-4"]))
               :data-role "open-orders-cancel-visible-confirmation-layer"}
         [:button {:type "button"
                   :class ["absolute" "inset-0" "pointer-events-auto" "bg-black/45" "backdrop-blur-[1px]"]
                   :aria-label "Dismiss cancel visible orders confirmation"
                   :data-role "open-orders-cancel-visible-confirmation-backdrop"
                   :on {:click [[:actions/close-cancel-visible-open-orders-confirmation]]}}]
         [:div {:class ["relative"
                        "z-[221]"
                        "w-full"
                        "max-w-[24rem]"
                        "space-y-4"
                        "rounded-2xl"
                        "border"
                        "border-[#1f3b3c]"
                        "bg-[#081b24]"
                        "p-4"
                        "shadow-[0_24px_60px_rgba(0,0,0,0.45)]"
                        "pointer-events-auto"]
                :style popover-style
                :role "dialog"
                :aria-modal true
                :aria-label "Cancel visible open orders confirmation"
                :tab-index 0
                :data-role "open-orders-cancel-visible-confirmation"
                :on {:keydown [[:actions/handle-cancel-visible-open-orders-confirmation-keydown
                                [:event/key]]]}}
          [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
           [:div {:class ["space-y-2"]}
            [:div {:class ["inline-flex"
                           "items-center"
                           "rounded-full"
                           "border"
                           "border-[#26454d]"
                           "bg-[#0b2732]"
                           "px-2.5"
                           "py-1"
                           "text-xs"
                           "font-medium"
                           "uppercase"
                           "tracking-[0.08em]"
                           "text-[#9cb6bd]"]
                   :data-role "open-orders-cancel-visible-confirmation-count"}
             (visible-order-count-label order-count)]
            [:h3 {:class ["text-lg" "font-semibold" "text-[#e5eef1]"]
                  :data-role "open-orders-cancel-visible-confirmation-title"}
             "Cancel Visible Orders?"]]
           [:button {:type "button"
                     :class ["inline-flex"
                             "h-8"
                             "w-8"
                             "items-center"
                             "justify-center"
                             "rounded-lg"
                             "border"
                             "border-[#17313d]"
                             "bg-transparent"
                             "text-[#94a8ae]"
                             "transition-colors"
                             "hover:bg-[#102229]"
                             "hover:text-[#e5eef1]"
                             "focus:outline-none"
                             "focus-visible:ring-2"
                             "focus-visible:ring-trading-green/70"
                             "focus-visible:ring-offset-1"
                             "focus-visible:ring-offset-[#081b24]"]
                     :aria-label "Close cancel visible orders confirmation"
                     :data-role "open-orders-cancel-visible-confirmation-close"
                     :on {:click [[:actions/close-cancel-visible-open-orders-confirmation]]}}
            "×"]]
          [:div {:class ["space-y-2"]}
           [:p {:class ["text-sm" "leading-6" "text-[#d6e4e8]"]
                :data-role "open-orders-cancel-visible-confirmation-message"}
            (str "Cancel " (visible-order-count-label order-count)
                 " currently shown in Open Orders.")]
           [:p {:class ["text-sm" "leading-6" "text-[#8ea4ab]"]}
            "Orders hidden by the current filter or coin search will not be canceled."]]
          [:div {:class ["flex" "justify-end" "gap-2" "pt-1"]}
           [:button {:type "button"
                     :autofocus true
                     :class ["rounded-lg"
                             "border"
                             "border-[#2c4b50]"
                             "px-3.5"
                             "py-2"
                             "text-sm"
                             "font-medium"
                             "text-[#c5d4d8]"
                             "transition-colors"
                             "hover:border-[#3d666b]"
                             "hover:text-[#e5eef1]"
                             "focus:outline-none"
                             "focus-visible:ring-2"
                             "focus-visible:ring-trading-green/70"
                             "focus-visible:ring-offset-1"
                             "focus-visible:ring-offset-[#081b24]"]
                     :data-role "open-orders-cancel-visible-confirmation-cancel"
                     :on {:click [[:actions/close-cancel-visible-open-orders-confirmation]]}}
            "Keep Orders"]
           [:button {:type "button"
                     :disabled (zero? order-count)
                     :class (into ["rounded-lg"
                                   "border"
                                   "px-3.5"
                                   "py-2"
                                   "text-sm"
                                   "font-medium"
                                   "transition-colors"
                                   "focus:outline-none"
                                   "focus-visible:ring-2"
                                   "focus-visible:ring-[#f2b8c5]/70"
                                   "focus-visible:ring-offset-1"
                                   "focus-visible:ring-offset-[#081b24]"]
                                  (if (zero? order-count)
                                    ["border-[#5f3b46]"
                                     "bg-[#341920]/60"
                                     "text-[#9d7480]"
                                     "cursor-not-allowed"]
                                    ["border-[#7b3340]"
                                     "bg-[#4a1f29]"
                                     "text-[#ffd7de]"
                                     "hover:border-[#9b4354]"
                                     "hover:bg-[#5a2530]"]))
                     :data-role "open-orders-cancel-visible-confirmation-submit"
                     :on {:click [[:actions/submit-cancel-visible-open-orders-confirmation]]}}
            "Cancel Visible Orders"]]]]))))

(defn open-orders-tab-content
  ([normalized sort-state]
   (open-orders-tab-content normalized sort-state {}))
  ([normalized sort-state open-orders-state]
   (let [direction-filter (open-orders-direction-filter-key open-orders-state)
         market-by-key (or (:market-by-key open-orders-state) {})
         coin-search (:coin-search open-orders-state "")
         sorted (memoized-sorted-open-orders normalized direction-filter sort-state market-by-key coin-search)
         confirmation-view (cancel-visible-open-orders-confirmation-view
                            (:cancel-visible-confirmation open-orders-state))]
     (if (seq sorted)
       (table/tab-table-content
        [:div {:class ["grid" "gap-2" "py-1" "px-3" "bg-base-200" "text-xs" "font-medium" open-orders-grid-template-class]}
         [:div.pr-2.text-left.whitespace-nowrap (sortable-open-orders-header "Time" sort-state)]
         [:div.pl-1.text-left (sortable-open-orders-header "Type" sort-state)]
         [:div.text-left (sortable-open-orders-header "Coin" sort-state)]
         [:div.text-left (sortable-open-orders-header "Direction" sort-state)]
         [:div.text-left (sortable-open-orders-header "Size" sort-state)]
         [:div.text-left (sortable-open-orders-header "Original Size" sort-state)]
         [:div.text-left (sortable-open-orders-header "Order Value" sort-state)]
         [:div.text-left (sortable-open-orders-header "Price" sort-state)]
         [:div.text-left.whitespace-nowrap (table/non-sortable-header "Reduce Only")]
         [:div.text-left.whitespace-nowrap (table/non-sortable-header "Trigger Conditions")]
         [:div.text-left (table/non-sortable-header "TP/SL")]
         [:div.text-left (cancel-all-header-button sorted)]]
        (for [o sorted]
          ^{:key (str (:oid o) "-" (:coin o))}
          [:div {:class ["grid" "items-center" "gap-2" "py-px" "px-3" "hover:bg-base-300" "text-xs" open-orders-grid-template-class]}
           [:div.pr-2.text-left.whitespace-nowrap (shared/format-open-orders-time (:time o))]
           [:div.pl-1.text-left (or (:type o) "Order")]
           [:div.text-left (open-orders-coin-node (:coin o) market-by-key (:side o))]
           [:div {:class ["text-left" (direction-class (:side o))]} (direction-label (:side o))]
           [:div.text-left.num (shared/format-currency (:sz o))]
           [:div.text-left.num (shared/format-currency (or (:orig-sz o) (:sz o)))]
           [:div.text-left.num (if-let [val (order-value o)]
                                  (str (shared/format-currency val) " USDC")
                                  "--")]
           [:div.text-left.num (shared/format-trade-price (:px o))]
           [:div.text-left (if (:reduce-only o) "Yes" "No")]
           [:div.text-left (format-trigger-conditions o)]
           [:div.text-left (format-tp-sl o)]
           [:div.text-left
           [:button {:class ["inline-flex"
                              "w-full"
                              "justify-start"
                              "bg-transparent"
                              "p-0"
                              "font-medium"
                              "text-trading-text"
                              "transition-colors"
                              "focus:outline-none"
                              "focus:ring-0"
                              "focus:ring-offset-0"
                              "focus:shadow-none"
                              "focus-visible:outline-none"
                              "focus-visible:ring-0"
                              "focus-visible:ring-offset-0"
                              "hover:text-trading-text"
                              "whitespace-nowrap"]
                      :on {:click [[:actions/cancel-order o]]}}
             "Cancel"]]])
        confirmation-view)
       (if confirmation-view
         [:div {:class ["relative" "h-full"]}
          (empty-state "No open orders")
          confirmation-view]
         (empty-state "No open orders"))))))
