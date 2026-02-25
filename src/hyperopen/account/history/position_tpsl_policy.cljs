(ns hyperopen.account.history.position-tpsl-policy
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-tpsl-state :as position-tpsl-state]
            [hyperopen.domain.trading :as trading-domain]))

(defn- parse-num [value]
  (trading-domain/parse-num value))

(defn position-side [szi]
  (let [size-num (parse-num szi)]
    (cond
      (and (number? size-num) (neg? size-num)) :short
      (and (number? size-num) (pos? size-num)) :long
      :else :flat)))

(defn side->order-side [side]
  (case side
    :short :sell
    :buy))

(defn absolute-position-size [szi]
  (let [size-num (parse-num szi)]
    (if (number? size-num)
      (js/Math.abs size-num)
      0)))

(defn calculate-mark-price [position]
  (or (parse-num (:markPx position))
      (parse-num (:markPrice position))
      (parse-num (:entryPx position))
      0))

(defn extract-leverage [position]
  (or (parse-num (get-in position [:leverage :value]))
      (parse-num (:leverage position))
      0))

(defn positive-number? [value]
  (and (number? value) (pos? value)))

(defn- non-negative-number? [value]
  (and (number? value) (>= value 0)))

(def ^:private max-configure-size-percent 100)

(defn clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn full-position-size-input
  [modal]
  (let [size (parse-num (:position-size modal))]
    (if (positive-number? size)
      (trading-domain/number->clean-string size 8)
      "")))

(defn default-size-percent-input
  [modal]
  (if (positive-number? (parse-num (:position-size modal)))
    "100"
    "0"))

(defn size->percent
  [size position-size]
  (when (and (number? size)
             (positive-number? position-size))
    (clamp (* 100 (/ size position-size))
           0
           max-configure-size-percent)))

(defn percent->size
  [percent position-size]
  (when (and (number? percent)
             (positive-number? position-size))
    (* position-size
       (/ (clamp percent 0 max-configure-size-percent)
          100))))

(defn parsed-inputs [modal]
  (let [tp-trigger (parse-num (:tp-price modal))
        sl-trigger (parse-num (:sl-price modal))
        tp-limit (parse-num (:tp-limit modal))
        sl-limit (parse-num (:sl-limit modal))
        position-size (or (parse-num (:position-size modal)) 0)
        configured-size (parse-num (:size-input modal))
        configure-amount? (position-tpsl-state/bool (:configure-amount? modal))
        active-size (if configure-amount? configured-size position-size)
        tp-enabled? (positive-number? tp-trigger)
        sl-enabled? (positive-number? sl-trigger)]
    {:tp-trigger tp-trigger
     :sl-trigger sl-trigger
     :tp-limit tp-limit
     :sl-limit sl-limit
     :position-size position-size
     :active-size active-size
     :configure-amount? configure-amount?
     :limit-price? (position-tpsl-state/bool (:limit-price? modal))
     :tp-enabled? tp-enabled?
     :sl-enabled? sl-enabled?}))

(defn preview-submit-label [modal]
  (let [{:keys [tp-enabled? sl-enabled?]} (parsed-inputs modal)]
    (cond
      (and tp-enabled? sl-enabled?) "Place TP/SL Orders"
      tp-enabled? "Place TP Order"
      sl-enabled? "Place SL Order"
      :else "Place Order")))

(defmulti ^:private validation-rule-error
  (fn [rule _ctx] rule))

(defmethod validation-rule-error :modal-open
  [_ {:keys [modal]}]
  (when-not (position-tpsl-state/open? modal)
    "Place Order"))

(defmethod validation-rule-error :active-size
  [_ {:keys [active-size]}]
  (when-not (positive-number? active-size)
    "Place Order"))

(defmethod validation-rule-error :reduce-size
  [_ {:keys [active-size position-size]}]
  (when (> active-size position-size)
    "Reduce Too Large"))

