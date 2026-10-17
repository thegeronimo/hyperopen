(ns hyperopen.views.vaults.screener-view
  "Vaults page Screener tab: build a screen, watch it run, read the results."
  (:require [hyperopen.vaults.application.screener-vm :as screener-vm]
            [hyperopen.views.vaults.list-view.format :as format]
            [hyperopen.views.vaults.screener-view.builder :as builder]
            [hyperopen.views.vaults.screener-view.results :as results]))

(defn view-switch
  [active-view]
  [:nav {:aria-label "Vault views"
         :class ["flex" "gap-1" "rounded-xl" "border" "border-base-300" "bg-base-100" "p-1"]
         :data-role "vaults-view-switch"}
   (for [[view label] [[:list "All vaults"] [:screener "Screener"]]]
     (let [active? (= view active-view)]
       [:button {:type "button"
                 :replicant/key (str "vaults-view-" (name view))
                 :aria-pressed active?
                 :class (into ["inline-flex" "min-h-9" "items-center" "rounded-lg" "px-4" "text-sm" "transition-colors"]
                              (concat (if active?
                                        ["bg-ho-accent-soft" "text-ho-accent-bright"]
                                        ["text-trading-text-secondary" "hover:text-trading-text"])
                                      format/focus-ring-classes))
                 :data-role (str "vaults-view-" (name view))
                 :on {:click [[:actions/set-vaults-view view]]}}
        label]))])

(defn screener-view
  [state {:keys [desktop-layout?]}]
  (let [vm (assoc (screener-vm/screener-vm state) :desktop-layout? desktop-layout?)]
    [:div {:class ["flex" "flex-col" "gap-4"] :data-role "vault-screener"}
     [:p {:class ["max-w-[720px]" "text-sm" "leading-[22px]" "text-trading-text-secondary"]}
      "Screen vaults on the same tear-sheet metrics shown on each vault's page. Narrow the universe first, set your criteria, then run. History is fetched at a steady pace so your trading data never gets rate-limited."]
     (if (= :run (:panel vm))
       (results/run-view state vm)
       (builder/builder-view vm))]))
