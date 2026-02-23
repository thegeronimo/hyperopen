(ns hyperopen.schema.order-form-contracts
  (:require [cljs.spec.alpha :as s]))

(def ^:private required-vm-keys
  #{:form
    :side
    :type
    :entry-mode
    :pro-dropdown-open?
    :tpsl-panel-open?
    :pro-dropdown-options
    :pro-tab-label
    :controls
    :spot?
    :hip3?
    :read-only?
    :display
    :ui-leverage
    :next-leverage
    :size-percent
    :display-size-percent
    :notch-overlap-threshold
    :size-input-mode
    :size-display
    :price
    :base-symbol
    :quote-symbol
    :scale-preview-lines
    :error
    :submitting?
    :submit})

(def ^:private required-price-keys
  #{:raw :display :focused? :fallback :context})

(def ^:private required-price-context-keys
  #{:label :mid-available?})

(def ^:private required-display-keys
  #{:available-to-trade
    :current-position
    :liquidation-price
    :order-value
    :margin-required
    :slippage
    :fees})

(def ^:private required-display-fees-keys
  #{:effective
    :baseline})

(def ^:private required-scale-preview-keys
  #{:start :end})

(def ^:private required-submit-keys
  #{:form
    :errors
    :required-fields
    :reason
    :error-message
    :tooltip
    :market-price-missing?
    :disabled?})

(def ^:private required-controls-keys
  #{:limit-like?
    :show-limit-like-controls?
    :show-tpsl-toggle?
    :show-tpsl-panel?
    :show-post-only?
    :show-scale-preview?
    :show-liquidation-row?
    :show-slippage-row?})

