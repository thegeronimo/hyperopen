(ns hyperopen.views.account-info.tabs.trade-history.shared
  (:require [clojure.string :as str]
            [hyperopen.ui.table.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.cache-keys :as cache-keys]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tab-filters :as tab-filters]))

(def default-trade-history-sort
  {:column "Time" :direction :desc})

(def trade-history-direction-filter-key
  tab-filters/trade-history-direction-filter-key)

(defn trade-history-sort-state [trade-history-state]
  (merge default-trade-history-sort
         (or (:sort trade-history-state) {})))

(defn trade-history-coin [row]
  (projections/trade-history-coin row))

(defn trade-history-time-ms [row]
  (projections/trade-history-time-ms row))

(def trade-history-price-improved-tooltip-text
  "This fill price was more favorable to you than the price chart at that time, because your order provided liquidity to another user's liquidation.")

(defn trade-history-direction-base-label [row]
  (or (shared/non-blank-text (or (:dir row) (:direction row)))
      (case (:side row)
        "B" "Open Long"
        "A" "Open Short"
        "S" "Open Short"
        "-")))

(defn trade-history-action-side-from-label [direction-label]
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

(defn trade-history-action-side [row direction-label]
  (or (trade-history-action-side-from-label direction-label)
      (case (:side row)
        "B" :buy
        "A" :sell
        "S" :sell
        nil)))

(defn trade-history-action-class [row direction-label]
  (case (trade-history-action-side row direction-label)
    :buy "text-success"
    :sell "text-error"
    "text-trading-text"))

(defn trade-history-liquidation-fill? [row]
  (let [liquidation (:liquidation row)]
    (and (map? liquidation)
         (or (some? (:markPx liquidation))
             (some? (:method liquidation))
             (some? (:liquidatedUser liquidation))))))

(defn trade-history-liquidation-close-label [row direction-label]
  (let [direction-label* (shared/non-blank-text direction-label)
        normalized (some-> direction-label* str/lower-case)]
    (when (and (trade-history-liquidation-fill? row)
               normalized
               (not (str/includes? normalized "liquidation"))
               (or (re-matches #"^close\s+long$" normalized)
                   (re-matches #"^close\s+short$" normalized)))
      (str "Market Order Liquidation: " direction-label*))))

(defn trade-history-price-improved? [row direction-label]
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

(defn trade-history-direction-label [row]
  (let [base-label (trade-history-direction-base-label row)
        liquidation-label (trade-history-liquidation-close-label row base-label)
        final-label (or liquidation-label base-label)
        normalized (some-> final-label str/lower-case)]
    (if (and (trade-history-price-improved? row final-label)
             normalized
             (not (str/includes? normalized "price improved")))
      (str final-label " (Price Improved)")
      final-label)))

(defn trade-history-value-number [row]
  (projections/trade-history-value-number row))

(defn trade-history-closed-pnl-class [row]
  (let [value (projections/trade-history-closed-pnl-number row)]
    (cond
      (and (number? value) (pos? value)) "text-success"
      (and (number? value) (neg? value)) "text-error"
      :else "text-trading-text")))

(defn trade-history-row-id [row]
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

(defn- build-trade-history-coin-search-index
  [rows market-by-key]
  (let [rows* (or rows [])
        candidates-by-coin (volatile! {})]
    (mapv (fn [row]
            (let [coin (trade-history-coin row)
                  cached (get @candidates-by-coin coin)
                  candidates (or cached
                                 (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin market-by-key)
                                       normalized (shared/normalized-coin-search-candidates
                                                   [coin base-label prefix-label])]
                                   (vswap! candidates-by-coin assoc coin normalized)
                                   normalized))]
              [row candidates]))
          rows*)))

(def ^:dynamic *build-trade-history-coin-search-index*
  build-trade-history-coin-search-index)

(defn- filter-trade-history-by-coin-search
  [rows indexed-rows coin-search]
  (let [query (shared/compile-coin-search-query coin-search)]
    (if (shared/coin-search-query-blank? query)
      (vec (or rows []))
      (into []
            (comp (filter (fn [[_ normalized-candidates]]
                            (shared/normalized-coin-candidates-match? normalized-candidates query)))
                  (map first))
            (or indexed-rows [])))))

(defn memoized-sorted-trade-history [rows direction-filter sort-state market-by-key coin-search]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-trade-history-cache
        row-match (cache-keys/rows-match-state rows
                                               (:rows cache)
                                               (:rows-signature cache))
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
      (let [base-sorted-rows (if same-base?
                               (:base-sorted-rows cache)
                               (vec (sort-trade-history-by-column
                                     (filter-trade-history-by-direction rows direction-filter)
                                     column
                                     direction
                                     market-by-key)))
            indexed-rows (if same-index?
                           (:indexed-rows cache)
                           (*build-trade-history-coin-search-index* base-sorted-rows market-by-key))
            result (filter-trade-history-by-coin-search base-sorted-rows indexed-rows coin-search)]
        (reset! sorted-trade-history-cache {:rows rows
                                            :rows-signature (:signature row-match)
                                            :direction-filter direction-filter
                                            :coin-search coin-search
                                            :column column
                                            :direction direction
                                            :market-by-key market-by-key
                                            :market-signature (:signature market-match)
                                            :base-sorted-rows base-sorted-rows
                                            :indexed-rows indexed-rows
                                            :result result})
        result))))
