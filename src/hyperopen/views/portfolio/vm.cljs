(ns hyperopen.views.portfolio.vm
  (:require [hyperopen.domain.trading :as trading]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-info.projections :as projections]))

(def ^:private fourteen-days-ms
  (* 14 24 60 60 1000))

(defn- number-or-zero [value]
  (if (number? value)
    value
    0))

(defn- fills-source [state]
  (or (get-in state [:orders :fills])
      (get-in state [:webdata2 :fills])
      []))

(defn- trade-values [rows]
  (keep (fn [row]
          (let [value (projections/trade-history-value-number row)
                time-ms (projections/trade-history-time-ms row)]
            (when (number? value)
              {:value value
               :time-ms time-ms})))
        rows))

(defn volume-14d-usd [state]
  (let [values (trade-values (fills-source state))
        cutoff (- (.now js/Date) fourteen-days-ms)
        in-window (filter (fn [{:keys [time-ms]}]
                            (and (number? time-ms)
                                 (>= time-ms cutoff)))
                          values)
        selected (if (seq in-window) in-window values)]
    (reduce (fn [acc {:keys [value]}]
              (+ acc value))
            0
            selected)))

(defn- total-equity [metrics]
  (or (when (number? (:portfolio-value metrics))
        (:portfolio-value metrics))
      (when (and (number? (:spot-equity metrics))
                 (number? (:perps-value metrics)))
        (+ (:spot-equity metrics)
           (:perps-value metrics)))
      (when (number? (:cross-account-value metrics))
        (:cross-account-value metrics))
      0))

(defn- perps-equity [metrics]
  (or (when (number? (:cross-account-value metrics))
        (:cross-account-value metrics))
      (when (number? (:perps-value metrics))
        (:perps-value metrics))
      0))

(defn- spot-equity [metrics]
  (if (number? (:spot-equity metrics))
    (:spot-equity metrics)
    0))

(defn- earn-balance [state]
  (let [borrow-lend-state (get state :borrow-lend)]
    (if (number? (:total-supplied-usd borrow-lend-state))
      (:total-supplied-usd borrow-lend-state)
      0)))

(defn portfolio-vm [state]
  (let [metrics (account-equity-view/account-equity-metrics state)
        fees trading/default-fees
        volume-14d (volume-14d-usd state)
        pnl (number-or-zero (:unrealized-pnl metrics))
        total (total-equity metrics)
        perps (perps-equity metrics)
        spot (spot-equity metrics)
        earn (earn-balance state)]
    {:volume-14d-usd volume-14d
     :fees {:taker (number-or-zero (:taker fees))
            :maker (number-or-zero (:maker fees))}
     :summary {:pnl pnl
               :volume volume-14d
               :max-drawdown-pct 0
               :total-equity total
               :perps-account-equity perps
               :spot-account-equity spot
               :earn-balance earn}}))
