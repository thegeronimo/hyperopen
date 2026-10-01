(ns hyperopen.runtime.collaborators.hyperevm
  (:require [hyperopen.hyperevm.actions :as hyperevm-actions]))

(defn action-deps
  []
  {:refresh-hyperevm-balances hyperevm-actions/refresh-hyperevm-balances
   :refresh-hyperevm-bridge-capacity hyperevm-actions/refresh-hyperevm-bridge-capacity
   :check-hyperevm-in-flight hyperevm-actions/check-hyperevm-in-flight
   :set-balances-location-filter hyperevm-actions/set-balances-location-filter})
