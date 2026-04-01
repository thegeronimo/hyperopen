(ns hyperopen.views.trading-chart.utils.indicator-catalog
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.indicators.schema :as indicator-schema]))

(defn- dedupe-indicators
  [definitions]
  (loop [remaining definitions
         seen #{}
         out []]
    (if-let [indicator (first remaining)]
      (let [indicator-id (:id indicator)]
        (if (contains? seen indicator-id)
          (recur (rest remaining) seen out)
          (recur (rest remaining) (conj seen indicator-id) (conj out indicator))))
      (vec out))))

(defn get-available-indicators
  []
  (->> (indicator-schema/indicator-definitions)
       dedupe-indicators
       (sort-by (fn [{:keys [id] :as indicator}]
                  [(str/lower-case (or (:name indicator) ""))
                   (name id)]))
       vec))
