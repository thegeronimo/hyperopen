(ns hyperopen.views.account-info.tabs.outcomes
  "Outcome positions, grouped by event and priced after fees.

   Cost is what was paid. Value now is shares x best bid, less the closing fee.
   If right is $1.00 a share, less the settlement fee. If right has no total:
   two sides of one event cannot both win."
  (:require [clojure.string :as str]
            [hyperopen.outcomes.domain.clock :as clock]
            [hyperopen.outcomes.domain.quotes :as quotes]
            [hyperopen.views.account-info.projections.outcomes :as outcome-projections]
            [hyperopen.views.account-info.shared :as shared]))

(def ^:private outcomes-grid-style
  ;; Position · Shares · Avg price · Cost · Value now · If right · Action
  {:grid-template-columns (str "minmax(14rem,1.5fr) minmax(4.5rem,0.4fr) minmax(6rem,0.55fr) "
                               "minmax(5.5rem,0.5fr) minmax(6rem,0.55fr) minmax(6rem,0.55fr) "
                               "minmax(7.5rem,0.7fr)")})

(def ^:private outcomes-grid-min-width-class
  "min-w-[880px]")

(def footnote
  (str "Value now = shares × best bid, less the closing fee. If right = $1.00 a share, "
       "less the settlement fee. \"If right\" has no total: two sides of one event "
       "cannot both win."))

(defn- empty-state
  []
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium "No active outcomes"]
   [:div {:class ["mt-2" "text-sm" "text-trading-text-secondary"]} "No data available"]])

(defn- header-cell
  [label]
  [:div {:class ["text-left" "px-0" "text-xs" "font-medium"
                 "text-trading-text-secondary" "whitespace-nowrap"]}
   label])

(defn- amount-cell
  [text & [tone-class]]
  [:div {:class ["text-left" "font-semibold" "num" "whitespace-nowrap" (or tone-class "text-trading-text")]}
   text])

(defn shares-text
  [{:keys [size]}]
  (str (js/Math.floor (or size 0))))

(defn avg-price-text
  "Average price in cents with the book's precision: 81.902¢."
  [{:keys [entry-price]}]
  (quotes/cents-text entry-price {:precise? true}))

(defn cost-text
  [{:keys [cost]}]
  (quotes/usd-text cost))

(defn value-now-text
  "After the closing fee; \"~\" when the price is a last mark, not a bid; the
   gross figure with a note when the venue's fee is unconfirmed."
  [{:keys [value-after-fees value-basis fee-known?]}]
  (str (when (= :mark value-basis) "~")
       (quotes/usd-text value-after-fees)
       (when (false? fee-known?) " (fee unconfirmed)")))

(defn if-right-text
  [{:keys [if-right fee-known?]}]
  (str (quotes/usd-text if-right)
       (when (false? fee-known?) " (fee unconfirmed)")))

(defn event-meta-text
  "\"NBA · starts in 7h 34m\"."
  [{:keys [market-meta clock]} now-ms]
  (let [when-text (some-> (clock/clock-view clock now-ms) :label str/lower-case)]
    (->> [market-meta when-text]
         (remove str/blank?)
         (str/join " · "))))

(defn pair-text
  "\"40 pairs · worth $40.00 whoever wins\"."
  [{:keys [pairs worth]}]
  (str pairs (if (== 1 pairs) " pair" " pairs") " · worth " (quotes/usd-text worth) " whoever wins"))

(defn merge-label
  "\"Merge for $39.94\"; plain \"Merge\" when the fee is unconfirmed."
  [{:keys [merge]}]
  (if (:fee-known? merge)
    (str "Merge for " (quotes/usd-text (:net merge)))
    "Merge"))

(defn- outcome-side
  [{:keys [side-index side-name]}]
  (cond
    (= 1 side-index) :short
    (= 0 side-index) :long
    (= "no" (some-> side-name str str/trim str/lower-case)) :short
    :else :long))

(defn open-market-actions
  [{:keys [market-key side-coin]}]
  (when-let [side-coin* (shared/non-blank-text side-coin)]
    [[:actions/open-outcome-market market-key side-coin*]]))

(defn cash-out-actions
  "ONE action: take the user to this market's ticket in cash-out mode."
  [{:keys [market-key side-coin size]}]
  [[:actions/open-outcome-cash-out market-key side-coin (js/Math.floor (or size 0))]])

