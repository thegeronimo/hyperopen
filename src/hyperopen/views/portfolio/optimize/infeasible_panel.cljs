(ns hyperopen.views.portfolio.optimize.infeasible-panel
  "The infeasible run banner: the friendly reason headline, the violation copy,
  the affected-control highlight, and the hiccup. The unblock paths it renders
  live in `infeasible-remediation`."
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.infeasible-remediation
             :as remediation]))

(def ^:private violation-control-keys
  {:sum-upper-below-target #{:max-asset-weight}
   :sum-upper-below-net-min #{:net-min :max-asset-weight}
   :sum-lower-above-target #{:held-locks}
   :target-return-above-feasible-maximum #{:target-return}
   :gross-floor-above-gross-max #{:gross-min :gross-max}
   :gross-floor-exceeds-capacity #{:gross-min :max-asset-weight}
   ;; The two codes with a remediation model are refined per violation by
   ;; `refined-control-keys`; these entries are the code-only fallback for a
   ;; result that carries no violation payload.
   :net-unreachable-given-sides #{:gross-min :gross-max :net-min :net-max :max-asset-weight}
   :minimum-risk-without-exposure-floor #{:gross-min :net-min :net-max}
   :solver-result-gross-exposure-violation #{:gross-max}
   :solver-result-turnover-violation #{:max-turnover}
   :equal-risk-invalid-exposure-targets #{:gross-max}
   :equal-risk-gross-target-not-positive #{:gross-max}
   :equal-risk-gross-target-above-max #{:gross-max}
   :equal-risk-gross-target-below-floor #{:gross-min :gross-max}
   :equal-risk-gross-minimum-above-target #{:held-locks}
   :equal-risk-gross-capacity-below-target #{:max-asset-weight}
   :inverse-volatility-zero-volatility-asset #{:blocklist}})

(def ^:private violation-constraint-control-keys
  {:gross-exposure #{:gross-max}
   :net-exposure #{:net-min :net-max}
   :turnover #{:max-turnover}})

(def ^:private control-labels
  {:max-asset-weight "Max Asset Weight"
   :gross-min "Gross Exposure Min"
   :gross-max "Gross Exposure"
   :held-locks "Held Position Locks"
   :long-only? "Long Only"
   :max-turnover "Turnover Cap"
   :net-max "Net Exposure Max"
   :net-min "Net Exposure Min"
   :target-return "Target Return"
   :blocklist "Excluded Assets"})

(def ^:private code-labels
  ;; opt-format/keyword-label falls back to (name value), which leaks the raw
  ;; diagnostic token into the "Reason:" headline and the chip row. Every reason
  ;; the engine can publish at run level gets a label here, alongside the
  ;; violation codes that carry copy.
  {:net-unreachable-given-sides "Net target out of reach"
   :minimum-risk-without-exposure-floor "No exposure floor"
   :solver-boundary-row-violation "Constraint row out of bounds"
   :objective-collapses-to-cash "Objective collapses to cash"
   :constraint-presolve "Constraints cannot all be met"
   :constraints-unsatisfiable "No portfolio satisfies every constraint"
   :solver-returned-invalid-solution "Solver answer broke a constraint"
   :solver-returned-no-solution "Solver found no solution"
   :solver-primal-infeasible "Solver: constraints conflict"
   :solver-solution-outside-constraints "Solver answer outside its own bounds"
   ;; The run-level twin of the solver-level reason above. It must NOT read as a
   ;; proof of infeasibility: the solver returned a real point that failed the
   ;; row check, which is a solver-quality failure, not a statement about the
   ;; request.
   :solver-solution-failed-boundary-check "Solver answer outside its own bounds"
   :target-return-above-feasible-maximum "Target return out of reach"
   :equal-risk-presolve "Equal Risk setup cannot be solved"
   :inverse-volatility-presolve "Risk-weighted sizing setup cannot be solved"
   :invalid-return-model "Return model unavailable"
   :unknown-objective "Unsupported objective"})

(defn- code-label
  [value]
  (or (get code-labels value)
      (opt-format/keyword-label value "unknown")))

(defn infeasible-result
  [run-state]
  (when (= :infeasible (:status run-state))
    (or (:result run-state)
        run-state)))

;; The remediation model's public surface, re-exported so `workspace_view` and
;; the panel tests keep one entry point for the banner.
(def remediation-suggestions remediation/suggestions)

(def remediation-rationale remediation/rationale)

(def ^:private violation-codes remediation/violation-codes)

(defn- structured-violation-messages
  [violation]
  (case (:code violation)
    :sum-upper-below-net-min
    (when (and (opt-format/finite-number? (:sum-upper violation))
               (opt-format/finite-number? (:net-min violation)))
      [(str "Maximum possible net exposure is "
            (opt-format/format-decimal (:sum-upper violation))
            ", below the minimum of "
            (opt-format/format-decimal (:net-min violation))
            ".")
       "Lower Net Exposure Min, add eligible long assets, or raise Max Asset Weight."])

    :gross-floor-above-gross-max
    (when (and (opt-format/finite-number? (:gross-floor violation))
               (opt-format/finite-number? (:gross-max violation)))
      [(str "Gross Exposure Min of "
            (opt-format/format-decimal (:gross-floor violation))
            " is above the maximum of "
            (opt-format/format-decimal (:gross-max violation))
            ".")
       "Raise Gross Exposure (max) or lower Gross Exposure Min."])

    :gross-floor-exceeds-capacity
    (when (and (opt-format/finite-number? (:gross-floor violation))
               (opt-format/finite-number? (:gross-capacity violation)))
      [(str "Gross Exposure Min of "
            (opt-format/format-decimal (:gross-floor violation))
            " is higher than the "
            (opt-format/format-decimal (:gross-capacity violation))
            " the selected assets can reach.")
       "Lower Gross Exposure Min, add eligible assets, or raise Max Asset Weight."])
    nil))

(defn- violation-messages
  [result]
  (->> (get-in result [:details :violations])
       (mapcat (fn [violation]
                 (cons (:message violation)
                       (structured-violation-messages violation))))
       (remove str/blank?)
       distinct
       vec))

(defn- refined-control-keys
  "Controls that can actually MOVE this violation. Long Only pins net at gross,
  so the net bounds are inert and the toggle itself is the control to look at."
  [{:keys [code long-only? max-feasible-gross-floor]}]
  (let [long? (true? long-only?)]
    (case code
      :net-unreachable-given-sides
      (cond-> #{:gross-min :gross-max :max-asset-weight}
        long? (conj :long-only?)
        (not long?) (into #{:net-min :net-max}))

      :minimum-risk-without-exposure-floor
      (cond-> #{:net-min :net-max}
        ;; A setup whose net window admits no positive floor must not point at
        ;; Gross Exposure Min: every value it could take is rejected on read.
        (or (nil? max-feasible-gross-floor)
            (and (opt-format/finite-number? max-feasible-gross-floor)
                 (pos? max-feasible-gross-floor)))
        (conj :gross-min))

      (get violation-control-keys code))))

(defn- violation->control-keys
  [violation]
  (concat (refined-control-keys violation)
          (get violation-constraint-control-keys (:constraint-code violation))))

(defn highlighted-control-keys
  [result]
  (let [violations (get-in result [:details :violations])]
    (if (seq violations)
      (set (mapcat violation->control-keys violations))
      (set (mapcat violation-control-keys (violation-codes result))))))

(defn- suggestion-item
  [{:keys [id copy label actions]}]
  (into [:li {:class ["flex" "flex-wrap" "items-baseline" "gap-x-2" "gap-y-1"]
              :data-role (str "portfolio-optimizer-infeasible-suggestion-" (name id))
              :replicant/key (str "infeasible-suggestion-" (name id))}]
        (remove nil?)
        [[:span copy]
         (when (and (not (str/blank? label)) (seq actions))
           [:button {:type "button"
                     :class ["rounded-full"
                             "border"
                             "border-warning/50"
                             "bg-warning/10"
                             "px-2"
                             "py-0.5"
                             "text-xs"
                             "font-semibold"
                             "text-warning"
                             "hover:bg-warning/20"]
                     :data-role (str "portfolio-optimizer-infeasible-fix-" (name id))
                     :on {:click actions}}
            label])]))

(defn- remediation-block
  [result]
  (let [suggestions (remediation-suggestions result)]
    (when (seq suggestions)
      (into [:div {:class ["mt-3"]
                   :data-role "portfolio-optimizer-infeasible-suggestions"}]
            (remove nil?)
            [[:p {:class ["text-xs" "font-semibold" "uppercase" "tracking-[0.24em]"]}
              "What you can do"]
             (when-let [rationale (remediation-rationale result)]
               [:p {:class ["mt-2" "text-xs"]
                    :data-role "portfolio-optimizer-infeasible-rationale"}
                rationale])
             (into [:ol {:class ["mt-2" "list-decimal" "space-y-1.5" "pl-4" "text-xs"]}]
                   (map suggestion-item suggestions))]))))

(defn infeasible-banner
  [result highlighted-controls]
  (when result
    (let [codes (violation-codes result)
          messages (violation-messages result)
          labels (keep control-labels highlighted-controls)]
      [:section {:class ["rounded-xl"
                         "border"
                         "border-warning/50"
                         "bg-warning/10"
                         "p-4"
                         "text-warning"]
                 :data-role "portfolio-optimizer-infeasible-banner"}
       [:p {:class ["text-[0.75rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.24em]"]}
        "Infeasible Optimization"]
       [:p {:class ["mt-2" "text-sm"]}
        (str "Reason: " (code-label (:reason result)))]
       (when-not (str/blank? (:message result))
         [:p {:class ["mt-2" "text-sm" "text-warning"]}
          (:message result)])
       (when (seq messages)
         (into [:ul {:class ["mt-3" "space-y-1" "text-xs"]}]
               (map (fn [message]
                      [:li message])
                    messages)))
       (remediation-block result)
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
                       (code-label code)])
                    codes)))
       (when (seq labels)
         [:p {:class ["mt-3" "text-xs"]}
          (str "Affected controls: " (str/join ", " labels))])])))
