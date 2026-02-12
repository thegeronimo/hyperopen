(ns hyperopen.runtime.wiring
  (:require [hyperopen.app.actions :as app-actions]
            [hyperopen.app.effects :as app-effects]
            [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.runtime.registry-composition :as registry-composition]))

(defn runtime-effect-deps
  ([] (runtime-effect-deps runtime-state/runtime))
  ([runtime]
   (app-effects/runtime-effect-deps runtime)))

(defn runtime-action-deps
  []
  (app-actions/runtime-action-deps))

(defn runtime-registration-deps
  ([] (runtime-registration-deps runtime-state/runtime))
  ([runtime]
  (registry-composition/runtime-registration-deps
   {:register-effects! runtime-registry/register-effects!
    :register-actions! runtime-registry/register-actions!
    :register-system-state! runtime-registry/register-system-state!
    :register-placeholders! runtime-registry/register-placeholders!}
   {:effect-deps (runtime-effect-deps runtime)
    :action-deps (runtime-action-deps)})))
