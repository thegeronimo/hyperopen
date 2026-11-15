(ns hyperopen.portfolio.optimizer.application.view-model.refinement
  "View-model for the draft-result + refinement surface. Derives the result-quality
  assessment, the depth options, in-flight progress, and (after a refinement) the
  before/after comparison against the retained baseline. Pure: reads scenario-scoped
  state + the solved result; all grading lives in domain.refinement."
  (:require [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.refinement :as refinement]))

(def ^:private depth-copy
  {:quick {:label "Quick" :hint "Light pass · ~a few seconds"}
   :thorough {:label "Thorough" :hint "Balanced · ~10–20s est."}
   :maximum {:label "Maximum" :hint "Densest · ~20–40s est."}})

(defn- depth-options
  [selected-depth]
  (mapv (fn [depth]
          (let [{:keys [label hint]} (depth-copy depth)]
            {:key depth
             :points (refinement/depth-points depth)
             :label label
             :hint hint
             :selected? (= depth selected-depth)}))
        refinement/depth-order))

(defn- runtime-ms
  [progress]
  (let [start (:started-at-ms progress)
        end (:completed-at-ms progress)]
    (when (and (number? start)
               (number? end)
               (>= end start))
      (- end start))))

(defn- outcome
  [state result in-flight?]
  (let [baseline (get-in state contracts/refinement-baseline-result-path)]
    (when (and baseline
               (= :solved (:status result))
               (not in-flight?))
      (when-let [change (refinement/selection-change baseline result)]
        {:depth (get-in state contracts/refinement-depth-path)
         :change change
         :exact-selection? (refinement/exact-selection? result)
         :material? (:material? change)}))))

(defn refinement-model
  [{:keys [state result run-state running? progress]}]
  (let [selected-depth (or (refinement/normalize-depth
                            (get-in state contracts/ui-refinement-depth-path))
                           refinement/default-depth)
        solved? (= :solved (:status result))
        active? (boolean (get-in state contracts/refinement-active-path))
        running?* (boolean (or running?
                               (= :running (:status run-state))))
        in-flight? (boolean (and active? running?*))]
    {:solved? solved?
     :can-refine? (boolean (and solved? (not running?*)))
     :depth selected-depth
     :depth-options (depth-options selected-depth)
     :in-flight? in-flight?
     :assessment (refinement/assess-result result)
     :runtime-ms (runtime-ms progress)
     :objective-kind (get-in result [:solver :objective-kind])
     :progress {:overall-percent (:overall-percent progress)
                :active-step (:active-step progress)}
     :outcome (outcome state result in-flight?)}))
