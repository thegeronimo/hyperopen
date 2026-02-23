(ns hyperopen.test-support.async
  (:require [cljs.test :refer-macros [is]]))

(defn unexpected-error
  [done]
  (fn [err]
    (is false (str "Unexpected error: " err))
    (done)))
