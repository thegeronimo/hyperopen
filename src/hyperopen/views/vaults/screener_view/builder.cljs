(ns hyperopen.views.vaults.screener-view.builder
  "Screener setup: universe, metric criteria, data quality and the estimate."
  (:require [hyperopen.views.vaults.list-view.format :as format]))

(def card-classes
  ["rounded-2xl" "border" "border-base-300/80" "bg-base-100/95" "p-4" "md:p-5"])

(defn- step-heading
  [id number title note]
  [:div {:class ["flex" "flex-wrap" "items-baseline" "justify-between" "gap-2"]}
   [:h2 {:id id :class ["text-base" "font-medium" "text-trading-text"]}
    [:span {:class ["num" "mr-2" "text-ho-accent"]} number]
    title]
   (when note
     [:span {:class ["text-xs" "text-trading-text-secondary"]} note])])

(defn- field-label
  [text]
  [:div {:class ["text-xs" "text-trading-text-secondary"]} text])

(defn segmented
  [label items role]
  [:div {:role "group"
         :aria-label label
         :class ["flex" "flex-wrap" "gap-1.5"]
         :data-role role}
   (for [{:keys [value label active? action]} items]
     [:button {:type "button"
               :replicant/key (str role "-" value)
               :aria-pressed (boolean active?)
               :class (into ["min-h-9" "rounded-lg" "border" "px-3.5" "text-sm" "transition-colors"]
                            (concat (if active?
                                      ["border-ho-border-accent" "bg-ho-accent-soft" "text-ho-accent-bright"]
                                      ["border-base-300" "bg-transparent" "text-trading-text" "hover:bg-base-200"])
                                    format/focus-ring-classes))
               :on {:click [action]}}
      label])])

(defn- universe-section
  [{:keys [ages tvls include-deposited? universe-count base-count funnel-pct loading?]}]
  [:section {:aria-labelledby "screener-universe" :class (into ["flex" "flex-col" "gap-5"] card-classes)}
   (step-heading "screener-universe" "1" "Universe" "Free — uses the vault list already on this page")
   [:div {:class ["flex" "flex-col" "gap-2"]}
    (field-label "Active for at least")
    (segmented "Active for at least" ages "vault-screener-age")]
   [:div {:class ["flex" "flex-col" "gap-2"]}
    (field-label "Minimum TVL")
    (segmented "Minimum TVL" tvls "vault-screener-tvl")]
   [:div {:class ["flex" "flex-wrap" "gap-5" "text-sm"]}
    [:label {:class ["inline-flex" "items-center" "gap-2" "text-trading-text-secondary"]}
     [:input {:type "checkbox" :checked true :disabled true
              :class ["h-4" "w-4" "rounded" "border-base-300" "text-ho-accent"]}]
     "Exclude closed vaults"]
    [:label {:class ["inline-flex" "items-center" "gap-2" "text-trading-text"]}
     [:input {:type "checkbox"
              :checked (boolean include-deposited?)
              :class ["h-4" "w-4" "rounded" "border-base-300" "bg-base-100" "text-ho-accent"]
              :data-role "vault-screener-include-deposited"
              :on {:change [[:actions/set-vault-screener-control :include-deposited? [:event.target/checked]]]}}]
     "Always include vaults I've deposited in"]]
   [:div {:class ["flex" "flex-col" "gap-2.5" "rounded-xl" "border" "border-base-300" "bg-ho-bg" "px-4" "py-3.5"]}
    [:div {:class ["flex" "flex-wrap" "items-baseline" "justify-between" "gap-2"]}
     (if loading?
       [:div {:class ["text-sm" "text-trading-text-secondary"]} "Loading vaults…"]
       [:div {:class ["text-sm"] :data-role "vault-screener-universe-count"}
        [:span {:class ["num" "text-[20px]" "text-ho-accent-bright"]} (str universe-count)]
        [:span {:class ["text-trading-text-secondary"]} " of "]
        [:span {:class ["num"]} (str base-count)]
        [:span {:class ["text-trading-text-secondary"]} " open vaults match"]])
     [:span {:class ["text-xs" "text-trading-text-secondary"]} "Child vaults roll up into their parent"]]
    [:div {:aria-hidden true :class ["h-1.5" "overflow-hidden" "rounded-full" "bg-base-300"]}
     [:div {:class ["h-full" "rounded-full" "bg-ho-accent"]
            :style {:width (str funnel-pct "%")}}]]]])

