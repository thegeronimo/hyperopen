(ns hyperopen.account.history.position-tpsl-transitions
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-tpsl-policy :as position-tpsl-policy]
            [hyperopen.account.history.position-tpsl-state :as position-tpsl-state]
            [hyperopen.domain.trading :as trading-domain]))

(def ^:private updatable-paths
  #{[:tp-price]
    [:tp-limit]
    [:sl-price]
    [:sl-limit]})

(defn- parse-num [value]
  (trading-domain/parse-num value))

(defn- percent->input-text
  [percent]
  (if (number? percent)
    (trading-domain/number->clean-string percent 2)
    ""))

(defn- sync-size-percent-input
  [modal]
  (let [size-text (position-tpsl-state/normalize-input-text (:size-input modal))
        size-value (parse-num size-text)
        position-size (or (parse-num (:position-size modal)) 0)
        percent-text (if (str/blank? size-text)
                       ""
                       (if-let [percent (position-tpsl-policy/size->percent size-value position-size)]
                         (percent->input-text percent)
                         ""))]
    (assoc modal :size-percent-input percent-text)))

(defn- apply-size-percent-input
  [modal raw-value]
  (let [raw-text (position-tpsl-state/normalize-input-text raw-value)
        percent-value (parse-num raw-text)
        position-size (or (parse-num (:position-size modal)) 0)]
    (cond
      (str/blank? raw-text)
      (-> modal
          (assoc :size-percent-input ""
                 :size-input ""
                 :error nil))

      (and (number? percent-value)
           (position-tpsl-policy/positive-number? position-size))
      (let [clamped-percent (position-tpsl-policy/clamp percent-value 0 100)
            next-size (position-tpsl-policy/percent->size clamped-percent position-size)]
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

(defn- capture-configured-size-pnl-targets
  [modal]
  (let [{:keys [active-size]} (position-tpsl-policy/parsed-inputs modal)
        tp-price (parse-num (:tp-price modal))
        sl-price (parse-num (:sl-price modal))
        gain-mode (position-tpsl-state/tp-gain-mode modal)
        loss-mode (position-tpsl-state/sl-loss-mode modal)]
    (if-not (position-tpsl-policy/positive-number? active-size)
      {}
      {:tp (when (position-tpsl-policy/positive-number? tp-price)
             {:mode gain-mode
              :value (if (position-tpsl-state/percent-pnl-input-mode? gain-mode)
                       (position-tpsl-policy/estimated-gain-percent-for-mode modal gain-mode)
                       (position-tpsl-policy/estimated-gain-usd modal))})
       :sl (when (position-tpsl-policy/positive-number? sl-price)
             {:mode loss-mode
              :value (if (position-tpsl-state/percent-pnl-input-mode? loss-mode)
                       (position-tpsl-policy/estimated-loss-percent-for-mode modal loss-mode)
                       (position-tpsl-policy/estimated-loss-usd modal))})})))

(defn- target-value->input-text
  [value]
  (when (number? value)
    (trading-domain/number->clean-string value 8)))

(defn- target->price-text
  [modal side {:keys [mode value]}]
  (when-let [input-text (target-value->input-text value)]
    (if (position-tpsl-state/percent-pnl-input-mode? mode)
      (position-tpsl-policy/pnl-percent-input->price-text modal input-text side mode)
      (position-tpsl-policy/pnl-input->price-text modal input-text side))))

(defn- reprice-configured-size-targets
  [modal targets]
  (let [{:keys [active-size]} (position-tpsl-policy/parsed-inputs modal)
        tp-price (target->price-text modal :tp (:tp targets))
        sl-price (target->price-text modal :sl (:sl targets))]
    (cond-> modal
      (and (position-tpsl-policy/positive-number? active-size)
           (seq tp-price))
      (assoc :tp-price tp-price)

      (and (position-tpsl-policy/positive-number? active-size)
           (seq sl-price))
      (assoc :sl-price sl-price))))

(defn set-modal-field [modal path value]
  (let [path* (if (vector? path) path [path])
        value* (position-tpsl-state/normalize-input-text value)]
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
          (assoc :tp-gain-mode (position-tpsl-policy/resolve-next-pnl-input-mode
                                (position-tpsl-state/tp-gain-mode modal)
                                value))
          (assoc :error nil))

      (= path* [:sl-loss-mode])
      (-> modal
          (assoc :sl-loss-mode (position-tpsl-policy/resolve-next-pnl-input-mode
                                (position-tpsl-state/sl-loss-mode modal)
                                value))
          (assoc :error nil))

      (= path* [:tp-gain])
      (-> modal
          (assoc :tp-price (let [gain-mode (position-tpsl-state/tp-gain-mode modal)]
                             (if (position-tpsl-state/percent-pnl-input-mode? gain-mode)
                               (position-tpsl-policy/pnl-percent-input->price-text
                                modal
                                value*
                                :tp
                                gain-mode)
                               (position-tpsl-policy/pnl-input->price-text modal value* :tp))))
          (assoc :error nil))

      (= path* [:sl-loss])
      (-> modal
          (assoc :sl-price (let [loss-mode (position-tpsl-state/sl-loss-mode modal)]
                             (if (position-tpsl-state/percent-pnl-input-mode? loss-mode)
                               (position-tpsl-policy/pnl-percent-input->price-text
                                modal
                                value*
                                :sl
                                loss-mode)
                               (position-tpsl-policy/pnl-input->price-text modal value* :sl))))
          (assoc :error nil))

      :else
      modal)))

(defn set-configure-amount [modal checked]
  (let [targets (capture-configured-size-pnl-targets modal)
        checked? (position-tpsl-state/bool checked)
        next-modal (assoc modal :configure-amount? checked?
                                :error nil)
        default-size-text (position-tpsl-policy/full-position-size-input modal)
        default-percent-text (position-tpsl-policy/default-size-percent-input modal)]
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
  (let [checked? (position-tpsl-state/bool checked)
        next-modal (assoc modal :limit-price? checked?
                                :error nil)]
    (if checked?
      next-modal
      (assoc next-modal :tp-limit "" :sl-limit ""))))
