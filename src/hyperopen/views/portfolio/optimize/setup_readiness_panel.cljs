(ns hyperopen.views.portfolio.optimize.setup-readiness-panel
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]))

(def ^:private severity-card-classes
  {:blocking ["border-error/40" "bg-error/10" "text-error"]
   :caution ["border-warning/40" "bg-warning/10" "text-warning"]
   :info ["border-base-300" "bg-base-200/40" "text-trading-muted"]})

(def ^:private severity-action-classes
  {:blocking ["border-error/50" "bg-error/10" "text-error" "hover:bg-error/20"]
   :caution ["border-warning/50" "bg-warning/10" "text-warning" "hover:bg-warning/20"]
   :info ["border-base-300" "bg-base-200/40" "text-trading-muted" "hover:bg-base-200/60"]})

(defn- warning-card
  [{:keys [message detail code-label count assets action severity]}]
  (let [severity (or severity :caution)]
    [:div {:class (into ["rounded-md" "border" "px-2" "py-1.5" "text-xs"]
                        (get severity-card-classes severity))
           :data-role "portfolio-optimizer-readiness-warning"
           :data-severity (name severity)}
     [:div {:class ["flex" "items-start" "justify-between" "gap-2"]}
      [:p {:class ["font-semibold"]}
       message]
      (when (and (number? count) (> count 1))
        [:span {:class ["shrink-0" "rounded-full" "border" "border-current/50"
                        "px-1.5" "font-mono" "text-[0.6875rem]"]
                :data-role "portfolio-optimizer-readiness-warning-count"}
         count])]
     (when detail
       [:p {:class ["mt-0.5" "opacity-80"]
            :data-role "portfolio-optimizer-readiness-warning-detail"}
        detail])
     (when action
       [:button {:type "button"
                 :class (into ["mt-1.5" "border" "px-2" "py-1" "font-mono"
                               "text-[0.6875rem]" "font-semibold" "uppercase"
                               "tracking-[0.08em]"]
                              (get severity-action-classes severity))
                 :data-role "portfolio-optimizer-readiness-warning-action"
                 :on {:click (:actions action)}}
        (:label action)])
     (when (and (number? count) (> count 1) (seq assets))
       [:details {:class ["mt-1"]}
        [:summary {:class ["cursor-pointer" "select-none" "font-mono"
                           "text-[0.75rem]" "uppercase" "tracking-[0.08em]"
                           "opacity-80"]}
         (str "Show " count " assets")]
        [:p {:class ["mt-1" "leading-[1.5]" "opacity-80"]
             :data-role "portfolio-optimizer-readiness-warning-assets"}
         (str/join ", " (map :label assets))]])
     (when code-label
       [:details {:class ["mt-1"]}
        [:summary {:class ["cursor-pointer" "select-none" "font-mono"
                           "text-[0.6875rem]" "uppercase" "tracking-[0.08em]"
                           "opacity-60"]}
         "Details"]
        [:p {:class ["mt-1" "font-mono" "text-[0.6875rem]" "uppercase"
                     "tracking-[0.08em]" "opacity-70"]
             :data-role "portfolio-optimizer-readiness-warning-code"}
         code-label]])]))

(defn readiness-panel
  "Grouped data-health warnings. The surrounding Data health section owns the
  heading and overall status, so this renders copy + severity-ranked warning
  cards only. Pass {:suppress-copy? true} when the section already printed a
  combined status line."
  ([readiness history-load-state] (readiness-panel readiness history-load-state nil))
  ([readiness history-load-state {:keys [suppress-copy?]}]
   (let [{:keys [copy error-message warnings]}
         (optimizer-view-model/readiness-panel-model readiness history-load-state)]
     [:div {:class ["mt-3"]
          :data-role "portfolio-optimizer-readiness-panel"}
    (when-not suppress-copy?
      [:p {:class ["text-xs" "text-trading-muted"]}
       copy])
    (when error-message
      [:p {:class ["mt-3"
                   "rounded-md"
                   "border"
                   "border-error/40"
                   "bg-error/10"
                   "px-2"
                   "py-1.5"
                   "text-xs"
                   "text-error"]}
       error-message])
      (let [{notes :info, primary :primary}
            (group-by #(if (= :info (:severity %)) :info :primary) warnings)]
        (list
         (when (seq primary)
           (into [:div {:class ["mt-3" "space-y-2"]}]
                 (map warning-card primary)))
         (when (seq notes)
           [:details {:class ["mt-3"]
                      :data-role "portfolio-optimizer-readiness-notes"}
            [:summary {:class ["cursor-pointer" "select-none" "font-mono"
                               "text-[0.6875rem]" "uppercase" "tracking-[0.08em]"
                               "text-trading-muted"]}
             (str (count notes) " data " (if (= 1 (count notes)) "note" "notes"))]
            (into [:div {:class ["mt-2" "space-y-2"]}]
                  (map warning-card notes))])))])))
