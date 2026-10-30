(ns hyperopen.vol-range.domain.quote-sizing
  "Sizing a side from a USDC amount instead of a base quantity.

  Rung prices do not depend on size, so for a given amount the planner looks
  for the largest whole-lot base quantity whose ladder fits:

    - buys: gross order value plus the USDC fee reserve <= amount (the amount
      is also the side's maximum USDC debit);
    - sells: gross order value <= amount.

  The result is an ordinary base quantity; everything downstream (allocation,
  checks, review, revalidation) is unchanged. Pure exact-decimal code."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.fees :as fees]
            [hyperopen.vol-range.domain.grid :as grid]))

(defn- d [s] (precision/parse-decimal s))
(defn- ds [x] (precision/decimal->string x))
(def ^:private zero (d "0"))

(defn- big [x] (js/BigInt x))
(defn- b- [a b] (js* "(~{} - ~{})" a b))
(defn- bhalf-up [a b] (js* "((~{} + ~{} + 1n) / 2n)" a b))
(defn- b>= [a b] (js* "(~{} >= ~{})" a b))

(defn- cost
  "USDC cost of the ladder at `n` lots (a BigInt), or nil when a rung would
  round to zero lots. Prices are computed once by the caller."
  [{:keys [side sz-decimals fee-capability] :as params} prices n]
  (let [{:keys [sizes errors]} (grid/allocate-lots (assoc params :quantity (precision/lots->decimal n sz-decimals)))]
    (when (and (empty? errors) (= (count sizes) (count prices)))
      (let [legs (mapv (fn [{:keys [price]} size]
                         {:size (ds size) :notional (ds (precision/mul price size))})
                       prices sizes)
            gross (reduce precision/add zero (map #(d (:notional %)) legs))]
        (if (= side :buy)
          (precision/add gross (d (:usdc-debit-bound (fees/fee-reserves {:buy-legs legs
                                                                          :capability fee-capability}))))
          gross)))))

(defn- fits? [params prices amount n]
  (when-let [c (cost params prices n)] (<= (precision/cmp c amount) 0)))

(def ^:private max-min-lot-scan
  "Upper bound on the scan for the smallest buildable lot count. Skew is at
  most 3.5x and count at most 12, so every rung has a lot by about 27 lots."
  2000)

(defn- min-buildable-lots [params prices]
  (some #(let [n (big %)] (when (cost params prices n) n))
        (range (:count params) (+ (:count params) max-min-lot-scan))))

(defn- error [code side data]
  (contracts/error code (merge {:side side :path [side :quote-amount]} data)))

(defn solve-quantity
  "{:quantity dec} for the largest whole-lot base quantity whose ladder fits
  `amount` USDC, or {:errors [...]}.

  Keys: :side :amount (dec) :r :v :sz-decimals :count :z-start :z-end :skew
  :market-type, and :fee-capability (buys only; reserves count against the
  amount)."
  [{:keys [side amount sz-decimals] :as params}]
  (let [{price-errors :errors rows :prices} (grid/side-prices params)
        prices (when (empty? price-errors) (mapv #(select-keys % [:price]) rows))
        n-min (when prices (min-buildable-lots params prices))]
    (cond
      (seq price-errors) {:errors price-errors}
      (nil? n-min) {:errors [(error :plan/zero-size-rung side {})]}
      (not (fits? params prices amount n-min))
      (let [minimum (precision/quantize (cost params prices n-min) 2 :ceil)]
        {:errors [(error :input/quote-amount-too-small side
                         {:minimum (ds minimum)
                          :message (str "Too small for " (:count params) " "
                                        (if (= side :buy) "buy" "sell") " rungs. Enter at least "
                                        (precision/to-fixed minimum 2) " USDC, or use fewer rungs.")})]})
      :else
      ;; Gross value grows by at least one lot at the lowest rung price per
      ;; extra lot, which bounds the search from above. Lot counts are BigInts:
      ;; a large amount over a tiny lot exceeds 2^53.
      (let [lot (precision/lots->decimal 1 sz-decimals)
            low (reduce precision/dec-min (map :price prices))
            hi (:units (precision/divide amount (precision/mul lot low) 0 :floor))
            hi (if (b>= hi n-min) hi n-min)
            ;; Largest fitting n by bisection; `lo` only ever holds a fitting
            ;; count, so the result fits even though largest-remainder
            ;; allocation is not strictly monotone in cost.
            n (loop [lo n-min hi hi]
                (if (b>= lo hi)
                  lo
                  (let [mid (bhalf-up lo hi)]
                    (if (fits? params prices amount mid) (recur mid hi) (recur lo (b- mid (big 1)))))))]
        {:quantity (precision/lots->decimal n sz-decimals)}))))
