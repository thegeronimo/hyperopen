(ns hyperopen.api.gateway.orders.commands
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]))

(defn- tif->wire [tif]
  (case tif
    :ioc "Ioc"
    :alo "Alo"
    "Gtc"))

(defn- positive-number? [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)
       (pos? value)))

(defn- canonical-price-text
  [command-context value]
  (let [parsed (trading-domain/parse-num value)]
    (when (positive-number? parsed)
      (or (trading-domain/canonical-order-price-string
           {:active-asset (:active-asset command-context)
            :market (:market command-context)}
           parsed)
          (trading-domain/number->clean-string parsed 8)))))

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

(defn build-tpsl-orders
  ([asset-idx side form]
   (build-tpsl-orders asset-idx side form nil))
  ([asset-idx side form command-context]
   (let [tp (get-in form [:tp])
         sl (get-in form [:sl])
         tp-enabled? (:enabled? tp)
         sl-enabled? (:enabled? sl)
         base-size (trading-domain/parse-num (:size form))
         close-side (trading-domain/opposite-side side)
         mk-trigger (fn [tpsl cfg]
                      (let [trigger (trading-domain/parse-num (:trigger cfg))
                            limit-price (trading-domain/parse-num (:limit cfg))
                            order-price (or limit-price trigger)
                            trigger-text (canonical-price-text command-context trigger)
                            order-price-text (canonical-price-text command-context order-price)]
                        (when (and (positive-number? base-size)
                                   (positive-number? trigger)
                                   (positive-number? order-price)
                                   (seq trigger-text)
                                   (seq order-price-text))
                          (array-map :a asset-idx
                                     :b (trading-domain/order-side->is-buy close-side)
                                     :p order-price-text
                                     :s (str base-size)
                                     :r true
                                     :t {:trigger (array-map :isMarket (:is-market cfg)
                                                             :triggerPx trigger-text
                                                             :tpsl tpsl)}))))
         tp-order (when tp-enabled?
                    (mk-trigger "tp" tp))
         sl-order (when sl-enabled?
                    (mk-trigger "sl" sl))
         valid? (and (or (not tp-enabled?) tp-order)
                     (or (not sl-enabled?) sl-order))]
     (when valid?
       (cond-> []
         tp-order (conj tp-order)
         sl-order (conj sl-order))))))

(def ^:private standard-order-required-checks
  {:limit (fn [{:keys [size price]}]
            (and (positive-number? size)
                 (positive-number? price)))
   :market (fn [{:keys [size price]}]
             (and (positive-number? size)
                  (positive-number? price)))
   :stop-market (fn [{:keys [size trigger effective-price]}]
                  (and (positive-number? size)
                       (positive-number? trigger)
                       (positive-number? effective-price)))
   :stop-limit (fn [{:keys [size price trigger]}]
                 (and (positive-number? size)
                      (positive-number? price)
                      (positive-number? trigger)))
   :take-market (fn [{:keys [size trigger effective-price]}]
                  (and (positive-number? size)
                       (positive-number? trigger)
                       (positive-number? effective-price)))
   :take-limit (fn [{:keys [size price trigger]}]
                 (and (positive-number? size)
                      (positive-number? price)
                      (positive-number? trigger)))})

(defn- order-wire-values-valid?
  [order-type {:keys [price-text trigger-text]}]
  (case order-type
    :limit (seq price-text)
    :market (seq price-text)
    :stop-market (seq trigger-text)
    :stop-limit (and (seq price-text) (seq trigger-text))
    :take-market (seq trigger-text)
    :take-limit (and (seq price-text) (seq trigger-text))
    true))

