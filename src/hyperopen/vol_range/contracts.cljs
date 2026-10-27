(ns hyperopen.vol-range.contracts
  "Versioned Vol Range contracts (HO-SPEC-014 v2.1, Release A0).

  Invariant ownership: `hyperopen.vol-range.domain.admission` owns all
  inventory, cash, fee and price checks; `hyperopen.vol-range.application.planner`
  is the only orchestrator; views consume projections only.

  Release A0 is preview-only. There is no execution flag reader in this
  namespace or anywhere else: nothing here can construct a signable action."
  (:require [cljs.spec.alpha :as s]
            [hyperopen.config :as config]
            [hyperopen.domain.trading.precision :as precision]))

(def model-version 1)
(def precision-version 1)
(def estimator-version "parkinson-v1")
(def draft-schema-version 1)

(def product-policy
  {:rung-count {:min 1 :max 12}
   :distance-sigma {:min "0.10" :max "4.00"}
   :skew {:min "1.00" :max "3.50"}
   :manual-daily-pct {:min "0.10" :max "50.00"}
   :tilt-strength {:min "0" :max "1" :default "0.45"}
   :min-leg-notional-usdc "10.50"
   :venue-min-notional-usdc "10"
   :max-distance-from-mid-fraction "0.5"
   :remediation-step-sigma "0.05"
   :lookback-days 14
   :daily-fetch-bars 16
   :daily-fetch-ttl-ms 900000
   :daily-retry-ms 30000
   :review-max-age-ms 60000
   :anchor-drift-sigma "0.50"
   :vol-change-fraction "0.20"
   :fee-reserve-scale 8
   :sigma-display-scale 4})

(def capability-matrix
  {:network :hyperliquid-mainnet
   :market-types #{:spot}
   :quote-symbols #{"USDC"}
   ;; Spec 3.1: admission inspects the RAW `userAbstraction` value. The
   ;; normalized `[:account :mode]` maps unknown values to :classic, so it is
   ;; never used for admission. Missing or unlisted raw values are blocked.
   :raw-account-modes #{"default" "disabled" "dexAbstraction"}
   :release :a0
   :execution? false
   :reporting? false})

(def freshness-policy
  "Spec 7.3 maximum source ages in milliseconds. Release A0 reports ages and
  stale flags in the review; placement admission (Release A) enforces them
  with awaited reads."
  {:bbo-ms 2000
   :vwap-ms 30000
   :fee-ms 300000
   :account-ms 5000
   :review-ms 60000})

(defn current-network
  "The one network identity Vol Range scopes and plans carry. HyperOpen's
  exchange transport is mainnet-only today (spec 3.2); a testnet adapter must
  change this together with endpoint and signing selection."
  []
  (:network capability-matrix))

(defn preview-enabled? []
  (config/vol-range-preview-enabled?))

;; ---------------------------------------------------------------------------
;; Error and warning registry
;; ---------------------------------------------------------------------------

