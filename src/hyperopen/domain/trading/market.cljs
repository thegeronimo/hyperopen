(ns hyperopen.domain.trading.market
  (:require [clojure.string :as str]
            [hyperopen.domain.market.instrument :as instrument]
            [hyperopen.domain.trading.position-lookup :as position-lookup]
            [hyperopen.domain.trading.core :as core]
            [hyperopen.domain.trading.fees :as fees]))

(defn- unified-account-mode? [context]
  (= :unified (get-in context [:account :mode])))

(defn- usdc-coin? [coin]
  (and (string? coin)
       (str/starts-with? coin "USDC")))

(defn market-identity
  "Return a deterministic market identity summary for UI and action policy.
   {:base-symbol string
    :quote-symbol string
    :spot? boolean
    :hip3? boolean
    :read-only? boolean}"
  [{:keys [active-asset market]}]
  (instrument/market-identity active-asset (or market {})))

(defn- unified-spot-usdc-available [context]
  (when (unified-account-mode? context)
    (some (fn [balance]
            (when (usdc-coin? (:coin balance))
              (let [available-direct (or (core/parse-num (:available balance))
                                         (core/parse-num (:availableBalance balance))
                                         (core/parse-num (:available-balance balance)))
                    total (core/parse-num (:total balance))
                    hold (core/parse-num (:hold balance))
                    available-derived (when (number? total)
                                        (- total (or hold 0)))
                    available (if (number? available-direct)
                                available-direct
                                available-derived)]
                (when (number? available)
                  (max 0 available)))))
          (get-in context [:spot :clearinghouse-state :balances]))))

(defn available-to-trade [context]
  (let [clearinghouse (or (:clearinghouse context) {})
        unified-available (unified-spot-usdc-available context)
        withdrawable (core/parse-num (:withdrawable clearinghouse))
        summary (or (get-in clearinghouse [:marginSummary])
                    (get-in clearinghouse [:crossMarginSummary])
                    {})
        account-value (or (core/parse-num (:accountValue summary)) 0)
        margin-used (or (core/parse-num (:totalMarginUsed summary)) 0)
        fallback-available (- account-value margin-used)]
    (max 0 (cond
             (number? unified-available) unified-available
             (number? withdrawable) withdrawable
             :else fallback-available))))

(defn position-for-market
  [context]
  (position-lookup/position-for-market context))

(defn position-for-active-asset [context]
  (position-for-market context))

(defn current-position-summary [context]
  (let [active-asset (:active-asset context)
        position (position-for-market context)
        size (or (core/parse-num (:szi position)) 0)
        liquidation (core/parse-num (:liquidationPx position))]
    {:coin active-asset
     :size size
     :abs-size (js/Math.abs size)
     :direction (cond
                  (pos? size) :long
                  (neg? size) :short
                  :else :flat)
     :liquidation-price (when (and (number? liquidation) (pos? liquidation))
                          liquidation)}))

(defn- estimated-maintenance-margin
  "Approximate maintenance requirement using market max leverage.
   This provides a deterministic liquidation estimate for new/flat positions."
  [context order-value]
  (let [max-lev (core/market-max-leverage context)]
    (when (and (number? order-value)
               (pos? order-value)
               (number? max-lev)
               (pos? max-lev))
      (/ order-value max-lev))))

(defn- projected-liquidation-price
  [context form available ref-price order-value]
  (let [size (core/parse-num (:size form))
        side (:side form)
        maintenance-margin (estimated-maintenance-margin context order-value)]
    (when (and (number? available)
               (number? ref-price)
               (pos? ref-price)
               (number? size)
               (pos? size)
               (number? maintenance-margin))
      (let [collateral-buffer (- available maintenance-margin)
            projected (if (= side :sell)
                        (+ ref-price (/ collateral-buffer size))
                        (- ref-price (/ collateral-buffer size)))]
        (when (and (number? projected)
                   (pos? projected))
          projected)))))

(defn- size-decimals [context]
  (let [sz-decimals (get-in context [:market :szDecimals])
        parsed (core/parse-num sz-decimals)]
    (-> (or parsed 4)
        (max 0)
        (min 8)
        int)))

(def ^:private max-market-price-significant-figures 5)
(def ^:private max-perp-price-decimals 6)
(def ^:private max-spot-price-decimals 8)

(defn- normalized-market-type [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim str/lower-case keyword)
    :else nil))

(defn- spot-market? [context]
  (let [market-type (normalized-market-type (get-in context [:market :market-type]))]
    (or (= :spot market-type)
        (:spot? (market-identity {:active-asset (:active-asset context)
                                  :market (:market context)})))))

