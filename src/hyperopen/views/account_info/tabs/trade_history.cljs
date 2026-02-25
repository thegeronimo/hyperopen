(ns hyperopen.views.account-info.tabs.trade-history
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.history-pagination :as history-pagination]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.table :as table]
            [hyperopen.utils.formatting :as fmt]))

(def default-trade-history-sort
  {:column "Time" :direction :desc})

(def trade-history-direction-filter-options
  [[:all "All"]
   [:long "Long"]
   [:short "Short"]])

(def trade-history-direction-filter-labels
  (into {} trade-history-direction-filter-options))

(defn trade-history-direction-filter-key [trade-history-state]
  (let [raw-direction (:direction-filter trade-history-state)
        direction-filter (cond
                           (keyword? raw-direction) raw-direction
                           (string? raw-direction) (keyword (str/lower-case raw-direction))
                           :else :all)]
    (if (contains? trade-history-direction-filter-labels direction-filter)
      direction-filter
      :all)))

(defn trade-history-sort-state [trade-history-state]
  (merge default-trade-history-sort
         (or (:sort trade-history-state) {})))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(defn- external-link-icon
  ([] (external-link-icon ["h-3" "w-3" "shrink-0"]))
  ([class-names]
   [:svg {:class class-names
          :viewBox "0 0 20 20"
          :fill "none"
          :stroke "currentColor"
          :stroke-width "1.8"
          :aria-hidden true}
    [:path {:d "M8 4h8v8"}]
    [:path {:d "M16 4 7 13"}]
    [:path {:d "M14 10v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"}]]))

(defn- trade-history-coin [row]
  (projections/trade-history-coin row))

(defn- trade-history-time-ms [row]
  (projections/trade-history-time-ms row))

(def ^:private trade-history-explorer-tx-base-url
  "https://app.hyperliquid.xyz/explorer/tx/")

