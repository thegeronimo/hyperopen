(ns hyperopen.portfolio.optimizer.application.current-portfolio
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.portfolio.optimizer.application.spot-token-labels :as spot-token-labels]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def ^:private non-blank-text coercion/non-blank-text)

(def ^:private parse-number coercion/parse-float-number)

(defn- abs-number
  [value]
  (js/Math.abs value))

(def ^:private positive-number? coercion/positive-number?)

(def ^:private finite-number? coercion/finite-number?)

(def ^:private exposure-band-width
  0.05)

(def ^:private constraint-rounding-scale
  100)

(def ^:private rounding-epsilon
  0.000000001)

(defn- zeroish?
  [value]
  (or (nil? value)
      (zero? value)))

(defn- ceil-to-constraint-precision
  [value]
  (/ (js/Math.ceil (- (* value constraint-rounding-scale)
                      rounding-epsilon))
     constraint-rounding-scale))

(defn- floor-to-constraint-precision
  [value]
  (/ (js/Math.floor (+ (* value constraint-rounding-scale)
                       rounding-epsilon))
     constraint-rounding-scale))

(defn- current-weight-for-cap
  [nav-usdc exposure]
  (or (let [weight (parse-number (:weight exposure))]
        (when (finite-number? weight)
          (abs-number weight)))
      (let [notional (or (parse-number (:abs-notional-usdc exposure))
                         (some-> (:signed-notional-usdc exposure)
                                 parse-number
                                 abs-number))]
        (when (and (positive-number? nav-usdc)
                   (finite-number? notional))
          (/ notional nav-usdc)))))

(defn- max-current-asset-weight
  [nav-usdc exposures]
  (reduce max
          0
          (keep (partial current-weight-for-cap nav-usdc)
                exposures)))

(defn- existing-max-asset-weight
  [constraints]
  (let [value (parse-number (:max-asset-weight constraints))]
    (when (and (finite-number? value)
               (not (neg? value)))
      value)))

(defn- market-mark-price
  [market]
  (some (fn [k]
          (let [value (parse-number (get market k))]
            (when (positive-number? value)
              value)))
        [:mark :markRaw :markPx :midPx :oraclePx]))

(defn- normalize-coin
  [coin]
  (non-blank-text coin))

(defn- normalize-dex
  [dex]
  (non-blank-text dex))

(defn- margin-summary
  [clearinghouse-state]
  (or (:marginSummary clearinghouse-state)
      (:crossMarginSummary clearinghouse-state)
      (get-in clearinghouse-state [:clearinghouseState :marginSummary])
      (get-in clearinghouse-state [:clearinghouseState :crossMarginSummary])))

(defn- clearinghouse-state
  [state]
  (or (get-in state [:webdata2 :clearinghouseState])
      (get-in state [:webdata2 :clearinghouseState :clearinghouseState])))

(defn- perp-account-value-usdc
  [state]
  (some-> state
          clearinghouse-state
          margin-summary
          :accountValue
          parse-number))

(defn- total-margin-used-usdc
  [state]
  (some-> state
          clearinghouse-state
          margin-summary
          :totalMarginUsed
          parse-number))

(defn- spot-balances
  [state]
  (or (get-in state [:spot :clearinghouse-state :balances])
      (get-in state [:spot :clearinghouseState :balances])
      (get-in state [:spot :balances])
      []))

(defn holdings-sources-signature
  "Cheap per-source availability slices (no snapshot build). Store watchers
  re-attempt the holdings preseed on EACH source's false->true transition: the
  spot balances routinely land before the perp clearinghouse snapshot, and a
  single any-source trigger would be consumed by the spot arrival while the perp
  book — usually the seedable part — is still in flight."
  [state]
  {:perp? (some? (clearinghouse-state state))
   :spot? (boolean (seq (spot-balances state)))})

(defn holdings-source-arrived?
  "True when `new-state` gained a holdings source `old-state` did not have."
  [old-state new-state]
  (let [old-sig (holdings-sources-signature old-state)
        new-sig (holdings-sources-signature new-state)]
    (boolean (or (and (:perp? new-sig) (not (:perp? old-sig)))
                 (and (:spot? new-sig) (not (:spot? old-sig)))))))

(defn- position-rows
  [state]
  (let [base-rows (or (get-in state [:webdata2 :clearinghouseState :assetPositions])
                      [])
        dex-rows (->> (or (:perp-dex-clearinghouse state) {})
                      (mapcat (fn [[dex dex-state]]
                                (map #(assoc % :dex dex)
                                     (or (:assetPositions dex-state) [])))))]
    (vec (concat base-rows dex-rows))))

