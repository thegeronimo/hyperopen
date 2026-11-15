(ns hyperopen.views.portfolio.optimize.optimization-progress-panel
  (:require [clojure.string :as str]))

(defn- clamp-percent
  [value]
  (let [n (if (number? value) value 0)]
    (-> (if (and (not (js/isNaN n))
                 (js/isFinite n))
          n
          0)
        (max 0)
        (min 100))))

(defn- status-label
  [status]
  (case status
    :running "Computing"
    :succeeded "Complete"
    :failed "Failed"
    :idle "Idle"
    (-> (or status :idle) name (str/replace "-" " "))))

(defn- title-label
  [status]
  (case status
    :failed "Optimization Failed"
    :succeeded "Optimization Complete"
    "Optimization In Progress"))

(defn- headline-label
  [status]
  (case status
    :failed "Optimization failed"
    :succeeded "Optimization complete"
    "Optimizing portfolio…"))

(defn- elapsed-seconds
  [progress]
  (let [started (:started-at-ms progress)
        completed (:completed-at-ms progress)
        end-ms (or completed (:now-ms progress) (.now js/Date))]
    (when (number? started)
      (max 0 (/ (- end-ms started) 1000)))))

(defn- display-percent
  "Percent for the bar + headline number: the eased trickle value while running
  (so it keeps moving between the worker's bursty updates), else true progress."
  [progress status]
  (let [real (clamp-percent (:overall-percent progress))]
    (if (and (= :running status)
             (number? (:display-percent progress)))
      (max real (clamp-percent (:display-percent progress)))
      real)))

(defn- format-seconds
  [seconds]
  (if (number? seconds)
    (str (.toFixed seconds 1) "s")
    "n/a"))

(defn- active-step-label
  [progress]
  (let [active (:active-step progress)]
    (some (fn [step]
            (when (= active (:id step))
              (:label step)))
          (:steps progress))))

(defn- summary-text
  "Quiet one-liner: elapsed · current sub-step. No remaining-time estimate — a
  stateless linear guess drifts badly while the worker stalls mid-step."
  [progress elapsed]
  (let [step-label (active-step-label progress)]
    (->> [(when (number? elapsed) (str (format-seconds elapsed) " elapsed"))
          (when (and (= :running (:status progress)) (seq step-label)) step-label)]
         (remove nil?)
         (str/join " · "))))

(defn- overall-tone-class
  [status]
  (case status
    :succeeded "bg-primary"
    :failed "bg-error"
    "bg-warning"))

(defn- percent-tone-class
  [status]
  (case status
    :succeeded "text-primary"
    :failed "text-error"
    "text-warning"))

(defn- step-tone-class
  [step]
  (case (:status step)
    :succeeded "bg-primary"
    :failed "bg-error"
    :running "bg-warning"
    "bg-base-300"))

(defn- step-row
  [idx step]
  (let [percent (clamp-percent (:percent step))
        running? (= :running (:status step))
        bar-percent (if running? (max percent 3) percent)
        row-id (or (:id step) idx)
        row-token (if (keyword? row-id)
                    (name row-id)
                    (str row-id))]
    [:div {:class ["space-y-1.5"]
           :data-role (str "portfolio-optimizer-progress-step-" row-token)}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3" "text-[0.75rem]"]}
      [:p {:class ["min-w-0" "font-mono" "font-semibold" "text-trading-text"]}
       [:span {:class ["text-trading-muted"]} (str (inc idx) ". ")]
       (:label step)
       (when (seq (:detail step))
         [:span {:class ["text-trading-muted"]} (str " · " (:detail step))])]
      [:span {:class ["font-mono" "text-[0.6875rem]" "text-trading-muted"]}
       (str (.toFixed percent 0) "%")]]
     [:div {:class ["h-1.5" "overflow-hidden" "rounded-full" "bg-base-300/60"]}
      [:div {:class ["h-full"
                     "transition-[width]"
                     "duration-500"
                     "ease-out"
                     (step-tone-class step)
                     (when running? "animate-pulse")]
             :style {:width (str bar-percent "%")}}]]]))

