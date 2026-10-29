(ns hyperopen.views.portfolio.vol-range.headline
  "Headline card of the Vol Range report (HO-SPEC-014 v2.1 sections 12.3,
  12.4, 12.6, 12.7): Net tracked asset P&L, Hold P&L and Ladder
  contribution versus hold for the selected window, the benchmark note and
  the exact window, or the status that says why the figures are hidden;
  the All tracked markets total note and the covered-subset control. The
  figures stack at phone width. Renders the view model only."
  (:require [hyperopen.vol-range.application.report-copy :as copy]))

(def ^:private figure-order [:net-pnl :hold-pnl :contribution])

(defn- tone-classes [tone]
  (case tone
    :positive ["text-success"]
    :negative ["text-error"]
    ["text-ho-text"]))

(defn- figure [k {:keys [label display tone]}]
  [:div {:class ["flex" "flex-col" "gap-0.5" "rounded-md" "border" "border-ho-border" "p-2"]
         :data-role (str "vol-range-report-" (name k))
         :data-tone (name tone)}
   [:dt {:class ["text-xs" "text-ho-text-secondary"]} label]
   [:dd {:class (into ["num" "text-base" "font-semibold"] (tone-classes tone))
         :data-role (str "vol-range-report-" (name k) "-value")}
    display]])

(defn headline-card
  [{:keys [status title figures benchmark-label range-text message no-total subset-offer]}]
  (when (or figures message no-total subset-offer)
    [:section {:class ["flex" "flex-col" "gap-2" "rounded-md" "border" "border-ho-border" "p-3"]
               :aria-label title
               :data-role "vol-range-report-headline"
               :data-status (name status)}
     (when title
       [:h3 {:class ["text-sm" "font-semibold" "text-ho-text"] :data-role "vol-range-report-headline-title"} title])
     (when range-text
       [:p {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-range-report-range"} range-text])
     (when figures
       [:dl {:class ["grid" "grid-cols-1" "gap-2" "sm:grid-cols-3"]}
        (for [k figure-order :let [f (get figures k)] :when f]
          ^{:key (name k)} (figure k f))])
     (when benchmark-label
       [:p {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-report-benchmark"} copy/benchmark-note])
     (when message
       [:p {:class ["text-sm" "text-ho-text-secondary"] :role "status" :data-role "vol-range-report-suppressed"}
        message])
     (when no-total
       [:p {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-range-report-no-total"} no-total])
     (when subset-offer
       (let [{:keys [label active?]} subset-offer]
         [:div
          [:button {:type "button"
                    :class ["min-h-[24px]" "rounded-md" "border" "border-ho-border" "px-2.5" "py-1" "text-xs"
                            "text-ho-text" "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"]
                    :aria-pressed (if active? "true" "false")
                    :data-role "vol-range-report-covered-subset"
                    :on {:click [[:actions/set-vol-range-report-covered-subset (not active?)]]}}
           label]]))]))
