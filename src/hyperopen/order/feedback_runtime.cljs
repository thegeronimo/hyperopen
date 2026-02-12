(ns hyperopen.order.feedback-runtime
  (:require [clojure.string :as str]))

(defn set-order-feedback-toast!
  [store kind message]
  (let [message* (some-> message str str/trim)]
    (swap! store assoc-in [:ui :toast]
           (when (seq message*)
             {:kind kind
              :message message*}))))

(defn clear-order-feedback-toast!
  [store]
  (swap! store assoc-in [:ui :toast] nil))

(defn clear-order-feedback-toast-timeout!
  [order-feedback-toast-timeout-id clear-timeout-fn]
  (when-let [timeout-id @order-feedback-toast-timeout-id]
    (clear-timeout-fn timeout-id)
    (reset! order-feedback-toast-timeout-id nil)))

(defn clear-order-feedback-toast-timeout-in-runtime!
  [runtime clear-timeout-fn]
  (when-let [timeout-id (get-in @runtime [:timeouts :order-toast])]
    (clear-timeout-fn timeout-id)
    (swap! runtime assoc-in [:timeouts :order-toast] nil)))

(defn schedule-order-feedback-toast-clear!
  [{:keys [store
           runtime
           order-feedback-toast-timeout-id
           clear-order-feedback-toast!
           clear-order-feedback-toast-timeout!
           order-feedback-toast-duration-ms
           set-timeout-fn]}]
  (clear-order-feedback-toast-timeout!)
  (let [timeout-id (set-timeout-fn
                    (fn []
                      (clear-order-feedback-toast! store)
                      (if runtime
                        (swap! runtime assoc-in [:timeouts :order-toast] nil)
                        (reset! order-feedback-toast-timeout-id nil)))
                    order-feedback-toast-duration-ms)]
    (if runtime
      (swap! runtime assoc-in [:timeouts :order-toast] timeout-id)
      (reset! order-feedback-toast-timeout-id timeout-id))))

(defn show-order-feedback-toast!
  [store kind message schedule-order-feedback-toast-clear!]
  (set-order-feedback-toast! store kind message)
  (when (seq (get-in @store [:ui :toast :message]))
    (schedule-order-feedback-toast-clear! store)))