(def ^:private trade-history-tx-hash-pattern
  #"^0x[0-9a-fA-F]{64}$")

(defn- trade-history-tx-hash [row]
  (shared/non-blank-text (or (:hash row)
                              (:txHash row)
                              (:tx-hash row))))

(defn- valid-trade-history-tx-hash? [hash-value]
  (boolean (and hash-value
                (re-matches trade-history-tx-hash-pattern hash-value))))

(defn- trade-history-explorer-tx-url [row]
  (let [hash-value (trade-history-tx-hash row)]
    (when (valid-trade-history-tx-hash? hash-value)
      (str trade-history-explorer-tx-base-url hash-value))))

(defn- trade-history-time-node [row]
  (let [formatted-time (shared/format-open-orders-time (trade-history-time-ms row))]
    (if-let [explorer-url (trade-history-explorer-tx-url row)]
      [:a {:href explorer-url
           :target "_blank"
           :rel "noopener noreferrer"
           :class ["inline-flex"
                   "min-h-6"
                   "items-center"
                   "gap-0.5"
                   "whitespace-nowrap"
                   "rounded"
                   "text-trading-green"
                   "hover:text-trading-green/80"
                   "focus-visible:outline-none"
                   "focus-visible:ring-2"
                   "focus-visible:ring-trading-green/70"
                   "focus-visible:ring-offset-1"
                   "focus-visible:ring-offset-base-100"]}
       [:span formatted-time]
       (external-link-icon)]
      formatted-time)))

(defn- format-usdc-amount [value]
  (if-let [num (shared/parse-optional-num value)]
    (str (.toLocaleString (js/Number. num)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         " USDC")
    "--"))

(defn- trade-history-direction-base-label [row]
  (or (shared/non-blank-text (or (:dir row) (:direction row)))
      (case (:side row)
        "B" "Open Long"
        "A" "Open Short"
        "S" "Open Short"
        "-")))

(defn- trade-history-action-side-from-label [direction-label]
  (let [normalized (some-> direction-label shared/non-blank-text str/lower-case)]
    (cond
      (not normalized) nil
      (str/includes? normalized "sell") :sell
      (str/includes? normalized "open short") :sell
      (str/includes? normalized "close long") :sell
      (str/includes? normalized "buy") :buy
      (str/includes? normalized "open long") :buy
      (str/includes? normalized "close short") :buy
      :else nil)))

(defn- trade-history-action-side [row direction-label]
  (or (trade-history-action-side-from-label direction-label)
      (case (:side row)
        "B" :buy
        "A" :sell
        "S" :sell
        nil)))

(defn- trade-history-action-class [row direction-label]
  (case (trade-history-action-side row direction-label)
    :buy "text-success"
    :sell "text-error"
    "text-trading-text"))

(def ^:private trade-history-price-improved-tooltip-text
  "This fill price was more favorable to you than the price chart at that time, because your order provided liquidity to another user's liquidation.")

(defn- trade-history-liquidation-fill? [row]
  (let [liquidation (:liquidation row)]
    (and (map? liquidation)
         (or (some? (:markPx liquidation))
             (some? (:method liquidation))
             (some? (:liquidatedUser liquidation))))))

(defn- trade-history-liquidation-close-label [row direction-label]
  (let [direction-label* (shared/non-blank-text direction-label)
        normalized (some-> direction-label* str/lower-case)]
    (when (and (trade-history-liquidation-fill? row)
               normalized
               (not (str/includes? normalized "liquidation"))
               (or (re-matches #"^close\s+long$" normalized)
                   (re-matches #"^close\s+short$" normalized)))
      (str "Market Order Liquidation: " direction-label*))))

(defn- trade-history-price-improved? [row direction-label]
  (let [direction-label* (or (shared/non-blank-text direction-label)
                             (trade-history-direction-base-label row))
        normalized (some-> direction-label* str/lower-case)
        liquidation-fill? (trade-history-liquidation-fill? row)
        liquidation-direction? (and normalized
                                   (str/includes? normalized "liquidation"))
        price-improved-text? (and normalized
                                  (str/includes? normalized "price improved"))]
    (boolean (or price-improved-text?
                 (and liquidation-fill?
                      normalized
                      (not= normalized "-")
                      (not liquidation-direction?))))))

(defn- trade-history-direction-label [row]
  (let [base-label (trade-history-direction-base-label row)
        liquidation-label (trade-history-liquidation-close-label row base-label)
        final-label (or liquidation-label base-label)
        normalized (some-> final-label str/lower-case)]
    (if (and (trade-history-price-improved? row final-label)
             normalized
             (not (str/includes? normalized "price improved")))
      (str final-label " (Price Improved)")
      final-label)))

(defn- trade-history-direction-node [row]
  (let [direction-label (trade-history-direction-label row)
        direction-class (trade-history-action-class row direction-label)]
    (if (trade-history-price-improved? row direction-label)
      [:div {:class ["text-left" direction-class]}
       [:div {:class ["group" "relative" "inline-flex" "min-h-6" "items-center"]}
        [:span {:class ["cursor-help"
                        "rounded"
                        "underline"
                        "decoration-dotted"
                        "underline-offset-2"
                        "focus-visible:outline-none"
                        "focus-visible:ring-2"
                        "focus-visible:ring-trading-green/70"
                        "focus-visible:ring-offset-1"
                        "focus-visible:ring-offset-base-100"]
                :tab-index 0}
         direction-label]
        [:div {:class ["pointer-events-none"
                       "absolute"
                       "left-0"
                       "bottom-full"
                       "z-50"
                       "mb-2"
                       "opacity-0"
                       "transition-opacity"
                       "duration-200"
                       "group-hover:opacity-100"
                       "group-focus-within:opacity-100"]}
         [:div {:class ["relative"
                        "w-[520px]"
                        "max-w-[calc(100vw-2rem)]"
                        "min-w-[380px]"
                        "rounded-md"
                        "bg-gray-800"
                        "px-3"
                        "py-1.5"
                        "text-xs"
                        "leading-tight"
                        "text-gray-100"
                        "shadow-lg"
                        "whitespace-normal"]}
          trade-history-price-improved-tooltip-text
          [:div {:class ["absolute"
                         "top-full"
                         "left-3"
                         "h-0"
                         "w-0"
                         "border-4"
                         "border-transparent"
                         "border-t-gray-800"]}]]]]]
      [:div {:class ["text-left" direction-class]}
       direction-label])))

(defn- trade-history-coin-node [row market-by-key]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display (trade-history-coin row)
                                                                        market-by-key)
        direction-label (trade-history-direction-label row)
        direction-class (trade-history-action-class row direction-label)]
    [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
     [:span {:class (cond-> ["truncate"]
                      direction-class
                      (conj direction-class))}
      base-label]
     (when prefix-label
       [:span {:class shared/position-chip-classes} prefix-label])]))

(defn- format-trade-history-price [row]
  (let [price (or (:px row) (:price row) (:p row))]
    (if-let [num (shared/parse-optional-num price)]
      (or (fmt/format-trade-price-plain num price) "--")
      "--")))

(defn- format-order-history-size [value]
  (if-let [num (shared/parse-optional-num value)]
    (.toLocaleString (js/Number. num)
                     "en-US"
                     #js {:minimumFractionDigits 0
                          :maximumFractionDigits 6})
    "--"))

(defn- format-trade-history-size [row market-by-key]
  (let [size-raw (or (:sz row) (:size row) (:s row))
        size-text (if-let [size-string (shared/non-blank-text size-raw)]
                    size-string
                    (format-order-history-size size-raw))
        {:keys [base-label]} (shared/resolve-coin-display (trade-history-coin row) market-by-key)]
    (if (= size-text "--")
      "--"
      (str size-text " " base-label))))

(defn- trade-history-value-number [row]
  (projections/trade-history-value-number row))

(defn- format-trade-history-value [row]
  (format-usdc-amount (trade-history-value-number row)))

(defn- format-trade-history-fee [row]
  (format-usdc-amount (projections/trade-history-fee-number row)))

(defn- format-trade-history-closed-pnl [row]
  (format-usdc-amount (projections/trade-history-closed-pnl-number row)))

(defn- trade-history-closed-pnl-class [row]
  (let [value (projections/trade-history-closed-pnl-number row)]
    (cond
      (and (number? value) (pos? value)) "text-success"
      (and (number? value) (neg? value)) "text-error"
      :else "text-trading-text")))

(defn- trade-history-row-id [row]
  (projections/trade-history-row-id row))

(defn sort-trade-history-by-column
  ([rows column direction]
   (sort-trade-history-by-column rows column direction {}))
  ([rows column direction market-by-key]
   (sort-kernel/sort-rows-by-column
    rows
    {:column column
     :direction direction
     :accessor-by-column
     {"Time" (fn [row]
               (or (trade-history-time-ms row) 0))
      "Coin" (fn [row]
               (or (some-> (shared/resolve-coin-display (trade-history-coin row) market-by-key)
                           :base-label
                           str/lower-case)
                   ""))
      "Direction" (fn [row]
                    (or (trade-history-direction-label row) ""))
      "Price" (fn [row]
                (or (shared/parse-optional-num (or (:px row) (:price row) (:p row))) 0))
      "Size" (fn [row]
               (or (shared/parse-optional-num (or (:sz row) (:size row) (:s row))) 0))
      "Trade Value" (fn [row]
                      (or (trade-history-value-number row) 0))
      "Fee" (fn [row]
              (or (projections/trade-history-fee-number row) 0))
      "Closed PNL" (fn [row]
                     (or (projections/trade-history-closed-pnl-number row) 0))}
     :tie-breaker trade-history-row-id})))

(defonce ^:private sorted-trade-history-cache (atom nil))

(defn reset-trade-history-sort-cache! []
  (reset! sorted-trade-history-cache nil))

(defn- filter-trade-history-by-direction [rows direction-filter]
  (let [rows* (or rows [])]
    (case direction-filter
      :long (filterv (fn [row]
                       (= :buy
                          (trade-history-action-side row
                                                     (trade-history-direction-label row))))
                     rows*)
      :short (filterv (fn [row]
                        (= :sell
                           (trade-history-action-side row
                                                      (trade-history-direction-label row))))
                      rows*)
      (vec rows*))))

(defn- memoized-sorted-trade-history [rows direction-filter sort-state market-by-key]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-trade-history-cache
        cache-hit? (and (map? cache)
                        (identical? rows (:rows cache))
                        (= direction-filter (:direction-filter cache))
                        (identical? market-by-key (:market-by-key cache))
                        (= column (:column cache))
                        (= direction (:direction cache)))]
    (if cache-hit?
      (:result cache)
      (let [filtered-rows (filter-trade-history-by-direction rows direction-filter)
            result (vec (sort-trade-history-by-column filtered-rows column direction market-by-key))]
        (reset! sorted-trade-history-cache {:rows rows
                                            :direction-filter direction-filter
                                            :column column
                                            :direction direction
                                            :market-by-key market-by-key
                                            :result result})
        result))))

