(ns hyperopen.views.portfolio.optimize.run-status-banner
  "Layer 1 of the run-feedback pattern: a full-width, sticky status banner that
  makes an in-flight optimization the page's primary state. It pins to the top of
  the scroll viewport (see .optimizer-run-banner in setup.css) so the user always
  sees that a run started, which step it is on, and how long it has taken —
  independent of scroll position, objective type, or whether the right rail has
  been pushed below the fold. The right-rail progress/readiness panels remain as
  Layer 2 detail; the Run button is Layer 3 (local action state). The redundancy
  is intentional: primary state must be visible where the user is looking.

  Canonical progress SHAPE + transitions live in
  hyperopen.portfolio.optimizer.application.progress; this namespace only reads
  it for display."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]))

(defn- clamp-percent
  [value]
  (let [n (if (number? value) value 0)]
    (-> (if (and (not (js/isNaN n))
                 (js/isFinite n))
          n
          0)
        (max 0)
        (min 100))))

(defn elapsed-seconds
  "Wall-clock seconds since the run started. While running, the progress ticker
  writes a fresh :now-ms each frame so the clock advances smoothly between the
  worker's bursty messages; fall back to the live clock before the first tick."
  [progress]
  (let [started (:started-at-ms progress)
        end-ms (or (:completed-at-ms progress) (:now-ms progress) (.now js/Date))]
    (when (number? started)
      (max 0 (/ (- end-ms started) 1000)))))

(defn format-seconds
  [seconds]
  (when (number? seconds)
    (str (.toFixed seconds 1) "s")))

(defn- count-assets
  [draft]
  (count (:universe draft)))

(defn display-percent
  "Secondary 'motion' number: the eased trickle value while running (so it keeps
  moving between the worker's sparse updates), else true progress. The honest
  primary indicator is the discrete step position — this is only a feel number."
  [progress status]
  (let [real (clamp-percent (:overall-percent progress))]
    (if (and (= :running status)
             (number? (:display-percent progress)))
      (max real (clamp-percent (:display-percent progress)))
      real)))

(def ^:private step-human-labels
  {:fetch-returns "Loading return history"
   :risk-model "Building risk model"
   :return-model "Building return model"
   :solve "Solving portfolio"
   :frontier "Mapping the efficient frontier"
   :diagnostics "Preparing results"})

(def ^:private step-short-labels
  {:fetch-returns "History"
   :risk-model "Risk model"
   :return-model "Returns"
   :solve "Solve"
   :frontier "Frontier"
   :diagnostics "Results"})

(defn- active-step-human-label
  "Primary headline label for the active step. The return-model step is where the
  user's views enter the forecast, so a views-aware (Black-Litterman) run names
  that explicitly instead of the generic 'Building return model'."
  [progress draft]
  (let [active (:active-step progress)]
    (cond
      (nil? active) "Finishing up"

      (and (= :return-model active)
           (= :black-litterman (get-in draft [:return-model :kind])))
      "Applying your return views"

      :else (get step-human-labels active "Working…"))))

(defn step-position
  "1-based index of the active step within the run's step list, and the total
  count — the honest 'Step 3 of 6' progress the banner leads with."
  [progress]
  (let [steps (vec (:steps progress))
        active (:active-step progress)
        idx (first (keep-indexed (fn [i step]
                                   (when (= active (:id step))
                                     i))
                                 steps))]
    {:index (when idx (inc idx))
     :count (count steps)}))

(defn- step-segment
  "One segment of the stepper. The segments double as the progress bar: completed
  steps read full (green), the running step fills by its own percent (amber, with
  a visible sliver so the pulse reads as activity), pending steps stay an empty
  track. Tone comes from :data-seg-status via token-based CSS."
  [idx step]
  (let [status (:status step)
        percent (clamp-percent (:percent step))
        running? (= :running status)
        succeeded? (= :succeeded status)
        fill (cond
               succeeded? 100
               running? (max percent 8)
               (= :failed status) 100
               :else 0)
        step-id (:id step)
        token (if (keyword? step-id) (name step-id) (str (or step-id idx)))]
    [:div {:class ["min-w-0" "flex-1" "space-y-1"]
           :data-role (str "portfolio-optimizer-run-banner-segment-" token)}
     [:div {:class ["h-1" "overflow-hidden" "rounded-full" "optimizer-run-banner__seg-track"]}
      [:div {:class ["optimizer-run-banner__seg-fill"
                     "h-full"
                     "rounded-full"
                     "transition-[width]"
                     "duration-500"
                     "ease-out"
                     (when running? "animate-pulse")]
             :data-seg-status (name (or status :pending))
             :style {:width (str fill "%")}}]]
     [:span {:class ["block"
                     "truncate"
                     "font-mono"
                     "text-[0.5625rem]"
                     "uppercase"
                     "tracking-[0.1em]"
                     (cond
                       running? "text-warning"
                       succeeded? "text-trading-muted"
                       :else "text-trading-muted/50")]}
      (get step-short-labels step-id (:label step))]]))

(defn- stepper
  [progress]
  (into [:div {:class ["mt-3" "flex" "items-start" "gap-1.5"]
               :data-role "portfolio-optimizer-run-banner-stepper"}]
        (map-indexed step-segment (vec (:steps progress)))))

(defn- detail-row
  "Technical per-step line for the details drawer: the solver-internal label +
  detail + percent the worker actually reported (what a diagnostics reader wants),
  as opposed to the human headline above."
  [idx step]
  (let [percent (clamp-percent (:percent step))
        status (:status step)
        step-id (:id step)
        token (if (keyword? step-id) (name step-id) (str (or step-id idx)))]
    [:div {:class ["flex" "items-baseline" "justify-between" "gap-3"
                   "font-mono" "text-[0.6875rem]"]
           :data-role (str "portfolio-optimizer-run-banner-detail-" token)}
     [:span {:class ["min-w-0" "truncate"
                     (case status
                       :running "text-warning"
                       :succeeded "text-trading-text"
                       :failed "text-error"
                       "text-trading-muted/60")]}
      [:span {:class ["text-trading-muted"]} (str (inc idx) ". ")]
      (:label step)
      (when (seq (:detail step))
        [:span {:class ["text-trading-muted"]} (str " · " (:detail step))])]
     [:span {:class ["shrink-0" "tabular-nums" "text-trading-muted"]}
      (str (.toFixed percent 0) "%")]]))

(defn- details-drawer
  [progress error-message]
  [:details {:class ["mt-2" "group"]
             :data-role "portfolio-optimizer-run-banner-details"}
   [:summary {:class ["flex" "items-center" "gap-1.5" "cursor-pointer" "select-none"
                      "list-none" "[&::-webkit-details-marker]:hidden"
                      "font-mono" "text-[0.6875rem]" "text-trading-muted"
                      "hover:text-trading-text"]}
    [:span {:class ["underline" "decoration-dotted" "underline-offset-2"]} "Details"]
    [:span {:class ["text-trading-muted/60" "transition-transform" "group-open:rotate-90"]
            :aria-hidden "true"} "▸"]]
   (into [:div {:class ["mt-2" "space-y-1"]}]
         (map-indexed detail-row (vec (:steps progress))))
   (when (seq error-message)
     [:p {:class ["mt-2" "border" "border-error/40" "bg-error/10" "px-2" "py-1.5"
                  "text-[0.75rem]" "text-error"]}
      error-message])])

(defn- snapshot-line
  "Which scenario the running solve belongs to — the same preset/objective/risk
  the right-rail contract card shows, so the banner is self-sufficient about what
  is being optimized without the user cross-referencing the rail."
  [draft]
  (let [{:keys [preset-label objective-label risk-label]}
        (optimizer-view-model/setup-summary-card-model draft {:labelize controls/labelize})]
    (->> [preset-label objective-label risk-label]
         (remove str/blank?)
         (str/join " · "))))

(defn- running-banner
  [{:keys [optimization-progress draft]}]
  (let [elapsed (elapsed-seconds optimization-progress)
        percent (display-percent optimization-progress :running)
        {:keys [index count]} (step-position optimization-progress)
        asset-count (count-assets draft)
        meta (->> [(when index (str "Step " index " of " count))
                   (when-let [s (format-seconds elapsed)] (str s " elapsed"))
                   (when (pos? asset-count) (str asset-count " assets"))]
                  (remove nil?)
                  (str/join " · "))]
    [:div
     [:div {:class ["flex" "flex-wrap" "items-baseline" "justify-between" "gap-x-3" "gap-y-0.5"]}
      [:p {:class ["font-mono" "text-[0.6875rem]" "font-semibold" "uppercase"
                   "tracking-[0.12em]" "text-warning"]}
       [:span {:class ["optimizer-run-banner__dot" "mr-1.5" "inline-block" "h-1.5" "w-1.5"
                       "animate-pulse"]
               :aria-hidden "true"}]
       "Optimizing portfolio"]
      [:span {:class ["font-mono" "text-[0.6875rem]" "text-trading-muted" "tabular-nums"]
              :data-role "portfolio-optimizer-run-banner-meta"}
       meta]]
     [:div {:class ["mt-1.5" "flex" "items-baseline" "justify-between" "gap-3"]}
      [:p {:class ["min-w-0" "truncate" "text-[0.9375rem]" "font-semibold" "text-trading-text"]
           :data-role "portfolio-optimizer-run-banner-headline"
           :aria-live "polite"}
       (active-step-human-label optimization-progress draft)]
      [:span {:class ["shrink-0" "font-mono" "text-[0.9375rem]" "font-semibold"
                      "tabular-nums" "text-warning"]}
       (str (.toFixed percent 0) "%")]]
     (stepper optimization-progress)
     [:div {:class ["mt-2.5" "flex" "flex-wrap" "items-center" "justify-between" "gap-x-4" "gap-y-1"]}
      [:p {:class ["min-w-0" "truncate" "text-[0.75rem]" "text-trading-muted"]
           :data-role "portfolio-optimizer-run-banner-snapshot"}
       (snapshot-line draft)]
      (details-drawer optimization-progress nil)]]))

(defn- succeeded-banner
  [_ctx]
  [:div {:class ["flex" "flex-wrap" "items-baseline" "gap-x-3" "gap-y-1"]}
   [:p {:class ["font-mono" "text-[0.6875rem]" "font-semibold" "uppercase"
                "tracking-[0.12em]" "text-success"]}
    [:span {:class ["optimizer-run-banner__dot" "mr-1.5" "inline-block" "h-1.5" "w-1.5"]
            :aria-hidden "true"}]
    "Optimization complete"]
   [:p {:class ["text-[0.9375rem]" "font-semibold" "text-trading-text"]
        :data-role "portfolio-optimizer-run-banner-headline"
        :aria-live "polite"}
    "Opening results…"]])

(defn- failed-banner
  [{:keys [run-state optimization-progress draft]}]
  (let [error (:error run-state)
        message (or (:message error)
                    (get-in optimization-progress [:error :message])
                    "The optimizer could not complete this run.")
        code (some-> (:code error) name)]
    [:div
     [:div {:class ["flex" "flex-wrap" "items-baseline" "justify-between" "gap-x-3" "gap-y-0.5"]}
      [:p {:class ["font-mono" "text-[0.6875rem]" "font-semibold" "uppercase"
                   "tracking-[0.12em]" "text-error"]}
       [:span {:class ["optimizer-run-banner__dot" "mr-1.5" "inline-block" "h-1.5" "w-1.5"]
               :aria-hidden "true"}]
       "Optimization could not complete"]
      (when code
        [:span {:class ["font-mono" "text-[0.625rem]" "uppercase"
                        "tracking-[0.08em]" "text-error/80"]}
         code])]
     [:p {:class ["mt-1.5" "text-[0.9375rem]" "font-semibold" "text-trading-text"]
          :data-role "portfolio-optimizer-run-banner-headline"
          :aria-live "polite"}
      message]
     [:div {:class ["mt-2.5" "flex" "flex-wrap" "items-center" "justify-between" "gap-x-4" "gap-y-2"]}
      [:button {:type "button"
                :class ["border" "border-warning/70" "bg-warning/80" "px-4" "py-1.5"
                        "text-[0.8125rem]" "font-semibold" "text-base-100"]
                :data-role "portfolio-optimizer-run-banner-retry"
                :disabled (empty? (:universe draft))
                :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
       "Retry optimization"]
      (details-drawer optimization-progress message)]]))

(defn- tone
  [{:keys [running? succeeded?]}]
  (cond running? :running succeeded? :succeeded :else :failed))

(defn run-status-banner
  "Sticky primary run-status banner. Renders while a run is in flight, on the
  brief success beat before the app reveals results, and on a hard worker/solve
  failure. Infeasible & unsupported results have a dedicated infeasible banner and
  readiness guidance, so they are intentionally NOT surfaced here to avoid two
  competing banners."
  [{:keys [optimization-progress run-state draft] :as ctx}]
  (let [progress-status (:status optimization-progress)
        run-status (:status run-state)
        running? (= :running progress-status)
        succeeded? (and (not running?) (= :succeeded progress-status))
        hard-failed? (and (not running?) (= :failed run-status))
        visible? (or running? succeeded? hard-failed?)]
    (when visible?
      (let [t (tone {:running? running? :succeeded? succeeded?})]
        [:section {:class ["optimizer-run-banner"]
                   :data-role "portfolio-optimizer-run-banner"
                   :data-run-tone (name t)}
         (case t
           :running (running-banner ctx)
           :succeeded (succeeded-banner ctx)
           :failed (failed-banner ctx))]))))
