(ns hyperopen.order.cancel-visible-confirmation
  (:require [hyperopen.domain.trading :as trading-domain]))

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(defn default-state
  []
  {:open? false
   :orders []
   :anchor nil})

(defn open?
  [confirmation]
  (boolean (:open? confirmation)))

(defn- normalize-anchor-number
  [value]
  (let [number* (trading-domain/parse-num value)]
    (when (number? number*)
      number*)))

(defn normalize-anchor
  [anchor]
  (let [anchor* (cond
                  (map? anchor) anchor
                  (some? anchor) (js->clj anchor :keywordize-keys true)
                  :else nil)]
    (when (map? anchor*)
      (let [normalized (reduce (fn [acc k]
                                 (if-let [value (normalize-anchor-number (get anchor* k))]
                                   (assoc acc k value)
                                   acc))
                               {}
                               anchor-keys)]
        (when (seq normalized)
          normalized)))))

(defn open-state
  [orders anchor]
  (assoc (default-state)
         :open? true
         :orders (->> (or orders [])
                      (filter map?)
                      vec)
         :anchor (normalize-anchor anchor)))
