(ns hyperopen.schema.trading-submit-policy-contracts)

(def ^:private order-type-values
  #{:market :limit :stop-market :stop-limit :take-market :take-limit :scale :twap})

(def ^:private side-values
  #{:buy :sell})

(def ^:private validation-code-values
  #{:order/size-invalid
    :order/price-required
    :order/trigger-required
    :scale/inputs-invalid
    :scale/skew-invalid
    :scale/endpoint-notional-too-small
    :twap/runtime-invalid
    :twap/order-notional-too-small
    :twap/trigger-price-invalid
    :twap/stop-price-invalid
    :tpsl/tp-trigger-required
    :tpsl/sl-trigger-required
    :spot/insufficient-usdc
    :spot/insufficient-base-balance})

(def ^:private submit-reason-values
  #{nil
    :submitting
    :spectate-mode-read-only
    :spot-read-only
    :market-price-missing
    :validation-errors
    :request-unavailable
    :agent-not-ready})

(def ^:private prepare-result-keys
  #{:type :side :size :price :market-price-missing?})

(def ^:private validation-summary-keys
  #{:error-codes :required-fields})

(def ^:private submit-policy-keys
  #{:type
    :side
    :size
    :price
    :market-price-missing?
    :error-codes
    :required-fields
    :reason
    :disabled?
    :error-message
    :request-present?})

(defn- exact-keys?
  [value expected]
  (and (map? value)
       (= expected (set (keys value)))))

(defn- vector-of?
  [predicate value]
  (and (vector? value)
       (every? predicate value)))

(defn prepare-result-projection
  [result]
  {:type (get-in result [:form :type])
   :side (get-in result [:form :side])
   :size (or (get-in result [:form :size]) "")
   :price (or (get-in result [:form :price]) "")
   :market-price-missing? (boolean (:market-price-missing? result))})

(defn validation-summary-projection
  [{:keys [errors required-fields]}]
  {:error-codes (->> (or errors [])
                     (keep :code)
                     vec)
   :required-fields (vec (or required-fields []))})

(defn submit-policy-projection
  [policy]
  {:type (get-in policy [:form :type])
   :side (get-in policy [:form :side])
   :size (or (get-in policy [:form :size]) "")
   :price (or (get-in policy [:form :price]) "")
   :market-price-missing? (boolean (:market-price-missing? policy))
   :error-codes (->> (or (:errors policy) [])
                     (keep :code)
                     vec)
   :required-fields (vec (or (:required-fields policy) []))
   :reason (:reason policy)
   :disabled? (boolean (:disabled? policy))
   :error-message (:error-message policy)
   :request-present? (some? (:request policy))})

(defn prepare-result-projection-valid?
  [projection]
  (and (exact-keys? projection prepare-result-keys)
       (contains? order-type-values (:type projection))
       (contains? side-values (:side projection))
       (string? (:size projection))
       (string? (:price projection))
       (boolean? (:market-price-missing? projection))))

(defn validation-summary-projection-valid?
  [projection]
  (and (exact-keys? projection validation-summary-keys)
       (vector-of? #(contains? validation-code-values %) (:error-codes projection))
       (vector-of? string? (:required-fields projection))))

(defn submit-policy-projection-valid?
  [projection]
  (and (exact-keys? projection submit-policy-keys)
       (contains? order-type-values (:type projection))
       (contains? side-values (:side projection))
       (string? (:size projection))
       (string? (:price projection))
       (boolean? (:market-price-missing? projection))
       (vector-of? #(contains? validation-code-values %) (:error-codes projection))
       (vector-of? string? (:required-fields projection))
       (contains? submit-reason-values (:reason projection))
       (boolean? (:disabled? projection))
       (or (nil? (:error-message projection))
           (string? (:error-message projection)))
       (boolean? (:request-present? projection))))

(defn assert-prepare-result-projection!
  [projection context]
  (when-not (prepare-result-projection-valid? projection)
    (throw (js/Error.
            (str "prepare result projection contract validation failed. "
                 "context=" (pr-str context)
                 " projection=" (pr-str projection)))))
  projection)

(defn assert-validation-summary-projection!
  [projection context]
  (when-not (validation-summary-projection-valid? projection)
    (throw (js/Error.
            (str "validation summary projection contract validation failed. "
                 "context=" (pr-str context)
                 " projection=" (pr-str projection)))))
  projection)

(defn assert-submit-policy-projection!
  [projection context]
  (when-not (submit-policy-projection-valid? projection)
    (throw (js/Error.
            (str "submit policy projection contract validation failed. "
                 "context=" (pr-str context)
                 " projection=" (pr-str projection)))))
  projection)
