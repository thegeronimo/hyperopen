(ns hyperopen.runtime.effect-adapters.route-query
  (:require [hyperopen.route-query-state :as route-query-state]))

(defn replace-shareable-route-query
  [_ store]
  (let [location (some-> js/globalThis .-location)
        history (some-> js/globalThis .-history)
        pathname (some-> location .-pathname)
        search (or (some-> location .-search) "")
        current-path (str pathname search)
        replacement-path (route-query-state/shareable-route-browser-path
                          @store
                          pathname
                          search)]
    (when (and history
               replacement-path
               (not= current-path replacement-path))
      (.replaceState history nil "" replacement-path))))
