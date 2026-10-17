(ns hyperopen.views.vaults.screener-view.results
  "Screener run panel (progress, pacing, backoff) and the grouped results."
  (:require [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.vaults.screener.model :as model]
            [hyperopen.views.vaults.list-view.format :as format]
            [hyperopen.wallet.core :as wallet]))

(def ^:private tab-labels
  {:pass "Pass"
   :near "Near miss"
   :fail "Fail"
   :thin "Not enough data"})

(defn- vault-href
  [state vault-address]
  (spectate-mode-links/internal-route-href state (str "/vaults/" vault-address)))

(defn- chip
  [text accent?]
  [:span {:class (into ["rounded-full" "border" "px-2.5" "py-1" "text-xs"]
                       (if accent?
                         ["border-ho-border-accent" "bg-ho-accent-soft" "text-ho-accent-bright"]
                         ["border-base-300" "bg-base-100" "text-trading-text"]))}
   text])

(defn- secondary-button
  [label action role]
  [:button {:type "button"
            :class (into ["inline-flex" "min-h-10" "items-center" "gap-2" "rounded-lg" "border" "border-base-300"
                          "bg-base-100" "px-3.5" "text-sm" "text-trading-text" "hover:bg-base-200"]
                         format/focus-ring-classes)
            :data-role role
            :on {:click [action]}}
   label])

(defn- stat
  [value label value-classes]
  [:div
   [:div {:class (into ["num" "text-[28px]" "leading-8"] value-classes)} value]
   [:div {:class ["mt-1" "text-sm" "text-trading-text-secondary"]} label]])

(defn- optimize-option
  [label count source]
  [:button {:type "button"
            :role "menuitem"
            :disabled (zero? count)
            :class (into ["flex" "min-h-10" "w-full" "items-center" "justify-between" "gap-3" "rounded-md" "px-2.5"
                          "text-left" "text-sm" "text-trading-text" "hover:bg-base-200"
                          "disabled:cursor-not-allowed" "disabled:opacity-50"]
                         format/focus-ring-classes)
            :data-role (str "vault-screener-optimize-" (name source))
            :on {:click [[:actions/open-vault-optimizer-handoff source]]}}
   label
   [:span {:class ["num" "text-trading-text-secondary"]} (str count)]])

