(ns hyperopen.domain.trading.fees
  (:require [hyperopen.domain.trading.core :as core]))

(def ^:private stable-pair-fee-multiplier
  0.2)

(def ^:private growth-mode-fee-multiplier
  0.1)

(def ^:private special-adjustment-rebate-multiplier
  1.5)

(def ^:private special-adjustment-taker-multiplier
  0.8)

(defn default-fee-quote
  []
  {:effective core/default-fees
   :baseline nil})

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- normalize-market-type
  [market-type]
  (cond
    (keyword? market-type) market-type
    (string? market-type) (keyword market-type)
    :else nil))

(defn- spot-market?
  [market-type]
  (= :spot (normalize-market-type market-type)))

(defn- parse-rate
  [value]
  (let [parsed (core/parse-num value)]
    (when (finite-number? parsed)
      parsed)))

(defn- user-rates
  [user-fees spot?]
  (when (map? user-fees)
    {:cross-rate (parse-rate (if spot?
                               (:userSpotCrossRate user-fees)
                               (:userCrossRate user-fees)))
     :add-rate (parse-rate (if spot?
                             (:userSpotAddRate user-fees)
                             (:userAddRate user-fees)))}))

(defn- normalize-discount
  [value]
  (let [parsed (or (parse-rate value) 0)]
    (if (> parsed 1)
      1
      (max 0 parsed))))

(defn- referral-adjusted-rates
  [user-fees spot?]
  (let [{:keys [cross-rate add-rate]} (user-rates user-fees spot?)
        referral-factor (- 1 (normalize-discount (:activeReferralDiscount user-fees)))]
    (when (and (finite-number? cross-rate)
               (finite-number? add-rate)
               (finite-number? referral-factor))
      {:cross-rate (* cross-rate referral-factor)
       :add-rate (if (pos? add-rate)
                   (* add-rate referral-factor)
                   add-rate)})))

(defn- baseline-rates
  [user-fees spot?]
  (let [{:keys [cross-rate add-rate]} (user-rates user-fees spot?)
        staking-factor (- 1 (normalize-discount (get-in user-fees [:activeStakingDiscount :discount])))]
    (when (and (finite-number? cross-rate)
               (finite-number? add-rate)
               (finite-number? staking-factor)
               (not (zero? staking-factor)))
      {:cross-rate (/ cross-rate staking-factor)
       :add-rate (if (>= add-rate 0)
                   (/ add-rate staking-factor)
                   add-rate)})))

(defn- perp-fee-factors
  [{:keys [deployer-fee-scale growth-mode?]}]
  (let [scale (or (parse-rate deployer-fee-scale) 0)
        maker-positive-scale (if (< scale 1)
                               (+ scale 1)
                               (* 2 scale))
        adjustment-c (if (< scale 1)
                       (/ scale (+ 1 scale))
                       0.5)
        growth-scale (if growth-mode?
                       growth-mode-fee-multiplier
                       1)]
    {:maker-positive-scale maker-positive-scale
     :adjustment-c adjustment-c
     :growth-scale growth-scale}))

(defn quote-fees
  [user-fees {:keys [market-type
                     stable-pair?
                     deployer-fee-scale
                     growth-mode?
                     extra-adjustment?]}]
  (let [spot? (spot-market? market-type)
        perp? (= :perp (normalize-market-type market-type))
        effective-rates (referral-adjusted-rates user-fees spot?)
        baseline* (baseline-rates user-fees spot?)]
    (when (and effective-rates baseline*)
      (let [{:keys [cross-rate add-rate]} baseline*
            stable-factor (if (and spot? stable-pair?)
                            stable-pair-fee-multiplier
                            1)
            {:keys [maker-positive-scale adjustment-c growth-scale]}
            (if perp?
              (perp-fee-factors {:deployer-fee-scale deployer-fee-scale
                                 :growth-mode? (boolean growth-mode?)})
              {:maker-positive-scale 1
               :adjustment-c 0
               :growth-scale 1})
            maker-raw (* 100 (:add-rate effective-rates) stable-factor growth-scale)
            maker-effective (if (pos? maker-raw)
                              (* maker-raw maker-positive-scale)
                              (* maker-raw
                                 (if extra-adjustment?
                                   (+ (* special-adjustment-rebate-multiplier
                                         (- 1 adjustment-c))
                                      adjustment-c)
                                   1)))
            taker-raw (* 100
                         (:cross-rate effective-rates)
                         stable-factor
                         maker-positive-scale
                         growth-scale)
            taker-effective (if extra-adjustment?
                              (* taker-raw
                                 (+ (* special-adjustment-taker-multiplier
                                       (- 1 adjustment-c))
                                    adjustment-c))
                              taker-raw)
            baseline-taker (* 100 cross-rate)
            baseline-maker (* 100 add-rate)]
        (when (and (finite-number? maker-effective)
                   (finite-number? taker-effective)
                   (finite-number? baseline-taker)
                   (finite-number? baseline-maker))
          {:effective {:taker taker-effective
                       :maker maker-effective}
           :baseline (when (< taker-effective baseline-taker)
                       {:taker baseline-taker
                        :maker baseline-maker})})))))
