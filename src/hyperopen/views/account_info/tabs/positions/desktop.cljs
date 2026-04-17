(ns hyperopen.views.account-info.tabs.positions.desktop
  (:require [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.router :as router]
            [hyperopen.views.account-info.position-margin-modal :as position-margin-modal]
            [hyperopen.views.account-info.position-reduce-popover :as position-reduce-popover]
            [hyperopen.views.account-info.position-tpsl-modal :as position-tpsl-modal]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]
            [hyperopen.views.account-info.tabs.positions.layout :as positions-layout]
            [hyperopen.views.account-info.tabs.positions.shared :as positions-shared]))

(defn- position-coin-click-actions
  [coin positions-state]
  (when-let [coin* (shared/non-blank-text coin)]
    (if (true? (:navigate-to-trade-on-coin-click? positions-state))
      [[:actions/select-asset coin*]
       [:actions/navigate (router/trade-route-path coin*)]]
      [[:actions/select-asset coin*]])))

(defn position-row-from-vm
  [row-vm tpsl-modal reduce-popover margin-modal read-only? positions-state]
  (let [position-data (:row-data row-vm)
        pos (:position row-vm)
        side (:side row-vm)
        chip-classes (shared/position-chip-classes-for-side side)
        coin-cell-style (shared/position-coin-cell-style-for-side side)
        coin-tone-class (shared/position-side-tone-class side)
        size-tone-class (shared/position-side-size-class side)
        coin-label (:coin-label row-vm)
        dex-label (:dex-label row-vm)
        leverage (get-in pos [:leverage :value])
        position-value-num (:position-value-num row-vm)
        margin (:margin row-vm)
        margin-editable? (:margin-editable? row-vm)
        margin-mode-label (:margin-mode-label row-vm)
        display-funding (:funding-display row-vm)
        display-funding-text (:funding-display-text row-vm)
        funding-tooltip (:funding-tooltip row-vm)
        liq-explanation (:liq-explanation row-vm)
        tpsl-copy (:tpsl-copy row-vm)
        row-key (:row-key row-vm)
        coin-click-actions (position-coin-click-actions (:coin pos) positions-state)
        active-modal?
        (and (not read-only?)
             (position-tpsl/open? tpsl-modal)
             (= row-key (:position-key tpsl-modal)))
        active-reduce-popover?
        (and (not read-only?)
             (position-reduce/open? reduce-popover)
             (= row-key (:position-key reduce-popover)))
        active-margin-modal?
        (and (not read-only?)
             (position-margin/open? margin-modal)
             (= row-key (:position-key margin-modal)))]
    (into [:div {:class ["grid"
                         (positions-layout/positions-grid-template-class read-only?)
                         "gap-2"
                         "py-0"
                         "pr-3"
                         (positions-layout/positions-grid-min-width-class read-only?)
                         "hover:bg-base-300"
                         "items-center"
                         "text-sm"]}
           [:div {:class ["flex" "min-w-0" "items-center" "gap-1.5" "self-stretch"]
                  :style coin-cell-style}
            (shared/coin-select-control
             (:coin pos)
             [:span {:class ["flex" "w-full" "min-w-0" "items-center" "gap-1.5"]}
              [:span {:class ["block" "min-w-0" "truncate" "font-medium" coin-tone-class]
                      :title coin-label}
               coin-label]
              (when (some? leverage)
                [:span {:class chip-classes} (str leverage "x")])
              (when dex-label
                [:span {:class chip-classes} dex-label])]
             {:extra-classes ["w-full" "justify-start" "overflow-hidden" "text-left"]
              :click-actions coin-click-actions
              :attrs {:data-role "positions-coin-select"}})]
           [:div {:class ["min-w-0" "truncate" "text-left" "font-semibold" "num" size-tone-class]
                  :title (:size-display row-vm)}
            (:size-display row-vm)]
           [:div.text-left.font-semibold.num
            (if (number? position-value-num)
              (str (shared/format-currency position-value-num) " USDC")
              "--")]
           [:div.text-left.font-semibold.num (shared/format-trade-price (:entry-price row-vm))]
           [:div.text-left.font-semibold.num (:mark-price-display row-vm)]
           [:div {:class ["text-left" "font-semibold" "num" (:pnl-color-class row-vm)]}
            (positions-shared/format-pnl-inline (:pnl-num row-vm) (:pnl-percent row-vm))]
           [:div.text-left.font-semibold.num
            (positions-shared/explainable-value-node
             (positions-shared/format-liquidation-price (:liq-price row-vm))
             liq-explanation)]
           [:div {:class ["text-left" "relative" "min-w-0" "font-semibold" "num"]}
            [:div {:class ["inline-flex"
                           "max-w-full"
                           "min-w-0"
                           "items-center"
                           "gap-0.5"
                           "overflow-hidden"
                           "whitespace-nowrap"]}
             [:span {:class ["inline-flex" "items-baseline" "gap-1" "whitespace-nowrap" "select-text"]}
              [:span {:class ["num"]}
               (str "$" (shared/format-currency margin))]
              (when margin-mode-label
                [:span {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
                 (str "(" margin-mode-label ")")])]
             (when (and margin-editable?
                        (not read-only?))
               (positions-shared/position-detail-edit-button
                "Edit Margin"
                [[:actions/open-position-margin-modal position-data :event.currentTarget/bounds]]
                :data-position-margin-trigger))]
            (when (and active-margin-modal?
                       (positions-layout/active-desktop-table-layout?))
              (position-margin-modal/position-margin-modal-view margin-modal))]
           [:div.text-left.font-semibold.num
            (positions-shared/explainable-value-node
             [:span {:class [(:funding-tone-class row-vm) "num"]}
              (if (number? display-funding)
                display-funding-text
                "--")]
             funding-tooltip
             {:underlined? false})]]
          (concat
           (when-not read-only?
             [[:div {:class ["text-left" "relative"]}
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
                                 "hover:text-[#7fffe4]"
                                 "focus-visible:text-[#7fffe4]"
                                 "whitespace-nowrap"]
                         :type "button"
                         :data-position-reduce-trigger "true"
                         :on {:click [[:actions/open-position-reduce-popover position-data :event.currentTarget/bounds]]}}
                "Reduce"]
               (when (and active-reduce-popover?
                          (positions-layout/active-desktop-table-layout?))
                 (position-reduce-popover/position-reduce-popover-view reduce-popover))]])
           [[:div {:class ["text-left" "relative"]}
             [:div {:class ["inline-flex" "items-center" "gap-0.5" "whitespace-nowrap"]}
              [:span {:class ["font-normal" "text-trading-text" "whitespace-nowrap" "select-text"]} tpsl-copy]
              (when-not read-only?
                (positions-shared/position-detail-edit-button
                 "Edit TP/SL"
                 [[:actions/open-position-tpsl-modal position-data :event.currentTarget/bounds]]
                 :data-position-tpsl-trigger))]
             (when (and active-modal?
                        (positions-layout/active-desktop-table-layout?))
               (position-tpsl-modal/position-tpsl-modal-view tpsl-modal))]]))))