(defn- max-price-decimals [context]
  (let [max-decimals (if (spot-market? context)
                       max-spot-price-decimals
                       max-perp-price-decimals)]
    (-> (- max-decimals (size-decimals context))
        (max 0)
        int)))

(defn- finite-positive-number? [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)
       (pos? value)))

(defn- integer-number? [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)
       (= value (js/Math.trunc value))))

(defn- truncate-to-decimals [value decimals]
  (let [safe-decimals (-> decimals
                          (max 0)
                          int)
        factor (js/Math.pow 10 safe-decimals)]
    (/ (js/Math.floor (* value factor)) factor)))

(defn- truncate-to-significant-figures [value significant-figures]
  (let [safe-significant-figures (int significant-figures)
        magnitude (js/Math.floor (/ (js/Math.log value)
                                    (js/Math.log 10)))
        shift (- (inc magnitude) safe-significant-figures)]
    (if (neg? shift)
      (truncate-to-decimals value (- shift))
      (let [factor (js/Math.pow 10 shift)]
        (* (js/Math.floor (/ value factor)) factor)))))

(defn canonical-order-price-string
  "Format order prices according to Hyperliquid tick+sig-fig constraints."
  [context price]
  (when (finite-positive-number? price)
    (let [decimals (max-price-decimals context)
          integer-input? (integer-number? price)
          decimals-truncated (truncate-to-decimals price decimals)
          sig-truncated (if integer-input?
                          decimals-truncated
                          (truncate-to-significant-figures decimals-truncated
                                                           max-market-price-significant-figures))
          formatted (core/number->clean-string sig-truncated decimals)
          parsed (core/parse-num formatted)]
      (when (and (seq formatted)
                 (number? parsed)
                 (pos? parsed))
        formatted))))

(defn base-size-string
  "Format canonical base size by truncating to market szDecimals."
  [context value]
  (when (and (number? value) (pos? value))
    (let [decimals (size-decimals context)
          factor (js/Math.pow 10 decimals)
          truncated (/ (js/Math.floor (* value factor)) factor)]
      (when (pos? truncated)
        (core/number->clean-string truncated decimals)))))

(defn- level-price [level]
  (let [price (or (core/parse-num (:px level))
                  (core/parse-num (:price level))
                  (core/parse-num (:p level)))]
    (when (and (number? price) (pos? price))
      price)))

(defn- level-size [level]
  (let [size (or (core/parse-num (:sz level))
                 (core/parse-num (:size level))
                 (core/parse-num (:s level)))]
    (when (and (number? size) (pos? size))
      size)))

(defn- normalize-level [level]
  (let [price (level-price level)
        size (level-size level)]
    (when (and (number? price)
               (pos? price)
               (number? size)
               (pos? size))
      {:price price
       :size size})))

(defn- ordered-market-levels [context side]
  (let [raw-levels (case side
                     :buy (get-in context [:orderbook :asks])
                     :sell (get-in context [:orderbook :bids])
                     nil)
        comparator (case side
                     :buy <
                     :sell >
                     nil)]
    (when comparator
      (->> (or raw-levels [])
           (keep normalize-level)
           (sort-by :price comparator)
           vec))))

(defn- top-of-book-midpoint [context]
  (let [bids (->> (get-in context [:orderbook :bids])
                  (keep normalize-level))
        asks (->> (get-in context [:orderbook :asks])
                  (keep normalize-level))
        best-bid (reduce (fn [best level]
                           (let [price (:price level)]
                             (if (or (nil? best) (> price best))
                               price
                               best)))
                         nil
                         bids)
        best-ask (reduce (fn [best level]
                           (let [price (:price level)]
                             (if (or (nil? best) (< price best))
                               price
                               best)))
                         nil
                         asks)]
    (when (and (number? best-bid)
               (pos? best-bid)
               (number? best-ask)
               (pos? best-ask))
      (/ (+ best-bid best-ask) 2))))

(defn- simulate-average-fill-price [levels requested-size]
  ;; Walk sorted levels deterministically and require a full simulated fill.
  ;; Return nil when visible depth cannot satisfy requested-size.
  (when (and (number? requested-size) (pos? requested-size))
    (loop [remaining requested-size
           filled-notional 0
           remaining-levels levels]
      (cond
        (<= remaining 0)
        (/ filled-notional requested-size)

        (empty? remaining-levels)
        nil

        :else
        (let [{:keys [price size]} (first remaining-levels)
              fill-size (min remaining size)]
          (recur (- remaining fill-size)
                 (+ filled-notional (* fill-size price))
                 (rest remaining-levels)))))))

