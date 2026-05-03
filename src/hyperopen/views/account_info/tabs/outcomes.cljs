(ns hyperopen.views.account-info.tabs.outcomes
  (:require [clojure.string :as str]
            [hyperopen.router :as router]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tabs.positions.shared :as positions-shared]))

(def ^:private outcomes-grid-template-class
  "grid-cols-[minmax(18rem,1.75fr)_minmax(7rem,0.65fr)_minmax(8rem,0.7fr)_minmax(7rem,0.65fr)_minmax(7rem,0.65fr)_minmax(8rem,0.75fr)]")

(def ^:private outcomes-grid-min-width-class
  "min-w-[860px]")

(defn- empty-state
  []
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium "No active outcomes"]
   [:div {:class ["mt-2" "text-sm" "text-trading-text-secondary"]} "No data available"]])

(defn- header-cell
  [label]
  [:div {:class ["text-left"
                 "px-0"
                 "text-xs"
                 "font-medium"
                 "text-trading-text-secondary"
                 "whitespace-nowrap"]}
   label])

(defn- outcome-side
  [{:keys [side-index side-name]}]
  (cond
    (= 1 side-index) :short
    (= 0 side-index) :long
    (= "no" (some-> side-name str str/trim str/lower-case)) :short
    (= "yes" (some-> side-name str str/trim str/lower-case)) :long
    :else :long))

(defn- outcome-title-click-actions
  [{:keys [market-key side-coin]}]
  (when-let [side-coin* (shared/non-blank-text side-coin)]
    (let [market-key* (shared/non-blank-text market-key)
          select-action (if market-key*
                          [:actions/select-asset-by-market-key market-key*]
                          [:actions/select-asset side-coin*])]
      [select-action
       [:actions/navigate (router/trade-route-path side-coin*)]])))

(defn- outcome-title-cell
  [{:keys [title side-coin] :as row}]
  (let [side (outcome-side row)
        coin-cell-style (shared/position-coin-cell-style-for-side side)
        coin-tone-class (shared/position-side-tone-class side)]
    [:div {:class ["flex" "min-w-0" "items-center" "self-stretch"]
           :style coin-cell-style}
     (shared/coin-select-control
      side-coin
      [:span {:class ["flex" "w-full" "min-w-0" "items-center"]}
       [:span {:class ["block" "min-w-0" "truncate" "font-semibold" coin-tone-class]
               :title title}
        title]]
      {:extra-classes ["w-full" "justify-start" "overflow-hidden" "text-left"]
       :click-actions (outcome-title-click-actions row)
       :attrs {:data-role "outcome-market-select"}})]))

(defn- amount-cell
  [text & [tone-class]]
  [:div {:class ["text-left" "font-semibold" "num" "whitespace-nowrap" (or tone-class "text-trading-text")]}
   text])

(defn- size-text
  [{:keys [size side-name]}]
  (str (shared/format-balance-amount size 0) " " side-name))

(defn- value-text
  [{:keys [position-value quote]}]
  (str (shared/format-currency position-value) " " (or quote "USDH")))

(defn- price-text
  [value]
  (if (and (number? value)
           (< (js/Math.abs value) 1))
    (.toFixed value 5)
    (let [formatted (shared/format-trade-price value)]
      (if (and (string? formatted)
               (.startsWith formatted "$"))
        (subs formatted 1)
        formatted))))

(defn- pnl-tone-class
  [pnl-value]
  (cond
    (pos? pnl-value) "text-success"
    (neg? pnl-value) "text-error"
    :else "text-trading-text"))

(defn- outcome-row
  [row]
  [:div {:class ["grid"
                 outcomes-grid-template-class
                 "gap-2"
                 "py-1.5"
                 "pr-3"
                 outcomes-grid-min-width-class
                 "hover:bg-base-300"
                 "items-center"
                 "text-sm"]
         :data-role (str "outcome-row-" (:side-coin row))}
   (outcome-title-cell row)
   (amount-cell (size-text row))
   (amount-cell (value-text row))
   (amount-cell (price-text (:entry-price row)))
   (amount-cell (price-text (:mark-price row)))
   (amount-cell (positions-shared/format-pnl-inline (:pnl-value row) (:roe-pct row))
                (pnl-tone-class (:pnl-value row)))])

(defn- outcome-table-header
  []
  [:div {:class ["grid"
                 outcomes-grid-template-class
                 "gap-2"
                 "py-1"
                 "pr-3"
                 outcomes-grid-min-width-class
                 "bg-base-200"]}
   [:div.text-left.pl-3 (header-cell "Outcome")]
   (header-cell "Size")
   (header-cell "Position Value")
   (header-cell "Entry Price")
   (header-cell "Mark Price")
   (header-cell "PNL (ROE %)")])

(defn- mobile-outcome-card
  [row]
  [:div {:class ["rounded-md"
                 "border"
                 "border-base-300"
                 "bg-base-200"
                 "px-3"
                 "py-2.5"
                 "text-sm"
                 "spectate-sm"]
         :data-role (str "mobile-outcome-card-" (:side-coin row))}
   [:div {:class ["mb-2"]}
    (outcome-title-cell row)]
   [:div {:class ["grid" "grid-cols-2" "gap-x-3" "gap-y-2"]}
    [:div [:div.text-xs.text-trading-text-secondary "Size"] (amount-cell (size-text row))]
    [:div [:div.text-xs.text-trading-text-secondary "Value"] (amount-cell (value-text row))]
    [:div [:div.text-xs.text-trading-text-secondary "Entry"] (amount-cell (price-text (:entry-price row)))]
    [:div [:div.text-xs.text-trading-text-secondary "Mark"] (amount-cell (price-text (:mark-price row)))]
    [:div {:class ["col-span-2"]}
     [:div.text-xs.text-trading-text-secondary "PNL (ROE %)"]
     (amount-cell (positions-shared/format-pnl-inline (:pnl-value row) (:roe-pct row))
                  (pnl-tone-class (:pnl-value row)))]]])

(defn outcomes-tab-content
  [{:keys [outcomes]}]
  (let [rows (vec (or outcomes []))]
    (if (seq rows)
      [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
       (outcome-table-header)
       (into [:div {:class ["hidden"
                            "lg:block"
                            "flex-1"
                            "min-h-0"
                            "min-w-0"
                            "overflow-auto"
                            "scrollbar-hide"]
                   :data-role "account-tab-rows-viewport"}]
             (map (fn [row]
                    ^{:key (:key row)}
                    (outcome-row row))
                  rows))
       (into [:div {:class ["lg:hidden"
                            "flex-1"
                            "min-h-0"
                            "overflow-y-auto"
                            "scrollbar-hide"
                            "space-y-2.5"
                            "px-2.5"
                            "pt-2"
                            "pb-[calc(6rem+env(safe-area-inset-bottom))]"]
                   :data-role "outcomes-mobile-cards-viewport"}]
             (map (fn [row]
                    ^{:key (str "mobile-" (:key row))}
                    (mobile-outcome-card row))
                  rows))]
      (empty-state))))
