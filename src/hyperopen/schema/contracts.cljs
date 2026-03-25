(ns hyperopen.schema.contracts
  (:require [clojure.set :as set]
            [hyperopen.schema.contracts.action-args :as action-args]
            [hyperopen.schema.contracts.assertions :as assertions]
            [hyperopen.schema.contracts.common :as common]
            [hyperopen.schema.contracts.effect-args :as effect-args]
            [hyperopen.schema.runtime-registration-catalog :as runtime-registration-catalog]))

(let [contracted-ids (set (keys action-args/action-args-spec-by-id))
      registered-ids (runtime-registration-catalog/action-ids)]
  (when-not (= contracted-ids registered-ids)
    (throw (js/Error.
            (str "Action contract metadata drift detected. "
                 "missing=" (pr-str (set/difference registered-ids contracted-ids))
                 " extra=" (pr-str (set/difference contracted-ids registered-ids)))))))

(let [contracted-ids (set (keys effect-args/effect-args-spec-by-id))
      registered-ids (runtime-registration-catalog/effect-ids)]
  (when-not (= contracted-ids registered-ids)
    (throw (js/Error.
            (str "Effect contract metadata drift detected. "
                 "missing=" (pr-str (set/difference registered-ids contracted-ids))
                 " extra=" (pr-str (set/difference contracted-ids registered-ids)))))))

(def validation-enabled? common/validation-enabled?)

(def assert-action-args! assertions/assert-action-args!)
(def assert-effect-args! assertions/assert-effect-args!)
(def assert-effect-call! assertions/assert-effect-call!)
(def assert-emitted-effects! assertions/assert-emitted-effects!)
(def assert-app-state! assertions/assert-app-state!)
(def assert-provider-message! assertions/assert-provider-message!)
(def assert-signed-exchange-payload! assertions/assert-signed-exchange-payload!)
(def assert-exchange-response! assertions/assert-exchange-response!)

(defn contracted-action-ids
  []
  (runtime-registration-catalog/action-ids))

(defn contracted-effect-ids
  []
  (runtime-registration-catalog/effect-ids))

(defn action-ids-using-any-args
  []
  (->> action-args/action-args-spec-by-id
       (keep (fn [[action-id spec]]
               (when (= spec ::common/any-args)
                 action-id)))
       set))