(defn position-row
  ([position-data]
   (position-row position-data nil nil nil false))
  ([position-data tpsl-modal]
   (position-row position-data tpsl-modal nil nil false))
  ([position-data tpsl-modal reduce-popover]
   (position-row position-data tpsl-modal reduce-popover nil false))
  ([position-data tpsl-modal reduce-popover margin-modal]
   (position-row position-data tpsl-modal reduce-popover margin-modal false))
  ([position-data tpsl-modal reduce-popover margin-modal read-only?]
   (position-row-from-vm (positions-vm/position-row-vm position-data)
                         tpsl-modal
                         reduce-popover
                         margin-modal
                         read-only?
                         {})))

(def ^:private pnl-header-explanation
  "Mark price is used to estimate unrealized PNL. Only trade prices are used for realized PNL.")

(def ^:private margin-header-explanation
  "For isolated positions, margin includes unrealized pnl.")

(def ^:private funding-header-explanation
  "Net funding payments since the position was opened. Hover for all-time and since changed.")

(defn sortable-header
  ([column-name sort-state]
   (sortable-header column-name sort-state nil))
  ([column-name sort-state explanation]
   (table/sortable-header-button column-name
                                 sort-state
                                 :actions/sort-positions
                                 {:explanation explanation})))

(defn position-table-header
  ([sort-state]
   (position-table-header sort-state false []))
  ([sort-state extra-classes]
   (position-table-header sort-state false extra-classes))
  ([sort-state read-only? extra-classes]
   (into [:div {:class (into ["grid"
                              (positions-layout/positions-grid-template-class read-only?)
                              "gap-2"
                              "py-1"
                              "pr-3"
                              (positions-layout/positions-grid-min-width-class read-only?)
                              "bg-base-200"]
                             extra-classes)}
          [:div.text-left.pl-3 (sortable-header "Coin" sort-state)]
          [:div.text-left (sortable-header "Size" sort-state)]
          [:div.text-left (sortable-header "Position Value" sort-state)]
          [:div.text-left (sortable-header "Entry Price" sort-state)]
          [:div.text-left (sortable-header "Mark Price" sort-state)]
          [:div.text-left (sortable-header "PNL (ROE %)" sort-state pnl-header-explanation)]
          [:div.text-left (sortable-header "Liq. Price" sort-state)]
          [:div.text-left (sortable-header "Margin" sort-state margin-header-explanation)]
          [:div.text-left (sortable-header "Funding" sort-state funding-header-explanation)]]
         (concat
          (when-not read-only?
            [[:div.text-left
              [:button {:class (into ["w-full"
                                      "text-left"
                                      "focus:outline-none"
                                      "focus:ring-1"
                                      "focus:ring-[#8a96a6]/40"
                                      "focus:ring-offset-0"
                                      "focus:shadow-none"]
                                     (concat table/header-base-text-classes
                                             table/sortable-header-interaction-classes))
                        :type "button"
                        :on {:click [[:actions/trigger-close-all-positions]]}}
               "Close All"]]])
          [[:div.text-left (table/non-sortable-header "TP/SL")]]))))
