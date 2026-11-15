(ns hyperopen.views.portfolio.optimize.setup-header
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.contracts :as optimizer-contracts]
            [hyperopen.views.portfolio.optimize.scenario-picker :as scenario-picker]))

(defn- clock-label
  "Local wall-clock label for the autosave note (\"10:42 PM\"). Rendered from the
  epoch with local getters; tests assert presence, not the exact string, so the
  label stays timezone-safe."
  [at-ms]
  (when (number? at-ms)
    (.toLocaleTimeString (js/Date. at-ms)
                         js/undefined
                         #js {:hour "numeric" :minute "2-digit"})))

(def ^:private eyebrow-class
  ["font-mono" "text-[0.6875rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"])

(defn- non-blank
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- workspace-title
  "The scenario being worked on: its saved name once one exists (save and load
  both write the name into the draft config), else the unsaved placeholder.
  The default draft's machine name is a placeholder, not a user choice, so it
  falls through to the unsaved wording."
  [draft route]
  (let [draft-name (non-blank (:name draft))]
    (or (when-not (= "Untitled Optimization" draft-name)
          draft-name)
        (case (:kind route)
          :optimize-scenario (str "Scenario " (:scenario-id route))
          "Untitled scenario"))))

(defn setup-header
  [{:keys [state draft route draft-persist saving-scenario?]}]
  (let [save-state (get-in state optimizer-contracts/scenario-save-state-path)
        scenario-saved? (= :saved (:status save-state))]
    [:header {:class ["optimizer-setup-header" "border" "border-base-300" "bg-base-100/90" "px-3" "py-2"]
              :data-role "portfolio-optimizer-setup-header"}
     [:div {:class ["flex" "items-center" "justify-between" "gap-4"]}
      [:div {:class ["min-w-0"]}
       [:p {:class eyebrow-class} "Portfolio Optimizer"]
       [:div {:class ["mt-1" "flex" "flex-wrap" "items-center" "gap-2"]}
        [:h1 {:class ["text-lg" "font-medium" "tracking-[-0.01em]" "text-trading-text"]
              :data-role "portfolio-optimizer-workspace-title"}
         (workspace-title draft route)]
        [:span {:class ["text-[0.8125rem]" "text-trading-muted"]}
         "- configure your target portfolio"]
        [:span {:class ["optimizer-status-tag" "border" "border-base-300" "bg-base-200/40" "px-2" "py-0.5"
                        "font-mono" "text-[0.6875rem]" "font-semibold" "uppercase"
                        "tracking-[0.12em]" "text-trading-muted/70"]
                :data-role "portfolio-optimizer-setup-status-tag"}
         (if (= :computed (:status draft)) "computed" "draft")]
        (when-not scenario-saved?
          (when-let [saved-label (clock-label (:at-ms draft-persist))]
            [:span {:class ["font-mono" "text-[0.6875rem]" "uppercase" "tracking-[0.12em]"
                            "text-trading-muted/60"]
                    :data-role "portfolio-optimizer-draft-autosave-note"}
             (str "Saved " saved-label)]))
        (when scenario-saved?
          (when-let [saved-label (clock-label (:completed-at-ms save-state))]
            [:span {:class ["font-mono" "text-[0.6875rem]" "uppercase" "tracking-[0.12em]"
                            "text-trading-green/80"]
                    :data-role "portfolio-optimizer-scenario-saved-note"}
             (str "Scenario saved " saved-label)]))]
       [:span {:class ["sr-only"]
               :data-role "portfolio-optimizer-draft-state"}
        (if (get-in draft [:metadata :dirty?])
          "Draft has unsaved changes"
          "Draft clean")]]
      (scenario-picker/scenario-strip state {:saving-scenario? saving-scenario?})]]))
