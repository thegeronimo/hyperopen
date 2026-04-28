(ns hyperopen.views.portfolio.optimize.infeasible-panel
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def ^:private violation-control-keys
  {:sum-upper-below-target #{:max-asset-weight}
   :sum-lower-above-target #{:held-locks}
   :target-return-above-feasible-maximum #{:target-return}})

(def ^:private control-labels
  {:max-asset-weight "Max Asset Weight"
   :held-locks "Held Position Locks"
   :target-return "Target Return"})

(defn infeasible-result
  [run-state]
  (when (= :infeasible (:status run-state))
    (or (:result run-state)
        run-state)))

(defn- violation-codes
  [result]
  (let [violations (get-in result [:details :violations])]
    (cond
      (seq violations) (mapv :code violations)
      (:reason result) [(:reason result)]
      :else [])))

(defn highlighted-control-keys
  [result]
  (set (mapcat violation-control-keys (violation-codes result))))

(defn infeasible-banner
  [result highlighted-controls]
  (when result
    (let [codes (violation-codes result)
          labels (keep control-labels highlighted-controls)]
      [:section {:class ["rounded-xl"
                         "border"
                         "border-warning/50"
                         "bg-warning/10"
                         "p-4"
                         "text-warning"]
                 :data-role "portfolio-optimizer-infeasible-banner"}
       [:p {:class ["text-[0.65rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.24em]"]}
        "Infeasible Optimization"]
       [:p {:class ["mt-2" "text-sm"]}
        (str "Reason: " (opt-format/keyword-label (:reason result) "unknown"))]
       (when (seq codes)
         (into [:div {:class ["mt-3" "flex" "flex-wrap" "gap-2"]}]
               (map (fn [code]
                      [:span {:class ["rounded-full"
                                      "border"
                                      "border-warning/40"
                                      "px-2"
                                      "py-1"
                                      "text-xs"
                                      "font-semibold"]}
                       (opt-format/keyword-label code "unknown")])
                    codes)))
       (when (seq labels)
         [:p {:class ["mt-3" "text-xs"]}
          (str "Affected controls: " (str/join ", " labels))])])))
