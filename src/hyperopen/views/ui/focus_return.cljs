(ns hyperopen.views.ui.focus-return
  (:require [hyperopen.views.ui.dom :as dom]))

(defn data-role-return-focus-props
  [current-data-role request-data-role request-token]
  (cond-> {:replicant/key (str "focus-return:"
                               current-data-role
                               ":"
                               (or request-token 0)
                               ":"
                               (= current-data-role request-data-role))}
    (and (= current-data-role request-data-role)
         (number? request-token)
         (pos? request-token))
    (assoc :replicant/on-render dom/focus-visible-node!)))
