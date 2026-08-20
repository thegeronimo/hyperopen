(ns hyperopen.domain.trading.validation
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.core :as core]
            [hyperopen.domain.trading.market :as market]))

(def ^:private validation-error-specs
  {:order/size-invalid {:message "Size must be greater than 0."
                        :fields [:size]}
   :order/price-required {:message "Price is required for limit orders."
                          :fields [:price]}
   :order/trigger-required {:message "Trigger price is required for stop/take orders."
                            :fields [:trigger-px]}
   :scale/inputs-invalid {:message "Scale orders need start/end prices and count between 2 and 100."
                          :fields [:scale-start :scale-end :scale-count]}
   :scale/skew-invalid {:message "Scale skew must be greater than 0 and at most 100."
                        :fields []}
   :scale/endpoint-notional-too-small {:message "Scale start/end orders must each be at least 10 in order value."
                                       :fields []}
   :twap/runtime-invalid {:message "TWAP runtime must be between 5 minutes and 7 days."
                          :fields []}
   :twap/order-notional-too-small {:message "A TWAP order must be at least 100 USDC in total order value."
                                   :fields [:size]}
   :twap/trigger-price-invalid {:message "TWAP trigger price must be greater than 0."
                                :fields []}
   :twap/stop-price-invalid {:message "TWAP max/min price must be greater than 0."
                             :fields []}
   :tpsl/tp-trigger-required {:message "TP trigger price is required when TP is enabled."
                              :fields [:tp-trigger]}
   :tpsl/sl-trigger-required {:message "SL trigger price is required when SL is enabled."
                              :fields [:sl-trigger]}
   :spot/insufficient-usdc {:message "Not enough USDC available to buy this size."
                            :fields [:size]}
   :spot/insufficient-base-balance {:message "Not enough balance to sell this size."
                                    :fields [:size]}})

(defn- validation-error [code]
  (when-let [spec (get validation-error-specs code)]
    (assoc spec :code code)))

(defn validation-error-message [error]
  (let [code (cond
               (keyword? error) error
               (map? error) (:code error)
               :else nil)]
    (or (get-in validation-error-specs [code :message])
        "Invalid order form.")))

(defn validation-errors->messages [errors]
  (->> (or errors [])
       (map validation-error-message)
       vec))

(defn- invalid-positive? [value]
  (or (nil? value)
      (<= value 0)))

(defn- require-size-error [{:keys [size]}]
  (cond-> []
    (invalid-positive? size)
    (conj (validation-error :order/size-invalid))))

(defn- require-price-error [{:keys [price]}]
  (cond-> []
    (invalid-positive? price)
    (conj (validation-error :order/price-required))))

(defn- require-trigger-error [{:keys [trigger]}]
  (cond-> []
    (invalid-positive? trigger)
    (conj (validation-error :order/trigger-required))))

(defn- validate-market [parsed]
  (require-size-error parsed))

(defn- validate-limit [parsed]
  (into (require-size-error parsed)
        (require-price-error parsed)))

(defn- validate-stop-market [parsed]
  (into (require-size-error parsed)
        (require-trigger-error parsed)))

(defn- validate-stop-limit [parsed]
  (into (validate-limit parsed)
        (require-trigger-error parsed)))

(defn- validate-take-market [parsed]
  (validate-stop-market parsed))

(defn- validate-take-limit [parsed]
  (validate-stop-limit parsed))

(defn- validate-scale [{:keys [size
                               scale-start
                               scale-end
                               scale-count
                               scale-skew
                               start-notional
                               end-notional]}]
  (cond-> (require-size-error {:size size})
    (or (nil? scale-start)
        (nil? scale-end)
        (not (core/valid-scale-order-count? scale-count)))
    (conj (validation-error :scale/inputs-invalid))

    (not (core/valid-scale-skew? scale-skew))
    (conj (validation-error :scale/skew-invalid))

    (or (nil? start-notional)
        (nil? end-notional)
        (< start-notional core/scale-min-endpoint-notional)
        (< end-notional core/scale-min-endpoint-notional))
    (conj (validation-error :scale/endpoint-notional-too-small))))

(defn- spot-affordability-errors
  "Spot affordability check, applied only to spot markets and only when spot
   balances are loaded (fail-open on missing data so the exchange remains the
   source of truth). Buys require USDC available >= order value; sells require
   base-token available >= size. Fees are not reserved here (lenient by design
   to avoid false rejects); the exchange enforces the precise margin.

   Skipped entirely for unified (portfolio-margin) accounts: there, buying power
   is the unified collateral base (the exchange auto-borrows USDC against
   collateral, and acquiring/selling the borrowed asset is how a borrow is
   repaid), so idle spot-wallet USDC / held base balance is the wrong cap and
   would false-reject legitimate borrow-repaying trades."
  [context form size]
  (or
   (when (and context
              (core/spot-market-context? context)
              (not (market/unified-account-mode? context))
              (number? size)
              (pos? size)
              (seq (get-in context [:spot :clearinghouse-state :balances])))
     (if (= :sell (:side form))
       (when (> size (market/spot-base-available context))
         [(validation-error :spot/insufficient-base-balance)])
       (let [ref-price (market/reference-price context form)
             order-value (when (and (number? ref-price) (pos? ref-price))
                           (* size ref-price))]
         (when (and (number? order-value)
                    (> order-value (market/spot-usdc-available context)))
           [(validation-error :spot/insufficient-usdc)]))))
   []))

(defn- advanced-price-invalid?
  "An optional TWAP price field is invalid only when the user typed something that is not a
   usable positive price. Blank means 'not set' and is always fine."
  [value]
  (and (some? value)
       (seq (str/trim (str value)))
       (invalid-positive? (core/parse-num value))))