(defmethod validation-rule-error :trigger-required
  [_ {:keys [tp-enabled? sl-enabled?]}]
  (when (and (not tp-enabled?) (not sl-enabled?))
    "Place Order"))

(defmethod validation-rule-error :tp-trigger-direction
  [_ {:keys [tp-enabled? mark-price side tp-trigger]}]
  (when (and tp-enabled? (positive-number? mark-price))
    (case side
      :long (when (< tp-trigger mark-price) "Take Profit Price Too Low")
      :short (when (> tp-trigger mark-price) "Take Profit Price Too High")
      nil)))

(defmethod validation-rule-error :sl-trigger-direction
  [_ {:keys [sl-enabled? mark-price side sl-trigger]}]
  (when (and sl-enabled? (positive-number? mark-price))
    (case side
      :long (when (> sl-trigger mark-price) "Stop Loss Price Too High")
      :short (when (< sl-trigger mark-price) "Stop Loss Price Too Low")
      nil)))

(defmethod validation-rule-error :limit-required
  [_ {:keys [limit-price? tp-enabled? sl-enabled? tp-limit sl-limit]}]
  (when (and limit-price?
             (or (and tp-enabled? (not (positive-number? tp-limit)))
                 (and sl-enabled? (not (positive-number? sl-limit)))))
    "Limit Price Must Be Set"))

(defmethod validation-rule-error :tp-limit-direction
  [_ {:keys [limit-price? tp-enabled? side tp-limit tp-trigger]}]
  (when (and limit-price? tp-enabled? (> tp-limit tp-trigger))
    (case side
      :long "Take Profit Limit Price Too High"
      :short "Take Profit Limit Price Too Low"
      nil)))

(defmethod validation-rule-error :sl-limit-direction
  [_ {:keys [limit-price? sl-enabled? side sl-limit sl-trigger]}]
  (when (and limit-price? sl-enabled? (< sl-limit sl-trigger))
    (case side
      :long "Stop Loss Limit Price Too Low"
      :short "Stop Loss Price Too Low"
      nil)))

(defmethod validation-rule-error :default
  [_ _]
  nil)

(def ^:private validation-rule-order
  [:modal-open
   :active-size
   :reduce-size
   :trigger-required
   :tp-trigger-direction
   :sl-trigger-direction
   :limit-required
   :tp-limit-direction
   :sl-limit-direction])

