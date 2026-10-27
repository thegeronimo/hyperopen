(ns hyperopen.vol-range.application.revalidation
  "Pure revalidation of a FIXED reviewed order vector (HO-SPEC-014 v2.1
  sections 3.3, 7.3, 8.1 steps 3 and 8; VR-06, VR-09).

  The reviewed vector is never amended. Revalidation re-runs the single
  planner against fresh inputs, with other operations' pending reservations
  taken out of the available balances, and requires the SAME legs back with a
  :ready status. Any difference (a fill, a balance change, a moved book, a
  precision or identity change) fails and sends the user back to review.
  Source ages are checked against the spec 7.3 policy."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.contracts :as contracts]))

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str precision/parse-decimal) zero))
(defn- ds [x] (precision/decimal->string x))

(def identity-keys
  "Scope fields an operation can never be retargeted across (VR-18)."
  [:network :owner-address :account-address :vault-address :market-type :dex :spot-pair-index :asset-id :coin
   :base-token-id :quote-token-id :sz-decimals])

(defn- less-pending [balance pending]
  (when balance
    (let [available (precision/dec-max zero (precision/sub (d (:available balance)) (d pending)))]
      (assoc balance :available (ds available)))))

(defn account-with-pending
  "`account` with OTHER operations' pending reservations removed from the
  available balances (spec 3.3: counted until the venue hold is coherent)."
  [account {:keys [usdc base]}]
  (cond-> account
    (:quote account) (update :quote less-pending usdc)
    (:base account) (update :base less-pending base)))

