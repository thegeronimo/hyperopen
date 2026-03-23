(ns hyperopen.order.submit-confirmation
  (:require [clojure.string :as str]))

(def ^:private supported-variants
  #{:open-order
    :close-position})

(defn default-state
  []
  {:open? false
   :variant nil
   :message nil
   :request nil
   :path-values []})

(defn open?
  [confirmation]
  (boolean (:open? confirmation)))

(defn normalize-variant
  [variant]
  (if (contains? supported-variants variant)
    variant
    :open-order))

(defn- normalize-message
  [message]
  (some-> message str str/trim not-empty))

(defn- normalize-path-values
  [path-values]
  (if (sequential? path-values)
    (->> path-values
         (filter vector?)
         vec)
    []))

(defn open-state
  [{:keys [variant message request path-values]}]
  (assoc (default-state)
         :open? true
         :variant (normalize-variant variant)
         :message (normalize-message message)
         :request (when (map? request) request)
         :path-values (normalize-path-values path-values)))
