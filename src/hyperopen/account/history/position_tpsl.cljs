(ns hyperopen.account.history.position-tpsl
  (:require [clojure.string :as str]
            [hyperopen.api.gateway.orders.commands :as order-commands]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.views.account-info.projections :as projections]))

(defn default-modal-state []
  {:open? false
   :position-key nil
   :anchor nil
   :coin nil
   :dex nil
   :position-side nil
   :entry-price 0
   :mark-price 0
   :position-size 0
   :position-value 0
   :margin-used 0
   :leverage 0
   :size-input ""
   :size-percent-input ""
   :configure-amount? false
   :limit-price? false
   :tp-price ""
   :tp-limit ""
   :sl-price ""
   :sl-limit ""
   :tp-gain-mode :usd
   :sl-loss-mode :usd
   :submitting? false
   :error nil})

(defn open? [modal]
  (boolean (:open? modal)))

(defn- parse-num [value]
  (trading-domain/parse-num value))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- normalize-display-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(defn- normalize-anchor
  [anchor]
  (when (map? anchor)
    (let [normalized (reduce (fn [acc k]
                               (if-let [n (parse-num (get anchor k))]
                                 (assoc acc k n)
                                 acc))
                             {}
                             anchor-keys)]
      (when (seq normalized)
        normalized))))

(defn- bool [value]
  (boolean value))

(defn- position-side [szi]
  (let [size-num (parse-num szi)]
    (cond
      (and (number? size-num) (neg? size-num)) :short
      (and (number? size-num) (pos? size-num)) :long
      :else :flat)))

(defn- side->order-side [side]
  (case side
    :short :sell
    :buy))

(defn- absolute-position-size [szi]
  (let [size-num (parse-num szi)]
    (if (number? size-num)
      (js/Math.abs size-num)
      0)))

(defn- calculate-mark-price [position]
  (or (parse-num (:markPx position))
      (parse-num (:markPrice position))
      (parse-num (:entryPx position))
      0))

(defn- extract-leverage [position]
  (or (parse-num (get-in position [:leverage :value]))
      (parse-num (:leverage position))
      0))

(defn- positive-number? [value]
  (and (number? value) (pos? value)))

(defn- non-negative-number? [value]
  (and (number? value) (>= value 0)))

(def ^:private pnl-input-mode-default :usd)

(defn- normalize-pnl-input-mode [value]
  (let [as-keyword (cond
                     (keyword? value) value
                     (string? value) (keyword (str/lower-case (str/trim value)))
                     :else nil)]
    (if (= as-keyword :percent)
      :percent
      pnl-input-mode-default)))

(defn tp-gain-mode [modal]
  (normalize-pnl-input-mode (:tp-gain-mode modal)))

(defn sl-loss-mode [modal]
  (normalize-pnl-input-mode (:sl-loss-mode modal)))

(defn- close-enough?
  [x y]
  (< (js/Math.abs (- x y)) 0.00000001))

(defn- candidate-market?
  [market coin dex]
  (let [coin* (normalize-display-text coin)
        dex* (normalize-display-text dex)
        market-coin* (normalize-display-text (:coin market))
        market-dex* (normalize-display-text (:dex market))]
    (and (= :perp (:market-type market))
         (= coin* market-coin*)
         (= (or dex* "")
            (or market-dex* "")))))

(defn- resolve-market-by-coin-and-dex [market-by-key coin dex]
  (let [markets* (vals (or market-by-key {}))
        exact (some #(when (candidate-market? % coin dex) %) markets*)
        fallback (markets/resolve-market-by-coin market-by-key coin)]
    (or exact fallback)))

(defn- resolve-market-asset-id [market]
  (or (some parse-int-value
            [(:asset-id market)
             (:assetId market)])
      (let [idx (parse-int-value (:idx market))
            dex (normalize-display-text (:dex market))]
        (when (and (number? idx)
                   (or (nil? dex) (= "" dex)))
          idx))))

(def ^:private max-configure-size-percent 100)

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- full-position-size-input
  [modal]
  (let [size (parse-num (:position-size modal))]
    (if (positive-number? size)
      (trading-domain/number->clean-string size 8)
      "")))

