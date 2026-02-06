(ns hyperopen.state.trading
  (:require [clojure.string :as str]))

(def order-types
  [:market :limit :stop-market :stop-limit :take-market :take-limit :scale :twap])

(def advanced-order-types
  [:stop-market :stop-limit :take-market :take-limit :scale :twap])

(def limit-like-order-types
  #{:limit :stop-limit :take-limit})

(def tif-options [:gtc :ioc :alo])

(def default-max-slippage-pct 8.0)

(def default-fees
  {:taker 0.045
   :maker 0.015})

(defn default-order-form []
  {:type :limit
   :side :buy
   :ui-leverage 20
   :size-percent 0
   :tpsl-panel-open? false
   :size ""
   :price ""
   :trigger-px ""
   :reduce-only false
   :post-only false
   :tif :gtc
   :slippage 0.5
   :scale {:start ""
           :end ""
           :count 5
           :skew :even}
   :twap {:minutes 5
          :randomize true}
   :tp {:enabled? false
        :trigger ""
        :is-market true
        :limit ""}
   :sl {:enabled? false
        :trigger ""
        :is-market true
        :limit ""}
   :submitting? false
   :error nil})

(defn parse-num [v]
  (cond
    (number? v) v
    (string? v) (let [s (str/trim v)
                      n (js/parseFloat s)]
                  (when (and (not (str/blank? s)) (not (js/isNaN n))) n))
    :else nil))

(defn- clamp-num [n min-v max-v]
  (-> n
      (max min-v)
      (min max-v)))

(defn clamp-percent [percent]
  (let [parsed (or (parse-num percent) 0)]
    (clamp-num parsed 0 100)))

(defn- number->clean-string [value decimals]
  (let [safe-decimals (-> (or decimals 4)
                          (max 0)
                          (min 8))]
    (if (number? value)
      (-> (.toFixed value safe-decimals)
          (str/replace #"0+$" "")
          (str/replace #"\.$" ""))
      "")))

(defn normalize-order-type [order-type]
  (let [candidate (if (keyword? order-type) order-type (keyword order-type))]
    (if (some #{candidate} order-types) candidate :limit)))

(defn limit-like-type? [order-type]
  (contains? limit-like-order-types (normalize-order-type order-type)))

(defn entry-mode-for-type [order-type]
  (case (normalize-order-type order-type)
    :market :market
    :limit :limit
    :pro))

(defn normalize-pro-order-type [order-type]
  (let [candidate (normalize-order-type order-type)]
    (if (some #{candidate} advanced-order-types)
      candidate
      :stop-market)))

(defn market-max-leverage [state]
  (let [max-lev (parse-num (get-in state [:active-market :maxLeverage]))]
    (when (and (number? max-lev) (pos? max-lev))
      max-lev)))

(defn normalize-ui-leverage [state leverage]
  (let [raw (or (parse-num leverage) 20)
        max-lev (or (market-max-leverage state) 100)]
    (-> (clamp-num raw 1 max-lev)
        js/Math.round)))

(defn- active-clearinghouse-state [state]
  (let [dex (get-in state [:active-market :dex])]
    (if (and (string? dex) (seq dex))
      (get-in state [:perp-dex-clearinghouse dex])
      (get-in state [:webdata2 :clearinghouseState]))))

(defn available-to-trade [state]
  (let [clearinghouse (or (active-clearinghouse-state state) {})
        withdrawable (parse-num (:withdrawable clearinghouse))
        summary (or (get-in clearinghouse [:marginSummary])
                    (get-in clearinghouse [:crossMarginSummary])
                    {})
        account-value (or (parse-num (:accountValue summary)) 0)
        margin-used (or (parse-num (:totalMarginUsed summary)) 0)
        fallback-available (- account-value margin-used)]
    (max 0 (if (number? withdrawable)
             withdrawable
             fallback-available))))

(defn position-for-active-asset [state]
  (let [active-asset (:active-asset state)
        positions (get-in (active-clearinghouse-state state) [:assetPositions])]
    (some (fn [entry]
            (let [position (or (:position entry) entry)]
              (when (= active-asset (:coin position))
                position)))
          positions)))

(defn current-position-summary [state]
  (let [active-asset (:active-asset state)
        position (position-for-active-asset state)
        size (or (parse-num (:szi position)) 0)
        liquidation (parse-num (:liquidationPx position))]
    {:coin active-asset
     :size size
     :abs-size (js/Math.abs size)
     :direction (cond
                  (pos? size) :long
                  (neg? size) :short
                  :else :flat)
     :liquidation-price (when (and (number? liquidation) (pos? liquidation))
                          liquidation)}))

(defn- size-decimals [state]
  (let [sz-decimals (parse-num (get-in state [:active-market :szDecimals]))]
    (-> (or sz-decimals 4)
        (max 0)
        (min 8)
        int)))

(defn best-bid-price [state]
  (let [active-asset (:active-asset state)
        orderbook (get-in state [:orderbooks active-asset])]
    (some-> orderbook :bids first :px parse-num)))

(defn best-ask-price [state]
  (let [active-asset (:active-asset state)
        orderbook (get-in state [:orderbooks active-asset])]
    (some-> orderbook :asks first :px parse-num)))

(defn reference-price [state form]
  (let [order-type (normalize-order-type (:type form))
        limit-price (when (limit-like-type? order-type)
                      (parse-num (:price form)))
        side (:side form)
        best-px (if (= side :buy)
                  (best-ask-price state)
                  (best-bid-price state))
        projected-mark (parse-num (get-in state [:active-market :mark]))
        streamed-mark (parse-num (get-in state [:active-assets :contexts (:active-asset state) :mark]))]
    (cond
      (and (number? limit-price) (pos? limit-price)) limit-price
      (and (number? best-px) (pos? best-px)) best-px
      (and (number? projected-mark) (pos? projected-mark)) projected-mark
      (and (number? streamed-mark) (pos? streamed-mark)) streamed-mark
      :else nil)))

(defn mid-price-summary
  "Return deterministic price context for UI rows:
   {:mid-price number|nil :source :mid|:reference|:none}."
  [state form]
  (let [bid (best-bid-price state)
        ask (best-ask-price state)
        mid (when (and (number? bid)
                       (pos? bid)
                       (number? ask)
                       (pos? ask))
              (/ (+ bid ask) 2))
        ref (reference-price state form)]
    (cond
      (number? mid) {:mid-price mid :source :mid}
      (number? ref) {:mid-price ref :source :reference}
      :else {:mid-price nil :source :none})))

(defn effective-limit-price
  "Return a deterministic fallback price for limit-like order types.
   Prefers mid (bid/ask average), then reference price."
  [state form]
  (when (limit-like-type? (:type form))
    (let [{:keys [mid-price]} (mid-price-summary state form)
          ref (reference-price state form)]
      (cond
        (and (number? mid-price) (pos? mid-price)) mid-price
        (and (number? ref) (pos? ref)) ref
        :else nil))))

(defn effective-limit-price-string
  "String representation for deterministic limit fallback price."
  [state form]
  (when-let [price (effective-limit-price state form)]
    (number->clean-string price 6)))

(defn size-from-percent [state form percent]
  (let [pct (clamp-percent percent)
        available (available-to-trade state)
        leverage (normalize-ui-leverage state (:ui-leverage form))
        ref-price (reference-price state form)
        notional (* available leverage (/ pct 100))]
    (when (and (pos? pct)
               (number? ref-price)
               (pos? ref-price)
               (pos? notional))
      (/ notional ref-price))))

(defn percent-from-size [state form size]
  (let [size-value (parse-num size)
        available (available-to-trade state)
        leverage (normalize-ui-leverage state (:ui-leverage form))
        ref-price (reference-price state form)
        notional-capacity (* available leverage)]
    (when (and (number? size-value)
               (pos? size-value)
               (number? ref-price)
               (pos? ref-price)
               (pos? notional-capacity))
      (clamp-percent (* 100 (/ (* size-value ref-price) notional-capacity))))))

(defn apply-size-percent [state form percent]
  (let [pct (clamp-percent percent)
        normalized-form (assoc form
                               :size-percent pct
                               :ui-leverage (normalize-ui-leverage state (:ui-leverage form)))
        computed-size (size-from-percent state normalized-form pct)
        decimals (size-decimals state)]
    (cond
      (zero? pct) (assoc normalized-form :size "")
      (number? computed-size) (assoc normalized-form :size (number->clean-string computed-size decimals))
      :else normalized-form)))

(defn sync-size-from-percent [state form]
  (let [pct (clamp-percent (:size-percent form))]
    (if (pos? pct)
      (apply-size-percent state form pct)
      (assoc form
             :size-percent pct
             :ui-leverage (normalize-ui-leverage state (:ui-leverage form))))))

(defn sync-size-percent-from-size [state form]
  (let [size (parse-num (:size form))
        derived (percent-from-size state form (:size form))
        leverage (normalize-ui-leverage state (:ui-leverage form))]
    (cond
      (or (nil? size) (<= size 0))
      (assoc form :size-percent 0 :ui-leverage leverage)

      (number? derived)
      (assoc form :size-percent derived :ui-leverage leverage)

      :else
      (assoc form
             :size-percent (clamp-percent (:size-percent form))
             :ui-leverage leverage))))

(defn normalize-order-form [state form]
  (let [normalized-type (normalize-order-type (:type form))
        final-type (if (= :pro (entry-mode-for-type normalized-type))
                     (normalize-pro-order-type normalized-type)
                     normalized-type)]
    (-> form
        (assoc :type final-type
               :size-percent (clamp-percent (:size-percent form))
               :ui-leverage (normalize-ui-leverage state (:ui-leverage form))
               :tpsl-panel-open? (boolean (:tpsl-panel-open? form))))))

(defn order-summary [state form]
  (let [normalized-form (normalize-order-form state form)
        size (parse-num (:size normalized-form))
        ref-price (reference-price state normalized-form)
        order-value (when (and (number? size) (pos? size) (number? ref-price) (pos? ref-price))
                      (* size ref-price))
        leverage (normalize-ui-leverage state (:ui-leverage normalized-form))
        margin-required (when (and (number? order-value) (pos? leverage))
                          (/ order-value leverage))
        position (current-position-summary state)
        liquidation-price (:liquidation-price position)
        slippage-est (if (= :market (:type normalized-form))
                       (max 0 (or (parse-num (:slippage normalized-form)) 0))
                       0)]
    {:available-to-trade (available-to-trade state)
     :current-position position
     :reference-price ref-price
     :order-value order-value
     :margin-required margin-required
     :liquidation-price liquidation-price
     :slippage-est slippage-est
     :slippage-max default-max-slippage-pct
     :fees default-fees}))

(defn validate-order-form [form]
  (let [size (parse-num (:size form))
        price (parse-num (:price form))
        trigger (parse-num (:trigger-px form))
        scale-start (parse-num (get-in form [:scale :start]))
        scale-end (parse-num (get-in form [:scale :end]))
        scale-count (parse-num (get-in form [:scale :count]))
        twap-min (parse-num (get-in form [:twap :minutes]))
        tp-enabled? (get-in form [:tp :enabled?])
        sl-enabled? (get-in form [:sl :enabled?])
        tp-trigger (parse-num (get-in form [:tp :trigger]))
        sl-trigger (parse-num (get-in form [:sl :trigger]))]
    (cond-> []
      (or (nil? size) (<= size 0)) (conj "Size must be greater than 0.")
      (and (limit-like-type? (:type form))
           (or (nil? price) (<= price 0))) (conj "Price is required for limit orders.")
      (and (#{:stop-market :stop-limit :take-market :take-limit} (:type form))
           (or (nil? trigger) (<= trigger 0))) (conj "Trigger price is required for stop/take orders.")
      (and (= :scale (:type form))
           (or (nil? scale-start) (nil? scale-end) (<= scale-count 1)))
      (conj "Scale orders need start/end prices and count > 1.")
      (and (= :twap (:type form))
           (or (nil? twap-min) (<= twap-min 0)))
      (conj "TWAP minutes must be greater than 0.")
      (and tp-enabled? (or (nil? tp-trigger) (<= tp-trigger 0)))
      (conj "TP trigger price is required when TP is enabled.")
      (and sl-enabled? (or (nil? sl-trigger) (<= sl-trigger 0)))
      (conj "SL trigger price is required when SL is enabled."))))

(defn order-side->is-buy [side]
  (= side :buy))

(defn opposite-side [side]
  (if (= side :buy) :sell :buy))

(defn scale-weights [count skew]
  (let [n (int count)
        base (range 1 (inc n))
        weights (case skew
                  :front (reverse base)
                  :back base
                  base)
        total (reduce + weights)]
    (map #(/ % total) weights)))

(defn build-scale-orders [asset-idx side total-size start end reduce-only post-only]
  (let [count (max 2 (int (or (parse-num (get-in total-size [:count])) 2)))
        start-px (parse-num start)
        end-px (parse-num end)
        weights (scale-weights count (get-in total-size [:skew] :even))
        size (parse-num (get-in total-size [:size]))
        step (if (= count 1) 0 (/ (- end-px start-px) (dec count)))
        tif (if post-only "Alo" "Gtc")]
    (map-indexed
      (fn [i w]
        (let [px (+ start-px (* step i))
              sz (* size w)]
          {:a asset-idx
           :b (order-side->is-buy side)
           :p (str px)
           :s (str sz)
           :r reduce-only
           :t {:limit {:tif tif}}}))
      weights)))

(defn build-tpsl-orders [asset-idx side form]
  (let [tp (get-in form [:tp])
        sl (get-in form [:sl])
        tp-enabled? (:enabled? tp)
        sl-enabled? (:enabled? sl)
        close-side (opposite-side side)
        mk-trigger (fn [tpsl cfg]
                     {:a asset-idx
                      :b (order-side->is-buy close-side)
                      :p (str (or (parse-num (:limit cfg)) (parse-num (:trigger cfg))))
                      :s (str (parse-num (:size form)))
                      :r true
                      :t {:trigger {:isMarket (:is-market cfg)
                                    :triggerPx (parse-num (:trigger cfg))
                                    :tpsl tpsl}}})]
    (cond-> []
      tp-enabled? (conj (mk-trigger "tp" tp))
      sl-enabled? (conj (mk-trigger "sl" sl)))))

(defn build-order-action
  "Return {:action action :grouping grouping}"
  [state form]
  (let [active-asset (:active-asset state)
        asset-idx (get-in state [:asset-contexts (keyword active-asset) :idx])
        side (:side form)
        size (parse-num (:size form))
        price (parse-num (:price form))
        trigger (parse-num (:trigger-px form))
        reduce-only (:reduce-only form)
        post-only (:post-only form)
        tif (case (:tif form)
              :ioc "Ioc"
              :alo "Alo"
              "Gtc")
        grouping (if (or (get-in form [:tp :enabled?]) (get-in form [:sl :enabled?]))
                   "normalTpsl"
                   "na")]
    (when (and active-asset asset-idx size)
      (let [base-order {:a asset-idx
                        :b (order-side->is-buy side)
                        :p (str price)
                        :s (str size)
                        :r reduce-only}
            order (case (:type form)
                    :limit (assoc base-order :t {:limit {:tif (if post-only "Alo" tif)}})
                    :market (assoc base-order :t {:limit {:tif "Ioc"}})
                    :stop-market (assoc base-order :p (str (or price trigger))
                                        :t {:trigger {:isMarket true :triggerPx trigger :tpsl "sl"}})
                    :stop-limit (assoc base-order :t {:trigger {:isMarket false :triggerPx trigger :tpsl "sl"}})
                    :take-market (assoc base-order :p (str (or price trigger))
                                        :t {:trigger {:isMarket true :triggerPx trigger :tpsl "tp"}})
                    :take-limit (assoc base-order :t {:trigger {:isMarket false :triggerPx trigger :tpsl "tp"}})
                    base-order)
            tpsl-orders (build-tpsl-orders asset-idx side form)
            orders (cond-> [order]
                     (seq tpsl-orders) (into tpsl-orders))]
        {:action {:type "order"
                  :grouping grouping
                  :orders orders}
         :asset-idx asset-idx
         :orders orders}))))

(defn build-twap-action [state form]
  (let [active-asset (:active-asset state)
        asset-idx (get-in state [:asset-contexts (keyword active-asset) :idx])
        side (:side form)
        size (parse-num (:size form))
        minutes (parse-num (get-in form [:twap :minutes]))
        randomize (boolean (get-in form [:twap :randomize]))]
    (when (and active-asset asset-idx size minutes)
      {:action {:type "twapOrder"
                :twap {:a asset-idx
                       :b (order-side->is-buy side)
                       :s (str size)
                       :r (boolean (:reduce-only form))
                       :m (int minutes)
                       :t randomize}}
       :asset-idx asset-idx})))

(defn best-price [state side]
  (if (= side :buy)
    (best-ask-price state)
    (best-bid-price state)))

(defn apply-market-price [state form]
  (let [side (:side form)
        px (best-price state side)
        slippage (or (parse-num (:slippage form)) 0.5)
        adj (if (= side :buy) (+ 1 (/ slippage 100)) (- 1 (/ slippage 100)))]
    (when px
      (assoc form :price (str (* px adj))))))

(defn build-order-request [state form]
  (case (:type form)
    :twap (build-twap-action state form)
    :scale (let [active-asset (:active-asset state)
                 asset-idx (get-in state [:asset-contexts (keyword active-asset) :idx])
                 side (:side form)
                 size (parse-num (:size form))
                 scale (get-in form [:scale])
                 orders (when (and asset-idx size)
                          (build-scale-orders
                            asset-idx
                            side
                            {:size size :count (:count scale) :skew (:skew scale)}
                            (get scale :start)
                            (get scale :end)
                            (:reduce-only form)
                            (:post-only form)))]
             (when (seq orders)
               {:action {:type "order"
                         :grouping "na"
                         :orders (vec orders)}
                :asset-idx asset-idx
                :orders orders}))
    (build-order-action state form)))
