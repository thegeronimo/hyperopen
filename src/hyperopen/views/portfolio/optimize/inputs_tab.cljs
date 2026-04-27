(ns hyperopen.views.portfolio.optimize.inputs-tab
  (:require [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- format-pct
  [value]
  (if (finite-number? value)
    (str (.toLocaleString (* 100 value)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         "%")
    "N/A"))

(defn- format-decimal
  [value]
  (if (finite-number? value)
    (.toLocaleString value "en-US" #js {:maximumFractionDigits 3})
    "N/A"))

(defn- format-usdc
  [value]
  (if (finite-number? value)
    (str "$" (.toLocaleString value
                              "en-US"
                              #js {:maximumFractionDigits 0}))
    "N/A"))

(defn- keyword-label
  [value]
  (cond
    (keyword? value) (name value)
    (some? value) (str value)
    :else "N/A"))

(defn- audit-card
  [data-role title & children]
  [:section {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
             :data-role data-role}
   [:p {:class ["text-xs" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    title]
   (into [:div {:class ["mt-3"]}]
         children)])

(defn- universe-audit
  [draft]
  (audit-card
   "portfolio-optimizer-inputs-universe"
   "Universe"
   [:p {:class ["font-semibold" "tabular-nums"]} (str (count (:universe draft)) " instruments")]
   (into [:div {:class ["mt-3" "space-y-1"]}]
         (map (fn [instrument]
                [:p {:class ["rounded-md" "border" "border-base-300" "bg-base-100/70" "px-2" "py-1"
                             "text-xs" "font-semibold" "tabular-nums"]}
                 (:instrument-id instrument)])
              (:universe draft)))))

(defn- models-audit
  [draft views]
  (audit-card
   "portfolio-optimizer-inputs-models"
   "Model Stack"
   [:div {:class ["grid" "grid-cols-1" "gap-2" "sm:grid-cols-3"]}
    [:div [:span {:class ["text-xs" "text-trading-muted"]} "Objective"] [:p (keyword-label (get-in draft [:objective :kind]))]]
    [:div [:span {:class ["text-xs" "text-trading-muted"]} "Return Model"] [:p (keyword-label (get-in draft [:return-model :kind]))]]
    [:div [:span {:class ["text-xs" "text-trading-muted"]} "Risk Model"] [:p (keyword-label (get-in draft [:risk-model :kind]))]]]
   [:p {:class ["mt-3" "text-xs" "text-trading-muted"]}
    (str "Black-Litterman views: " (count views))]))

(defn- constraints-audit
  [constraints]
  (audit-card
   "portfolio-optimizer-inputs-constraints"
   "Constraints"
   [:div {:class ["grid" "grid-cols-2" "gap-2" "text-xs"]}
    [:p (str "Long-only: " (if (:long-only? constraints) "yes" "no"))]
    [:p (str "Max asset: " (format-pct (:max-asset-weight constraints)))]
    [:p (str "Gross: " (format-decimal (:gross-max constraints)))]
    [:p (str "Turnover: " (format-pct (:max-turnover constraints)))]
    [:p (str "Rebalance tolerance: " (format-pct (:rebalance-tolerance constraints)))]
    [:p (str "Dust: " (or (:dust-usdc constraints) "N/A"))]]))

(defn- execution-audit
  [execution-assumptions]
  (audit-card
   "portfolio-optimizer-inputs-execution-assumptions"
   "Execution Assumptions"
   [:div {:class ["grid" "grid-cols-2" "gap-2" "text-xs"]}
    [:p (str "Manual capital: " (format-usdc (:manual-capital-usdc execution-assumptions)))]
    [:p (str "Fallback slippage: " (or (:fallback-slippage-bps execution-assumptions) "N/A") " bps")]
    [:p (str "Default order: " (keyword-label (:default-order-type execution-assumptions)))]
    [:p (str "Fee mode: " (keyword-label (:fee-mode execution-assumptions)))]]))

(defn inputs-tab
  [state]
  (let [draft (or (get-in state [:portfolio :optimizer :draft])
                  (optimizer-defaults/default-draft))
        scenario-id (or (get-in state [:portfolio :optimizer :active-scenario :loaded-id])
                        (:id draft))
        constraints (:constraints draft)
        execution-assumptions (:execution-assumptions draft)
        views (get-in draft [:return-model :views])]
    [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-inputs-tab"}
     [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
      [:div
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
        "Inputs"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        "Read-only scenario input audit. Duplicate the scenario before editing inputs."]]
      (when scenario-id
        [:button {:type "button"
                  :class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "px-3" "py-2"
                          "text-xs" "font-semibold" "text-trading-text"]
                  :data-role "portfolio-optimizer-inputs-duplicate"
                  :on {:click [[:actions/duplicate-portfolio-optimizer-scenario scenario-id]]}}
         "Duplicate to Edit"])]
     [:div {:class ["mt-4" "grid" "grid-cols-1" "gap-3" "lg:grid-cols-2"]
            :data-role "portfolio-optimizer-inputs-audit-grid"}
      (universe-audit draft)
      (models-audit draft views)
      (constraints-audit constraints)
      (execution-audit execution-assumptions)]]))
