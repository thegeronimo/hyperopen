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

(defn- elapsed-seconds
  [progress]
  (let [started (:started-at-ms progress)
        completed (:completed-at-ms progress)
        end-ms (or completed (.now js/Date))]
    (when (number? started)
      (/ (- end-ms started) 1000))))

(defn- format-seconds
  [seconds]
  (if (number? seconds)
    (str (.toFixed seconds 1) "s")
    "n/a"))

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
        row-id (or (:id step) idx)
        row-token (if (keyword? row-id)
                    (name row-id)
                    (str row-id))]
    [:div {:class ["space-y-1.5"]
           :data-role (str "portfolio-optimizer-progress-step-" row-token)}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3" "text-xs"]}
      [:p {:class ["min-w-0" "font-mono" "font-semibold" "text-trading-text"]}
       [:span {:class ["text-trading-muted"]} (str (inc idx) ". ")]
       (:label step)
       (when (seq (:detail step))
         [:span {:class ["text-trading-muted"]} (str " · " (:detail step))])]
      [:span {:class ["font-mono" "text-[0.65rem]" "text-trading-muted"]}
       (str (.toFixed percent 0) "%")]]
     [:div {:class ["h-1.5" "overflow-hidden" "rounded-full" "bg-base-300/60"]}
      [:div {:class ["h-full" (step-tone-class step)]
             :style {:width (str percent "%")}}]]]))

(defn progress-panel
  [progress]
  (let [status (:status progress)
        visible? (contains? #{:running :succeeded :failed} status)
        steps (vec (:steps progress))
        elapsed (elapsed-seconds progress)]
    (when visible?
      [:section {:class ["mt-4"
                         "rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-100/95"
                         "p-3"
                         "shadow-[0_0_0_1px_rgba(255,255,255,0.02)]"]
                 :data-role "portfolio-optimizer-progress-panel"}
       [:div {:class ["flex" "items-center" "justify-between" "gap-3" "border-b" "border-base-300" "pb-2"]}
        [:p {:class ["font-mono"
                     "text-[0.65rem]"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.18em]"
                     "text-trading-muted"]}
         (title-label status)]
        [:span {:class ["rounded-sm"
                        "border"
                        (if (= :failed status) "border-error/60" "border-warning/60")
                        "px-1.5"
                        "py-0.5"
                        "font-mono"
                        "text-[0.6rem]"
                        "font-semibold"
                        "uppercase"
                        "tracking-[0.12em]"
                        (if (= :failed status) "text-error" "text-warning")]}
         (status-label status)]]
       (into
        [:div {:class ["mt-3" "space-y-3"]}]
        (map-indexed step-row steps))
       [:p {:class ["mt-3" "font-mono" "text-[0.65rem]" "text-trading-muted"]
            :data-role "portfolio-optimizer-progress-footer"}
        (str "elapsed " (format-seconds elapsed)
             " · overall " (.toFixed (clamp-percent (:overall-percent progress)) 0) "%")]
       (when-let [message (get-in progress [:error :message])]
         [:p {:class ["mt-2"
                      "rounded-md"
                      "border"
                      "border-error/40"
                      "bg-error/10"
                      "px-2"
                      "py-1.5"
                      "text-xs"
                      "text-error"]
              :data-role "portfolio-optimizer-progress-error"}
          message])])))
