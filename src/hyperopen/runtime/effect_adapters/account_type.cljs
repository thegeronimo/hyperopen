(ns hyperopen.runtime.effect-adapters.account-type
  (:require [hyperopen.account-type.effects :as account-type-effects]))

(defn api-set-user-abstraction-effect
  [_ store request]
  (account-type-effects/api-set-user-abstraction!
   {:store store}
   request))
