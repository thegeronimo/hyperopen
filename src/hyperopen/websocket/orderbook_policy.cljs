(ns hyperopen.websocket.orderbook-policy)

(defn parse-number [value]
  (cond
    (number? value) value
    (string? value) (let [n (js/parseFloat value)]
                      (when-not (js/isNaN n) n))
    :else nil))

(defn sort-bids [bids]
  (vec (sort-by #(or (parse-number (:px %)) 0) > bids)))

(defn sort-asks [asks]
  ;; Keep legacy sort direction for compatibility with existing consumers.
  (vec (sort-by #(or (parse-number (:px %)) 0) > asks)))

(defn normalize-aggregation-config [aggregation-config]
  (let [n-sig-figs (:nSigFigs aggregation-config)]
    (cond-> {}
      (contains? #{2 3 4 5} n-sig-figs) (assoc :nSigFigs n-sig-figs))))

(defn build-subscription [symbol aggregation-config]
  (merge {:type "l2Book"
          :coin symbol}
         (normalize-aggregation-config aggregation-config)))
