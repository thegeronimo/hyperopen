(ns hyperopen.views.earn-view
  "The /earn route: HyperCore lending pools, the account's positions and
  borrow health, and the Earn modal."
  (:require [hyperopen.views.earn.modal :as earn-modal]
            [hyperopen.views.earn.vm :as earn-vm]))

(def ^:private card-classes
  ["rounded-lg" "border" "border-ho-border" "bg-ho-surface"])

(def ^:private th-base
  ["px-3" "py-2.5" "text-xs" "font-medium" "text-ho-text-secondary" "whitespace-nowrap"])

(def ^:private td-base
  ["px-3" "py-3.5" "text-sm" "text-ho-text" "border-t" "border-ho-border" "whitespace-nowrap"])

(def ^:private th-classes (conj th-base "text-right"))

(def ^:private td-classes (conj td-base "text-right"))

(defn- action-button
  [{:keys [label primary? disabled? action data-role]}]
  [:button {:type "button"
            :disabled disabled?
            :class (into ["h-8" "px-3.5" "rounded-md" "text-xs" "font-medium" "border" "transition-colors"
                          "disabled:opacity-40" "disabled:cursor-not-allowed"]
                         (if primary?
                           ["bg-ho-accent" "border-ho-accent" "text-ho-bg-deep" "hover:bg-ho-accent-hi"]
                           ["bg-ho-surface-raised" "border-ho-border" "text-ho-text" "hover:border-ho-text-dim"]))
            :data-role data-role
            :on {:click [action]}}
   label])

(defn- open-modal-action
  [{:keys [connected?]} kind token]
  (if connected?
    [:actions/open-borrow-lend-modal kind token]
    [:actions/connect-wallet]))

(defn- summary-card
  [label value sub data-role value-classes]
  [:div {:class (into card-classes ["p-3" "sm:p-4" "flex" "flex-col" "gap-1.5" "min-w-0"]) :data-role data-role}
   [:div {:class ["text-xs" "text-ho-text-secondary"]} label]
   [:div {:class (into ["num" "text-xl" "font-medium"] (or value-classes ["text-ho-text"]))} value]
   [:div {:class ["text-xs" "text-ho-text-secondary" "truncate"]} sub]])

(def ^:private health-classes
  {:safe ["text-ho-buy"] :caution ["text-ho-warn"] :risk ["text-ho-sell"] :none ["text-ho-text"]})

(defn- summary-cards
  [{:keys [summary]}]
  (let [{:keys [supplied-usd supply-apy collateral-usd borrow-limit-usd borrowed-usd borrow-apy
                health-factor health-status health-label liquidation-px primary-collateral-symbol]} summary]
    [:section {:aria-label "Your position"
               :class ["grid" "gap-2" "sm:gap-3" "grid-cols-2" "xl:grid-cols-4"]}
     (summary-card "Supplied" supplied-usd (str "Earning " supply-apy) "earn-summary-supplied" nil)
     (summary-card "Collateral" collateral-usd (str "Borrow limit " borrow-limit-usd) "earn-summary-collateral" nil)
     (summary-card "Borrowed" borrowed-usd (str "Paying " borrow-apy) "earn-summary-borrowed" nil)
     (summary-card (str "Health factor · " health-label) health-factor
                   (if primary-collateral-symbol
                     (str "Liquidation if " primary-collateral-symbol " ≤ " liquidation-px)
                     "No collateral supplied")
                   "earn-summary-health"
                   (get health-classes health-status))]))

(defn- utilization-bar
  [utilization above-kink? label]
  [:div {:class ["flex" "items-center" "gap-2.5"]}
   [:div {:class ["relative" "flex-1" "h-1.5" "rounded-full" "bg-ho-surface-raised"]
          :role "img"
          :aria-label (str "Utilization " label ", kink at 80%")}
    [:div {:class (into ["absolute" "inset-y-0" "left-0" "rounded-full"]
                        (if above-kink? ["bg-ho-warn"] ["bg-ho-accent"]))
           :style {:width (str (* 100 (min 1 (max 0 (or utilization 0)))) "%")}}]
    [:div {:class ["absolute" "left-[80%]" "-top-1" "w-0.5" "h-3.5" "rounded-sm" "bg-ho-text"]}]]
   [:span {:class (into ["num" "text-xs" "min-w-[44px]" "text-right"]
                        (if above-kink? ["text-ho-warn"] ["text-ho-text"]))}
    label]])