(defn- criterion-row
  [{:keys [metric label hint op unit value]}]
  [:div {:replicant/key (str "criterion-" (name metric))
         :class ["grid" "items-center" "gap-2" "rounded-xl" "border" "border-base-300" "bg-ho-bg"
                 "py-2" "pl-3.5" "pr-2" "grid-cols-[minmax(0,1fr)_20px_96px_44px]"
                 "sm:grid-cols-[minmax(0,1fr)_40px_132px_44px]"]
         :data-role "vault-screener-criterion"}
   [:div {:class ["flex" "min-w-0" "flex-col" "gap-0.5"]}
    [:span {:class ["text-sm" "text-trading-text"]} label]
    [:span {:class ["text-xs" "text-trading-text-secondary"]} hint]]
   [:span {:class ["num" "text-center" "text-sm" "text-trading-text-secondary"]} op]
   [:label {:class ["flex" "h-10" "items-center" "gap-1.5" "rounded-lg" "border" "border-base-300" "bg-base-100" "px-2.5"]}
    [:span {:class ["sr-only"]} (str label " threshold")]
    [:input {:type "text"
             :inputmode "decimal"
             :autocomplete "off"
             :value value
             :class ["num" "w-full" "min-w-0" "border-0" "bg-transparent" "p-0" "text-sm" "text-trading-text" "focus:ring-0"]
             :on {:input [[:actions/set-vault-screener-criterion-value metric [:event.target/value]]]}}]
    (when (seq unit)
      [:span {:class ["text-xs" "text-trading-text-secondary"]} unit])]
   [:button {:type "button"
             :aria-label (str "Remove " label)
             :class (into ["inline-flex" "h-11" "w-11" "items-center" "justify-center" "rounded-lg"
                           "text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]
                          format/focus-ring-classes)
             :on {:click [[:actions/remove-vault-screener-criterion metric]]}}
    [:svg {:viewBox "0 0 16 16" :class ["h-4" "w-4"] :fill "none" :stroke "currentColor"
           :stroke-width "1.5" :stroke-linecap "round" :aria-hidden true}
     [:path {:d "M4 4l8 8M12 4l-8 8"}]]]])

(defn- plus-icon
  []
  [:svg {:viewBox "0 0 12 12" :class ["h-3" "w-3" "text-ho-accent"] :fill "none" :stroke "currentColor"
         :stroke-width "1.5" :stroke-linecap "round" :aria-hidden true}
   [:path {:d "M6 2v8M2 6h8"}]])

(defn- criteria-section
  [{:keys [windows criteria available-metrics]}]
  [:section {:aria-labelledby "screener-criteria" :class (into ["flex" "flex-col" "gap-5"] card-classes)}
   (step-heading "screener-criteria" "2" "Metric criteria" "Thresholds can be changed after the run without fetching again")
   [:div {:class ["flex" "flex-col" "gap-2"]}
    (field-label "Measured over")
    (segmented "Measured over" windows "vault-screener-window")]
   [:div {:class ["flex" "flex-col" "gap-2"]}
    (if (seq criteria)
      (for [criterion criteria]
        (criterion-row criterion))
      [:div {:class ["rounded-xl" "border" "border-dashed" "border-base-300" "p-3.5" "text-sm" "text-trading-text-secondary"]}
       "No metric criteria — the screen will rank the universe without filtering it."])]
   (when (seq available-metrics)
     [:div {:class ["flex" "flex-col" "gap-2"]}
      (field-label "Add a metric")
      [:div {:class ["flex" "flex-wrap" "gap-1.5"]}
       (for [{:keys [key label]} available-metrics]
         [:button {:type "button"
                   :replicant/key (str "add-" (name key))
                   :class (into ["inline-flex" "min-h-8" "items-center" "gap-1.5" "rounded-full" "border"
                                 "border-base-300" "px-3" "text-xs" "text-trading-text" "hover:bg-base-200"]
                                format/focus-ring-classes)
                   :data-role "vault-screener-add-metric"
                   :on {:click [[:actions/add-vault-screener-criterion key]]}}
          (plus-icon)
          label])]])])

(defn- quality-section
  [{:keys [min-history]}]
  [:section {:aria-labelledby "screener-quality" :class (into ["flex" "flex-col" "gap-4"] card-classes)}
   (step-heading "screener-quality" "3" "Data quality" nil)
   [:div {:class ["flex" "flex-col" "gap-2"]}
    (field-label "Minimum history in the window")
    (segmented "Minimum history" min-history "vault-screener-min-history")]
   [:p {:class ["text-sm" "leading-5" "text-trading-text-secondary"]}
    "Hyperliquid keeps sparse vault history — about 15 points per 6 months for an older vault. Vaults below the minimum show as "
    [:span {:class ["text-trading-text"]} "Not enough data"]
    " rather than passing or failing, so a short track record can't fake a high Sharpe."]])

(defn- estimate-row
  [label value accent?]
  [:div {:class ["contents"]}
   [:dt {:class ["text-trading-text-secondary"]} label]
   [:dd {:class (into ["num" "text-right"] (when accent? ["text-ho-accent-bright"]))} value]])

(defn- estimate-panel
  [{:keys [estimate universe-count run]}]
  (let [{:keys [cached to-fetch megabytes eta-label cache-loaded?]} estimate
        can-run? (pos? universe-count)]
    [:aside {:aria-labelledby "screener-estimate"
             :class ["flex" "flex-col" "gap-4" "rounded-2xl" "border" "border-ho-border-accent" "bg-ho-accent-soft/40" "p-5"]
             :data-role "vault-screener-estimate"}
     [:h2 {:id "screener-estimate" :class ["text-xs" "font-medium" "uppercase" "tracking-wider" "text-trading-text-secondary"]}
      "Estimate"]
     [:div
      [:div {:class ["num" "text-[36px]" "leading-[40px]" "text-trading-text"]
             :data-role "vault-screener-eta"}
       (if (= eta-label "instant") "Instant" (str "≈ " eta-label))]
      [:div {:class ["mt-1" "text-sm" "text-trading-text-secondary"]}
       (if (zero? to-fetch)
         "Everything is cached — results open immediately."
         "Paced at one vault every 2 seconds.")]]
     [:dl {:class ["grid" "grid-cols-[minmax(0,1fr)_auto]" "gap-x-3" "gap-y-2.5" "text-sm"]}
      (estimate-row "Vaults to evaluate" (str universe-count) false)
      (estimate-row "Already cached" (if cache-loaded? (str cached) "…") false)
      (estimate-row "To fetch" (str to-fetch) true)
      (estimate-row "Data to download" (str "≈ " megabytes " MB") false)]
     [:div {:class ["flex" "flex-col" "gap-2"]}
      [:div {:class ["flex" "justify-between" "text-xs" "text-trading-text-secondary"]}
       [:span "Request budget"]
       [:span {:class ["num"]} "1 request / 2 s"]]
      [:div {:aria-hidden true :class ["flex" "h-2" "overflow-hidden" "rounded-full" "bg-base-300"]}
       [:div {:class ["w-1/2" "bg-ho-accent"]}]]
      [:div {:class ["flex" "justify-between" "gap-3" "text-xs"]}
       [:span {:class ["inline-flex" "items-center" "gap-1.5" "text-trading-text"]}
        [:span {:aria-hidden true :class ["h-2" "w-2" "rounded-sm" "bg-ho-accent"]}] "Screener"]
       [:span {:class ["inline-flex" "items-center" "gap-1.5" "text-trading-text-secondary"]}
        [:span {:aria-hidden true :class ["h-2" "w-2" "rounded-sm" "bg-base-300"]}] "Kept free for trading & live data"]]]
     [:button {:type "button"
               :disabled (not can-run?)
               :class (into ["flex" "min-h-12" "items-center" "justify-center" "rounded-xl" "bg-ho-accent"
                             "text-base" "font-medium" "text-ho-bg" "transition-colors" "hover:bg-ho-accent-hi"
                             "disabled:cursor-not-allowed" "disabled:opacity-50"]
                            format/focus-ring-classes)
               :data-role "vault-screener-run"
               :on {:click [[:actions/run-vault-screener]]}}
      "Run screen"]
     (when (:id run)
       [:button {:type "button"
                 :class (into ["text-sm" "text-ho-accent-bright" "hover:underline"] format/focus-ring-classes)
                 :on {:click [[:actions/control-vault-screener-run :show-results]]}}
        "Back to the last results"])
     [:ul {:class ["list-disc" "space-y-1.5" "pl-4" "text-xs" "leading-[18px]" "text-trading-text-secondary"]}
      [:li "Runs in the background — keep trading or leave the page. You'll get a notification when it's ready."]
      [:li "History is cached on this device for 6 hours. Re-running only fetches what's stale."]
      [:li "Slows down automatically if Hyperliquid pushes back, then carries on."]]]))

(defn builder-view
  [{:keys [presets] :as vm}]
  [:div {:class ["flex" "flex-col" "gap-4"] :data-role "vault-screener-builder"}
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
    [:span {:class ["text-xs" "text-trading-text-secondary"]} "Start from"]
    (for [{:keys [id label]} presets]
      [:button {:type "button"
                :replicant/key (str "preset-" (name id))
                :class (into ["min-h-8" "rounded-full" "border" "border-base-300" "bg-base-100" "px-3"
                              "text-xs" "text-trading-text" "hover:bg-base-200"]
                             format/focus-ring-classes)
                :on {:click [[:actions/apply-vault-screener-preset id]]}}
       label])]
   [:div {:class ["flex" "flex-wrap" "items-start" "gap-4"]}
    [:div {:class ["flex" "min-w-0" "flex-col" "gap-4" "flex-[999_1_560px]"]}
     (universe-section vm)
     (criteria-section vm)
     (quality-section vm)]
    [:div {:class ["min-w-0" "flex-[1_1_320px]" "xl:sticky" "xl:top-4"]}
     (estimate-panel vm)]]])