(def ^:private allowed-transition-keys
  #{:order-form :order-form-ui :order-form-runtime})

(defn- exact-keys? [value expected-keys]
  (= expected-keys (set (keys value))))

(s/def :order-form-vm/form map?)
(s/def :order-form-vm/side keyword?)
(s/def :order-form-vm/type keyword?)
(s/def :order-form-vm/entry-mode keyword?)
(s/def :order-form-vm/pro-dropdown-open? boolean?)
(s/def :order-form-vm/tpsl-panel-open? boolean?)
(s/def :order-form-vm/pro-dropdown-options (s/coll-of keyword? :kind vector?))
(s/def :order-form-vm/pro-tab-label string?)
(s/def :order-form-vm/spot? boolean?)
(s/def :order-form-vm/hip3? boolean?)
(s/def :order-form-vm/read-only? boolean?)
(s/def :order-form-vm/ui-leverage number?)
(s/def :order-form-vm/next-leverage number?)
(s/def :order-form-vm/size-percent number?)
(s/def :order-form-vm/display-size-percent string?)
(s/def :order-form-vm/notch-overlap-threshold number?)
(s/def :order-form-vm/size-input-mode keyword?)
(s/def :order-form-vm/size-display string?)
(s/def :order-form-vm/base-symbol string?)
(s/def :order-form-vm/quote-symbol string?)
(s/def :order-form-vm/error (s/nilable string?))
(s/def :order-form-vm/submitting? boolean?)

(s/def :order-form-vm.price-context/label string?)
(s/def :order-form-vm.price-context/mid-available? boolean?)
(s/def :order-form-vm/price-context
  (s/and
   (s/keys :req-un [:order-form-vm.price-context/label
                    :order-form-vm.price-context/mid-available?])
   #(exact-keys? % required-price-context-keys)))

(s/def :order-form-vm.price/raw string?)
(s/def :order-form-vm.price/display string?)
(s/def :order-form-vm.price/focused? boolean?)
(s/def :order-form-vm.price/fallback (s/nilable string?))
(s/def :order-form-vm.price/context :order-form-vm/price-context)
(s/def :order-form-vm/price
  (s/and
   (s/keys :req-un [:order-form-vm.price/raw
                    :order-form-vm.price/display
                    :order-form-vm.price/focused?
                    :order-form-vm.price/fallback
                    :order-form-vm.price/context])
   #(exact-keys? % required-price-keys)))

(s/def :order-form-vm.display/available-to-trade string?)
(s/def :order-form-vm.display/current-position string?)
(s/def :order-form-vm.display/liquidation-price string?)
(s/def :order-form-vm.display/order-value string?)
(s/def :order-form-vm.display/margin-required string?)
(s/def :order-form-vm.display/slippage string?)
(s/def :order-form-vm.display.fees/effective string?)
(s/def :order-form-vm.display.fees/baseline (s/nilable string?))
(s/def :order-form-vm.display/fees
  (s/and
   (s/keys :req-un [:order-form-vm.display.fees/effective
                    :order-form-vm.display.fees/baseline])
   #(exact-keys? % required-display-fees-keys)))
(s/def :order-form-vm/display
  (s/and
   (s/keys :req-un [:order-form-vm.display/available-to-trade
                    :order-form-vm.display/current-position
                    :order-form-vm.display/liquidation-price
                    :order-form-vm.display/order-value
                    :order-form-vm.display/margin-required
                    :order-form-vm.display/slippage
                    :order-form-vm.display/fees])
   #(exact-keys? % required-display-keys)))

(s/def :order-form-vm.scale-preview/start string?)
(s/def :order-form-vm.scale-preview/end string?)
(s/def :order-form-vm/scale-preview-lines
  (s/and
   (s/keys :req-un [:order-form-vm.scale-preview/start
                    :order-form-vm.scale-preview/end])
   #(exact-keys? % required-scale-preview-keys)))

(s/def :order-form-vm.controls/limit-like? boolean?)
(s/def :order-form-vm.controls/show-limit-like-controls? boolean?)
(s/def :order-form-vm.controls/show-tpsl-toggle? boolean?)
(s/def :order-form-vm.controls/show-tpsl-panel? boolean?)
(s/def :order-form-vm.controls/show-post-only? boolean?)
(s/def :order-form-vm.controls/show-scale-preview? boolean?)
(s/def :order-form-vm.controls/show-liquidation-row? boolean?)
(s/def :order-form-vm.controls/show-slippage-row? boolean?)
(s/def :order-form-vm/controls
  (s/and
   (s/keys :req-un [:order-form-vm.controls/limit-like?
                    :order-form-vm.controls/show-limit-like-controls?
                    :order-form-vm.controls/show-tpsl-toggle?
                    :order-form-vm.controls/show-tpsl-panel?
                    :order-form-vm.controls/show-post-only?
                    :order-form-vm.controls/show-scale-preview?
                    :order-form-vm.controls/show-liquidation-row?
                    :order-form-vm.controls/show-slippage-row?])
   #(exact-keys? % required-controls-keys)))

(s/def :order-form-vm.submit/form map?)
(s/def :order-form-vm.submit/errors vector?)
(s/def :order-form-vm.submit/required-fields vector?)
(s/def :order-form-vm.submit/reason (s/nilable keyword?))
(s/def :order-form-vm.submit/error-message (s/nilable string?))
(s/def :order-form-vm.submit/tooltip (s/nilable string?))
(s/def :order-form-vm.submit/market-price-missing? boolean?)
(s/def :order-form-vm.submit/disabled? boolean?)
(s/def :order-form-vm/submit
  (s/and
   (s/keys :req-un [:order-form-vm.submit/form
                    :order-form-vm.submit/errors
                    :order-form-vm.submit/required-fields
                    :order-form-vm.submit/reason
                    :order-form-vm.submit/error-message
                    :order-form-vm.submit/tooltip
                    :order-form-vm.submit/market-price-missing?
                    :order-form-vm.submit/disabled?])
   #(exact-keys? % required-submit-keys)))

(s/def ::order-form-vm
  (s/and
   (s/keys :req-un [:order-form-vm/form
                    :order-form-vm/side
                    :order-form-vm/type
                    :order-form-vm/entry-mode
                    :order-form-vm/pro-dropdown-open?
                    :order-form-vm/tpsl-panel-open?
                    :order-form-vm/pro-dropdown-options
                    :order-form-vm/pro-tab-label
                    :order-form-vm/controls
                    :order-form-vm/spot?
                    :order-form-vm/hip3?
                    :order-form-vm/read-only?
                    :order-form-vm/display
                    :order-form-vm/ui-leverage
                    :order-form-vm/next-leverage
                    :order-form-vm/size-percent
                    :order-form-vm/display-size-percent
                    :order-form-vm/notch-overlap-threshold
                    :order-form-vm/size-input-mode
                    :order-form-vm/size-display
                    :order-form-vm/price
                    :order-form-vm/base-symbol
                    :order-form-vm/quote-symbol
                    :order-form-vm/scale-preview-lines
                    :order-form-vm/error
                    :order-form-vm/submitting?
                    :order-form-vm/submit])
   #(exact-keys? % required-vm-keys)))

(s/def :order-form-transition.runtime/submitting? boolean?)
(s/def :order-form-transition.runtime/error (s/nilable string?))
(s/def :order-form-transition/order-form-runtime
  (s/and
   (s/keys :req-un [:order-form-transition.runtime/submitting?
                    :order-form-transition.runtime/error])
   #(exact-keys? % #{:submitting? :error})))

(s/def ::order-form-transition
  (s/and map?
         #(seq %)
         #(every? (fn [key] (contains? allowed-transition-keys key)) (keys %))
         (fn [transition]
           (or (not (contains? transition :order-form))
               (map? (:order-form transition))))
         (fn [transition]
           (or (not (contains? transition :order-form-ui))
               (map? (:order-form-ui transition))))
         (fn [transition]
           (or (not (contains? transition :order-form-runtime))
               (s/valid? :order-form-transition/order-form-runtime
                         (:order-form-runtime transition))))))

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