(defn merge-actions
  [{:keys [market-key side-coins pairs]}]
  [[:actions/merge-outcome-pairs market-key (first side-coins) (second side-coins) pairs]])

(def ^:private link-button-classes
  ["inline-flex" "w-full" "justify-start" "bg-transparent" "p-0" "font-semibold"
   "text-trading-green" "transition-colors" "focus:outline-none" "focus:ring-0"
   "hover:text-ho-accent-bright" "focus-visible:text-ho-accent-bright" "whitespace-nowrap"])

(defn- cash-out-button
  [row]
  [:button {:class link-button-classes
            :type "button"
            :data-role "outcome-cash-out"
            :on {:click (cash-out-actions row)}}
   "Cash out"])

(defn- merge-button
  [pair]
  [:button {:class link-button-classes
            :type "button"
            :data-role "outcome-merge"
            :on {:click (merge-actions pair)}}
   (merge-label pair)])

(defn- event-header
  [{:keys [title rows] :as group} now-ms]
  (let [lead (first rows)
        meta-text (event-meta-text group now-ms)]
    [:div {:class ["py-1.5" "pr-3" "pl-3" "bg-base-200" "min-w-0"]
           :data-role "outcome-event-header"}
     (shared/coin-select-control
      (:side-coin lead)
      [:span {:class ["flex" "w-full" "min-w-0" "flex-col" "items-start"]}
       [:span {:class ["block" "w-full" "min-w-0" "truncate" "font-semibold" "text-trading-text"]
               :title title}
        title]
       (when (seq meta-text)
         [:span {:class ["block" "w-full" "min-w-0" "truncate" "text-xs" "text-trading-text-secondary"]}
          meta-text])]
      {:extra-classes ["w-full" "justify-start" "overflow-hidden" "text-left"]
       :click-actions (open-market-actions lead)
       :attrs {:data-role "outcome-market-select"}})]))

(defn- position-cell
  [{:keys [position-label your-side side-name] :as row}]
  [:div {:class ["min-w-0" "truncate" "font-semibold" (shared/position-side-tone-class (outcome-side row))]
         :title (or your-side position-label)}
   (or position-label side-name)])

(defn- outcome-row
  [row read-only?]
  [:div {:class ["grid" "gap-2" "py-1.5" "pr-3" "pl-3" outcomes-grid-min-width-class
                 "hover:bg-base-300" "items-center" "text-sm"]
         :style outcomes-grid-style
         :data-role (str "outcome-row-" (:side-coin row))}
   (position-cell row)
   (amount-cell (shares-text row))
   (amount-cell (avg-price-text row))
   (amount-cell (cost-text row))
   (amount-cell (value-now-text row))
   (amount-cell (if-right-text row))
   [:div {:class ["text-left" "relative"]}
    (when-not read-only?
      (cash-out-button row))]])

(defn- pair-row
  [pair read-only?]
  [:div {:class ["grid" "gap-2" "py-1" "pr-3" "pl-3" outcomes-grid-min-width-class
                 "items-center" "text-xs" "text-trading-text-secondary"]
         :style outcomes-grid-style
         :data-role "outcome-pair-row"}
   [:div {:class ["col-span-6" "whitespace-nowrap"]} (pair-text pair)]
   [:div {:class ["text-left"]}
    (when-not read-only?
      (merge-button pair))]])

(defn- totals-row
  [{:keys [cost value-now]}]
  [:div {:class ["grid" "gap-2" "py-1.5" "pr-3" "pl-3" outcomes-grid-min-width-class
                 "items-center" "text-sm" "border-t" "border-base-300"]
         :style outcomes-grid-style
         :data-role "outcome-totals-row"}
   [:div {:class ["font-semibold" "text-trading-text"]} "Total"]
   [:div] [:div]
   (amount-cell (quotes/usd-text cost))
   (amount-cell (quotes/usd-text value-now))
   [:div] [:div]])

(defn- outcome-table-header
  []
  [:div {:class ["grid" "gap-2" "py-1" "pr-3" "pl-3" outcomes-grid-min-width-class "bg-base-200"]
         :style outcomes-grid-style}
   (header-cell "Position")
   (header-cell "Shares")
   (header-cell "Avg price")
   (header-cell "Cost")
   (header-cell "Value now")
   (header-cell "If right")
   (header-cell "Action")])

