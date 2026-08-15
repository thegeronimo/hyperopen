(ns hyperopen.views.portfolio.optimize.setup-history-assumption-queue
  "The History-assumptions queue (designer restructure 2026-08-14, option 1c):
  a fixed asset rail that never scrolls, one question at a time, the
  recommended model leading, and manual controls opt-in.

  Views render the queue view-model and dispatch its carried action ids. Both
  disclosures here are DOM state, never :open from app state - the model-detail
  toggle is keyed constant so it survives moving down the rail, and the
  adjust-by-hand drawer is keyed per asset so it closes itself the moment the
  queue advances."
  (:require [hyperopen.views.portfolio.optimize.setup-history-assumption-fields :as fields]))

(defn- queue-header
  [{:keys [settled-label left-label]}]
  [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
   [:p {:class ["max-w-prose" "text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]}
    "Work the queue. The rail below never scrolls, so you always know what is left."]
   [:div {:class ["shrink-0" "text-right"]}
    [:p {:class ["font-mono" "text-[0.6875rem]" "font-semibold" "uppercase"
                 "tracking-[0.1em]" "text-trading-muted"]
         :data-role "portfolio-optimizer-history-assumptions-settled"}
     settled-label]
    [:p {:class ["mt-1" "text-[0.6875rem]" "text-trading-muted/70"]}
     left-label]]])

(defn- asset-rail
  "Every asset in the workflow, always visible. The dot carries progress at a
  glance; clicking a pill moves the queue without settling anything."
  [pills]
  (into [:div {:class ["optimizer-assumption-queue__rail"]
               :data-role "portfolio-optimizer-history-assumptions-rail-pills"}]
        (map (fn [{:keys [instrument-id label native-label tone active? action
                          status-label]}]
               [:button {:type "button"
                         :class ["optimizer-assumption-pill"]
                         :aria-pressed (if active? "true" "false")
                         :title (str label " — " status-label)
                         :data-role (str "portfolio-optimizer-history-assumption-pill-"
                                         instrument-id)
                         :on {:click [action]}}
                [:span {:class ["optimizer-assumption-dot"]
                        :data-tone tone
                        :aria-hidden "true"}]
                [:span {:class ["font-mono" "text-[0.75rem]" "font-semibold"]} label]
                [:span {:class ["font-mono" "text-[0.625rem]" "text-trading-muted/70"]}
                 native-label]]))
        pills))

(defn- detail-block
  "Every diagnostic behind ONE toggle. The head stays readable while closed —
  it carries the model line itself — so opening is a choice to see the
  workings, never the only way to read the answer."
  [{:keys [model detail-rows detail-footnote]}]
  [:details {:class ["optimizer-assumption-queue__detail-shell"]
             :replicant/key "history-assumption-model-detail"
             :data-role "portfolio-optimizer-history-assumption-model-detail"}
   [:summary {:class ["optimizer-plain-summary" "cursor-pointer" "select-none"
                      "focus:outline-none"]}
    [:span {:class ["flex" "flex-wrap" "items-baseline" "justify-between" "gap-2"]}
     [:span {:class ["font-mono" "text-[0.6875rem]" "font-semibold" "uppercase"
                     "tracking-[0.1em]" "text-warning"]}
      (:eyebrow model)]
     [:span {:class ["optimizer-section-trailing" "font-mono" "text-[0.625rem]"
                     "font-semibold" "uppercase" "tracking-[0.08em]"
                     "text-trading-muted"]}
      "Show model detail"]
     [:span {:class ["optimizer-section-open-only" "font-mono" "text-[0.625rem]"
                     "font-semibold" "uppercase" "tracking-[0.08em]"
                     "text-trading-muted"]}
      "Hide model detail"]]
    [:span {:class ["mt-2" "block" "font-mono" "text-[0.8125rem]" "font-medium"
                    "leading-[1.5]" "text-trading-text"]
            :data-role "portfolio-optimizer-history-assumption-model-line"}
     (:line model)]
    (when (:why model)
      [:span {:class ["mt-1" "block" "text-[0.6875rem]" "leading-[1.45]"
                      "text-trading-muted"]}
       (:why model)])]
   (into [:div {:class ["optimizer-assumption-queue__detail" "mt-3" "border-t"
                        "border-base-300" "pt-3"]}]
         (mapcat (fn [{:keys [key label value]}]
                   [[:span {:class ["font-mono" "text-[0.625rem]" "font-semibold"
                                    "uppercase" "tracking-[0.08em]"
                                    "text-trading-muted/70" "whitespace-nowrap"]
                            :data-role (str "portfolio-optimizer-history-assumption-detail-"
                                            key)}
                     label]
                    [:span {:class ["text-[0.75rem]" "leading-[1.4]" "text-trading-muted"]}
                     value]]))
         detail-rows)
   [:p {:class ["mt-2.5" "text-[0.6875rem]" "leading-[1.5]" "text-trading-muted/70"]}
    detail-footnote]])

(defn- model-panel
  "Recommendation first: the model the queue proposes (or the one already
  authored), what it rests on, and the single click that settles it."
  [active]
  (let [model (:model active)]
    [:div {:class ["optimizer-assumption-queue__model" "mt-3" "border"
                   "border-primary/60" "bg-primary/10" "p-3"]
           :data-role (str "portfolio-optimizer-history-assumption-model-"
                           (:instrument-id active))
           :data-model-kind (name (:kind model))}
     (detail-block active)
     (when (:unavailable-note model)
       [:p {:class ["mt-2" "text-[0.6875rem]" "leading-[1.45]" "text-trading-muted"]
            :data-role (str "portfolio-optimizer-history-assumption-recommendation-unavailable-"
                            (:instrument-id active))}
        (:unavailable-note model)])
     [:div {:class ["mt-3" "flex" "flex-wrap" "items-center" "gap-2"]}
      [:button (cond-> {:type "button"
                        :class ["optimizer-primary-action" "border" "border-warning/70"
                                "bg-warning/80" "px-3" "py-1.5" "text-[0.75rem]"
                                "font-semibold" "text-base-100"
                                "disabled:cursor-not-allowed" "disabled:opacity-40"]
                        :data-role (:cta-role model)}
                 (:cta-action model) (assoc :on {:click [(:cta-action model)]})
                 (or (:cta-disabled? model) (nil? (:cta-action model)))
                 (assoc :disabled true))
       (:cta model)]
      ;; Reset is only meaningful once something is authored; an asset still on
      ;; its recommendation has nothing of the user's to throw away. It re-seeds
      ;; the approach's own defaults — NOT the backend recommendation — so the
      ;; label must not promise the latter.
      (when (:mode active)
        [:button {:type "button"
                  :class ["border" "border-base-300" "px-3" "py-1.5" "text-[0.75rem]"
                          "font-semibold" "text-trading-muted" "hover:text-trading-text"]
                  :data-role (str "portfolio-optimizer-history-assumption-reset-"
                                  (:instrument-id active))
                  :on {:click [[(get-in active [:actions :reset]) (:instrument-id active)]]}}
         "Reset to defaults"])
      (when (:history-loading? active)
        [:p {:class ["animate-pulse" "text-[0.6875rem]" "text-trading-muted"]
             :data-role (str "portfolio-optimizer-history-assumption-apply-loading-"
                             (:instrument-id active))}
         "Waiting for proxy history to load…"])]]))

(defn- adjust-drawer
  "Manual controls, opt-in. Keyed on the asset so advancing the queue always
  hands the next question its own closed drawer."
  [active]
  (let [id (:instrument-id active)]
    [:details {:class ["optimizer-assumption-queue__adjust" "mt-2" "border"
                       "border-base-300" "bg-base-200/20"]
               :replicant/key (str "history-assumption-adjust-" id)
               :data-role (str "portfolio-optimizer-history-assumption-adjust-" id)}
     [:summary {:class ["optimizer-plain-summary" "flex" "cursor-pointer" "select-none"
                        "items-center" "justify-between" "gap-2" "p-2"
                        "focus:outline-none" "focus:text-warning"]}
      [:span {:class ["text-[0.75rem]" "font-semibold" "text-trading-muted"]}
       "Adjust by hand"]
      [:span {:class ["optimizer-section-trailing" "font-mono" "text-[0.625rem]"
                      "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
       "Open"]
      [:span {:class ["optimizer-section-open-only" "font-mono" "text-[0.625rem]"
                      "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
       "Close"]]
     [:div {:class ["border-t" "border-base-300" "p-2"]}
      (fields/manual-controls active)
      [:div {:class ["mt-3" "flex" "items-center" "justify-end" "border-t"
                     "border-base-300" "pt-2"]}
       (when (:mode active)
         [:button {:type "button"
                   :class ["text-[0.6875rem]" "uppercase" "tracking-[0.08em]"
                           "text-trading-muted" "hover:text-warning"]
                   :data-role (str "portfolio-optimizer-history-assumption-clear-" id)
                   :on {:click [[(get-in active [:actions :clear]) id]]}}
          "Clear"])]]]))

(defn- card-errors
  [active]
  (when (seq (:errors active))
    (into [:ul {:class ["mt-2" "space-y-1" "text-[0.75rem]" "text-warning"]
                :data-role (str "portfolio-optimizer-history-assumption-errors-"
                                (:instrument-id active))}]
          (map (fn [message] [:li message]) (:errors active)))))

(defn- queue-footer
  [{:keys [prev next left-label advance]}]
  [:div {:class ["mt-3" "flex" "flex-wrap" "items-center" "justify-between"
                 "gap-2" "border-t" "border-base-300" "pt-2.5"]}
   [:div
    (when prev
      [:button {:type "button"
                :class ["border" "border-base-300" "px-3" "py-1.5" "text-[0.75rem]"
                        "font-semibold" "text-trading-muted" "hover:text-trading-text"]
                :data-role "portfolio-optimizer-history-assumptions-queue-prev"
                :on {:click [(:action prev)]}}
       (str "◂ Prev · " (:label prev))])]
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
    [:span {:class ["text-[0.6875rem]" "text-trading-muted/70"]} left-label]
    (when next
      [:button {:type "button"
                :class ["border" "border-base-300" "px-3" "py-1.5" "text-[0.75rem]"
                        "font-semibold" "text-trading-muted" "hover:text-trading-text"]
                :data-role "portfolio-optimizer-history-assumptions-queue-skip"
                :on {:click [(:action next)]}}
       "Skip for now"])
    [:button (cond-> {:type "button"
                      :class ["optimizer-primary-action" "border" "border-warning/70"
                              "bg-warning/80" "px-3" "py-1.5" "text-[0.75rem]"
                              "font-semibold" "text-base-100"
                              "disabled:cursor-not-allowed" "disabled:opacity-40"]
                      :data-role "portfolio-optimizer-history-assumptions-queue-advance"
                      :on {:click (vec (:actions advance))}}
               (:disabled? advance) (assoc :disabled true))
     (str (:label advance) " ▸")]]])

(defn- status-chip
  "Status pill for the asset under the question. While this asset's proxy
  history is still fetching, ANY status verdict is provisional (completeness
  judged against a not-yet-loaded usable set can even read Configured), so the
  chip reads a pulsing \"Loading history…\" until the fetch settles."
  [active]
  (let [id (:instrument-id active)
        ;; Green is reserved for a DECISION the user made. Engine-backed but
        ;; unaccepted still owes a turn, so it reads amber like everything else
        ;; the queue is asking about.
        settled? (boolean (:settled? active))
        loading? (boolean (:history-loading? active))]
    [:span (cond-> {:class ["shrink-0" "border" "px-1.5" "py-0.5" "font-mono"
                            "text-[0.625rem]" "font-semibold" "uppercase"
                            "tracking-[0.1em]"
                            (cond loading? "border-base-300"
                                  settled? "border-success/50"
                                  :else "border-warning/50")
                            (cond loading? "text-trading-muted"
                                  settled? "text-success"
                                  :else "text-warning")
                            (cond loading? "bg-base-200/40"
                                  settled? "bg-success/10"
                                  :else "bg-warning/10")
                            (when loading? "animate-pulse")]
                    :data-role (str "portfolio-optimizer-history-assumption-status-" id)
                    ;; The raw card status rides on untouched: it is the pinned
                    ;; engine-backing contract, independent of the queue's
                    ;; decision vocabulary above.
                    :data-status (some-> (:status active) name)}
             loading? (assoc :data-loading "true"))
     (if loading? "Loading history…" (:queue-status-label active))]))

(defn- active-question
  [active]
  [:section {:class ["mt-3" "border" "border-base-300" "bg-base-100" "p-3"]
             :data-role (:role active)
             :replicant/key "history-assumption-active"}
   [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
    [:div {:class ["min-w-0"]}
     [:p {:class ["text-[0.9375rem]" "font-semibold" "leading-[1.3]" "text-trading-text"]
          :data-role "portfolio-optimizer-history-assumption-question"}
      (:question active)]
     [:p {:class ["mt-1" "text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]}
      (:native-line active)]]
    (status-chip active)]
   ;; The agent's stated reason for an imported configuration — the trust
   ;; artifact that lets the user audit a file-authored basket at a glance.
   (when (:rationale active)
     [:p {:class ["mt-2" "border" "border-base-300" "bg-base-200/20" "p-2"
                  "text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]
          :data-role (str "portfolio-optimizer-history-assumption-rationale-"
                          (:instrument-id active))}
      (str "Agent rationale: " (:rationale active))])
   (card-errors active)
   (model-panel active)
   (adjust-drawer active)])

(defn queue
  "The whole queue: header counter, fixed rail, the one open question, footer."
  [{:keys [pills active] :as model}]
  [:div {:class ["space-y-3"]
         :data-role "portfolio-optimizer-history-assumptions-queue"}
   (queue-header model)
   (asset-rail pills)
   (when active (active-question active))
   (when active (queue-footer model))])

(defn empty-note
  []
  [:p {:class ["text-[0.75rem]" "leading-[1.5]" "text-trading-muted"]
       :data-role "portfolio-optimizer-history-assumptions-empty"}
   (str "Nothing needs assumptions right now. Pick an asset above to model it "
        "as a basket of assets it behaves like - its returns are then driven by "
        "the basket plus specific risk instead of its own short history.")])
