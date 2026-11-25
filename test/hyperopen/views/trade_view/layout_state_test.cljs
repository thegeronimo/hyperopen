(ns hyperopen.views.trade-view.layout-state-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade-view.layout-state :as layout-state]))

(defn- class-set
  [classes]
  (set classes))

(deftest desktop-layout-defaults-to-desktop-breakpoint-test
  (is (true? (layout-state/desktop-layout? nil)))
  (is (false? (layout-state/desktop-layout? 430))))

(deftest trade-layout-state-mobile-account-surface-hides-market-strip-test
  (let [layout (layout-state/trade-layout-state {:trade-ui {:mobile-surface :account}}
                                                430
                                                false)]
    (is (false? (:desktop-layout? layout)))
    (is (= :account (:mobile-surface layout)))
    (is (true? (:mobile-account-surface? layout)))
    (is (false? (:show-mobile-active-asset? layout)))
    (is (false? (:chart-panel-visible? layout)))
    (is (false? (:orderbook-panel-visible? layout)))
    (is (false? (:order-entry-panel-visible? layout)))
    (is (false? (:account-panel-visible? layout)))
    (is (true? (:mobile-account-summary-visible? layout)))
    (is (true? (:show-equity-surface? layout)))
    (is (nil? (:grid-style layout)))
    (is (contains? (class-set (:mobile-active-asset-strip-classes layout)) "hidden"))
    (is (contains? (class-set (:mobile-surface-tabs-classes layout)) "hidden"))
    (is (contains? (class-set (:mobile-account-summary-classes layout)) "absolute"))))

(deftest trade-layout-state-desktop-preserves-panel-contracts-test
  (let [layout (layout-state/trade-layout-state {:trade-ui {:mobile-surface :trades}}
                                                1280
                                                true)]
    (is (true? (:desktop-layout? layout)))
    (is (= :trades (:mobile-surface layout)))
    (is (true? (:chart-panel-visible? layout)))
    (is (true? (:orderbook-panel-visible? layout)))
    (is (true? (:order-entry-panel-visible? layout)))
    (is (true? (:account-panel-visible? layout)))
    (is (false? (:mobile-account-summary-visible? layout)))
    (is (false? (:show-mobile-active-asset? layout)))
    (is (= "minmax(var(--trade-chart-row-min-height), 1fr) var(--trade-account-panel-height)"
           (:grid-template-rows (:grid-style layout))))
    (is (= "calc(var(--trade-chart-row-min-height) + var(--trade-account-panel-height))"
           (:min-height (:grid-style layout))))
    (is (contains? (class-set (:desktop-active-asset-shell-classes layout)) "overflow-visible"))
    (is (contains? (class-set (:chart-panel-classes layout)) "overflow-visible"))
    (is (contains? (class-set (:chart-panel-classes layout)) "lg:flex"))
    (is (contains? (class-set (:orderbook-panel-classes layout)) "xl:row-start-1"))
    (is (contains? (class-set (:order-entry-panel-classes layout)) "xl:row-span-2"))
    (is (contains? (class-set (:account-panel-classes layout)) "xl:col-span-2"))))

(deftest desktop-trade-grid-style-exposes-content-derived-height-contract-test
  (let [grid-style (layout-state/desktop-trade-grid-style true)]
    (is (nil? (layout-state/desktop-trade-grid-style false)))
    (is (not (str/blank? (:--trade-chart-canvas-min-height grid-style)))
        "desktop grid should expose the chart canvas minimum through a CSS custom property")
    (is (not (str/blank? (:--trade-chart-toolbar-height grid-style)))
        "desktop grid should expose the chart toolbar allowance through a CSS custom property")
    (is (not (str/blank? (:--trade-chart-market-strip-height grid-style)))
        "desktop grid should expose the market strip allowance through a CSS custom property")
    (is (= "calc(var(--trade-chart-market-strip-height) + var(--trade-chart-toolbar-height) + var(--trade-chart-canvas-min-height))"
           (:--trade-chart-row-min-height grid-style)))
    (is (= "clamp(12rem, 30vh, 23rem)"
           (:--trade-account-panel-height grid-style)))
    (is (= "12rem" (:--trade-chart-canvas-min-height grid-style)))
    (is (= "minmax(var(--trade-chart-row-min-height), 1fr) var(--trade-account-panel-height)"
           (:grid-template-rows grid-style)))
    (is (not (str/includes? (:grid-template-rows grid-style) "minmax(24rem, 1fr)")))
    (is (= "calc(var(--trade-chart-row-min-height) + var(--trade-account-panel-height))"
           (:min-height grid-style)))))

(deftest desktop-market-strip-stays-pinned-in-the-chart-column-test
  (let [closed (layout-state/trade-layout-state {} 1280 false)
        funding-open (layout-state/trade-layout-state {} 1280 true)
        shell-classes (class-set (:desktop-active-asset-shell-classes closed))]
    (is (contains? shell-classes "lg:sticky"))
    (is (contains? shell-classes "lg:top-0"))
    (is (contains? shell-classes "lg:z-[130]"))
    (is (not (contains? shell-classes "relative")))
    (is (contains? (class-set (:desktop-active-asset-shell-classes funding-open)) "lg:z-[160]"))
    (is (contains? (class-set (:chart-panel-classes closed)) "lg:overflow-clip"))
    (is (contains? (class-set (:chart-panel-classes closed)) "overflow-hidden"))))

(deftest desktop-order-entry-column-scrolls-inside-its-cell-test
  (let [classes (class-set (:order-entry-panel-classes (layout-state/trade-layout-state {} 1280 false)))]
    (is (contains? classes "lg:overflow-y-auto"))
    (is (contains? classes "lg:overflow-x-hidden"))
    (is (contains? classes "min-h-0"))))

(deftest open-asset-selector-lifts-the-desktop-chart-panel-test
  (let [selector-open {:asset-selector {:visible-dropdown :asset-selector}}
        desktop (class-set (:chart-panel-classes (layout-state/trade-layout-state selector-open 1280 false)))
        mobile (class-set (:chart-panel-classes (layout-state/trade-layout-state selector-open 430 false)))]
    (is (contains? desktop "overflow-visible"))
    (is (contains? desktop "z-[160]"))
    (is (not (contains? desktop "lg:overflow-clip")))
    (is (contains? mobile "lg:overflow-clip"))
    (is (not (contains? mobile "overflow-visible")))))
