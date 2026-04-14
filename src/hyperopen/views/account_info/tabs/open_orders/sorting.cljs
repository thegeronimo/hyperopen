(ns hyperopen.views.account-info.tabs.open-orders.sorting
  (:require [clojure.string :as str]
            [hyperopen.ui.table.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.cache-keys :as cache-keys]
            [hyperopen.views.account-info.shared :as shared]))

(def ^:private short-order-side-values #{"A" "S"})

(defn order-value [{:keys [sz px]}]
  (let [size (shared/parse-num sz)
        price (shared/parse-num px)]
    (when (and (pos? size) (pos? price))
      (* size price))))

(defn open-orders-direction-filter-key [open-orders-state]
  (let [raw-direction (:direction-filter open-orders-state)
        direction-filter (cond
                           (keyword? raw-direction) raw-direction
                           (string? raw-direction) (keyword (str/lower-case raw-direction))
                           :else :all)]
    (if (contains? #{:all :long :short} direction-filter)
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
      "Direction" (fn [o]
                    (case (:side o)
                      "B" "Long"
                      "A" "Short"
                      "S" "Short"
                      (or (:side o) "-")))
      "Size" (fn [o] (shared/parse-num (:sz o)))
      "Original Size" (fn [o] (shared/parse-num (or (:orig-sz o) (:sz o))))
      "Order Value" (fn [o] (or (order-value o) 0))
      "Price" (fn [o] (shared/parse-num (:px o)))}})))

(defonce ^:private sorted-open-orders-cache (atom nil))

(defn reset-open-orders-sort-cache! []
  (reset! sorted-open-orders-cache nil))

(defn memoized-sorted-open-orders [orders direction-filter sort-state market-by-key coin-search]
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
