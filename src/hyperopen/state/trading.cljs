(ns hyperopen.state.trading
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.gateway.orders.commands :as order-commands]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.state.trading.order-form-key-policy :as order-form-key-policy]
            [hyperopen.state.trading.fee-context :as trading-fee-context]
            [hyperopen.trading.order-form-state :as order-form-state]))

(def order-types trading-domain/order-types)
(def order-type-spec trading-domain/order-type-spec)
(def advanced-order-types trading-domain/advanced-order-types)
(def limit-like-order-types trading-domain/limit-like-order-types)
(def tif-options trading-domain/tif-options)
(def default-max-slippage-pct trading-domain/default-max-slippage-pct)
(def default-market-slippage-pct trading-domain/default-market-slippage-pct)
(def default-fees trading-domain/default-fees)
(def legacy-scale-skew->number trading-domain/legacy-scale-skew->number)
(def scale-min-order-count trading-domain/scale-min-order-count)
(def scale-max-order-count trading-domain/scale-max-order-count)
(def scale-min-endpoint-notional trading-domain/scale-min-endpoint-notional)

(def parse-num trading-domain/parse-num)
(def clamp-percent trading-domain/clamp-percent)
(def normalize-scale-skew-number trading-domain/normalize-scale-skew-number)
;; Value-object adapters for canonical order field semantics.
(def order-type-value trading-domain/order-type-value)
(def tif-value trading-domain/tif-value)
(def side-value trading-domain/side-value)
(def price-value trading-domain/price-value)
(def size-value trading-domain/size-value)
(def percent-value trading-domain/percent-value)
(def leverage-value trading-domain/leverage-value)
(def normalize-order-type trading-domain/normalize-order-type)
(def limit-like-type? trading-domain/limit-like-type?)
(def entry-mode-for-type trading-domain/entry-mode-for-type)
(def normalize-entry-mode trading-domain/normalize-entry-mode)
(def normalize-pro-order-type trading-domain/normalize-pro-order-type)
(def validation-error-message trading-domain/validation-error-message)
(def validation-errors->messages trading-domain/validation-errors->messages)
(def submit-required-fields trading-domain/submit-required-fields)
(def order-side->is-buy trading-domain/order-side->is-buy)
(def opposite-side trading-domain/opposite-side)
(def scale-weights trading-domain/scale-weights)
(def scale-preview-boundaries trading-domain/scale-preview-boundaries)
(def normalize-size-input-mode order-form-state/normalize-size-input-mode)
(def normalize-size-input-source order-form-state/normalize-size-input-source)
(def normalize-margin-mode order-form-state/normalize-margin-mode)

(defn default-order-form []
  (order-form-state/default-order-form))

(defn default-order-form-ui []
  (order-form-state/default-order-form-ui))

(defn default-order-form-runtime []
  (order-form-state/default-order-form-runtime))

(declare normalize-order-form
         build-order-request
         market-identity
         market-max-leverage
         cross-margin-allowed?)

(defn order-form-ui-overrides-from-form
  "Extract UI-owned order-form fields from a normalized working form."
  [form]
  (order-form-key-policy/order-form-ui-overrides-from-form form))

(defn persist-order-form
  "Strip deprecated and UI-owned order-form fields from persisted domain draft payloads."
  [form]
  (order-form-key-policy/strip-deprecated-canonical-order-form-keys form))

(defn normalize-order-form-ui [ui]
  (order-form-state/normalize-order-form-ui ui))

(defn normalize-order-form-runtime [runtime]
  (order-form-state/normalize-order-form-runtime runtime))

