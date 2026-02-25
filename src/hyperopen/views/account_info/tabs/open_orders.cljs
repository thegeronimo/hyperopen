(ns hyperopen.views.account-info.tabs.open-orders
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.table :as table]))

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

(defn- open-order-matches-coin-search?
  [row query]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display (:coin row) {})]
    (or (shared/coin-matches-search? (:coin row) query)
        (shared/coin-matches-search? base-label query)
        (shared/coin-matches-search? prefix-label query))))

(defn- filter-open-orders-by-coin-search
  [rows coin-search]
  (let [query (shared/normalize-coin-search-query coin-search)
        rows* (or rows [])]
    (if (str/blank? query)
      (vec rows*)
      (filterv #(open-order-matches-coin-search? % query) rows*))))

(defn- coin-style [side]
  (case side
    "B" {:color long-coin-color}
    "A" {:color sell-coin-color}
    "S" {:color sell-coin-color}
    nil))

(defn- open-orders-coin-node [coin side]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin {})
        node-style (coin-style side)]
    (shared/coin-select-control
     coin
     [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
      [:span (cond-> {:class (cond-> ["truncate"]
                                side
                                (conj "font-semibold"))}
               node-style
               (assoc :style node-style))
       base-label]
      (when prefix-label
        [:span {:class shared/position-chip-classes} prefix-label])]
     {:extra-classes ["w-full" "justify-start" "text-left"]})))

(defn sort-open-orders-by-column [orders column direction]
  (sort-kernel/sort-rows-by-column
   orders
   {:column column
    :direction direction
    :accessor-by-column
    {"Time" (fn [o] (shared/parse-num (:time o)))
     "Type" (fn [o] (or (:type o) ""))
     "Coin" (fn [o] (or (:coin o) ""))
     "Direction" (fn [o] (direction-label (:side o)))
     "Size" (fn [o] (shared/parse-num (:sz o)))
     "Original Size" (fn [o] (shared/parse-num (or (:orig-sz o) (:sz o))))
     "Order Value" (fn [o] (or (order-value o) 0))
     "Price" (fn [o] (shared/parse-num (:px o)))}}))

(defonce ^:private sorted-open-orders-cache (atom nil))

(defn reset-open-orders-sort-cache! []
  (reset! sorted-open-orders-cache nil))

(defn- memoized-sorted-open-orders [orders direction-filter sort-state coin-search]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-open-orders-cache
        cache-hit? (and (map? cache)
                        (identical? orders (:orders cache))
                        (= direction-filter (:direction-filter cache))
                        (= coin-search (:coin-search cache))
                        (= column (:column cache))
                        (= direction (:direction cache)))]
    (if cache-hit?
      (:result cache)
      (let [direction-filtered (filter-open-orders-by-direction orders direction-filter)
            search-filtered (filter-open-orders-by-coin-search direction-filtered coin-search)
            result (vec (sort-open-orders-by-column search-filtered column direction))]
        (reset! sorted-open-orders-cache {:orders orders
                                          :direction-filter direction-filter
                                          :coin-search coin-search
                                          :column column
                                          :direction direction
                                          :result result})
        result))))

(defn sortable-open-orders-header [column-name sort-state]
  (table/sortable-header-button column-name sort-state :actions/sort-open-orders))

(def ^:private open-orders-grid-template-class
  "grid-cols-[minmax(130px,1.45fr)_minmax(70px,0.75fr)_minmax(60px,0.7fr)_minmax(70px,0.8fr)_minmax(60px,0.7fr)_minmax(80px,0.85fr)_minmax(100px,1fr)_minmax(70px,0.8fr)_minmax(80px,0.95fr)_minmax(120px,1.35fr)_minmax(70px,0.8fr)_minmax(80px,0.9fr)]")

(defn open-orders-tab-content
  ([normalized sort-state]
   (open-orders-tab-content normalized sort-state {}))
  ([normalized sort-state open-orders-state]
   (let [direction-filter (open-orders-direction-filter-key open-orders-state)
         coin-search (:coin-search open-orders-state "")
         sorted (memoized-sorted-open-orders normalized direction-filter sort-state coin-search)]
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
         [:div.text-left (table/non-sortable-header "Cancel All")]]
        (for [o sorted]
          ^{:key (str (:oid o) "-" (:coin o))}
          [:div {:class ["grid" "gap-2" "py-px" "px-3" "hover:bg-base-300" "text-xs" open-orders-grid-template-class]}
           [:div.pr-2.text-left.whitespace-nowrap (shared/format-open-orders-time (:time o))]
           [:div.pl-1.text-left (or (:type o) "Order")]
           [:div.text-left (open-orders-coin-node (:coin o) (:side o))]
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
            [:button {:class ["btn" "btn-xs" "btn-ghost"]
                      :on {:click [[:actions/cancel-order o]]}}
             "Cancel"]]]))
       (empty-state "No open orders")))))
