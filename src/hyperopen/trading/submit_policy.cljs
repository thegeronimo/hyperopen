(ns hyperopen.trading.submit-policy
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.trading.order-form-state :as order-form-state]))

(def ^:private isolated-only-margin-modes
  #{:no-cross :strict-isolated})

(defn- parse-optional-boolean
  [value]
  (cond
    (boolean? value) value
    (string? value) (= "true" (some-> value str/trim str/lower-case))
    :else nil))

(defn- normalize-market-margin-mode
  [value]
  (let [token (cond
                (keyword? value) (name value)
                (string? value) value
                :else nil)
        normalized (some-> token
                           str/trim
                           str/lower-case
                           (str/replace #"[_-]" ""))]
    (case normalized
      "normal" :normal
      "nocross" :no-cross
      "strictisolated" :strict-isolated
      nil)))

(defn cross-margin-allowed?
  "Return true when market metadata allows cross-margin submit state."
  [market]
  (let [market* (or market {})
        only-isolated? (parse-optional-boolean
                        (or (:only-isolated? market*)
                            (:onlyIsolated market*)))
        margin-mode (normalize-market-margin-mode
                     (or (:margin-mode market*)
                         (:marginMode market*)))]
    (not (or (true? only-isolated?)
             (contains? isolated-only-margin-modes margin-mode)))))

(defn effective-margin-mode
  "Normalize UI margin mode and collapse cross to isolated when the market disallows cross."
  [market mode]
  (let [normalized (order-form-state/normalize-margin-mode mode)]
    (if (and (= normalized :cross)
             (not (cross-margin-allowed? market)))
      :isolated
      normalized)))

(defn prepare-order-form-for-submit
  "Return a prepared submit form and market-price availability flag.
   Expects a reduced trading context and an already-normalized order form."
  [trading-context normalized-form]
  (let [normalized-form* (or normalized-form {})
        market-form (when (= :market (:type normalized-form*))
                      (trading-domain/apply-market-price trading-context normalized-form*))
        form-with-market (or market-form normalized-form*)
        form* (if (and (trading-domain/limit-like-type? (:type form-with-market))
                       (str/blank? (:price form-with-market)))
                (if-let [fallback-price (trading-domain/effective-limit-price-string
                                         trading-context
                                         form-with-market)]
                  (assoc form-with-market :price fallback-price)
                  form-with-market)
                form-with-market)]
    {:form form*
     :market-price-missing? (and (= :market (:type normalized-form*))
                                 (nil? market-form))}))

(defn validation-summary
  "Return validation errors and deterministic required-field labels for a prepared form."
  [trading-context prepared-form]
  (let [errors (trading-domain/validate-order-form trading-context prepared-form)]
    {:errors errors
     :required-fields (trading-domain/submit-required-fields errors)}))

(defn submit-policy
  "Return deterministic submit policy for the pure submit kernel.
   `submit-context` must provide:
   - `:trading-context`
   - `:identity`
   - `:spectate-mode-message`
   - optional `:request-builder` pure fn of prepared-form -> request|nil"
  ([submit-context normalized-form]
   (submit-policy submit-context normalized-form {}))
  ([{:keys [trading-context
            identity
            spectate-mode-message
            request-builder]}
    normalized-form
    options]
   (let [{:keys [mode submitting? agent-ready? agent-unavailable-message]
          :or {mode :view}} options
         submit-prep (prepare-order-form-for-submit trading-context normalized-form)
         prepared-form (:form submit-prep)
         market-price-missing? (:market-price-missing? submit-prep)
         {:keys [errors required-fields]} (validation-summary trading-context prepared-form)
         request (when (and (= mode :submit)
                            (fn? request-builder))
                   (request-builder prepared-form))
         reason (case mode
                  :submit
                  (cond
                    (seq spectate-mode-message) :spectate-mode-read-only
                    (:spot? identity) :spot-read-only
                    market-price-missing? :market-price-missing
                    (seq errors) :validation-errors
                    (nil? request) :request-unavailable
                    (false? agent-ready?) :agent-not-ready
                    :else nil)

                  :view
                  (cond
                    submitting? :submitting
                    (seq spectate-mode-message) :spectate-mode-read-only
                    (:spot? identity) :spot-read-only
                    market-price-missing? :market-price-missing
                    (seq errors) :validation-errors
                    :else nil)

                  nil)
         error-message (case reason
                         :spectate-mode-read-only spectate-mode-message
                         :spot-read-only "Spot trading is not supported yet."
                         :market-price-missing "Market price unavailable. Load order book first."
                         :validation-errors (trading-domain/validation-error-message (first errors))
                         :request-unavailable "Select an asset and ensure market data is loaded."
                         :agent-not-ready (or agent-unavailable-message
                                              "Enable trading before submitting orders.")
                         nil)]
     {:form prepared-form
      :request request
      :errors errors
      :required-fields required-fields
      :market-price-missing? market-price-missing?
      :identity identity
      :reason reason
      :disabled? (boolean reason)
      :error-message error-message})))
