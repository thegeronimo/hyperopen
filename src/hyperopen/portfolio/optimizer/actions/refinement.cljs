(ns hyperopen.portfolio.optimizer.actions.refinement
  "Actions for the draft-result refinement flow. A refinement re-runs the existing solve
  pipeline at a higher frontier density (the chosen depth's point budget), snapshotting the
  current result as a baseline for the before/after comparison. State writes go straight to
  the refinement / UI paths and deliberately do NOT mark the draft dirty (a refinement is a
  quality bump for the same inputs, not a draft edit)."
  (:require [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as defaults]
            [hyperopen.portfolio.optimizer.domain.refinement :as refinement]))

(defn- current-solved-result
  [state]
  (let [result (get-in state contracts/last-successful-run-result-path)]
    (when (= :solved (:status result))
      result)))

(defn- optimizer-running?
  [state]
  (or (= :running (get-in state contracts/run-state-status-path))
      (= :running (get-in state contracts/optimization-progress-status-path))))

(defn- selected-depth
  [state]
  (or (refinement/normalize-depth (get-in state contracts/ui-refinement-depth-path))
      refinement/default-depth))

(defn set-portfolio-optimizer-refinement-depth
  [_state depth]
  (if-let [depth* (refinement/normalize-depth depth)]
    [[:effects/save contracts/ui-refinement-depth-path depth*]]
    []))

(defn refine-portfolio-optimizer
  [state]
  (let [result (current-solved-result state)]
    (if (and result
             (not (optimizer-running? state)))
      (let [depth (selected-depth state)
            requested-points (refinement/depth-points depth)]
        [[:effects/save-many
          [[contracts/refinement-active-path true]
           [contracts/refinement-depth-path depth]
           [contracts/refinement-requested-points-path requested-points]
           [contracts/refinement-baseline-result-path result]]]
         [:effects/run-portfolio-optimizer-pipeline]])
      [])))

(defn stop-portfolio-optimizer-refinement
  [state]
  (if (get-in state contracts/refinement-active-path)
    [[:effects/save-many
      [[contracts/refinement-path (defaults/default-refinement-state)]
       [contracts/run-state-status-path :succeeded]
       [contracts/optimization-progress-status-path :idle]]]]
    []))
