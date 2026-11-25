(ns hyperopen.views.trade-view.layout-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.trade-view.layout-state :as layout-state]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(deftest trade-view-does-not-use-app-shell-gutter-test
  (let [view-node (trade-view/trade-view (support/base-state))]
    (is (nil? (support/find-first-node view-node
                                       #(contains? (support/node-class-set %) "app-shell-gutter"))))))

(deftest trade-view-root-and-right-column-layout-test
  (let [view-node (trade-view/trade-view (support/base-state))
        root-classes (support/root-class-set view-node)
        scroll-shell (support/find-by-data-role view-node "trade-scroll-shell")
        scroll-shell-classes (support/node-class-set scroll-shell)
        chart-panel (support/find-by-parity-id view-node "trade-chart-panel")
        grid-shell (support/find-first-node view-node
                                            #(and (contains? (support/node-class-set %) "lg:grid-cols-[minmax(0,1fr)_320px]")
                                                  (contains? (support/node-class-set %) "xl:grid-cols-[minmax(0,1fr)_280px_320px]")))
        right-divider (support/find-first-node view-node
                                              #(contains? (support/node-class-set %) "right-[320px]"))
        right-divider-classes (support/node-class-set right-divider)
        grid-style (get-in grid-shell [1 :style])]
    (is (not (contains? root-classes "overflow-auto")))
    (is (contains? root-classes "min-h-0"))
    (is (contains? root-classes "overflow-hidden"))
    (is (not (contains? root-classes "scrollbar-hide")))
    (is (not (contains? root-classes "xl:overflow-y-auto")))
    (is (some? scroll-shell))
    (is (contains? scroll-shell-classes "max-lg:scrollbar-hide"))
    (is (not (contains? scroll-shell-classes "scrollbar-hide")))
    (is (contains? scroll-shell-classes "overflow-y-auto"))
    (is (some? right-divider))
    (is (contains? right-divider-classes "right-[320px]"))
    (is (contains? (support/node-class-set chart-panel) "lg:row-start-1"))
    (is (some? grid-shell))
    (is (= "minmax(var(--trade-chart-row-min-height), 1fr) var(--trade-account-panel-height)"
           (:grid-template-rows grid-style)))
    (is (= "calc(var(--trade-chart-row-min-height) + var(--trade-account-panel-height))"
           (:min-height grid-style)))
    (is (contains? (support/node-class-set grid-shell) "xl:grid-cols-[minmax(0,1fr)_280px_320px]"))
    (is (not (contains? (support/node-class-set grid-shell) "xl:row-span-2")))))

(deftest trade-view-xl-panel-span-contract-test
  (let [view-node (trade-view/trade-view (support/base-state))
        chart-panel (support/find-by-parity-id view-node "trade-chart-panel")
        orderbook-panel (support/find-by-parity-id view-node "trade-orderbook-panel")
        order-entry-panel (support/find-by-parity-id view-node "trade-order-entry-panel")
        account-panel (support/find-by-parity-id view-node "trade-account-tables-panel")
        chart-panel-classes (support/node-class-set chart-panel)
        orderbook-classes (support/node-class-set orderbook-panel)
        order-entry-classes (support/node-class-set order-entry-panel)
        account-panel-classes (support/node-class-set account-panel)]
    (is (some? chart-panel))
    (is (some? orderbook-panel))
    (is (some? order-entry-panel))
    (is (some? account-panel))
    (is (contains? chart-panel-classes "min-w-0"))
    (is (contains? chart-panel-classes "lg:overflow-clip"))
    (is (contains? orderbook-classes "xl:col-start-2"))
    (is (contains? orderbook-classes "xl:row-start-1"))
    (is (not (contains? orderbook-classes "xl:row-span-2")))
    (is (contains? order-entry-classes "xl:col-start-3"))
    (is (contains? order-entry-classes "xl:row-span-2"))
    (is (contains? account-panel-classes "xl:col-start-1"))
    (is (contains? account-panel-classes "xl:col-span-2"))))

(deftest trade-view-account-info-cell-bounds-overflow-test
  (let [view-node (trade-view/trade-view (support/base-state))
        account-info-cell (support/find-by-parity-id view-node "trade-account-tables-panel")
        account-info-cell-classes (support/node-class-set account-info-cell)]
    (is (some? account-info-cell))
    (is (contains? account-info-cell-classes "border-t"))
    (is (contains? account-info-cell-classes "lg:flex"))
    (is (contains? account-info-cell-classes "flex-col"))
    (is (contains? account-info-cell-classes "min-h-0"))
    (is (contains? account-info-cell-classes "overflow-hidden"))))

(deftest trade-view-keeps-account-table-height-stable-across-standard-tabs-test
  (let [standard-tabs [:balances
                       :positions
                       :open-orders
                       :twap
                       :trade-history
                       :funding-history
                       :order-history]
        account-table-nodes (support/with-account-surface-exports
                              {:account-info-view account-info-view/account-info-view}
                              (fn []
                                (mapv (fn [tab]
                                        (support/find-by-parity-id
                                         (trade-view/trade-view
                                          (assoc-in (support/base-state)
                                                    [:account-info :selected-tab]
                                                    tab))
                                         "account-tables"))
                                      standard-tabs)))
        class-sets (mapv support/node-class-set account-table-nodes)
        reference-classes (first class-sets)]
    (is (every? some? account-table-nodes))
    (is (every? #(= reference-classes %) class-sets))
    (is (contains? reference-classes "h-full"))
    (is (not (contains? reference-classes "h-96")))
    (is (not (contains? reference-classes "lg:h-[29rem]")))
    (is (contains? reference-classes "overflow-hidden"))
    (is (contains? reference-classes "min-h-0"))))

(deftest trade-view-orderbook-panel-uses-compact-mobile-height-with-desktop-override-test
  (let [view-node (trade-view/trade-view (support/base-state))
        orderbook-panel (support/find-by-parity-id view-node "trade-orderbook-panel")
        orderbook-classes (support/node-class-set orderbook-panel)]
    (is (some? orderbook-panel))
    (is (contains? orderbook-classes "h-[320px]"))
    (is (contains? orderbook-classes "min-h-[320px]"))
    (is (contains? orderbook-classes "sm:h-[360px]"))
    (is (contains? orderbook-classes "sm:min-h-[360px]"))
    (is (contains? orderbook-classes "lg:h-full"))
    (is (contains? orderbook-classes "lg:min-h-0"))))

(deftest trade-view-layout-state-desktop-boundary-test
  (is (false? (layout-state/desktop-layout? 1023)))
  (is (layout-state/desktop-layout? 1024)))

(deftest trade-view-layout-state-derives-visibility-and-grid-contracts-test
  (let [mobile-state (assoc-in (support/base-state) [:trade-ui :mobile-surface] :account)
        mobile-layout (layout-state/trade-layout-state mobile-state 430 true)
        desktop-state (assoc-in (support/base-state) [:trade-ui :mobile-surface] :chart)
        desktop-layout (layout-state/trade-layout-state desktop-state 1280 false)]
    (is (= :account (:mobile-surface mobile-layout)))
    (is (false? (:desktop-layout? mobile-layout)))
    (is (:mobile-account-surface? mobile-layout))
    (is (:mobile-account-summary-visible? mobile-layout))
    (is (contains? (set (:mobile-active-asset-strip-classes mobile-layout)) "hidden"))
    (is (contains? (set (:mobile-surface-tabs-classes mobile-layout)) "hidden"))
    (is (= ["absolute"
            "inset-0"
            "z-20"
            "bg-base-100"
            "border-t"
            "border-base-300"
            "flex"
            "flex-col"
            "min-h-0"
            "lg:hidden"]
           (:mobile-account-summary-classes mobile-layout)))
    (is (= :chart (:mobile-surface desktop-layout)))
    (is (layout-state/desktop-layout? 1280))
    (is (:desktop-layout? desktop-layout))
    (is (:show-equity-surface? desktop-layout))
    (is (:chart-panel-visible? desktop-layout))
    (is (= "minmax(var(--trade-chart-row-min-height), 1fr) var(--trade-account-panel-height)"
           (:grid-template-rows (:grid-style desktop-layout))))
    (is (= "calc(var(--trade-chart-row-min-height) + var(--trade-account-panel-height))"
           (:min-height (:grid-style desktop-layout))))
    (is (contains? (set (:chart-panel-classes desktop-layout)) "lg:row-start-1"))
    (is (contains? (set (:account-panel-classes desktop-layout)) "xl:col-span-2"))))
