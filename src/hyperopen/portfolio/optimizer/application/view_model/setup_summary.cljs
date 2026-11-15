(ns hyperopen.portfolio.optimizer.application.view-model.setup-summary
  "Setup summary/label projections: the Run-summary card model, the plain
  summary rows, the constraints one-liner, and the shared display-name maps.
  Split from view-model.setup at the namespace-size gate (2026-07-10)."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.return-views :as return-views]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.portfolio.optimizer.application.view-model.universe :as universe]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as exposure-policy]))

(defn- title-case-token
  [token]
  (if (seq token)
    (str (str/upper-case (subs token 0 1))
         (subs token 1))
    token))

(defn- default-labelize
  [value]
  (cond
    (keyword? value)
    (->> (str/split (name value) #"-")
         (map title-case-token)
         (str/join " "))

    (some? value)
    (str value)

    :else
    "--"))

(defn- apply-labelize
  [formatter value]
  (cond
    (nil? value)
    "--"

    (fn? formatter)
    (formatter value)

    (map? formatter)
    (get formatter value (default-labelize value))

    :else
    (default-labelize value)))

(declare constraints-summary-line)

(defn- active-preset
  [draft]
  (let [objective-kind (get-in draft [:objective :kind])
        return-kind (get-in draft [:return-model :kind])]
    (if (or (= :black-litterman return-kind)
            (= :max-sharpe objective-kind))
      :max-sharpe
      :conservative)))

(def ^:private preset-display-names
  {:conservative "Conservative"
   :max-sharpe "Maximum Sharpe"})

(def objective-display-names
  "The ONE user-facing vocabulary for objectives. \"Minimum risk\" is the
  product term everywhere the user reads; \"minimum variance\" is the method
  and stays in secondary/technical copy only."
  {:minimum-variance "Minimum risk"
   :max-sharpe "Maximum Sharpe"
   :equal-risk "Equal Risk"
   :inverse-volatility "Risk-weighted sizing"
   :target-volatility "Target volatility"
   :target-return "Target return"})

(def return-free-objective-kinds
  "Objectives whose weights never consume expected returns, so the Run summary
  shows \"Not used\" under Return forecast for them: the contracts
  covariance-only kinds plus Minimum risk (return-free in the UI sense, but
  not engine-exempted from return-model validity like the contracts set)."
  (conj contracts-constants/covariance-only-objective-kinds :minimum-variance))

(defn- universe-label
  [instrument]
  (if (= :vault (:market-type instrument))
    (universe/instrument-primary-label instrument)
    (or (:coin instrument)
        (universe/instrument-primary-label instrument))))

(defn- universe-summary
  [draft]
  (let [universe (vec (:universe draft))
        labels (->> universe
                    (keep universe-label)
                    (take 5)
                    (str/join ", "))]
    (str (count universe) " assets"
         (when (seq labels) (str " - " labels)))))

(defn- summary-row
  [label title copy]
  {:label label
   :title title
   :copy copy})

(defn- strip-label
  [label prefix]
  (when label (str/replace-first label prefix "")))

(defn setup-summary-model
  ([draft]
   (setup-summary-model draft nil))
  ([draft {:keys [labelize percent-label]}]
   (let [preset (active-preset draft)
         objective-kind (get-in draft [:objective :kind])
         return-kind (get-in draft [:return-model :kind])
         constraints (:constraints draft)
         labelize* #(apply-labelize labelize %)
         bl? (= :black-litterman return-kind)]
     {:active-preset preset
      :black-litterman? bl?
      :summary-rows
      [(summary-row "Preset" (labelize* preset)
                    "You can deviate from the preset below without changing the universe.")
       (summary-row "Universe" (universe-summary draft)
                    "Selected instruments are optimized as one cross-margin book.")
       (summary-row "Expected Returns" (labelize* return-kind)
                    "Funding-adjusted return assumptions are kept separate from covariance.")
       (summary-row "Objective" (labelize* objective-kind)
                    "Objective remains separate from return model selection.")
       (summary-row "Portfolio exposure"
                    (constraints-summary-line constraints {:objective-kind objective-kind})
                    "Exposure limits are enforced before the recommendation is accepted.")
       (summary-row "Horizon" "Annualized"
                    "Displayed return and volatility metrics use the optimizer annualization convention.")]})))

(defn- fmt-mult
  [x]
  (when (number? x)
    (str (.toFixed x 2) "×")))

(defn- fmt-abs-mult
  [x]
  (when (number? x)
    (str (.toFixed (js/Math.abs x) 2) "×")))

(defn- fmt-signed-mult
  [x]
  (when (number? x)
    (str (cond
           (pos? x) "+"
           (neg? x) "-"
           :else "")
         (.toFixed (js/Math.abs x) 2)
         "×")))

(defn gross-range-label
  [{:keys [gross-min gross-max]}]
  (if (number? gross-min)
    (str "Gross " (fmt-mult gross-min) "–" (fmt-mult gross-max))
    (str "Gross ≤ " (or (fmt-mult gross-max) "--"))))

(defn- net-range-direction
  [net-min net-max]
  (cond
    (and (zero? net-min) (zero? net-max)) :neutral
    (and (not (neg? net-min)) (pos? net-max)) :long
    (and (neg? net-min) (not (pos? net-max))) :short
    :else :neutral-range))

(defn- net-direction-copy
  [direction]
  (case direction
    :long "long"
    :short "short"
    :neutral "neutral"
    :neutral-range "neutral range"
    nil))

(defn- net-range-copy
  [net-min net-max]
  (let [direction (net-range-direction net-min net-max)
        direction-copy (net-direction-copy direction)]
    (cond
      (= net-min net-max)
      (str (fmt-signed-mult net-min) " " direction-copy)

      (= direction :long)
      (str (fmt-signed-mult net-min) "–" (fmt-abs-mult net-max) " " direction-copy)

      (= direction :short)
      (str (fmt-signed-mult net-min) "–" (fmt-abs-mult net-max) " " direction-copy)

      :else
      (str (fmt-signed-mult net-min) "–" (fmt-signed-mult net-max) " " direction-copy))))

(defn net-range-label
  [{:keys [net-min net-max net-band-pct]}]
  (let [band-suffix (when (and (number? net-band-pct) (pos? net-band-pct))
                      (str " ±" (.toFixed (* 100 net-band-pct) 1) "% of gross"))
        base (cond
               (and (number? net-min) (number? net-max))
               (str "Net " (net-range-copy net-min net-max))

               (number? net-max) (str "Net ≤ " (fmt-signed-mult net-max))
               (number? net-min) (str "Net ≥ " (fmt-signed-mult net-min))
               :else nil)]
    (when base (str base band-suffix))))

(defn- cap-label
  [cap]
  (when (number? cap)
    (str "Max asset " (js/Math.round (* 100 cap)) "%")))

(defn- band-label
  [tolerance]
  (when (number? tolerance)
    (str "Rebalance " (.toFixed (* 100 tolerance) 1) " pp")))

(def ^:private equal-risk-net-output-row-copy
  "Determined by Equal Risk from covariance and selected sides")

(def ^:private equal-risk-net-output-summary-copy
  "determined by Equal Risk from covariance and selected sides")

(defn- gross-target-label
  [constraints]
  (fmt-mult (:gross-target (exposure-policy/constraints->policy (or constraints {})))))

(defn- equal-risk-exposure-target
  [constraints]
  {:gross-label (gross-target-label constraints)
   :net-label "Resulting net"
   :direction :neutral
   :net-output-only? true})

(defn- equal-risk-gross-row-value
  [constraints]
  (when-let [gross-label (gross-target-label constraints)]
    (str gross-label " selected")))

(defn- equal-risk-exposure-rows
  [constraints]
  (filterv (fn [[_ value]] (some? value))
           [["Gross" (equal-risk-gross-row-value constraints)]
            ["Resulting net" equal-risk-net-output-row-copy]
            ["Max asset" (strip-label (cap-label (:max-asset-weight constraints))
                                      "Max asset ")]
            ["Rebalance" (strip-label (band-label (:rebalance-tolerance constraints))
                                      "Rebalance ")]]))

(defn- equal-risk-constraints-summary-line
  [constraints]
  (->> [(when-let [gross-value (equal-risk-gross-row-value constraints)]
         (str "Gross " gross-value))
        (str "Resulting net " equal-risk-net-output-summary-copy)
        (cap-label (:max-asset-weight constraints))
        (band-label (:rebalance-tolerance constraints))]
       (keep identity)
       (str/join " · ")))

(defn constraints-summary-line
  "One scannable line of the live exposure-policy numbers (\"Gross 1.90–1.91× ·
  Net +1.30×–1.41× long · Max asset 50% · Rebalance 3.0 pp\") shared by the
  Portfolio exposure header and the scenario-contract card."
  ([constraints]
   (constraints-summary-line constraints nil))
  ([constraints {:keys [objective-kind]}]
   (if (= :equal-risk objective-kind)
     (equal-risk-constraints-summary-line constraints)
     (->> [(gross-range-label constraints)
           (net-range-label constraints)
           (cap-label (:max-asset-weight constraints))
           (band-label (:rebalance-tolerance constraints))]
          (keep identity)
          (str/join " · ")))))

(def return-model-display-names
  "Human names for return-model kinds, shared by the model-section header and the
  scenario-contract card so both say \"Historical mean\", not \"Historical-mean\"."
  {:historical-mean "Historical mean"
   :ew-mean "EW mean"
   :black-litterman "Views + implied baseline"})

(def risk-model-display-names
  {:diagonal-shrink "Stabilized covariance"
   :ledoit-wolf-dense "Ledoit-Wolf"
   :mixed-frequency "Mixed frequency"
   :sample-covariance "Sample covariance"})

(defn- returns-source-label
  "Honest returns-source line for the scenario contract. For the views-aware
  model it reports the live provenance split (\"2 your views · 12 implied\");
  the estimator-only models name the estimate."
  [draft]
  (let [return-kind (get-in draft [:return-model :kind])]
    (if (= :black-litterman return-kind)
      (return-views/returns-contract-label
       (return-views/summary
        (return-views/rows {:universe (:universe draft)
                            :views (get-in draft [:return-model :views])})))
      (get return-model-display-names return-kind))))

(defn setup-summary-card-model
  "Scenario-contract card for the right column: universe (count + source),
  objective, returns source, risk model, and the live constraint numbers — the
  exact policy the solver receives. Derived output, not primary input."
  ([draft] (setup-summary-card-model draft nil))
  ([draft {:keys [labelize]}]
   (let [constraints (:constraints draft)
         labelize* #(apply-labelize labelize %)
         preset (active-preset draft)
         objective-kind (get-in draft [:objective :kind])
         equal-risk? (= :equal-risk objective-kind)
         return-free? (contains? return-free-objective-kinds objective-kind)
         returns-label (or (returns-source-label draft)
                           (labelize* (get-in draft [:return-model :kind])))]
     {:preset-label (get preset-display-names preset (labelize* preset))
      :asset-count (count (:universe draft))
      :universe-source-kind (get-in draft [:metadata :universe-source :kind])
      :objective-label (get objective-display-names objective-kind
                            (labelize* objective-kind))
      :returns-label returns-label
      :return-forecast-label (if return-free? "Not used" returns-label)
      :views-active? (= :black-litterman (get-in draft [:return-model :kind]))
      :min-variance? (= :minimum-variance objective-kind)
      :return-free? return-free?
      :exposure-target
      (if equal-risk?
        (equal-risk-exposure-target constraints)
        (let [{:keys [gross-target net-target]} (exposure-policy/constraints->policy
                                                 (or constraints {}))]
          {:gross-label (fmt-mult gross-target)
           :net-label (fmt-signed-mult net-target)
           :direction (cond
                        (and (number? net-target) (< 0.001 net-target)) :long
                        (and (number? net-target) (< net-target -0.001)) :short
                        :else :neutral)}))
      :exposure-rows
      (if equal-risk?
        (equal-risk-exposure-rows constraints)
        (filterv (fn [[_ value]] (some? value))
                 [["Gross" (strip-label (gross-range-label constraints) "Gross ")]
                  ["Net" (strip-label (net-range-label constraints) "Net ")]
                  ["Max asset" (strip-label (cap-label (:max-asset-weight constraints))
                                            "Max asset ")]
                  ["Rebalance" (strip-label (band-label (:rebalance-tolerance constraints))
                                            "Rebalance ")]]))
      :return-label (or (get return-model-display-names
                             (get-in draft [:return-model :kind]))
                        (labelize* (get-in draft [:return-model :kind])))
      :risk-label (or (get risk-model-display-names
                           (get-in draft [:risk-model :kind]))
                      (labelize* (get-in draft [:risk-model :kind])))
      :gross-min (:gross-min constraints)
      :gross-max (:gross-max constraints)
      :net-min (:net-min constraints)
      :net-max (:net-max constraints)
      :cap (:max-asset-weight constraints)
      :rebalance-tolerance (:rebalance-tolerance constraints)
      :constraints-line (constraints-summary-line constraints
                                                  {:objective-kind objective-kind})})))
