(ns hyperopen.portfolio.fee-schedule
  (:require [clojure.string :as str]))

(def default-market-type :perps)

(def market-type-options
  [{:value :perps
    :label "Perps"}
   {:value :spot
    :label "Spot"}
   {:value :spot-stable-pair
    :label "Spot + Stable Pair"}
   {:value :spot-aligned-quote
    :label "Spot + Aligned Quote"}
   {:value :spot-aligned-stable-pair
    :label "Spot + Aligned Quote + Stable Pair"}])

(def ^:private market-type-by-key
  (into {}
        (map (fn [{:keys [value]}]
               [value value]))
        market-type-options))

(defn- normalize-token
  [value]
  (some-> value
          str
          str/lower-case
          (str/replace #"[^a-z0-9]" "")))

(def ^:private market-type-aliases
  (reduce (fn [aliases {:keys [value label]}]
            (assoc aliases
                   (normalize-token value) value
                   (normalize-token (name value)) value
                   (normalize-token label) value))
          {}
          market-type-options))

(defn- option-by-value
  [options]
  (into {}
        (map (fn [{:keys [value] :as option}]
               [value option]))
        options))

(defn- option-aliases
  [options]
  (reduce (fn [aliases {:keys [value label description]}]
            (cond-> (assoc aliases
                           (normalize-token value) value
                           (normalize-token (name value)) value
                           (normalize-token label) value)
              description
              (assoc (normalize-token description) value)))
          {}
          options))

(defn- normalize-option
  [options default-value value]
  (let [by-value (option-by-value options)
        aliases (option-aliases options)]
    (or (when (and (keyword? value)
                   (contains? by-value value))
          value)
        (get aliases (normalize-token value))
        default-value)))

(defn normalize-market-type
  [value]
  (or (when (keyword? value)
        (get market-type-by-key value))
      (get market-type-aliases (normalize-token value))
      default-market-type))

(def default-referral-discount
  :none)

(def referral-discount-options
  [{:value :none
    :label "No referral discount"
    :description "No active referral discount"
    :discount 0}
   {:value :referral-4
    :label "4%"
    :description "Referral discount"
    :discount 0.04}])

(def default-staking-tier
  :none)

(def staking-tier-options
  [{:value :none
    :label "No stake"
    :description "No active staking discount"
    :discount 0}
   {:value :wood
    :label "Wood"
    :description ">10 HYPE staked = 5% discount"
    :discount 0.05}
   {:value :bronze
    :label "Bronze"
    :description ">100 HYPE staked = 10% discount"
    :discount 0.10}
   {:value :silver
    :label "Silver"
    :description ">1k HYPE staked = 15% discount"
    :discount 0.15}
   {:value :gold
    :label "Gold"
    :description ">10k HYPE staked = 20% discount"
    :discount 0.20}
   {:value :platinum
    :label "Platinum"
    :description ">100k HYPE staked = 30% discount"
    :discount 0.30}
   {:value :diamond
    :label "Diamond"
    :description ">500k HYPE staked = 40% discount"
    :discount 0.40}])

(def default-maker-rebate-tier
  :none)

(def maker-rebate-tier-options
  [{:value :none
    :label "No rebate"
    :description "No active maker rebate"}
   {:value :tier-1
    :label "Tier 1"
    :description ">0.5% 14d weighted maker volume = -0.001% maker fee"
    :maker-rate -0.001}
   {:value :tier-2
    :label "Tier 2"
    :description ">1.5% 14d weighted maker volume = -0.002% maker fee"
    :maker-rate -0.002}
   {:value :tier-3
    :label "Tier 3"
    :description ">3.0% 14d weighted maker volume = -0.003% maker fee"
    :maker-rate -0.003}])

(defn normalize-referral-discount
  [value]
  (normalize-option referral-discount-options default-referral-discount value))

(defn normalize-staking-tier
  [value]
  (normalize-option staking-tier-options default-staking-tier value))

(defn normalize-maker-rebate-tier
  [value]
  (normalize-option maker-rebate-tier-options default-maker-rebate-tier value))

(def ^:private volume-thresholds
  ["<= $5M" "> $5M" "> $25M" "> $100M" "> $500M" "> $2B" "> $7B"])

(def ^:private perps-rates
  [{:taker 0.045 :maker 0.015}
   {:taker 0.040 :maker 0.012}
   {:taker 0.035 :maker 0.008}
   {:taker 0.030 :maker 0.004}
   {:taker 0.028 :maker 0}
   {:taker 0.026 :maker 0}
   {:taker 0.024 :maker 0}])

(def ^:private spot-rates
  [{:taker 0.070 :maker 0.040}
   {:taker 0.060 :maker 0.030}
   {:taker 0.050 :maker 0.020}
   {:taker 0.040 :maker 0.010}
   {:taker 0.035 :maker 0}
   {:taker 0.030 :maker 0}
   {:taker 0.025 :maker 0}])

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- optional-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat value)
              :else js/NaN)]
    (when (finite-number? num)
      num)))

