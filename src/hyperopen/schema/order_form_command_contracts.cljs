(ns hyperopen.schema.order-form-command-contracts
  (:require [cljs.spec.alpha :as s]))

(def ^:private required-command-keys
  #{:command-id :args})

(def ^:private known-placeholder-tokens
  #{:order-form.event/target-value
    :order-form.event/target-checked
    :order-form.event/key})

(s/def ::command-id keyword?)
(s/def ::args vector?)
(s/def ::command-base
  (s/keys :req-un [::command-id ::args]))

(defn- unknown-placeholder-token?
  [arg]
  (and (keyword? arg)
       (= "order-form.event" (namespace arg))
       (not (contains? known-placeholder-tokens arg))))

(defn order-form-command-valid?
  [command allowed-command-ids]
  (and (s/valid? ::command-base command)
       (= required-command-keys (set (keys command)))
       (contains? allowed-command-ids (:command-id command))
       (every? #(not (unknown-placeholder-token? %)) (:args command))))

(defn- runtime-action-id?
  [value]
  (and (keyword? value)
       (= "actions" (namespace value))))

(defn runtime-action?
  [value]
  (and (vector? value)
       (seq value)
       (runtime-action-id? (first value))))

(defn runtime-actions-valid?
  [runtime-actions]
  (and (vector? runtime-actions)
       (seq runtime-actions)
       (every? runtime-action? runtime-actions)))

(defn assert-order-form-command!
  [command context allowed-command-ids]
  (when-not (order-form-command-valid? command allowed-command-ids)
    (throw (js/Error.
            (str "order-form command contract validation failed. "
                 "context=" (pr-str context)
                 " command=" (pr-str command)
                 " allowed-command-ids=" (pr-str (vec (sort allowed-command-ids)))
                 " explain=" (pr-str (s/explain-data ::command-base command))))))
  command)

(defn assert-runtime-actions!
  [runtime-actions context]
  (when-not (runtime-actions-valid? runtime-actions)
    (throw (js/Error.
            (str "order-form runtime action contract validation failed. "
                 "context=" (pr-str context)
                 " runtime-actions=" (pr-str runtime-actions)))))
  runtime-actions)
