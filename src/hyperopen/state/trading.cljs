(ns hyperopen.state.trading
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.api.gateway.orders.commands :as order-commands]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.domain.trading.market :as trading-market]
            [hyperopen.state.trading.order-form-key-policy :as order-form-key-policy]
            [hyperopen.state.trading.fee-context :as trading-fee-context]
            [hyperopen.trading.order-form-state :as order-form-state]
            [hyperopen.trading.submit-policy :as trading-submit-policy]))

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
(def twap-min-runtime-minutes trading-domain/twap-min-runtime-minutes)
(def twap-max-runtime-minutes trading-domain/twap-max-runtime-minutes)
(def twap-frequency-seconds trading-domain/twap-frequency-seconds)
(def twap-min-suborder-notional trading-domain/twap-min-suborder-notional)

(def parse-num trading-domain/parse-num)
(def clamp-percent trading-domain/clamp-percent)
(def normalize-scale-skew-number trading-domain/normalize-scale-skew-number)
(def split-twap-total-minutes trading-domain/split-twap-total-minutes)
(def twap-total-minutes trading-domain/twap-total-minutes)
(def valid-twap-runtime? trading-domain/valid-twap-runtime?)
(def twap-suborder-count trading-domain/twap-suborder-count)
(def twap-suborder-size trading-domain/twap-suborder-size)
(def twap-suborder-notional trading-domain/twap-suborder-notional)
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
         cross-margin-allowed?
         active-clearinghouse-state-for-market)

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

(defn cross-margin-allowed?
  [state]
  (trading-submit-policy/cross-margin-allowed? (:active-market state)))

(defn effective-margin-mode
  [state mode]
  (trading-submit-policy/effective-margin-mode (:active-market state) mode))

(defn- active-clearinghouse-state [state]
  (active-clearinghouse-state-for-market state (:active-market state)))

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

(defn- normalize-dex-key
  [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn- unwrap-clearinghouse-state
  [clearinghouse-state]
  (let [state* (or clearinghouse-state {})]
    (if (map? (:clearinghouseState state*))
      (:clearinghouseState state*)
      state*)))

(defn- named-dex-clearinghouse-state
  [state dex]
  (let [dex* (normalize-dex-key dex)
        by-dex (or (:perp-dex-clearinghouse state) {})]
    (or (get by-dex dex)
        (when (seq dex*)
          (some (fn [[candidate-dex clearinghouse-state]]
                  (when (= dex* (normalize-dex-key candidate-dex))
                    clearinghouse-state))
                by-dex)))))

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

(defn- resolved-active-market
  [state]
  (let [active-asset (:active-asset state)
        projected-market (:active-market state)
        market-by-key (get-in state [:asset-selector :market-by-key])]
    (cond
      (and (map? projected-market)
           (markets/market-matches-coin? projected-market active-asset))
      projected-market

      (and (map? market-by-key)
           (seq active-asset))
      (or (markets/resolve-or-infer-market-by-coin market-by-key active-asset)
          projected-market)

      :else
      projected-market)))

(defn- active-clearinghouse-state-for-market
  [state market]
  (let [dex (some-> (:dex market) str str/trim)]
    (unwrap-clearinghouse-state
     (if (seq dex)
       (named-dex-clearinghouse-state state dex)
       (get-in state [:webdata2 :clearinghouseState])))))

(defn position-for-market
  [state active-asset market]
  (let [market* (or market (resolved-active-market state))
        context {:active-asset active-asset
                 :market market*
                 :clearinghouse (or (active-clearinghouse-state-for-market state market*) {})}]
    (trading-market/position-for-market context)))

(defn position-for-active-asset [state]
  (position-for-market state (:active-asset state) (resolved-active-market state)))

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
  (trading-submit-policy/prepare-order-form-for-submit (trading-context state)
                                                       (normalize-order-form state form)))

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
   (let [{:keys [mode submitting? agent-ready? agent-unavailable-message]
          :or {mode :view}} options
         runtime (order-form-runtime-state state)
         submitting? (if (contains? options :submitting?)
                       submitting?
                       (:submitting? runtime))
         context (trading-context state)
         identity (market-identity state)
         normalized-form (normalize-order-form state form)]
     (trading-submit-policy/submit-policy
      {:trading-context context
       :identity identity
       :spectate-mode-message (account-context/mutations-blocked-message state)
       :request-builder (partial order-commands/build-order-request context)}
      normalized-form
      {:mode mode
       :submitting? submitting?
       :agent-ready? agent-ready?
       :agent-unavailable-message agent-unavailable-message}))))

(defn build-order-request [state form]
  (order-commands/build-order-request (trading-context state) form))
