(ns hyperopen.views.account-info.tabs.positions
  (:require [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(defn calculate-mark-price [position-data]
  (or (:markPx position-data)
      (:markPrice position-data)
      (:entryPx position-data)))

(defn- display-coin [position-data]
  (let [coin (:coin position-data)
        parsed (shared/parse-coin-namespace coin)]
    (or (:base parsed)
        (shared/non-blank-text coin)
        "-")))

(defn- dex-chip-label [position-data]
  (let [explicit-dex (shared/non-blank-text (:dex position-data))
        parsed-prefix (some-> (:coin position-data) shared/parse-coin-namespace :prefix)]
    (or explicit-dex parsed-prefix)))

(defn format-position-size [position-data]
  (let [size (or (:szi position-data) "0")
        coin (display-coin position-data)]
    (str size " " coin)))

(defn position-unique-key [position-data]
  (projections/position-unique-key position-data))

(defn collect-positions [webdata2 perp-dex-states]
  (projections/collect-positions webdata2 perp-dex-states))

(defn position-row [position-data]
  (let [pos (:position position-data)
        coin-label (display-coin pos)
        dex-label (dex-chip-label {:coin (:coin pos)
                                   :dex (:dex position-data)})
        leverage (get-in pos [:leverage :value])
        position-value (:positionValue pos)
        entry-price (:entryPx pos)
        mark-price (calculate-mark-price pos)
        pnl-num (shared/parse-optional-num (:unrealizedPnl pos))
        pnl-percent (some-> (:returnOnEquity pos) shared/parse-optional-num (* 100))
        pnl-color-class (cond
                          (and (number? pnl-num) (pos? pnl-num)) "text-success"
                          (and (number? pnl-num) (neg? pnl-num)) "text-error"
                          :else "text-trading-text")
        liq-price (:liquidationPx pos)
        margin (:marginUsed pos)
        funding-num (shared/parse-optional-num (get-in pos [:cumFunding :allTime]))
        display-funding (when (number? funding-num)
                          (if (pos? funding-num)
                            (- funding-num)
                            funding-num))]
    [:div {:class ["grid"
                   shared/positions-grid-template-class
                   "gap-2"
                   "py-0"
                   "pr-3"
                   shared/positions-grid-min-width-class
                   "hover:bg-base-300"
                   "items-center"
                   "text-sm"]}
     [:div {:class ["flex" "items-center" "gap-1.5" "self-stretch" "min-w-[170px]"]
            :style shared/position-coin-cell-style}
      [:span {:class ["font-medium" "whitespace-nowrap" "shrink-0"]} coin-label]
      (when (some? leverage)
        [:span {:class shared/position-chip-classes} (str leverage "x")])
      (when dex-label
        [:span {:class shared/position-chip-classes} dex-label])]
     [:div.text-left.font-semibold.num (format-position-size pos)]
     [:div.text-left.font-semibold.num "$" (shared/format-currency position-value)]
     [:div.text-left.font-semibold.num (shared/format-trade-price entry-price)]
     [:div.text-left.font-semibold.num (shared/format-trade-price mark-price)]
     [:div.text-left.font-semibold.num
      [:div
       [:span {:class [pnl-color-class "num"]}
        (if (number? pnl-num)
          (str "$" (shared/format-currency pnl-num))
          "--")]
       [:div.text-xs.opacity-70.num
        (if (number? pnl-percent)
          [:span {:class (if (pos? pnl-percent) "text-success" "text-error")}
           "(" (if (pos? pnl-percent) "+" "")
           (.toFixed pnl-percent 2)
           "%)"]
          [:span.text-trading-text "--"])]]]
     [:div.text-left.font-semibold.num (if liq-price (shared/format-trade-price liq-price) "N/A")]
     [:div.text-left.font-semibold.num "$" (shared/format-currency margin)]
     [:div.text-left.font-semibold.num
      [:span {:class [(cond
                        (and (number? display-funding) (neg? display-funding)) "text-error"
                        (and (number? display-funding) (pos? display-funding)) "text-success"
                        :else "text-trading-text")
                      "num"]}
       (if (number? display-funding)
         (str "$" (shared/format-currency display-funding))
         "--")]]
     [:div.text-left
      [:button.btn.btn-xs.btn-ghost "-- / --"]]]))

(defn sort-positions-by-column [positions column direction]
  (let [sort-fn (case column
                  "Coin" (fn [pos] (:coin (:position pos)))
                  "Size" (fn [pos] (or (shared/parse-optional-num (:szi (:position pos))) 0))
                  "Position Value" (fn [pos] (or (shared/parse-optional-num (:positionValue (:position pos))) 0))
                  "Entry Price" (fn [pos] (or (shared/parse-optional-num (:entryPx (:position pos))) 0))
                  "Mark Price" (fn [pos] (or (shared/parse-optional-num (calculate-mark-price (:position pos))) 0))
                  "PNL (ROE %)" (fn [pos] (or (shared/parse-optional-num (:unrealizedPnl (:position pos))) 0))
                  "Liq. Price" (fn [pos] (let [liq (:liquidationPx (:position pos))]
                                            (if liq
                                              (or (shared/parse-optional-num liq) js/Number.MAX_VALUE)
                                              js/Number.MAX_VALUE)))
                  "Margin" (fn [pos] (or (shared/parse-optional-num (:marginUsed (:position pos))) 0))
                  "Funding" (fn [pos] (or (shared/parse-optional-num (get-in (:position pos) [:cumFunding :allTime])) 0))
                  (fn [_] 0))
        sorted-positions (sort-by sort-fn positions)]
    (if (= direction :desc)
      (reverse sorted-positions)
      sorted-positions)))

(defn sortable-header [column-name sort-state]
  (table/sortable-header-button column-name sort-state :actions/sort-positions))

(defn position-table-header [sort-state]
  [:div {:class ["grid"
                 shared/positions-grid-template-class
                 "gap-2"
                 "py-1"
                 "pr-3"
                 shared/positions-grid-min-width-class
                 "bg-base-200"]}
   [:div.text-left.pl-3 (sortable-header "Coin" sort-state)]
   [:div.text-left (sortable-header "Size" sort-state)]
   [:div.text-left (sortable-header "Position Value" sort-state)]
   [:div.text-left (sortable-header "Entry Price" sort-state)]
   [:div.text-left (sortable-header "Mark Price" sort-state)]
   [:div.text-left (sortable-header "PNL (ROE %)" sort-state)]
   [:div.text-left (sortable-header "Liq. Price" sort-state)]
   [:div.text-left (sortable-header "Margin" sort-state)]
   [:div.text-left (sortable-header "Funding" sort-state)]
   [:div.text-left (table/non-sortable-header "TP/SL")]])

(defn positions-tab-content
  ([positions sort-state]
   (let [positions* (or positions [])
         sorted-positions (if (seq positions*)
                            (sort-positions-by-column positions*
                                                      (:column sort-state)
                                                      (:direction sort-state))
                            [])]
     (if (seq positions*)
       (table/tab-table-content (position-table-header sort-state)
                                (for [position sorted-positions]
                                  ^{:key (position-unique-key position)}
                                  (position-row position)))
       (empty-state "No active positions"))))
  ([webdata2 sort-state perp-dex-states]
   (positions-tab-content (collect-positions webdata2 perp-dex-states)
                          sort-state)))
