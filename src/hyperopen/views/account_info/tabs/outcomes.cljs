(ns hyperopen.views.account-info.tabs.outcomes
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.quotes :as quotes]
            [hyperopen.router :as router]
            [hyperopen.views.account-info.position-reduce-popover :as position-reduce-popover]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tabs.positions.shared :as positions-shared]))

(def ^:private outcomes-grid-style
  ;; Market · Your side · Shares · Avg → now · Value now · Pays if right · P&L · Resolves · Action
  {:grid-template-columns (str "minmax(16rem,1.6fr) minmax(7rem,0.7fr) minmax(4.5rem,0.4fr) minmax(8rem,0.6fr) "
                               "minmax(6rem,0.55fr) minmax(6.5rem,0.55fr) minmax(7.5rem,0.7fr) minmax(8rem,0.7fr) "
                               "minmax(5.5rem,0.45fr)")})

(def ^:private outcomes-grid-min-width-class
  "min-w-[1120px]")

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
  [{:keys [title side-coin market-meta] :as row}]
  (let [side (outcome-side row)
        coin-cell-style (shared/position-coin-cell-style-for-side side)]
    [:div {:class ["flex" "min-w-0" "items-center" "self-stretch"]
           :style coin-cell-style}
     (shared/coin-select-control
      side-coin
      [:span {:class ["flex" "w-full" "min-w-0" "flex-col" "items-start"]}
       [:span {:class ["block" "w-full" "min-w-0" "truncate" "font-semibold" "text-trading-text"]
               :title title}
        title]
       (when (seq market-meta)
         [:span {:class ["block" "w-full" "min-w-0" "truncate" "text-xs" "text-trading-text-secondary"]}
          market-meta])]
      {:extra-classes ["w-full" "justify-start" "overflow-hidden" "text-left"]
       :click-actions (outcome-title-click-actions row)
       :attrs {:data-role "outcome-market-select"}})]))

(defn- your-side-cell
  [{:keys [your-side side-name] :as row}]
  [:div {:class ["min-w-0" "truncate" "font-semibold" (shared/position-side-tone-class (outcome-side row))]
         :title your-side}
   (or your-side side-name)])

(defn- amount-cell
  [text & [tone-class]]
  [:div {:class ["text-left" "font-semibold" "num" "whitespace-nowrap" (or tone-class "text-trading-text")]}
   text])

(defn- shares-text
  [{:keys [size]}]
  (str (js/Math.floor (or size 0))))

(defn- avg-now-text
  [{:keys [entry-price mark-price]}]
  (str (quotes/cents-text entry-price) " → " (quotes/cents-text mark-price)))

(defn- resolves-text
  [{:keys [clock]} now-ms]
  (or (:label (clock/clock-view clock now-ms)) "—"))

(defn- pnl-tone-class
  [pnl-value]
  (cond
    (pos? pnl-value) "text-success"
    (neg? pnl-value) "text-error"
    :else "text-trading-text"))

(defn- active-reduce-popover?
  [row reduce-popover]
  (and (position-reduce/open? reduce-popover)
       (= (:key row) (:position-key reduce-popover))))

(defn- reduce-button
  [row]
  [:button {:class ["inline-flex"
                    "w-full"
                    "justify-start"
                    "bg-transparent"
                    "p-0"
                    "font-semibold"
                    "text-trading-green"
                    "transition-colors"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus:shadow-none"
                    "focus-visible:outline-none"
                    "focus-visible:ring-0"
                    "focus-visible:ring-offset-0"
                    "hover:text-ho-accent-bright"
                    "focus-visible:text-ho-accent-bright"
                    "whitespace-nowrap"]
            :type "button"
            :data-position-reduce-trigger "true"
            :data-role "outcome-cash-out"
            :on {:click [[:actions/open-position-reduce-popover row :event.currentTarget/bounds]]}}
   "Cash out"])

