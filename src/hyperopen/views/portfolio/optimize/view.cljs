(ns hyperopen.views.portfolio.optimize.view
  (:require [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.index-view :as index-view]
            [hyperopen.views.portfolio.optimize.scenario-detail-view :as scenario-detail-view]
            [hyperopen.views.portfolio.optimize.setup-view :as setup-view]))

(defn optimizer-view
  [state]
  (let [route (portfolio-routes/parse-portfolio-route
               (get-in state [:router :path]))]
    (case (:kind route)
      :optimize-index (index-view/index-view state)
      :optimize-new (setup-view/setup-view state route)
      :optimize-scenario (scenario-detail-view/scenario-detail-view state route)
      (index-view/index-view state))))
