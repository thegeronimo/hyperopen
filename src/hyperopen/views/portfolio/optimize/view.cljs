(ns hyperopen.views.portfolio.optimize.view
  (:require [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.scenario-detail-view :as scenario-detail-view]
            [hyperopen.views.portfolio.optimize.scenario-save-modal :as scenario-save-modal]
            [hyperopen.views.portfolio.optimize.setup-view :as setup-view]
            [hyperopen.views.portfolio.optimize.tear-sheet-view :as tear-sheet-view]))

(defn optimizer-view
  [state]
  (let [route (portfolio-routes/parse-portfolio-route
               (get-in state [:router :path]))]
    [:div {:class ["contents"]
           :data-role "portfolio-optimizer-route-content"}
     (case (:kind route)
       :optimize-scenario (if (= :tear-sheet (:view route))
                            (tear-sheet-view/tear-sheet-view state route)
                            (scenario-detail-view/scenario-detail-view state route))
       (setup-view/setup-view state route))
     (scenario-save-modal/scenario-save-modal state)]))
