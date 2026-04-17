(ns hyperopen.portfolio.fee-schedule
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.fees :as trading-fees]))

(def default-market-type :perps)

(def ^:private default-hip3-deployer-fee-scale
  1)

(def market-type-options
  [{:value :spot
    :label "Spot"}
   {:value :spot-aligned-quote
    :label "Spot + Aligned Quote"}
   {:value :spot-stable-pair
    :label "Spot + Stable Pair"}
   {:value :spot-aligned-stable-pair
    :label "Spot + Aligned Quote + Stable Pair"}
   {:value :perps
    :label "Core Perps"}
   {:value :hip3-perps
    :label "HIP-3 Perps"}
   {:value :hip3-perps-growth-mode
    :label "HIP-3 Perps + Growth mode"}
   {:value :hip3-perps-aligned-quote
    :label "HIP-3 Perps + Aligned Quote"}
   {:value :hip3-perps-growth-mode-aligned-quote
    :label "HIP-3 Perps + Growth mode + Aligned Quote"}])

(def ^:private hip3-market-types
  #{:hip3-perps
    :hip3-perps-growth-mode
    :hip3-perps-aligned-quote
    :hip3-perps-growth-mode-aligned-quote})

(def ^:private aligned-quote-symbols
  #{"HORSE"
    "USDH"
    "USDL"
    "USDZZ"})

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
  (-> (reduce (fn [aliases {:keys [value label]}]
                (assoc aliases
                       (normalize-token value) value
                       (normalize-token (name value)) value
                       (normalize-token label) value))
              {}
              market-type-options)
      (assoc (normalize-token "Perps") :perps)))

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

(defn- normalized-market-type
  [value]
  (cond
    (keyword? value) value
    (string? value) (let [normalized (some-> value str/trim str/lower-case)]
                      (when (seq normalized)
                        (keyword normalized)))
    :else nil))

(defn- hip3-market-type?
  [market-type]
  (contains? hip3-market-types (normalize-market-type market-type)))

(defn- growth-mode-enabled?
  [value]
  (or (= true value)
      (= :enabled value)
      (= "enabled" (some-> value str str/trim str/lower-case))))

(defn- aligned-quote?
  [market]
  (if (contains? market :special-quote-fee-adjustment?)
    (boolean (:special-quote-fee-adjustment? market))
    (contains? aligned-quote-symbols
               (some-> (:quote market) str str/trim str/upper-case))))

(defn- active-growth-mode?
  [market]
  (if (contains? market :growth-mode?)
    (boolean (:growth-mode? market))
    (growth-mode-enabled? (:growthMode market))))