(defn- default-size-percent-input
  [modal]
  (if (positive-number? (parse-num (:position-size modal)))
    "100"
    "0"))

(defn- size->percent
  [size position-size]
  (when (and (number? size)
             (positive-number? position-size))
    (clamp (* 100 (/ size position-size))
           0
           max-configure-size-percent)))

(defn- percent->size
  [percent position-size]
  (when (and (number? percent)
             (positive-number? position-size))
    (* position-size
       (/ (clamp percent 0 max-configure-size-percent)
          100))))

(defn- parsed-inputs [modal]
  (let [tp-trigger (parse-num (:tp-price modal))
        sl-trigger (parse-num (:sl-price modal))
        tp-limit (parse-num (:tp-limit modal))
        sl-limit (parse-num (:sl-limit modal))
        position-size (or (parse-num (:position-size modal)) 0)
        configured-size (parse-num (:size-input modal))
        configure-amount? (bool (:configure-amount? modal))
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
     :limit-price? (bool (:limit-price? modal))
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
  (when-not (open? modal)
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

(defn- submit-form [modal]
  (let [{:keys [tp-trigger
                sl-trigger
                tp-limit
                sl-limit
                active-size
                limit-price?
                tp-enabled?
                sl-enabled?]} (parsed-inputs modal)
        order-side (side->order-side (:position-side modal))
        tp-is-market? (not limit-price?)
        sl-is-market? (not limit-price?)]
    {:side order-side
     :size active-size
     :tp {:enabled? tp-enabled?
          :trigger tp-trigger
          :is-market tp-is-market?
          :limit (if (and limit-price? tp-enabled?)
                   tp-limit
                   "")}
     :sl {:enabled? sl-enabled?
          :trigger sl-trigger
          :is-market sl-is-market?
          :limit (if (and limit-price? sl-enabled?)
                   sl-limit
                   "")}}))

(defn prepare-submit [state modal]
  (let [validation (validate-modal modal)
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        market (resolve-market-by-coin-and-dex market-by-key
                                               (:coin modal)
                                               (:dex modal))
        asset-id (resolve-market-asset-id market)]
    (if-not (:is-ok validation)
      {:ok? false
       :display-message (:display-message validation)}
      (if-not (number? asset-id)
        {:ok? false
         :display-message "Select an asset and ensure market data is loaded."}
        (let [orders (order-commands/build-tpsl-orders asset-id
                                                       (:side (submit-form modal))
                                                       (submit-form modal)
                                                       {:active-asset (:coin modal)
                                                        :market market})]
          (if (seq orders)
            {:ok? true
             :display-message (:display-message validation)
             :request {:action {:type "order"
                                :orders orders
                                :grouping "positionTpsl"}}}
            {:ok? false
             :display-message "Place Order"}))))))

(defn from-position-row
  ([position-data]
   (from-position-row position-data nil))
  ([position-data anchor]
   (let [position (or (:position position-data) {})
         side (position-side (:szi position))
         size (absolute-position-size (:szi position))
         entry-price (or (parse-num (:entryPx position)) 0)
         mark-price (calculate-mark-price position)
         leverage (extract-leverage position)
         position-value (or (parse-num (:positionValue position))
                            (* size entry-price)
                            0)
         margin-used (or (parse-num (:marginUsed position))
                         (when (and (positive-number? position-value)
                                    (positive-number? leverage))
                           (/ position-value leverage))
                         0)]
     (assoc (default-modal-state)
            :open? true
            :position-key (projections/position-unique-key position-data)
            :anchor (normalize-anchor anchor)
            :coin (:coin position)
            :dex (normalize-display-text (:dex position-data))
            :position-side side
            :entry-price entry-price
            :mark-price mark-price
            :position-size size
            :position-value position-value
            :margin-used margin-used
            :leverage leverage
            :size-input (if (positive-number? size)
                          (trading-domain/number->clean-string size 8)
                          "")
            :size-percent-input (if (positive-number? size) "100" "0")))))

(def ^:private updatable-paths
  #{[:tp-price]
    [:tp-limit]
    [:sl-price]
    [:sl-limit]})

(defn- normalize-input-text [value]
  (str (or value "")))

(defn- percent->input-text
  [percent]
  (if (number? percent)
    (trading-domain/number->clean-string percent 2)
    ""))

(defn- sync-size-percent-input
  [modal]
  (let [size-text (normalize-input-text (:size-input modal))
        size-value (parse-num size-text)
        position-size (or (parse-num (:position-size modal)) 0)
        percent-text (if (str/blank? size-text)
                       ""
                       (if-let [percent (size->percent size-value position-size)]
                         (percent->input-text percent)
                         ""))]
    (assoc modal :size-percent-input percent-text)))

(defn- apply-size-percent-input
  [modal raw-value]
  (let [raw-text (normalize-input-text raw-value)
        percent-value (parse-num raw-text)
        position-size (or (parse-num (:position-size modal)) 0)]
    (cond
      (str/blank? raw-text)
      (-> modal
          (assoc :size-percent-input ""
                 :size-input ""
                 :error nil))

      (and (number? percent-value)
           (positive-number? position-size))
      (let [clamped-percent (clamp percent-value 0 max-configure-size-percent)
            next-size (percent->size clamped-percent position-size)]
        (-> modal
            (assoc :size-percent-input (percent->input-text clamped-percent)
                   :size-input (if (number? next-size)
                                 (trading-domain/number->clean-string next-size 8)
                                 "")
                   :error nil)))

      :else
      (-> modal
          (assoc :size-percent-input raw-text
                 :size-input ""
                 :error nil)))))

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

(defn- pnl-input->price-text
  [modal raw-value mode]
  (let [raw-text (normalize-input-text raw-value)
        pnl-value (parse-num raw-text)]
    (if (and (not (str/blank? raw-text))
             (number? pnl-value))
      (if-let [price (pnl->price modal pnl-value mode)]
        (trading-domain/number->clean-string price 8)
        "")
      "")))

(defn- pnl-percent->usd
  [modal pnl-percent]
  (when (non-negative-number? pnl-percent)
    (when-let [margin-basis (active-margin-basis modal)]
      (* margin-basis (/ pnl-percent 100)))))

(defn- pnl-percent-input->price-text
  [modal raw-value mode]
  (let [raw-text (normalize-input-text raw-value)
        percent-value (parse-num raw-text)]
    (if (and (not (str/blank? raw-text))
             (number? percent-value))
      (if-let [pnl-usd (pnl-percent->usd modal percent-value)]
        (if-let [price (pnl->price modal pnl-usd mode)]
          (trading-domain/number->clean-string price 8)
          "")
        "")
      "")))

(declare estimated-gain-usd
         estimated-loss-usd
         estimated-gain-percent
         estimated-loss-percent)

(defn- capture-configured-size-pnl-targets
  [modal]
  (let [{:keys [active-size]} (parsed-inputs modal)
        tp-price (parse-num (:tp-price modal))
        sl-price (parse-num (:sl-price modal))
        gain-mode (tp-gain-mode modal)
        loss-mode (sl-loss-mode modal)]
    (if-not (positive-number? active-size)
      {}
      {:tp (when (positive-number? tp-price)
             {:mode gain-mode
              :value (if (= gain-mode :percent)
                       (estimated-gain-percent modal)
                       (estimated-gain-usd modal))})
       :sl (when (positive-number? sl-price)
             {:mode loss-mode
              :value (if (= loss-mode :percent)
                       (estimated-loss-percent modal)
                       (estimated-loss-usd modal))})})))

(defn- target-value->input-text
  [value]
  (when (number? value)
    (trading-domain/number->clean-string value 8)))

(defn- target->price-text
  [modal side {:keys [mode value]}]
  (when-let [input-text (target-value->input-text value)]
    ((if (= mode :percent)
       pnl-percent-input->price-text
       pnl-input->price-text)
     modal
     input-text
     side)))

(defn- reprice-configured-size-targets
  [modal targets]
  (let [{:keys [active-size]} (parsed-inputs modal)
        tp-price (target->price-text modal :tp (:tp targets))
        sl-price (target->price-text modal :sl (:sl targets))]
    (cond-> modal
      (and (positive-number? active-size)
           (seq tp-price))
      (assoc :tp-price tp-price)

      (and (positive-number? active-size)
           (seq sl-price))
      (assoc :sl-price sl-price))))

(defn- parse-pnl-mode-command [value]
  (let [as-keyword (cond
                     (keyword? value) value
                     (string? value) (keyword (str/lower-case (str/trim value)))
                     :else nil)]
    (case as-keyword
      :toggle :toggle
      :usd :usd
      :percent :percent
      nil)))

(defn- toggle-pnl-input-mode [mode]
  (if (= (normalize-pnl-input-mode mode) :percent)
    :usd
    :percent))

(defn- resolve-next-pnl-input-mode [current-mode raw-command]
  (let [command (parse-pnl-mode-command raw-command)]
    (case command
      :toggle (toggle-pnl-input-mode current-mode)
      :usd :usd
      :percent :percent
      (normalize-pnl-input-mode current-mode))))

(defn set-modal-field [modal path value]
  (let [path* (if (vector? path) path [path])
        value* (normalize-input-text value)]
    (cond
      (= path* [:size-input])
      (let [targets (capture-configured-size-pnl-targets modal)]
        (-> modal
            (assoc :size-input value*
                   :error nil)
            sync-size-percent-input
            (reprice-configured-size-targets targets)))

      (= path* [:size-percent-input])
      (let [targets (capture-configured-size-pnl-targets modal)]
        (-> (apply-size-percent-input modal value*)
            (reprice-configured-size-targets targets)))

      (contains? updatable-paths path*)
      (-> modal
          (assoc-in path* value*)
          (assoc :error nil))

      (= path* [:tp-gain-mode])
      (-> modal
          (assoc :tp-gain-mode (resolve-next-pnl-input-mode (tp-gain-mode modal) value))
          (assoc :error nil))

      (= path* [:sl-loss-mode])
      (-> modal
          (assoc :sl-loss-mode (resolve-next-pnl-input-mode (sl-loss-mode modal) value))
          (assoc :error nil))

      (= path* [:tp-gain])
      (-> modal
          (assoc :tp-price ((if (= (tp-gain-mode modal) :percent)
                              pnl-percent-input->price-text
                              pnl-input->price-text)
                            modal
                            value*
                            :tp))
          (assoc :error nil))

      (= path* [:sl-loss])
      (-> modal
          (assoc :sl-price ((if (= (sl-loss-mode modal) :percent)
                              pnl-percent-input->price-text
                              pnl-input->price-text)
                            modal
                            value*
                            :sl))
          (assoc :error nil))

      :else
      modal)))

(defn set-configure-amount [modal checked]
  (let [targets (capture-configured-size-pnl-targets modal)
        checked? (bool checked)
        next-modal (assoc modal :configure-amount? checked?
                                :error nil)
        default-size-text (full-position-size-input modal)
        default-percent-text (default-size-percent-input modal)]
    (-> (if checked?
          (if (str/blank? (:size-input next-modal))
            (assoc next-modal
                   :size-input default-size-text
                   :size-percent-input default-percent-text)
            (sync-size-percent-input next-modal))
          (assoc next-modal
                 :size-input default-size-text
                 :size-percent-input default-percent-text))
        (reprice-configured-size-targets targets))))

(defn set-limit-price [modal checked]
  (let [checked? (bool checked)
        next-modal (assoc modal :limit-price? checked?
                                :error nil)]
    (if checked?
      next-modal
      (assoc next-modal :tp-limit "" :sl-limit ""))))

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

(defn estimated-gain-percent [modal]
  (let [gain-usd (estimated-gain-usd modal)
        margin-basis (active-margin-basis modal)]
    (if (and (positive-number? gain-usd)
             (positive-number? margin-basis))
      (* 100 (/ gain-usd margin-basis))
      0)))

(defn estimated-loss-percent [modal]
  (let [loss-usd (estimated-loss-usd modal)
        margin-basis (active-margin-basis modal)]
    (if (and (positive-number? loss-usd)
             (positive-number? margin-basis))
      (* 100 (/ loss-usd margin-basis))
      0)))

(defn valid-size?
  [modal]
  (let [{:keys [active-size position-size]} (parsed-inputs modal)]
    (and (positive-number? active-size)
         (or (close-enough? active-size position-size)
             (< active-size position-size)))))