(defn- trim-rate-decimals
  [text]
  (let [[whole decimals] (str/split text #"\.")]
    (if-not decimals
      text
      (let [trimmed (loop [chars (vec decimals)]
                      (if (and (> (count chars) 3)
                               (= \0 (peek chars)))
                        (recur (pop chars))
                        (apply str chars)))]
        (str whole "." trimmed)))))

(defn- format-rate
  [rate]
  (let [rate* (if (finite-number? rate) rate 0)]
    (if (zero? rate*)
      "0%"
      (str (trim-rate-decimals (.toFixed rate* 4)) "%"))))

(defn- format-discount-pct
  [ratio]
  (let [pct (* 100 (or (optional-number ratio) 0))
        fixed (.toFixed pct 2)
        trimmed (-> fixed
                    (str/replace #"0+$" "")
                    (str/replace #"\.$" ""))]
    (str trimmed "%")))

(defn- positive-fee-discount
  [rate discount]
  (if (pos? rate)
    (* rate (- 1 discount))
    rate))

(defn- option-discount
  [options selected-value]
  (or (:discount (get (option-by-value options) selected-value))
      0))

(defn- option-maker-rate
  [selected-value]
  (:maker-rate (get (option-by-value maker-rebate-tier-options) selected-value)))

(defn- apply-staking-tier
  [staking-tier row]
  (let [discount (option-discount staking-tier-options staking-tier)]
    (-> row
        (update :taker positive-fee-discount discount)
        (update :maker positive-fee-discount discount))))

(defn- apply-maker-rebate-tier
  [maker-rebate-tier row]
  (if-let [maker-rate (option-maker-rate maker-rebate-tier)]
    (assoc row :maker maker-rate)
    row))

(defn- apply-referral-discount
  [referral-discount row]
  (let [discount (option-discount referral-discount-options referral-discount)]
    (-> row
        (update :taker positive-fee-discount discount)
        (update :maker positive-fee-discount discount))))

(defn- apply-market-type
  [market-type row]
  (case (normalize-market-type market-type)
    :perps row
    :spot row
    :spot-stable-pair
    (-> row
        (update :taker * 0.2)
        (update :maker * 0.2))
    :spot-aligned-quote
    (-> row
        (update :taker * 0.8)
        (update :maker (fn [maker]
                         (if (neg? maker)
                           (* maker 1.5)
                           maker))))
    :spot-aligned-stable-pair
    (-> row
        (update :taker * 0.16)
        (update :maker (fn [maker]
                         (if (neg? maker)
                           (* maker 0.3)
                           (* maker 0.2)))))
    row))

(defn fee-schedule-rows
  ([market-type]
   (fee-schedule-rows market-type {}))
  ([market-type {:keys [referral-discount
                        staking-tier
                        maker-rebate-tier]}]
   (let [market-type* (normalize-market-type market-type)
         referral-discount* (normalize-referral-discount referral-discount)
         staking-tier* (normalize-staking-tier staking-tier)
         maker-rebate-tier* (normalize-maker-rebate-tier maker-rebate-tier)
         base-rates (if (= :perps market-type*)
                      perps-rates
                      spot-rates)]
     (mapv (fn [index row]
             (let [row* (->> row
                             (apply-staking-tier staking-tier*)
                             (apply-maker-rebate-tier maker-rebate-tier*)
                             (apply-market-type market-type*)
                             (apply-referral-discount referral-discount*))]
               {:tier (str index)
                :volume (nth volume-thresholds index)
                :taker (format-rate (:taker row*))
                :maker (format-rate (:maker row*))}))
           (range)
           base-rates))))

(defn- market-type-label
  [market-type]
  (or (some (fn [{:keys [value label]}]
              (when (= value market-type)
                label))
            market-type-options)
      "Perps"))

(defn- wallet-connected?
  [state]
  (boolean (some-> (get-in state [:wallet :address]) str str/trim seq)))

(defn- approx=
  [a b]
  (and (finite-number? a)
       (finite-number? b)
       (< (js/Math.abs (- a b)) 0.0000001)))

(defn- current-referral-discount
  [user-fees]
  (let [discount (or (optional-number (:activeReferralDiscount user-fees)) 0)]
    (if (pos? discount)
      :referral-4
      :none)))

(defn- staking-tier
  [staking-discount]
  (or (:tier staking-discount)
      (:stakingTier staking-discount)
      (:staking-tier staking-discount)
      (:level staking-discount)))

(defn- current-staking-tier
  [user-fees]
  (let [staking-discount (get-in user-fees [:activeStakingDiscount])
        active-discount (or (optional-number (:discount staking-discount)) 0)
        tier (staking-tier staking-discount)]
    (or (when (some? tier)
          (let [tier* (normalize-staking-tier tier)]
            (when-not (= :none tier*)
              tier*)))
        (some (fn [{:keys [value discount]}]
                (when (and (not= :none value)
                           (approx= active-discount discount))
                  value))
              staking-tier-options)
        :none)))

(defn- current-maker-rebate-tier
  [user-fees]
  (let [maker-rate (optional-number (:userAddRate user-fees))]
    (if (and (finite-number? maker-rate)
             (neg? maker-rate))
      (let [maker-pct (* 100 maker-rate)]
        (or (some (fn [{:keys [value maker-rate]}]
                    (when (and maker-rate
                               (approx= maker-pct maker-rate))
                      value))
                  maker-rebate-tier-options)
            :none))
      :none)))

(def rate-note
  "* Rates reflect selected referral, staking, and maker rebate scenarios; HIP-3 deployer adjustments not included")

(def documentation-url
  "https://hyperliquid.gitbook.io/hyperliquid-docs/trading/fees")

(def ^:private missing-selection
  ::missing-selection)

(defn- selected-value
  [state path normalize current-value]
  (let [value (get-in state path missing-selection)]
    (if (or (= missing-selection value)
            (nil? value))
      current-value
      (normalize value))))

(defn- option
  [options value]
  (get (option-by-value options) value))

(defn- current-helper
  [connected? none-helper active-helper selected current-value]
  (cond
    (not connected?) "Wallet not connected"
    (= :none selected) none-helper
    (= selected current-value) active-helper
    :else "Scenario preview"))

(defn- decorate-options
  [options current-value selected-value current-label]
  (mapv (fn [{:keys [value] :as option}]
          (cond-> (assoc option :selected? (= value selected-value))
            (= value current-value)
            (assoc :current? true
                   :current-label current-label)))
        options))

(defn- referral-control
  [state connected? current-value selected]
  (let [selected-option (option referral-discount-options selected)]
    {:label "Referral Status"
     :value (:label selected-option)
     :helper (current-helper connected?
                             "No active referral discount"
                             "Active referral discount"
                             selected
                             current-value)
     :selected-value selected
     :dropdown-open? (boolean (get-in state [:portfolio-ui :fee-schedule-referral-dropdown-open?]))
     :options (decorate-options referral-discount-options
                                current-value
                                selected
                                "Current wallet status")}))

(defn- staking-control
  [state connected? current-value selected]
  (let [selected-option (option staking-tier-options selected)]
    {:label "Staking Tier"
     :value (:label selected-option)
     :helper (current-helper connected?
                             "No active staking discount"
                             "Active staking discount"
                             selected
                             current-value)
     :selected-value selected
     :dropdown-open? (boolean (get-in state [:portfolio-ui :fee-schedule-staking-dropdown-open?]))
     :options (decorate-options staking-tier-options
                                current-value
                                selected
                                "Current wallet staking tier")}))

(defn- maker-rebate-control
  [state connected? current-value selected]
  (let [selected-option (option maker-rebate-tier-options selected)]
    {:label "Maker Rebate Tier"
     :value (:label selected-option)
     :helper (current-helper connected?
                             "No active maker rebate"
                             "Current maker rate is a rebate"
                             selected
                             current-value)
     :selected-value selected
     :dropdown-open? (boolean (get-in state [:portfolio-ui :fee-schedule-maker-rebate-dropdown-open?]))
     :options (decorate-options maker-rebate-tier-options
                                current-value
                                selected
                                "Current wallet maker rebate")}))

(defn fee-schedule-model
  [state]
  (let [market-type (normalize-market-type
                     (get-in state [:portfolio-ui :fee-schedule-market-type]
                             default-market-type))
        connected? (wallet-connected? state)
        user-fees (get-in state [:portfolio :user-fees])
        current-referral (if connected?
                           (current-referral-discount user-fees)
                           :none)
        current-staking (if connected?
                          (current-staking-tier user-fees)
                          :none)
        current-maker-rebate (if connected?
                               (current-maker-rebate-tier user-fees)
                               :none)
        selected-referral (selected-value state
                                          [:portfolio-ui :fee-schedule-referral-discount]
                                          normalize-referral-discount
                                          current-referral)
        selected-staking (selected-value state
                                         [:portfolio-ui :fee-schedule-staking-tier]
                                         normalize-staking-tier
                                         current-staking)
        selected-maker-rebate (selected-value state
                                              [:portfolio-ui :fee-schedule-maker-rebate-tier]
                                              normalize-maker-rebate-tier
                                              current-maker-rebate)
        scenario {:referral-discount selected-referral
                  :staking-tier selected-staking
                  :maker-rebate-tier selected-maker-rebate}]
    {:open? (boolean (get-in state [:portfolio-ui :fee-schedule-open?]))
     :anchor (get-in state [:portfolio-ui :fee-schedule-anchor])
     :title "Fee Schedule"
     :selected-market-type market-type
     :selected-market-label (market-type-label market-type)
     :market-dropdown-open? (boolean (get-in state [:portfolio-ui :fee-schedule-market-dropdown-open?]))
     :market-options market-type-options
     :referral (referral-control state connected? current-referral selected-referral)
     :staking (staking-control state connected? current-staking selected-staking)
     :maker-rebate (maker-rebate-control state connected? current-maker-rebate selected-maker-rebate)
     :rows (fee-schedule-rows market-type scenario)
     :rate-note rate-note
     :documentation-url documentation-url}))
