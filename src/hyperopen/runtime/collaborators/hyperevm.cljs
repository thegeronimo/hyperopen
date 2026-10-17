(ns hyperopen.runtime.collaborators.hyperevm
  (:require [hyperopen.hyperevm.actions :as hyperevm-actions]
            [hyperopen.platform :as platform]))

(defn- retry-hyperevm-balances
  "The Retry link and the balance poller dispatch this without a clock;
   the runtime supplies it, so the action stays pure."
  [state]
  (hyperevm-actions/retry-hyperevm-balances state (platform/now-ms)))

(defn action-deps
  []
  {:refresh-hyperevm-balances hyperevm-actions/refresh-hyperevm-balances
   :retry-hyperevm-balances retry-hyperevm-balances
   :refresh-hyperevm-bridge-capacity hyperevm-actions/refresh-hyperevm-bridge-capacity
   :check-hyperevm-in-flight hyperevm-actions/check-hyperevm-in-flight
   :set-balances-location-filter hyperevm-actions/set-balances-location-filter})
