(ns hyperopen.vol-range.application.plan-core
  "Pure Vol Range planning core (HO-SPEC-014 v2.1 section 6.7). Computes the
  ::plan-result WITHOUT remediations; `planner/prepare-plan` adds those so that
  `remediations` can re-run this core without a namespace cycle. All inputs are
  injected snapshots; nothing here reads state, time or randomness and nothing
  here can build an exchange action."
  (:require [clojure.walk :as walk]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.admission :as admission]
            [hyperopen.vol-range.domain.fees :as fees]
            [hyperopen.vol-range.domain.grid :as grid]
            [hyperopen.vol-range.domain.quote-sizing :as quote-sizing]))

(defn- d [s] (precision/parse-decimal s))
(defn- ds [x] (precision/decimal->string x))
(def ^:private zero (d "0"))
(defn- sum-by [legs k] (reduce precision/add zero (map #(d (get % k)) legs)))
(defn- dedupe-errors [errors] (vec (distinct errors)))
(defn- merge-snapshot-errors [findings snapshot]
  (if (and snapshot (not= :ready (:status snapshot)))
    (into findings (:errors snapshot))
    findings))

(defn- balance [account k] (some-> (get-in account [k :available]) d))

;; ---------------------------------------------------------------------------
;; Capability
;; ---------------------------------------------------------------------------

(defn- capability-findings
  "Spec 3.1 / 15.2: the RAW account abstraction must be explicitly allowlisted.
  A missing raw value (not loaded yet) is unavailable; any other value,
  including future ones, is unsupported."
  [caps market account]
  (let [market-type (:market-type market)
        supported? (and (contains? (:market-types caps) market-type)
                        (contains? (:quote-symbols caps) (:quote-symbol market)))
        has-account? (and account (some? (:account-address account)))
        raw-mode (:raw-account-mode account)
        sz (:sz-decimals market)]
    {:errors
     (cond-> []
       (empty? market) (conj (contracts/error :data/market-metadata-unavailable {}))
       (and (seq market) (not supported?)) (conj (contracts/error :capability/market-unsupported {}))
       (and supported? (nil? sz)) (conj (contracts/error :data/market-metadata-unavailable {}))
       (false? (:preview? caps)) (conj (contracts/error :capability/preview-disabled {}))
       (and has-account? (nil? raw-mode))
       (conj (contracts/error :data/account-mode-unavailable {}))
       (and has-account? raw-mode (not (contains? (:raw-account-modes caps) raw-mode)))
       (conj (contracts/error :capability/account-mode-unsupported {:mode raw-mode}))
       (not has-account?)
       (conj (contracts/error :capability/account-required {}))
       (or (nil? (:base account)) (nil? (:quote account)))
       (conj (contracts/error :data/balances-unavailable {})))
     :warnings []}))

;; ---------------------------------------------------------------------------
;; Volatility and anchor
;; ---------------------------------------------------------------------------

(defn- resolve-volatility [resolved market]
  (if (= :manual (:vol-mode resolved))
    (if-let [f (:manual-daily-fraction resolved)]
      {:v (d f) :source :manual}
      {})
    (let [av (:auto-volatility market)
          f (when (= :ok (:status av)) (some-> (:daily-fraction av) d))]
      (cond
        (nil? f) {:errors [(if (= :stale (:reason av))
                            (contracts/error :data/volatility-stale {:reason :stale
                                                                     :stale-reason (:stale-reason av)})
                            (contracts/error :data/volatility-unavailable {:reason (:reason av)}))]}
        (or (:zero? av) (precision/zero-dec? f)) {:errors [(contracts/error :data/volatility-zero {})]}
        :else {:v f :source :auto :window (:window av) :estimator-version (:estimator-version av)}))))

(defn- bbo-values [bbo]
  (let [bid (some-> (:bid bbo) d) ask (some-> (:ask bbo) d)]
    (when (and bid ask (precision/pos-dec? bid) (precision/pos-dec? ask))
      {:bid bid :ask ask
       :mid (precision/mul (precision/add bid ask) (d "0.5"))})))

(defn- resolve-anchor [resolved market bbo]
  (let [mode (:anchor-mode resolved)
        positive (fn [p] (when (and p (precision/pos-dec? p)) p))]
    (case mode
      :custom (when-let [p (:custom-price resolved)] {:price p :observed-at-ms nil})
      :market-price (when-let [p (:mid bbo)]
                      {:price p :observed-at-ms (get-in market [:bbo :observed-at-ms])})
      (:vwap-24h :utc-open)
      (let [a (get-in market [:anchors mode])]
        (when-let [p (positive (some-> (:price a) d))]
          {:price p :observed-at-ms (:observed-at-ms a)}))
      nil)))

;; ---------------------------------------------------------------------------
;; Inventory
;; ---------------------------------------------------------------------------

(defn- existing-exposure [open-orders]
  (reduce (fn [acc {:keys [side remaining]}]
            (let [k (cond (contains? #{:buy "buy" "B" :B} side) :buy
                          (contains? #{:sell "sell" "A" :A} side) :sell)
                  r (d remaining)]
              (if (and k r) (update acc k precision/add r) acc)))
          {:buy zero :sell zero}
          open-orders))

(defn- base-sell-fee-fraction
  "Positive maker fraction when sell fees settle in the base token; nil otherwise."
  [fees]
  (when (and (= :ok (:status fees)) (= :base (:sell-fee-token fees)))
    (let [f (some-> (:maker-fraction fees) d)]
      (when (and f (precision/pos-dec? f)) f))))

(defn- sell-fee-bound [x f]
  (precision/quantize (precision/mul x f) (:fee-reserve-scale contracts/product-policy) :ceil))

(defn- sellable-excess
  "Largest lot multiple `x` with x + base fee bound(x) inside both the room above
  protected core (after existing sells) and the available base (spec 4.2)."
  [{:keys [q s-existing core available-base sz fee-f]}]
  (when (and q available-base core sz)
    (let [room (precision/dec-max zero (precision/dec-min available-base
                                                          (precision/sub (precision/sub q s-existing) core)))]
      (if-not fee-f
        (precision/quantize room sz :floor)
        (let [lot (precision/lots->decimal 1 sz)
              fits? (fn [x] (<= (precision/cmp (precision/add x (sell-fee-bound x fee-f)) room) 0))
              guess (precision/quantize (precision/divide room (precision/add (d "1") fee-f) 20 :floor)
                                        sz :floor)]
          (loop [x guess]
            (cond (not (pos? (precision/cmp x zero))) zero
                  (fits? x) x
                  :else (recur (precision/sub x lot)))))))))

;; ---------------------------------------------------------------------------
;; Totals
;; ---------------------------------------------------------------------------

(defn- vwap [gross qty]
  (when (and gross qty (precision/pos-dec? qty))
    (precision/divide gross qty 8 :half-up)))

(defn- side-totals [side enabled? legs reserve usdc-bound base-debit cap available-usdc]
  (let [qty (sum-by legs :size)
        gross (sum-by legs :notional)
        vw (vwap gross qty)
        base {:enabled? (boolean enabled?) :quantity (ds qty) :gross (ds gross)
              :vwap (some-> vw ds) :fee-reserve reserve :rung-count (count legs)}]
    (if (= side :buy)
      (let [max-debit (precision/add gross usdc-bound)]
        (assoc base :max-debit (ds max-debit)
               :cap (some-> cap ds)
               :cap-headroom (some-> cap (precision/sub max-debit) ds)
               :available-headroom (some-> available-usdc (precision/sub max-debit) ds)))
      (let [fee-quote (if (= :quote (:token reserve)) (d (:amount reserve)) zero)]
        (assoc base :base-committed (ds (precision/add qty base-debit))
               :net-proceeds (ds (precision/sub gross fee-quote)))))))

(defn- quoted-gap [buy-t sell-t]
  (when (and (pos? (:rung-count buy-t)) (pos? (:rung-count sell-t)))
    (let [bv (d (:vwap buy-t)) sv (d (:vwap sell-t))
          per-unit (precision/sub sv bv)]
      {:per-unit (ds per-unit)
       :pct-of-buy-vwap (ds (precision/divide (precision/mul per-unit (d "100")) bv 4 :half-up))})))

;; ---------------------------------------------------------------------------
;; Fingerprint
;; ---------------------------------------------------------------------------

(defn- by-pr [a b] (compare (pr-str a) (pr-str b)))

(defn canonical [x]
  (walk/postwalk
   (fn [v]
     (cond (map? v) (into (sorted-map-by by-pr) v)
           (set? v) (vec (sort by-pr v))
           :else v))
   x))

(def provenance-keys
  "Observation and load times: provenance that travels beside the plan inputs.
  They never change the plan's numbers, so they stay out of the fingerprint and
  every memo key (a new receipt time alone must not re-plan)."
  #{:observed-at-ms :loaded-at-ms})

(defn without-provenance [x]
  (walk/postwalk (fn [v] (if (map? v) (apply dissoc v provenance-keys) v)) x))

(defn fingerprint [input]
  (str "vr1-" (.toString (hash (pr-str (canonical (without-provenance
                                                   (dissoc input :plan-id :clock :remediations?))))) 16)))

;; ---------------------------------------------------------------------------
;; Planner core
;; ---------------------------------------------------------------------------

(defn- side-legs
  "Legs for one side. In USDC mode the base quantity is first solved from the
  amount (`quote-sizing`); the solved quantity is reported as :solved-quantity."
  [side sd {:keys [r v sz market-type fees]}]
  (when (and (:enabled? sd) r v sz (not (neg? sz))
             (some? (if (= :quote (:amount-unit sd)) (:quote-amount sd) (:quantity sd)))
             (every? some? [(:count sd) (:start sd) (:end sd) (:skew sd)])
             (<= (precision/cmp (:start sd) (:end sd)) 0))
    (let [params {:side side :r r :v v :skew (:skew sd) :sz-decimals sz :count (:count sd)
                  :z-start (:start sd) :z-end (:end sd) :market-type market-type}
          solved (when (= :quote (:amount-unit sd))
                   ;; Without a fee quote the plan is not admissible anyway;
                   ;; size it without a reserve so the preview still renders.
                   (quote-sizing/solve-quantity (assoc params :amount (:quote-amount sd)
                                                       :fee-capability fees)))]
      (cond
        (:errors solved) {:legs [] :errors (:errors solved)}
        solved (assoc (grid/build-side-legs (assoc params :quantity (:quantity solved)))
                      :solved-quantity (:quantity solved))
        :else (grid/build-side-legs (assoc params :quantity (:quantity sd)))))))

(defn prepare-plan-core
  "Plan without remediations. See `planner/prepare-plan` for the contract."
  [{:keys [plan-id scope draft market account fees capabilities model-version precision-version] :as input}]
  (let [caps (merge contracts/capability-matrix {:preview? true} capabilities)
        market (or market {})
        {:keys [resolved errors]} (admission/validate-inputs draft contracts/product-policy)
        cap-f (capability-findings caps market account)
        account-ok? (and account (:base account) (:quote account) (some? (:account-address account)))
        sz (:sz-decimals market)
        bbo (bbo-values (:bbo market))
        {:keys [v source window estimator-version] vol-errors :errors} (resolve-volatility resolved market)
        anchor (resolve-anchor resolved market bbo)
        anchor-error (when (and (nil? anchor) (not= :custom (:anchor-mode resolved)))
                       [(contracts/error :data/anchor-unavailable {:mode (:anchor-mode resolved)})])
        q (when account-ok? (some-> (get-in account [:base :total]) d))
        core (or (:core resolved) q)
        target (or (:target resolved) q)
        maximum (:maximum resolved)
        limit-errors (when (and q (or (nil? (:core resolved)) (nil? (:target resolved))))
                       (admission/check-inventory-limits {:core core :target target :maximum maximum}))
        tilt? (:tilt-enabled? resolved)
        ;; Spec 5.4 needs a real q_max: never invent one for tilt.
        ;; Skipped when the maximum is already reported missing on the same field.
        tilt-errors (when (and tilt? (nil? maximum)
                               (not-any? #(= :input/maximum-required (:code %)) errors))
                      [(contracts/error :input/maximum-required-for-tilt
                                        {:path [:inventory :maximum]})])
        kappa (when tilt? (or (:tilt-strength resolved) (d (get-in contracts/product-policy [:tilt-strength :default]))))
        delta (when (and tilt? anchor v q maximum)
                (grid/inventory-delta {:q q :core (or core zero) :target (or target zero)
                                       :maximum maximum}))
        shifted (when (and anchor v (or (not tilt?) delta))
                  (grid/shifted-anchor {:anchor (:price anchor) :v v :kappa kappa :delta delta
                                        :tilt-enabled? tilt?}))
        r (:price shifted)
        geo {:r r :v v :sz sz :market-type (:market-type market) :fees fees}
        capable? (empty? (filter #(contains? #{:capability/market-unsupported :capability/preview-disabled
                                              :capability/account-mode-unsupported
                                              :data/market-metadata-unavailable}
                                            (:code %))
                                 (:errors cap-f)))
        buy-r (when capable? (side-legs :buy (:buy resolved) geo))
        sell-r (when capable? (side-legs :sell (:sell resolved) geo))
        buy-legs (:legs buy-r [])
        sell-legs (:legs sell-r [])
        fee-ok? (= :ok (:status fees))
        reserves (fees/fee-reserves {:buy-legs buy-legs :sell-legs sell-legs :capability fees})
        exposure (existing-exposure (:open-orders account))
        buy-total (sum-by buy-legs :size)
        sell-total (sum-by sell-legs :size)
        usdc-bound (d (:usdc-debit-bound reserves))
        base-debit (d (:base-debit-bound reserves))
        base-credit (d (:base-credit-bound reserves))
        avail-usdc (balance account :quote)
        avail-base (balance account :base)
        buy-cap (get-in resolved [:buy :max-usdc-debit])
        leg-errors (when capable?
                     (admission/check-legs {:buy-legs buy-legs :sell-legs sell-legs :bbo (:bbo market)
                                            :mid (:mid bbo) :policy contracts/product-policy}))
        bound-errors (when (and capable? q)
                       (admission/check-bounds
                        {:q q :core core :maximum maximum :b-existing (:buy exposure)
                         :s-existing (:sell exposure) :b-new buy-total :s-new sell-total
                         :base-credit-bound base-credit :base-debit-bound base-debit
                         :usdc-debit-bound usdc-bound :buy-notional (sum-by buy-legs :notional)
                         :cap buy-cap :available-usdc avail-usdc :available-base avail-base}))
        excess (sellable-excess {:q q :s-existing (:sell exposure) :core core :available-base avail-base
                                 :sz sz :fee-f (base-sell-fee-fraction fees)})
        breach-warnings (when (and capable? q)
                          (admission/existing-commitment-warnings
                           {:q q :core core :maximum maximum :b-existing (:buy exposure)
                            :s-existing (:sell exposure)}))
        sell-enabled? (get-in resolved [:sell :enabled?])
        buy-enabled? (get-in resolved [:buy :enabled?])
        all-errors (dedupe-errors
                    (-> []
                        (into errors)
                        (into (:errors cap-f))
                        (merge-snapshot-errors market)
                        (merge-snapshot-errors account)
                        (into (when capable? vol-errors))
                        (into (when (and capable? v) anchor-error))
                        (into (when (and capable? v anchor) (:errors buy-r)))
                        (into (when (and capable? v anchor) (:errors sell-r)))
                        (into (when (and capable? (not fee-ok?)) [(contracts/error :data/fee-quote-unavailable {})]))
                        (into (remove (fn [e] (some #(= (:code e) (:code %)) errors)) limit-errors))
                        (into tilt-errors)
                        (into leg-errors)
                        (into bound-errors)))
        ;; In USDC mode the side's field is the amount: point quantity
        ;; errors there so they stay attached to a rendered input.
        all-errors (mapv (fn [e]
                           (let [[side k] (get-in e [:data :path])]
                             (if (and (= :quantity k) (= :quote (get-in resolved [side :amount-unit])))
                               (assoc-in e [:data :path] [side :quote-amount])
                               e)))
                         all-errors)
        warnings (dedupe-errors
                  (cond-> (into (vec (:warnings cap-f)) breach-warnings)
                    (and fee-ok? (not (:settlement-verified? fees)))
                    (conj (contracts/warning :capability/fee-settlement-unverified {}))
                    (and sell-enabled? account-ok? excess (precision/zero-dec? excess))
                    (conj (contracts/warning :hint/nothing-to-sell
                                             {:message "Nothing to sell above protected core. Lower protected core to distribute inventory."
                                              :path [:inventory :protected-core]}))))
        entered (fn [t side]
                  (let [sd (get resolved side)]
                    (cond-> (assoc t :amount-unit (if (= :quote (:amount-unit sd)) :quote :base))
                      (= :quote (:amount-unit sd)) (assoc :quote-amount (some-> (:quote-amount sd) ds)))))
        buy-t (entered (side-totals :buy buy-enabled? buy-legs (:buy reserves) usdc-bound base-debit buy-cap avail-usdc)
                       :buy)
        sell-t (entered (side-totals :sell sell-enabled? sell-legs (:sell reserves) usdc-bound base-debit nil nil)
                        :sell)
        legs (into buy-legs sell-legs)]
    {:status (contracts/status-for-errors all-errors)
     :plan-id plan-id
     :scope scope
     :input-fingerprint (fingerprint (dissoc input :plan-id))
     :model {:anchor (when anchor {:mode (:anchor-mode resolved) :price (ds (:price anchor))
                                   :observed-at-ms (:observed-at-ms anchor)})
             :volatility (when v {:daily-fraction (ds v) :source source :window window
                                  :estimator-version estimator-version})
             :tilt {:enabled? (boolean tilt?)
                    :strength (some-> kappa ds)
                    :delta (some-> delta ds)
                    :shift-sigma (when (and tilt? delta) (grid/shift-sigma kappa delta))}
             :shifted-anchor (some-> r ds)
             :bands (when (and anchor v) (grid/bands {:anchor (:price anchor) :v v}))
             :excursion-sigma (when (and anchor v bbo)
                                (grid/excursion-sigma {:mid (:mid bbo) :anchor (:price anchor) :v v}))}
     :legs legs
     :totals {:buy buy-t :sell sell-t
              :leg-count (count legs)
              :sellable-excess (some-> excess ds)
              :quoted-vwap-gap (quoted-gap buy-t sell-t)}
     :inventory-outcomes (when q
                           (admission/inventory-outcomes
                            {:q q :b-existing (:buy exposure) :s-existing (:sell exposure)
                             :b-new buy-total :s-new sell-total :base-credit-bound base-credit
                             :base-debit-bound base-debit :core core :target target :maximum maximum}))
     :fee-reserves reserves
     :source-versions {:model-version (or model-version contracts/model-version)
                       :precision-version (or precision-version contracts/precision-version)
                       :estimator-version (or estimator-version contracts/estimator-version)
                       :draft-schema-version contracts/draft-schema-version
                       :bbo-observed-at-ms (get-in market [:bbo :observed-at-ms])
                       :anchor-observed-at-ms (:observed-at-ms anchor)
                       :fee-loaded-at-ms (:loaded-at-ms fees)
                       :volatility-window window}
     :remediations []
     :errors all-errors
     :warnings warnings}))