(defn effective-order-form-ui
  "Return normalized order-form UI flags with type-based invariants."
  [form ui]
  (let [normalized-form (or form {})
        normalized-ui (normalize-order-form-ui ui)
        order-type (normalize-order-type (:type normalized-form))]
    (cond-> normalized-ui
      true (assoc :entry-mode (entry-mode-for-type order-type)
                  :ui-leverage (or (:ui-leverage normalized-form)
                                   (:ui-leverage normalized-ui))
                  :leverage-draft (or (:leverage-draft normalized-ui)
                                      (:ui-leverage normalized-form)
                                      (:ui-leverage normalized-ui))
                  :margin-mode (normalize-margin-mode
                                (or (:margin-mode normalized-form)
                                    (:margin-mode normalized-ui)))
                  :size-input-mode (normalize-size-input-mode
                                    (or (:size-input-mode normalized-form)
                                        (:size-input-mode normalized-ui)))
                  :size-input-source (normalize-size-input-source
                                      (or (:size-input-source normalized-form)
                                          (:size-input-source normalized-ui)))
                  :size-display (if (contains? normalized-form :size-display)
                                  (or (:size-display normalized-form) "")
                                  (or (:size-display normalized-ui) "")))
      (not (limit-like-type? order-type)) (assoc :price-input-focused? false)
      (not (limit-like-type? order-type)) (assoc :tif-dropdown-open? false)
      (= :scale order-type) (assoc :tpsl-panel-open? false
                                   :tpsl-unit-dropdown-open? false)
      (not (:tpsl-panel-open? normalized-ui)) (assoc :tpsl-unit-dropdown-open? false))))

(defn raw-order-form-draft
  "Return persisted order draft map without applying normalization."
  [state]
  (let [form (:order-form state)]
    (if (map? form) form (default-order-form))))

(defn- compose-order-form-with-ui
  [form ui]
  (let [base (or form {})
        entry-mode (or (:entry-mode base) (:entry-mode ui))
        ui-leverage (or (:ui-leverage base) (:ui-leverage ui))
        size-input-mode (or (:size-input-mode base) (:size-input-mode ui))
        size-input-source (or (:size-input-source base) (:size-input-source ui))
        margin-mode (or (:margin-mode base) (:margin-mode ui))
        size-display (if (contains? base :size-display)
                       (:size-display base)
                       (:size-display ui))]
    (assoc base
           :entry-mode entry-mode
           :ui-leverage ui-leverage
           :margin-mode margin-mode
           :size-input-mode size-input-mode
           :size-input-source size-input-source
           :size-display (or size-display ""))))

(defn order-form-draft
  "Return normalized order draft for domain/application reads."
  [state]
  (let [raw-form (raw-order-form-draft state)
        raw-ui (normalize-order-form-ui (:order-form-ui state))
        composed (compose-order-form-with-ui raw-form raw-ui)]
    (normalize-order-form state composed)))

(defn order-form-ui-state
  "Return effective UI flags for order form from :order-form-ui."
  [state]
  (let [ui-state (:order-form-ui state)
        normalized-form (order-form-draft state)
        normalized-ui (merge (normalize-order-form-ui ui-state)
                             (order-form-ui-overrides-from-form normalized-form))
        effective-ui (effective-order-form-ui normalized-form normalized-ui)]
    (cond-> effective-ui
      (not (cross-margin-allowed? state))
      (assoc :margin-mode :isolated
             :margin-mode-dropdown-open? false))))

(defn order-form-runtime-state
  "Return normalized runtime workflow state for order form."
  [state]
  (normalize-order-form-runtime (:order-form-runtime state)))

(defn market-info
  "Return normalized market info required by order-form selectors."
  [state]
  (let [market (or (:active-market state) {})
        identity (market-identity state)]
    (assoc identity
           :sz-decimals (or (:szDecimals market) 4)
           :max-leverage (market-max-leverage state)
           :cross-margin-allowed? (cross-margin-allowed? state)
           :market-type (:market-type market)
           :dex (:dex market))))

(defn market-identity [state]
  (trading-domain/market-identity {:active-asset (:active-asset state)
                                   :market (:active-market state)}))

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
  [state]
  (let [market (or (:active-market state) {})
        only-isolated? (parse-optional-boolean
                        (or (:only-isolated? market)
                            (:onlyIsolated market)))
        margin-mode (normalize-market-margin-mode
                     (or (:margin-mode market)
                         (:marginMode market)))]
    (not (or (true? only-isolated?)
             (contains? isolated-only-margin-modes margin-mode)))))

(defn effective-margin-mode
  [state mode]
  (let [normalized (normalize-margin-mode mode)]
    (if (and (= normalized :cross)
             (not (cross-margin-allowed? state)))
      :isolated
      normalized)))

