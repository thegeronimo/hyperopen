(ns hyperopen.views.portfolio.optimize.view
  (:require [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.index-view :as index-view]
            [hyperopen.views.portfolio.optimize.workspace-view :as workspace-view]))

(defn optimizer-view
  [state]
  (let [route (portfolio-routes/parse-portfolio-route
               (get-in state [:router :path]))]
    (case (:kind route)
      :optimize-index (index-view/index-view state)
      :optimize-new (workspace-view/workspace-view state route)
      :optimize-scenario (workspace-view/workspace-view state route)
      (index-view/index-view state))))