(defn progress-panel
  "Compact single-bar optimization progress. Renders one overall bar driven by
  :overall-percent, the current sub-step in fine print, and the full per-step
  breakdown tucked inside a collapsible 'details' disclosure.

  opts:
    :show-header? (default true) — when false, omit the eyebrow + status badge
                  (used when an enclosing banner already provides that context)."
  ([progress]
   (progress-panel progress nil))
  ([progress {:keys [show-header?] :or {show-header? true}}]
   (let [status (:status progress)
         visible? (contains? #{:running :succeeded :failed} status)
         steps (vec (:steps progress))
         elapsed (elapsed-seconds progress)
         percent (display-percent progress status)
         running? (= :running status)]
     (when visible?
       [:section {:class ["mt-4"
                          "rounded-lg"
                          "border"
                          "border-base-300"
                          "bg-base-100/95"
                          "p-3"
                          "shadow-[0_0_0_1px_rgba(255,255,255,0.02)]"]
                  :data-role "portfolio-optimizer-progress-panel"}
        (when show-header?
          [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
           [:p {:class ["font-mono"
                        "text-[0.6875rem]"
                        "font-semibold"
                        "uppercase"
                        "tracking-[0.08em]"
                        "text-trading-muted"]}
            (title-label status)]
           [:span {:class ["rounded-sm"
                           "border"
                           (if (= :failed status) "border-error/60" "border-warning/60")
                           "px-1.5"
                           "py-0.5"
                           "font-mono"
                           "text-[0.625rem]"
                           "font-semibold"
                           "uppercase"
                           "tracking-[0.08em]"
                           (if (= :failed status) "text-error" "text-warning")]}
            (status-label status)]])
        [:div {:class [(when show-header? "mt-3")
                       "flex"
                       "items-baseline"
                       "justify-between"
                       "gap-3"]}
         [:p {:class ["text-sm" "font-semibold" "text-trading-text"]}
          (headline-label status)]
         (when-not (= :failed status)
           [:span {:class ["font-mono"
                           "text-sm"
                           "font-semibold"
                           "tabular-nums"
                           (percent-tone-class status)]}
            (str (.toFixed percent 0) "%")])]
        [:div {:class ["mt-2" "h-1.5" "overflow-hidden" "rounded-full" "bg-base-300/60"]
               :role "progressbar"
               :aria-label "Optimization progress"
               :aria-valuemin "0"
               :aria-valuemax "100"
               :aria-valuenow (.toFixed percent 0)}
         [:div {:class ["h-full"
                        "transition-[width]"
                        "duration-500"
                        "ease-out"
                        (overall-tone-class status)
                        (when running? "animate-pulse")]
                :style {:width (str (if running? (max percent 3) percent) "%")}}]]
        [:details {:class ["mt-2" "group"]
                   :data-role "portfolio-optimizer-progress-details"}
         [:summary {:class ["flex"
                            "items-center"
                            "justify-between"
                            "gap-3"
                            "cursor-pointer"
                            "select-none"
                            "list-none"
                            "[&::-webkit-details-marker]:hidden"
                            "font-mono"
                            "text-[0.6875rem]"
                            "text-trading-muted"]
                    :data-role "portfolio-optimizer-progress-footer"}
          [:span {:class ["min-w-0" "truncate"]}
           (summary-text progress elapsed)]
          [:span {:class ["shrink-0"
                          "text-trading-muted/70"
                          "underline"
                          "decoration-dotted"
                          "underline-offset-2"
                          "group-hover:text-trading-text"]}
           "details"]]
         (into
          [:div {:class ["mt-3" "space-y-3"]}]
          (map-indexed step-row steps))]
        (when-let [message (get-in progress [:error :message])]
          [:p {:class ["mt-2"
                       "rounded-md"
                       "border"
                       "border-error/40"
                       "bg-error/10"
                       "px-2"
                       "py-1.5"
                       "text-[0.8125rem]"
                       "text-error"]
               :data-role "portfolio-optimizer-progress-error"}
           message])]))))
