(ns hyperopen.views.account-info.tabs.positions.mobile
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.views.account-info.mobile-cards :as mobile-cards]
            [hyperopen.views.account-info.position-margin-modal :as position-margin-modal]
            [hyperopen.views.account-info.position-reduce-popover :as position-reduce-popover]
            [hyperopen.views.account-info.position-tpsl-modal :as position-tpsl-modal]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tabs.positions.layout :as positions-layout]
            [hyperopen.views.account-info.tabs.positions.shared :as positions-shared]))

(defn- position-value-copy
  [position-value-num]
  (if (number? position-value-num)
    (str (shared/format-currency position-value-num) " USDC")
    "--"))

(defn- funding-value-node
  [display-funding display-funding-text]
  [:span {:class [(cond
                    (and (number? display-funding) (neg? display-funding)) "text-error"
                    (and (number? display-funding) (pos? display-funding)) "text-success"
                    :else "text-trading-text")
                  "num"]}
   (if (number? display-funding)
     display-funding-text
     "--")])

(defn- mobile-position-coin-node
  [position-data side]
  (let [pos (:position position-data)
        chip-classes (shared/position-chip-classes-for-side side)
        coin-label (positions-shared/display-coin pos)
        dex-label (positions-shared/dex-chip-label {:coin (:coin pos)
                                                    :dex (:dex position-data)})
        leverage (get-in pos [:leverage :value])]
    [:span {:class ["flex" "min-w-0" "items-center" "gap-1"]}
     [:span {:class ["truncate" "font-medium" "leading-4" "text-trading-text"]} coin-label]
     (when (some? leverage)
       [:span {:class chip-classes} (str leverage "x")])
     (when dex-label
       [:span {:class chip-classes} dex-label])]))