(def ^:private registry-rows
  [[:input/no-side-enabled :input :invalid "Enable at least one side"]
   [:input/quantity-required :input :invalid "Enter a quantity"]
   [:input/quantity-not-lot-multiple :input :invalid "Quantity must be a multiple of the lot size"]
   [:input/invalid-number :input :invalid "Enter a valid number"]
   [:input/count-out-of-range :input :invalid "Rung count is out of range"]
   [:input/distance-out-of-range :input :invalid "Distance is out of range"]
   [:input/start-above-end :input :invalid "Start distance must not exceed end distance"]
   [:input/skew-out-of-range :input :invalid "Size skew is out of range"]
   [:input/max-debit-required :input :invalid "Enter a maximum USDC debit"]
   [:input/quote-amount-required :input :invalid "Enter a USDC amount"]
   [:input/limit-below-lot :input :invalid "Maximum is less than one lot at the anchor price"]
   [:input/quote-amount-too-small :input :invalid "The USDC amount is too small for these rungs"]
   [:input/maximum-required :input :invalid "Enter a maximum inventory"]
   [:input/inventory-limits-order :input :invalid "Protected core must not exceed target, and target must not exceed maximum"]
   [:input/manual-vol-out-of-range :input :invalid "Manual daily volatility is out of range"]
   [:input/custom-anchor-invalid :input :invalid "Enter a valid custom anchor price"]
   [:input/tilt-strength-out-of-range :input :invalid "Tilt strength is out of range"]
   [:data/volatility-unavailable :data :unavailable "14 completed daily candles required"]
   [:data/volatility-zero :data :invalid "Measured volatility is zero; enter a manual value"]
   [:data/anchor-unavailable :data :unavailable "Anchor price is unavailable"]
   [:data/bbo-unavailable :data :unavailable "Best bid and ask are unavailable"]
   [:data/bbo-crossed :data :unavailable "Best bid and ask are crossed"]
   [:data/balances-unavailable :data :unavailable "Balances are unavailable"]
   [:data/fee-quote-unavailable :data :unavailable "Fee quote is unavailable"]
   [:data/market-metadata-unavailable :data :unavailable "Market metadata is unavailable"]
   [:capability/market-unsupported :capability :unavailable "Vol Range supports USDC spot markets only"]
   [:capability/account-mode-unsupported :capability :unavailable "This account mode is not supported"]
   [:capability/preview-disabled :capability :unavailable "Vol Range preview is disabled"]
   [:capability/account-required :capability :unavailable "Connect an account to plan"]
   [:plan/nonpositive-price :plan :invalid "A rung price is not positive"]
   [:plan/zero-size-rung :plan :invalid "A rung rounds to zero size"]
   [:plan/duplicate-price :plan :invalid "Two rungs share the same price"]
   [:plan/post-only-cross :plan :invalid "A rung would cross the market"]
   [:plan/self-cross :plan :invalid "A buy rung is at or above a sell rung"]
   [:plan/distance-guard :plan :invalid "A rung is too far from the market"]
   [:plan/min-notional :plan :invalid "A rung is below the minimum order value"]
   [:plan/max-inventory-breach :plan :invalid "Buys could exceed the maximum inventory"]
   [:plan/protected-core-breach :plan :invalid "Sells could go below the protected core"]
   [:plan/usdc-cap-exceeded :plan :invalid "Buys exceed the maximum USDC debit"]
   [:plan/insufficient-usdc :plan :invalid "Not enough available USDC"]
   [:plan/insufficient-base :plan :invalid "Not enough available base asset"]
   [:plan/corrective-direction :plan :invalid "Orders move inventory away from the target"]
   [:plan/unbounded-commitment :plan :invalid "Commitment cannot be bounded"]
   [:capability/fee-settlement-unverified :capability :invalid "Fee settlement currency is not verified"]
   [:capability/raw-account-mode-unverified :capability :invalid "Raw account mode is not verified"]
   [:hint/nothing-to-sell :hint :invalid "No sellable inventory above the protected core"]
   [:data/volatility-stale :data :unavailable "Daily candles are stale and are being refreshed"]
   [:data/account-mode-unavailable :data :unavailable "Account mode is not loaded yet"]
   [:input/maximum-required-for-tilt :input :invalid "Set a maximum inventory to use inventory tilt"]
   [:plan/existing-commitment-worsens-breach :plan :invalid
    "Existing open orders could deepen the current inventory breach"]])

(def error-registry
  (into {}
        (map (fn [[code category status message]]
               [code {:category category :status status :message message}]))
        registry-rows))

(defn- entry [code data]
  (let [{:keys [category message]} (get error-registry code)]
    {:code code
     :category category
     :message (or (:message data) message)
     :data data}))

(defn error [code data] (entry code data))
(defn warning [code data] (entry code data))

