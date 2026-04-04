(ns hyperopen.views.ui.dom
  (:require [hyperopen.platform :as platform]))

(defn focus-visible-node!
  [node]
  (when node
    (platform/queue-microtask!
     (fn []
       (when (and (.-isConnected node)
                  (not= "none"
                        (some-> (js/getComputedStyle node) .-display)))
         (.focus node))))))
