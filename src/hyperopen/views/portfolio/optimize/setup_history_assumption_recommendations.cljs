(ns hyperopen.views.portfolio.optimize.setup-history-assumption-recommendations
  "Section-level bulk apply for backend-recommended history assumptions: settle
  every asset the server already has a defensible model for in one click, so
  the queue below only has to ask about the ones that genuinely need a person.

  The per-asset recommendation now leads the queue's own model panel (queue
  restructure, 2026-08-14) — this namespace kept only the aggregate shortcut.")

(defn recommended-banner
  "Section strip offering the one-click bulk apply when any card carries an
  applicable server recommendation."
  [{:keys [recommended-count recommended-actions]}]
  (when (pos? (or recommended-count 0))
    [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2"
                   "border" "border-success/40" "bg-success/10" "px-2" "py-1.5"]
           :data-role "portfolio-optimizer-history-assumptions-recommended-banner"}
     [:span {:class ["text-[0.6875rem]" "text-trading-text"]}
      (str "A model is already worked out for " recommended-count
           (if (= 1 recommended-count) " asset" " assets")
           " — accept now, change anything later.")]
     [:button {:type "button"
               :class ["border" "border-success/50" "bg-success/10" "px-2" "py-1"
                       "font-mono" "text-[0.6875rem]" "font-semibold" "uppercase"
                       "tracking-[0.08em]" "text-success"]
               :data-role "portfolio-optimizer-history-assumptions-apply-all-recommended"
               :on {:click [[(:apply-all recommended-actions)]]}}
      (if (= 1 recommended-count) "Accept it" (str "Accept all " recommended-count))]]))
