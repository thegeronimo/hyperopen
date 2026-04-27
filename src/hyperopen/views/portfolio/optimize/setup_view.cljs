(ns hyperopen.views.portfolio.optimize.setup-view
  (:require [hyperopen.views.portfolio.optimize.workspace-view :as workspace-view]))

(defn setup-view
  [state route]
  (workspace-view/workspace-view state route))