(defn status-for-errors
  "Plan status for a set of errors: :unavailable beats :invalid; none is :ready."
  [errors]
  (let [statuses (map #(get-in error-registry [(:code %) :status] :invalid) errors)]
    (cond
      (some #{:unavailable} statuses) :unavailable
      (seq statuses) :invalid
      :else :ready)))

(defn scope-key
  "Draft/plan scope key. Market type and dex are part of the key because perp,
  HIP-3 and spot markets reuse the same small integer indexes."
  [{:keys [network account-address market-type dex spot-pair-index]}]
  (str (name (or network (current-network))) "|" (or account-address "none") "|"
       (if market-type (name market-type) "unknown") "|" (or dex "") "|" spot-pair-index))

;; ---------------------------------------------------------------------------
;; Specs (exact keys)
;; ---------------------------------------------------------------------------

(defn- exact-keys? [m ks]
  (and (map? m) (= (set (keys m)) ks)))

(defn- decimal-string? [x]
  (some? (precision/parse-decimal x)))

(defn- canonical-positive-decimal?
  "A clean wire string (spec 6.2): parses, is > 0 and round-trips unchanged, so
  \"1.50\", \"5.\", \"0\" and \"-1\" are rejected."
  [x]
  (when (string? x)
    (when-let [v (precision/parse-decimal x)]
      (and (precision/pos-dec? v) (= x (precision/decimal->string v))))))

(defn- canonical-nonnegative-decimal?
  "Like `canonical-positive-decimal?` but admits \"0\": an :invalid plan keeps
  zero-lot rungs as diagnostics next to :plan/zero-size-rung (spec 6.1). A
  :ready plan can never contain one because that error blocks it."
  [x]
  (when (string? x)
    (when-let [v (precision/parse-decimal x)]
      (and (not (precision/neg-dec? v)) (= x (precision/decimal->string v))))))

(s/def ::leg
  (s/and #(exact-keys? % #{:leg-id :side :rung-index :price :size :notional :actual-sigma :raw-price})
         #(string? (:leg-id %))
         #(contains? #{:buy :sell} (:side %))
         #(and (integer? (:rung-index %)) (<= 0 (:rung-index %)))
         #(canonical-positive-decimal? (:price %))
         #(canonical-nonnegative-decimal? (:size %))
         #(canonical-nonnegative-decimal? (:notional %))
         #(decimal-string? (:raw-price %))
         #(or (nil? (:actual-sigma %)) (string? (:actual-sigma %)))))

(s/def ::error
  (s/and #(exact-keys? % #{:code :category :message :data})
         #(keyword? (:code %))
         #(keyword? (:category %))
         #(string? (:message %))))

(s/def ::warning ::error)

(s/def ::patch-path (s/and vector? #(every? keyword? %)))

(s/def ::remediation
  (s/and #(exact-keys? % #{:id :label :patch})
         #(keyword? (:id %))
         #(string? (:label %))
         #(and (vector? (:patch %))
               (every? (fn [pair] (and (vector? pair) (= 2 (count pair))
                                       (s/valid? ::patch-path (first pair))))
                       (:patch %)))))

(s/def ::plan-result
  (s/and #(exact-keys? % #{:status :plan-id :scope :input-fingerprint :model :legs :totals
                           :inventory-outcomes :fee-reserves :source-versions :remediations
                           :errors :warnings})
         #(contains? #{:ready :invalid :unavailable} (:status %))
         ;; Status is a function of the errors, and a :ready plan has legs.
         #(= (:status %) (status-for-errors (:errors %)))
         #(or (not= :ready (:status %))
              (and (seq (:legs %))
                   (every? (fn [l] (and (canonical-positive-decimal? (:size l))
                                        (canonical-positive-decimal? (:notional l))))
                           (:legs %))))
         #(or (nil? (:plan-id %)) (string? (:plan-id %)))
         #(string? (:input-fingerprint %))
         #(or (nil? (:scope %)) (map? (:scope %)))
         #(and (sequential? (:legs %)) (every? (fn [l] (s/valid? ::leg l)) (:legs %)))
         #(and (sequential? (:remediations %)) (every? (fn [r] (s/valid? ::remediation r)) (:remediations %)))
         #(and (sequential? (:errors %)) (every? (fn [e] (s/valid? ::error e)) (:errors %)))
         #(and (sequential? (:warnings %)) (every? (fn [e] (s/valid? ::warning e)) (:warnings %)))))

(defn- nil-or-keys? [m ks] (or (nil? m) (exact-keys? m ks)))

(s/def ::market-snapshot
  (s/and #(exact-keys? % #{:status :errors :coin :market-type :base-symbol :quote-symbol
                           :sz-decimals :bbo :anchors :auto-volatility :observed-at-ms})
         #(contains? #{:ready :invalid :unavailable} (:status %))
         #(nil-or-keys? (:bbo %) #{:bid :ask :observed-at-ms})
         #(let [a (:anchors %)]
            (and (exact-keys? a #{:vwap-24h :utc-open :market-price})
                 (every? (fn [v] (nil-or-keys? v #{:price :observed-at-ms})) (vals a))))))

(defn- balance? [b] (exact-keys? b #{:total :hold :available}))

(s/def ::account-snapshot
  (s/and #(exact-keys? % #{:status :errors :account-address :account-mode :raw-account-mode
                           :read-only? :base :quote :open-orders :observed-at-ms})
         #(or (nil? (:raw-account-mode %)) (string? (:raw-account-mode %)))
         #(contains? #{:ready :invalid :unavailable} (:status %))
         #(or (nil? (:base %)) (balance? (:base %)))
         #(or (nil? (:quote %)) (balance? (:quote %)))
         #(and (sequential? (:open-orders %))
               (every? (fn [o] (exact-keys? o #{:oid :side :price :remaining})) (:open-orders %)))))

(s/def ::fee-capability
  #(exact-keys? % #{:status :maker-fraction :buy-fee-token :sell-fee-token :settlement-verified?
                    :source :loaded-at-ms}))

(defn leg-valid? [x] (s/valid? ::leg x))
(defn error-valid? [x] (s/valid? ::error x))
(defn warning-valid? [x] (s/valid? ::warning x))
(defn remediation-valid? [x] (s/valid? ::remediation x))
(defn plan-result-valid? [x] (s/valid? ::plan-result x))
(defn market-snapshot-valid? [x] (s/valid? ::market-snapshot x))
(defn account-snapshot-valid? [x] (s/valid? ::account-snapshot x))
(defn fee-capability-valid? [x] (s/valid? ::fee-capability x))
