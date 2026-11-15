(ns hyperopen.portfolio.optimizer.actions.scenario-library
  "Workspace scenario-library actions: the header Scenarios menu (list, load,
  manage the wallet's saved scenarios) and the explicit New-scenario fresh
  start. Loading a saved scenario is plain navigation to its route; these
  actions only own the menu state and the reset."
  (:require [hyperopen.portfolio.optimizer.contracts :as contracts]))

(defn toggle-portfolio-optimizer-scenario-menu
  [state]
  (if (true? (get-in state contracts/ui-scenario-menu-open-path))
    [[:effects/save contracts/ui-scenario-menu-open-path false]]
    [[:effects/save contracts/ui-scenario-menu-open-path true]
     [:effects/load-portfolio-optimizer-scenario-index]]))

(defn close-portfolio-optimizer-scenario-menu
  [_state]
  [[:effects/save contracts/ui-scenario-menu-open-path false]])

(defn handle-portfolio-optimizer-scenario-menu-keydown
  [state key]
  (if (= "Escape" (some-> key str))
    (close-portfolio-optimizer-scenario-menu state)
    []))

(defn new-portfolio-optimizer-scenario
  "Explicit fresh start: close the menu, then hand the reset to the effect,
  which deletes the per-wallet autosaved draft record, clears the workspace
  state, and re-runs the holdings preseed."
  [_state]
  [[:effects/save contracts/ui-scenario-menu-open-path false]
   [:effects/reset-portfolio-optimizer-draft]])
