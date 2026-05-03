(ns hyperopen.schema.order-request-contracts
  (:require [hyperopen.schema.contracts.common :as common]))

(def ^:private standard-request-keys
  #{:action :asset-idx :orders})

(def ^:private standard-request-with-pre-actions-keys
  #{:action :asset-idx :orders :pre-actions})

(def ^:private twap-request-keys
  #{:action :asset-idx})

(def ^:private twap-request-with-pre-actions-keys
  #{:action :asset-idx :pre-actions})

(def ^:private order-action-keys
  #{:type :orders :grouping})

(def ^:private twap-action-keys
  #{:type :twap})

(def ^:private order-keys
  #{:a :b :p :s :r :t})

(def ^:private order-keys-without-reduce-only
  #{:a :b :p :s :t})

(def ^:private limit-container-keys
  #{:limit})

(def ^:private limit-keys
  #{:tif})

(def ^:private trigger-container-keys
  #{:trigger})

(def ^:private trigger-keys
  #{:isMarket :triggerPx :tpsl})

(def ^:private twap-keys
  #{:a :b :s :r :m :t})

(def ^:private leverage-keys
  #{:type :asset :isCross :leverage})

(def ^:private grouping-values
  #{"na" "normalTpsl"})

(def ^:private tif-values
  #{"Alo" "Gtc" "Ioc"})

(def ^:private tpsl-values
  #{"tp" "sl"})

(defn- exact-keys?
  [value expected-keys]
  (and (map? value)
       (= expected-keys (set (keys value)))))

(defn- exact-key-order?
  [value expected-order]
  (and (map? value)
       (= expected-order (vec (keys value)))))

(defn- non-negative-integer-value?
  [value]
  (and (integer? value)
       (>= value 0)))

(defn- positive-integer-value?
  [value]
  (and (integer? value)
       (pos? value)))

(defn- positive-numberish-string?
  [value]
  (and (common/non-empty-string? value)
       (when-let [parsed (common/parse-number-value value)]
         (pos? parsed))))

(defn- reduce-only-flag?
  [value]
  (or (nil? value)
      (boolean? value)))

(defn- order-shell?
  [order]
  (and (or (and (exact-keys? order order-keys)
                (exact-key-order? order [:a :b :p :s :r :t])
                (reduce-only-flag? (:r order)))
           (and (exact-keys? order order-keys-without-reduce-only)
                (exact-key-order? order [:a :b :p :s :t])))
       (non-negative-integer-value? (:a order))
       (boolean? (:b order))
       (positive-numberish-string? (:p order))
       (positive-numberish-string? (:s order))))

(defn limit-order?
  [order]
  (and (order-shell? order)
       (let [t (:t order)]
         (and (exact-keys? t limit-container-keys)
              (exact-key-order? t [:limit])
              (exact-keys? (:limit t) limit-keys)
              (exact-key-order? (:limit t) [:tif])
              (contains? tif-values (get-in t [:limit :tif]))))))

(defn trigger-order?
  [order]
  (and (order-shell? order)
       (let [t (:t order)]
         (and (exact-keys? t trigger-container-keys)
              (exact-key-order? t [:trigger])
              (exact-keys? (:trigger t) trigger-keys)
              (exact-key-order? (:trigger t) [:isMarket :triggerPx :tpsl])
              (boolean? (get-in t [:trigger :isMarket]))
              (positive-numberish-string? (get-in t [:trigger :triggerPx]))
              (contains? tpsl-values (get-in t [:trigger :tpsl]))))))

(defn wire-order?
  [order]
  (or (limit-order? order)
      (trigger-order? order)))

(defn update-leverage-action?
  [action]
  (and (exact-keys? action leverage-keys)
       (exact-key-order? action [:type :asset :isCross :leverage])
       (= "updateLeverage" (:type action))
       (non-negative-integer-value? (:asset action))
       (boolean? (:isCross action))
       (positive-integer-value? (:leverage action))))

(defn- pre-actions-valid?
  [pre-actions]
  (and (vector? pre-actions)
       (seq pre-actions)
       (every? update-leverage-action? pre-actions)))

(defn order-action?
  [action]
  (and (exact-keys? action order-action-keys)
       (exact-key-order? action [:type :orders :grouping])
       (= "order" (:type action))
       (contains? grouping-values (:grouping action))
       (vector? (:orders action))
       (seq (:orders action))
       (every? wire-order? (:orders action))))

(defn twap-action?
  [action]
  (and (exact-keys? action twap-action-keys)
       (exact-key-order? action [:type :twap])
       (= "twapOrder" (:type action))
       (let [twap (:twap action)]
         (and (exact-keys? twap twap-keys)
              (exact-key-order? twap [:a :b :s :r :m :t])
              (non-negative-integer-value? (:a twap))
              (boolean? (:b twap))
              (positive-numberish-string? (:s twap))
              (boolean? (:r twap))
              (positive-integer-value? (:m twap))
              (boolean? (:t twap))))))

(defn standard-order-request-valid?
  [request]
  (and (map? request)
       (or (and (exact-keys? request standard-request-keys)
                (exact-key-order? request [:action :asset-idx :orders]))
           (and (exact-keys? request standard-request-with-pre-actions-keys)
                (exact-key-order? request [:action :asset-idx :orders :pre-actions])
                (pre-actions-valid? (:pre-actions request))))
       (non-negative-integer-value? (:asset-idx request))
       (= (:orders request)
          (get-in request [:action :orders]))
       (order-action? (:action request))
       (every? #(= (:asset-idx request) (:a %))
               (:orders request))
       (if (= "normalTpsl" (get-in request [:action :grouping]))
         (> (count (:orders request)) 1)
         true)))

(defn scale-request-valid?
  [request]
  (and (standard-order-request-valid? request)
       (= "na" (get-in request [:action :grouping]))
       (every? limit-order? (:orders request))))

(defn twap-request-valid?
  [request]
  (and (map? request)
       (or (and (exact-keys? request twap-request-keys)
                (exact-key-order? request [:action :asset-idx]))
           (and (exact-keys? request twap-request-with-pre-actions-keys)
                (exact-key-order? request [:action :asset-idx :pre-actions])
                (pre-actions-valid? (:pre-actions request))))
       (non-negative-integer-value? (:asset-idx request))
       (twap-action? (:action request))
       (= (:asset-idx request)
          (get-in request [:action :twap :a]))))

(defn order-request-valid?
  [request]
  (or (standard-order-request-valid? request)
      (scale-request-valid? request)
      (twap-request-valid? request)))

(defn nil-or-order-request-valid?
  [request]
  (or (nil? request)
      (order-request-valid? request)))

(defn assert-standard-order-request!
  [request context]
  (when-not (standard-order-request-valid? request)
    (throw (js/Error.
            (str "standard order request contract validation failed. "
                 "context=" (pr-str context)
                 " request=" (pr-str request)))))
  request)

(defn assert-scale-request!
  [request context]
  (when-not (scale-request-valid? request)
    (throw (js/Error.
            (str "scale order request contract validation failed. "
                 "context=" (pr-str context)
                 " request=" (pr-str request)))))
  request)

(defn assert-twap-request!
  [request context]
  (when-not (twap-request-valid? request)
    (throw (js/Error.
            (str "twap order request contract validation failed. "
                 "context=" (pr-str context)
                 " request=" (pr-str request)))))
  request)

(defn assert-order-request!
  [request context]
  (when-not (order-request-valid? request)
    (throw (js/Error.
            (str "order request contract validation failed. "
                 "context=" (pr-str context)
                 " request=" (pr-str request)))))
  request)