(defn- token-badge
  [symbol]
  [:span {:aria-hidden "true"
          :class ["w-7" "h-7" "rounded-full" "inline-flex" "items-center" "justify-center"
                  "text-xs" "font-semibold" "bg-ho-accent-soft" "text-ho-accent"]}
   (subs (or symbol "?") 0 1)])

(defn- pools-table
  [{:keys [pools portfolio-margin? read-only?] :as vm}]
  [:section {:aria-labelledby "earn-pools-heading" :class card-classes :data-role "earn-pools"}
   [:div {:class ["flex" "flex-wrap" "justify-between" "items-baseline" "gap-3" "px-4" "pt-4" "pb-1"]}
    [:h2 {:id "earn-pools-heading" :class ["m-0" "text-sm" "font-semibold"]} "Stablecoin pools"]
    [:span {:class ["text-xs" "text-ho-text-dim"]} "Interest accrues hourly · bar marker = 80% kink"]]
   [:div {:class ["relative" "overflow-x-auto"]}
    [:table {:class ["w-full" "min-w-[820px]" "border-collapse"]}
     [:thead
      [:tr
       [:th {:class (into th-base ["text-left" "pl-4"])} "Asset"]
       [:th {:class th-classes} "Supply APY"]
       [:th {:class th-classes} "Borrow APY"]
       [:th {:class (into th-base ["text-left" "w-[180px]"])} "Utilization"]
       [:th {:class th-classes} "Total supplied"]
       [:th {:class th-classes} "Total borrowed"]
       [:th {:class th-classes} "Available"]
       [:th {:class (into th-classes ["pr-4"])} [:span {:class ["sr-only"]} "Actions"]]]]
     [:tbody
      (for [{:keys [token symbol supply-apy borrow-apy utilization utilization-label above-kink?
                    supplied borrowed available]} pools]
        ^{:key token}
        [:tr {:data-role (str "earn-pool-row-" token)}
         [:td {:class (into td-base ["text-left" "pl-4"])}
          [:div {:class ["flex" "items-center" "gap-2.5"]}
           (token-badge symbol)
           [:span {:class ["font-semibold"]} symbol]]]
         [:td {:class (into td-classes ["num" "text-ho-buy"])} supply-apy]
         [:td {:class (into td-classes ["num"])} borrow-apy]
         [:td {:class (into td-base ["text-left"])} (utilization-bar utilization above-kink? utilization-label)]
         [:td {:class (into td-classes ["num"])} supplied]
         [:td {:class (into td-classes ["num"])} borrowed]
         [:td {:class (into td-classes ["num"])} available]
         [:td {:class (into td-classes ["pr-4"])}
          [:div {:class ["flex" "gap-1.5" "justify-end"]}
           (action-button {:label "Supply" :primary? true :disabled? read-only?
                           :action (open-modal-action vm :supply token)
                           :data-role (str "earn-supply-" token)})
           (action-button {:label "Borrow" :disabled? (or read-only? portfolio-margin?)
                           :action (open-modal-action vm :borrow token)
                           :data-role (str "earn-borrow-" token)})]]])]]]])

