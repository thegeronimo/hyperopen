(ns hyperopen.schema.contracts.state
  (:require [cljs.spec.alpha :as s]
            [hyperopen.schema.contracts.common :as common]
            [hyperopen.state.trading.order-form-key-policy :as order-form-key-policy]))

(def ^:private hyperunit-lifecycle-keys
  #{:direction
    :asset-key
    :operation-id
    :state
    :status
    :source-tx-confirmations
    :destination-tx-confirmations
    :position-in-withdraw-queue
    :destination-tx-hash
    :state-next-at
    :last-updated-ms
    :error})

(defn- hyperunit-lifecycle-input?
  [value]
  (and (map? value)
       (every? hyperunit-lifecycle-keys (keys value))))

(defn- hyperunit-lifecycle-state?
  [value]
  (and (map? value)
       (= hyperunit-lifecycle-keys (set (keys value)))
       (or (nil? (:direction value))
           (contains? #{:deposit :withdraw} (:direction value)))
       (or (nil? (:asset-key value))
           (keyword? (:asset-key value)))
       (or (nil? (:operation-id value))
           (common/non-empty-string? (:operation-id value)))
       (or (nil? (:state value))
           (keyword? (:state value)))
       (or (nil? (:status value))
           (keyword? (:status value)))
       (or (nil? (:source-tx-confirmations value))
           (common/non-negative-integer-value? (:source-tx-confirmations value)))
       (or (nil? (:destination-tx-confirmations value))
           (common/non-negative-integer-value? (:destination-tx-confirmations value)))
       (or (nil? (:position-in-withdraw-queue value))
           (common/non-negative-integer-value? (:position-in-withdraw-queue value)))
       (or (nil? (:destination-tx-hash value))
           (common/non-empty-string? (:destination-tx-hash value)))
       (or (nil? (:state-next-at value))
           (common/non-negative-integer-value? (:state-next-at value)))
       (or (nil? (:last-updated-ms value))
           (common/non-negative-integer-value? (:last-updated-ms value)))
       (or (nil? (:error value))
           (common/non-empty-string? (:error value)))))

(defn- funding-modal-state?
  [value]
  (and (map? value)
       (contains? value :hyperunit-lifecycle)
       (hyperunit-lifecycle-state? (:hyperunit-lifecycle value))))

(defn- funding-ui-state?
  [value]
  (and (map? value)
       (contains? value :modal)
       (funding-modal-state? (:modal value))))

(s/def ::hyperunit-lifecycle-input hyperunit-lifecycle-input?)

(s/def ::coin ::common/non-empty-string)
(s/def ::symbol ::common/non-empty-string)
(s/def ::active-asset (s/nilable ::common/non-empty-string))
(s/def ::active-market
  (s/nilable
   (s/keys :req-un [::coin ::symbol])))

(s/def ::asset-selector-state
  (s/and map?
         #(vector? (:markets %))
         #(map? (:market-by-key %))
         #(set? (:favorites %))
         #(set? (:loaded-icons %))
         #(set? (:missing-icons %))))

(s/def ::wallet-state
  (s/and map?
         #(map? (:agent %))))

(s/def ::websocket-state map?)
(s/def ::websocket-ui-state map?)

(s/def ::router-state
  (s/and map?
         #(string? (:path %))))

(s/def ::order-form-state
  (s/and map?
         #(not-any? order-form-key-policy/ui-owned-order-form-key? (keys %))
         #(not-any? order-form-key-policy/legacy-order-form-compatibility-key? (keys %))))

(s/def ::order-form-ui-state
  (s/and map?
         #(= order-form-key-policy/order-form-ui-state-keys (set (keys %)))
         #(boolean? (:pro-order-type-dropdown-open? %))
         #(boolean? (:margin-mode-dropdown-open? %))
         #(boolean? (:leverage-popover-open? %))
         #(boolean? (:size-unit-dropdown-open? %))
         #(boolean? (:tpsl-unit-dropdown-open? %))
         #(boolean? (:tif-dropdown-open? %))
         #(boolean? (:price-input-focused? %))
         #(boolean? (:tpsl-panel-open? %))
         #(contains? #{:market :limit :pro} (:entry-mode %))
         #(number? (:ui-leverage %))
         #(number? (:leverage-draft %))
         #(contains? #{:cross :isolated} (:margin-mode %))
         #(contains? #{:quote :base} (:size-input-mode %))
         #(contains? #{:manual :percent} (:size-input-source %))
         #(string? (:size-display %))))

(s/def ::order-form-runtime-state
  (s/and map?
         #(contains? % :submitting?)
         #(contains? % :error)
         #(boolean? (:submitting? %))
         #(or (nil? (:error %))
              (string? (:error %)))))

(s/def ::funding-ui-state funding-ui-state?)

(s/def ::app-state
  (s/and map?
         #(contains? % :active-asset)
         #(contains? % :active-market)
         #(contains? % :asset-selector)
         #(contains? % :wallet)
         #(contains? % :websocket)
         #(contains? % :websocket-ui)
         #(contains? % :router)
         #(contains? % :order-form)
         #(contains? % :order-form-ui)
         #(contains? % :order-form-runtime)
         #(contains? % :funding-ui)
         #(s/valid? ::active-asset (:active-asset %))
         #(s/valid? ::active-market (:active-market %))
         #(s/valid? ::asset-selector-state (:asset-selector %))
         #(s/valid? ::wallet-state (:wallet %))
         #(s/valid? ::websocket-state (:websocket %))
         #(s/valid? ::websocket-ui-state (:websocket-ui %))
         #(s/valid? ::router-state (:router %))
         #(s/valid? ::order-form-state (:order-form %))
         #(s/valid? ::order-form-ui-state (:order-form-ui %))
         #(s/valid? ::order-form-runtime-state (:order-form-runtime %))
         #(s/valid? ::funding-ui-state (:funding-ui %))))