(defn fixed-vector [legs]
  (mapv #(select-keys % [:leg-id :side :rung-index :price :size]) legs))

(defn- age-error [now-ms observed-at limit code]
  (when (or (not (number? observed-at)) (> (- now-ms observed-at) limit))
    code))

(defn revalidate
  "{:ok? bool :errors [kw ...] :plan fresh-plan}.

  Keys: :frozen-plan and :frozen-scope from review; :input, the planner input
  built from live state at this moment (draft, scope, market, fees,
  capabilities); :account, an ::account-snapshot from an awaited barrier read;
  :pending, other operations' reservations {:usdc :base}; :now-ms."
  [{:keys [frozen-plan frozen-scope input account pending now-ms]}]
  (let [policy contracts/freshness-policy
        scope (:scope input)
        scope-errors (when (not= (select-keys frozen-scope identity-keys) (select-keys scope identity-keys))
                       [:guard/scope-changed])
        age-errors (keep identity
                         [(age-error now-ms (get-in input [:market :bbo :observed-at-ms]) (:bbo-ms policy)
                                     :guard/book-stale)
                          (age-error now-ms (get-in input [:fees :loaded-at-ms]) (:fee-ms policy)
                                     :guard/fees-stale)
                          (age-error now-ms (:observed-at-ms account) (:account-ms policy)
                                     :guard/account-stale)])
        account-errors (when (not= :ready (:status account)) [:guard/account-unavailable])
        fresh (when (and (empty? scope-errors) (empty? account-errors))
                (planner/prepare-plan (assoc input
                                             :plan-id (:plan-id frozen-plan)
                                             :account (account-with-pending account pending)
                                             :clock now-ms
                                             :remediations? false)))
        plan-errors (cond
                      (nil? fresh) []
                      (not= :ready (:status fresh)) [:guard/plan-not-admissible]
                      (not= (fixed-vector (:legs frozen-plan)) (fixed-vector (:legs fresh))) [:guard/plan-changed]
                      :else [])
        errors (vec (concat scope-errors account-errors age-errors plan-errors))]
    {:ok? (and (= :ready (:status frozen-plan)) (empty? errors))
     :errors (if (= :ready (:status frozen-plan)) errors (into [:guard/plan-not-ready] errors))
     :plan fresh}))

(defn- norm-price [x] (some-> x str precision/parse-decimal precision/decimal->string))

(defn- base-fee? [fees side]
  (= :base (get fees (if (= :buy side) :buy-fee-token :sell-fee-token))))

(defn repair-reservations
  "Funds a repair commits: buy notional plus the maker fee bound in USDC, sell
  size plus the base fee bound (spec 6.5). Decimal strings."
  [legs fees]
  (let [f (if (= :ok (:status fees)) (d (:maker-fraction fees)) zero)
        one (d "1")
        cost (fn [l] (precision/mul (d (:price l)) (d (:size l))))
        buys (filter #(= :buy (:side %)) legs)
        sells (filter #(= :sell (:side %)) legs)
        sum (fn [xs] (reduce precision/add zero xs))]
    {:usdc (ds (sum (map #(if (base-fee? fees :buy) (cost %) (precision/mul (cost %) (precision/add one f))) buys)))
     :base (ds (sum (map #(if (base-fee? fees :sell) (precision/mul (d (:size %)) (precision/add one f)) (d (:size %)))
                         sells)))}))

(defn revalidate-repair
  "{:ok? bool :errors [kw ...] :reservations {:usdc :base}} for repair legs
  ({:logical-id :side :price :size}) of a grid in `grid-scope` (spec 8.6,
  13.4). Each leg repairs one rejected member (`candidates`, keyed by logical
  id) with AT MOST its rejected quantity, at a price no surviving leg
  (`survivor-prices`) already uses and within the product distance from mid.
  Every leg must rest post-only on the fresh book, use a legal price and an
  exact lot size, meet the minimum notional, and fit the available balances
  after other operations' pending reservations. Fees must be the user's
  quote. Inventory bounds (spec 6.6) are re-checked by the repair review that
  produces these legs (stage 6), not here."
  [{:keys [grid-scope input account pending legs candidates survivor-prices now-ms]}]
  (let [policy contracts/freshness-policy
        scope (:scope input)
        market (:market input)
        fees (:fees input)
        bid (some-> (get-in market [:bbo :bid]) d)
        ask (some-> (get-in market [:bbo :ask]) d)
        precision-ctx {:market-type (:market-type market) :sz-decimals (:sz-decimals market)}
        min-notional (d (:min-leg-notional-usdc contracts/product-policy))
        max-distance (d (:max-distance-from-mid-fraction contracts/product-policy))
        mid (when (and bid ask) (precision/divide (precision/add bid ask) (d "2") 12 :half-up))
        leg-error (fn [{:keys [logical-id side price size]}]
                    (let [p (precision/parse-decimal (str price))
                          s (precision/parse-decimal (str size))
                          candidate (get candidates logical-id)]
                      (cond
                        (or (nil? p) (nil? s) (not (precision/pos-dec? s))) :repair/invalid-leg
                        (or (nil? candidate) (not= side (:side candidate))) :repair/not-a-candidate
                        (pos? (precision/cmp s (d (:original-base candidate)))) :repair/exceeds-rejected-quantity
                        (contains? (set (keep norm-price survivor-prices)) (precision/decimal->string p))
                        :repair/duplicate-price
                        (or (nil? mid)
                            (pos? (precision/cmp (precision/abs-dec (precision/sub p mid))
                                                 (precision/mul mid max-distance))))
                        :repair/too-far-from-mid
                        (not (precision/legal-price? p precision-ctx)) :repair/illegal-price
                        (not (:lots (precision/decimal->lots s (:sz-decimals market)))) :repair/not-lot-multiple
                        (neg? (precision/cmp (precision/mul p s) min-notional)) :repair/below-min-notional
                        (and (= :buy side) (or (nil? ask) (>= (precision/cmp p ask) 0))) :repair/crosses-book
                        (and (= :sell side) (or (nil? bid) (<= (precision/cmp p bid) 0))) :repair/crosses-book
                        :else nil)))
        repair-prices (keep (comp norm-price :price) legs)
        reservations (repair-reservations legs fees)
        available (account-with-pending account pending)
        errors (vec (distinct
                     (concat
                      (when (not= (select-keys grid-scope identity-keys) (select-keys scope identity-keys))
                        [:guard/scope-changed])
                      (when (not= :ready (:status account)) [:guard/account-unavailable])
                      (when (not= :ok (:status fees)) [:guard/fees-unavailable])
                      (keep identity
                            [(age-error now-ms (get-in market [:bbo :observed-at-ms]) (:bbo-ms policy) :guard/book-stale)
                             (age-error now-ms (:loaded-at-ms fees) (:fee-ms policy) :guard/fees-stale)
                             (age-error now-ms (:observed-at-ms account) (:account-ms policy) :guard/account-stale)])
                      (when (empty? legs) [:repair/empty])
                      (when (and (seq legs) (not (apply distinct? (map :logical-id legs))))
                        [:repair/duplicate-member])
                      (when (and (seq repair-prices) (not (apply distinct? repair-prices)))
                        [:repair/duplicate-price])
                      (keep leg-error legs)
                      (when (and (= :ready (:status account))
                                 (pos? (precision/cmp (d (:usdc reservations)) (d (get-in available [:quote :available])))))
                        [:repair/insufficient-usdc])
                      (when (and (= :ready (:status account))
                                 (pos? (precision/cmp (d (:base reservations)) (d (get-in available [:base :available])))))
                        [:repair/insufficient-base]))))]
    {:ok? (empty? errors) :errors errors :reservations reservations}))
