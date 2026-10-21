(ns hyperopen.views.account-info.tabs.earn
  "The account panel's Earn tab: the account's HyperCore lending positions and
  borrow health. Actions open the Earn page with its modal ready, so the
  modal and its previews stay inside the Earn route module."
  (:require [hyperopen.borrow-lend.account :as bl-account]
            [hyperopen.views.earn.vm :as earn-vm]))

(defn earn-tab-model
  "The slice of the Earn page view model this tab renders."
  [state]
  (let [{:keys [summary positions read-only? portfolio-margin? pools]} (earn-vm/earn-vm state)]
    {:summary summary
     :positions positions
     :utilization-by-token (into {} (map (juxt :token :utilization-label)) pools)
     :above-kink-by-token (into {} (map (juxt :token :above-kink?)) pools)
     :read-only? read-only?
     :portfolio-margin? portfolio-margin?
     :loading? (true? (get-in state [:borrow-lend :loading :user-state]))}))

(defn tab-count
  "Rows the tab shows: one per supplied leg and one per borrowed leg."
  [state]
  (reduce (fn [n {:keys [supplied borrowed]}]
            (+ n (if (pos? supplied) 1 0) (if (pos? borrowed) 1 0)))
          0
          (bl-account/positions state)))

(defn- open-earn-actions
  [kind token]
  [[:actions/navigate "/earn"]
   [:actions/open-borrow-lend-modal kind token]])

(defn- row-button
  [label kind token disabled?]
  [:button {:type "button"
            :disabled disabled?
            :class ["px-1.5" "py-1" "text-xs" "font-medium" "text-ho-accent" "hover:text-ho-accent-bright"
                    "disabled:text-ho-text-dim" "disabled:cursor-not-allowed"]
            :data-role (str "account-earn-" (name kind) "-" token)
            :on {:click (open-earn-actions kind token)}}
   label])

(def ^:private kind-labels
  {:supplied ["Supplied" "text-ho-buy"]
   :collateral ["Collateral" "text-ho-text-secondary"]
   :borrowed ["Borrowed" "text-ho-sell"]})

(def ^:private health-classes
  {:safe "text-ho-buy" :caution "text-ho-warn" :risk "text-ho-sell" :none "text-ho-text"})

(def ^:private th-base
  ["px-3" "py-2" "text-xs" "font-medium" "text-trading-text-secondary" "whitespace-nowrap"])

(def ^:private td-base
  ["px-3" "py-2.5" "text-xs" "text-trading-text" "border-t" "border-base-300" "whitespace-nowrap"])

(defn- summary-strip
  [{:keys [health-factor health-status limit-used-label liquidation-px primary-collateral-symbol
           net-apy has-debt?]}]
  [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-5" "gap-y-1.5" "px-4" "py-2.5"
                 "border-b" "border-base-300" "text-xs" "text-trading-text-secondary"]
         :data-role "account-earn-summary"}
   [:span "Health factor "
    [:span {:class ["num" "font-medium" (get health-classes health-status "text-ho-text")]} health-factor]]
   [:span "Borrow limit used " [:span {:class ["num" "text-trading-text"]} limit-used-label]]
   (when (and has-debt? primary-collateral-symbol)
     [:span (str "Liquidation if " primary-collateral-symbol " ≤ ")
      [:span {:class ["num" "text-ho-sell"]} liquidation-px]])
   [:span "Net APY " [:span {:class ["num" "text-ho-buy"]} net-apy]]
   [:button {:type "button"
             :class ["ml-auto" "font-medium" "text-ho-accent" "hover:text-ho-accent-bright"]
             :data-role "account-earn-open"
             :on {:click [[:actions/navigate "/earn"]]}}
    "Open Earn →"]])

(defn- positions-table
  [{:keys [positions utilization-by-token above-kink-by-token read-only? portfolio-margin?]}]
  [:div {:class ["relative" "overflow-auto" "min-h-0" "flex-1"]}
   [:table {:class ["w-full" "min-w-[760px]" "border-collapse"]}
    [:thead
     [:tr
      [:th {:class (into th-base ["text-left" "pl-4"])} "Asset"]
      [:th {:class (into th-base ["text-left"])} "Type"]
      [:th {:class (into th-base ["text-right"])} "Amount"]
      [:th {:class (into th-base ["text-right"])} "USD value"]
      [:th {:class (into th-base ["text-right"])} "APY"]
      [:th {:class (into th-base ["text-right"])} "Interest (30d)"]
      [:th {:class (into th-base ["text-right"])} "Pool utilization"]
      [:th {:class (into th-base ["text-right" "pr-4"])} [:span {:class ["sr-only"]} "Actions"]]]]
    [:tbody
     (for [{:keys [key token symbol kind amount value apy interest]} positions
           :let [[kind-label kind-class] (get kind-labels kind)
                 tone (case kind :supplied "text-ho-buy" :borrowed "text-ho-sell" "text-ho-text-dim")
                 pool? (not= :collateral kind)]]
       ^{:key key}
       [:tr {:data-role (str "account-earn-row-" key)}
        [:td {:class (into td-base ["text-left" "pl-4" "font-semibold"])} symbol]
        [:td {:class (into td-base ["text-left" kind-class])} kind-label]
        [:td {:class (into td-base ["text-right" "num"])} (if (= :borrowed kind) (str "-" amount) amount)]
        [:td {:class (into td-base ["text-right" "num"])} value]
        [:td {:class (into td-base ["text-right" "num" tone])} apy]
        [:td {:class (into td-base ["text-right" "num" tone])} interest]
        [:td {:class (into td-base ["text-right" "num" (if (and pool? (get above-kink-by-token token))
                                                          "text-ho-warn"
                                                          "text-trading-text-secondary")])}
         (if pool? (get utilization-by-token token "--") "--")]
        [:td {:class (into td-base ["text-right" "pr-4"])}
         (if (= :borrowed kind)
           (list (row-button "Borrow" :borrow token (or read-only? portfolio-margin?))
                 (row-button "Repay" :repay token read-only?))
           (list (row-button (if (= :collateral kind) "Add" "Supply") :supply token read-only?)
                 (row-button "Withdraw" :withdraw token read-only?)))]])]]])

(defn earn-tab-content
  [{:keys [earn]}]
  (let [{:keys [positions loading? summary]} earn]
    [:div {:class ["flex" "flex-col" "h-full" "min-h-0"] :data-role "account-earn-tab"}
     (summary-strip summary)
     (if (seq positions)
       (positions-table earn)
       [:div {:class ["flex" "flex-col" "items-center" "justify-center" "gap-2" "py-12" "text-sm"
                      "text-trading-text-secondary"]
              :data-role "account-earn-empty"}
        (if loading?
          "Loading lending positions…"
          "No lending positions. Supply a stablecoin to earn, or add collateral to borrow.")
        (when-not loading?
          [:button {:type "button"
                    :class ["text-xs" "font-medium" "text-ho-accent" "hover:text-ho-accent-bright"]
                    :on {:click [[:actions/navigate "/earn"]]}}
           "Open Earn"])])]))
