(ns hyperopen.vol-range.domain.admission
  "Single owner of Vol Range input, inventory, cash, fee, price and capability
  invariants (HO-SPEC-014 v2.1 sections 4.1-4.3, 6.3-6.6). Pure: every input is
  an injected value. Nothing here builds an exchange action."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.volatility :as volatility]))

(defn- d [s] (precision/parse-decimal s))
(defn- ds [x] (precision/decimal->string x))
(def ^:private zero (d "0"))
(defn- cmp [a b] (precision/cmp a b))
(defn- err [code path & [extra]] (contracts/error code (merge {:path path} extra)))

;; ---------------------------------------------------------------------------
;; Input validation (4.1-4.3)
;; ---------------------------------------------------------------------------

(defn- parse-field
  "{:blank? true} | {:invalid? true} | {:value dec}."
  [x]
  (cond
    (nil? x) {:blank? true}
    (number? x) (if-let [v (precision/number->decimal x)] {:value v} {:invalid? true})
    (string? x) (let [t (.trim x)]
                  (cond (= "" t) {:blank? true}
                        :else (if-let [v (d t)] {:value v} {:invalid? true})))
    :else {:invalid? true}))

(defn- in-range? [v {:keys [min max]}]
  (and (>= (cmp v (d min)) 0) (<= (cmp v (d max)) 0)))

(defn- positive-field
  "Required positive decimal; `blank-code` for blank or non-positive."
  [x path blank-code]
  (let [{:keys [value blank? invalid?]} (parse-field x)]
    (cond blank? {:errors [(err blank-code path)]}
          invalid? {:errors [(err :input/invalid-number path)]}
          (precision/pos-dec? value) {:value value}
          :else {:errors [(err blank-code path)]})))

(defn- ranged-field [x path code range-policy]
  (let [{:keys [value invalid? blank?]} (parse-field x)]
    (cond (or blank? invalid?) {:errors [(err :input/invalid-number path)]}
          (in-range? value range-policy) {:value value}
          :else {:errors [(err code path)]})))

(defn- count-field [x path {:keys [min max]}]
  (let [{:keys [value invalid? blank?]} (parse-field x)]
    (cond (or blank? invalid?) {:errors [(err :input/invalid-number path)]}
          (and (integer? (js/Number (ds value)))
               (not (.includes (ds value) "."))
               (<= min (js/Number (ds value)) max))
          {:value (js/Number (ds value))}
          :else {:errors [(err :input/count-out-of-range path)]})))

(defn- validate-side [side sd policy]
  (if-not (:enabled? sd)
    {:resolved {:enabled? false} :errors []}
    (let [p (fn [k] [side k])
          qty (positive-field (:quantity sd) (p :quantity) :input/quantity-required)
          cnt (count-field (:count sd) (p :count) (:rung-count policy))
          start (ranged-field (:start-sigma sd) (p :start-sigma) :input/distance-out-of-range (:distance-sigma policy))
          end (ranged-field (:end-sigma sd) (p :end-sigma) :input/distance-out-of-range (:distance-sigma policy))
          skew (ranged-field (:skew sd) (p :skew) :input/skew-out-of-range (:skew policy))
          debit (when (= side :buy)
                  (positive-field (:max-usdc-debit sd) (p :max-usdc-debit) :input/max-debit-required))
          order-error (when (and (:value start) (:value end) (pos? (cmp (:value start) (:value end))))
                        [(err :input/start-above-end (p :start-sigma))])]
      {:resolved (cond-> {:enabled? true :quantity (:value qty) :count (:value cnt)
                          :start (:value start) :end (:value end) :skew (:value skew)}
                   debit (assoc :max-usdc-debit (:value debit)))
       :errors (vec (concat (:errors qty) (:errors cnt) (:errors start) (:errors end)
                            (:errors skew) (:errors debit) order-error))})))

(defn check-inventory-limits
  "Require core <= target <= maximum over the values that are present."
  [{:keys [core target maximum]}]
  (cond
    (and core target (pos? (cmp core target)))
    [(err :input/inventory-limits-order [:inventory :protected-core])]
    (and target maximum (pos? (cmp target maximum)))
    [(err :input/inventory-limits-order [:inventory :target])]
    (and core maximum (pos? (cmp core maximum)))
    [(err :input/inventory-limits-order [:inventory :protected-core])]
    :else []))

(defn- optional-nonneg [x path]
  (let [{:keys [value blank? invalid?]} (parse-field x)]
    (cond blank? {}
          invalid? {:errors [(err :input/invalid-number path)]}
          (precision/neg-dec? value) {:errors [(err :input/invalid-number path)]}
          :else {:value value})))

(defn validate-inputs
  "Validate a locale-normalized draft. Returns {:resolved ... :errors [...]}.
  Fields resolve independently (nil when invalid) so geometry can still be
  computed for diagnostics. Blank protected core / target are not errors."
  [draft policy]
  (let [policy (or policy contracts/product-policy)
        {:keys [anchor volatility inventory buy sell]} draft
        anchor-mode (or (:mode anchor) :vwap-24h)
        custom (when (= :custom anchor-mode)
                 (let [r (positive-field (:custom-price anchor) [:anchor :custom-price] :input/custom-anchor-invalid)]
                   (if (:errors r)
                     {:errors [(err :input/custom-anchor-invalid [:anchor :custom-price])]}
                     r)))
        vol-mode (or (:mode volatility) :auto)
        manual (when (= :manual vol-mode)
                 (let [{:keys [daily-fraction error]} (volatility/manual-daily-fraction (:manual-daily-pct volatility))]
                   (if error
                     {:errors [(err error [:volatility :manual-daily-pct])]}
                     {:value daily-fraction})))
        core (optional-nonneg (:protected-core inventory) [:inventory :protected-core])
        target (optional-nonneg (:target inventory) [:inventory :target])
        maximum-parsed (parse-field (:maximum inventory))
        buys? (boolean (:enabled? buy))
        maximum (cond
                  (:invalid? maximum-parsed) {:errors [(err :input/invalid-number [:inventory :maximum])]}
                  (:blank? maximum-parsed) (when buys? {:errors [(err :input/maximum-required [:inventory :maximum])]})
                  (precision/pos-dec? (:value maximum-parsed)) {:value (:value maximum-parsed)}
                  :else {:errors [(err :input/maximum-required [:inventory :maximum])]})
        tilt? (boolean (:tilt-enabled? inventory))
        strength (when tilt?
                   (let [{:keys [value blank? invalid?]} (parse-field (:tilt-strength inventory))]
                     (cond blank? {:value (d (get-in policy [:tilt-strength :default]))}
                           invalid? {:errors [(err :input/invalid-number [:inventory :tilt-strength])]}
                           (in-range? value (:tilt-strength policy)) {:value value}
                           :else {:errors [(err :input/tilt-strength-out-of-range [:inventory :tilt-strength])]})))
        buy-r (validate-side :buy buy policy)
        sell-r (validate-side :sell sell policy)
        limits (check-inventory-limits {:core (:value core) :target (:value target) :maximum (:value maximum)})
        no-side (when-not (or buys? (:enabled? sell))
                  [(err :input/no-side-enabled [:buy :enabled?])])
        errors (vec (concat no-side
                            (:errors buy-r) (:errors sell-r)
                            (:errors custom) (:errors manual) (:errors strength)
                            (:errors core) (:errors target) (:errors maximum) limits))]
    {:resolved {:anchor-mode anchor-mode
                :custom-price (:value custom)
                :vol-mode vol-mode
                :manual-daily-fraction (:value manual)
                :tilt-enabled? tilt?
                :tilt-strength (:value strength)
                :core (:value core)
                :target (:value target)
                :maximum (:value maximum)
                :buy (:resolved buy-r)
                :sell (:resolved sell-r)}
     :errors errors}))

;; ---------------------------------------------------------------------------
;; Price checks (6.3-6.4)
;; ---------------------------------------------------------------------------

(defn- ->dec [x] (cond (map? x) x (string? x) (d x) :else nil))

(defn- duplicate-errors [side legs]
  (let [dups (->> legs (map :price) frequencies (filter (fn [[_ n]] (> n 1))) (map first))]
    (when (seq dups)
      [(err :plan/duplicate-price [] {:side side :price (first dups)})])))

(defn check-legs
  "Price-level checks over the final leg vectors against the BBO and mid."
  [{:keys [buy-legs sell-legs bbo mid policy]}]
  (let [policy (or policy contracts/product-policy)
        bid (->dec (:bid bbo))
        ask (->dec (:ask bbo))
        bbo-ok? (and bid ask (precision/pos-dec? bid) (precision/pos-dec? ask))
        crossed? (and bbo-ok? (>= (cmp bid ask) 0))
        mid (or (->dec mid) (when (and bbo-ok? (not crossed?))
                              (precision/mul (precision/add bid ask) (d "0.5"))))
        floor-n (d (:min-leg-notional-usdc policy))
        guard (d (:max-distance-from-mid-fraction policy))
        usable? (and bbo-ok? (not crossed?))
        legs (concat buy-legs sell-legs)
        buy-prices (map #(d (:price %)) buy-legs)
        sell-prices (map #(d (:price %)) sell-legs)]
    (vec
     (concat
      (cond (not bbo-ok?) [(contracts/error :data/bbo-unavailable {})]
            crossed? [(contracts/error :data/bbo-crossed {})])
      (when usable?
        (concat
         (for [l buy-legs :when (>= (cmp (d (:price l)) ask) 0)]
           (contracts/error :plan/post-only-cross
                            {:leg-id (:leg-id l) :side :buy :price (:price l) :touch :ask
                             :ask (ds ask) :touch-price (ds ask)
                             :message (str "Buy at " (:price l) " is above the best ask " (ds ask))}))
         (for [l sell-legs :when (<= (cmp (d (:price l)) bid) 0)]
           (contracts/error :plan/post-only-cross
                            {:leg-id (:leg-id l) :side :sell :price (:price l) :touch :bid
                             :bid (ds bid) :touch-price (ds bid)
                             :message (str "Sell at " (:price l) " is below the best bid " (ds bid))}))))
      (when (and (seq buy-prices) (seq sell-prices)
                 (>= (cmp (reduce #(if (pos? (cmp %2 %1)) %2 %1) buy-prices)
                          (reduce #(if (neg? (cmp %2 %1)) %2 %1) sell-prices)) 0))
        [(contracts/error :plan/self-cross {})])
      (duplicate-errors :buy buy-legs)
      (duplicate-errors :sell sell-legs)
      (when mid
        (for [l legs
              :let [p (d (:price l))]
              :when (pos? (cmp (precision/abs-dec (precision/sub p mid)) (precision/mul guard mid)))]
          (contracts/error :plan/distance-guard {:leg-id (:leg-id l) :side (:side l) :price (:price l)})))
      (for [l legs :when (neg? (cmp (d (:notional l)) floor-n))]
        (contracts/error :plan/min-notional {:leg-id (:leg-id l) :side (:side l) :notional (:notional l)}))))))

;; ---------------------------------------------------------------------------
;; Inventory and cash bounds (6.6)
;; ---------------------------------------------------------------------------

(defn corrective-mode
  "Starting breach: :above-maximum, :below-core, or nil."
  [{:keys [q core maximum]}]
  (cond (and maximum (pos? (cmp q maximum))) :above-maximum
        (and core (neg? (cmp q core))) :below-core
        :else nil))

(defn inventory-outcomes
  "Worst-case inventory endpoints as clean strings. Existing open orders
  count once; buys and sells are never offset."
  [{:keys [q b-existing s-existing b-new s-new base-credit-bound base-debit-bound core target maximum]}]
  (let [z #(or % zero)
        b-existing (z b-existing) s-existing (z s-existing) b-new (z b-new) s-new (z s-new)]
    {:current (ds q)
     :core (some-> core ds)
     :target (some-> target ds)
     :maximum (some-> maximum ds)
     :all-buys-first (ds (precision/add (precision/add (precision/add q b-existing) b-new) (z base-credit-bound)))
     :all-sells-first (ds (precision/sub (precision/sub (precision/sub q s-existing) s-new) (z base-debit-bound)))
     :all-fill (ds (precision/sub (precision/add q b-new) s-new))
     :existing-buy-exposure (ds b-existing)
     :existing-sell-exposure (ds s-existing)
     :starting-breach (corrective-mode {:q q :core core :maximum maximum})}))

(defn check-bounds
  "Spec 6.6 hard constraints with directional corrective handling. Cash, fee
  and balance checks always apply; corrective plans only get the directional
  inventory exception."
  [{:keys [q core maximum b-existing s-existing b-new s-new base-credit-bound base-debit-bound
           usdc-debit-bound buy-notional cap available-usdc available-base]}]
  (let [z #(or % zero)
        b-new (z b-new) s-new (z s-new)
        out (inventory-outcomes {:q q :b-existing b-existing :s-existing s-existing :b-new b-new
                                 :s-new s-new :base-credit-bound base-credit-bound
                                 :base-debit-bound base-debit-bound :core core :maximum maximum})
        all-buy (d (:all-buys-first out))
        all-sell (d (:all-sells-first out))
        mode (:starting-breach out)
        buys? (precision/pos-dec? b-new)
        sells? (precision/pos-dec? s-new)
        max-debit (precision/add (z buy-notional) (z usdc-debit-bound))
        wrong-way (fn [side] (err :plan/corrective-direction [side :quantity] {:starting-breach mode :side side}))]
    (vec
     (concat
      (when (and (= mode :above-maximum) buys?) [(wrong-way :buy)])
      (when (and (= mode :below-core) sells?) [(wrong-way :sell)])
      (when (and maximum (not= mode :above-maximum) (pos? (cmp all-buy maximum)))
        [(contracts/error :plan/max-inventory-breach
                          {:all-buy-inventory (ds all-buy) :maximum (ds maximum)})])
      (when (and core (not= mode :below-core) (neg? (cmp all-sell core)))
        [(contracts/error :plan/protected-core-breach
                          {:all-sell-inventory (ds all-sell) :protected-core (ds core)})])
      (when (and buys? cap (pos? (cmp max-debit cap)))
        [(contracts/error :plan/usdc-cap-exceeded {:required (ds max-debit) :cap (ds cap)})])
      (when (and buys? available-usdc (pos? (cmp max-debit available-usdc)))
        [(contracts/error :plan/insufficient-usdc {:required (ds max-debit) :available (ds available-usdc)})])
      (when (and sells? available-base
                 (pos? (cmp (precision/add s-new (z base-debit-bound)) available-base)))
        [(contracts/error :plan/insufficient-base
                          {:required (ds (precision/add s-new (z base-debit-bound)))
                           :available (ds available-base)})])))))
