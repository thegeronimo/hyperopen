(ns hyperopen.state.trading
  (:require [clojure.string :as str]
            [hyperopen.api.gateway.orders.commands :as order-commands]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.trading.order-form-state :as order-form-state]))

(def order-types trading-domain/order-types)
(def order-type-spec trading-domain/order-type-spec)
(def advanced-order-types trading-domain/advanced-order-types)
(def limit-like-order-types trading-domain/limit-like-order-types)
(def tif-options trading-domain/tif-options)
(def default-max-slippage-pct trading-domain/default-max-slippage-pct)
(def default-fees trading-domain/default-fees)
(def legacy-scale-skew->number trading-domain/legacy-scale-skew->number)
(def scale-min-order-count trading-domain/scale-min-order-count)
(def scale-max-order-count trading-domain/scale-max-order-count)
(def scale-min-endpoint-notional trading-domain/scale-min-endpoint-notional)

(def parse-num trading-domain/parse-num)
(def clamp-percent trading-domain/clamp-percent)
(def normalize-scale-skew-number trading-domain/normalize-scale-skew-number)
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

(defn default-order-form []
  (order-form-state/default-order-form))

(defn default-order-form-ui []
  (order-form-state/default-order-form-ui))

(def ^:private order-form-ui-flag-keys
  [:pro-order-type-dropdown-open?
   :price-input-focused?
   :tpsl-panel-open?])

(declare normalize-order-form build-order-request)

(defn normalize-order-form-ui [ui]
  (order-form-state/normalize-order-form-ui ui))

(defn effective-order-form-ui
  "Return normalized order-form UI flags with type-based invariants."
  [form ui]
  (let [normalized-form (or form {})
        normalized-ui (normalize-order-form-ui ui)
        order-type (normalize-order-type (:type normalized-form))]
    (cond-> normalized-ui
      (not (limit-like-type? order-type)) (assoc :price-input-focused? false)
      (= :scale order-type) (assoc :tpsl-panel-open? false))))

(defn order-form-ui-state
  "Return effective UI flags for order form from :order-form-ui with legacy fallback."
  [state]
  (let [legacy-flags (select-keys (:order-form state) order-form-ui-flag-keys)
        ui-state (merge legacy-flags (:order-form-ui state))
        normalized-form (normalize-order-form state (:order-form state))]
    (effective-order-form-ui normalized-form ui-state)))

(defn market-identity [state]
  (trading-domain/market-identity {:active-asset (:active-asset state)
                                   :market (:active-market state)}))

(defn- active-clearinghouse-state [state]
  (let [dex (get-in state [:active-market :dex])]
    (if (and (string? dex) (seq dex))
      (get-in state [:perp-dex-clearinghouse dex])
      (get-in state [:webdata2 :clearinghouseState]))))

(defn- trading-context [state]
  (let [active-asset (:active-asset state)
        streamed-mark (get-in state [:active-assets :contexts active-asset :mark])]
    {:active-asset active-asset
     :asset-idx (get-in state [:asset-contexts (keyword active-asset) :idx])
     :orderbook (get-in state [:orderbooks active-asset])
     :market (cond-> (or (:active-market state) {})
               (some? streamed-mark) (assoc :streamed-mark streamed-mark))
     :account (:account state)
     :spot (:spot state)
     :clearinghouse (or (active-clearinghouse-state state) {})}))

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

(defn size-from-percent [state form percent]
  (trading-domain/size-from-percent (trading-context state) form percent))

(defn percent-from-size [state form size]
  (trading-domain/percent-from-size (trading-context state) form size))

(defn apply-size-percent [state form percent]
  (trading-domain/apply-size-percent (trading-context state) form percent))

(defn sync-size-from-percent [state form]
  (trading-domain/sync-size-from-percent (trading-context state) form))

(defn sync-size-percent-from-size [state form]
  (trading-domain/sync-size-percent-from-size (trading-context state) form))

(defn normalize-order-form [state form]
  (let [context (trading-context state)
        normalized-type (normalize-order-type (:type form))
        entry-mode (normalize-entry-mode (:entry-mode form) normalized-type)
        final-type (case entry-mode
                     :market :market
                     :limit :limit
                     (normalize-pro-order-type normalized-type))
        normalized-form (-> (reduce dissoc form order-form-ui-flag-keys)
                            (order-form-state/normalize-order-form)
                            (assoc :entry-mode entry-mode
                                   :type final-type
                                   :size-display (or (:size-display form) (:size form) "")
                                   :size-percent (clamp-percent (:size-percent form))
                                   :ui-leverage (trading-domain/normalize-ui-leverage context (:ui-leverage form))))]
    (cond-> normalized-form
      (= :scale final-type) (assoc-in [:tp :enabled?] false)
      (= :scale final-type) (assoc-in [:sl :enabled?] false))))

(defn order-summary [state form]
  (let [requested-type (normalize-order-type (:type form))
        normalized-form (-> (normalize-order-form state form)
                            (assoc :requested-type requested-type))]
    (trading-domain/order-summary (trading-context state) normalized-form)))

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
  ([state form {:keys [mode submitting? agent-ready?]
                :or {mode :view
                     submitting? false}}]
   (let [submit-prep (prepare-order-form-for-submit state form)
         prepared-form (:form submit-prep)
         market-price-missing? (:market-price-missing? submit-prep)
         identity (market-identity state)
         spot? (:spot? identity)
         hip3? (:hip3? identity)
         errors (validate-order-form state prepared-form)
         required-fields (submit-required-fields errors)
         request (when (= mode :submit)
                   (build-order-request state prepared-form))
         reason (case mode
                  :submit
                  (cond
                    spot? :spot-read-only
                    hip3? :hip3-read-only
                    market-price-missing? :market-price-missing
                    (seq errors) :validation-errors
                    (nil? request) :request-unavailable
                    (false? agent-ready?) :agent-not-ready
                    :else nil)

                  :view
                  (cond
                    submitting? :submitting
                    spot? :spot-read-only
                    hip3? :hip3-read-only
                    market-price-missing? :market-price-missing
                    (seq errors) :validation-errors
                    :else nil)

                  nil)
         error-message (case reason
                         :spot-read-only "Spot trading is not supported yet."
                         :hip3-read-only "HIP-3 trading is not supported yet."
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
