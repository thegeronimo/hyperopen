(ns hyperopen.views.active-asset.outcome-row
  "Trade header for outcome markets: the event and its kicker, each side's
   chance, the selected side's price in cents, the change in points, the clock,
   volume and open interest."
  (:require [hyperopen.views.active-asset.icon-button :as icon-button]
            [hyperopen.views.active-asset.outcome-tooltip :as outcome-tooltip]
            [hyperopen.views.trade.order-form-controls :as order-form-controls]))

(defn option-strip-selector
  "The answer picker beside the title for events with several answers."
  [{:keys [outcome-options outcome-option-id outcome-option-ui]} outcome-handlers]
  (when (and outcome-handlers (> (count outcome-options) 1))
    [:div {:class ["relative" "min-w-[11rem]" "max-w-[18rem]" "flex-none"]
           :style (when (:open? outcome-option-ui)
                    {:z-index 260})
           :data-role "market-strip-outcome-option-selector"}
     (order-form-controls/outcome-option-row outcome-options
                                             outcome-option-id
                                             outcome-handlers
                                             (assoc outcome-option-ui
                                                    :menu-width-classes ["w-[min(42rem,calc(100vw-2rem))]"]
                                                    :menu-style
                                                    {:width "min(42rem, calc(100vw - 2rem))" :max-width "calc(100vw - 2rem)"}))]))

(defn- stat
  ([label value] (stat label value nil))
  ([label value {:keys [title value-classes role]}]
   [:div {:class ["asset-stat-cell" "flex" "min-w-0" "justify-center"]}
    [:div {:class ["min-w-0" "text-center"]
           :title title
           :data-role role}
     [:div {:class ["asset-stat-label" "mb-1" "truncate" "text-xs" "text-gray-400"]} label]
     [:div {:class (into ["truncate" "text-xs" "font-medium" "num"] (or value-classes ["text-trading-text"]))}
      value]]]))

(defn- change-classes
  [direction]
  (case direction
    :up ["text-success"]
    :down ["text-error"]
    ["text-trading-text-secondary"]))

(defn- clock-classes
  [phase]
  (case phase
    :soon ["text-ho-warn"]
    :live ["text-ho-buy"]
    :awaiting ["text-trading-text-secondary"]
    ["text-trading-text"]))

(defn- chances-text
  [chances]
  (if (= 2 (count chances))
    (let [[a b] chances]
      (str (:label a) " " (:text a) " · " (:text b) " " (:label b)))
    (let [{:keys [label text]} (first chances)]
      (str label " " text))))

(defn- kicker-line
  [{:keys [kicker venue]}]
  [:div {:class ["flex" "min-w-0" "items-center" "gap-1.5" "px-2" "text-xs" "text-trading-text-secondary"]
         :data-role "outcome-header-kicker"}
   (when (seq kicker) [:span {:class ["truncate"]} kicker])
   [:span {:class ["shrink-0" "rounded" "border" "border-base-300" "px-1" "text-ho-text-muted"]} venue]])

(defn desktop-row
  [{:keys [icon-market dropdown-visible? missing-icons loaded-icons outcome-header] :as row-vm}
   {:keys [outcome-handlers]}]
  (let [{:keys [chances price-label price-text change-label change-text change-direction
                clock-label clock-absolute clock-phase volume-text open-interest-text]} outcome-header]
    [:div {:class ["asset-strip-row" "relative" "hidden" "items-center" "gap-2" "px-0" "py-1.5" "lg:grid" "md:gap-3"]
           :style {:grid-template-columns "minmax(10rem, 1fr) repeat(6, minmax(0, max-content))"}
           :data-role "outcome-header"}
     [:div {:class ["flex" "min-w-0" "justify-start" "app-shell-gutter-left" "items-center" "gap-2"]
            :data-role "outcome-market-selector-group"}
      [:div {:class ["flex" "min-w-0" "flex-col"]}
       (kicker-line outcome-header)
       [:div {:class ["relative" "group/outcome-name" "flex" "items-center"]
              :data-role "outcome-market-name-hover-region"}
        (icon-button/asset-button icon-market dropdown-visible? missing-icons loaded-icons
                                  {:outcome-hover-glow? true})
        (outcome-tooltip/outcome-tooltip-panel (:outcome-tooltip row-vm))]]
      (option-strip-selector row-vm outcome-handlers)]
     (stat "Chance" (chances-text chances) {:role "outcome-header-chance"})
     (stat price-label price-text {:role "outcome-header-price"})
     (stat change-label change-text {:value-classes (change-classes change-direction)})
     (stat "When" clock-label {:title clock-absolute
                                      :value-classes (clock-classes clock-phase)
                                      :role "outcome-header-clock"})
     (stat "Volume 24h" volume-text)
     (stat "Open interest" open-interest-text {:title "Shares outstanding × $1.00"})]))

(defn mobile-row
  [{:keys [icon-market dropdown-visible? missing-icons loaded-icons outcome-header] :as row-vm}
   {:keys [outcome-handlers]}]
  (let [{:keys [chances price-label price-text change-label change-text change-direction clock-label
                clock-phase volume-text open-interest-text]} outcome-header]
    [:div {:class ["lg:hidden" "space-y-2" "px-3" "py-2.5"]
           :data-role "trade-mobile-asset-summary"}
     (kicker-line outcome-header)
     ;; The event name gets the full width; the chances sit on their own line.
     [:div {:class ["flex" "min-w-0" "flex-col" "gap-1.5"]}
      (icon-button/asset-button icon-market dropdown-visible? missing-icons loaded-icons)
      (option-strip-selector row-vm outcome-handlers)]
     [:div {:class ["flex" "items-baseline" "justify-between" "gap-3"]}
      [:div {:class ["num" "min-w-0" "truncate" "text-xl" "font-semibold" "leading-none" "text-trading-text"]
             :data-role "outcome-header-chance"}
       (chances-text chances)]
      [:div {:class (into ["num" "shrink-0" "text-xs"] (change-classes change-direction))}
       (str change-text " " change-label)]]
     [:div {:class ["grid" "grid-cols-2" "gap-x-4" "gap-y-1" "text-xs"]}
      [:div {:class ["flex" "justify-between" "gap-2"]}
       [:span {:class ["text-trading-text-secondary"]} price-label]
       [:span {:class ["num" "text-trading-text"]} price-text]]
      [:div {:class (into ["truncate" "text-right"] (clock-classes clock-phase))} clock-label]
      [:div {:class ["flex" "justify-between" "gap-2"]}
       [:span {:class ["text-trading-text-secondary"]} "Volume 24h"]
       [:span {:class ["num" "text-trading-text"]} volume-text]]
      [:div {:class ["flex" "justify-end" "gap-2"]}
       [:span {:class ["text-trading-text-secondary"]} "Open interest"]
       [:span {:class ["num" "text-trading-text"]} open-interest-text]]]]))
