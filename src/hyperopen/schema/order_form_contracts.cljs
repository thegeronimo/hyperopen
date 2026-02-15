(ns hyperopen.schema.order-form-contracts
  (:require [cljs.spec.alpha :as s]))

(def ^:private required-vm-keys
  #{:form
    :side
    :type
    :entry-mode
    :pro-dropdown-options
    :order-type-sections
    :spot?
    :hip3?
    :read-only?
    :price
    :display
    :controls
    :submit})

(def ^:private required-price-keys
  #{:raw :display :focused? :fallback :context})

(def ^:private required-price-context-keys
  #{:label :mid-available?})

(def ^:private required-submit-keys
  #{:errors
    :required-fields
    :reason
    :error-message
    :tooltip
    :market-price-missing?
    :disabled?})

(def ^:private required-controls-keys
  #{:show-limit-like-controls?
    :show-tpsl-toggle?
    :show-tpsl-panel?
    :show-post-only?
    :show-scale-preview?
    :show-liquidation-row?
    :show-slippage-row?})

(def ^:private allowed-transition-keys
  #{:order-form :order-form-ui :order-form-runtime})

(defn- map-with-required-keys?
  [value required-keys]
  (and (map? value)
       (every? #(contains? value %) required-keys)))

(defn- price-context-shape?
  [price-context]
  (and (map-with-required-keys? price-context required-price-context-keys)
       (string? (:label price-context))
       (boolean? (:mid-available? price-context))))

(defn- price-shape?
  [price]
  (and (map-with-required-keys? price required-price-keys)
       (string? (:raw price))
       (string? (:display price))
       (boolean? (:focused? price))
       (or (nil? (:fallback price)) (string? (:fallback price)))
       (price-context-shape? (:context price))))

(defn- submit-shape?
  [submit]
  (and (map-with-required-keys? submit required-submit-keys)
       (vector? (:errors submit))
       (vector? (:required-fields submit))
       (or (nil? (:reason submit)) (keyword? (:reason submit)))
       (or (nil? (:error-message submit)) (string? (:error-message submit)))
       (or (nil? (:tooltip submit)) (string? (:tooltip submit)))
       (boolean? (:market-price-missing? submit))
       (boolean? (:disabled? submit))))

(defn- controls-shape?
  [controls]
  (and (map-with-required-keys? controls required-controls-keys)
       (every? true? (map boolean? (vals (select-keys controls required-controls-keys))))))

(defn- order-form-vm-shape?
  [vm]
  (and (map-with-required-keys? vm required-vm-keys)
       (map? (:form vm))
       (keyword? (:side vm))
       (keyword? (:type vm))
       (keyword? (:entry-mode vm))
       (vector? (:pro-dropdown-options vm))
       (vector? (:order-type-sections vm))
       (boolean? (:spot? vm))
       (boolean? (:hip3? vm))
       (boolean? (:read-only? vm))
       (map? (:display vm))
       (price-shape? (:price vm))
       (controls-shape? (:controls vm))
       (submit-shape? (:submit vm))))

(defn- runtime-shape?
  [runtime]
  (and (map? runtime)
       (boolean? (:submitting? runtime))
       (or (nil? (:error runtime))
           (string? (:error runtime)))))

(defn- transition-shape?
  [transition]
  (and (map? transition)
       (seq transition)
       (every? #(contains? allowed-transition-keys %) (keys transition))
       (or (not (contains? transition :order-form))
           (map? (:order-form transition)))
       (or (not (contains? transition :order-form-ui))
           (map? (:order-form-ui transition)))
       (or (not (contains? transition :order-form-runtime))
           (runtime-shape? (:order-form-runtime transition)))))

(s/def ::order-form-vm order-form-vm-shape?)
(s/def ::order-form-transition transition-shape?)

(defn order-form-vm-valid?
  [vm]
  (s/valid? ::order-form-vm vm))

(defn order-form-transition-valid?
  [transition]
  (s/valid? ::order-form-transition transition))

(defn assert-order-form-vm!
  [vm context]
  (when-not (order-form-vm-valid? vm)
    (throw (js/Error.
            (str "order-form VM schema validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str vm)
                 " explain=" (pr-str (s/explain-data ::order-form-vm vm))))))
  vm)

(defn assert-order-form-transition!
  [transition context]
  (when-not (order-form-transition-valid? transition)
    (throw (js/Error.
            (str "order-form transition schema validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str transition)
                 " explain=" (pr-str (s/explain-data ::order-form-transition transition))))))
  transition)
