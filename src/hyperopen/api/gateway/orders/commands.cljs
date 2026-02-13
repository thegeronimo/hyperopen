(ns hyperopen.api.gateway.orders.commands
  (:require [hyperopen.domain.trading :as trading-domain]))

(defn- tif->wire [tif]
  (case tif
    :ioc "Ioc"
    :alo "Alo"
    "Gtc"))

(defn build-scale-orders [asset-idx side total-size start end reduce-only post-only]
  (let [legs (trading-domain/scale-order-legs (get total-size :size)
                                              (get total-size :count)
                                              (get total-size :skew)
                                              start
                                              end
                                              {:sz-decimals (get total-size :sz-decimals)})
        tif (if post-only "Alo" "Gtc")]
    (mapv (fn [{:keys [price size]}]
            (array-map :a asset-idx
                       :b (trading-domain/order-side->is-buy side)
                       :p (str price)
                       :s (str size)
                       :r reduce-only
                       :t {:limit {:tif tif}}))
          legs)))

(defn build-tpsl-orders [asset-idx side form]
  (let [tp (get-in form [:tp])
        sl (get-in form [:sl])
        tp-enabled? (:enabled? tp)
        sl-enabled? (:enabled? sl)
        close-side (trading-domain/opposite-side side)
        mk-trigger (fn [tpsl cfg]
                     (array-map :a asset-idx
                                :b (trading-domain/order-side->is-buy close-side)
                                :p (str (or (trading-domain/parse-num (:limit cfg))
                                            (trading-domain/parse-num (:trigger cfg))))
                                :s (str (trading-domain/parse-num (:size form)))
                                :r true
                                :t {:trigger (array-map :isMarket (:is-market cfg)
                                                        :triggerPx (trading-domain/parse-num (:trigger cfg))
                                                        :tpsl tpsl)}))]
    (cond-> []
      tp-enabled? (conj (mk-trigger "tp" tp))
      sl-enabled? (conj (mk-trigger "sl" sl)))))

(def ^:private standard-order-shape-builders
  {:limit (fn [base-order {:keys [post-only tif]}]
            (assoc base-order :t {:limit {:tif (if post-only "Alo" tif)}}))
   :market (fn [base-order _]
             (assoc base-order :t {:limit {:tif "Ioc"}}))
   :stop-market (fn [base-order {:keys [price trigger]}]
                  (assoc base-order
                         :p (str (or price trigger))
                         :t {:trigger (array-map :isMarket true :triggerPx trigger :tpsl "sl")}))
   :stop-limit (fn [base-order {:keys [trigger]}]
                 (assoc base-order :t {:trigger (array-map :isMarket false :triggerPx trigger :tpsl "sl")}))
   :take-market (fn [base-order {:keys [price trigger]}]
                  (assoc base-order
                         :p (str (or price trigger))
                         :t {:trigger (array-map :isMarket true :triggerPx trigger :tpsl "tp")}))
   :take-limit (fn [base-order {:keys [trigger]}]
                 (assoc base-order
                        :t {:trigger (array-map :isMarket false :triggerPx trigger :tpsl "tp")}))})

(defn- build-standard-order-action
  [order-type command-context form]
  (let [active-asset (:active-asset command-context)
        asset-idx (:asset-idx command-context)
        side (:side form)
        size (trading-domain/parse-num (:size form))
        price (trading-domain/parse-num (:price form))
        trigger (trading-domain/parse-num (:trigger-px form))
        reduce-only (:reduce-only form)
        post-only (:post-only form)
        tif (tif->wire (:tif form))
        shape-builder (get standard-order-shape-builders order-type)
        grouping (if (or (get-in form [:tp :enabled?]) (get-in form [:sl :enabled?]))
                   "normalTpsl"
                   "na")]
    (when (and shape-builder active-asset asset-idx size)
      (let [base-order (array-map :a asset-idx
                                  :b (trading-domain/order-side->is-buy side)
                                  :p (str price)
                                  :s (str size)
                                  :r reduce-only)
            order (shape-builder base-order
                                 {:post-only post-only
                                  :tif tif
                                  :trigger trigger
                                  :price price})
            tpsl-orders (build-tpsl-orders asset-idx side form)
            orders (cond-> [order]
                     (seq tpsl-orders) (into tpsl-orders))]
        {:action (array-map :type "order"
                            :orders orders
                            :grouping grouping)
         :asset-idx asset-idx
         :orders orders}))))

(defn build-order-action
  "Return {:action action :grouping grouping}"
  [command-context form]
  (let [order-type (trading-domain/normalize-order-type (:type form))]
    (build-standard-order-action order-type command-context (assoc form :type order-type))))

(defn- build-scale-request [command-context form]
  (let [asset-idx (:asset-idx command-context)
        side (:side form)
        size (trading-domain/parse-num (:size form))
        scale (get-in form [:scale])
        orders (when (and asset-idx size)
                 (build-scale-orders
                  asset-idx
                  side
                  {:size size
                   :count (:count scale)
                   :skew (:skew scale)
                   :sz-decimals (:sz-decimals command-context)}
                  (get scale :start)
                  (get scale :end)
                  (:reduce-only form)
                  (:post-only form)))]
    (when (seq orders)
      {:action (array-map :type "order"
                          :orders (vec orders)
                          :grouping "na")
       :asset-idx asset-idx
       :orders orders})))

(defn build-twap-action [command-context form]
  (let [active-asset (:active-asset command-context)
        asset-idx (:asset-idx command-context)
        side (:side form)
        size (trading-domain/parse-num (:size form))
        minutes (trading-domain/parse-num (get-in form [:twap :minutes]))
        randomize (boolean (get-in form [:twap :randomize]))]
    (when (and active-asset asset-idx size minutes)
      {:action (array-map :type "twapOrder"
                          :twap (array-map :a asset-idx
                                           :b (trading-domain/order-side->is-buy side)
                                           :s (str size)
                                           :r (boolean (:reduce-only form))
                                           :m (int minutes)
                                           :t randomize))
       :asset-idx asset-idx})))

(def ^:private order-request-builders
  {:build/market (fn [command-context form]
                   (build-standard-order-action :market command-context form))
   :build/limit (fn [command-context form]
                  (build-standard-order-action :limit command-context form))
   :build/stop-market (fn [command-context form]
                        (build-standard-order-action :stop-market command-context form))
   :build/stop-limit (fn [command-context form]
                       (build-standard-order-action :stop-limit command-context form))
   :build/take-market (fn [command-context form]
                        (build-standard-order-action :take-market command-context form))
   :build/take-limit (fn [command-context form]
                       (build-standard-order-action :take-limit command-context form))
   :build/scale build-scale-request
   :build/twap build-twap-action})

(defn build-order-request [command-context form]
  (let [order-type (trading-domain/normalize-order-type (:type form))
        builder-id (trading-domain/order-type-builder-id order-type)
        build-fn (get order-request-builders builder-id)]
    (when build-fn
      (build-fn command-context (assoc form :type order-type)))))
