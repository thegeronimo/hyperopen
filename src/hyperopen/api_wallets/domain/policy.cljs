(ns hyperopen.api-wallets.domain.policy
  (:require [clojure.string :as str]))

(defn approval-name-for-row
  [row]
  (when (= :named (:row-kind row))
    (or (:approval-name row)
        (:name row))))

(defn merged-rows
  [extra-agents default-agent-row]
  (cond-> (vec (or extra-agents []))
    (map? default-agent-row)
    (conj default-agent-row)))

(defn- compare-string-values
  [left right]
  (compare (str/lower-case (or left ""))
           (str/lower-case (or right ""))))

(defn- compare-valid-until
  [left right]
  (let [left-ms (:valid-until-ms left)
        right-ms (:valid-until-ms right)]
    (cond
      (and (nil? left-ms) (nil? right-ms)) 0
      (nil? left-ms) 1
      (nil? right-ms) -1
      :else (compare left-ms right-ms))))

(defn- compare-rows
  [column left right]
  (let [primary (case column
                  :address (compare-string-values (:address left) (:address right))
                  :valid-until (compare-valid-until left right)
                  (compare-string-values (:name left) (:name right)))]
    (if (zero? primary)
      (let [secondary (compare-string-values (:name left) (:name right))]
        (if (zero? secondary)
          (compare-string-values (:address left) (:address right))
          secondary))
      primary)))

(defn sorted-rows
  [rows sort-state]
  (let [{:keys [column direction]} (or sort-state {})
        descending? (= :desc direction)]
    (->> (or rows [])
         (sort (fn [left right]
                 (let [comparison (compare-rows column left right)]
                   (if descending?
                     (> comparison 0)
                     (< comparison 0)))))
         vec)))
