(ns hyperopen.vol-range.domain.fees
  "Fee capability and conservative fee reserve bounds for Vol Range
  (HO-SPEC-014 v2.1 section 6.5). Pure exact-decimal code.

  `percent->fraction` is the ONE place a display percent becomes a fraction.
  Everything downstream of `fee-capability` works with fractions only."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- d [s] (precision/parse-decimal s))
(defn- ds [x] (precision/decimal->string x))
(def ^:private zero (d "0"))
(def ^:private one-percent (d "0.01"))

(defn percent->fraction
  "Convert a display percent to an exact fraction dec (pct / 100).
  `hyperopen.domain.trading.fees/quote-fees` returns DISPLAY PERCENT, so 0.04
  means 0.04% and converts to the fraction 0.0004. Accepts a finite JS number
  or a decimal string; nil otherwise. This is the single named conversion
  boundary: never divide a fee by 100 anywhere else."
  [pct]
  (when-let [value (cond
                     (number? pct) (precision/number->decimal pct)
                     (string? pct) (precision/parse-decimal (.trim pct))
                     :else nil)]
    (precision/mul value one-percent)))

(defn live-spot-settlement
  "Assumed live spot settlement: the fee is charged in the token received.
  Unverified; callers must mark `:settlement-verified? false`."
  []
  {:buy :base :sell :quote})

(defn fee-capability
  "Normalize a fee quote into a ::fee-capability map. A missing quote or the
  generic display fallback is :unavailable."
  [{:keys [quote fallback? loaded-at-ms settlement settlement-verified? source]}]
  (let [maker (when quote (percent->fraction (get-in quote [:effective :maker])))]
    (if (or (nil? quote) fallback? (nil? maker))
      {:status :unavailable :maker-fraction nil :buy-fee-token nil :sell-fee-token nil
       :settlement-verified? nil :source nil :loaded-at-ms nil}
      (let [settlement (or settlement (live-spot-settlement))]
        {:status :ok
         :maker-fraction (ds maker)
         :buy-fee-token (:buy settlement)
         :sell-fee-token (:sell settlement)
         :settlement-verified? (boolean settlement-verified?)
         :source source
         :loaded-at-ms loaded-at-ms}))))

(defn- sum-field [legs k]
  (reduce precision/add zero (map #(d (get % k)) legs)))

(defn- ceil-fee [x] (precision/quantize x (:fee-reserve-scale contracts/product-policy) :ceil))

(def ^:private no-reserves
  {:buy {:token nil :amount "0"} :sell {:token nil :amount "0"}
   :usdc-debit-bound "0" :base-debit-bound "0" :base-credit-bound "0"})

(defn fee-reserves
  "Conservative per-token fee bounds for the proposed legs. Positive maker
  fees are debit reserves; a negative fee (rebate) never funds anything and
  only raises the upper base-inventory bound when settled in base."
  [{:keys [buy-legs sell-legs capability]}]
  (let [f (when (= :ok (:status capability)) (d (:maker-fraction capability)))]
    (if-not f
      no-reserves
      (let [buy-token (:buy-fee-token capability)
            sell-token (:sell-fee-token capability)
            positive? (precision/pos-dec? f)
            basis (fn [token legs] (sum-field legs (if (= token :quote) :notional :size)))
            fee (fn [token legs] (if positive? (ceil-fee (precision/mul (basis token legs) f)) zero))
            buy-fee (fee buy-token buy-legs)
            sell-fee (fee sell-token sell-legs)
            credit (if (and (precision/neg-dec? f) (= buy-token :base))
                     (ceil-fee (precision/mul (precision/abs-dec f) (sum-field buy-legs :size)))
                     zero)]
        {:buy {:token buy-token :amount (ds buy-fee)}
         :sell {:token sell-token :amount (ds sell-fee)}
         :usdc-debit-bound (ds (if (= buy-token :quote) buy-fee zero))
         :base-debit-bound (ds (if (= sell-token :base) sell-fee zero))
         :base-credit-bound (ds credit)}))))