(def ^:private standard-order-shape-builders
  {:limit (fn [base-order {:keys [post-only tif]}]
            (assoc base-order :t {:limit {:tif (if post-only "Alo" tif)}}))
   :market (fn [base-order _]
             (assoc base-order :t {:limit {:tif "Ioc"}}))
   :stop-market (fn [base-order {:keys [price-text trigger-text]}]
                  (assoc base-order
                         :p (or price-text trigger-text)
                         :t {:trigger (array-map :isMarket true :triggerPx trigger-text :tpsl "sl")}))
   :stop-limit (fn [base-order {:keys [trigger-text]}]
                 (assoc base-order :t {:trigger (array-map :isMarket false :triggerPx trigger-text :tpsl "sl")}))
   :take-market (fn [base-order {:keys [price-text trigger-text]}]
                  (assoc base-order
                         :p (or price-text trigger-text)
                         :t {:trigger (array-map :isMarket true :triggerPx trigger-text :tpsl "tp")}))
   :take-limit (fn [base-order {:keys [trigger-text]}]
                 (assoc base-order
                        :t {:trigger (array-map :isMarket false :triggerPx trigger-text :tpsl "tp")}))})

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
        price-text (canonical-price-text command-context price)
        trigger-text (canonical-price-text command-context trigger)
        shape-builder (get standard-order-shape-builders order-type)
        required-check (get standard-order-required-checks order-type)
        required-values-valid? (boolean (and required-check
                                             (required-check {:size size
                                                              :price price
                                                              :trigger trigger
                                                              :effective-price (or price trigger)})))
        wire-values-valid? (order-wire-values-valid? order-type
                                                     {:price-text price-text
                                                      :trigger-text trigger-text})
        grouping (if (or (get-in form [:tp :enabled?]) (get-in form [:sl :enabled?]))
                   "normalTpsl"
                   "na")]
    (when (and shape-builder
               (string? active-asset)
               (number? asset-idx)
               required-values-valid?
               wire-values-valid?)
      (let [base-order (array-map :a asset-idx
                                  :b (trading-domain/order-side->is-buy side)
                                  :p (or price-text "")
                                  :s (str size)
                                  :r reduce-only)
            order (shape-builder base-order
                                 {:post-only post-only
                                  :tif tif
                                  :trigger-text trigger-text
                                  :price-text price-text})
            tpsl-orders (build-tpsl-orders asset-idx side (assoc form :size size) command-context)]
        (when (some? tpsl-orders)
          (let [orders (cond-> [order]
                         (seq tpsl-orders) (into tpsl-orders))]
            {:action (array-map :type "order"
                                :orders orders
                                :grouping grouping)
             :asset-idx asset-idx
             :orders orders}))))))

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
        sz-decimals (get-in command-context [:market :szDecimals])
        orders (when (and (number? asset-idx)
                          (positive-number? size))
                 (build-scale-orders
                  asset-idx
                  side
                  {:size size
                   :count (:count scale)
                   :skew (:skew scale)
                   :sz-decimals sz-decimals}
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
    (when (and (string? active-asset)
               (number? asset-idx)
               (positive-number? size)
               (positive-number? minutes))
      {:action (array-map :type "twapOrder"
                          :twap (array-map :a asset-idx
                                           :b (trading-domain/order-side->is-buy side)
                                           :s (str size)
                                           :r (boolean (:reduce-only form))
                                           :m (int minutes)
                                           :t randomize))
       :asset-idx asset-idx})))

(defn- normalize-margin-mode
  [value]
  (let [candidate (cond
                    (keyword? value) value
                    (string? value) (keyword (str/lower-case value))
                    :else :cross)]
    (if (= candidate :isolated) :isolated :cross)))

(def ^:private isolated-only-margin-modes
  #{:no-cross :strict-isolated})

(defn- parse-optional-boolean
  [value]
  (cond
    (boolean? value) value
    (string? value) (= "true" (some-> value str/trim str/lower-case))
    :else nil))

(defn- normalize-market-margin-mode
  [value]
  (let [token (cond
                (keyword? value) (name value)
                (string? value) value
                :else nil)
        normalized (some-> token
                          str/trim
                          str/lower-case
                          (str/replace #"[_-]" ""))]
    (case normalized
      "normal" :normal
      "nocross" :no-cross
      "strictisolated" :strict-isolated
      nil)))

(defn- cross-margin-allowed?
  [command-context]
  (let [market (or (:market command-context) {})
        only-isolated? (parse-optional-boolean
                        (or (:only-isolated? market)
                            (:onlyIsolated market)))
        margin-mode (normalize-market-margin-mode
                     (or (:margin-mode market)
                         (:marginMode market)))]
    (not (or (true? only-isolated?)
             (contains? isolated-only-margin-modes margin-mode)))))

(defn- normalize-leverage
  [value]
  (let [parsed (trading-domain/parse-num value)]
    (when (positive-number? parsed)
      (-> parsed js/Math.round int (max 1)))))

(defn- normalize-market-type
  [value]
  (let [candidate (cond
                    (keyword? value) value
                    (string? value) (keyword (str/lower-case value))
                    :else nil)]
    (when (contains? #{:perp :spot} candidate)
      candidate)))

(defn- spot-instrument?
  [value]
  (and (string? value)
       (str/includes? value "/")))

(defn- perp-market?
  [command-context]
  (let [market (or (:market command-context) {})
        market-type (normalize-market-type (:market-type market))
        instrument (or (:active-asset command-context)
                       (:coin market))]
    (case market-type
      :spot false
      :perp true
      (not (spot-instrument? instrument)))))

(defn- build-update-leverage-action
  [command-context form]
  (let [asset-idx (:asset-idx command-context)
        perp-market-eligible? (perp-market? command-context)
        leverage (normalize-leverage (:ui-leverage form))
        margin-mode (normalize-margin-mode (:margin-mode form))
        effective-margin-mode (if (cross-margin-allowed? command-context)
                                margin-mode
                                :isolated)]
    (when (and perp-market-eligible?
               (number? asset-idx)
               (number? leverage))
      (array-map :type "updateLeverage"
                 :asset asset-idx
                 :isCross (not= effective-margin-mode :isolated)
                 :leverage leverage))))

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
      (let [request (build-fn command-context (assoc form :type order-type))
            update-leverage-action (build-update-leverage-action command-context form)]
        (cond-> request
          (and (map? request)
               (map? update-leverage-action))
          (assoc :pre-actions [update-leverage-action]))))))
