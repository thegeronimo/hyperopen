(ns hyperopen.views.account-info.tabs.order-history
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.cache-keys :as cache-keys]
            [hyperopen.views.account-info.history-pagination :as history-pagination]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.table :as table]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]
            [hyperopen.utils.formatting :as fmt]))

(def order-history-status-options
  [[:all "All"]
   [:long "Long"]
   [:short "Short"]])

(def order-history-status-labels
  (into {} order-history-status-options))

(def order-history-page-size-options
  history-pagination/order-history-page-size-options)

(def default-order-history-page-size
  history-pagination/default-order-history-page-size)

(def default-order-history-sort
  {:column "Time" :direction :desc})

(def order-history-long-coin-color "rgb(151, 252, 228)")
(def order-history-sell-coin-color "rgb(234, 175, 184)")
(def ^:private short-order-side-values #{"A" "S"})
(def ^:private order-history-status-tooltip-width-class
  "w-max")

(defn order-history-status-filter-key [order-history-state]
  (let [status-filter (:status-filter order-history-state)]
    (if (contains? order-history-status-labels status-filter)
      status-filter
      :all)))

(defn order-history-sort-state [order-history-state]
  (merge default-order-history-sort
         (or (:sort order-history-state) {})))

(defn normalize-order-history-page-size [value]
  (history-pagination/normalize-order-history-page-size value))

(defn normalize-order-history-page
  ([value]
   (history-pagination/normalize-order-history-page value nil))
  ([value max-page]
   (history-pagination/normalize-order-history-page value max-page)))

(defn normalize-order-history-row [row]
  (when-let [normalized (projections/normalize-order-history-row row)]
    (assoc normalized
           :status-label (or (:status-label normalized)
                             (projections/order-history-status-label (:status normalized)
                                                                     (:remaining-size-num normalized)
                                                                     order-history-status-labels)))))

(defn normalized-order-history [rows]
  (->> (projections/normalized-order-history rows)
       (mapv (fn [row]
               (assoc row
                      :status-label (or (:status-label row)
                                        (projections/order-history-status-label (:status row)
                                                                                (:remaining-size-num row)
                                                                                order-history-status-labels)))))))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(defn- title-case-label [value]
  (shared/title-case-label value))

(defn- order-history-coin-style [side]
  (case side
    "B" {:color order-history-long-coin-color}
    "A" {:color order-history-sell-coin-color}
    "S" {:color order-history-sell-coin-color}
    nil))

(defn- order-history-coin-node
  ([coin]
   (order-history-coin-node coin {} nil))
  ([coin market-by-key]
   (order-history-coin-node coin market-by-key nil))
  ([coin market-by-key side]
   (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin market-by-key)
         coin-style (order-history-coin-style side)]
     (shared/coin-select-control
      coin
      [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
       [:span (cond-> {:class (cond-> ["truncate"]
                                side
                                (conj "font-semibold"))}
                coin-style
                (assoc :style coin-style))
        base-label]
       (when prefix-label
         [:span {:class shared/position-chip-classes} prefix-label])]
      {:extra-classes ["w-full" "justify-start" "text-left"]}))))

(defn- order-history-row-sort-id [row]
  (str (or (:time-ms row) 0)
       "|"
       (or (:coin row) "")
       "|"
       (or (:oid row) "")
       "|"
       (or (:status-label row) "")))

(defn- format-order-history-size [value]
  (if-let [num (shared/parse-optional-num value)]
    (.toLocaleString (js/Number. num)
                     "en-US"
                     #js {:minimumFractionDigits 0
                          :maximumFractionDigits 6})
    "--"))

(defn format-order-history-filled-size [filled-size]
  (if (and (number? filled-size)
           (pos? filled-size))
    (format-order-history-size filled-size)
    "--"))

