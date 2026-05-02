(ns hyperopen.views.trade-view.shell
  (:require [hyperopen.views.ui.funding-modal-positioning :as funding-modal-positioning]))

(def trade-mobile-surfaces
  [[:chart "Chart"]
   [:orderbook "Order Book"]
   [:trades "Trades"]])

(defn- mobile-surface-button
  [selected-surface [surface-id label]]
  [:button {:type "button"
            :data-role (str "trade-mobile-surface-button-" (name surface-id))
            :class (into ["flex-1"
                          "border-b-2"
                          "px-2"
                          "py-2"
                          "text-sm"
                          "font-medium"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                         (if (= selected-surface surface-id)
                           ["border-primary" "text-trading-text"]
                           ["border-transparent" "text-trading-text-secondary" "hover:text-trading-text"]))
            :on {:click [[:actions/select-trade-mobile-surface surface-id]]}}
   label])

(defn desktop-secondary-panel-placeholder
  [title data-role & {:keys [fill-height?]
                      :or {fill-height? false}}]
  [:div {:class (into ["w-full"
                       "min-h-0"
                       "overflow-hidden"
                       "bg-base-100"
                       "p-3"
                       "space-y-3"]
                      (if fill-height?
                        ["h-full"]
                        ["min-h-[9rem]"]))
         :data-role data-role}
   [:div {:class ["text-sm" "font-semibold" "text-trading-text-secondary"]}
    title]
   [:div {:class ["space-y-2"]}
    [:div {:class ["h-3" "w-28" "rounded" "bg-base-300/60"]}]
    [:div {:class ["h-3" "w-full" "rounded" "bg-base-300/50"]}]
    [:div {:class ["h-3" "w-5/6" "rounded" "bg-base-300/40"]}]
    [:div {:class ["h-3" "w-2/3" "rounded" "bg-base-300/30"]}]]])

(defn render-mobile-active-asset-strip
  [state {:keys [layout]} {:keys [render-active-asset-panel]}]
  [:div {:class (:mobile-active-asset-strip-classes layout)
         :data-parity-id "trade-mobile-active-asset-strip"}
   (when (:show-mobile-active-asset? layout)
     (render-active-asset-panel state))])

(defn render-mobile-surface-tabs
  [mobile-surface {:keys [layout]}]
  [:div {:class (:mobile-surface-tabs-classes layout)
         :data-parity-id "trade-mobile-surface-tabs"}
   [:div {:class ["flex" "items-center" "gap-0"]}
    (for [[surface-id _label :as surface] trade-mobile-surfaces]
      ^{:key (str "trade-mobile-surface-" (name surface-id))}
      (mobile-surface-button mobile-surface surface))]])

(defn render-trade-chart-panel
  [desktop-layout?
   {:keys [layout]}
   {:keys [active-asset-panel-state trade-chart-panel-state]}
   {:keys [render-active-asset-panel-state trade-chart-panel-content-state]}]
  [:div {:class (:chart-panel-classes layout)
         :data-parity-id "trade-chart-panel"}
   [:div {:class (:desktop-active-asset-shell-classes layout)}
    (when desktop-layout?
      (render-active-asset-panel-state active-asset-panel-state))]
   (when (:chart-panel-visible? layout)
     [:div {:class ["overflow-hidden" "flex-1" "min-h-0" "min-w-0"]}
      (trade-chart-panel-content-state trade-chart-panel-state)])])

(defn render-orderbook-panel-shell
  [desktop-layout?
   {:keys [layout]}
   {:keys [orderbook-panel-state mobile-orderbook-panel-state]}
   {:keys [render-orderbook-panel]}]
  [:div {:class (:orderbook-panel-classes layout)
         :data-parity-id "trade-orderbook-panel"}
   [:div {:class ["h-full" "min-h-0" "lg:hidden"]}
    (when (and (:orderbook-panel-visible? layout)
               (not desktop-layout?))
      (render-orderbook-panel mobile-orderbook-panel-state))]
   [:div {:class ["hidden" "h-full" "min-h-0" "lg:block"]}
    (when (and (:orderbook-panel-visible? layout)
               desktop-layout?)
      (render-orderbook-panel orderbook-panel-state))]])

(defn render-order-entry-panel-shell
  [desktop-layout?
   {:keys [layout]}
   {:keys [account-equity-panel-state
           desktop-secondary-panels-ready?
           equity-metrics
           order-form-panel-state]}
   {:keys [render-account-equity-panel-state render-order-form-panel]}]
  [:div {:class (:order-entry-panel-classes layout)
         :data-parity-id funding-modal-positioning/trade-order-entry-panel-parity-id}
   (when (:order-entry-panel-visible? layout)
     (render-order-form-panel order-form-panel-state))
   [:div {:class ["hidden" "border-t" "border-base-300" "lg:block"]
          :data-parity-id "trade-desktop-account-equity-panel"}
    (when (and desktop-layout?
               (:order-entry-panel-visible? layout))
      (if desktop-secondary-panels-ready?
        (or (render-account-equity-panel-state account-equity-panel-state equity-metrics {})
            (desktop-secondary-panel-placeholder "Account Equity"
                                                 "trade-desktop-account-equity-placeholder"))
        (desktop-secondary-panel-placeholder "Account Equity"
                                             "trade-desktop-account-equity-placeholder")))]])

(defn render-account-panel-shell
  [state
   desktop-layout?
   {:keys [layout]}
   {:keys [account-info-panel-state desktop-secondary-panels-ready?]}
   {:keys [render-account-info-panel render-account-info-panel-state]}]
  [:div {:class (:account-panel-classes layout)
         :data-parity-id "trade-account-tables-panel"}
   [:div {:class ["w-full" "lg:hidden"]
          :data-parity-id "trade-mobile-account-panel"}
    (when (and (:account-panel-visible? layout)
               (not desktop-layout?))
      (or (render-account-info-panel state)
          (desktop-secondary-panel-placeholder "Account"
                                               "trade-mobile-account-panel-placeholder"
                                               :fill-height? true)))]
   [:div {:class ["hidden" "w-full" "min-h-0" "lg:flex"]
          :data-parity-id "trade-desktop-account-panel"}
    (when (and (:account-panel-visible? layout)
               desktop-layout?)
      (if desktop-secondary-panels-ready?
        (or (render-account-info-panel-state account-info-panel-state
                                             {:default-panel-classes ["h-full"]})
            (desktop-secondary-panel-placeholder "Account"
                                                 "trade-desktop-account-panel-placeholder"
                                                 :fill-height? true))
        (desktop-secondary-panel-placeholder "Account"
                                             "trade-desktop-account-panel-placeholder"
                                             :fill-height? true)))]])

(defn render-mobile-account-summary
  [state {:keys [layout]} {:keys [equity-metrics]} {:keys [mobile-account-surface]}]
  (when (:mobile-account-summary-visible? layout)
    [:div {:class (:mobile-account-summary-classes layout)
           :data-parity-id "trade-mobile-account-summary-panel"}
     (mobile-account-surface state equity-metrics)]))

(defn render-trade-grid
  [state
   {:keys [desktop-layout? layout] :as layout-context}
   panel-context
   renderers]
  [:div {:class ["relative" "flex-1" "min-h-0"]}
   [:div {:class ["hidden" "xl:block" "absolute" "top-0" "bottom-0" "right-[320px]" "w-px" "bg-base-300" "pointer-events-none" "z-10"]}]
   [:div {:class ["grid"
                  "h-full"
                  "min-h-0"
                  "grid-cols-1"
                  "gap-x-0" "gap-y-0"
                  "bg-base-100"
                  "items-stretch"
                  "lg:h-full"
                  "lg:grid-cols-[minmax(0,1fr)_320px]"
                  "xl:grid-cols-[minmax(0,1fr)_280px_320px]"]
          :style (:grid-style layout)}
    (render-trade-chart-panel desktop-layout? layout-context panel-context renderers)
    (render-orderbook-panel-shell desktop-layout? layout-context panel-context renderers)
    (render-order-entry-panel-shell desktop-layout? layout-context panel-context renderers)
    (render-account-panel-shell state desktop-layout? layout-context panel-context renderers)]
   (render-mobile-account-summary state layout-context panel-context renderers)])
