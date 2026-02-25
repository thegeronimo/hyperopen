(ns hyperopen.account.history.position-tpsl-state
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]))

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

(defn normalize-display-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(defn normalize-anchor
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

(defn bool [value]
  (boolean value))

(def ^:private pnl-input-mode-default :usd)

(defn normalize-pnl-input-mode [value]
  (let [as-keyword (cond
                     (keyword? value) value
                     (string? value) (keyword (str/lower-case (str/trim value)))
                     :else nil)]
    (case as-keyword
      :usd :usd
      :roe-percent :roe-percent
      :position-percent :position-percent
      ;; Legacy compatibility: historical :percent mode represented ROE%.
      :percent :roe-percent
      :roe :roe-percent
      :position :position-percent
      pnl-input-mode-default)))

(defn percent-pnl-input-mode?
  [value]
  (contains? #{:roe-percent :position-percent}
             (normalize-pnl-input-mode value)))

(defn tp-gain-mode [modal]
  (normalize-pnl-input-mode (:tp-gain-mode modal)))

(defn sl-loss-mode [modal]
  (normalize-pnl-input-mode (:sl-loss-mode modal)))

(defn normalize-input-text [value]
  (str (or value "")))