(defn- validate-twap
  "TWAP validation checks the runtime window and the order's TOTAL notional.

   It deliberately does NOT check per-clip notional. Since the 2026-08-01 venue upgrade
   Hyperliquid keeps each clip above its own $10 floor by spacing clips further apart, so
   a per-clip check can only produce false rejections -- it used to block a $200 order over
   24 hours that the venue works happily as 20 clips of $10."
  [{:keys [twap-minutes twap-order-notional twap-trigger-px twap-stop-px] :as parsed}]
  (let [runtime-valid? (boolean (core/valid-twap-runtime? twap-minutes))]
    (cond-> (require-size-error parsed)
      (not runtime-valid?)
      (conj (validation-error :twap/runtime-invalid))

      (and (number? twap-order-notional)
           (< twap-order-notional core/twap-min-order-notional))
      (conj (validation-error :twap/order-notional-too-small))

      ;; The advanced settings are optional, so blank is fine -- but a value that was typed
      ;; and cannot be used must say so here. Without this the request builder just fails
      ;; closed and the user gets a disabled submit button with no explanation.
      (advanced-price-invalid? twap-trigger-px)
      (conj (validation-error :twap/trigger-price-invalid))

      (advanced-price-invalid? twap-stop-px)
      (conj (validation-error :twap/stop-price-invalid)))))

(def ^:private type-validator-dispatch
  {:validate/market validate-market
   :validate/limit validate-limit
   :validate/stop-market validate-stop-market
   :validate/stop-limit validate-stop-limit
   :validate/take-market validate-take-market
   :validate/take-limit validate-take-limit
   :validate/scale validate-scale
   :validate/twap validate-twap})

(defn validate-order-form
  ([form]
   (validate-order-form nil form))
  ([context form]
   (let [order-type (core/normalize-order-type (:type form))
         size (core/parse-num (:size form))
         price (core/parse-num (:price form))
         trigger (core/parse-num (:trigger-px form))
         scale-start (core/parse-num (get-in form [:scale :start]))
         scale-end (core/parse-num (get-in form [:scale :end]))
         scale-count (core/parse-num (get-in form [:scale :count]))
         scale-skew (get-in form [:scale :skew])
         scale-sz-decimals (or (get-in context [:market :szDecimals])
                               (:sz-decimals form)
                               (get-in form [:scale :sz-decimals]))
         scale-legs (when (= :scale order-type)
                      (core/scale-order-legs size
                                             scale-count
                                             scale-skew
                                             scale-start
                                             scale-end
                                             {:sz-decimals scale-sz-decimals}))
         start-leg (first scale-legs)
         end-leg (last scale-legs)
         start-notional (when (and (map? start-leg)
                                   (number? (:price start-leg))
                                   (number? (:size start-leg)))
                          (* (:price start-leg) (:size start-leg)))
         end-notional (when (and (map? end-leg)
                                 (number? (:price end-leg))
                                 (number? (:size end-leg)))
                        (* (:price end-leg) (:size end-leg)))
         twap-minutes (core/twap-total-minutes (get-in form [:twap]))
         twap-order-notional (core/twap-order-notional size
                                                       (market/reference-price context form))
         twap-trigger-px (get-in form [:twap :trigger-px])
         twap-stop-px (get-in form [:twap :stop-px])
         tp-enabled? (get-in form [:tp :enabled?])
         sl-enabled? (get-in form [:sl :enabled?])
         tp-trigger (core/parse-num (get-in form [:tp :trigger]))
         sl-trigger (core/parse-num (get-in form [:sl :trigger]))
         parsed {:order-type order-type
                 :size size
                 :price price
                 :trigger trigger
                 :scale-start scale-start
                 :scale-end scale-end
                 :scale-count scale-count
                 :scale-skew scale-skew
                 :start-notional start-notional
                 :end-notional end-notional
                 :twap-minutes twap-minutes
                 :twap-order-notional twap-order-notional
                 :twap-trigger-px twap-trigger-px
                 :twap-stop-px twap-stop-px}
         validator-id (core/order-type-validator-id order-type)
         validator-fn (get type-validator-dispatch validator-id validate-limit)
         type-errors (validator-fn parsed)
         tpsl-errors (cond-> []
                       (and tp-enabled? (invalid-positive? tp-trigger))
                       (conj (validation-error :tpsl/tp-trigger-required))

                       (and sl-enabled? (invalid-positive? sl-trigger))
                       (conj (validation-error :tpsl/sl-trigger-required)))
         spot-errors (spot-affordability-errors context form size)]
     (into [] (concat type-errors tpsl-errors spot-errors)))))

(def ^:private required-field-rank
  {:price 0
   :size 1
   :trigger-px 2
   :scale-start 3
   :scale-end 4
   :scale-count 5
   :twap-runtime 6
   :tp-trigger 7
   :sl-trigger 8})

(def ^:private required-field-label
  {:price "Price"
   :size "Size"
   :trigger-px "Trigger Price"
   :scale-start "Start Price"
   :scale-end "End Price"
   :scale-count "Total Orders"
   :twap-runtime "Runtime"
   :tp-trigger "TP Trigger"
   :sl-trigger "SL Trigger"})

(defn submit-required-fields
  "Map validation errors to deterministic field labels for submit guidance UI."
  [errors]
  (->> (or errors [])
       (mapcat (fn [error]
                 (let [code (cond
                              (keyword? error) error
                              (map? error) (:code error)
                              :else nil)]
                   (or (get-in validation-error-specs [code :fields]) []))))
       distinct
       (sort-by #(get required-field-rank % 999))
       (map required-field-label)
       (remove nil?)
       vec))