(defn- active-clearinghouse-state [state]
  (let [dex (get-in state [:active-market :dex])]
    (if (and (string? dex) (seq dex))
      (get-in state [:perp-dex-clearinghouse dex])
      (get-in state [:webdata2 :clearinghouseState]))))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- named-dex-market?
  [market]
  (let [dex (some-> (:dex market) str str/trim)]
    (seq dex)))

(defn- market-asset-id
  [market]
  (let [market* (or market {})
        explicit-asset-id (some parse-int-value
                                [(:asset-id market*)
                                 (:assetId market*)])
        idx (some parse-int-value [(:idx market*)])
        named-dex? (named-dex-market? market*)]
    (or explicit-asset-id
        (when (and (some? idx)
                   (not named-dex?))
          idx))))

(defn- asset-context-idx
  [state active-asset]
  (some parse-int-value
        [(get-in state [:asset-contexts (keyword active-asset) :idx])
         (get-in state [:asset-contexts active-asset :idx])]))

(defn- resolve-trading-asset-idx
  [state]
  (let [active-market (or (:active-market state) {})
        active-asset (:active-asset state)
        market-idx (market-asset-id active-market)
        named-dex? (named-dex-market? active-market)]
    (or market-idx
        (when-not named-dex?
          (asset-context-idx state active-asset)))))

(defn- trading-context
  ([state]
   (trading-context state (trading-fee-context/select-fee-context state)))
  ([state fee-context]
   (let [active-asset (:active-asset state)
         streamed-mark (get-in state [:active-assets :contexts active-asset :mark])
         active-market (or (:active-market state) {})
         market* (cond-> active-market
                   (some? streamed-mark) (assoc :streamed-mark streamed-mark)
                   true (assoc :growth-mode? (boolean (:growth-mode? fee-context))))]
     {:active-asset active-asset
      :asset-idx (resolve-trading-asset-idx state)
      :orderbook (get-in state [:orderbooks active-asset])
      :market market*
      :account (:account state)
      :spot (:spot state)
      :clearinghouse (or (active-clearinghouse-state state) {})})))

(defn market-max-leverage [state]
  (trading-domain/market-max-leverage (trading-context state)))

(defn normalize-ui-leverage [state leverage]
  (trading-domain/normalize-ui-leverage (trading-context state) leverage))

(defn available-to-trade [state]
  (trading-domain/available-to-trade (trading-context state)))

(defn position-for-active-asset [state]
  (trading-domain/position-for-active-asset (trading-context state)))

(defn current-position-summary [state]
  (trading-domain/current-position-summary (trading-context state)))

(defn base-size-string
  "Format canonical base size by truncating to market szDecimals."
  [state value]
  (trading-domain/base-size-string (trading-context state) value))

(defn best-bid-price [state]
  (trading-domain/best-bid-price (trading-context state)))

(defn best-ask-price [state]
  (trading-domain/best-ask-price (trading-context state)))

(defn reference-price [state form]
  (trading-domain/reference-price (trading-context state) form))

(defn mid-price-summary
  "Return deterministic price context for UI rows:
   {:mid-price number|nil :source :mid|:reference|:none}."
  [state form]
  (trading-domain/mid-price-summary (trading-context state) form))

(defn effective-limit-price
  "Return a deterministic fallback price for limit-like order types.
   Prefers mid (bid/ask average), then reference price."
  [state form]
  (trading-domain/effective-limit-price (trading-context state) form))

(defn effective-limit-price-string
  "String representation for deterministic limit fallback price."
  [state form]
  (trading-domain/effective-limit-price-string (trading-context state) form))

(defn mid-price-string
  "String representation for the true midpoint (best bid/ask average).
   Returns nil when midpoint is unavailable."
  [state form]
  (trading-domain/mid-price-string (trading-context state) form))

(defn size-display-for-input-mode
  "Project :size-display from canonical :size based on :size-input-mode."
  [state form]
  (let [normalized-form (normalize-order-form state form)
        mode (normalize-size-input-mode (:size-input-mode normalized-form))
        size (parse-num (:size normalized-form))
        ref-price (reference-price state normalized-form)]
    (cond
      (or (nil? size) (<= size 0))
      ""

      (= mode :base)
      (or (:size normalized-form)
          (base-size-string state size)
          "")

      (and (number? ref-price) (pos? ref-price))
      (trading-domain/number->clean-string (* size ref-price) 2)

      :else
      "")))

