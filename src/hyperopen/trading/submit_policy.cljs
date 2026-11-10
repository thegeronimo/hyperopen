(ns hyperopen.trading.submit-policy
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.domain.trading.market :as trading-market]
            [hyperopen.trading.order-form-state :as order-form-state]
            [hyperopen.trading.vol-range-draft :as vol-range-draft]))

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

(defn- outcome-context?
  [trading-context]
  (trading-market/outcome-market-context? trading-context))

(defn prepare-order-form-for-submit
  "Return a prepared submit form and market-price availability flag.
   Expects a reduced trading context and an already-normalized order form."
  [trading-context normalized-form]
  (let [normalized-form* (or normalized-form {})
        market-form (when (= :market (:type normalized-form*))
                      (trading-domain/apply-market-price trading-context normalized-form*))
        form-with-market (or market-form normalized-form*)
        ;; An outcome limit order trades at the price the trader typed or not
        ;; at all; it never borrows the book midpoint behind a blank field.
        form* (if (and (trading-domain/limit-like-type? (:type form-with-market))
                       (str/blank? (:price form-with-market))
                       (not (outcome-context? trading-context)))
                (if-let [fallback-price (trading-domain/effective-limit-price-string
                                         trading-context
                                         form-with-market)]
                  (assoc form-with-market :price fallback-price)
                  form-with-market)
                form-with-market)]
    {:form form*
     :market-price-missing? (and (= :market (:type normalized-form*))
                                 (nil? market-form))}))

(defn outcome-order-message
  "Plain reason an outcome order cannot go yet, or nil. Checks, in order: a
   limit price strictly inside 0..1, a market order the side's book forbids
   (`market-orders-off?`, from `book/market-orders-off?`), the stale-price
   confirmation, whole shares, shares held for a sale (every account mode;
   nothing loaded means nothing held) and, in classic mode with balances
   loaded, USDC for a buy. The ticket's submit gate reads this, so no other
   path can send what the ticket would refuse."
  [trading-context prepared-form {:keys [confirmation-required? market-orders-off?]}]
  (when (outcome-context? trading-context)
    (let [form (or prepared-form {})
          limit? (= :limit (:type form))
          price (trading-domain/parse-num (:price form))
          size (trading-domain/parse-num (:size form))
          balances-loaded? (seq (get-in trading-context [:spot :clearinghouse-state :balances]))
          classic? (not (trading-market/unified-account-mode? trading-context))
          sell? (= :sell (:side form))
          ref-price (trading-market/reference-price trading-context form)]
      (cond
        (and limit? (not (number? price)))
        "Enter a limit price"

        (and limit? (not (< 0 price 1)))
        "Limit price must be between 0¢ and 100¢"

        (and (= :market (:type form)) market-orders-off?)
        "Market orders are off for this side; set a limit price"

        (and confirmation-required? (not (true? (:outcome-stale-confirmed? form))))
        "Confirm that these prices may be stale"

        (and (number? size) (pos? size) (not= size (js/Math.floor size)))
        "Outcome orders are whole shares"

        (and sell? (number? size) (pos? size)
             (> size (:available (trading-market/outcome-side-shares trading-context
                                                                     (:active-asset trading-context)))))
        "You hold fewer shares than that"

        (and balances-loaded? classic? (not sell?) (number? size) (number? ref-price)
             (> (* size ref-price) (trading-market/spot-usdc-available trading-context)))
        "Not enough USDC for this order"

        :else nil))))

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
            outcome-confirmation-required?
            outcome-market-orders-off?
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
         outcome-message (outcome-order-message trading-context
                                                prepared-form
                                                {:confirmation-required? outcome-confirmation-required?
                                                 :market-orders-off? outcome-market-orders-off?})
         ;; Vol Range A0 is preview-only (VR-24): never build a request.
         vol-range? (and (= :scale (:type normalized-form))
                         (= :volatility-range (vol-range-draft/scale-mode normalized-form)))
         request (when (and (= mode :submit)
                            (not vol-range?)
                            (fn? request-builder))
                   (request-builder prepared-form))
         reason (case mode
                  :submit
                  (cond
                    vol-range? :vol-range-preview-only
                    (seq spectate-mode-message) :spectate-mode-read-only
                    ;; An outcome's own reason (e.g. market orders off on a
                    ;; one-sided book) says more than a missing market price.
                    outcome-message :outcome-order-blocked
                    market-price-missing? :market-price-missing
                    (seq errors) :validation-errors
                    (nil? request) :request-unavailable
                    (false? agent-ready?) :agent-not-ready
                    :else nil)

                  :view
                  (cond
                    vol-range? :vol-range-preview-only
                    submitting? :submitting
                    (seq spectate-mode-message) :spectate-mode-read-only
                    ;; An outcome's own reason (e.g. market orders off on a
                    ;; one-sided book) says more than a missing market price.
                    outcome-message :outcome-order-blocked
                    market-price-missing? :market-price-missing
                    (seq errors) :validation-errors
                    :else nil)

                  nil)
         error-message (case reason
                         :vol-range-preview-only "Placement isn't available yet."
                         :spectate-mode-read-only spectate-mode-message
                         :market-price-missing "Market price unavailable. Load order book first."
                         :outcome-order-blocked outcome-message
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