(defonce ^:private market-resolution-memo
  (volatile! nil))

(defn- resolve-market-cached
  [market-by-key coin]
  (let [memo @market-resolution-memo
        memo (if (and memo (identical? (:market-by-key memo) market-by-key))
               memo
               {:market-by-key market-by-key :by-coin {}})]
    (if-let [entry (find (:by-coin memo) coin)]
      (do
        (vreset! market-resolution-memo memo)
        (val entry))
      (let [market (markets/resolve-market-by-coin market-by-key coin)]
        (vreset! market-resolution-memo
                 (assoc-in memo [:by-coin coin] market))
        market))))

(defonce ^:private spot-market-resolution-memo
  (volatile! nil))

(defn- resolve-spot-market-cached
  [market-by-key coin]
  (let [memo @spot-market-resolution-memo
        memo (if (and memo (identical? (:market-by-key memo) market-by-key))
               memo
               {:market-by-key market-by-key :by-coin {}})]
    (if-let [entry (find (:by-coin memo) coin)]
      (do
        (vreset! spot-market-resolution-memo memo)
        (val entry))
      (let [market (markets/resolve-spot-market-by-coin market-by-key coin)]
        (vreset! spot-market-resolution-memo
                 (assoc-in memo [:by-coin coin] market))
        market))))

(defn- perps-instrument-id
  [dex coin]
  (let [coin* (normalize-coin coin)
        dex* (normalize-dex dex)]
    (when (seq coin*)
      (if (and (seq dex*)
               (not (str/starts-with? coin* (str dex* ":"))))
        (str "perp:" dex* ":" coin*)
        (str "perp:" coin*)))))

(defn- spot-instrument-id
  [market coin]
  (or (non-blank-text (:key market))
      (when-let [coin* (normalize-coin coin)]
        (str "spot:" coin*))))

(defn- position-mark-price
  [market-by-key position-row]
  (let [position (or (:position position-row) {})
        coin (normalize-coin (:coin position))
        market (resolve-market-cached market-by-key coin)]
    (or (parse-number (:markPx position))
        (parse-number (:markPrice position))
        (parse-number (:markPx position-row))
        (parse-number (:markPrice position-row))
        (market-mark-price market))))

(defn- leverage-mode
  [position]
  (let [leverage (:leverage position)
        leverage-type (or (:type leverage)
                          (:marginMode position)
                          (:margin-mode position))
        token (some-> leverage-type str str/trim str/lower-case)]
    (cond
      (= token "isolated") :isolated
      (= token "cross") :cross
      :else nil)))

(defn- position-side
  [signed-size]
  (cond
    (pos? signed-size) :long
    (neg? signed-size) :short
    :else :flat))

(defn- position-notional
  [position signed-size mark-price]
  (or (let [position-value (parse-number (:positionValue position))]
        (when (number? position-value)
          (* (if (neg? signed-size) -1 1)
             (abs-number position-value))))
      (when (and (number? mark-price)
                 (not (zeroish? signed-size)))
        (* signed-size mark-price))))

(declare market-display-fields)

(defn- build-perp-exposures
  [state]
  (let [market-by-key (get-in state [:asset-selector :market-by-key])]
    (reduce (fn [{:keys [exposures warnings] :as acc} position-row]
              (let [position (or (:position position-row) {})
                    coin (normalize-coin (:coin position))
                    dex (normalize-dex (:dex position-row))
                    signed-size (parse-number (:szi position))
                    mark-price (position-mark-price market-by-key position-row)
                    signed-notional (when (number? signed-size)
                                      (position-notional position signed-size mark-price))
                    instrument-id (perps-instrument-id dex coin)]
                (cond
                  (or (not (seq instrument-id))
                      (zeroish? signed-size))
                  acc

                  (not (number? signed-notional))
                  (assoc acc :warnings
                         (conj warnings
                               {:code :missing-perp-notional
                                :instrument-id instrument-id
                                :coin coin
                                :dex dex}))

                  :else
                  (assoc acc :exposures
                         (conj exposures
                               (merge
                                {:instrument-id instrument-id
                                 :market-type :perp
                                 :coin coin
                                 :dex dex
                                 :signed-size signed-size
                                 :mark-price mark-price
                                 :signed-notional-usdc signed-notional
                                 :abs-notional-usdc (abs-number signed-notional)
                                 :side (position-side signed-size)
                                 :margin-mode (leverage-mode position)
                                 :leverage (parse-number (get-in position [:leverage :value]))
                                 :source (if (seq dex)
                                           :perp-dex-clearinghouse
                                           :clearinghouse)}
                                (market-display-fields
                                 (or (get market-by-key instrument-id)
                                     (resolve-market-cached market-by-key coin)))))))))
            {:exposures []
             :warnings []}
            (position-rows state))))