(defn- outcome-row
  [row reduce-popover read-only? now-ms]
  (let [active-reduce-popover? (and (not read-only?)
                                    (active-reduce-popover? row reduce-popover))]
    [:div {:class ["grid"
                   "gap-2"
                   "py-1.5"
                   "pr-3"
                   outcomes-grid-min-width-class
                   "hover:bg-base-300"
                   "items-center"
                   "text-sm"]
           :style outcomes-grid-style
           :data-role (str "outcome-row-" (:side-coin row))}
     (outcome-title-cell row)
     (your-side-cell row)
     (amount-cell (shares-text row))
     (amount-cell (avg-now-text row))
     (amount-cell (quotes/usd-text (:position-value row)))
     (amount-cell (quotes/usd-text (or (:pays row) (:size row))))
     (amount-cell (positions-shared/format-pnl-inline (:pnl-value row) (:roe-pct row))
                  (pnl-tone-class (:pnl-value row)))
     [:div {:class ["truncate" "text-left" "text-xs" "text-trading-text-secondary"]}
      (resolves-text row now-ms)]
     [:div {:class ["text-left" "relative"]}
      (when-not read-only?
        (reduce-button row))
      (when active-reduce-popover?
        (position-reduce-popover/position-reduce-popover-view reduce-popover))]]))

(defn- outcome-table-header
  []
  [:div {:class ["grid"
                 "gap-2"
                 "py-1"
                 "pr-3"
                 outcomes-grid-min-width-class
                 "bg-base-200"]
         :style outcomes-grid-style}
   [:div.text-left.pl-3 (header-cell "Market")]
   (header-cell "Your side")
   (header-cell "Shares")
   (header-cell "Avg → now")
   (header-cell "Value now")
   (header-cell "Pays if right")
   (header-cell "P&L")
   (header-cell "Resolves")
   (header-cell "Action")])

(defn- mobile-outcome-card
  [row reduce-popover read-only? now-ms]
  (let [active-reduce-popover? (and (not read-only?)
                                    (active-reduce-popover? row reduce-popover))]
    [:div {:class ["rounded-md"
                   "border"
                   "border-base-300"
                   "bg-base-200"
                   "px-3"
                   "py-2.5"
                   "text-sm"
                   "spectate-sm"]
           :data-role (str "mobile-outcome-card-" (:side-coin row))}
     [:div {:class ["mb-2" "flex" "items-start" "gap-3"]}
      [:div {:class ["min-w-0" "flex-1"]}
       (outcome-title-cell row)]
      (when-not read-only?
        [:div {:class ["shrink-0" "pt-0.5"]}
         (reduce-button row)])]
     [:div {:class ["grid" "grid-cols-2" "gap-x-3" "gap-y-2"]}
      [:div [:div.text-xs.text-trading-text-secondary "Your side"] (your-side-cell row)]
      [:div [:div.text-xs.text-trading-text-secondary "Shares"] (amount-cell (shares-text row))]
      [:div [:div.text-xs.text-trading-text-secondary "Avg → now"] (amount-cell (avg-now-text row))]
      [:div [:div.text-xs.text-trading-text-secondary "Value now"] (amount-cell (quotes/usd-text (:position-value row)))]
      [:div [:div.text-xs.text-trading-text-secondary "Pays if right"] (amount-cell (quotes/usd-text (or (:pays row) (:size row))))]
      [:div [:div.text-xs.text-trading-text-secondary "Resolves"] (amount-cell (resolves-text row now-ms))]
      [:div {:class ["col-span-2"]}
       [:div.text-xs.text-trading-text-secondary "P&L"]
       (amount-cell (positions-shared/format-pnl-inline (:pnl-value row) (:roe-pct row))
                    (pnl-tone-class (:pnl-value row)))]]
     (when active-reduce-popover?
       (position-reduce-popover/position-reduce-popover-view reduce-popover))]))

(defn outcomes-tab-content
  [{:keys [outcomes reduce-popover read-only? now-ms]}]
  (let [rows (vec (or outcomes []))
        now-ms (or now-ms (.now js/Date))]
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
                    (outcome-row row reduce-popover read-only? now-ms))
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
                    (mobile-outcome-card row reduce-popover read-only? now-ms))
                  rows))]
      (empty-state))))