(defn- group-block
  [group read-only? now-ms]
  (into [:div {:data-role (str "outcome-group-" (:key group))}
         (event-header group now-ms)]
        (concat
         (map (fn [row]
                ^{:key (:key row)}
                (outcome-row row read-only?))
              (:rows group))
         (when-let [pair (:pair group)]
           [^{:key (str (:key group) "-pair")}
            (pair-row pair read-only?)]))))

(defn- mobile-row
  [row read-only?]
  [:div {:class ["rounded-md" "border" "border-base-300" "bg-base-200" "px-3" "py-2.5" "text-sm" "spectate-sm"]
         :data-role (str "mobile-outcome-card-" (:side-coin row))}
   [:div {:class ["mb-2" "flex" "items-start" "gap-3"]}
    [:div {:class ["min-w-0" "flex-1"]} (position-cell row)]
    (when-not read-only?
      [:div {:class ["shrink-0" "pt-0.5"]} (cash-out-button row)])]
   [:div {:class ["grid" "grid-cols-2" "gap-x-3" "gap-y-2"]}
    [:div [:div.text-xs.text-trading-text-secondary "Shares"] (amount-cell (shares-text row))]
    [:div [:div.text-xs.text-trading-text-secondary "Avg price"] (amount-cell (avg-price-text row))]
    [:div [:div.text-xs.text-trading-text-secondary "Cost"] (amount-cell (cost-text row))]
    [:div [:div.text-xs.text-trading-text-secondary "Value now"] (amount-cell (value-now-text row))]
    [:div {:class ["col-span-2"]}
     [:div.text-xs.text-trading-text-secondary "If right"]
     (amount-cell (if-right-text row))]]])

(defn- mobile-group
  [group read-only? now-ms]
  (into [:div {:class ["space-y-2"] :data-role (str "mobile-outcome-group-" (:key group))}
         (event-header group now-ms)]
        (concat
         (map (fn [row]
                ^{:key (str "mobile-" (:key row))}
                (mobile-row row read-only?))
              (:rows group))
         (when-let [pair (:pair group)]
           [^{:key (str (:key group) "-mobile-pair")}
            [:div {:class ["flex" "items-center" "justify-between" "gap-3" "px-3" "text-xs"
                           "text-trading-text-secondary"]}
             [:span (pair-text pair)]
             (when-not read-only? (merge-button pair))]]))))

(defn outcomes-tab-content
  [{:keys [outcomes read-only? now-ms user-fees]}]
  (let [rows (vec (or outcomes []))
        now-ms (or now-ms (.now js/Date))
        groups (outcome-projections/group-by-event rows user-fees)
        totals (outcome-projections/totals groups)]
    (if (seq rows)
      [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
       (outcome-table-header)
       (into [:div {:class ["hidden" "lg:block" "flex-1" "min-h-0" "min-w-0" "overflow-auto" "scrollbar-hide"]
                    :data-role "account-tab-rows-viewport"}]
             (concat
              (map (fn [group]
                     ^{:key (:key group)}
                     (group-block group read-only? now-ms))
                   groups)
              [(totals-row totals)
               [:div {:class ["px-3" "py-2" "text-xs" "text-trading-text-secondary"]
                      :data-role "outcome-footnote"}
                footnote]]))
       (into [:div {:class ["lg:hidden" "flex-1" "min-h-0" "overflow-y-auto" "scrollbar-hide"
                           "space-y-2.5" "px-2.5" "pt-2"
                           "pb-[calc(6rem+env(safe-area-inset-bottom))]"]
                    :data-role "outcomes-mobile-cards-viewport"}]
             (concat
              (map (fn [group]
                     ^{:key (str "mobile-" (:key group))}
                     (mobile-group group read-only? now-ms))
                   groups)
              [[:div {:class ["flex" "justify-between" "px-3" "text-sm" "font-semibold"]
                      :data-role "outcome-totals-row-mobile"}
                [:span "Total"]
                [:span (str (quotes/usd-text (:cost totals)) " cost · "
                            (quotes/usd-text (:value-now totals)) " now")]]
               [:div {:class ["px-3" "text-xs" "text-trading-text-secondary"]} footnote]]))]
      (empty-state))))
