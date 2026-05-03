(ns hyperopen.portfolio.optimizer.application.return-inputs
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.engine.context :as engine-context]))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn decimal->percent-text
  [value]
  (if (finite-number? value)
    (-> (.toFixed (* value 100) 4)
        (str/replace #"\.?0+$" ""))
    ""))

(defn readiness-inputs-by-instrument
  [readiness]
  (let [request (:request readiness)]
    (if (and (= :ready (:status readiness))
             (= :black-litterman (get-in request [:return-model :kind])))
      (engine-context/expected-return-inputs-by-instrument request)
      {})))