(defn- market-slippage-estimate-pct [context form]
  (let [requested-size (core/parse-num (:size form))
        side (:side form)
        levels (ordered-market-levels context side)
        avg-fill (simulate-average-fill-price levels requested-size)
        midpoint (top-of-book-midpoint context)]
    (when (and (number? avg-fill)
               (pos? avg-fill)
               (number? midpoint)
               (pos? midpoint))
      (* 100 (/ (js/Math.abs (- avg-fill midpoint)) midpoint)))))

(defn- best-side-price
  "Return best price for a side independent of vector ordering.
   For bids, pass > comparator. For asks, pass < comparator."
  [levels better?]
  (reduce (fn [best level]
            (let [px (core/parse-num (:px level))]
              (if (and (number? px)
                       (pos? px)
                       (or (nil? best) (better? px best)))
                px
                best)))
          nil
          (or levels [])))

(defn best-bid-price [context]
  (best-side-price (get-in context [:orderbook :bids]) >))

(defn best-ask-price [context]
  (best-side-price (get-in context [:orderbook :asks]) <))

(defn reference-price [context form]
  (let [order-type (core/normalize-order-type (:type form))
        limit-price (when (core/limit-like-type? order-type)
                      (core/parse-num (:price form)))
        side (:side form)
        best-px (if (= side :buy)
                  (best-ask-price context)
                  (best-bid-price context))
        projected-mark (core/parse-num (get-in context [:market :mark]))
        streamed-mark (core/parse-num (get-in context [:market :streamed-mark]))]
    (cond
      (and (number? limit-price) (pos? limit-price)) limit-price
      (and (number? best-px) (pos? best-px)) best-px
      (and (number? projected-mark) (pos? projected-mark)) projected-mark
      (and (number? streamed-mark) (pos? streamed-mark)) streamed-mark
      :else nil)))

(defn mid-price-summary
  "Return deterministic price context for UI rows:
   {:mid-price number|nil :source :mid|:reference|:none}."
  [context form]
  (let [bid (best-bid-price context)
        ask (best-ask-price context)
        mid (when (and (number? bid)
                       (pos? bid)
                       (number? ask)
                       (pos? ask))
              (/ (+ bid ask) 2))
        ref (reference-price context form)]
    (cond
      (number? mid) {:mid-price mid :source :mid}
      (number? ref) {:mid-price ref :source :reference}
      :else {:mid-price nil :source :none})))

(defn effective-limit-price
  "Return a deterministic fallback price for limit-like order types.
   Prefers mid (bid/ask average), then reference price."
  [context form]
  (when (core/limit-like-type? (:type form))
    (let [{:keys [mid-price]} (mid-price-summary context form)
          ref (reference-price context form)]
      (cond
        (and (number? mid-price) (pos? mid-price)) mid-price
        (and (number? ref) (pos? ref)) ref
        :else nil))))

(defn effective-limit-price-string
  "String representation for deterministic limit fallback price."
  [context form]
  (when-let [price (effective-limit-price context form)]
    (core/number->clean-string price 6)))

(defn mid-price-string
  "String representation for the true midpoint (best bid/ask average).
   Returns nil when midpoint is unavailable."
  [context form]
  (let [{:keys [mid-price source]} (mid-price-summary context form)]
    (when (and (= source :mid)
               (number? mid-price)
               (pos? mid-price))
      (core/number->clean-string mid-price 6))))

(defn size-from-percent [context form percent]
  (let [pct (core/clamp-percent percent)
        available (available-to-trade context)
        leverage (core/normalize-ui-leverage context (:ui-leverage form))
        ref-price (reference-price context form)
        notional (* available leverage (/ pct 100))]
    (when (and (pos? pct)
               (number? ref-price)
               (pos? ref-price)
               (pos? notional))
      (/ notional ref-price))))

(defn percent-from-size [context form size]
  (let [size-value (core/parse-num size)
        available (available-to-trade context)
        leverage (core/normalize-ui-leverage context (:ui-leverage form))
        ref-price (reference-price context form)
        notional-capacity (* available leverage)]
    (when (and (number? size-value)
               (pos? size-value)
               (number? ref-price)
               (pos? ref-price)
               (pos? notional-capacity))
      (core/clamp-percent (* 100 (/ (* size-value ref-price) notional-capacity))))))

