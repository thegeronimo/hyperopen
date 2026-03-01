(ns hyperopen.views.portfolio.vm.volume
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.vm.constants :as constants]
            [hyperopen.portfolio.metrics.parsing :as parsing]))

(defn- fills-source
  [state]
  (get-in state [:market-data :account-info :agent-webdata :fills]))

(defn- trade-values
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (when-let [px (parsing/optional-number (:px row))]
                 (when-let [sz (parsing/optional-number (:sz row))]
                   (* px sz)))))
       (reduce + 0)))

(defonce fills-volume-cache (atom nil))

(defn volume-14d-usd
  [state]
  (let [fills (fills-source state)
        current-sig (hash fills)
        cached @fills-volume-cache]
    (if (= current-sig (:signature cached))
      (:volume cached)
      (let [now-ms (.getTime (js/Date.))
            cutoff-ms (- now-ms constants/fourteen-days-ms)
            recent-fills (filter (fn [row]
                                   (let [time-ms (parsing/history-point-time-ms row)]
                                     (and (parsing/finite-number? time-ms)
                                          (>= time-ms cutoff-ms))))
                                 fills)
            volume (trade-values recent-fills)]
        (reset! fills-volume-cache {:signature current-sig
                                    :volume volume})
        volume))))

(defn daily-user-vlm-rows
  [state]
  (let [info (get-in state [:market-data :account-info :agent-webdata])]
    (or (:dailyUserVlm info) [])))

(defn daily-user-vlm-row-volume
  [row]
  (let [exchange-vlm (parsing/optional-number (:exchangeVlm row))
        hl-vlm (parsing/optional-number (:hlVlm row))]
    (if (and exchange-vlm hl-vlm)
      (+ exchange-vlm hl-vlm)
      (or exchange-vlm hl-vlm 0))))

(defn volume-14d-usd-from-user-fees
  [state]
  (let [rows (daily-user-vlm-rows state)
        now-ms (.getTime (js/Date.))
        cutoff-ms (- now-ms constants/fourteen-days-ms)
        recent-rows (filter (fn [row]
                              (let [time-ms (parsing/history-point-time-ms row)]
                                (and (parsing/finite-number? time-ms)
                                     (>= time-ms cutoff-ms))))
                            rows)]
    (reduce (fn [acc row]
              (+ acc (daily-user-vlm-row-volume row)))
            0
            recent-rows)))

(defn fees-from-user-fees
  [user-fees]
  (when user-fees
    (let [fee-rows (or (:feeHistory user-fees) [])
          now-ms (.getTime (js/Date.))
          cutoff-ms (- now-ms constants/fourteen-days-ms)
          recent-rows (filter (fn [row]
                                (let [time-ms (parsing/history-point-time-ms row)]
                                  (and (parsing/finite-number? time-ms)
                                       (>= time-ms cutoff-ms))))
                              fee-rows)]
      (reduce (fn [acc row]
                (+ acc (or (parsing/optional-number (:fee row)) 0)))
              0
              recent-rows))))