(defn- collateral-table
  [{:keys [collateral read-only?] :as vm}]
  [:section {:aria-labelledby "earn-collateral-heading" :class card-classes :data-role "earn-collateral"}
   [:div {:class ["flex" "flex-wrap" "justify-between" "items-baseline" "gap-3" "px-4" "pt-4" "pb-1"]}
    [:h2 {:id "earn-collateral-heading" :class ["m-0" "text-sm" "font-semibold"]} "Collateral"]
    [:span {:class ["text-xs" "text-ho-text-dim"]} "Collateral earns no interest. It sets how much you can borrow."]]
   [:div {:class ["relative" "overflow-x-auto"]}
    [:table {:class ["w-full" "min-w-[820px]" "border-collapse"]}
     [:thead
      [:tr
       [:th {:class (into th-base ["text-left" "pl-4"])} "Asset"]
       [:th {:class th-classes} "Oracle price"]
       [:th {:class th-classes} "Max LTV"]
       [:th {:class th-classes} "Liquidation at"]
       [:th {:class th-classes} "Total supplied"]
       [:th {:class th-classes} "Your collateral"]
       [:th {:class (into th-classes ["pr-4"])} [:span {:class ["sr-only"]} "Actions"]]]]
     [:tbody
      (for [{:keys [token symbol oracle-px ltv threshold total-supplied-usd mine has-position?]} collateral]
        ^{:key token}
        [:tr {:data-role (str "earn-collateral-row-" token)}
         [:td {:class (into td-base ["text-left" "pl-4"])}
          [:div {:class ["flex" "items-center" "gap-2.5"]}
           (token-badge symbol)
           [:span {:class ["font-semibold"]} symbol]]]
         [:td {:class (into td-classes ["num"])} oracle-px]
         [:td {:class (into td-classes ["num"])} ltv]
         [:td {:class (into td-classes ["num"])} threshold]
         [:td {:class (into td-classes ["num"])} total-supplied-usd]
         [:td {:class (into td-classes ["num"])} mine]
         [:td {:class (into td-classes ["pr-4"])}
          [:div {:class ["flex" "gap-1.5" "justify-end"]}
           (action-button {:label "Add" :disabled? read-only?
                           :action (open-modal-action vm :supply token)
                           :data-role (str "earn-collateral-add-" token)})
           (action-button {:label "Withdraw" :disabled? (or read-only? (not has-position?))
                           :action (open-modal-action vm :withdraw token)
                           :data-role (str "earn-collateral-withdraw-" token)})]]])]]]])

(def ^:private kind-labels
  {:supplied ["Supplied" "text-ho-buy"]
   :collateral ["Collateral" "text-ho-text-secondary"]
   :borrowed ["Borrowed" "text-ho-sell"]})

(defn- positions-table
  [{:keys [positions read-only? portfolio-margin?] :as vm}]
  (if (empty? positions)
    [:section {:class (into card-classes ["p-8" "text-center" "text-sm" "text-ho-text-secondary"])
               :data-role "earn-positions-empty"}
     "No lending positions yet. Supply a stablecoin to earn, or add HYPE or UBTC as collateral to borrow."]
    [:section {:aria-label "Your positions" :class (into card-classes ["relative" "overflow-x-auto"])
               :data-role "earn-positions"}
     [:table {:class ["w-full" "min-w-[760px]" "border-collapse"]}
      [:thead
       [:tr
        [:th {:class (into th-base ["text-left" "pl-4"])} "Position"]
        [:th {:class th-classes} "Amount"]
        [:th {:class th-classes} "Value"]
        [:th {:class th-classes} "APY"]
        [:th {:class th-classes} "Interest (30d)"]
        [:th {:class (into th-classes ["pr-4"])} [:span {:class ["sr-only"]} "Actions"]]]]
      [:tbody
       (for [{:keys [key token symbol kind amount value apy interest]} positions
             :let [[kind-label kind-class] (get kind-labels kind)
                   tone (case kind :supplied "text-ho-buy" :borrowed "text-ho-sell" "text-ho-text-dim")]]
         ^{:key key}
         [:tr {:data-role (str "earn-position-" key)}
          [:td {:class (into td-base ["text-left" "pl-4"])}
           [:span {:class ["font-semibold"]} symbol]
           [:span {:class ["ml-2" "text-xs" kind-class]} kind-label]]
          [:td {:class (into td-classes ["num"])} amount]
          [:td {:class (into td-classes ["num"])} value]
          [:td {:class (conj td-classes "num" tone)} apy]
          [:td {:class (conj td-classes "num" tone)} interest]
          [:td {:class (into td-classes ["pr-4"])}
           [:div {:class ["flex" "gap-1.5" "justify-end"]}
            (if (= :borrowed kind)
              (list
               (action-button {:label "Borrow" :disabled? (or read-only? portfolio-margin?)
                               :action (open-modal-action vm :borrow token)
                               :data-role (str "earn-position-borrow-" token)})
               (action-button {:label "Repay" :primary? true :disabled? read-only?
                               :action (open-modal-action vm :repay token)
                               :data-role (str "earn-position-repay-" token)}))
              (list
               (action-button {:label (if (= :collateral kind) "Add" "Supply more") :disabled? read-only?
                               :action (open-modal-action vm :supply token)
                               :data-role (str "earn-position-supply-" token)})
               (action-button {:label "Withdraw" :disabled? read-only?
                               :action (open-modal-action vm :withdraw token)
                               :data-role (str "earn-position-withdraw-" token)})))]]])]]]))

