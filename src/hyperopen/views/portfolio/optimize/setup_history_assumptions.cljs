(ns hyperopen.views.portfolio.optimize.setup-history-assumptions
  "History-assumptions section shell: the collapsed-by-exception disclosure, the
  aggregate strips that belong to the whole workflow (loading, bulk accept,
  agent file, manual entry point), and the queue itself.

  The section is a QUEUE (designer restructure 2026-08-14, option 1c): a fixed
  asset rail, one question at a time, the recommended model leading, manual
  controls opt-in. Chrome lives in setup-history-assumption-queue; the editable
  field controls in setup-history-assumption-fields. Views render the queue
  view-model and dispatch its carried action ids — no history math here."
  (:require [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]
            [hyperopen.views.portfolio.optimize.setup-history-assumption-queue :as queue]
            [hyperopen.views.portfolio.optimize.setup-history-assumption-recommendations :as recommendations]
            [hyperopen.views.portfolio.optimize.setup-history-assumptions-io :as assumptions-io]))

(defn- add-asset-select
  "Manual entry point: bring ANY selected asset into the queue. The thresholds
  only auto-flag egregious cases; the user may judge an asset statistically
  unsound (a young listing, a stubby return stream) on their own and factor-load
  it regardless. Choosing an asset seeds proxy mode - it joins the rail
  immediately."
  [addable-assets]
  (when (seq addable-assets)
    ;; Full-width own row (see history-assumptions-section) so the placeholder
    ;; "+ Model an asset with proxies…" is never clipped horizontally. py-0 +
    ;; leading-none: optimizer surfaces pin inputs/selects to a fixed 26px
    ;; border-box (optimizer/base.css) while @tailwindcss/forms adds 0.5rem
    ;; vertical padding to every <select> - left alone it clips the text, so
    ;; py-0 overrides it; pr-7 leaves room for the native chevron.
    (into [:select {:class ["w-full" "border" "border-base-300" "bg-base-100/80"
                            "py-0" "pl-2" "pr-7" "font-mono" "text-[0.75rem]" "leading-none"
                            "text-trading-muted"]
                    :value ""
                    :data-role "portfolio-optimizer-history-assumption-workflow-add"
                    :on {:change [[:actions/set-portfolio-optimizer-history-assumption-mode
                                   [:event.target/value] :proxy]]}}
           [:option {:value ""} "+ Model an asset with proxies…"]]
          ;; Options arrive sorted ascending by native return-day count with the
          ;; count in the label, so the assets limiting the shared covariance
          ;; window sit at the top of the list.
          (map (fn [{:keys [instrument-id label option-label]}]
                 [:option {:value instrument-id} (or option-label label)]))
          addable-assets)))

(defn- loading-banner
  "Aggregate \"background work in progress\" strip: while any asset's proxy
  history is still fetching, tell the user the queue below will fill in on its
  own so they don't start re-editing provisional state."
  [loading-count]
  (when (pos? loading-count)
    [:div {:class ["flex" "items-center" "gap-2" "border" "border-base-300"
                   "bg-base-200/40" "px-2" "py-1.5"]
           :data-role "portfolio-optimizer-history-assumptions-loading-banner"}
     [:span {:class ["h-1.5" "w-1.5" "shrink-0" "animate-pulse" "rounded-full"
                     "bg-warning"]
             :aria-hidden "true"}]
     [:span {:class ["font-mono" "text-[0.6875rem]" "text-trading-muted"]}
      (str "Loading proxy history for " loading-count
           (if (= 1 loading-count) " asset" " assets")
           " — the queue updates automatically when it finishes.")]]))

(defn- section-trailing-status
  "Collapsed-header status for the section: the one line that must carry the
  whole story while the disclosure rests closed. Wrapped in
  `optimizer-section-trailing` by the caller so it hides while open (the queue
  header then shows the same counter in full)."
  [{:keys [card-count settled-count loading-count]}]
  (cond
    (pos? loading-count)
    {:label (str "Loading proxy history for " loading-count
                 (if (= 1 loading-count) " asset…" " assets…"))
     :tone "text-trading-muted"}

    (zero? card-count)
    {:label "None needed"
     :tone "text-trading-muted/70"}

    (= settled-count card-count)
    {:label (str settled-count " of " card-count " settled")
     :tone "text-success"}

    :else
    {:label (str (- card-count settled-count)
                 (if (= 1 (- card-count settled-count))
                   " asset needs setup"
                   " assets need setup"))
     :tone "text-warning"}))

(defn history-assumptions-section
  "Collapsed-by-exception disclosure panel. When every asset in the queue is
  settled the section rests as one summary line (\"History assumptions — N of N
  settled\"); it forces itself :open whenever anything needs the user (assets
  still unsettled, proxy history still loading) — a machine condition, so
  re-asserting :open against the user's toggle is the point, same shape as the
  More-goals drawer. Everything inside (agent IO toolbar, picker, queue) is
  demoted with the body, never removed."
  [{:keys [state draft readiness history-load-state]}]
  (let [{:keys [addable-assets applicable? card-count settled-count left-count
                history-loading-count]
         :as queue-model}
        (optimizer-view-model/history-assumption-queue-model
         state draft readiness history-load-state
         {:percent-label controls/percent-label})
        loading-count (or history-loading-count 0)
        needs-attention? (boolean (or (pos? loading-count) (pos? left-count)))
        trailing (section-trailing-status {:card-count card-count
                                           :settled-count settled-count
                                           :loading-count loading-count})]
    (when (or applicable? (seq addable-assets))
      [:details (cond-> {:class ["optimizer-setup-panel" "border" "border-base-300"
                                 "bg-base-100/90" "p-3"]
                         :data-role "portfolio-optimizer-history-assumptions-section"
                         :id "portfolio-optimizer-history-assumptions-section"
                         :replicant/key "history-assumptions-section"}
                  needs-attention? (assoc :open true))
       [:summary {:class ["cursor-pointer" "select-none" "focus:outline-none"
                          "focus:text-warning"]}
        [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
         ;; Result vocabulary in the title ("History assumptions" — same concept
         ;; the rail panel summarizes); the mechanism word "proxy" stays in the
         ;; body (queue detail, "Behaves like" field, picker).
         [:p {:class controls/section-title-class} "History assumptions"]
         ;; The count role only exists while assets are in the queue (pinned
         ;; contract); the zero-card resting label stays role-less.
         [:span (cond-> {:class ["optimizer-section-trailing" "shrink-0" "whitespace-nowrap"
                                 "font-mono" "text-[0.6875rem]" (:tone trailing)]}
                  (pos? card-count)
                  (assoc :data-role "portfolio-optimizer-history-assumptions-count"))
          (:label trailing)]]]
       [:div {:class ["mt-3" "space-y-3"]}
        ;; The work leads. Background state and the one-click bulk accept sit
        ;; above the queue because both change what the queue still has to ask;
        ;; the file tooling and the manual entry point are accelerators, so they
        ;; trail it rather than standing between the user and the question.
        (loading-banner loading-count)
        (recommendations/recommended-banner queue-model)
        (if (pos? card-count)
          (queue/queue queue-model)
          (queue/empty-note))
        [:div {:class ["space-y-2" "border-t" "border-base-300" "pt-3"]}
         (assumptions-io/io-toolbar
          {:asset-count card-count
           :universe-count (+ card-count (count addable-assets))})
         (assumptions-io/io-note
          (get-in state contracts/ui-history-assumptions-io-note-path))
         ;; The picker gets its OWN full-width row so the placeholder text is
         ;; never clipped by the toolbar competing for width.
         (add-asset-select addable-assets)]]])))
