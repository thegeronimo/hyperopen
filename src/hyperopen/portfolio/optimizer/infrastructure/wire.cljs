(ns hyperopen.portfolio.optimizer.infrastructure.wire
  (:require [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def normalize-worker-boundary contracts/normalize-worker-boundary)

(defn- worker-key
  [key]
  (cond
    (keyword? key) (subs (str key) 1)
    (string? key) key
    :else (str key)))

(defn clj->worker-boundary
  [value]
  (clj->js value {:keyword-fn worker-key}))