(defn- usdc-coin?
  [coin]
  (= "USDC" (some-> coin str str/trim str/upper-case)))

(defn- spot-price
  [market-by-key coin]
  (if (usdc-coin? coin)
    1
    (market-mark-price (resolve-market-cached market-by-key coin))))

(defn- market-display-fields
  [market]
  (cond-> {}
    (non-blank-text (:symbol market))
    (assoc :symbol (non-blank-text (:symbol market)))

    (non-blank-text (:base market))
    (assoc :base (non-blank-text (:base market)))

    (non-blank-text (:quote market))
    (assoc :quote (non-blank-text (:quote market)))

    (contains? market :hip3?)
    (assoc :hip3? (boolean (:hip3? market)))))

(defn- spot-display-fields
  "Market-derived display fields, with a spotMeta fallback whenever the catalog
   left the base missing or as a bare @<n> reference (dust/illiquid pairs)."
  [spot-resolver market coin token]
  (let [market-fields (market-display-fields market)
        market-base (non-blank-text (:base market-fields))]
    (if (and market-base (not (spot-token-labels/at-reference? market-base)))
      market-fields
      (merge market-fields
             (spot-token-labels/display-fields spot-resolver coin token)))))

(defn- build-spot-exposures
  [state]
  (let [market-by-key (get-in state [:asset-selector :market-by-key])
        spot-resolver (spot-token-labels/resolver (get-in state [:spot :meta]))]
    (reduce (fn [{:keys [exposures warnings cash-usdc spot-noncash-usdc] :as acc} balance]
              (let [coin (normalize-coin (:coin balance))
                    token (:token balance)
                    total (parse-number (:total balance))
                    hold (or (parse-number (:hold balance)) 0)
                    available (when (number? total)
                                (- total hold))
                    market (resolve-spot-market-cached market-by-key coin)
                    price (or (market-mark-price market)
                              (spot-price market-by-key coin))
                    instrument-id (spot-instrument-id market coin)]
                (cond
                  (or (not (seq coin))
                      (zeroish? total))
                  acc

                  (usdc-coin? coin)
                  (assoc acc :cash-usdc (+ cash-usdc total))

                  (not (number? price))
                  (assoc acc :warnings
                         (conj warnings
                               {:code :missing-spot-price
                                :instrument-id instrument-id
                                :coin coin}))

                  :else
                  (let [signed-notional (* total price)]
                    (-> acc
                        (update :spot-noncash-usdc + signed-notional)
                        (update :exposures conj
                                (merge
                                 {:instrument-id instrument-id
                                  :market-type :spot
                                  :coin coin
                                  :signed-size total
                                  :available-size available
                                  :hold-size hold
                                  :mark-price price
                                  :signed-notional-usdc signed-notional
                                  :abs-notional-usdc (abs-number signed-notional)
                                  :side :long
                                  :source :spot-clearinghouse}
                                 (spot-display-fields spot-resolver
                                                      market
                                                      coin
                                                      token))))))))
            {:exposures []
             :warnings []
             :cash-usdc 0
             :spot-noncash-usdc 0}
            (spot-balances state))))

(defn- with-weights
  [nav-usdc exposures]
  (mapv (fn [exposure]
          (if (positive-number? nav-usdc)
            (assoc exposure :weight (/ (:signed-notional-usdc exposure) nav-usdc))
            (assoc exposure :weight nil)))
        exposures))

(defn- snapshot-signature
  [state address]
  {:address address
   :webdata2-loaded? (some? (clearinghouse-state state))
   :spot-loaded? (boolean (seq (spot-balances state)))
   :perp-dex-count (count (or (:perp-dex-clearinghouse state) {}))
   :market-count (count (or (get-in state [:asset-selector :market-by-key]) {}))})

(defn- snapshot-memo-inputs
  [state]
  [(clearinghouse-state state)
   (spot-balances state)
   (get-in state [:spot :meta])
   (:perp-dex-clearinghouse state)
   (get-in state [:asset-selector :market-by-key])
   (get-in state [:account :mode])
   (account-context/effective-account-address state)
   (account-context/inspected-account-read-only? state)
   (account-context/mutations-blocked-message state)])

