(ns hyperopen.views.account-equity.venue-breakdown
  "The classic panel's per-venue views: the Perps row that opens into one row
   per venue, and Perps Overview with a venue switch.

   Neither holds app state. The Perps row is a native `<details>`; the switch
   is a radio group read by CSS (`.perp-venue-tabs` in
   `src/styles/surfaces/account.css`). Uncontrolled inputs keep their DOM
   state across re-renders, so `:checked` is never passed from render: the
   default tab is the one CSS shows while nothing is checked."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.views.account-equity.format :refer [display-currency
                                                           display-leverage
                                                           display-percent
                                                           label-with-tooltip
                                                           metric-row
                                                           pnl-display]]))

(def ^:private perps-tooltip
  "Balance + Unrealized PNL (approximate account value if all positions were closed)")

(defn- chevron []
  [:svg {:viewBox "0 0 12 12" :fill "none" :stroke "currentColor" :stroke-width "1.6"
         :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"
         :class ["perp-venue-chevron" "h-3" "w-3" "shrink-0" "text-trading-text-secondary"]}
   [:path {:d "M4.5 3l3 3-3 3"}]])

(defn- this-market-tag []
  [:span {:class ["rounded" "bg-ho-accent-soft" "px-1.5" "text-xs" "font-semibold" "uppercase"
                  "tracking-wide" "text-ho-accent-bright"]
          :data-role "perp-venue-this-market"}
   "This market"])

(defn- transfer-action
  [{:keys [id dex]}]
  [:actions/open-funding-transfer-modal
   :event.currentTarget/bounds
   (str "account-equity-venue-transfer-" id)
   {:dex (or dex "") :to-perp? true}])

(defn- venue-row
  [{:keys [id label value funded? this-market?] :as venue} can-transfer?]
  (let [unfunded? (and this-market? (not funded?))]
    [:div {:class ["flex" "items-center" "justify-between" "gap-2" "border-l" "pl-3" "ml-1" "text-sm"
                   (if unfunded? "border-ho-warn" "border-base-300")]
           :data-role (str "perp-venue-row-" id)}
     [:span {:class ["flex" "min-w-0" "items-center" "gap-1.5" "text-trading-text"]}
      [:span {:class ["truncate"]} label]
      (if this-market? (this-market-tag) [:span {:class ["hidden"]}])]
     [:span {:class ["flex" "shrink-0" "items-center" "gap-2.5"]}
      [:button (cond-> {:type "button"
                        :class (into ["text-xs" "font-medium" "transition-colors"]
                                     (cond
                                       (not can-transfer?) ["hidden"]
                                       unfunded? ["rounded-md" "border" "border-ho-warn" "px-2" "py-0.5"
                                                  "font-semibold" "text-ho-warn"]
                                       :else ["text-ho-accent" "hover:text-ho-accent-hi"]))
                        :data-role (str "account-equity-venue-transfer-" id)}
                 can-transfer? (assoc :on {:click [(transfer-action venue)]}))
       (if unfunded? "Fund" "Transfer")]
      [:span {:class ["num" (if unfunded? "text-ho-warn" "text-trading-text")]}
       (display-currency value)]]]))

(defn perps-row
  "The Perps row. With a venue breakdown it opens into one row per venue."
  [{:keys [perps-value perp-venues state]}]
  (if-not perp-venues
    (metric-row "Perps" (display-currency perps-value) :tooltip perps-tooltip)
    (let [{:keys [rows hidden-count funded-count]} perp-venues
          can-transfer? (not (account-context/spectate-mode-active? state))
          unfunded (some #(when (and (:this-market? %) (not (:funded? %))) %) rows)]
      [:details {:class ["perp-venue-breakdown"] :open true :data-role "perp-venue-breakdown"}
       [:summary {:class ["flex" "cursor-pointer" "list-none" "items-center" "justify-between" "text-sm"]}
        [:span {:class ["flex" "items-center" "gap-1.5"]}
         (label-with-tooltip "Perps" perps-tooltip)
         (chevron)
         [:span {:class ["text-xs" "text-trading-text-secondary"] :data-role "perp-venue-count"}
          (str funded-count (if (= 1 funded-count) " venue" " venues"))]]
        [:span {:class ["num" "text-trading-text"]} (display-currency perps-value)]]
       (into [:div {:class ["space-y-0.5" "py-0.5"]}]
             (map #(venue-row % can-transfer?))
             rows)
       [:p {:class (cond-> ["ml-1" "pl-3" "text-xs" "text-trading-text-secondary"]
                     (not unfunded) (conj "hidden"))
            :data-role "perp-venue-unfunded-note"}
        (if unfunded
          (str (:label unfunded) " markets use their own margin. Your other perps balances do not back them.")
          "")]
       [:p {:class (cond-> ["ml-1" "pl-3" "text-xs" "text-trading-text-secondary"]
                     (not (pos? hidden-count)) (conj "hidden"))
            :data-role "perp-venue-hidden-count"}
        (str hidden-count (if (= 1 hidden-count) " more venue" " more venues") " with no balance")]])))

(defn- overview-rows
  [{:keys [balance unrealized-pnl cross-margin-ratio maintenance-margin cross-account-leverage available]}]
  (let [pnl (pnl-display unrealized-pnl)]
    (list
     (metric-row "Balance" (display-currency balance)
                 :tooltip "Total Net Transfers + Total Realized Profit + Total Net Funding Fees")
     (metric-row "Unrealized PNL" (:text pnl) :value-class (:class pnl))
     (metric-row "Cross Margin Ratio" (display-percent cross-margin-ratio)
                 :tooltip "Maintenance Margin / Portfolio Value. Your cross positions will be liquidated if Margin Ratio reaches 100%.")
     (metric-row "Maintenance Margin" (display-currency maintenance-margin)
                 :tooltip "The minimum portfolio value required to keep your cross positions open")
     (metric-row "Cross Account Leverage" (display-leverage cross-account-leverage)
                 :tooltip "Cross Account Leverage = Total Cross Positions Value / Cross Account Value.")
     (metric-row "Available to trade" (display-currency available)
                 :tooltip "Margin free to open positions on this venue."))))

(defn venue-overview
  "Perps Overview for one venue at a time. Margin ratio, maintenance margin
   and leverage are per venue; a sum across venues describes none of them."
  [{:keys [rows]}]
  (let [default-id (:id (or (some #(when (:this-market? %) %) rows) (first rows)))]
    ;; A form scopes the radio group, so two copies of the panel on one page
    ;; (desktop and mobile layouts) do not share a selection; the inputs sit
    ;; inside their labels, so no element ids are needed either.
    (into [:form {:class ["perp-venue-tabs"] :data-role "perp-venue-overview"}
           [:div {:class ["perp-venue-tabs-title" "text-xs" "font-semibold" "text-trading-text"]}
            "Perps Overview"]]
          (mapcat (fn [{:keys [id label] :as venue}]
                    (let [default? (= id default-id)]
                      [[:label (cond-> {:class ["perp-venue-tab"]
                                        :data-role (str "perp-venue-tab-" id)}
                                 default? (assoc :data-default "true"))
                        [:input {:type "radio" :name "perp-venue-tab" :class ["sr-only"] :data-venue id}]
                        label]
                       (into [:div (cond-> {:class ["perp-venue-panel" "space-y-2"]
                                            :data-role (str "perp-venue-panel-" id)}
                                     default? (assoc :data-default "true"))]
                             (overview-rows venue))])))
          rows)))