(defn- mobile-position-action-button
  [label action]
  [:button {:type "button"
            :class ["inline-flex"
                    "items-center"
                    "justify-start"
                    "bg-transparent"
                    "p-0"
                    "text-sm"
                    "font-medium"
                    "leading-none"
                    "text-trading-green"
                    "transition-colors"
                    "hover:text-[#7fffe4]"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus-visible:text-[#7fffe4]"
                    "whitespace-nowrap"]
            :on {:click action}}
   label])

(defn- editable-mobile-detail-value
  [content edit-button]
  [:div {:class ["inline-flex" "max-w-full" "items-start" "gap-0.5" "align-top"]}
   [:div {:class ["min-w-0" "leading-5"]}
    content]
   edit-button])

(defn- position-overlay-trigger
  [action-id position-data]
  [[action-id
    position-data
    (if (positions-layout/phone-overlay-trigger?)
      (positions-layout/mobile-position-overlay-anchor)
      :event.currentTarget/bounds)]])

(defn- mobile-position-margin-value-node
  [margin margin-mode-label]
  [:div {:class ["inline-grid"
                 "grid-cols-[max-content_auto]"
                 "items-start"
                 "gap-x-0.5"
                 "gap-y-0.5"]}
   [:span {:class ["num" "font-medium" "text-trading-text" "whitespace-nowrap"]}
    (str "$" (shared/format-currency margin))]
   (when margin-mode-label
     [:span {:class ["col-span-full"
                     "text-xs"
                     "font-medium"
                     "leading-4"
                     "text-trading-text-secondary"
                     "whitespace-nowrap"]}
      (str "(" margin-mode-label ")")])])

(defn- editable-mobile-margin-value
  [margin margin-mode-label edit-button]
  [:div {:class ["inline-grid"
                 "grid-cols-[max-content_auto]"
                 "items-start"
                 "gap-x-0.5"
                 "gap-y-0.5"
                 "align-top"]}
   (mobile-position-margin-value-node margin margin-mode-label)
   edit-button])

(def ^:private mobile-position-card-summary-grid-classes
  ["grid"
   "grid-cols-[minmax(0,1.75fr)_minmax(0,0.95fr)_minmax(0,1.05fr)_auto]"
   "items-start"
   "gap-x-2.5"
   "gap-y-2"])

(defn mobile-position-card-from-vm
  [expanded-row-id row-vm tpsl-modal reduce-popover margin-modal read-only?]
  (let [position-data (:row-data row-vm)
        pos (:position row-vm)
        side (:side row-vm)
        row-id (some-> (:row-key row-vm) str str/trim)
        expanded? (= expanded-row-id row-id)
        position-value-num (:position-value-num row-vm)
        pnl-num (:pnl-num row-vm)
        pnl-percent (:pnl-percent row-vm)
        pnl-color-class (:pnl-color-class row-vm)
        margin-editable? (:margin-editable? row-vm)
        margin-mode-label (:margin-mode-label row-vm)
        display-funding (:funding-display row-vm)
        display-funding-text (:funding-display-text row-vm)]
    (mobile-cards/expandable-card
     {:data-role (str "mobile-position-card-" row-id)
      :expanded? expanded?
      :toggle-actions [[:actions/toggle-account-info-mobile-card :positions row-id]]
      :summary-grid-classes mobile-position-card-summary-grid-classes
      :summary-items [(mobile-cards/summary-item "Coin"
                                                 (mobile-position-coin-node position-data side)
                                                 {:root-classes ["pr-1"]
                                                  :value-classes ["font-medium" "leading-4"]})
                      (mobile-cards/summary-item "Size"
                                                 (:size-display row-vm)
                                                 {:value-classes ["num" "font-medium" "leading-4" "whitespace-nowrap"]})
                      (mobile-cards/summary-item "PNL (ROE %)"
                                                 [:span {:class ["num" pnl-color-class]}
                                                  (positions-shared/format-pnl-inline pnl-num pnl-percent)]
                                                 {:value-classes ["font-medium" "leading-4" "whitespace-nowrap"]})]
      :detail-content
      [:div {:class ["space-y-3"]}
       (mobile-cards/detail-grid
        "grid-cols-3"
        [(mobile-cards/detail-item "Entry Price"
                                   (shared/format-trade-price (:entry-price row-vm))
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Mark Price"
                                   (:mark-price-display row-vm)
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Liq. Price"
                                   (positions-shared/format-liquidation-price (:liq-price row-vm))
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Position Value"
                                   (position-value-copy position-value-num)
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Margin"
                                   (if (and margin-editable?
                                            (not read-only?))
                                     (editable-mobile-margin-value
                                      (:margin row-vm)
                                      margin-mode-label
                                      (positions-shared/mobile-position-detail-edit-button
                                       "Edit Margin"
                                       (position-overlay-trigger
                                        :actions/open-position-margin-modal
                                        position-data)
                                       :data-position-margin-trigger))
                                     (mobile-position-margin-value-node
                                      (:margin row-vm)
                                      margin-mode-label))
                                   {:value-classes ["font-medium"]})
         (mobile-cards/detail-item "TP/SL"
                                   (if read-only?
                                     [:span {:class ["font-medium" "text-trading-text" "whitespace-nowrap"]}
                                      (:tpsl-copy row-vm)]
                                     (editable-mobile-detail-value
                                      [:span {:class ["font-medium" "text-trading-text" "whitespace-nowrap"]}
                                       (:tpsl-copy row-vm)]
                                      (positions-shared/mobile-position-detail-edit-button
                                       "Edit TP/SL"
                                       (position-overlay-trigger
                                        :actions/open-position-tpsl-modal
                                        position-data)
                                       :data-position-tpsl-trigger)))
                                   {:value-classes ["font-medium"]})
         (mobile-cards/detail-item "Funding"
                                   (funding-value-node display-funding display-funding-text)
                                   {:value-classes ["font-medium" "whitespace-nowrap"]})])
       (when-not read-only?
         [:div {:class ["border-t" "border-[#17313d]" "pt-2.5"]}
          [:div {:class ["relative" "flex" "flex-wrap" "items-center" "gap-x-5" "gap-y-2"]}
           (mobile-position-action-button
            "Close"
            (position-overlay-trigger
             :actions/open-position-reduce-popover
             position-data))
           (when margin-editable?
             (mobile-position-action-button
              "Margin"
              (position-overlay-trigger
               :actions/open-position-margin-modal
               position-data)))
           (mobile-position-action-button
            "TP/SL"
            (position-overlay-trigger
             :actions/open-position-tpsl-modal
             position-data))]])]})))

(defn- active-position-key-visible?
  [visible-row-keys overlay]
  (contains? visible-row-keys (:position-key overlay)))

(defn- ensure-active-layout-anchor
  [overlay]
  (if (and (map? overlay)
           (positions-layout/active-card-layout?)
           (not (map? (:anchor overlay))))
    (assoc overlay :anchor (positions-layout/mobile-position-overlay-anchor))
    overlay))

(defn mobile-position-overlay-outlet
  [visible-row-keys tpsl-modal reduce-popover margin-modal read-only?]
  (when (and (positions-layout/active-card-layout?)
             (not read-only?))
    (let [margin-modal* (ensure-active-layout-anchor margin-modal)
          reduce-popover* (ensure-active-layout-anchor reduce-popover)
          tpsl-modal* (ensure-active-layout-anchor tpsl-modal)]
      (cond
        (and (position-margin/open? margin-modal*)
             (active-position-key-visible? visible-row-keys margin-modal*))
        (position-margin-modal/position-margin-modal-view margin-modal*)

        (and (position-reduce/open? reduce-popover*)
             (active-position-key-visible? visible-row-keys reduce-popover*))
        (position-reduce-popover/position-reduce-popover-view reduce-popover*)

        (and (position-tpsl/open? tpsl-modal*)
             (active-position-key-visible? visible-row-keys tpsl-modal*))
        (position-tpsl-modal/position-tpsl-modal-view tpsl-modal*)

        :else
        nil))))