(defn- validation-error-message
  [ctx]
  (some #(validation-rule-error % ctx) validation-rule-order))

(defn validate-modal [modal]
  (let [ctx (assoc (parsed-inputs modal)
                   :modal modal
                   :side (:position-side modal)
                   :mark-price (or (parse-num (:mark-price modal))
                                   (parse-num (:entry-price modal))
                                   0))
        label (preview-submit-label modal)]
    (if-let [error-message (validation-error-message ctx)]
      {:is-ok false
       :display-message error-message}
      {:is-ok true
       :display-message label})))

(defn- close-enough?
  [x y]
  (< (js/Math.abs (- x y)) 0.00000001))

(defn- base-margin-basis
  [modal]
  (let [margin-used (parse-num (:margin-used modal))
        position-value (parse-num (:position-value modal))
        leverage (parse-num (:leverage modal))]
    (cond
      (positive-number? margin-used) margin-used
      (and (positive-number? position-value)
           (positive-number? leverage)) (/ position-value leverage)
      (positive-number? position-value) position-value
      :else nil)))

(defn- active-margin-basis
  [modal]
  (let [base (base-margin-basis modal)
        {:keys [active-size position-size]} (parsed-inputs modal)]
    (when (and (positive-number? base)
               (positive-number? active-size))
      (if (positive-number? position-size)
        (* base (/ active-size position-size))
        base))))

(defn- base-position-value-basis
  [modal]
  (let [position-value (parse-num (:position-value modal))
        position-size (parse-num (:position-size modal))
        entry-price (parse-num (:entry-price modal))]
    (cond
      (positive-number? position-value) position-value
      (and (positive-number? position-size)
           (positive-number? entry-price)) (* position-size entry-price)
      :else nil)))

(defn- active-position-value-basis
  [modal]
  (let [base (base-position-value-basis modal)
        {:keys [active-size position-size]} (parsed-inputs modal)]
    (when (and (positive-number? base)
               (positive-number? active-size))
      (if (positive-number? position-size)
        (* base (/ active-size position-size))
        base))))

(defn- normalize-percent-mode
  [mode]
  (let [normalized (position-tpsl-state/normalize-pnl-input-mode mode)]
    (if (position-tpsl-state/percent-pnl-input-mode? normalized)
      normalized
      :roe-percent)))

(defn- active-percent-basis
  [modal percent-mode]
  (case (normalize-percent-mode percent-mode)
    :position-percent (active-position-value-basis modal)
    (active-margin-basis modal)))

(defn active-size [modal]
  (let [{:keys [active-size]} (parsed-inputs modal)]
    (if (positive-number? active-size)
      active-size
      0)))

(defn configured-size-percent [modal]
  (let [percent-input (parse-num (:size-percent-input modal))
        position-size (or (parse-num (:position-size modal)) 0)
        configured-size (parse-num (:size-input modal))]
    (cond
      (number? percent-input)
      (clamp percent-input 0 max-configure-size-percent)

      (number? configured-size)
      (or (size->percent configured-size position-size) 0)

      :else
      0)))

(defn estimated-gain-usd [modal]
  (let [entry (parse-num (:entry-price modal))
        size (or (active-size modal) 0)
        tp (parse-num (:tp-price modal))
        side (:position-side modal)]
    (if (and (positive-number? size)
             (positive-number? entry)
             (positive-number? tp))
      (max 0
           (case side
             :short (* size (- entry tp))
             (* size (- tp entry))))
      0)))

(defn estimated-loss-usd [modal]
  (let [entry (parse-num (:entry-price modal))
        size (or (active-size modal) 0)
        sl (parse-num (:sl-price modal))
        side (:position-side modal)]
    (if (and (positive-number? size)
             (positive-number? entry)
             (positive-number? sl))
      (max 0
           (case side
             :short (* size (- sl entry))
             (* size (- entry sl))))
      0)))

(defn- pnl-usd->percent
  [pnl-usd basis]
  (if (and (positive-number? pnl-usd)
           (positive-number? basis))
    (* 100 (/ pnl-usd basis))
    0))

(defn estimated-gain-roe-percent [modal]
  (pnl-usd->percent (estimated-gain-usd modal)
                    (active-margin-basis modal)))

(defn estimated-loss-roe-percent [modal]
  (pnl-usd->percent (estimated-loss-usd modal)
                    (active-margin-basis modal)))

(defn estimated-gain-position-percent [modal]
  (pnl-usd->percent (estimated-gain-usd modal)
                    (active-position-value-basis modal)))

(defn estimated-loss-position-percent [modal]
  (pnl-usd->percent (estimated-loss-usd modal)
                    (active-position-value-basis modal)))

(defn estimated-gain-percent [modal]
  (estimated-gain-roe-percent modal))

(defn estimated-loss-percent [modal]
  (estimated-loss-roe-percent modal))

(defn estimated-gain-percent-for-mode
  [modal pnl-mode]
  (case (normalize-percent-mode pnl-mode)
    :position-percent (estimated-gain-position-percent modal)
    (estimated-gain-roe-percent modal)))

(defn estimated-loss-percent-for-mode
  [modal pnl-mode]
  (case (normalize-percent-mode pnl-mode)
    :position-percent (estimated-loss-position-percent modal)
    (estimated-loss-roe-percent modal)))

(defn valid-size?
  [modal]
  (let [{:keys [active-size position-size]} (parsed-inputs modal)]
    (and (positive-number? active-size)
         (or (close-enough? active-size position-size)
             (< active-size position-size)))))

(defn- pnl->price
  [modal pnl-value mode]
  (let [{:keys [active-size]} (parsed-inputs modal)
        entry (parse-num (:entry-price modal))
        side (:position-side modal)]
    (when (and (non-negative-number? pnl-value)
               (positive-number? active-size)
               (positive-number? entry))
      (let [delta (/ pnl-value active-size)]
        (case [mode side]
          [:tp :long] (+ entry delta)
          [:tp :short] (- entry delta)
          [:sl :long] (- entry delta)
          [:sl :short] (+ entry delta)
          nil)))))

(defn pnl-input->price-text
  [modal raw-value mode]
  (let [raw-text (position-tpsl-state/normalize-input-text raw-value)
        pnl-value (parse-num raw-text)]
    (if (and (not (str/blank? raw-text))
             (number? pnl-value))
      (if-let [price (pnl->price modal pnl-value mode)]
        (trading-domain/number->clean-string price 8)
        "")
      "")))

(defn- pnl-percent->usd
  [modal pnl-percent percent-mode]
  (when (non-negative-number? pnl-percent)
    (when-let [basis (active-percent-basis modal percent-mode)]
      (* basis (/ pnl-percent 100)))))

(defn pnl-percent-input->price-text
  ([modal raw-value mode]
   (pnl-percent-input->price-text modal raw-value mode :roe-percent))
  ([modal raw-value mode percent-mode]
   (let [raw-text (position-tpsl-state/normalize-input-text raw-value)
         percent-value (parse-num raw-text)]
     (if (and (not (str/blank? raw-text))
              (number? percent-value))
       (if-let [pnl-usd (pnl-percent->usd modal percent-value percent-mode)]
         (if-let [price (pnl->price modal pnl-usd mode)]
           (trading-domain/number->clean-string price 8)
           "")
         "")
       ""))))

(defn pnl-mode-unit-token
  [mode]
  (case (position-tpsl-state/normalize-pnl-input-mode mode)
    :roe-percent "%(E)"
    :position-percent "%(P)"
    "$"))

(defn pnl-mode-menu-label
  [mode]
  (case (position-tpsl-state/normalize-pnl-input-mode mode)
    :roe-percent "%(E): percent of margin/equity used (ROE)."
    :position-percent "%(P): percent of position value (notional)."
    "$: profit/loss in USDC."))

(defn pnl-mode-option-label
  [mode]
  (case (position-tpsl-state/normalize-pnl-input-mode mode)
    :roe-percent "%(E)"
    :position-percent "%(P)"
    "$"))

(defn- normalize-pnl-mode-command
  [raw-command]
  (let [as-keyword (cond
                     (keyword? raw-command) raw-command
                     (string? raw-command) (keyword (str/lower-case (str/trim raw-command)))
                     :else nil)]
    (case as-keyword
      :toggle :toggle
      :usd :usd
      :roe-percent :roe-percent
      :position-percent :position-percent
      ;; Legacy command compatibility.
      :percent :roe-percent
      nil)))

(def ^:private pnl-mode-toggle-cycle
  [:usd :roe-percent :position-percent])

(defn- toggle-pnl-input-mode
  [current-mode]
  (let [normalized (position-tpsl-state/normalize-pnl-input-mode current-mode)
        current-index (or (first (keep-indexed (fn [i v]
                                                 (when (= v normalized)
                                                   i))
                                               pnl-mode-toggle-cycle))
                          0)
        next-index (mod (inc current-index) (count pnl-mode-toggle-cycle))]
    (nth pnl-mode-toggle-cycle next-index)))

(defn resolve-next-pnl-input-mode [current-mode raw-command]
  (let [command (normalize-pnl-mode-command raw-command)]
    (case command
      :toggle (toggle-pnl-input-mode current-mode)
      :usd :usd
      :roe-percent :roe-percent
      :position-percent :position-percent
      (position-tpsl-state/normalize-pnl-input-mode current-mode))))