(defn sync-size-display-for-input-mode
  "Return form with :size-display synchronized to :size-input-mode."
  [state form]
  (let [mode (normalize-size-input-mode (:size-input-mode form))]
    (assoc form
           :size-input-mode mode
           :size-display (size-display-for-input-mode state
                                                      (assoc form :size-input-mode mode)))))

(defn order-price-policy
  "Return deterministic order-price behavior for view rendering and transitions.
   {:raw-price string
    :display-price string
    :focused? boolean
    :limit-like? boolean
    :fallback-price string|nil
    :capture-on-focus-price string|nil
    :mid-available? boolean
    :context-label string
    :mid-price string|nil}"
  ([state form]
   (order-price-policy state form (order-form-ui-state state)))
  ([state form ui-state]
   (let [normalized-form (normalize-order-form state form)
         raw-price (or (:price normalized-form) "")
         focused? (boolean (:price-input-focused? ui-state))
         limit-like? (limit-like-type? (:type normalized-form))
         fallback-price (when limit-like?
                          (effective-limit-price-string state normalized-form))
         display-price (cond
                         (not (str/blank? raw-price))
                         raw-price

                         (and (not focused?) limit-like?)
                         (or fallback-price "")

                         :else
                         "")
         mid-context (mid-price-summary state normalized-form)
         mid-available? (= :mid (:source mid-context))
         capture-on-focus-price (when (and limit-like?
                                           (str/blank? raw-price)
                                           (seq fallback-price))
                                  fallback-price)]
     {:raw-price raw-price
      :display-price display-price
      :focused? focused?
      :limit-like? limit-like?
      :fallback-price fallback-price
      :capture-on-focus-price capture-on-focus-price
      :mid-available? mid-available?
      :context-label (if mid-available? "Mid" "Ref")
      :mid-price (mid-price-string state normalized-form)})))

(defn size-from-percent [state form percent]
  (trading-domain/size-from-percent (trading-context state) form percent))

(defn percent-from-size [state form size]
  (trading-domain/percent-from-size (trading-context state) form size))

(defn apply-size-percent [state form percent]
  (sync-size-display-for-input-mode
   state
   (trading-domain/apply-size-percent (trading-context state) form percent)))

(defn sync-size-from-percent [state form]
  (sync-size-display-for-input-mode
   state
   (trading-domain/sync-size-from-percent (trading-context state) form)))

(defn sync-size-percent-from-size [state form]
  (sync-size-display-for-input-mode
   state
   (trading-domain/sync-size-percent-from-size (trading-context state) form)))

(defn normalize-order-form [state form]
  (let [context (trading-context state)
        normalized-type (:value (trading-domain/order-type-value (:type form)))
        entry-mode (normalize-entry-mode (:entry-mode form) normalized-type)
        final-type (case entry-mode
                     :market :market
                     :limit :limit
                     (normalize-pro-order-type normalized-type))
        normalized-side (:value (trading-domain/side-value (:side form)))
        normalized-tif (:value (trading-domain/tif-value (:tif form)))
        normalized-percent (:value (trading-domain/percent-value (:size-percent form)))
        normalized-leverage (:value (trading-domain/leverage-value context (:ui-leverage form)))
        normalized-size-input-mode (normalize-size-input-mode (:size-input-mode form))
        normalized-size-input-source (normalize-size-input-source (:size-input-source form))
        stripped-form (order-form-key-policy/strip-legacy-order-form-compatibility-keys form)
        normalized-form (-> stripped-form
                            (order-form-state/normalize-order-form)
                            (assoc :entry-mode entry-mode
                                   :type final-type
                                   :side normalized-side
                                   :tif normalized-tif
                                   :margin-mode (effective-margin-mode state (:margin-mode form))
                                   :size-input-mode normalized-size-input-mode
                                   :size-input-source normalized-size-input-source
                                   :size-display (or (:size-display form) (:size form) "")
                                   :size-percent normalized-percent
                                   :ui-leverage normalized-leverage))]
    (cond-> normalized-form
      (= :scale final-type) (assoc-in [:tp :enabled?] false)
      (= :scale final-type) (assoc-in [:sl :enabled?] false))))

