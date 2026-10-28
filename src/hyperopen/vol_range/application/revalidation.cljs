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
            [hyperopen.vol-range.application.repair-plan :as repair-plan]
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

(defn repair-reservations
  "Funds a repair commits (see `repair-plan/repair-reservations`)."
  [legs fees]
  (repair-plan/repair-reservations legs fees))

(defn revalidate-repair
  "{:ok? bool :errors [kw ...] :reservations {:usdc :base}} for repair legs
  ({:logical-id :side :price :size}) of a grid in `grid-scope` (spec 6.6,
  8.6, 13.4). Each leg repairs one rejected member (`candidates`, keyed by
  logical id) with AT MOST its rejected quantity, at a price no surviving or
  other repair leg (`survivors`, {:side :price}) already uses or crosses and
  within the product distance from mid. Every leg must rest post-only on the
  fresh book, use a legal price and an exact lot size, meet the minimum
  notional, and fit the available balances after other operations' pending
  reservations. Fees must be the user's quote. Inventory bounds use the
  ladder's recorded `limits` ({:core :maximum}, nil when none were recorded)
  through `repair-plan/bound-errors`, so the review and this final guard run
  the same check. Leg checks are `repair-plan/leg-error`."
  [{:keys [grid-scope input account pending legs candidates survivors limits now-ms]}]
  (let [policy contracts/freshness-policy
        scope (:scope input)
        market (:market input)
        fees (:fees input)
        leg-error (fn [leg]
                    (repair-plan/leg-error
                     {:market market :candidates candidates
                      :survivors (concat survivors
                                         (keep #(when (not= (:logical-id %) (:logical-id leg))
                                                  (select-keys % [:side :price]))
                                               legs))}
                     leg))
        repair-prices (keep (comp norm-price :price) legs)
        reservations (repair-reservations legs fees)
        available (account-with-pending account pending)
        ready? (= :ready (:status account))
        errors (vec (distinct
                     (concat
                      (when (not= (select-keys grid-scope identity-keys) (select-keys scope identity-keys))
                        [:guard/scope-changed])
                      (when-not ready? [:guard/account-unavailable])
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
                      (when (and ready?
                                 (pos? (precision/cmp (d (:usdc reservations)) (d (get-in available [:quote :available])))))
                        [:repair/insufficient-usdc])
                      (when (and ready?
                                 (pos? (precision/cmp (d (:base reservations)) (d (get-in available [:base :available])))))
                        [:repair/insufficient-base])
                      (when (and ready? (seq legs))
                        (repair-plan/bound-errors {:account (repair-plan/account-summary available)
                                                   :limits limits :legs legs :fees fees})))))]
    {:ok? (empty? errors) :errors errors :reservations reservations}))