(defn- normalized-direction-label [value]
  (let [text (shared/non-blank-text value)]
    (when text
      (->> (str/split text #"\s+")
           (remove str/blank?)
           (map str/capitalize)
           (str/join " ")))))

(defn- order-history-direction-label
  [{:keys [direction side reduce-only]}]
  (or (normalized-direction-label direction)
      (if reduce-only
        (case side
          "B" "Close Short"
          "A" "Close Long"
          "S" "Close Long"
          (open-orders-tab/direction-label side))
        (open-orders-tab/direction-label side))))

(defn- order-history-direction-class
  [row]
  (let [side-class (open-orders-tab/direction-class (:side row))
        direction-label (some-> (order-history-direction-label row) str/lower-case)]
    (if (not= side-class "text-base-content")
      side-class
      (cond
        (or (str/includes? direction-label "buy")
            (str/includes? direction-label "open long")
            (str/includes? direction-label "close short"))
        "text-success"

        (or (str/includes? direction-label "sell")
            (str/includes? direction-label "open short")
            (str/includes? direction-label "close long"))
        "text-error"

        :else "text-base-content"))))

(defn- format-order-history-value [order-value]
  (if (and (number? order-value)
           (pos? order-value))
    (str (.toLocaleString (js/Number. order-value)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         " USDC")
    "--"))

(defn format-order-history-price [{:keys [market? px]}]
  (if market?
    "Market"
    (if-let [num (shared/parse-optional-num px)]
      (or (fmt/format-trade-price-plain num px) "0.00")
      "--")))

(defn format-order-history-reduce-only [{:keys [reduce-only]}]
  (case reduce-only
    true "Yes"
    false "No"
    "--"))

(defn format-order-history-trigger [{:keys [is-trigger trigger-condition trigger-px]}]
  (if (and is-trigger
           (pos? (or (shared/parse-optional-num trigger-px) 0)))
    (let [label (open-orders-tab/trigger-condition-label trigger-condition)
          trigger-px-num (shared/parse-optional-num trigger-px)
          price (if (number? trigger-px-num)
                  (or (fmt/format-trade-price-plain trigger-px-num trigger-px) "--")
                  "--")]
      (if label
        (str label " " price)
        (str "Trigger @ " price)))
    "N/A"))

(defn- format-order-history-tp-sl [{:keys [is-position-tpsl]}]
  (if is-position-tpsl
    "TP/SL"
    "--"))

(defn- order-history-status-node
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
                       order-history-status-tooltip-width-class
                       "max-w-[calc(100vw-2rem)]"
                       "rounded-md"
                       "bg-gray-800"
                       "px-2.5"
                       "py-1.5"
                       "text-left"
                       "text-xs"
                       "leading-tight"
                       "text-gray-100"
                       "shadow-lg"
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

(defn- order-history-filter-status [rows status-filter]
  (case status-filter
    :long (filter (fn [row]
                    (= "B" (:side row)))
                  rows)
    :short (filter (fn [row]
                     (contains? short-order-side-values (:side row)))
                   rows)
    rows))

(defn- build-order-history-coin-search-index
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

(def ^:dynamic *build-order-history-coin-search-index*
  build-order-history-coin-search-index)

(defn- filter-order-history-by-coin-search
  [rows indexed-rows coin-search]
  (let [query (shared/compile-coin-search-query coin-search)]
    (if (shared/coin-search-query-blank? query)
      (vec (or rows []))
      (into []
            (comp (filter (fn [[_ normalized-candidates]]
                            (shared/normalized-coin-candidates-match? normalized-candidates query)))
                  (map first))
            (or indexed-rows [])))))

(defn sort-order-history-by-column [rows column direction]
  (sort-kernel/sort-rows-by-column
   rows
   {:column column
    :direction direction
    :accessor-by-column
     {"Time" (fn [row] (or (:time-ms row) 0))
      "Type" (fn [row] (title-case-label (:type row)))
      "Coin" (fn [row] (or (:coin row) ""))
      "Direction" (fn [row] (or (order-history-direction-label row) ""))
      "Size" (fn [row] (or (:size-num row) 0))
      "Filled Size" (fn [row] (or (:filled-size row) 0))
     "Order Value" (fn [row] (or (:order-value row) 0))
     "Price" (fn [row] (or (shared/parse-optional-num (:px row)) 0))
     "Reduce Only" (fn [row]
                     (case (:reduce-only row)
                       true 1
                       false 0
                       -1))
     "Trigger Conditions" (fn [row]
                            (format-order-history-trigger row))
     "TP/SL" (fn [row] (if (:is-position-tpsl row) 1 0))
     "Status" (fn [row] (or (:status-label row) ""))
     "Order ID" (fn [row]
                  (let [oid (:oid row)
                        oid-num (shared/parse-optional-num oid)]
                    (if (number? oid-num)
                      [0 oid-num]
                      [1 (str (or oid ""))])))}
    :tie-breaker order-history-row-sort-id}))

(defonce ^:private sorted-order-history-cache (atom nil))

(defn reset-order-history-sort-cache! []
  (reset! sorted-order-history-cache nil))

(defn- memoized-order-history-rows [order-history status-filter sort-state market-by-key coin-search]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-order-history-cache
        row-match (cache-keys/rows-match-state order-history
                                               (:order-history cache)
                                               (:order-history-signature cache))
        market-match (cache-keys/value-match-state market-by-key
                                                   (:market-by-key cache)
                                                   (:market-signature cache))
        same-base? (and (map? cache)
                        (:same-input? row-match)
                        (= status-filter (:status-filter cache))
                        (= column (:column cache))
                        (= direction (:direction cache)))
        same-index? (and same-base?
                         (:same-input? market-match))
        cache-hit? (and same-index?
                        (= coin-search (:coin-search cache)))]
    (if cache-hit?
      (:result cache)
      (let [normalized (if same-base?
                         (:normalized cache)
                         (normalized-order-history order-history))
            status-filtered (if same-base?
                              (:status-filtered cache)
                              (vec (order-history-filter-status normalized status-filter)))
            base-sorted (if same-base?
                          (:base-sorted cache)
                          (vec (sort-order-history-by-column status-filtered column direction)))
            indexed-rows (if same-index?
                           (:indexed-rows cache)
                           (*build-order-history-coin-search-index* base-sorted market-by-key))
            result (filter-order-history-by-coin-search base-sorted indexed-rows coin-search)]
        (reset! sorted-order-history-cache {:order-history order-history
                                            :order-history-signature (:signature row-match)
                                            :status-filter status-filter
                                            :market-by-key market-by-key
                                            :market-signature (:signature market-match)
                                            :coin-search coin-search
                                            :column column
                                            :direction direction
                                            :normalized normalized
                                            :status-filtered status-filtered
                                            :base-sorted base-sorted
                                            :indexed-rows indexed-rows
                                            :result result})
        result))))

(defn sortable-order-history-header
  ([column-name sort-state]
   (sortable-order-history-header column-name sort-state {}))
  ([column-name sort-state options]
   (table/sortable-header-button column-name sort-state :actions/sort-order-history options)))

(def ^:private order-history-grid-template-class
  "grid-cols-[minmax(130px,1.45fr)_minmax(110px,1.25fr)_minmax(84px,0.9fr)_minmax(64px,0.7fr)_minmax(82px,0.9fr)_minmax(72px,0.75fr)_minmax(100px,1.05fr)_minmax(72px,0.8fr)_minmax(74px,0.72fr)_minmax(140px,1.55fr)_minmax(60px,0.65fr)_minmax(120px,1.25fr)_minmax(106px,1.2fr)]")

(defn order-history-table [order-history order-history-state]
  (let [sort-state (order-history-sort-state order-history-state)
        status-filter (order-history-status-filter-key order-history-state)
        market-by-key (or (:market-by-key order-history-state) {})
        coin-search (:coin-search order-history-state "")
        sorted (memoized-order-history-rows order-history
                                            status-filter
                                            sort-state
                                            market-by-key
                                            coin-search)
        {:keys [rows] :as pagination} (history-pagination/paginate-history-rows sorted order-history-state)]
    (if (seq sorted)
      (table/tab-table-content
       [:div {:class ["grid" "gap-2" "py-1" "px-3" "bg-base-200" "text-xs" "font-medium" order-history-grid-template-class]}
        [:div.pr-2.whitespace-nowrap (sortable-order-history-header "Time" sort-state)]
        [:div.pl-1.text-left (sortable-order-history-header "Type" sort-state)]
        [:div.text-left (sortable-order-history-header "Coin" sort-state)]
        [:div.text-left (sortable-order-history-header "Direction" sort-state)]
        [:div.text-left (sortable-order-history-header "Size" sort-state)]
        [:div.text-left (sortable-order-history-header "Filled Size" sort-state)]
        [:div.text-left (sortable-order-history-header "Order Value" sort-state)]
        [:div.text-left (sortable-order-history-header "Price" sort-state)]
        [:div.text-left.whitespace-nowrap (sortable-order-history-header "Reduce Only" sort-state)]
        [:div.text-left.whitespace-nowrap (sortable-order-history-header "Trigger Conditions" sort-state)]
        [:div.text-left (sortable-order-history-header "TP/SL" sort-state)]
        [:div.text-left (sortable-order-history-header "Status" sort-state)]
        [:div.text-left (sortable-order-history-header "Order ID"
                                                      sort-state
                                                      {:extra-classes ["order-history-order-id-text"
                                                                       "tracking-tight"]})]]
       (for [row rows]
         ^{:key (order-history-row-sort-id row)}
         [:div {:class ["grid" "gap-2" "py-px" "px-3" "hover:bg-base-300" "text-xs" order-history-grid-template-class]}
          [:div.pr-2.whitespace-nowrap (or (shared/format-open-orders-time (:time-ms row)) "--")]
          [:div.pl-1.text-left (title-case-label (:type row))]
          [:div.text-left (order-history-coin-node (:coin row) market-by-key (:side row))]
          [:div {:class ["text-left" (order-history-direction-class row)]}
           (order-history-direction-label row)]
          [:div.text-left.num (format-order-history-size (:size row))]
          [:div.text-left.num (format-order-history-filled-size (:filled-size row))]
          [:div.text-left.num (format-order-history-value (:order-value row))]
          [:div.text-left.num (format-order-history-price row)]
          [:div.text-left (format-order-history-reduce-only row)]
          [:div.text-left (format-order-history-trigger row)]
          [:div.text-left (format-order-history-tp-sl row)]
          [:div {:class ["text-left" "break-words" "leading-4"]}
           (order-history-status-node row)]
          [:div {:class ["text-left"
                         "order-history-order-id-text"
                         "tracking-tight"
                         "whitespace-nowrap"]}
           (or (some-> (:oid row) str) "--")]])
       (history-pagination/order-history-pagination-controls pagination))
      (if (:loading? order-history-state)
        (empty-state "Loading order history...")
        (empty-state "No order history")))))

(defn order-history-tab-content
  ([order-history]
   (order-history-table order-history {}))
  ([order-history order-history-state]
   [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
    (when-let [error (:error order-history-state)]
      [:div {:class ["px-4" "py-2" "text-xs" "text-error" "border-b" "border-base-300" "bg-base-200"]}
       (str error)])
    (order-history-table order-history order-history-state)]))
