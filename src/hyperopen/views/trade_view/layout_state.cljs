(ns hyperopen.views.trade-view.layout-state
  (:require [hyperopen.trade.layout-actions :as trade-layout-actions]))

(def ^:private desktop-breakpoint-px
  1024)

(def ^:private desktop-account-panel-height
  "clamp(21rem, 38vh, 29rem)")

(def ^:private desktop-chart-row-min-height
  "27.75rem")

(defn desktop-layout?
  [viewport-width]
  (>= (if (number? viewport-width)
        viewport-width
        desktop-breakpoint-px)
      desktop-breakpoint-px))

(defn desktop-trade-grid-style
  [desktop-layout?]
  (when desktop-layout?
    {:grid-template-rows (str "minmax(" desktop-chart-row-min-height ", 1fr) "
                              desktop-account-panel-height)}))

(defn normalize-mobile-surface
  [state]
  (trade-layout-actions/normalize-trade-mobile-surface
   (get-in state [:trade-ui :mobile-surface])))

(defn- mobile-active-asset-strip-classes
  [funding-tooltip-open? mobile-account-surface?]
  (into ["lg:hidden" "border-b" "border-base-300" "bg-base-200"]
        (concat
         (when funding-tooltip-open?
           ["relative" "z-[200]" "overflow-visible"])
         (when mobile-account-surface?
           ["hidden"]))))

(defn- mobile-surface-tabs-classes
  [mobile-account-surface?]
  (into ["lg:hidden" "border-b" "border-base-300" "bg-base-200/70" "px-3"]
        (when mobile-account-surface?
          ["hidden"])))

(defn- desktop-active-asset-shell-classes
  [funding-tooltip-open?]
  (into ["hidden" "lg:block"]
        (when funding-tooltip-open?
          ["relative" "z-[160]" "overflow-visible"])))

(defn- chart-panel-classes
  [mobile-surface funding-tooltip-open?]
  (into [(if (= mobile-surface :chart) "flex" "hidden")
         "bg-base-100"
         "flex-col"
         "min-h-0"
         "min-w-0"]
        (concat
         (if funding-tooltip-open?
           ["relative" "z-[160]" "overflow-visible"]
           ["overflow-hidden"])
         ["lg:flex"
          "lg:row-start-1"
          "lg:col-start-1"
          "lg:border-r"
          "lg:border-base-300"])))

(defn- orderbook-panel-classes
  [mobile-orderbook-surface?]
  (into [(if mobile-orderbook-surface? "block" "hidden")
         "bg-base-100"
         "w-full"
         "h-[320px]"
         "min-h-[320px]"
         "overflow-hidden"]
        ["sm:h-[360px]"
         "sm:min-h-[360px]"
         "lg:block"
         "lg:h-full"
         "lg:min-h-0"
         "lg:col-start-2"
         "lg:row-start-2"
         "lg:border-l"
         "lg:border-t"
         "lg:border-base-300"
         "xl:col-start-2"
         "xl:row-start-1"
         "xl:border-t-0"]))

(defn- order-entry-panel-classes
  [mobile-surface]
  (into [(if (= mobile-surface :ticket) "flex" "hidden")
         "bg-base-100"
         "overflow-visible"
         "flex-col"
         "min-h-0"]
        ["lg:flex"
         "lg:col-start-2"
         "lg:row-start-1"
         "lg:border-l"
         "lg:border-base-300"
         "xl:col-start-3"
         "xl:row-span-2"]))

(defn- account-panel-classes
  [mobile-surface mobile-market-surface?]
  (into [(if (= mobile-surface :account)
           "hidden"
           (if mobile-market-surface? "flex" "hidden"))
         "bg-base-100"
         "border-t"
         "border-base-300"
         "flex-col"
         "min-h-0"
         "overflow-hidden"]
        ["lg:flex"
         "lg:col-start-1"
         "lg:row-start-2"
         "lg:h-full"
         "xl:col-start-1"
         "xl:col-span-2"]))

(defn- mobile-account-summary-classes
  []
  ["absolute"
   "inset-0"
   "z-20"
   "bg-base-100"
   "border-t"
   "border-base-300"
   "flex"
   "flex-col"
   "min-h-0"
   "lg:hidden"])

(defn trade-layout
  [desktop-layout? mobile-surface funding-tooltip-open?]
  (let [mobile-market-surface? (contains? trade-layout-actions/market-mobile-surfaces
                                          mobile-surface)
        mobile-account-surface? (= mobile-surface :account)
        mobile-orderbook-surface? (contains? #{:orderbook :trades} mobile-surface)]
    {:desktop-layout? desktop-layout?
     :mobile-market-surface? mobile-market-surface?
     :mobile-account-surface? mobile-account-surface?
     :mobile-orderbook-surface? mobile-orderbook-surface?
     :chart-panel-visible? (or desktop-layout? (= mobile-surface :chart))
     :orderbook-panel-visible? (or desktop-layout? mobile-orderbook-surface?)
     :order-entry-panel-visible? (or desktop-layout? (= mobile-surface :ticket))
     :account-panel-visible? (or desktop-layout? mobile-market-surface?)
     :mobile-account-summary-visible? (and (not desktop-layout?)
                                           mobile-account-surface?)
     :show-mobile-active-asset? (and (not desktop-layout?)
                                     (not mobile-account-surface?))
     :show-equity-surface? (or desktop-layout?
                               mobile-account-surface?)
     :grid-style (desktop-trade-grid-style desktop-layout?)
     :mobile-active-asset-strip-classes (mobile-active-asset-strip-classes funding-tooltip-open?
                                                                    mobile-account-surface?)
     :mobile-surface-tabs-classes (mobile-surface-tabs-classes mobile-account-surface?)
     :desktop-active-asset-shell-classes (desktop-active-asset-shell-classes funding-tooltip-open?)
     :chart-panel-classes (chart-panel-classes mobile-surface funding-tooltip-open?)
     :orderbook-panel-classes (orderbook-panel-classes mobile-orderbook-surface?)
     :order-entry-panel-classes (order-entry-panel-classes mobile-surface)
     :account-panel-classes (account-panel-classes mobile-surface mobile-market-surface?)
     :mobile-account-summary-classes (mobile-account-summary-classes)}))

(defn trade-layout-state
  [state viewport-width funding-tooltip-open?]
  (let [desktop-layout* (desktop-layout? viewport-width)
        mobile-surface (normalize-mobile-surface state)]
    (assoc (trade-layout desktop-layout* mobile-surface funding-tooltip-open?)
           :mobile-surface mobile-surface)))
