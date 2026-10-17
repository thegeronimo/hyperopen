(ns hyperopen.views.vaults.optimizer-handoff-sheet
  "Review sheet that sends a set of vaults to the portfolio optimizer."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions.vault-handoff :as vault-handoff]
            [hyperopen.vaults.application.screener-vm :as screener-vm]
            [hyperopen.vaults.screener.optimizer-handoff :as optimizer-handoff]
            [hyperopen.vaults.screener.view-commands :as view-commands]
            [hyperopen.views.vaults.list-view.format :as format]))

(def ^:private goals
  [{:value :minimum-variance :label "Minimum risk" :sub "Lowest combined volatility"}
   {:value :equal-risk :label "Equal risk" :sub "Each vault adds the same risk · no return forecast needed"}
   {:value :max-sharpe :label "Maximum Sharpe" :sub "Best risk-adjusted return · sensitive to noisy returns"}])

(defn- index-row-by-address
  [state]
  (into {}
        (keep (fn [row]
                (when-let [address (some-> (:vault-address row) str str/lower-case)]
                  [address row])))
        (get-in state [:vaults :merged-index-rows])))

(defn- sharpe-for
  [state address]
  (or (get-in (view-commands/active-view state) [:metrics-by-address address :sharpe])
      (get-in state [:vaults :screener :results address :values :sharpe])))

(defn- card-classes
  [active?]
  (into ["rounded-xl" "border" "px-4" "py-3" "text-left"]
        (concat (if active?
                  ["border-ho-border-accent" "bg-ho-accent-soft"]
                  ["border-base-300" "hover:bg-base-200"])
                format/focus-ring-classes)))

(defn- vault-row
  [{:keys [address name tvl sharpe included?]}]
  [:label {:replicant/key (str "handoff-" address)
           :class ["grid" "min-h-11" "cursor-pointer" "items-center" "gap-2" "border-b" "border-base-300/60" "px-3.5"
                   "text-sm" "grid-cols-[24px_minmax(0,1fr)_96px_64px]"]
           :data-role "vault-optimizer-handoff-row"}
   [:input {:type "checkbox"
            :checked (boolean included?)
            :class ["h-4" "w-4" "rounded" "border-base-300" "bg-base-100" "text-ho-accent"]
            :on {:change [[:actions/set-vault-optimizer-handoff-field :toggle-vault address]]}}]
   [:span {:class (into ["truncate"] (if included? ["text-trading-text"] ["text-trading-text-secondary" "line-through"]))}
    name]
   [:span {:class ["num" "text-right" "text-trading-text-secondary"]} (format/format-total-currency tvl)]
   [:span {:class ["num" "text-right" "text-trading-text"]} (screener-vm/format-metric :sharpe sharpe)]])

