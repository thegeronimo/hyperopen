(ns hyperopen.runtime.effect-adapters.builder-codes
  (:require [hyperopen.builder-codes.effects :as builder-codes-effects]))

(defn api-load-builder-codes-effect
  [_ store owner]
  (builder-codes-effects/api-load-builder-codes!
   {:store store}
   owner))

(defn api-revoke-builder-effect
  [_ store request]
  (builder-codes-effects/api-revoke-builder!
   {:store store}
   request))
