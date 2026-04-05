(ns hyperopen.schema.contracts.assertions
  (:require [cljs.spec.alpha :as s]
            [hyperopen.account.lifecycle-invariants :as lifecycle-invariants]
            [hyperopen.schema.contracts.action-args :as action-args]
            [hyperopen.schema.contracts.common :as common]
            [hyperopen.schema.contracts.effect-args :as effect-args]
            [hyperopen.schema.contracts.state :as state]))

(defn- assertion-error
  [label spec value context]
  (js/Error.
   (str label " schema validation failed. "
        "context=" (pr-str context)
        " value=" (pr-str value)
        " explain=" (pr-str (s/explain-data spec value)))))

(defn- assert-spec!
  [label spec value context]
  (when-not (s/valid? spec value)
    (throw (assertion-error label spec value context)))
  value)

(defn assert-action-args!
  [action-id args context]
  (assert-spec! "action payload"
                ::action-args/action-id
                action-id
                context)
  (assert-spec! "action payload"
                (get action-args/action-args-spec-by-id action-id ::common/any-args)
                args
                (assoc context :action-id action-id)))

(defn assert-effect-args!
  [effect-id args context]
  (assert-spec! "effect request"
                ::effect-args/effect-id
                effect-id
                context)
  (assert-spec! "effect request"
                (get effect-args/effect-args-spec-by-id effect-id ::common/any-args)
                args
                (assoc context :effect-id effect-id)))

(defn assert-effect-call!
  [effect context]
  (when-not (and (vector? effect)
                 (seq effect))
    (throw (js/Error.
            (str "effect request schema validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str effect)))))
  (let [effect-id (first effect)
        args (subvec effect 1)]
    (assert-effect-args! effect-id args context)
    effect))

(defn assert-emitted-effects!
  [effects context]
  (when-not (sequential? effects)
    (throw (js/Error.
            (str "effect request schema validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str effects)))))
  (doseq [[idx effect] (map-indexed vector effects)]
    (assert-effect-call! effect (assoc context :effect-index idx)))
  effects)

(defn assert-app-state!
  [state-value context]
  (assert-spec! "app state" ::state/app-state state-value context)
  (lifecycle-invariants/assert-account-lifecycle-invariants! state-value context)
  state-value)

(defn assert-provider-message!
  [provider-message context]
  (assert-spec! "provider payload" ::common/provider-message provider-message context))

(defn assert-signed-exchange-payload!
  [payload context]
  (assert-spec! "exchange payload" ::common/signed-exchange-payload payload context))

(defn assert-exchange-response!
  [payload context]
  (assert-spec! "exchange payload" ::common/exchange-response payload context))
