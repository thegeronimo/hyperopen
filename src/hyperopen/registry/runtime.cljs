(ns hyperopen.registry.runtime
  (:require [nexus.registry :as nxr]
            [hyperopen.schema.runtime-registration-catalog :as runtime-registration-catalog]
            [hyperopen.runtime.validation :as runtime-validation]))

(defn registered-effect-ids
  []
  (runtime-registration-catalog/effect-ids))

(defn registered-action-ids
  []
  (runtime-registration-catalog/action-ids))

(defn- require-handler
  [handlers handler-key kind id]
  (let [handler (get handlers handler-key)]
    (if (fn? handler)
      handler
      (throw (js/Error.
              (str "Missing " (name kind) " handler " handler-key " for " id))))))

(defn register-effects!
  [handlers]
  (doseq [[effect-id handler-key] (runtime-registration-catalog/effect-binding-rows)]
    (nxr/register-effect!
     effect-id
     (runtime-validation/wrap-effect-handler
      effect-id
      (require-handler handlers handler-key :effect effect-id)))))

(defn register-actions!
  [handlers]
  (doseq [[action-id handler-key] (runtime-registration-catalog/action-binding-rows)]
    (nxr/register-action!
     action-id
     (runtime-validation/wrap-action-handler
      action-id
      (require-handler handlers handler-key :action action-id)))))

(defn register-system-state!
  []
  (nxr/register-system->state! deref))

(defn register-placeholders!
  []
  (nxr/register-placeholder! :event.target/value
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-target .-value)))

  (nxr/register-placeholder! :event.target/checked
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-target .-checked)))

  (nxr/register-placeholder! :event/key
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-key)))

  (nxr/register-placeholder! :event/metaKey
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-metaKey)))

  (nxr/register-placeholder! :event/ctrlKey
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-ctrlKey)))

  (nxr/register-placeholder! :event.target/scrollTop
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-target .-scrollTop)))

  (nxr/register-placeholder! :event/timeStamp
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-timeStamp)))

  (nxr/register-placeholder! :event/clientX
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-clientX)))

  (nxr/register-placeholder! :event.currentTarget/bounds
    (fn [{:replicant/keys [dom-event]}]
      (when-let [target (some-> dom-event .-currentTarget)]
        (when (fn? (.-getBoundingClientRect target))
          (let [rect (.getBoundingClientRect target)]
            {:left (.-left rect)
             :right (.-right rect)
             :top (.-top rect)
             :bottom (.-bottom rect)
             :width (.-width rect)
             :height (.-height rect)
             :viewport-width (some-> js/globalThis .-innerWidth)
             :viewport-height (some-> js/globalThis .-innerHeight)})))))

  (nxr/register-placeholder! :event.currentTarget/data-role
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-currentTarget (.getAttribute "data-role")))))
