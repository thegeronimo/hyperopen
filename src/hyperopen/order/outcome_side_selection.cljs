(ns hyperopen.order.outcome-side-selection
  (:require [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.state.trading :as trading]))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- selected-outcome-side-coin
  [state side-index]
  (let [side-index* (parse-int-value side-index)
        sides (get-in state [:active-market :outcome-sides])]
    (when (and (= :outcome (get-in state [:active-market :market-type]))
               (some? side-index*))
      (:coin (some (fn [side]
                     (when (= side-index*
                              (parse-int-value (:side-index side)))
                       side))
                   sides)))))

(defn- apply-transition
  [state {:keys [order-form order-form-ui order-form-runtime]}]
  (cond-> state
    (map? order-form)
    (assoc :order-form (trading/persist-order-form order-form))
    (map? order-form-ui)
    (assoc :order-form-ui order-form-ui)
    (map? order-form-runtime)
    (assoc :order-form-runtime order-form-runtime)))

(defn maybe-switch-side-market
  [state path value transition]
  (when-let [side-coin (and (= [:outcome-side] path)
                            (selected-outcome-side-coin
                             (apply-transition state transition)
                             value))]
    ;; Switching sides of the same contract keeps a Cash out going when the
    ;; new side is held; otherwise the ticket lands as a Buy.
    (asset-actions/select-asset
     (apply-transition state transition)
     side-coin
     {:keep-cash-out? true})))
