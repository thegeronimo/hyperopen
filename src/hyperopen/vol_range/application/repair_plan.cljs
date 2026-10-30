(ns hyperopen.vol-range.application.repair-plan
  "Pure repair proposal and the repair checks shared by the review and the
  final pre-HTTP guard (HO-SPEC-014 v2.1 sections 6.3-6.6, 8.6, 13.4).

  A repair restores definitively rejected logical members of ONE ladder with
  new CLOIDs. Each repaired leg keeps the member's side and its FULL rejected
  quantity (never more). Its price comes from the DEPLOYED model, never from
  the touch (spec 6.3): the rejected price is kept when it now passes every
  leg check against the fresh book; otherwise the sigma distance from the
  deployed shifted anchor is stepped AWAY from the book in the policy's
  remediation increments, starting at the first step strictly beyond the
  rejected leg's own distance, up to the policy's maximum distance, and the
  first price that passes post-only, self-cross, distinct-price,
  distance-from-mid, legal-price and minimum-notional checks is proposed. A
  member with no such price is reported as not repairable with its reason.

  Inventory bounds (spec 6.6) use the ladder's recorded limits
  (`[:settings :limits]`, base units) with the account's existing open orders
  in the market, which include the ladder's surviving legs, counted once.
  Without recorded limits only the cash and balance checks apply and the
  proposal carries the warning `:repair/limits-unrecorded`.

  Everything here is pure; the effect adapter supplies the awaited reads."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.admission :as admission]
            [hyperopen.vol-range.domain.fees :as fees]
            [hyperopen.vol-range.domain.grid :as grid]))

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str precision/parse-decimal) zero))
(defn- dn [x] (some-> x str precision/parse-decimal))
(defn- ds [x] (precision/decimal->string x))
(defn- norm-price [x] (some-> (dn x) ds))
(defn- sum [xs] (reduce precision/add zero xs))

;; ---------------------------------------------------------------------------
;; Context
;; ---------------------------------------------------------------------------

(defn proposal-limits
  "The ladder's recorded inventory limits {:core :maximum} (base units,
  strings or nil), or nil when the grid recorded no settings."
  [grid]
  (when (map? (:settings grid))
    (select-keys (get-in grid [:settings :limits]) [:core :maximum])))