(defn- history-table
  [{:keys [history]}]
  (if (empty? history)
    [:section {:class (into card-classes ["p-8" "text-center" "text-sm" "text-ho-text-secondary"])
               :data-role "earn-history-empty"}
     "No interest in the last 30 days. Supplies, borrows and repays also appear under Account Activity › Earn."]
    [:section {:aria-label "Interest history" :class (into card-classes ["relative" "overflow-x-auto"])
               :data-role "earn-history"}
     [:table {:class ["w-full" "min-w-[560px]" "border-collapse"]}
      [:thead
       [:tr
        [:th {:class (into th-base ["text-left" "pl-4"])} "Time"]
        [:th {:class (into th-base ["text-left"])} "Asset"]
        [:th {:class th-classes} "Interest earned"]
        [:th {:class (into th-classes ["pr-4"])} "Interest paid"]]]
      [:tbody
       (for [{:keys [key time token supply borrow]} history]
         ^{:key key}
         [:tr
          [:td {:class (into td-base ["text-left" "pl-4" "text-ho-text-secondary"])} time]
          [:td {:class (into td-base ["text-left" "font-semibold"])} token]
          [:td {:class (into td-classes ["num" "text-ho-buy"])} supply]
          [:td {:class (into td-classes ["num" "text-ho-sell" "pr-4"])} borrow]])]]]))

(defn- tabs
  [{:keys [tab]}]
  [:div {:role "tablist" :aria-label "Earn sections"
         :class ["flex" "gap-6" "border-b" "border-ho-border"]}
   (for [[id label] [[:markets "Markets"] [:positions "Your positions"] [:history "History"]]]
     ^{:key (name id)}
     [:button {:type "button"
               :role "tab"
               :aria-selected (str (= id tab))
               :class (into ["py-3" "px-0.5" "text-sm" "font-medium" "border-b-2" "-mb-px"]
                            (if (= id tab)
                              ["text-ho-text-hi" "border-ho-accent"]
                              ["text-ho-text-secondary" "border-transparent" "hover:text-ho-text"]))
               :data-role (str "earn-tab-" (name id))
               :on {:click [[:actions/set-borrow-lend-tab id]]}}
      label])])

(defn- health-card
  [{:keys [summary]}]
  (let [{:keys [limit-used limit-used-label borrowed-usd borrow-limit-usd available-to-borrow-usd
                borrow-stops-px liquidation-px primary-collateral-symbol primary-collateral-px
                health-status has-debt?]} summary
        bar-class (case health-status :risk "bg-ho-sell" :caution "bg-ho-warn" "bg-ho-buy")]
    [:section {:aria-labelledby "earn-health-heading"
               :class (into card-classes ["p-4" "flex" "flex-col" "gap-3.5"])
               :data-role "earn-health"}
     [:h2 {:id "earn-health-heading" :class ["m-0" "text-sm" "font-semibold"]} "Borrow health"]
     [:div {:class ["flex" "flex-col" "gap-2"]}
      [:div {:class ["flex" "justify-between" "text-xs" "text-ho-text-secondary"]}
       [:span "Borrow limit used"] [:span {:class ["num" "text-ho-text"]} limit-used-label]]
      [:div {:class ["relative" "h-2" "rounded-full" "bg-ho-surface-raised"]}
       [:div {:class ["absolute" "inset-y-0" "left-0" "rounded-full" bar-class]
              :style {:width (str (* 100 (min 1 (or limit-used 0))) "%")}}]]
      [:div {:class ["flex" "justify-between" "text-xs" "text-ho-text-dim"]}
       [:span borrowed-usd " borrowed"] [:span borrow-limit-usd " limit"]]]
     [:dl {:class ["m-0" "grid" "grid-cols-[1fr_auto]" "gap-x-3" "gap-y-2" "text-xs"]}
      [:dt {:class ["text-ho-text-secondary"]} "Can borrow more"]
      [:dd {:class ["m-0" "num"]} available-to-borrow-usd]
      (when primary-collateral-symbol
        (list
         [:dt {:class ["text-ho-text-secondary"]} (str "Borrowing stops if " primary-collateral-symbol " ≤")]
         [:dd {:class ["m-0" "num"]} (if has-debt? borrow-stops-px "--")]
         [:dt {:class ["text-ho-text-secondary"]} (str "Partial liquidation if " primary-collateral-symbol " ≤")]
         [:dd {:class ["m-0" "num" "text-ho-sell"]} (if has-debt? liquidation-px "--")]
         [:dt {:class ["text-ho-text-secondary"]} (str primary-collateral-symbol " oracle")]
         [:dd {:class ["m-0" "num"]} primary-collateral-px]))]
     [:p {:class ["m-0" "text-xs" "leading-5" "text-ho-text-dim"]}
      "Past the liquidation point, a backstop liquidator takes over the position in 20% slices."]]))

