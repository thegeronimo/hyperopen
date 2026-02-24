(ns hyperopen.views.account-info.tabs.positions
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.sort-kernel :as sort-kernel]
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

(defn- position-side [position-data]
  (let [size-num (shared/parse-optional-num (:szi position-data))]
    (cond
      (and (number? size-num) (neg? size-num)) :short
      (and (number? size-num) (pos? size-num)) :long
      :else :flat)))

(defn- absolute-size-text [size]
  (let [size-text (shared/non-blank-text size)]
    (cond
      (and size-text (str/starts-with? size-text "-")) (subs size-text 1)
      (and size-text (str/starts-with? size-text "+")) (subs size-text 1)
      size-text size-text
      :else (if-let [size-num (shared/parse-optional-num size)]
              (str (js/Math.abs size-num))
              "0"))))

(defn format-position-size [position-data]
  (let [size (absolute-size-text (:szi position-data))
        coin (display-coin position-data)]
    (str size " " coin)))

(defn- explainable-value-node [value-node explanation]
  (if explanation
    [:span {:class ["group" "relative" "inline-flex" "items-center" "underline" "decoration-dashed" "underline-offset-2"]}
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
                     "shadow-lg"
                     "opacity-0"
                     "transition-opacity"
                     "duration-200"
                     "group-hover:opacity-100"
                     "group-focus-within:opacity-100"]}
      explanation]]
    value-node))

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

(defn- funding-display-value [funding-num]
  (when (number? funding-num)
    (if (pos? funding-num)
      (- funding-num)
      funding-num)))

(defn- format-funding-tooltip [all-time-funding since-change-funding]
  (let [all-time-text (if (number? all-time-funding)
                        (str "$" (shared/format-currency all-time-funding))
                        "--")
        since-change-text (if (number? since-change-funding)
                            (str "$" (shared/format-currency since-change-funding))
                            "--")]
    (str "All-time: " all-time-text " Since change: " since-change-text)))

(defn- edit-icon []
  [:svg {:class ["h-3" "w-3" "shrink-0" "text-trading-green"]
         :viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :aria-hidden true}
   [:path {:d "M4 13.5V16h2.5L14 8.5 11.5 6 4 13.5Z"}]
   [:path {:d "M10.5 7 13 9.5"}]])

(defn position-unique-key [position-data]
  (projections/position-unique-key position-data))

(defn collect-positions [webdata2 perp-dex-states]
  (projections/collect-positions webdata2 perp-dex-states))

(defn position-row [position-data]
  (let [pos (:position position-data)
        side (position-side pos)
        chip-classes (shared/position-chip-classes-for-side side)
        coin-cell-style (shared/position-coin-cell-style-for-side side)
        coin-tone-class (shared/position-side-tone-class side)
        size-tone-class (shared/position-side-size-class side)
        coin-label (display-coin pos)
        dex-label (dex-chip-label {:coin (:coin pos)
                                   :dex (:dex position-data)})
        leverage (get-in pos [:leverage :value])
        position-value (:positionValue pos)
        position-value-num (shared/parse-optional-num position-value)
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
        since-change-funding-num (or (shared/parse-optional-num (get-in pos [:cumFunding :sinceChange]))
                                     (shared/parse-optional-num (get-in pos [:cumFunding :since-change]))
                                     (shared/parse-optional-num (get-in pos [:cumFunding :sinceOpen]))
                                     (shared/parse-optional-num (get-in pos [:cumFunding :since-open])))
        display-funding (funding-display-value funding-num)
        display-since-change-funding (funding-display-value since-change-funding-num)
        funding-tooltip (when (number? display-funding)
                          (format-funding-tooltip display-funding display-since-change-funding))
        liq-explanation (or (shared/non-blank-text (:liquidationExplanation pos))
                            (shared/non-blank-text (:liquidation-explanation pos))
                            (shared/non-blank-text (:liquidation-explanation position-data)))]
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
            :style coin-cell-style}
      [:span {:class ["font-medium" "whitespace-nowrap" "shrink-0" coin-tone-class]} coin-label]
      (when (some? leverage)
        [:span {:class chip-classes} (str leverage "x")])
      (when dex-label
        [:span {:class chip-classes} dex-label])]
     [:div {:class ["text-left" "font-semibold" "num" size-tone-class]} (format-position-size pos)]
     [:div.text-left.font-semibold.num
      (if (number? position-value-num)
        (str (shared/format-currency position-value-num) " USDC")
        "--")]
     [:div.text-left.font-semibold.num (shared/format-trade-price entry-price)]
     [:div.text-left.font-semibold.num (shared/format-trade-price mark-price)]
     [:div {:class ["text-left" "font-semibold" "num" pnl-color-class]}
      (format-pnl-inline pnl-num pnl-percent)]
     [:div.text-left.font-semibold.num
      (explainable-value-node
       (if liq-price (shared/format-trade-price liq-price) "N/A")
       liq-explanation)]
     [:div.text-left.font-semibold.num "$" (shared/format-currency margin)]
     [:div.text-left.font-semibold.num
      (explainable-value-node
       [:span {:class [(cond
                         (and (number? display-funding) (neg? display-funding)) "text-error"
                         (and (number? display-funding) (pos? display-funding)) "text-success"
                         :else "text-trading-text")
                       "num"]}
        (if (number? display-funding)
          (str "$" (shared/format-currency display-funding))
          "--")]
       funding-tooltip)]
     [:div.text-left
      [:button {:class ["btn" "btn-xs" "btn-ghost" "gap-1" "px-1.5" "font-normal" "text-trading-text"]
                :type "button"
                :on {:click [[:actions/open-position-tpsl-modal position-data]]}}
       [:span "-- / --"]
       (edit-icon)]]]))

(defn sort-positions-by-column [positions column direction]
  (sort-kernel/sort-rows-by-column
   positions
   {:column column
    :direction direction
    :accessor-by-column
    {"Coin" (fn [pos] (:coin (:position pos)))
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
     "Funding" (fn [pos] (or (shared/parse-optional-num (get-in (:position pos) [:cumFunding :allTime])) 0))}}))

(defonce ^:private sorted-positions-cache (atom nil))

(defn reset-positions-sort-cache! []
  (reset! sorted-positions-cache nil))

(defn- memoized-sorted-positions [positions sort-state]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-positions-cache
        cache-hit? (and (map? cache)
                        (identical? positions (:positions cache))
                        (= column (:column cache))
                        (= direction (:direction cache)))]
    (if cache-hit?
      (:result cache)
      (let [result (vec (sort-positions-by-column positions column direction))]
        (reset! sorted-positions-cache {:positions positions
                                        :column column
                                        :direction direction
                                        :result result})
        result))))

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
   [:div.text-left (sortable-header "PNL (ROE %)" sort-state pnl-header-explanation)]
   [:div.text-left (sortable-header "Liq. Price" sort-state)]
   [:div.text-left (sortable-header "Margin" sort-state margin-header-explanation)]
   [:div.text-left (sortable-header "Funding" sort-state funding-header-explanation)]
   [:div.text-left (table/non-sortable-header "TP/SL")]])

(defn positions-tab-content
  ([positions sort-state]
   (let [positions* (or positions [])
         sorted-positions (if (seq positions*)
                            (memoized-sorted-positions positions* sort-state)
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
