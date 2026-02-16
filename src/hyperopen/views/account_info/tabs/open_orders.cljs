(ns hyperopen.views.account-info.tabs.open-orders
  (:require [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]))

(def ^:private long-coin-color "rgb(151, 252, 228)")
(def ^:private sell-coin-color "rgb(234, 175, 184)")

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

(defn format-tp-sl [{:keys [is-position-tpsl]}]
  (if is-position-tpsl
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

(defn- coin-style [side]
  (case side
    "B" {:color long-coin-color}
    "A" {:color sell-coin-color}
    "S" {:color sell-coin-color}
    nil))

(defn- open-orders-coin-node [coin side]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin {})
        node-style (coin-style side)]
    [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
     [:span (cond-> {:class (cond-> ["truncate"]
                               side
                               (conj "font-semibold"))}
              node-style
              (assoc :style node-style))
      base-label]
     (when prefix-label
       [:span {:class shared/position-chip-classes} prefix-label])]))

(defn sort-open-orders-by-column [orders column direction]
  (let [sort-fn (case column
                  "Time" (fn [o] (shared/parse-num (:time o)))
                  "Type" (fn [o] (or (:type o) ""))
                  "Coin" (fn [o] (or (:coin o) ""))
                  "Direction" (fn [o] (direction-label (:side o)))
                  "Size" (fn [o] (shared/parse-num (:sz o)))
                  "Original Size" (fn [o] (shared/parse-num (or (:orig-sz o) (:sz o))))
                  "Order Value" (fn [o] (or (order-value o) 0))
                  "Price" (fn [o] (shared/parse-num (:px o)))
                  (fn [_] 0))
        sorted (sort-by sort-fn orders)]
    (if (= direction :desc)
      (reverse sorted)
      sorted)))

(defonce ^:private sorted-open-orders-cache (atom nil))

(defn reset-open-orders-sort-cache! []
  (reset! sorted-open-orders-cache nil))

(defn- memoized-sorted-open-orders [orders sort-state]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-open-orders-cache
        cache-hit? (and (map? cache)
                        (identical? orders (:orders cache))
                        (= column (:column cache))
                        (= direction (:direction cache)))]
    (if cache-hit?
      (:result cache)
      (let [result (vec (sort-open-orders-by-column orders column direction))]
        (reset! sorted-open-orders-cache {:orders orders
                                          :column column
                                          :direction direction
                                          :result result})
        result))))

(defn sortable-open-orders-header [column-name sort-state]
  (table/sortable-header-button column-name sort-state :actions/sort-open-orders))

(defn open-orders-tab-content [normalized sort-state]
  (let [sorted (memoized-sorted-open-orders normalized sort-state)]
    (if (seq sorted)
      (table/tab-table-content
       [:div {:class ["grid" "gap-2" "py-1" "px-3" "bg-base-200" "text-xs" "font-medium" "grid-cols-[130px_70px_60px_70px_60px_80px_100px_70px_70px_120px_50px_70px]"]}
        [:div.pr-2.whitespace-nowrap (sortable-open-orders-header "Time" sort-state)]
        [:div.pl-1 (sortable-open-orders-header "Type" sort-state)]
        [:div (sortable-open-orders-header "Coin" sort-state)]
        [:div (sortable-open-orders-header "Direction" sort-state)]
        [:div.text-right (sortable-open-orders-header "Size" sort-state)]
        [:div.text-right (sortable-open-orders-header "Original Size" sort-state)]
        [:div.text-right (sortable-open-orders-header "Order Value" sort-state)]
        [:div.text-right (sortable-open-orders-header "Price" sort-state)]
        [:div.text-left.whitespace-nowrap "Reduce Only"]
        [:div.text-left.whitespace-nowrap "Trigger Conditions"]
        [:div.text-left "TP/SL"]
        [:div.text-left "Cancel All"]]
       (for [o sorted]
         ^{:key (str (:oid o) "-" (:coin o))}
         [:div {:class ["grid" "gap-2" "py-px" "px-3" "hover:bg-base-300" "text-xs" "grid-cols-[130px_70px_60px_70px_60px_80px_100px_70px_70px_120px_50px_70px]"]}
          [:div.pr-2.whitespace-nowrap (shared/format-open-orders-time (:time o))]
          [:div.pl-1 (or (:type o) "Order")]
          [:div (open-orders-coin-node (:coin o) (:side o))]
          [:div {:class (direction-class (:side o))} (direction-label (:side o))]
          [:div.text-right.num.num-right (shared/format-currency (:sz o))]
          [:div.text-right.num.num-right (shared/format-currency (or (:orig-sz o) (:sz o)))]
          [:div.text-right.num.num-right (if-let [val (order-value o)]
                                            (str (shared/format-currency val) " USDC")
                                            "--")]
          [:div.text-right.num.num-right (shared/format-trade-price (:px o))]
          [:div.text-left (if (:reduce-only o) "Yes" "No")]
          [:div.text-left (format-trigger-conditions o)]
          [:div.text-left (format-tp-sl o)]
          [:div.text-left
           [:button {:class ["btn" "btn-xs" "btn-ghost"]
                     :on {:click [[:actions/cancel-order o]]}}
            "Cancel"]]]))
      (empty-state "No open orders"))))
