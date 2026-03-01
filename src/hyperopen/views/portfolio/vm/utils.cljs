(ns hyperopen.views.portfolio.vm.utils
  (:require [clojure.string :as str]
            [hyperopen.portfolio.metrics.parsing :as parsing]))

(defn optional-number [value]
  (parsing/optional-number value))

(defn number-or-zero [value]
  (if (number? value) value 0))

(defn finite-number? [value]
  (parsing/finite-number? value))

(defn canonical-summary-key
  [value]
  (when value
    (let [v (name value)]
      (cond
        (or (= v "perps") (= v "perp")) :perps
        (or (= v "spot")) :spot
        (or (= v "vaults") (= v "vault")) :vaults
        :else :all))))

(defn max-drawdown-ratio
  [summary]
  (when summary
    (let [max-drawdown (or (:maxDrawdown summary)
                           (get-in summary [:metrics :maxDrawdown]))]
      (when (finite-number? max-drawdown)
        (min 0 max-drawdown)))))

(defn normalize-metric-token-map
  [state]
  (let [account-info (get-in state [:market-data :account-info])
        wallet-address (get-in state [:wallet :address])]
    (str (hash account-info) "-" (hash wallet-address))))

(defn metric-token
  [state request-data]
  (str (normalize-metric-token-map state) "-" (hash request-data)))