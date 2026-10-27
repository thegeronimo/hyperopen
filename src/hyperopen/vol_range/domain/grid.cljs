(ns hyperopen.vol-range.domain.grid
  "Anchor/tilt geometry, legal rung prices and lot allocation for Vol Range
  (HO-SPEC-014 v2.1 sections 5.4-5.5, 6.1-6.2). Pure exact-decimal code; inputs
  are decs from `hyperopen.domain.trading.precision`. Nothing here builds an
  order action."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- d [s] (precision/parse-decimal s))
(def ^:private one (d "1"))
(def ^:private zero (d "0"))
(defn- ds [x] (precision/decimal->string x))
(defn- clamp [x lo hi] (precision/dec-min hi (precision/dec-max lo x)))

;; ---------------------------------------------------------------------------
;; Inventory tilt (5.4)
;; ---------------------------------------------------------------------------

(defn inventory-delta
  "Inventory skew in [-1, 1]; zero at the target."
  [{:keys [q core target maximum]}]
  (let [c (precision/cmp q target)]
    (cond
      (zero? c) zero
      (pos? c) (let [den (precision/sub maximum target)]
                 (if (precision/zero-dec? den)
                   one
                   (clamp (precision/divide (precision/sub q target) den 20 :half-up) zero one)))
      :else (let [den (precision/sub target core)]
              (if (precision/zero-dec? den)
                (precision/neg one)
                (clamp (precision/divide (precision/sub q target) den 20 :half-up)
                       (precision/neg one) zero))))))

(defn shifted-anchor
  "Anchor shifted by inventory tilt: A * (1 - kappa*delta*v), or exactly A."
  [{:keys [anchor v kappa delta tilt-enabled?]}]
  (if-not tilt-enabled?
    {:price anchor}
    (let [factor (precision/sub one (precision/mul (precision/mul kappa delta) v))
          p (precision/quantize (precision/mul anchor factor) 20 :half-up)]
      (if (precision/pos-dec? p)
        {:price p}
        {:error :plan/nonpositive-price}))))

(defn shift-sigma
  "Tilt shift in sigma units, (- kappa*delta), clean string at 4 places."
  [kappa delta]
  (ds (precision/quantize (precision/neg (precision/mul kappa delta)) 4 :half-up)))

;; ---------------------------------------------------------------------------
;; Reference bands and excursion (5.5)
;; ---------------------------------------------------------------------------

(defn bands
  "Reference bands around the UNSHIFTED anchor, unquantized."
  [{:keys [anchor v]}]
  (let [at (fn [k] (ds (precision/mul anchor (precision/add one (precision/mul (d k) v)))))]
    {:minus-2 (at "-2") :minus-1 (at "-1") :plus-1 (at "1") :plus-2 (at "2")}))

(defn- sigma-distance
  "(price / base - 1) / v as one exact division: (price - base) / (base * v)."
  [price base v places]
  (when (and v (not (precision/zero-dec? v)))
    (some-> (precision/divide (precision/sub price base) (precision/mul base v) places :half-up)
            ds)))

(defn excursion-sigma
  "Market excursion from the anchor in sigma, 2 places; nil without volatility."
  [{:keys [mid anchor v]}]
  (when (and mid anchor v) (sigma-distance mid anchor v 2)))

(defn actual-sigma
  "Distance of a rounded price from reference `r` in sigma, 4 places."
  [{:keys [price r v]}]
  (when (and price r v) (sigma-distance price r v 4)))

;; ---------------------------------------------------------------------------
;; Rung prices (6.1-6.2)
;; ---------------------------------------------------------------------------

(defn raw-rung-price
  "Exact raw rung price; one division by (K-1), floor for buys, ceil for sells."
  [{:keys [r v z-start z-end count index side]}]
  (let [sign (if (= side :buy) precision/sub precision/add)]
    (if (= 1 count)
      (precision/mul r (sign one (precision/mul z-start v)))
      (let [km1 (d (str (dec count)))
            z (precision/add (precision/mul z-start km1)
                             (precision/mul (d (str index)) (precision/sub z-end z-start)))
            numerator (precision/mul r (sign km1 (precision/mul z v)))]
        (precision/divide numerator km1 20 (if (= side :buy) :floor :ceil))))))

(defn side-prices
  "Raw and legal prices for every rung of a side, nearest first."
  [{:keys [count side market-type sz-decimals] :as params}]
  (let [opts {:market-type market-type :sz-decimals sz-decimals}
        rows (mapv (fn [i]
                     (let [raw (raw-rung-price (assoc params :index i))
                           price (when (precision/pos-dec? raw)
                                   (precision/quantize-price raw side opts))]
                       {:rung-index i :raw-price raw :price price}))
                   (range count))
        errors (into [] (keep (fn [{:keys [rung-index price]}]
                                (when-not price
                                  (contracts/error :plan/nonpositive-price
                                                   {:rung-index rung-index :side side}))))
                     rows)]
    (if (seq errors) {:errors errors :prices rows} {:prices rows})))

;; ---------------------------------------------------------------------------
;; Lot allocation (6.1)
;; ---------------------------------------------------------------------------

(defn- big [x] (js/BigInt x))
(defn- b+ [a b] (js* "(~{} + ~{})" a b))
(defn- b- [a b] (js* "(~{} - ~{})" a b))
(defn- b* [a b] (js* "(~{} * ~{})" a b))
(defn- bquot [a b] (js* "(~{} / ~{})" a b))
(defn- brem [a b] (js* "(~{} % ~{})" a b))
(defn- bcmp [a b] (cond (js* "(~{} < ~{})" a b) -1 (js* "(~{} > ~{})" a b) 1 :else 0))

(defn- weights
  "Exact integer rung weights from the skew's decimal expansion."
  [skew k]
  (if (= 1 k)
    [(big 1)]
    (let [{:keys [units scale]} (d (ds skew))
          unit (big (str "1" (apply str (repeat scale "0"))))
          m (b- units unit)
          base (b* (big (dec k)) unit)]
      (mapv (fn [i] (b+ base (b* m (big i)))) (range k)))))

(defn allocate-lots
  "Largest-remainder allocation of `quantity` over `count` rungs in whole lots.
  Never drops, merges or resizes a rung."
  [{:keys [quantity sz-decimals count skew]}]
  (let [conv (when (and quantity (precision/pos-dec? quantity))
               (precision/decimal->lots quantity sz-decimals))]
    (cond
      (nil? quantity) {:errors [(contracts/error :input/quantity-required {})]}
      (not (precision/pos-dec? quantity)) {:errors [(contracts/error :input/quantity-required {})]}
      (nil? conv) {:errors [(contracts/error :data/market-metadata-unavailable {})]}
      ;; Spec 6.1: the error carries an explicit suggested valid quantity.
      (:error conv) {:errors [(contracts/error :input/quantity-not-lot-multiple
                                               (let [{:keys [suggested-down suggested-up]} conv
                                                     down? (precision/pos-dec? (precision/parse-decimal suggested-down))]
                                                 {:suggested-down suggested-down
                                                  :suggested-up suggested-up
                                                  :message (str "Quantity must be a multiple of the lot size. Try "
                                                                (if down? (str suggested-down " or ") "")
                                                                suggested-up ".")}))]}
      :else
      (let [n (:lots conv)
            ws (weights skew count)
            sum-w (reduce b+ (big 0) ws)
            prods (mapv #(b* n %) ws)
            floors (mapv #(bquot % sum-w) prods)
            rems (mapv #(brem % sum-w) prods)
            leftover (js/Number (reduce b- n floors))
            winners (->> (range count)
                         (sort (fn [a b] (let [c (bcmp (nth rems b) (nth rems a))]
                                           (if (zero? c) (compare a b) c))))
                         (take leftover)
                         set)
            lots (vec (map-indexed (fn [i f] (if (contains? winners i) (b+ f (big 1)) f)) floors))
            sizes (mapv #(precision/lots->decimal % sz-decimals) lots)
            zero-errors (into [] (keep-indexed
                                  (fn [i l] (when (zero? (bcmp l (big 0)))
                                              (contracts/error :plan/zero-size-rung {:rung-index i}))))
                              lots)]
        (cond-> {:lots lots :sizes sizes}
          (seq zero-errors) (assoc :errors zero-errors))))))

(defn build-side-legs
  "Legs for one side, nearest first, plus any errors with diagnostic legs."
  [{:keys [side r v quantity skew sz-decimals] :as params}]
  (let [prices (side-prices params)
        alloc (allocate-lots (assoc params :quantity quantity :skew skew))
        sizes (:sizes alloc)
        legs (into []
                   (keep (fn [{:keys [rung-index raw-price price]}]
                           (let [size (get sizes rung-index)]
                             (when (and price size)
                               {:leg-id (str (name side) "-" rung-index)
                                :side side
                                :rung-index rung-index
                                :price (ds price)
                                :size (ds size)
                                :notional (ds (precision/mul price size))
                                :actual-sigma (actual-sigma {:price price :r r :v v})
                                :raw-price (ds raw-price)}))))
                   (:prices prices))]
    {:legs legs
     :errors (vec (concat (:errors prices)
                          ;; Allocation errors belong to this side's quantity field, so two
                          ;; sides' identical errors stay distinct and the input is marked.
                          (map (fn [e]
                                 (cond-> (update e :data assoc :side side :path [side :quantity])
                                   (= :input/quantity-not-lot-multiple (:code e))
                                   (update :message #(str (if (= side :buy) "Buy" "Sell") " " (str/lower-case (subs % 0 1)) (subs % 1)))))
                               (:errors alloc))))}))
