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

(defn- normalize-market-type
  [value]
  (let [candidate (cond
                    (keyword? value) value
                    (string? value) (keyword (str/lower-case value))
                    :else nil)]
    (when (contains? #{:perp :spot :outcome} candidate)
      candidate)))

(defn- attach-cloid
  "Appends the wire client-order-id (:c) to a single order when the form carries a
   non-blank :cloid. Assoc'd LAST so the array-map field order stays a,b,p,s,r,t,c —
   Hyperliquid's canonical order struct — because the L1 action is signed by msgpacking
   the map as-is (hl-signing/compute-connection-id), so field order is part of the hash.
   No :cloid on the form => the order is byte-for-byte unchanged (every non-optimizer
   caller)."
  [order form]
  (let [cloid (:cloid form)]
    (cond-> order
      (and (string? cloid) (seq (str/trim cloid))) (assoc :c (str/trim cloid)))))

(defn- suppress-reduce-only?
  "Reduce-only is perp-only: spot and outcome markets have no position to
   reduce, so the wire :r is forced false for them across all order types."
  [command-context]
  (contains? #{:spot :outcome}
             (normalize-market-type (get-in command-context [:market :market-type]))))

(defn- spot-market-command?
  [command-context]
  (= :spot (normalize-market-type (get-in command-context [:market :market-type]))))

(defn- effective-reduce-only
  [command-context form]
  (if (suppress-reduce-only? command-context)
    false
    (boolean (:reduce-only form))))

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
                       :r (boolean reduce-only)
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

(def ^:private order-wire-values-valid-checks
  {:limit (fn [{:keys [price-text]}]
            (seq price-text))
   :market (fn [{:keys [price-text]}]
             (seq price-text))
   :stop-market (fn [{:keys [trigger-text]}]
                  (seq trigger-text))
   :stop-limit (fn [{:keys [price-text trigger-text]}]
                 (and (seq price-text) (seq trigger-text)))
   :take-market (fn [{:keys [trigger-text]}]
                  (seq trigger-text))
   :take-limit (fn [{:keys [price-text trigger-text]}]
                 (and (seq price-text) (seq trigger-text)))})

(defn- order-wire-values-valid?
  [order-type wire-values]
  (when-let [check (get order-wire-values-valid-checks order-type)]
    (check wire-values)))

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
        reduce-only (effective-reduce-only command-context form)
        spot? (spot-market-command? command-context)
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
        ;; TP/SL brackets are perp-only (their legs are reduce-only against a
        ;; position); spot suppresses them, keeping grouping "na".
        tpsl-enabled? (and (not spot?)
                           (or (get-in form [:tp :enabled?]) (get-in form [:sl :enabled?])))
        grouping (if tpsl-enabled? "normalTpsl" "na")]
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
            order (-> (shape-builder base-order
                                     {:post-only post-only
                                      :tif tif
                                      :trigger-text trigger-text
                                      :price-text price-text})
                      (attach-cloid form))
            tpsl-orders (if spot?
                          []
                          (build-tpsl-orders asset-idx side (assoc form :size size) command-context))]
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
                  (effective-reduce-only command-context form)
                  (:post-only form)))
        ;; Interpolated ladder prices come out raw from scale-order-legs (e.g.
        ;; 99.66666666666667), exceeding the wire precision the exchange accepts:
        ;; perp = 5 sig figs / (6 - szDecimals) decimals, spot = (8 - szDecimals)
        ;; decimals. Canonicalize every leg price (both perp and spot) so the
        ;; exchange does not reject ladder legs. Adjacent legs may canonicalize to
        ;; the same price on very tight ladders; duplicates are left as-is (the
        ;; exchange accepts multiple resting orders at one price, and dropping
        ;; would silently under-fill the requested total size).
        orders (if (seq orders)
                 (mapv (fn [order]
                         (if-let [p (canonical-price-text command-context
                                                          (trading-domain/parse-num (:p order)))]
                           (assoc order :p p)
                           order))
                       orders)
                 orders)]
    (when (seq orders)
      {:action (array-map :type "order"
                          :orders (vec orders)
                          :grouping "na")
       :asset-idx asset-idx
       :orders orders})))

(defn- price-input-present?
  "True when the user actually typed something into an optional price field. Blank means
   'not set', which is different from 'set to something the wire cannot express'."
  [value]
  (and (some? value)
       (seq (str/trim (str value)))))

(defn- twap-reference-mark
  "The venue watches the MARK price for TWAP trigger and termination conditions, so the
   trigger direction is inferred against the mark rather than the book."
  [command-context]
  (or (trading-domain/parse-num (get-in command-context [:market :mark]))
      (trading-domain/parse-num (get-in command-context [:market :streamed-mark]))))

(defn- twap-details
  "Builds the optional :details block that carries a twapOrder's advanced settings.

   Shape (venue format): {:t {:p <trigger price> :a <true when the mark must rise TO the
   trigger, false when it must fall to it>} :s <price at which the venue terminates the
   order>}. Both keys are always present when the block is emitted, with the unused one
   nil -- that is what the exchange expects.

   Returns nil when the user set neither field, so an ordinary TWAP produces the exact
   action map it always has and signs to the same bytes. Returns ::invalid when a value
   was typed but cannot be expressed on the wire (sub-tick, non-positive, unparseable),
   or when a trigger was typed and there is no mark price to infer its direction from --
   in both cases the builder must fail closed rather than silently drop the setting the
   user asked for.

   The direction flag is inferred rather than asked for: Hyperliquid's own ticket exposes
   a single Trigger Price field with no above/below control, and infers the direction from
   where the trigger sits relative to the mark."
  [command-context form]
  (let [trigger-raw (get-in form [:twap :trigger-px])
        stop-raw (get-in form [:twap :stop-px])
        trigger-typed? (price-input-present? trigger-raw)
        stop-typed? (price-input-present? stop-raw)]
    (when (or trigger-typed? stop-typed?)
      (let [trigger-px (when trigger-typed? (canonical-price-text command-context trigger-raw))
            stop-px (when stop-typed? (canonical-price-text command-context stop-raw))
            mark (twap-reference-mark command-context)
            trigger-parsed (trading-domain/parse-num trigger-px)]
        (if (or (and trigger-typed? (not trigger-px))
                (and stop-typed? (not stop-px))
                (and trigger-typed? (not (positive-number? mark))))
          ::invalid
          (array-map :t (when trigger-px
                          (array-map :p trigger-px
                                     :a (>= trigger-parsed mark)))
                     :s stop-px))))))

(defn build-twap-action [command-context form]
  (let [active-asset (:active-asset command-context)
        asset-idx (:asset-idx command-context)
        side (:side form)
        size (trading-domain/parse-num (:size form))
        minutes (trading-domain/twap-total-minutes (get-in form [:twap]))
        randomize (boolean (get-in form [:twap :randomize]))
        details (twap-details command-context form)]
    (when (and (string? active-asset)
               (number? asset-idx)
               (positive-number? size)
               (trading-domain/valid-twap-runtime? minutes)
               (not= ::invalid details))
      {:action (cond-> (array-map :type "twapOrder"
                                  :twap (array-map :a asset-idx
                                                   :b (trading-domain/order-side->is-buy side)
                                                   :s (str size)
                                                   :r (effective-reduce-only command-context form)
                                                   :m (int minutes)
                                                   :t randomize))
                 ;; Assoc'd LAST so the signed key order stays type, twap, details. With no
                 ;; advanced settings the key is absent entirely and the msgpack bytes are
                 ;; identical to every TWAP this app has ever sent.
                 details (assoc :details details))
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
      :outcome false
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
