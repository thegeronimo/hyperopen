(ns hyperopen.api.endpoints.orders)

(defn request-frontend-open-orders!
  [post-info! address dex opts]
  (let [body (cond-> {"type" "frontendOpenOrders"
                      "user" address}
               (and dex (not= dex "")) (assoc "dex" dex))]
    (post-info! body
                (merge {:priority :high}
                       opts))))

(defn request-user-fills!
  [post-info! address opts]
  (post-info! {"type" "userFills"
               "user" address
               "aggregateByTime" true}
              (merge {:priority :high}
                     opts)))

(defn- historical-orders-seq
  [payload]
  (cond
    (sequential? payload)
    payload

    (map? payload)
    (let [nested (or (:orders payload)
                     (:historicalOrders payload)
                     (:data payload))]
      (if (sequential? nested) nested []))

    :else
    []))

(defn- normalize-historical-order-row
  [row]
  (when (map? row)
    (let [order (:order row)]
      (if (map? order)
        row
        (assoc row :order row)))))

(defn request-historical-orders!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve [])
    (-> (post-info! {"type" "historicalOrders"
                     "user" address}
                    (merge {:priority :high}
                           opts))
        (.then (fn [payload]
                 (->> payload
                      historical-orders-seq
                      (map normalize-historical-order-row)
                      (remove nil?)
                      vec))))))
