(ns hyperopen.views.portfolio.optimize.universe-panel
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.query :as asset-query]))

(def ^:private search-input-class
  ["mt-2" "w-full" "rounded-md" "border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
   "text-sm" "font-semibold" "outline-none" "focus:border-primary/70"])

(defn- normalized-text
  [value]
  (some-> value str str/trim))

(defn- selected-instrument-ids
  [universe]
  (into #{} (keep :instrument-id) universe))

(defn- market-label
  [market]
  (or (normalized-text (:symbol market))
      (normalized-text (:coin market))
      (normalized-text (:key market))
      "Unknown Market"))

(defn- candidate-markets
  [state universe query]
  (let [selected-ids (selected-instrument-ids universe)
        query* (or (normalized-text query) "")]
    (->> (asset-query/filter-and-sort-assets
          (get-in state [:asset-selector :markets])
          query*
          :volume
          :desc
          #{}
          false
          false
          :all)
         (filter #(and (normalized-text (:key %))
                       (normalized-text (:coin %))
                       (:market-type %)
                       (not (contains? selected-ids (:key %)))))
         (take 6)
         vec)))

(defn- selected-row
  [instrument]
  (let [instrument-id (:instrument-id instrument)]
    [:div {:class ["flex" "items-center" "justify-between" "gap-3" "rounded-lg" "border"
                   "border-base-300" "bg-base-200/40" "px-3" "py-2"]}
     [:div
      [:p {:class ["text-sm" "font-semibold"]} instrument-id]
      [:p {:class ["text-xs" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
       (str (:coin instrument) " / " (name (:market-type instrument)))]]
     [:button {:type "button"
               :class ["rounded-md" "border" "border-base-300" "px-2" "py-1" "text-[0.65rem]"
                       "font-semibold" "uppercase" "tracking-[0.14em]" "text-trading-muted"
                       "hover:border-warning/60" "hover:text-warning"]
               :data-role (str "portfolio-optimizer-universe-remove-" instrument-id)
               :on {:click [[:actions/remove-portfolio-optimizer-universe-instrument
                              instrument-id]]}}
      "Remove"]]))

(defn- market-row
  [market]
  (let [market-key (:key market)]
    [:div {:class ["flex" "items-center" "justify-between" "gap-3" "rounded-lg" "border"
                   "border-base-300" "bg-base-200/30" "px-3" "py-2"]}
     [:div
      [:p {:class ["text-sm" "font-semibold"]} (market-label market)]
      [:p {:class ["text-xs" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
       (str market-key " / " (name (:market-type market)))]]
     [:button {:type "button"
               :class ["rounded-md" "border" "border-primary/50" "bg-primary/10" "px-2" "py-1"
                       "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.14em]"
                       "text-primary"]
               :data-role (str "portfolio-optimizer-universe-add-" market-key)
               :on {:click [[:actions/add-portfolio-optimizer-universe-instrument market-key]]}}
      "Add"]]))

(defn universe-panel
  [state draft]
  (let [universe (vec (or (:universe draft) []))
        search-query (or (get-in state [:portfolio-ui :optimizer :universe-search-query]) "")
        markets (candidate-markets state universe search-query)]
    [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-universe-panel"}
     [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
      [:div
       [:p {:class ["text-[0.65rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.24em]"
                    "text-trading-muted"]}
        "Universe"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        (str (count universe)
             " instruments selected. Seed from current holdings or add assets one by one.")]]
      [:button {:type "button"
                :class ["rounded-lg" "border" "border-primary/50" "bg-primary/10" "px-3" "py-2"
                        "text-xs" "font-semibold" "uppercase" "tracking-[0.16em]" "text-primary"]
                :data-role "portfolio-optimizer-universe-use-current"
                :on {:click [[:actions/set-portfolio-optimizer-universe-from-current]]}}
       "Use Current Holdings"]]
     [:div {:class ["mt-4" "grid" "grid-cols-1" "gap-3" "lg:grid-cols-2"]}
      [:div
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]"
                    "text-trading-muted"]}
        "Selected"]
       (if (seq universe)
         (into [:div {:class ["mt-2" "space-y-2"]}]
               (map selected-row universe))
         [:p {:class ["mt-2" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"
                      "text-sm" "text-trading-muted"]}
          "No instruments selected yet."])]
      [:div
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]"
                    "text-trading-muted"]}
        "Manual Add"]
       [:input {:type "search"
                :class search-input-class
                :placeholder "Search BTC, ETH, spot:PURR/USDC..."
                :data-role "portfolio-optimizer-universe-search-input"
                :value search-query
                :on {:input [[:actions/set-portfolio-optimizer-universe-search-query
                              [:event.target/value]]]}}]
       [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
        "Requires history reload after adding new assets."]
       (if (seq markets)
         (into [:div {:class ["mt-2" "space-y-2"]}]
               (map market-row markets))
         [:p {:class ["mt-2" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"
                      "text-sm" "text-trading-muted"]}
          "No matching unused markets found."])]]]))
