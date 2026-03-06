(ns hyperopen.views.portfolio.vm.volume
  (:require [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.portfolio.vm.constants :as constants]))

(defn- optional-number
  [value]
  (projections/parse-optional-num value))

(defn- number-or-zero
  [value]
  (if-let [n (optional-number value)]
    n
    0))

(defn- fills-source
  [state]
  (or (get-in state [:orders :fills])
      (get-in state [:webdata2 :fills])
      []))

(defn- trade-values
  [rows]
  (keep (fn [row]
          (let [value (projections/trade-history-value-number row)
                time-ms (projections/trade-history-time-ms row)]
            (when (number? value)
              {:value value
               :time-ms time-ms})))
        rows))

(defonce fills-volume-cache (atom nil))

(defn volume-14d-usd
  [state]
  (let [fills (fills-source state)
        cache @fills-volume-cache
        values (if (and cache
                        (identical? fills (:fills cache)))
                 (:values cache)
                 (let [new-values (trade-values fills)]
                   (reset! fills-volume-cache {:fills fills
                                               :values new-values})
                   new-values))
        cutoff (- (.now js/Date) constants/fourteen-days-ms)
        recent-values (let [timed-values (keep :time-ms values)]
                        (if (seq timed-values)
                          (filter (fn [{:keys [time-ms]}]
                                    (and (number? time-ms)
                                         (>= time-ms cutoff)))
                                  values)
                          values))]
    (reduce (fn [acc {:keys [value]}]
              (+ acc value))
            0
            recent-values)))

(defn daily-user-vlm-rows
  [state]
  (let [rows (or (get-in state [:portfolio :user-fees :dailyUserVlm])
                 (get-in state [:portfolio :user-fees :daily-user-vlm]))]
    (if (sequential? rows)
      rows
      [])))

(defn daily-user-vlm-row-volume
  [row]
  (cond
    (map? row)
    (let [exchange (optional-number (:exchange row))
          user-cross (optional-number (:userCross row))
          user-add (optional-number (:userAdd row))]
      (if (or (number? user-cross)
              (number? user-add))
        (+ (or user-cross 0)
           (or user-add 0))
        (or exchange 0)))

    (and (sequential? row)
         (>= (count row) 2))
    (number-or-zero (second row))

    :else
    0))

(defn volume-14d-usd-from-user-fees
  [state]
  (let [rows (daily-user-vlm-rows state)]
    (when (seq rows)
      (reduce (fn [acc row]
                (+ acc (daily-user-vlm-row-volume row)))
              0
              (butlast rows)))))

(defn fees-from-user-fees
  [user-fees]
  (let [referral-discount (number-or-zero (:activeReferralDiscount user-fees))
        cross-rate (optional-number (:userCrossRate user-fees))
        add-rate (optional-number (:userAddRate user-fees))
        adjusted-cross-rate (when (number? cross-rate)
                              (* cross-rate (- 1 referral-discount)))
        adjusted-add-rate (when (number? add-rate)
                            (if (pos? add-rate)
                              (* add-rate (- 1 referral-discount))
                              add-rate))]
    (when (and (number? adjusted-cross-rate)
               (number? adjusted-add-rate))
      {:taker (* 100 adjusted-cross-rate)
       :maker (* 100 adjusted-add-rate)})))
