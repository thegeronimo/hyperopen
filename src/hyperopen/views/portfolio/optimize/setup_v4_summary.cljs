(ns hyperopen.views.portfolio.optimize.setup-v4-summary
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.instrument-display :as instrument-display]))

(defn- universe-label
  [instrument]
  (if (instrument-display/vault-instrument? instrument)
    (instrument-display/primary-label instrument)
    (or (:coin instrument)
        (instrument-display/primary-label instrument))))

(defn universe-summary
  [draft]
  (let [universe (vec (:universe draft))
        labels (->> universe
                    (keep universe-label)
                    (take 5)
                    (str/join ", "))]
    (str (count universe) " assets"
         (when (seq labels) (str " - " labels)))))