(defn- namespaced-symbol
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (let [without-namespace (if (str/includes? text ":")
                                (second (str/split text #":" 2))
                                text)]
        (first (str/split without-namespace #"/" 2))))))

(defn- active-market-symbol
  [state market]
  (or (namespaced-symbol (:base market))
      (namespaced-symbol (:coin market))
      (namespaced-symbol (:active-asset state))))

(defn- active-market-scenario
  [market]
  (let [market-type (normalized-market-type (:market-type market))
        hip3? (and (= :perp market-type)
                   (seq (some-> (:dex market) str str/trim)))
        growth? (active-growth-mode? market)
        aligned? (aligned-quote? market)]
    (cond
      (= :spot market-type)
      (cond
        (and (:stable-pair? market) aligned?) :spot-aligned-stable-pair
        (:stable-pair? market) :spot-stable-pair
        aligned? :spot-aligned-quote
        :else :spot)

      hip3?
      (cond
        (and growth? aligned?) :hip3-perps-growth-mode-aligned-quote
        growth? :hip3-perps-growth-mode
        aligned? :hip3-perps-aligned-quote
        :else :hip3-perps)

      (= :perp market-type)
      :perps

      :else nil)))

(defn- active-fee-context
  [state]
  (let [market (or (:active-market state) {})
        dex (some-> (:dex market) str str/trim)
        active-deployer-fee-scale (optional-number
                                   (get-in state
                                           [:perp-dex-fee-config-by-name dex :deployer-fee-scale]))
        active-scenario (active-market-scenario market)
        hip3-active? (hip3-market-type? active-scenario)
        use-active-hip3-scale? (and hip3-active?
                                    (finite-number? active-deployer-fee-scale))]
    {:active-market-type active-scenario
     :active-market-symbol (active-market-symbol state market)
     :deployer-fee-scale (if use-active-hip3-scale?
                           active-deployer-fee-scale
                           default-hip3-deployer-fee-scale)}))

(defn- market-type-growth-mode?
  [market-type]
  (contains? #{:hip3-perps-growth-mode
               :hip3-perps-growth-mode-aligned-quote}
             (normalize-market-type market-type)))

(defn- market-type-aligned-quote?
  [market-type]
  (contains? #{:spot-aligned-quote
               :spot-aligned-stable-pair
               :hip3-perps-aligned-quote
               :hip3-perps-growth-mode-aligned-quote}
             (normalize-market-type market-type)))

(defn- market-type-stable-pair?
  [market-type]
  (contains? #{:spot-stable-pair
               :spot-aligned-stable-pair}
             (normalize-market-type market-type)))

(defn- available-market-type
  [market-type _active-context]
  (normalize-market-type market-type))

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
  [market-type active-context row]
  (let [market-type* (available-market-type market-type active-context)
        spot? (contains? #{:spot
                           :spot-stable-pair
                           :spot-aligned-quote
                           :spot-aligned-stable-pair}
                         market-type*)]
    (trading-fees/adjust-percentage-rates
     row
     {:market-type (if spot? :spot :perp)
      :stable-pair? (market-type-stable-pair? market-type*)
      :deployer-fee-scale (when (hip3-market-type? market-type*)
                            (or (:deployer-fee-scale active-context)
                                default-hip3-deployer-fee-scale))
      :growth-mode? (market-type-growth-mode? market-type*)
      :extra-adjustment? (market-type-aligned-quote? market-type*)})))

(defn fee-schedule-rows
  ([market-type]
   (fee-schedule-rows market-type {}))
  ([market-type {:keys [referral-discount
                        staking-tier
                        maker-rebate-tier
                        active-fee-context]}]
   (let [active-context (or active-fee-context {})
         market-type* (available-market-type market-type active-context)
         referral-discount* (normalize-referral-discount referral-discount)
         staking-tier* (normalize-staking-tier staking-tier)
         maker-rebate-tier* (normalize-maker-rebate-tier maker-rebate-tier)
         base-rates (if (or (= :perps market-type*)
                            (hip3-market-type? market-type*))
                      perps-rates
                      spot-rates)]
     (mapv (fn [index row]
             (let [row* (->> row
                             (apply-staking-tier staking-tier*)
                             (apply-maker-rebate-tier maker-rebate-tier*)
                             (apply-market-type market-type* active-context)
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
      "Core Perps"))

(defn- decorate-market-options
  [active-context selected-value]
  (let [active-market-type (:active-market-type active-context)
        active-market-symbol (:active-market-symbol active-context)]
    (mapv (fn [{:keys [value] :as option}]
            (cond-> (assoc option :selected? (= value selected-value))
              (and (= value active-market-type)
                   (seq active-market-symbol))
              (assoc :current? true
                     :current-label (str "Active market: " active-market-symbol))))
          market-type-options)))

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
  "* Rates reflect selected scenarios, market type, and HIP-3 deployer context")

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
  (let [active-context (active-fee-context state)
        market-type (available-market-type
                     (get-in state [:portfolio-ui :fee-schedule-market-type]
                             default-market-type)
                     active-context)
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
                  :maker-rebate-tier selected-maker-rebate
                  :active-fee-context active-context}]
    {:open? (boolean (get-in state [:portfolio-ui :fee-schedule-open?]))
     :anchor (get-in state [:portfolio-ui :fee-schedule-anchor])
     :title "Fee Schedule"
     :selected-market-type market-type
     :selected-market-label (market-type-label market-type)
     :market-dropdown-open? (boolean (get-in state [:portfolio-ui :fee-schedule-market-dropdown-open?]))
     :market-options (decorate-market-options active-context market-type)
     :referral (referral-control state connected? current-referral selected-referral)
     :staking (staking-control state connected? current-staking selected-staking)
     :maker-rebate (maker-rebate-control state connected? current-maker-rebate selected-maker-rebate)
     :rows (fee-schedule-rows market-type scenario)
     :rate-note rate-note
     :documentation-url documentation-url}))
