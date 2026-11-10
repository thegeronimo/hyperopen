(ns hyperopen.views.trade.order-form-vm-submit
  (:require [clojure.string :as str]))

(defn submit-tooltip-message
  "`outcome?` tickets have one blank field a trader can miss, the amount, so
   they say that instead of naming the form field."
  ([required-fields market-price-missing? reason error-message]
   (submit-tooltip-message required-fields market-price-missing? reason error-message false))
  ([required-fields market-price-missing? reason error-message outcome?]
   (cond
     (= reason :spectate-mode-read-only)
     error-message

     (and outcome? (= ["Size"] (vec required-fields)))
     "Enter an amount"

     (seq required-fields)
     (str "Fill required fields: " (str/join ", " required-fields) ".")

     market-price-missing?
     "Load order book data before placing a market order."

     (seq error-message)
     error-message

     :else nil)))

(defn submit-tooltip-from-policy
  ([submit-policy] (submit-tooltip-from-policy submit-policy false))
  ([submit-policy outcome?]
   (submit-tooltip-message (:required-fields submit-policy)
                           (:market-price-missing? submit-policy)
                           (:reason submit-policy)
                           (:error-message submit-policy)
                           outcome?)))
