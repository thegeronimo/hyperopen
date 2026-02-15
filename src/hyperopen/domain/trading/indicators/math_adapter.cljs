(ns hyperopen.domain.trading.indicators.math-adapter
  (:require ["indicatorts" :refer [apo cci cmf cmo dema ema emv fi ichimokuCloud kc macd mfi mi movingLeastSquare movingLinearRegressionUsingLeastSquare obv psar pvo rma rsi stoch tema trix vortex vpt vwap vwma willr]
             :rename {apo js-apo
                      cci js-cci
                      cmf js-cmf
                      cmo js-cmo
                      dema js-dema
                      ema js-ema
                      emv js-emv
                      fi js-fi
                      ichimokuCloud js-ichimoku-cloud
                      kc js-kc
                      macd js-macd
                      mfi js-mfi
                      mi js-mi
                      movingLeastSquare js-moving-least-square
                      movingLinearRegressionUsingLeastSquare js-moving-linear-regression
                      obv js-obv
                      psar js-psar
                      pvo js-pvo
                      rma js-rma
                      rsi js-rsi
                      stoch js-stoch
                      tema js-tema
                      trix js-trix
                      vortex js-vortex
                      vpt js-vpt
                      vwap js-vwap
                      vwma js-vwma
                      willr js-willr}]))

(defn- values->array
  [values]
  (into-array values))

(defn- js-object->clj-map
  [value]
  (js->clj value :keywordize-keys true))

(defn on-balance-volume
  [close-values volume-values]
  (js-obv (values->array close-values)
          (values->array volume-values)))

(defn price-volume-trend
  [close-values volume-values]
  (js-vpt (values->array close-values)
          (values->array volume-values)))

(defn percentage-volume-oscillator
  [volume-values {:keys [fast slow signal]}]
  (js-object->clj-map
   (js-pvo (values->array volume-values)
           #js {:fast fast
                :slow slow
                :signal signal})))

(defn chaikin-money-flow
  [high-values low-values close-values volume-values {:keys [period]}]
  (js-cmf (values->array high-values)
          (values->array low-values)
          (values->array close-values)
          (values->array volume-values)
          #js {:period period}))

(defn chaikin-oscillator
  [high-values low-values close-values volume-values {:keys [fast slow]}]
  (js-object->clj-map
   (js-cmo (values->array high-values)
           (values->array low-values)
           (values->array close-values)
           (values->array volume-values)
           #js {:fast fast
                :slow slow})))

(defn ease-of-movement
  [high-values low-values volume-values {:keys [period]}]
  (js-emv (values->array high-values)
          (values->array low-values)
          (values->array volume-values)
          #js {:period period}))

(defn elders-force-index
  [close-values volume-values {:keys [period]}]
  (js-fi (values->array close-values)
         (values->array volume-values)
         #js {:period period}))

(defn money-flow-index
  [high-values low-values close-values volume-values {:keys [period]}]
  (js-mfi (values->array high-values)
          (values->array low-values)
          (values->array close-values)
          (values->array volume-values)
          #js {:period period}))

(defn absolute-price-oscillator
  [close-values {:keys [fast slow]}]
  (js-apo (values->array close-values)
          #js {:fast fast
               :slow slow}))

(defn stochastic
  [high-values low-values close-values {:keys [k-period d-period]}]
  (js-object->clj-map
   (js-stoch (values->array high-values)
             (values->array low-values)
             (values->array close-values)
             #js {:kPeriod k-period
                  :dPeriod d-period})))

(defn relative-strength-index
  [close-values {:keys [period]}]
  (js-rsi (values->array close-values)
          #js {:period period}))

(defn trix
  [close-values {:keys [period]}]
  (js-trix (values->array close-values)
           #js {:period period}))

(defn williams-r
  [high-values low-values close-values {:keys [period]}]
  (js-willr (values->array high-values)
            (values->array low-values)
            (values->array close-values)
            #js {:period period}))

(defn commodity-channel-index
  [high-values low-values close-values {:keys [period]}]
  (js-cci (values->array high-values)
          (values->array low-values)
          (values->array close-values)
          #js {:period period}))

(defn macd
  [close-values {:keys [fast slow signal]}]
  (js-object->clj-map
   (js-macd (values->array close-values)
            #js {:fast fast
                 :slow slow
                 :signal signal})))

(defn mass-index
  [high-values low-values {:keys [ema-period mi-period]}]
  (js-mi (values->array high-values)
         (values->array low-values)
         #js {:emaPeriod ema-period
              :miPeriod mi-period}))

(defn ichimoku-cloud
  [high-values low-values close-values {:keys [short medium long close]}]
  (js-object->clj-map
   (js-ichimoku-cloud (values->array high-values)
                      (values->array low-values)
                      (values->array close-values)
                      #js {:short short
                           :medium medium
                           :long long
                           :close close})))

(defn parabolic-sar
  [high-values low-values close-values {:keys [step max-value]}]
  (js-object->clj-map
   (js-psar (values->array high-values)
            (values->array low-values)
            (values->array close-values)
            #js {:step step
                 :max max-value})))

(defn vortex
  [high-values low-values close-values {:keys [period]}]
  (js-object->clj-map
   (js-vortex (values->array high-values)
              (values->array low-values)
              (values->array close-values)
              #js {:period period})))

(defn vwap
  [close-values volume-values {:keys [period]}]
  (js-vwap (values->array close-values)
           (values->array volume-values)
           #js {:period period}))

(defn vwma
  [close-values volume-values {:keys [period]}]
  (js-vwma (values->array close-values)
           (values->array volume-values)
           #js {:period period}))

(defn dema
  [close-values {:keys [period]}]
  (js-dema (values->array close-values)
           #js {:period period}))

(defn ema
  [close-values {:keys [period]}]
  (js-ema (values->array close-values)
          #js {:period period}))

(defn tema
  [close-values {:keys [period]}]
  (js-tema (values->array close-values)
           #js {:period period}))

(defn rma
  [close-values {:keys [period]}]
  (js-rma (values->array close-values)
          #js {:period period}))

(defn moving-linear-regression
  [period x-values y-values]
  (js-moving-linear-regression period
                               (values->array x-values)
                               (values->array y-values)))

(defn moving-least-square
  [period x-values y-values]
  (js-object->clj-map
   (js-moving-least-square period
                           (values->array x-values)
                           (values->array y-values))))

(defn keltner-channels
  [high-values low-values close-values {:keys [period]}]
  (js-object->clj-map
   (js-kc (values->array high-values)
          (values->array low-values)
          (values->array close-values)
          #js {:period period})))