(defn order-summary [state form]
  (let [fee-context (trading-fee-context/select-fee-context state)
        requested-type (normalize-order-type (:type form))
        normalized-form (-> (normalize-order-form state form)
                            (assoc :requested-type requested-type))]
    (trading-domain/order-summary (trading-context state fee-context)
                                  normalized-form
                                  fee-context)))

(defn validate-order-form
  ([form]
   (trading-domain/validate-order-form form))
  ([state form]
   (trading-domain/validate-order-form (trading-context state) form)))

(defn build-scale-orders [asset-idx side total-size start end reduce-only post-only]
  (order-commands/build-scale-orders asset-idx side total-size start end reduce-only post-only))

(defn build-tpsl-orders [asset-idx side form]
  (order-commands/build-tpsl-orders asset-idx side form))

(defn build-order-action
  "Return {:action action :grouping grouping}"
  [state form]
  (order-commands/build-order-action (trading-context state) form))

(defn build-twap-action [state form]
  (order-commands/build-twap-action (trading-context state) form))

(defn best-price [state side]
  (trading-domain/best-price (trading-context state) side))

(defn apply-market-price [state form]
  (trading-domain/apply-market-price (trading-context state) form))

(defn prepare-order-form-for-submit
  "Return a normalized order form suitable for deterministic submit validation.
   {:form prepared-form
    :market-price-missing? boolean}"
  [state form]
  (let [normalized-form (normalize-order-form state form)
        market-form (when (= :market (:type normalized-form))
                      (apply-market-price state normalized-form))
        form-with-market (or market-form normalized-form)
        form* (if (and (limit-like-type? (:type form-with-market))
                       (str/blank? (:price form-with-market)))
                (if-let [fallback-price (effective-limit-price-string state form-with-market)]
                  (assoc form-with-market :price fallback-price)
                  form-with-market)
                form-with-market)]
    {:form form*
     :market-price-missing? (and (= :market (:type normalized-form))
                                 (nil? market-form))}))

(defn submit-policy
  "Return deterministic submit policy for order-form view and submit action.
   mode :view -> use read-only/validation/market-price/submitting gates.
   mode :submit -> use read-only/validation/market-price/request/agent gates.
   {:form prepared-form
    :request request|nil
    :errors vector
    :required-fields vector
    :market-price-missing? boolean
    :identity market-identity-map
    :reason keyword|nil
    :disabled? boolean
    :error-message string|nil}"
  ([state form]
   (submit-policy state form {}))
  ([state form options]
   (let [{:keys [mode submitting? agent-ready?]
          :or {mode :view}} options
         runtime (order-form-runtime-state state)
         submitting? (if (contains? options :submitting?)
                       submitting?
                       (:submitting? runtime))
         submit-prep (prepare-order-form-for-submit state form)
         prepared-form (:form submit-prep)
         market-price-missing? (:market-price-missing? submit-prep)
         identity (market-identity state)
         ghost-mode-message (account-context/mutations-blocked-message state)
         spot? (:spot? identity)
         errors (validate-order-form state prepared-form)
         required-fields (submit-required-fields errors)
         request (when (= mode :submit)
                   (build-order-request state prepared-form))
         reason (case mode
                 :submit
                 (cond
                    (seq ghost-mode-message) :ghost-mode-read-only
                    spot? :spot-read-only
                    market-price-missing? :market-price-missing
                    (seq errors) :validation-errors
                    (nil? request) :request-unavailable
                    (false? agent-ready?) :agent-not-ready
                    :else nil)

                 :view
                 (cond
                    submitting? :submitting
                    (seq ghost-mode-message) :ghost-mode-read-only
                    spot? :spot-read-only
                    market-price-missing? :market-price-missing
                    (seq errors) :validation-errors
                    :else nil)

                  nil)
         error-message (case reason
                         :ghost-mode-read-only ghost-mode-message
                         :spot-read-only "Spot trading is not supported yet."
                         :market-price-missing "Market price unavailable. Load order book first."
                         :validation-errors (validation-error-message (first errors))
                         :request-unavailable "Select an asset and ensure market data is loaded."
                         :agent-not-ready "Enable trading before submitting orders."
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

(defn build-order-request [state form]
  (order-commands/build-order-request (trading-context state) form))