(defn- rate-curve-card
  [{:keys [curve]}]
  (when-let [{:keys [symbol utilization-label borrow-apy supply-apy borrow-points supply-points
                     borrow-marker supply-marker kink-x]} curve]
    [:section {:aria-labelledby "earn-curve-heading"
               :class (into card-classes ["p-4" "flex" "flex-col" "gap-2.5"])
               :data-role "earn-rate-curve"}
     [:div {:class ["flex" "justify-between" "items-baseline"]}
      [:h2 {:id "earn-curve-heading" :class ["m-0" "text-sm" "font-semibold"]} (str symbol " rate curve")]
      [:span {:class ["num" "text-xs" "text-ho-text-secondary"]} (str "u = " utilization-label)]]
     [:svg {:viewBox "0 0 300 160" :width "100%" :role "img"
            :aria-label (str symbol " borrow and supply APY by utilization. Borrow APY is 5% up to 80% utilization, then rises steeply. Now: borrow " borrow-apy ", supply " supply-apy ".")}
      [:line {:x1 32 :y1 132 :x2 292 :y2 132 :stroke "rgb(var(--ho-border))"}]
      [:line {:x1 32 :y1 72 :x2 292 :y2 72 :stroke "rgb(var(--ho-border))" :stroke-dasharray "2 4"}]
      [:line {:x1 32 :y1 12 :x2 292 :y2 12 :stroke "rgb(var(--ho-border))" :stroke-dasharray "2 4"}]
      [:text {:x 26 :y 135 :fill "rgb(var(--ho-text-dim))" :font-size 9 :text-anchor "end"} "0%"]
      [:text {:x 26 :y 75 :fill "rgb(var(--ho-text-dim))" :font-size 9 :text-anchor "end"} "50%"]
      [:text {:x 26 :y 15 :fill "rgb(var(--ho-text-dim))" :font-size 9 :text-anchor "end"} "100%"]
      [:line {:x1 kink-x :y1 12 :x2 kink-x :y2 132 :stroke "rgb(var(--ho-text-dim))" :stroke-dasharray "3 3"}]
      [:text {:x kink-x :y 148 :fill "rgb(var(--ho-text-secondary))" :font-size 9 :text-anchor "middle"} "80% kink"]
      [:polyline {:points borrow-points :fill "none" :stroke "rgb(var(--ho-sell))" :stroke-width 2 :stroke-linejoin "round"}]
      [:polyline {:points supply-points :fill "none" :stroke "rgb(var(--ho-buy))" :stroke-width 2 :stroke-linejoin "round"}]
      [:circle {:cx (first borrow-marker) :cy (second borrow-marker) :r 4
                :fill "rgb(var(--ho-bg))" :stroke "rgb(var(--ho-sell))" :stroke-width 2}]
      [:circle {:cx (first supply-marker) :cy (second supply-marker) :r 4
                :fill "rgb(var(--ho-bg))" :stroke "rgb(var(--ho-buy))" :stroke-width 2}]]
     [:div {:class ["flex" "gap-4" "text-xs" "text-ho-text-secondary"]}
      [:span {:class ["inline-flex" "items-center" "gap-1.5"]}
       [:span {:aria-hidden "true" :class ["w-3" "h-0.5" "bg-ho-sell"]}] (str "Borrow " borrow-apy)]
      [:span {:class ["inline-flex" "items-center" "gap-1.5"]}
       [:span {:aria-hidden "true" :class ["w-3" "h-0.5" "bg-ho-buy"]}] (str "Supply " supply-apy)]]
     [:p {:class ["m-0" "text-xs" "leading-5" "text-ho-text-dim"]}
      "Above 80% utilization, each extra point of utilization adds 4.75 points of borrow APY. New supply pulls the rate back down."]]))

