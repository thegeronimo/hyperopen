(ns hyperopen.portfolio.optimizer.switch-objective-actions-test
  "Argument-taking objective switch-and-rerun action for the floored-state
  cross-link (ExecPlan optimizer-inverse-volatility-objective, item 10):
  `switch-portfolio-optimizer-objective-and-run` takes the objective
  menu-option key, saves the objective from the option map, and reruns from
  the draft — unlike apply-portfolio-optimizer-objective-menu-selection-and-run,
  which reads the pending selection from UI state and takes no argument."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(def ^:private state
  {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}]
                                   :objective {:kind :equal-risk}
                                   :return-model {:kind :historical-mean}
                                   :metadata {:dirty? false}}}}})

(deftest switch-objective-and-run-saves-objective-and-emits-run-effects-test
  (is (exists? actions/switch-portfolio-optimizer-objective-and-run)
      "actions/switch-portfolio-optimizer-objective-and-run is not defined yet")
  (when (exists? actions/switch-portfolio-optimizer-objective-and-run)
    (let [effects (actions/switch-portfolio-optimizer-objective-and-run
                   state
                   :inverse-volatility)
          saved-pairs (mapcat second
                              (filter #(= :effects/save-many (first %)) effects))]
      (is (some (fn [[path value]]
                  (and (= [:portfolio :optimizer :draft :objective] path)
                       (= {:kind :inverse-volatility} value)))
                saved-pairs)
          (str "the new objective must save to the draft objective path, got "
               (pr-str effects)))
      (is (some (fn [[path value]]
                  (and (= [:portfolio :optimizer :draft :metadata :dirty?] path)
                       (true? value)))
                saved-pairs)
          "the draft is marked dirty")
      (is (some #(= :effects/run-portfolio-optimizer-pipeline (first %)) effects)
          "the switch reruns the optimizer from the updated draft"))))