(defn- optimize-menu
  [pass-count near-count]
  [:details {:class ["relative"]
             :data-role "vault-screener-optimize-menu"}
   [:summary {:class (into ["inline-flex" "min-h-10" "cursor-pointer" "list-none" "items-center" "gap-2" "rounded-lg"
                            "border" "border-ho-border-accent" "bg-ho-accent-soft" "px-3.5" "text-sm" "font-medium"
                            "text-ho-accent-bright" "hover:bg-ho-accent-soft-hi"]
                           format/focus-ring-classes)}
    [:svg {:viewBox "0 0 16 16" :class ["h-4" "w-4"] :fill "none" :stroke "currentColor"
           :stroke-width "1.6" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
     [:path {:d "M2 13l4-5 3 3 5-7"}]
     [:path {:d "M10 4h4v4"}]]
    "Optimize…"]
   [:div {:role "menu"
          :class ["ui-dropdown-panel" "absolute" "right-0" "top-full" "z-30" "mt-1.5" "flex" "w-64" "flex-col"
                  "rounded-xl" "border" "border-base-300" "bg-base-100" "p-1.5" "shadow-2xl"]
          :data-ui-native-details-panel "true"}
    (optimize-option "Passing vaults" pass-count :screener-pass)
    (optimize-option "Passing + near misses" (+ pass-count near-count) :screener-near)
    (optimize-option "Top 5 by Sharpe" (min 5 pass-count) :screener-top)]])

(defn- run-actions
  [{:keys [status]} pass-count near-count]
  [:div {:class ["flex" "flex-wrap" "gap-2"]}
   (when (and (= status :done) (pos? (+ pass-count near-count)))
     (optimize-menu pass-count near-count))
   (when (and (= status :done) (pos? pass-count))
     [:button {:type "button"
               :class (into ["inline-flex" "min-h-10" "items-center" "rounded-lg" "bg-ho-accent" "px-3.5" "text-sm"
                             "font-medium" "text-ho-bg" "hover:bg-ho-accent-hi"]
                            format/focus-ring-classes)
               :data-role "vault-screener-save"
               :on {:click [[:actions/open-vault-save-view-dialog]]}}
      "Save results…"])
   (case status
     :running (secondary-button "Pause" [:actions/control-vault-screener-run :pause] "vault-screener-pause")
     :paused (secondary-button "Resume" [:actions/control-vault-screener-run :resume] "vault-screener-resume")
     (secondary-button "Edit screen" [:actions/control-vault-screener-run :edit] "vault-screener-edit"))
   (if (#{:running :paused} status)
     (secondary-button "Cancel" [:actions/control-vault-screener-run :cancel] "vault-screener-cancel")
     (secondary-button "Run again" [:actions/run-vault-screener] "vault-screener-rerun"))])

(defn- headline
  [{:keys [status total done]} pass-count closed-count]
  (case status
    :done [:h2 {:class ["text-[28px]" "font-normal" "leading-[34px]"] :data-role "vault-screener-headline"}
           [:span {:class ["num" "text-ho-buy"]} (str pass-count)]
           " of " [:span {:class ["num"]} (str (- total closed-count))] " vaults pass"]
    :paused [:h2 {:class ["text-lg" "font-medium"] :data-role "vault-screener-headline"}
             (str "Paused at " done " of " total " vaults")]
    :cancelled [:h2 {:class ["text-lg" "font-medium"] :data-role "vault-screener-headline"}
                (str "Cancelled after " done " of " total " vaults")]
    [:h2 {:class ["text-lg" "font-medium"] :data-role "vault-screener-headline"}
     (str "Screening " total " vaults")]))

(defn- progress-panel
  [{:keys [run criteria-summary tab-counts closed-count]}]
  (let [{:keys [status done total cached fetched queued cached-pct fetched-pct
                eta-label interval-label backoff-seconds stale? chips failed]} run
        active? (#{:running :paused} status)]
    [:section {:aria-label "Screen run"
               :class ["flex" "flex-col" "gap-5" "rounded-2xl" "border" "border-ho-border-accent" "bg-ho-accent-soft/40" "p-5" "md:p-6"]
               :data-role "vault-screener-run-panel"}
     [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-4"]}
      [:div {:class ["flex" "min-w-0" "flex-col" "gap-2"]}
       (headline run (get tab-counts :pass 0) (or closed-count 0))
       (into [:div {:class ["flex" "flex-wrap" "gap-1.5"]}]
             (concat (map #(chip % false) chips)
                     (map #(chip % true) criteria-summary)))
       (if (pos? (or closed-count 0))
         [:p {:class ["text-xs" "text-trading-text-secondary"] :data-role "vault-screener-closed-note"}
          (str closed-count (if (= 1 closed-count) " vault" " vaults")
               " left out: closed to new deposits by the leader.")]
         [:span {:class ["hidden"]}])]
      (run-actions run (get tab-counts :pass 0) (get tab-counts :near 0))]
     (when stale?
       [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3" "rounded-xl" "border" "border-base-300" "bg-base-100" "px-4" "py-3" "text-sm"]
              :data-role "vault-screener-stale"}
        [:span {:class ["text-trading-text"]} "The universe or window changed since this run. Run again to update — cached vaults are instant."]
        (secondary-button "Run again" [:actions/run-vault-screener] "vault-screener-stale-rerun")])
     (when active?
       [:div {:class ["flex" "flex-wrap" "items-end" "gap-8"]}
        (stat [:span [:span (str done)] [:span {:class ["text-trading-text-secondary"]} (str " / " total)]]
              "vaults evaluated" ["text-[40px]" "leading-[44px]"])
        (stat (if backoff-seconds "Waiting" (str "≈ " eta-label))
              (if backoff-seconds "for the rate limit" (str "left at " interval-label)) [])
        (stat (str (get tab-counts :pass 0)) "passing so far" ["text-ho-buy"])])
     (when active?
       [:div {:class ["flex" "flex-col" "gap-2.5"]}
        [:div {:role "progressbar" :aria-label "Screen progress"
               :aria-valuemin 0 :aria-valuemax total :aria-valuenow done
               :class ["flex" "h-2.5" "overflow-hidden" "rounded-full" "bg-base-300"]}
         [:div {:class ["bg-ho-accent/60"] :style {:width (str cached-pct "%")}}]
         [:div {:class ["bg-ho-accent"] :style {:width (str fetched-pct "%")}}]]
        [:div {:class ["flex" "flex-wrap" "gap-5" "text-xs" "text-trading-text-secondary"]}
         [:span [:span {:class ["num" "text-trading-text"]} (str cached)] " from cache"]
         [:span [:span {:class ["num" "text-trading-text"]} (str fetched)] " fetched"]
         [:span [:span {:class ["num" "text-trading-text"]} (str queued)] " queued"]
         [:span "Largest vaults go first, so the ones most people look at land early."]]])
     (when backoff-seconds
       [:div {:class ["flex" "items-center" "gap-2.5" "rounded-xl" "border" "border-ho-warn/40" "bg-ho-warn/10" "px-4" "py-3" "text-sm"]
              :data-role "vault-screener-backoff"}
        [:span {:class ["font-medium" "text-ho-warn"]} "Hyperliquid asked us to slow down."]
        [:span {:class ["text-trading-text"]}
         (str "Resuming in " backoff-seconds " s at a slower pace — nothing is lost.")]])
     (when (and (pos? (or failed 0)) (not active?))
       [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3" "rounded-xl" "border" "border-ho-warn/40" "bg-ho-warn/10" "px-4" "py-3" "text-sm"]
              :data-role "vault-screener-failed"}
        [:span {:class ["text-trading-text"]}
         (str failed (if (= 1 failed) " vault's" " vaults'")
              " history couldn't be loaded after " model/fetch-attempts " tries, so "
              (if (= 1 failed) "it is" "they are") " under Not enough data. Run again to retry — the rest is cached.")]
        (secondary-button "Run again" [:actions/run-vault-screener] "vault-screener-failed-rerun")])
     (when (= status :running)
       [:p {:class ["text-sm" "text-trading-text-secondary"]}
        "You can leave this page or keep trading — the screen keeps running in the background and your trading data always goes first."])]))

(defn- outcome-mark
  [outcome]
  (case outcome
    :pass [:svg {:viewBox "0 0 12 12" :class ["h-3" "w-3" "text-ho-buy"] :fill "none" :stroke "currentColor"
                 :stroke-width "1.6" :stroke-linecap "round" :stroke-linejoin "round" :aria-label "meets criterion" :role "img"}
           [:path {:d "M2.5 6.5l2.2 2.2 4.8-5"}]]
    (:near :fail) [:svg {:viewBox "0 0 12 12" :class ["h-3" "w-3" (if (= outcome :near) "text-ho-warn" "text-ho-sell-hi")]
                         :fill "none" :stroke "currentColor" :stroke-width "1.6" :stroke-linecap "round"
                         :aria-label (if (= outcome :near) "just misses criterion" "misses criterion") :role "img"}
                   [:path {:d "M3 3l6 6M9 3l-6 6"}]]
    [:span {:class ["inline-block" "w-3"]}]))

(defn- sort-header
  [{:keys [metric label sorted? direction]}]
  [:th {:class ["px-2" "py-2" "text-right" "font-normal"]
        :aria-sort (cond (not sorted?) "none" (= direction :asc) "ascending" :else "descending")}
   [:button {:type "button"
             :class (into ["inline-flex" "items-center" "gap-1" "text-xs"
                           (if sorted? "text-ho-accent-bright" "text-trading-text-secondary")
                           "hover:text-trading-text"]
                          format/focus-ring-classes)
             :on {:click [[:actions/set-vault-screener-control :results-sort metric]]}}
    label
    (when sorted? [:span {:aria-hidden true} (if (= direction :asc) "▴" "▾")])]])

(defn- result-table-row
  [state {:keys [vault-address name leader tvl age-days your-deposit cells reason]} show-reason?]
  [:tr {:replicant/key (str "screener-row-" vault-address)
        :class ["border-t" "border-base-300/60" "text-sm" "hover:bg-base-200/50"]
        :data-role "vault-screener-result-row"}
   [:td {:class ["px-2" "py-2.5"]}
    [:a {:href (vault-href state vault-address)
         :class (into ["font-medium" "text-trading-text" "hover:underline"] format/focus-visible-only-ring-classes)}
     name]
    [:div {:class ["num" "mt-0.5" "text-xs" "text-trading-text-secondary"]}
     (or (when show-reason? reason) (wallet/short-addr leader))]]
   [:td {:class ["num" "px-2" "py-2.5" "text-right"]} (format/format-total-currency tvl)]
   [:td {:class ["num" "px-2" "py-2.5" "text-right" "text-trading-text-secondary"]} (str age-days " d")]
   (for [{:keys [metric text outcome low-confidence?]} cells]
     [:td {:replicant/key (str "cell-" (cljs.core/name metric))
           :class ["num" "px-2" "py-2.5" "text-right"]
           :title (when low-confidence? "Low confidence: sparse history in this window")}
      [:span {:class ["inline-flex" "items-center" "justify-end" "gap-1.5"
                      (if low-confidence? "text-trading-text-secondary" "text-trading-text")]}
       (outcome-mark outcome)
       text]])
   [:td {:class ["num" "px-2" "py-2.5" "text-right" "text-trading-text-secondary"]}
    (if (pos? (or your-deposit 0)) (format/format-total-currency your-deposit) "—")]])

(defn- result-card
  [state {:keys [vault-address name tvl age-days cells reason]}]
  [:a {:replicant/key (str "screener-card-" vault-address)
       :href (vault-href state vault-address)
       :class (into ["flex" "flex-col" "gap-2.5" "rounded-xl" "border" "border-base-300" "bg-base-100" "p-3.5"]
                    format/focus-visible-only-ring-classes)
       :data-role "vault-screener-result-card"}
   [:div {:class ["flex" "items-baseline" "justify-between" "gap-2"]}
    [:span {:class ["truncate" "font-medium" "text-trading-text"]} name]
    [:span {:class ["num" "text-xs" "text-trading-text-secondary"]} (str age-days " d")]]
   (when reason [:div {:class ["text-xs" "text-ho-warn"]} reason])
   (into [:div {:class ["grid" "grid-cols-3" "gap-2"]}
          [:div [:div {:class ["text-xs" "text-trading-text-secondary"]} "TVL"]
           [:div {:class ["num" "text-sm"]} (format/format-total-currency tvl)]]]
         (map (fn [{:keys [metric text]}]
                [:div [:div {:class ["text-xs" "text-trading-text-secondary"]}
                       (get {:sharpe "Sharpe" :max-drawdown "Max DD"} metric (cljs.core/name metric))]
                 [:div {:class ["num" "text-sm"]} text]])
              (take 2 cells)))])

(defn- tabs
  [{:keys [results-tab tab-counts]}]
  (into [:div {:role "tablist" :aria-label "Result groups" :class ["flex" "flex-wrap" "gap-1"]}]
        (map (fn [tab]
               (let [active? (= tab results-tab)]
                 [:button {:type "button" :role "tab" :aria-selected active?
                           :class (into ["min-h-9" "rounded-lg" "border" "px-3" "text-sm"]
                                        (concat (if active?
                                                  ["border-ho-border-accent" "bg-ho-accent-soft" "text-ho-accent-bright"]
                                                  ["border-base-300" "text-trading-text" "hover:bg-base-200"])
                                                format/focus-ring-classes))
                           :data-role (str "vault-screener-tab-" (cljs.core/name tab))
                           :on {:click [[:actions/set-vault-screener-control :results-tab tab]]}}
                  (str (tab-labels tab) " ")
                  [:span {:class ["num" (if active? "text-ho-accent-bright" "text-trading-text-secondary")]}
                   (str (get tab-counts tab 0))]]))
              [:pass :near :fail :thin])))

(defn- criteria-editor
  [{:keys [criteria]}]
  (when (seq criteria)
    (into [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}]
          (concat
           (map (fn [{:keys [metric label op unit value]}]
                  [:label {:replicant/key (str "edit-" (cljs.core/name metric))
                           :class ["inline-flex" "h-9" "items-center" "gap-2" "rounded-full" "border" "border-ho-border-accent"
                                   "bg-ho-accent-soft" "pl-3" "pr-1.5" "text-sm" "text-ho-accent-bright"]}
                   (str label " " op)
                   [:input {:type "text" :inputmode "decimal" :autocomplete "off" :value value
                            :aria-label (str label " threshold")
                            :class ["num" "h-6" "w-14" "rounded-full" "border-0" "bg-ho-bg" "px-2" "text-center" "text-sm"
                                    "text-trading-text" "focus:ring-1" "focus:ring-ho-accent"]
                            :on {:input [[:actions/set-vault-screener-criterion-value metric [:event.target/value]]]}}]
                   (when (seq unit) [:span {:class ["pr-1.5" "text-xs"]} unit])])
                criteria)
           [[:span {:class ["text-xs" "text-trading-text-secondary"]}
             "Thresholds update instantly — every metric is already computed."]]))))

(defn- empty-results
  [{:keys [results-tab run]}]
  [:div {:class ["px-3" "py-8" "text-center" "text-sm" "text-trading-text-secondary"]}
   (if (#{:running :paused} (:status run))
     "Nothing here yet — results fill in as vaults are evaluated."
     (case results-tab
       :pass "No vault meets every criterion. Try the Near miss tab or loosen a threshold."
       :near "No near misses."
       :fail "No vault failed outright."
       "Every vault had enough history."))])

(defn- results-section
  [state {:keys [columns result-rows results-tab desktop-layout? config] :as vm}]
  (let [show-reason? (#{:near :thin} results-tab)
        {sort-metric :metric sort-direction :direction} (:results-sort config)]
    [:section {:aria-label "Results"
               :class ["flex" "flex-col" "gap-3" "rounded-2xl" "border" "border-base-300/80" "bg-base-100/95" "p-3" "md:p-4"]
               :data-role "vault-screener-results"}
     (criteria-editor vm)
     (tabs vm)
     (cond
       (empty? result-rows)
       (empty-results vm)

       desktop-layout?
       [:div {:class ["overflow-x-auto"]}
        [:table {:class ["w-full" "min-w-[900px]" "border-collapse"]}
         [:thead
          (into [:tr {:class ["text-left" "text-xs" "text-trading-text-secondary"]}
                 [:th {:class ["px-2" "py-2" "font-normal"]} "Vault"]
                 (sort-header {:metric :tvl :label "TVL"
                               :sorted? (= :tvl sort-metric)
                               :direction sort-direction})
                 [:th {:class ["px-2" "py-2" "text-right" "font-normal"]} "Age"]]
                (concat (map sort-header columns)
                        [[:th {:class ["px-2" "py-2" "text-right" "font-normal"]} "Your deposit"]]))]
         (into [:tbody] (map #(result-table-row state % show-reason?)) result-rows)]]

       :else
       (into [:div {:class ["flex" "flex-col" "gap-2"]}] (map #(result-card state %)) result-rows))
     [:p {:class ["px-1" "text-xs" "leading-[18px]" "text-trading-text-secondary"]}
      "Metrics use the same history and calculations as each vault's tear sheet, so the numbers match what you see when you open a vault. Greyed values are low-confidence because the history is sparse."]]))

(defn run-view
  [state vm]
  [:div {:class ["flex" "flex-col" "gap-4"]}
   (progress-panel vm)
   (results-section state vm)])