(defn handoff-sheet
  [state]
  (let [{:keys [open? label addresses excluded objective vault-allocation?] :as sheet}
        (get-in state optimizer-handoff/sheet-path)]
    (when open?
      (let [rows-by-address (index-row-by-address state)
            excluded* (set excluded)
            rows (mapv (fn [address]
                         (let [row (get rows-by-address address)]
                           {:address address
                            :name (or (:name row) address)
                            :tvl (:tvl row)
                            :sharpe (sharpe-for state address)
                            :included? (not (contains? excluded* address))}))
                       addresses)
            selected (count (optimizer-handoff/selected-addresses sheet))
            sent (min selected vault-handoff/max-vaults)]
        [:div {:class ["fixed" "inset-0" "z-50" "flex" "items-start" "justify-center" "overflow-y-auto" "bg-ho-bg-deep/80"
                       "px-4" "py-8" "md:py-12"]
               :data-role "vault-optimizer-handoff"}
         [:div {:role "dialog"
                :aria-modal true
                :aria-labelledby "vault-optimizer-handoff-title"
                :class ["flex" "w-full" "max-w-[720px]" "flex-col" "rounded-2xl" "border" "border-base-300" "bg-base-100" "shadow-2xl"]}
          [:div {:class ["flex" "items-start" "justify-between" "gap-3" "border-b" "border-base-300" "px-6" "pb-4" "pt-5"]}
           [:div
            [:h2 {:id "vault-optimizer-handoff-title" :class ["text-[20px]" "font-medium" "text-trading-text"]}
             "Optimize these vaults"]
            [:p {:class ["mt-1.5" "text-sm" "leading-5" "text-trading-text-secondary"]}
             "From " [:span {:class ["text-trading-text"]} label]
             " · the optimizer's universe will be exactly the vaults you keep below."]]
           [:button {:type "button"
                     :aria-label "Close"
                     :class (into ["-mr-2" "inline-flex" "h-11" "w-11" "items-center" "justify-center" "rounded-lg"
                                   "text-trading-text-secondary" "hover:bg-base-200"]
                                  format/focus-ring-classes)
                     :on {:click [[:actions/close-vault-optimizer-handoff]]}}
            [:svg {:viewBox "0 0 16 16" :class ["h-4" "w-4"] :fill "none" :stroke "currentColor"
                   :stroke-width "1.5" :stroke-linecap "round" :aria-hidden true}
             [:path {:d "M4 4l8 8M12 4l-8 8"}]]]]
          [:div {:class ["flex" "flex-col" "gap-5" "px-6" "py-5"]}
           [:div {:class ["flex" "flex-col" "gap-2"]}
            [:div {:class ["flex" "items-baseline" "justify-between" "text-xs" "text-trading-text-secondary"]}
             [:span "Vaults"]
             [:span [:span {:class ["num" "text-trading-text"]} (str selected)] " of " (str (count rows)) " selected"]]
            (into [:div {:class ["max-h-[300px]" "overflow-y-auto" "rounded-xl" "border" "border-base-300" "bg-ho-bg"]
                         :data-role "vault-optimizer-handoff-list"}]
                  (map vault-row)
                  rows)
            [:div {:class ["text-xs" "leading-[18px]" "text-trading-text-secondary"]}
             (if (> selected vault-handoff/max-vaults)
               (str "The optimizer takes up to " vault-handoff/max-vaults " vaults; the first "
                    vault-handoff/max-vaults " in this list are sent.")
               "Vaults with short history may need a proxy or a conservative assumption in the optimizer before the first run.")]]
           [:div {:class ["flex" "flex-col" "gap-2"]}
            [:span {:class ["text-xs" "text-trading-text-secondary"]} "Goal"]
            (into [:div {:role "group" :aria-label "Goal" :class ["grid" "gap-2" "sm:grid-cols-3"]}]
                  (map (fn [{:keys [value label sub]}]
                         [:button {:type "button"
                                   :aria-pressed (= value objective)
                                   :class (card-classes (= value objective))
                                   :data-role (str "vault-optimizer-handoff-goal-" (name value))
                                   :on {:click [[:actions/set-vault-optimizer-handoff-field :objective value]]}}
                          [:span {:class ["block" "text-sm" "text-trading-text"]} label]
                          [:span {:class ["mt-1" "block" "text-xs" "leading-[17px]" "text-trading-text-secondary"]} sub]]))
                  goals)]
           [:div {:class ["flex" "flex-col" "gap-2"]}
            [:span {:class ["text-xs" "text-trading-text-secondary"]} "Allocation rules"]
            [:div {:role "radiogroup" :aria-label "Allocation rules" :class ["grid" "gap-2" "sm:grid-cols-2"]}
             [:button {:type "button"
                       :role "radio"
                       :aria-checked (true? vault-allocation?)
                       :class (card-classes (true? vault-allocation?))
                       :data-role "vault-optimizer-handoff-rules-vault"
                       :on {:click [[:actions/set-vault-optimizer-handoff-field :vault-allocation? true]]}}
              [:span {:class ["flex" "items-center" "justify-between" "gap-2" "text-sm" "text-trading-text"]}
               "Vault allocation"
               [:span {:class ["rounded-full" "bg-ho-accent-soft-hi" "px-2" "py-0.5" "text-xs" "text-ho-accent-bright"]} "Recommended"]]
              [:span {:class ["num" "mt-1.5" "block" "text-xs" "text-trading-text"]} "Long-only · 1.00× gross · max 25% per vault"]
              [:span {:class ["mt-1" "block" "text-xs" "leading-[17px]" "text-trading-text-secondary"]}
               "Vaults take deposits only — they can't be shorted or levered."]]
             [:button {:type "button"
                       :role "radio"
                       :aria-checked (false? vault-allocation?)
                       :class (card-classes (false? vault-allocation?))
                       :data-role "vault-optimizer-handoff-rules-default"
                       :on {:click [[:actions/set-vault-optimizer-handoff-field :vault-allocation? false]]}}
              [:span {:class ["block" "text-sm" "text-trading-text"]} "Optimizer defaults"]
              [:span {:class ["num" "mt-1.5" "block" "text-xs" "text-trading-text"]} "2.00× gross · +1.00× net · max 50% per asset"]
              [:span {:class ["mt-1" "block" "text-xs" "leading-[17px]" "text-trading-text-secondary"]}
               "Gross above 1.00× can't be filled with vault deposits."]]]]]
          [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3" "border-t" "border-base-300" "px-6" "py-4"]}
           [:span {:class ["max-w-[360px]" "text-xs" "leading-[18px]" "text-trading-text-secondary"]}
            "Starts a fresh optimizer draft, replacing your working draft. Saved scenarios are kept. Nothing is traded from here."]
           [:div {:class ["flex" "gap-2"]}
            [:button {:type "button"
                      :class (into ["min-h-11" "rounded-lg" "border" "border-base-300" "px-4" "text-sm" "text-trading-text"
                                    "hover:bg-base-200"]
                                   format/focus-ring-classes)
                      :on {:click [[:actions/close-vault-optimizer-handoff]]}}
             "Cancel"]
            [:button {:type "button"
                      :disabled (zero? selected)
                      :class (into ["min-h-11" "rounded-lg" "bg-ho-accent" "px-4" "text-sm" "font-medium" "text-ho-bg"
                                    "hover:bg-ho-accent-hi" "disabled:cursor-not-allowed" "disabled:opacity-50"]
                                   format/focus-ring-classes)
                      :data-role "vault-optimizer-handoff-confirm"
                      :on {:click [[:actions/confirm-vault-optimizer-handoff]
                                   [:actions/navigate optimizer-handoff/optimizer-route]]}}
             (str "Open optimizer · " sent (if (= 1 sent) " vault" " vaults"))]]]]]))))