(defn sortable-trade-history-header [column-name sort-state]
  (table/sortable-header-button column-name sort-state :actions/sort-trade-history))

(defn trade-history-table [fills trade-history-state]
  (let [all-rows (cond
                   (vector? fills) fills
                   (seq fills) (vec fills)
                   :else [])
        market-by-key (or (:market-by-key trade-history-state) {})
        direction-filter (trade-history-direction-filter-key trade-history-state)
        sort-state (trade-history-sort-state trade-history-state)
        sorted-rows (memoized-sorted-trade-history all-rows direction-filter sort-state market-by-key)
        {:keys [rows] :as pagination} (history-pagination/paginate-history-rows sorted-rows trade-history-state)]
    (if (seq sorted-rows)
      (table/tab-table-content
       [:div {:class ["grid"
                      "gap-2"
                      "py-1"
                      "px-3"
                      "bg-base-200"
                      "text-sm"
                      "font-medium"
                      "text-trading-text-secondary"
                      "grid-cols-[180px_90px_160px_90px_130px_130px_110px_120px]"]}
        [:div {:class ["text-left"]} (sortable-trade-history-header "Time" sort-state)]
        [:div {:class ["text-left"]} (sortable-trade-history-header "Coin" sort-state)]
        [:div {:class ["text-left"]} (sortable-trade-history-header "Direction" sort-state)]
        [:div {:class ["text-left"]} (sortable-trade-history-header "Price" sort-state)]
        [:div {:class ["text-left"]} (sortable-trade-history-header "Size" sort-state)]
        [:div {:class ["text-left"]} (sortable-trade-history-header "Trade Value" sort-state)]
        [:div {:class ["text-left"]} (sortable-trade-history-header "Fee" sort-state)]
        [:div {:class ["text-left"]} (sortable-trade-history-header "Closed PNL" sort-state)]]
       (for [f rows]
         ^{:key (trade-history-row-id f)}
         [:div {:class ["grid"
                        "gap-2"
                        "py-px"
                        "px-3"
                        "hover:bg-base-300"
                        "text-sm"
                        "grid-cols-[180px_90px_160px_90px_130px_130px_110px_120px]"]}
          [:div {:class ["text-left" "text-xs" "whitespace-nowrap"]}
           (trade-history-time-node f)]
          [:div {:class ["text-left"]}
           (trade-history-coin-node f market-by-key)]
          (trade-history-direction-node f)
          [:div {:class ["text-left" "num"]}
           (format-trade-history-price f)]
          [:div {:class ["text-left" "num"]}
           (format-trade-history-size f market-by-key)]
          [:div {:class ["text-left" "num"]}
           (format-trade-history-value f)]
          [:div {:class ["text-left" "num"]}
           (format-trade-history-fee f)]
          [:div {:class ["text-left" "num" (trade-history-closed-pnl-class f)]}
           (format-trade-history-closed-pnl f)]])
       (history-pagination/trade-history-pagination-controls pagination))
      (empty-state "No fills"))))

(defn trade-history-tab-content
  ([fills]
   (trade-history-table fills {}))
  ([fills trade-history-state]
   (trade-history-table fills trade-history-state)))