(defn apply-size-percent [context form percent]
  (let [pct (core/clamp-percent percent)
        available (available-to-trade context)
        leverage (core/normalize-ui-leverage context (:ui-leverage form))
        notional (* available leverage (/ pct 100))
        normalized-form (assoc form
                               :size-percent pct
                               :ui-leverage (core/normalize-ui-leverage context (:ui-leverage form)))
        computed-size (size-from-percent context normalized-form pct)
        quantized-size (when (number? computed-size)
                         (base-size-string context computed-size))
        display-notional (when (and (number? notional) (pos? notional))
                           (core/number->clean-string notional 2))]
    (cond
      (zero? pct) (assoc normalized-form :size "" :size-display "")
      (seq quantized-size) (assoc normalized-form
                                  :size quantized-size
                                  :size-display (or display-notional ""))
      :else normalized-form)))

(defn sync-size-from-percent [context form]
  (let [pct (core/clamp-percent (:size-percent form))]
    (if (pos? pct)
      (apply-size-percent context form pct)
      (assoc form
             :size-percent pct
             :ui-leverage (core/normalize-ui-leverage context (:ui-leverage form))))))

(defn sync-size-percent-from-size [context form]
  (let [size (core/parse-num (:size form))
        derived (percent-from-size context form (:size form))
        leverage (core/normalize-ui-leverage context (:ui-leverage form))]
    (cond
      (or (nil? size) (<= size 0))
      (assoc form :size-percent 0 :ui-leverage leverage)

      (number? derived)
      (assoc form :size-percent derived :ui-leverage leverage)

      :else
      (assoc form
             :size-percent (core/clamp-percent (:size-percent form))
             :ui-leverage leverage))))

(defn- context->fee-context
  [context]
  (let [market (or (:market context) {})
        dex (:dex market)]
    {:market-type (:market-type market)
     :stable-pair? (boolean (:stable-pair? market))
     :growth-mode? (boolean (:growth-mode? market))
     :dex dex
     :deployer-fee-scale (get-in context [:perp-dex-fee-config-by-name dex :deployer-fee-scale])
     :special-quote-fee-adjustment? (boolean (:special-quote-fee-adjustment? context))
     :user-fees (:user-fees context)}))

(defn- fee-quote
  [fee-context]
  (let [fee-context* (or fee-context {})
        user-fees (:user-fees fee-context*)]
    (or (fees/quote-fees user-fees {:market-type (:market-type fee-context*)
                                    :stable-pair? (boolean (:stable-pair? fee-context*))
                                    :deployer-fee-scale (:deployer-fee-scale fee-context*)
                                    :growth-mode? (boolean (:growth-mode? fee-context*))
                                    :extra-adjustment? (boolean (:special-quote-fee-adjustment? fee-context*))})
        (fees/default-fee-quote))))

(defn order-summary
  ([context form]
   (order-summary context form (context->fee-context context)))
  ([context form fee-context]
   (let [fee-context* (or fee-context (context->fee-context context))
         size (core/parse-num (:size form))
         ref-price (reference-price context form)
         available (available-to-trade context)
         order-value (when (and (number? size)
                                (pos? size)
                                (number? ref-price)
                                (pos? ref-price))
                       (* size ref-price))
         leverage (core/normalize-ui-leverage context (:ui-leverage form))
         margin-required (when (and (number? order-value) (pos? leverage))
                           (/ order-value leverage))
         position (current-position-summary context)
         liquidation-price (or (:liquidation-price position)
                               (projected-liquidation-price context form available ref-price order-value))
         requested-type (core/normalize-order-type (or (:requested-type form) (:type form)))
         market-order? (= :market requested-type)
         slippage-est (if market-order?
                        (market-slippage-estimate-pct context form)
                        0)]
     {:available-to-trade available
      :current-position position
      :reference-price ref-price
      :order-value order-value
      :margin-required margin-required
      :liquidation-price liquidation-price
      :slippage-est slippage-est
      :slippage-max core/default-max-slippage-pct
      :fees (fee-quote fee-context*)})))

(defn best-price [context side]
  (if (= side :buy)
    (best-ask-price context)
    (best-bid-price context)))

(defn apply-market-price [context form]
  (let [side (:side form)
        px (best-price context side)
        slippage (let [parsed (core/parse-num (:slippage form))]
                   (core/clamp-percent
                    (if (number? parsed)
                      parsed
                      core/default-market-slippage-pct)))
        adj (if (= side :buy) (+ 1 (/ slippage 100)) (- 1 (/ slippage 100)))
        adjusted-price (when (finite-positive-number? px)
                         (* px adj))]
    (when-let [market-price (canonical-order-price-string context adjusted-price)]
      (assoc form :price market-price))))