(defn repair-context
  "What every repair check needs from the CURRENT grid: the candidates keyed
  by logical id, the surviving current legs ({:side :price}) and the recorded
  limits."
  [grid]
  {:candidates (into {} (map (juxt :logical-id identity)) (lifecycle/repair-candidates grid))
   :survivors (->> (lifecycle/current-legs grid)
                   (remove lifecycle/definitively-not-placed?)
                   (mapv #(select-keys % [:side :price])))
   :limits (proposal-limits grid)})

;; ---------------------------------------------------------------------------
;; Leg checks
;; ---------------------------------------------------------------------------

(defn- book [market]
  (let [bid (dn (get-in market [:bbo :bid]))
        ask (dn (get-in market [:bbo :ask]))]
    {:bid bid
     :ask ask
     :mid (when (and bid ask) (precision/divide (precision/add bid ask) (d "2") 12 :half-up))}))

(defn- self-cross? [side p survivors]
  (let [other (if (= :buy side) :sell :buy)
        prices (keep #(when (= other (:side %)) (dn (:price %))) survivors)]
    (if (= :buy side)
      (some #(>= (precision/cmp p %) 0) prices)
      (some #(<= (precision/cmp p %) 0) prices))))

(defn leg-error
  "The first failed check for one repair leg ({:logical-id :side :price
  :size}), or nil. `candidates` (by logical id) are the definitively rejected
  current members; `survivors` ({:side :price}) are every other leg the leg
  must not duplicate or cross; `market` carries the fresh :bbo, :market-type
  and :sz-decimals. The minimum notional is checked last, so a leg failing
  only that check is otherwise safe."
  [{:keys [market candidates survivors]} {:keys [logical-id side price size]}]
  (let [{:keys [bid ask mid]} (book market)
        precision-ctx {:market-type (:market-type market) :sz-decimals (:sz-decimals market)}
        min-notional (d (:min-leg-notional-usdc contracts/product-policy))
        max-distance (d (:max-distance-from-mid-fraction contracts/product-policy))
        p (dn price)
        s (dn size)
        candidate (get candidates logical-id)]
    (cond
      (or (nil? p) (nil? s) (not (precision/pos-dec? s))) :repair/invalid-leg
      (or (nil? candidate) (not= side (:side candidate))) :repair/not-a-candidate
      (pos? (precision/cmp s (d (:original-base candidate)))) :repair/exceeds-rejected-quantity
      (contains? (set (keep (comp norm-price :price) survivors)) (ds p)) :repair/duplicate-price
      (or (nil? mid)
          (pos? (precision/cmp (precision/abs-dec (precision/sub p mid)) (precision/mul mid max-distance))))
      :repair/too-far-from-mid
      (not (precision/legal-price? p precision-ctx)) :repair/illegal-price
      (not (:lots (precision/decimal->lots s (:sz-decimals market)))) :repair/not-lot-multiple
      (and (= :buy side) (or (nil? ask) (>= (precision/cmp p ask) 0))) :repair/crosses-book
      (and (= :sell side) (or (nil? bid) (<= (precision/cmp p bid) 0))) :repair/crosses-book
      (self-cross? side p survivors) :repair/self-cross
      (neg? (precision/cmp (precision/mul p s) min-notional)) :repair/below-min-notional
      :else nil)))

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
        sells (filter #(= :sell (:side %)) legs)]
    {:usdc (ds (sum (map #(if (base-fee? fees :buy) (cost %) (precision/mul (cost %) (precision/add one f))) buys)))
     :base (ds (sum (map #(if (base-fee? fees :sell) (precision/mul (d (:size %)) (precision/add one f)) (d (:size %)))
                         sells)))}))

;; ---------------------------------------------------------------------------
;; Inventory and cash bounds (spec 6.6)
;; ---------------------------------------------------------------------------

(defn- less-pending [balance pending]
  (when balance
    (let [available (precision/dec-max zero (precision/sub (d (:available balance)) (d pending)))]
      (assoc balance :available (ds available)))))

(defn account-with-pending
  "`account` (an ::account-snapshot) with pending reservations {:usdc :base}
  removed from the available balances (spec 3.3: counted until the venue
  hold is coherent)."
  [account {:keys [usdc base]}]
  (cond-> account
    (:quote account) (update :quote less-pending usdc)
    (:base account) (update :base less-pending base)))

(defn account-summary
  "The account facts the bounds need, from an ::account-snapshot (balances
  and the market's open orders, survivors included). nil unless :ready.
  With `pending` reservations {:usdc :base} the available balances are net
  of them, exactly as admission and the final guard judge them, and
  `:venue-available-usdc`/`:venue-available-base` keep the venue's own
  figures, so a shortfall caused only by pending reservations is told apart
  (`:repair/funds-pending`)."
  ([account] (account-summary account nil))
  ([account pending]
   (when (= :ready (:status account))
     (let [available (account-with-pending account pending)
           exposure (fn [side] (ds (sum (keep #(when (= side (:side %)) (dn (:remaining %)))
                                              (:open-orders account)))))]
       (cond-> {:base-total (get-in account [:base :total])
                :available-base (get-in available [:base :available])
                :available-usdc (get-in available [:quote :available])
                :buy-exposure (exposure :buy)
                :sell-exposure (exposure :sell)}
         pending (assoc :venue-available-usdc (get-in account [:quote :available])
                        :venue-available-base (get-in account [:base :available])))))))

(def ^:private bound-codes
  {:plan/max-inventory-breach :repair/max-inventory-breach
   :plan/protected-core-breach :repair/protected-core-breach
   :plan/insufficient-usdc :repair/insufficient-usdc
   :plan/insufficient-base :repair/insufficient-base})

(defn- bound-code [{:keys [code data]}]
  (if (= :plan/corrective-direction code)
    ;; A buy while already above the maximum (or a sell while below the
    ;; core) deepens that breach.
    (if (= :buy (:side data)) :repair/max-inventory-breach :repair/protected-core-breach)
    (get bound-codes code code)))

(defn- with-notional [l]
  {:side (:side l) :size (ds (d (:size l))) :notional (ds (precision/mul (d (:price l)) (d (:size l))))})

(defn- bound-input
  "The `admission/check-bounds` input for repair `legs` against `account`
  (an `account-summary`) and `limits`, or nil without a base total."
  [{:keys [account limits legs fees]}]
  (when-let [q (dn (:base-total account))]
    (let [legs (map with-notional legs)
          buy-legs (filter #(= :buy (:side %)) legs)
          sell-legs (filter #(= :sell (:side %)) legs)
          reserves (fees/fee-reserves {:buy-legs buy-legs :sell-legs sell-legs :capability fees})]
      {:q q :core (dn (:core limits)) :maximum (dn (:maximum limits))
       :b-existing (d (:buy-exposure account)) :s-existing (d (:sell-exposure account))
       :b-new (sum (map (comp d :size) buy-legs)) :s-new (sum (map (comp d :size) sell-legs))
       :base-credit-bound (d (:base-credit-bound reserves))
       :base-debit-bound (d (:base-debit-bound reserves))
       :usdc-debit-bound (d (:usdc-debit-bound reserves))
       :buy-notional (sum (map (comp d :notional) buy-legs))
       :cap nil
       :available-usdc (d (:available-usdc account))
       :available-base (d (:available-base account))})))

(def ^:private funds-codes #{:repair/insufficient-usdc :repair/insufficient-base})

(defn funds-code
  "`code` when `need` exceeds the `venue` available balance too, else
  `:repair/funds-pending`: only pending reservations of earlier operations
  stand in the way, and they are released once the venue holds are read."
  [code need venue]
  (if (and venue (not (pos? (precision/cmp (d need) (d venue))))) :repair/funds-pending code))

(defn bound-errors
  "Spec 6.6 bounds for repair `legs` ({:side :price :size}): keywords
  `:repair/max-inventory-breach`, `:repair/protected-core-breach`,
  `:repair/insufficient-usdc`, `:repair/insufficient-base` (plus
  `:guard/account-unavailable` or `:guard/fees-unavailable` when they cannot
  be judged). `account` is an `account-summary` whose open orders (the
  ladder's survivors included) are the existing exposure, counted once;
  `limits` {:core :maximum} or nil, in which case only cash and balances are
  checked. A funds shortfall that the venue's own available balances
  (`:venue-available-*`) would cover is `:repair/funds-pending` instead."
  [{:keys [fees account] :as args}]
  (if-let [input (bound-input args)]
    (let [codes (map bound-code (admission/check-bounds input))
          venue (when (and (some funds-codes codes) (:venue-available-usdc account))
                  (set (map bound-code
                            (admission/check-bounds
                             (assoc input
                                    :available-usdc (d (:venue-available-usdc account))
                                    :available-base (d (:venue-available-base account)))))))]
      (vec (distinct (concat (when (not= :ok (:status fees)) [:guard/fees-unavailable])
                             (map #(if (and venue (funds-codes %) (not (venue %))) :repair/funds-pending %)
                                  codes)))))
    [:guard/account-unavailable]))

(defn inventory-outcomes
  "Worst-case inventory endpoints ({:all-buys-first :all-sells-first ...},
  base strings, `admission/inventory-outcomes`) for the selected legs of a
  frozen proposal: the same arithmetic `bound-errors` judges. nil without a
  base total."
  [proposal]
  (let [selected (set (:selected proposal))]
    (some-> (bound-input {:account (:account proposal) :limits (:limits proposal) :fees (:fees proposal)
                          :legs (filter #(contains? selected (:logical-id %)) (:legs proposal))})
            admission/inventory-outcomes)))

;; ---------------------------------------------------------------------------
;; Proposal
;; ---------------------------------------------------------------------------

(defn- model-inputs [grid]
  (let [r (dn (get-in grid [:model :shifted-anchor]))
        v (dn (get-in grid [:model :volatility :daily-fraction]))]
    (when (and r v (precision/pos-dec? r) (precision/pos-dec? v))
      {:r r :v v})))

(defn- price-at
  "Legal price `z` sigma away from `r` on `side`'s side of the model, or nil."
  [{:keys [r v]} side z market]
  (let [offset (precision/mul z v)
        raw (precision/mul r (if (= :buy side) (precision/sub (d "1") offset) (precision/add (d "1") offset)))]
    (when (precision/pos-dec? raw)
      (precision/quantize-price raw side {:market-type (:market-type market) :sz-decimals (:sz-decimals market)}))))

(defn- step-prices
  "Prices for each remediation step strictly beyond `rejected`'s distance from
  the model, away from the book, up to the maximum distance."
  [{:keys [r v] :as model} side rejected market]
  (let [step (d (:remediation-step-sigma contracts/product-policy))
        max-z (d (get-in contracts/product-policy [:distance-sigma :max]))
        away (if (= :buy side) (precision/sub r rejected) (precision/sub rejected r))
        first-k (max 1 (inc (js/parseInt (ds (precision/divide away (precision/mul (precision/mul r v) step) 0 :floor)))))
        last-k (js/parseInt (ds (precision/divide max-z step 0 :floor)))]
    (keep (fn [k] (price-at model side (precision/mul (d (str k)) step) market))
          (range first-k (inc last-k)))))

(defn- repair-leg [{:keys [r v]} candidate price repriced?]
  (let [size (ds (d (:original-base candidate)))]
    {:logical-id (:logical-id candidate)
     :side (:side candidate)
     :rung-index (:rung-index candidate)
     :rejected-price (:price candidate)
     :price (ds price)
     :size size
     :notional (ds (precision/mul price (d size)))
     :actual-sigma (grid/actual-sigma {:price price :r r :v v})
     :repriced? repriced?
     :reason (:reason candidate)}))

(defn- unrepairable [candidate code]
  {:logical-id (:logical-id candidate) :side (:side candidate) :rung-index (:rung-index candidate)
   :rejected-price (:price candidate) :size (ds (d (:original-base candidate))) :code code})

(defn- propose-leg
  "{:leg l} or {:unrepairable u} for one candidate, given the prices already
  taken (`survivors` plus earlier repair legs)."
  [ctx model candidate]
  (let [market (:market ctx)
        {:keys [bid ask]} (book market)
        rejected (dn (:price candidate))
        check (fn [p] (leg-error ctx {:logical-id (:logical-id candidate) :side (:side candidate)
                                      :price (ds p) :size (:original-base candidate)}))]
    (cond
      (nil? model) {:unrepairable (unrepairable candidate :repair/model-unavailable)}
      (or (nil? bid) (nil? ask) (nil? rejected)) {:unrepairable (unrepairable candidate :repair/book-unavailable)}
      (nil? (check rejected)) {:leg (repair-leg model candidate rejected false)}
      :else
      (loop [[p & more] (step-prices model (:side candidate) rejected market)
             small? (= :repair/below-min-notional (check rejected))]
        (if (nil? p)
          {:unrepairable (unrepairable candidate (if small? :repair/below-min-notional :repair/no-safe-price))}
          (let [e (check p)]
            (if (nil? e)
              {:leg (repair-leg model candidate p true)}
              (recur more (or small? (= :repair/below-min-notional e))))))))))

(defn propose
  "Repair proposal for every definitively rejected current member of `grid`
  against the fresh `market` (planner market: :bbo, :market-type,
  :sz-decimals), `account` (::account-snapshot from a barrier read), the
  account's `pending` reservations {:usdc :base} (judged like admission;
  nil for none) and `fees`. Returns the frozen proposal without its id and status:
  {:legs [...] :unrepairable [...] :selected #{logical-id ...} :bbo
  :account (account-summary) :limits (nil without recorded settings) :fees
  :proposed-at-ms}. Every repairable leg is selected by default
  (`keep-exclusions` then applies what the user left out before a refresh)."
  [{:keys [grid market account pending fees now-ms]}]
  (let [{:keys [candidates survivors limits]} (repair-context grid)
        model (model-inputs grid)
        ordered (->> (vals candidates) (sort-by (juxt (comp name :side) :rung-index)))
        result (reduce (fn [acc candidate]
                         (let [ctx {:market market :candidates candidates :survivors (:taken acc)}
                               {:keys [leg] :as r} (propose-leg ctx model candidate)]
                           (if leg
                             (-> acc
                                 (update :legs conj leg)
                                 (update :taken conj (select-keys leg [:side :price])))
                             (update acc :unrepairable conj (:unrepairable r)))))
                       {:legs [] :unrepairable [] :taken (vec survivors)}
                       ordered)]
    {:legs (:legs result)
     :unrepairable (:unrepairable result)
     :selected (set (map :logical-id (:legs result)))
     :bbo (select-keys (:bbo market) [:bid :ask :observed-at-ms])
     :account (account-summary account pending)
     :limits limits
     :fees fees
     :proposed-at-ms now-ms}))

(defn selected-legs
  "The legs the repair sends: [{:logical-id :price :size}] for the selected
  repairable legs, in proposal order. Exactly what the frozen proposal
  shows; nothing is re-priced."
  [proposal]
  (let [selected (set (:selected proposal))]
    (->> (:legs proposal)
         (filter #(contains? selected (:logical-id %)))
         (mapv #(select-keys % [:logical-id :price :size])))))

(defn excluded-ids
  "Logical ids the user left out of `proposal`: its unselected legs plus the
  exclusions it carried from an earlier review of the ladder, minus anything
  selected now."
  [{:keys [legs selected excluded]}]
  (let [selected (set selected)]
    (into (set (remove selected (map :logical-id legs))) (remove selected excluded))))

(defn keep-exclusions
  "`proposal` with the `excluded` logical ids unselected and remembered, so
  a refreshed proposal never re-selects an order the user left out."
  [proposal excluded]
  (if (seq excluded)
    (let [excluded (set excluded)]
      (-> proposal
          (update :selected #(set (remove excluded %)))
          (assoc :excluded excluded)))
    proposal))

(defn proposal-status
  "{:status :ready|:invalid :errors [kw ...] :warnings [kw ...]} for the
  selected legs of a frozen proposal (its :status must be :ready). Warning
  `:repair/limits-unrecorded` when the ladder recorded no limits."
  [proposal]
  (let [selected (set (:selected proposal))
        legs (filter #(contains? selected (:logical-id %)) (:legs proposal))
        errors (if (seq legs)
                 (bound-errors {:account (:account proposal) :limits (:limits proposal)
                                :legs legs :fees (:fees proposal)})
                 [])]
    {:status (if (and (= :ready (:status proposal)) (empty? errors)) :ready :invalid)
     :errors errors
     :warnings (if (nil? (:limits proposal)) [:repair/limits-unrecorded] [])}))
