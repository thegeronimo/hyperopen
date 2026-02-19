(ns hyperopen.trading.order-form-state
  (:require [hyperopen.domain.trading :as trading-domain]))

(def default-scale-order-count 5)
(def default-scale-skew "1.00")
(def default-ui-leverage 20)
(def default-slippage 0.5)
(def default-twap-minutes 5)
(def default-size-input-mode :quote)
(def default-size-input-source :manual)

(def valid-size-input-modes
  #{:quote :base})

(def valid-size-input-sources
  #{:manual :percent})

(defn normalize-size-input-mode [mode]
  (let [candidate (cond
                    (keyword? mode) mode
                    (string? mode) (keyword mode)
                    :else default-size-input-mode)]
    (if (contains? valid-size-input-modes candidate)
      candidate
      default-size-input-mode)))

(defn normalize-size-input-source [source]
  (let [candidate (cond
                    (keyword? source) source
                    (string? source) (keyword source)
                    :else default-size-input-source)]
    (if (contains? valid-size-input-sources candidate)
      candidate
      default-size-input-source)))

(defn default-order-form []
  {:type :limit
   :side :buy
   :size-percent 0
   :size ""
   :price ""
   :trigger-px ""
   :reduce-only false
   :post-only false
   :tif :gtc
   :slippage default-slippage
   :scale {:start ""
           :end ""
           :count default-scale-order-count
           :skew default-scale-skew}
   :twap {:minutes default-twap-minutes
          :randomize true}
   :tp {:enabled? false
        :trigger ""
        :is-market true
        :limit ""}
   :sl {:enabled? false
        :trigger ""
        :is-market true
        :limit ""}})

(defn default-order-form-ui []
  {:pro-order-type-dropdown-open? false
   :tpsl-panel-open? false
   :price-input-focused? false
   :entry-mode :limit
   :ui-leverage default-ui-leverage
   :size-input-mode default-size-input-mode
   :size-input-source default-size-input-source
   :size-display ""})

(defn normalize-scale-form [scale]
  (let [raw-scale (or scale {})
        raw-skew (:skew raw-scale)
        normalized-skew (cond
                          (string? raw-skew) raw-skew
                          (number? raw-skew) (trading-domain/number->clean-string
                                               (trading-domain/normalize-scale-skew-number raw-skew)
                                               2)
                          (keyword? raw-skew) (trading-domain/number->clean-string
                                                (trading-domain/normalize-scale-skew-number raw-skew)
                                                2)
                          :else default-scale-skew)]
    {:start (or (:start raw-scale) "")
     :end (or (:end raw-scale) "")
     :count (or (:count raw-scale) default-scale-order-count)
     :skew normalized-skew}))

(defn normalize-order-form [form]
  (-> form
      (assoc :scale (normalize-scale-form (:scale form)))))

(defn default-order-form-runtime []
  {:submitting? false
   :error nil})

(defn normalize-order-form-runtime [runtime]
  (assoc (default-order-form-runtime)
         :submitting? (boolean (:submitting? runtime))
         :error (let [error (:error runtime)]
                  (when (and (string? error)
                             (seq error))
                    error))))

(defn normalize-order-form-ui [ui]
  (let [entry-mode (let [candidate (cond
                                     (keyword? (:entry-mode ui)) (:entry-mode ui)
                                     (string? (:entry-mode ui)) (keyword (:entry-mode ui))
                                     :else :limit)]
                     (if (contains? #{:market :limit :pro} candidate)
                       candidate
                       :limit))
        parsed-leverage (trading-domain/parse-num (:ui-leverage ui))
        normalized-leverage (if (number? parsed-leverage)
                              (-> parsed-leverage js/Math.round int (max 1))
                              default-ui-leverage)
        size-input-mode (normalize-size-input-mode (:size-input-mode ui))
        size-input-source (normalize-size-input-source (:size-input-source ui))]
    (assoc (default-order-form-ui)
           :pro-order-type-dropdown-open? (boolean (:pro-order-type-dropdown-open? ui))
           :price-input-focused? (boolean (:price-input-focused? ui))
           :tpsl-panel-open? (boolean (:tpsl-panel-open? ui))
           :entry-mode entry-mode
           :ui-leverage normalized-leverage
           :size-input-mode size-input-mode
           :size-input-source size-input-source
           :size-display (str (or (:size-display ui) "")))))
