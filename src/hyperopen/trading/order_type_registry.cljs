(ns hyperopen.trading.order-type-registry)

(def ^:private default-order-type-capabilities
  {:sections []
   :limit-like? false
   :supports-tpsl? true
   :supports-post-only? false
   :show-scale-preview? false
   :show-liquidation-row? true
   :show-slippage-row? false})

(def order-type-config
  {:market {:label "Market"
            :show-slippage-row? true}
   :limit {:label "Limit"
           :limit-like? true}
   :stop-market {:label "Stop Market"
                 :sections [:trigger]
                 :limit-like? false
                 :supports-tpsl? true}
   :stop-limit {:label "Stop Limit"
                :sections [:trigger]
                :limit-like? true
                :supports-tpsl? true
                :supports-post-only? true}
   :take-market {:label "Take Market"
                 :sections [:trigger]
                 :limit-like? false
                 :supports-tpsl? true}
   :take-limit {:label "Take Limit"
                :sections [:trigger]
                :limit-like? true
                :supports-tpsl? true
                :supports-post-only? true}
   :scale {:label "Scale"
           :sections [:scale]
           :limit-like? false
           :supports-tpsl? false
           :show-scale-preview? true
           :show-liquidation-row? false}
   :twap {:label "TWAP"
          :sections [:twap]
          :limit-like? false
          :supports-tpsl? true}})

(def pro-order-type-order
  [:scale :stop-limit :stop-market :take-limit :take-market :twap])

(defn pro-order-types []
  pro-order-type-order)

(defn order-type-entry [order-type]
  (let [key* (if (keyword? order-type) order-type :limit)
        raw-entry (or (get order-type-config key*)
                      (get order-type-config :limit))]
    (merge default-order-type-capabilities raw-entry)))

(defn order-type-label [order-type]
  (or (:label (order-type-entry order-type))
      "Stop Market"))

(defn order-type-sections [order-type]
  (:sections (order-type-entry order-type)))

(defn order-type-limit-like? [order-type]
  (boolean (:limit-like? (order-type-entry order-type))))

(defn order-type-supports-tpsl? [order-type]
  (boolean (:supports-tpsl? (order-type-entry order-type))))

(defn order-type-supports-post-only? [order-type]
  (boolean (:supports-post-only? (order-type-entry order-type))))

(defn order-type-show-scale-preview? [order-type]
  (boolean (:show-scale-preview? (order-type-entry order-type))))

(defn order-type-show-liquidation-row? [order-type]
  (boolean (:show-liquidation-row? (order-type-entry order-type))))

(defn order-type-show-slippage-row? [order-type]
  (boolean (:show-slippage-row? (order-type-entry order-type))))