(defn- header
  [{:keys [mode-label]}]
  [:div {:class ["flex" "flex-wrap" "justify-between" "items-end" "gap-4"]}
   [:div {:class ["flex" "flex-col" "gap-1.5"]}
    [:h1 {:class ["m-0" "text-2xl" "font-semibold" "text-ho-text-hi"]} "Earn"]
    [:p {:class ["m-0" "text-sm" "leading-6" "text-ho-text-secondary" "max-w-[680px]"]}
     "Supply stablecoins to the HyperCore lending pool and earn a rate set by utilization. Or borrow them against HYPE and UBTC."]]
   [:span {:class ["text-xs" "text-ho-text-secondary" "border" "border-ho-border" "rounded-md" "px-2.5" "py-1.5"]
           :data-role "earn-account-mode"}
    mode-label]])

(defn- notices
  [{:keys [portfolio-margin? error loading?]}]
  (list
   (when portfolio-margin?
     [:div {:role "status"
            :class ["rounded-lg" "border" "border-ho-border-accent" "bg-ho-accent-soft" "px-4" "py-3" "text-sm" "leading-6"]
            :data-role "earn-pm-notice"}
      [:span {:class ["font-semibold"]} "Portfolio margin account. "]
      "Borrowing happens automatically against your spot and perp positions, so manual borrow is off. Idle USDC and USDT0 here already earn the supply rate."])
   (when error
     [:div {:role "alert"
            :class ["rounded-lg" "border" "border-ho-border-sell" "bg-ho-sell-soft-deep" "px-3" "py-2" "text-sm" "text-ho-sell-tint"]
            :data-role "earn-error"}
      (str "Couldn't load lending pools: " error)])
   (when loading?
     [:div {:class ["text-sm" "text-ho-text-secondary"] :data-role "earn-loading"} "Loading lending pools…"])))

(defn earn-view
  ([state]
   (earn-view state (earn-vm/earn-vm state)))
  ([_state {:keys [tab modal] :as vm}]
   [:div {:class ["flex" "flex-1" "w-full" "flex-col" "app-shell-gutter" "pt-3"]
          :data-parity-id "earn-root"}
    [:div {:class ["w-full" "pb-16"]}
     [:div {:class ["max-w-[1360px]" "mx-auto" "flex" "flex-col" "gap-6" "py-4"]}
      (header vm)
      (notices vm)
      (summary-cards vm)
      [:div {:class ["flex" "flex-wrap" "gap-5" "items-start"]}
       [:div {:class ["flex-[999_1_720px]" "min-w-0" "flex" "flex-col" "gap-5"]}
        (tabs vm)
        (case tab
          :positions (positions-table vm)
          :history (history-table vm)
          [:div {:class ["flex" "flex-col" "gap-5"]}
           (pools-table vm)
           (collateral-table vm)])]
       [:aside {:class ["flex-[1_1_320px]" "min-w-0" "flex" "flex-col" "gap-4"]}
        (health-card vm)
        (rate-curve-card vm)]]]]
    (when modal
      (earn-modal/earn-modal modal))]))

(defn ^:export route-view
  [state]
  (earn-view state))

(goog/exportSymbol "hyperopen.views.earn_view.route_view" route-view)