(defonce ^:private snapshot-memo
  (volatile! nil))

(defn- build-current-portfolio-snapshot
  [state]
  (let [{perp-exposures :exposures
         perp-warnings :warnings} (build-perp-exposures state)
        {spot-exposures :exposures
         spot-warnings :warnings
         cash-usdc :cash-usdc
         spot-noncash-usdc :spot-noncash-usdc} (build-spot-exposures state)
        perp-account-value (perp-account-value-usdc state)
        total-margin-used (total-margin-used-usdc state)
        unified-account? (= :unified (get-in state [:account :mode]))
        unified-collateral-usdc (+ cash-usdc spot-noncash-usdc)
        nav-usdc (cond
                   (and unified-account?
                        (positive-number? unified-collateral-usdc))
                   unified-collateral-usdc

                   (number? perp-account-value)
                   (+ perp-account-value spot-noncash-usdc)

                   :else
                   (+ cash-usdc spot-noncash-usdc))
        raw-exposures (vec (concat perp-exposures spot-exposures))
        exposures (with-weights nav-usdc raw-exposures)
        gross-exposure (reduce + (map :abs-notional-usdc exposures))
        net-exposure (reduce + (map :signed-notional-usdc exposures))
        address (account-context/effective-account-address state)
        read-only? (account-context/inspected-account-read-only? state)
        read-only-message (account-context/mutations-blocked-message state)
        snapshot-loaded? (boolean
                          (or (some? perp-account-value)
                              (seq exposures)
                              (pos? cash-usdc)))
        capital-ready? (positive-number? nav-usdc)
        execution-ready? (and capital-ready?
                              (not read-only?))]
    {:address address
     :loaded? snapshot-loaded?
     :snapshot-loaded? snapshot-loaded?
     :capital-ready? capital-ready?
     :execution-ready? execution-ready?
     :account {:mode (or (get-in state [:account :mode]) :classic)
               :read-only? read-only?
               :read-only-message read-only-message}
     :capital {:nav-usdc nav-usdc
               :cash-usdc cash-usdc
               :perp-account-value-usdc perp-account-value
               :total-margin-used-usdc total-margin-used
               :spot-noncash-usdc spot-noncash-usdc
               :gross-exposure-usdc gross-exposure
               :net-exposure-usdc net-exposure}
     :exposures exposures
     :by-instrument (into {}
                          (map (juxt :instrument-id identity))
                          exposures)
     :warnings (vec (concat perp-warnings spot-warnings))
     :signature (snapshot-signature state address)}))

(defn current-portfolio-snapshot
  [state]
  (let [inputs (snapshot-memo-inputs state)
        cached @snapshot-memo]
    (if (and cached (= (:inputs cached) inputs))
      (:value cached)
      (let [value (build-current-portfolio-snapshot state)]
        (vreset! snapshot-memo {:inputs inputs :value value})
        value))))

(defn current-derived-constraints
  [snapshot existing-constraints]
  (let [constraints (or existing-constraints {})
        nav-usdc (parse-number (get-in snapshot [:capital :nav-usdc]))
        gross-usdc (parse-number (get-in snapshot [:capital :gross-exposure-usdc]))
        net-usdc (parse-number (get-in snapshot [:capital :net-exposure-usdc]))
        exposures (seq (:exposures snapshot))]
    (when (and (positive-number? nav-usdc)
               (finite-number? gross-usdc)
               (finite-number? net-usdc)
               exposures)
      (let [gross-ratio (/ gross-usdc nav-usdc)
            net-ratio (/ net-usdc nav-usdc)
            net-min (floor-to-constraint-precision net-ratio)
            net-max net-min
            net-band-pct (if (> gross-ratio 1e-9)
                           (min 1.0 (/ (js/Math.round
                                        (* 1000000 (/ exposure-band-width gross-ratio)))
                                       1000000))
                           0.0)
            gross-max (ceil-to-constraint-precision
                       (max gross-ratio
                            (abs-number net-min)
                            (abs-number net-max)))
            current-max-asset-weight (ceil-to-constraint-precision
                                      (max-current-asset-weight nav-usdc
                                                                exposures))
            max-asset-weight (max (or (existing-max-asset-weight constraints)
                                      0)
                                  current-max-asset-weight)]
        (assoc constraints
               :long-only? false
               :gross-min (floor-to-constraint-precision gross-ratio)
               :gross-max gross-max
               :net-min net-min
               :net-max net-max
               :net-band-pct net-band-pct
               :max-asset-weight max-asset-weight
               :max-turnover nil)))))
