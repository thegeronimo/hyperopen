(ns hyperopen.views.app-shell-spacing-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.active-asset-view :as active-asset-view]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.views.api-wallets-view]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.views.l2-orderbook-view :as l2-orderbook-view]
            [hyperopen.views.trade.order-form-view :as order-form-view]
            [hyperopen.views.trade-view :as trade-view]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- contains-class? [node class-name]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))
                    class-set (set (class-values (:class attrs)))]
                (or (contains? class-set class-name)
                    (some walk children)))

              (seq? n)
              (some walk n)

              :else
              nil))]
    (boolean (walk node))))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- find-nodes [node pred]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))
                    matches (if (pred n) [n] [])]
                (into matches (mapcat walk children)))

              (seq? n)
              (mapcat walk n)

              :else []))]
    (vec (walk node))))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-text [node]
  (str/join " " (collect-strings node)))

(defn- root-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))]
    (set (class-values (:class attrs)))))

(defn- node-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))]
    (set (class-values (:class attrs)))))

(defn- with-viewport-width
  [width f]
  (let [original-inner-width (.-innerWidth js/globalThis)]
    (set! (.-innerWidth js/globalThis) width)
    (try
      (f)
      (finally
        (set! (.-innerWidth js/globalThis) original-inner-width)))))

(def trade-view-test-state
  {:active-asset nil
   :active-market nil
   :orderbooks {}
   :webdata2 {}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :fills []
            :fundings []
            :order-history []
            :ledger []}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}
   :order-form (trading/default-order-form)
   :asset-selector {:visible-dropdown nil
                    :search-term ""
                    :sort-by :volume
                    :sort-direction :desc
                    :markets []
                    :market-by-key {}
                    :loading? false
                    :phase :bootstrap
                    :favorites #{}
                    :missing-icons #{}
                    :favorites-only? false
                    :strict? false
                    :active-tab :all}
   :chart-options {:selected-timeframe :1d
                   :selected-chart-type :candlestick}
   :orderbook-ui {:size-unit :base
                  :size-unit-dropdown-visible? false
                  :price-aggregation-dropdown-visible? false
                  :price-aggregation-by-coin {}
                  :active-tab :orderbook}
   :account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-sort {:column nil :direction :asc}
                  :positions-sort {:column nil :direction :asc}
                  :open-orders-sort {:column "Time" :direction :desc}}})

(defn- active-asset-panel-test-state []
  (assoc trade-view-test-state
         :active-asset "BTC"
         :active-market {:key "perp:BTC"
                         :coin "BTC"
                         :symbol "BTC-USDC"
                         :base "BTC"
                         :market-type :perp}
         :active-assets {:contexts {"BTC" {:coin "BTC"
                                           :mark 64000.0
                                           :markRaw "64000.0"
                                           :oracle 63990.0
                                           :oracleRaw "63990.0"
                                           :change24h 1500.0
                                           :change24hPct 2.4
                                           :volume24h 1250000.0
                                           :openInterest 250000.0
                                           :fundingRate 0.01}}
                         :funding-predictability {:by-coin {}
                                                  :loading-by-coin {}
                                                  :error-by-coin {}}}
         :asset-selector {:visible-dropdown nil
                          :search-term ""
                          :sort-by :volume
                          :sort-direction :desc
                          :markets [{:key "perp:BTC"
                                     :coin "BTC"
                                     :symbol "BTC-USDC"
                                     :base "BTC"
                                     :market-type :perp}
                                    {:key "perp:ETH"
                                     :coin "ETH"
                                     :symbol "ETH-USDC"
                                     :base "ETH"
                                     :market-type :perp}]
                          :market-by-key {"perp:BTC" {:key "perp:BTC"
                                                      :coin "BTC"
                                                      :symbol "BTC-USDC"
                                                      :base "BTC"
                                                      :market-type :perp}
                                          "perp:ETH" {:key "perp:ETH"
                                                      :coin "ETH"
                                                      :symbol "ETH-USDC"
                                                      :base "ETH"
                                                      :market-type :perp}}
                          :loading? false
                          :phase :bootstrap
                          :favorites #{}
                          :missing-icons #{}
                          :loaded-icons #{"perp:BTC"}
                          :favorites-only? false
                          :strict? false
                          :active-tab :all}
         :funding-ui {:tooltip {}}
         :trade-ui {:mobile-asset-details-open? false}))

(deftest header-view-uses-app-shell-gutter-test
  (let [view-node (header-view/header-view {:wallet {}})]
    (is (contains-class? view-node "app-shell-gutter"))))

(deftest header-navigation-links-remain-left-aligned-test
  (let [view-node (header-view/header-view {:wallet {}})
        nav-node (find-first-node view-node
                                  (fn [candidate]
                                    (and (vector? candidate)
                                         (keyword? (first candidate))
                                         (str/starts-with? (name (first candidate)) "nav."))))]
    (is (= :nav.hidden.md:flex.flex-1.items-center.justify-start.space-x-8.ml-8
           (first nav-node)))))

(deftest header-navigation-links-use-hyperliquid-typography-classes-test
  (let [view-node (header-view/header-view {:wallet {}})
        trade-link (find-first-node view-node
                                    (fn [candidate]
                                      (and (= :a (first candidate))
                                           (some #{"Trade"} (collect-strings candidate)))))
        vaults-link (find-first-node view-node
                                     (fn [candidate]
                                       (and (= :a (first candidate))
                                            (some #{"Vaults"} (collect-strings candidate)))))
        trade-classes (set (class-values (get-in trade-link [1 :class])))
        vaults-classes (set (class-values (get-in vaults-link [1 :class])))]
    (is (contains? trade-classes "header-nav-link"))
    (is (contains? trade-classes "header-nav-link-active"))
    (is (contains? vaults-classes "header-nav-link"))
    (is (not (contains? vaults-classes "header-nav-link-active")))))

(deftest trade-view-does-not-use-app-shell-gutter-test
  (let [view-node (trade-view/trade-view trade-view-test-state)]
    (is (not (contains-class? view-node "app-shell-gutter")))))

(deftest trade-view-active-asset-panel-memoization-ignores-closed-selector-bookkeeping-test
  (let [render-panel @#'trade-view/render-active-asset-panel
        base-state (active-asset-panel-test-state)
        changed-state (-> base-state
                          (assoc-in [:asset-selector :search-term] "eth")
                          (assoc-in [:asset-selector :scroll-top] 144)
                          (assoc-in [:asset-selector :highlighted-market-key] "perp:ETH")
                          (assoc-in [:active-assets :contexts "ETH"] {:coin "ETH"
                                                                      :mark 3200.0})
                          (assoc-in [:active-assets :funding-predictability :by-coin "ETH"] {:mean 0.2}))]
    (is (identical? (render-panel base-state)
                    (render-panel changed-state)))))

(deftest trade-view-active-asset-panel-open-selector-still-reacts-to-dropdown-state-test
  (let [render-panel @#'trade-view/render-active-asset-panel
        base-state (assoc-in (active-asset-panel-test-state)
                             [:asset-selector :visible-dropdown]
                             :asset-selector)
        changed-state (assoc-in base-state
                                [:asset-selector :search-term]
                                "eth")]
    (is (not (identical? (render-panel base-state)
                         (render-panel changed-state))))))

(deftest trade-view-chart-loading-shell-preserves-chart-host-geometry-test
  (with-redefs [trade-modules/render-trade-chart-view (constantly nil)]
    (let [view-node (with-viewport-width
                      1280
                      #(trade-view/trade-view (active-asset-panel-test-state)))
          shell-node (find-first-node view-node
                                      (fn [candidate]
                                        (= "trade-chart-module-shell"
                                           (get-in candidate [1 :data-parity-id]))))
          host-node (find-first-node shell-node
                                     (fn [candidate]
                                       (contains? (node-class-set candidate)
                                                  "trading-chart-host")))]
      (is (some? shell-node))
      (is (contains? (node-class-set shell-node) "w-full"))
      (is (contains? (node-class-set shell-node) "h-full"))
      (is (some? host-node))
      (is (contains? (node-class-set host-node) "min-h-[360px]"))
      (is (some #{"Loading Chart"} (collect-strings shell-node))))))

(deftest trade-view-root-and-right-column-layout-test
  (let [view-node (trade-view/trade-view trade-view-test-state)
        root-classes (root-class-set view-node)
        scroll-shell (find-first-node view-node
                                      #(= "trade-scroll-shell"
                                          (get-in % [1 :data-role])))
        scroll-shell-classes (node-class-set scroll-shell)]
    (is (not (contains? root-classes "overflow-auto")))
    (is (contains? root-classes "min-h-0"))
    (is (contains? root-classes "overflow-hidden"))
    (is (not (contains? root-classes "scrollbar-hide")))
    (is (not (contains? root-classes "xl:overflow-y-auto")))
    (is (some? scroll-shell))
    (is (contains? scroll-shell-classes "scrollbar-hide"))
    (is (contains? scroll-shell-classes "overflow-y-auto"))
    (is (contains-class? view-node "right-[320px]"))
    (is (contains-class? view-node "lg:grid-cols-[minmax(0,1fr)_320px]"))
    (is (contains-class? view-node "lg:grid-rows-[minmax(520px,1fr)_29rem]"))
    (is (contains-class? view-node "xl:grid-cols-[minmax(0,1fr)_280px_320px]"))
    (is (contains-class? view-node "xl:grid-rows-[minmax(580px,1fr)_29rem]"))
    (is (contains-class? view-node "xl:row-span-2"))))

(deftest trade-view-xl-panel-span-contract-test
  (let [view-node (trade-view/trade-view trade-view-test-state)
        chart-panel (find-first-node view-node
                                     #(= "trade-chart-panel"
                                         (get-in % [1 :data-parity-id])))
        orderbook-panel (find-first-node view-node
                                         #(= "trade-orderbook-panel"
                                             (get-in % [1 :data-parity-id])))
        order-entry-panel (find-first-node view-node
                                           #(= "trade-order-entry-panel"
                                               (get-in % [1 :data-parity-id])))
        account-panel (find-first-node view-node
                                       #(= "trade-account-tables-panel"
                                           (get-in % [1 :data-parity-id])))
        chart-panel-classes (node-class-set chart-panel)
        orderbook-classes (node-class-set orderbook-panel)
        order-entry-classes (node-class-set order-entry-panel)
        account-panel-classes (node-class-set account-panel)]
    (is (some? chart-panel))
    (is (some? orderbook-panel))
    (is (some? order-entry-panel))
    (is (some? account-panel))
    (is (contains? chart-panel-classes "min-w-0"))
    (is (contains? chart-panel-classes "overflow-hidden"))
    (is (contains? orderbook-classes "xl:col-start-2"))
    (is (contains? orderbook-classes "xl:row-start-1"))
    (is (not (contains? orderbook-classes "xl:row-span-2")))
    (is (contains? order-entry-classes "xl:col-start-3"))
    (is (contains? order-entry-classes "xl:row-span-2"))
    (is (contains? account-panel-classes "xl:col-start-1"))
    (is (contains? account-panel-classes "xl:col-span-2"))))

(deftest trade-view-account-info-cell-bounds-overflow-test
  (let [view-node (trade-view/trade-view trade-view-test-state)
        account-info-cell (find-first-node view-node
                                           (fn [candidate]
                                             (= "trade-account-tables-panel"
                                                (get-in candidate [1 :data-parity-id]))))
        account-info-cell-classes (node-class-set account-info-cell)]
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
        account-table-nodes (mapv (fn [tab]
                                    (find-first-node
                                     (trade-view/trade-view (assoc-in trade-view-test-state
                                                                      [:account-info :selected-tab]
                                                                      tab))
                                     #(= "account-tables"
                                         (get-in % [1 :data-parity-id]))))
                                  standard-tabs)
        class-sets (mapv node-class-set account-table-nodes)
        reference-classes (first class-sets)]
    (is (every? some? account-table-nodes))
    (is (every? #(= reference-classes %) class-sets))
    (is (contains? reference-classes "h-full"))
    (is (not (contains? reference-classes "h-96")))
    (is (not (contains? reference-classes "lg:h-[29rem]")))
    (is (contains? reference-classes "overflow-hidden"))
    (is (contains? reference-classes "min-h-0"))))

(deftest trade-view-chart-shell-fills-the-desktop-top-row-test
  (with-redefs [trade-modules/render-trade-chart-view (constantly nil)]
    (let [view-node (with-viewport-width
                      1280
                      #(trade-view/trade-view (active-asset-panel-test-state)))
          chart-panel (find-first-node view-node
                                       #(= "trade-chart-panel"
                                           (get-in % [1 :data-parity-id])))
          loading-shell (find-first-node view-node
                                         #(= "trade-chart-module-shell"
                                             (get-in % [1 :data-parity-id])))
          shell-inner (nth loading-shell 2)
          chart-panel-classes (node-class-set chart-panel)
          shell-inner-classes (node-class-set shell-inner)]
      (is (some? chart-panel))
      (is (some? loading-shell))
      (is (contains? chart-panel-classes "lg:row-start-1"))
      (is (contains? chart-panel-classes "lg:col-start-1"))
      (is (contains? shell-inner-classes "h-full"))
      (is (contains? shell-inner-classes "flex"))
      (is (contains? shell-inner-classes "flex-col")))))

(deftest trade-view-orderbook-panel-uses-compact-mobile-height-with-desktop-override-test
  (let [view-node (trade-view/trade-view trade-view-test-state)
        orderbook-panel (find-first-node view-node
                                         #(= "trade-orderbook-panel"
                                             (get-in % [1 :data-parity-id])))
        orderbook-classes (node-class-set orderbook-panel)]
    (is (some? orderbook-panel))
    (is (contains? orderbook-classes "h-[320px]"))
    (is (contains? orderbook-classes "min-h-[320px]"))
    (is (contains? orderbook-classes "sm:h-[360px]"))
    (is (contains? orderbook-classes "sm:min-h-[360px]"))
    (is (contains? orderbook-classes "lg:h-full"))
    (is (contains? orderbook-classes "lg:min-h-0"))))

(deftest trade-view-restores-market-tables-and-keeps-account-surface-summary-only-test
  (with-viewport-width
    430
    (fn []
      (let [chart-view (trade-view/trade-view trade-view-test-state)
            chart-account-panel (find-first-node chart-view
                                                 #(= "trade-account-tables-panel"
                                                     (get-in % [1 :data-parity-id])))
            chart-classes (node-class-set chart-account-panel)
            chart-account-text (set (collect-strings chart-account-panel))
            chart-summary-panel (find-first-node chart-view
                                                 #(= "trade-mobile-account-summary-panel"
                                                     (get-in % [1 :data-parity-id])))
            chart-summary-classes (node-class-set chart-summary-panel)
            trades-view (trade-view/trade-view (assoc-in trade-view-test-state
                                                         [:trade-ui :mobile-surface]
                                                         :trades))
            trades-account-panel (find-first-node trades-view
                                                  #(= "trade-account-tables-panel"
                                                      (get-in % [1 :data-parity-id])))
            trades-orderbook-panel (find-first-node trades-view
                                                    #(= "trade-orderbook-panel"
                                                        (get-in % [1 :data-parity-id])))
            trades-order-entry-panel (find-first-node trades-view
                                                      #(= "trade-order-entry-panel"
                                                          (get-in % [1 :data-parity-id])))
            trades-account-classes (node-class-set trades-account-panel)
            trades-orderbook-classes (node-class-set trades-orderbook-panel)
            trades-order-entry-classes (node-class-set trades-order-entry-panel)
            ticket-view (trade-view/trade-view (assoc-in trade-view-test-state
                                                         [:trade-ui :mobile-surface]
                                                         :ticket))
            ticket-account-panel (find-first-node ticket-view
                                                  #(= "trade-account-tables-panel"
                                                      (get-in % [1 :data-parity-id])))
            ticket-classes (node-class-set ticket-account-panel)
            ticket-desktop-equity-panel (find-first-node ticket-view
                                                         #(= "trade-desktop-account-equity-panel"
                                                             (get-in % [1 :data-parity-id])))
            ticket-desktop-equity-classes (node-class-set ticket-desktop-equity-panel)
            ticket-summary-panel (find-first-node ticket-view
                                                  #(= "trade-mobile-account-summary-panel"
                                                      (get-in % [1 :data-parity-id])))
            account-view (trade-view/trade-view (assoc-in trade-view-test-state
                                                          [:trade-ui :mobile-surface]
                                                          :account))
            account-active-asset-strip (find-first-node account-view
                                                        #(= "trade-mobile-active-asset-strip"
                                                            (get-in % [1 :data-parity-id])))
            account-active-asset-strip-classes (node-class-set account-active-asset-strip)
            account-surface-tabs (find-first-node account-view
                                                  #(= "trade-mobile-surface-tabs"
                                                      (get-in % [1 :data-parity-id])))
            account-surface-tabs-classes (node-class-set account-surface-tabs)
            account-panel (find-first-node account-view
                                           #(= "trade-account-tables-panel"
                                               (get-in % [1 :data-parity-id])))
            account-panel-classes (node-class-set account-panel)
            account-summary-panel (find-first-node account-view
                                                   #(= "trade-mobile-account-summary-panel"
                                                       (get-in % [1 :data-parity-id])))
            account-summary-classes (node-class-set account-summary-panel)
            account-actions-panel (find-first-node account-view
                                                   #(= "trade-mobile-account-actions"
                                                       (get-in % [1 :data-parity-id])))
            account-actions-classes (node-class-set account-actions-panel)
            account-mobile-panel (find-first-node account-view
                                                  #(= "trade-mobile-account-surface"
                                                      (get-in % [1 :data-parity-id])))
            account-mobile-text (set (collect-strings account-mobile-panel))]
        (is (contains? chart-classes "flex"))
        (is (not (contains? chart-classes "hidden")))
        (is (nil? chart-summary-panel))
        (is (some #(str/starts-with? % "Balances") chart-account-text))
        (is (some #(str/starts-with? % "Open Orders") chart-account-text))
        (is (contains? chart-account-text "Trade History"))
        (is (contains? trades-account-classes "flex"))
        (is (not (contains? trades-account-classes "hidden")))
        (is (contains? trades-orderbook-classes "block"))
        (is (not (contains? trades-orderbook-classes "hidden")))
        (is (contains? trades-order-entry-classes "hidden"))
        (is (contains? ticket-classes "hidden"))
        (is (nil? ticket-summary-panel))
        (is (contains? ticket-desktop-equity-classes "hidden"))
        (is (contains? account-active-asset-strip-classes "hidden"))
        (is (contains? account-surface-tabs-classes "hidden"))
        (is (contains? account-panel-classes "hidden"))
        (is (contains? account-summary-classes "flex"))
        (is (not (contains? account-summary-classes "hidden")))
        (is (contains? account-summary-classes "absolute"))
        (is (contains? account-summary-classes "inset-0"))
        (is (contains? account-actions-classes "mt-auto"))
        (is (contains? account-actions-classes "pt-2"))
        (is (contains? account-actions-classes "pb-1.5"))
        (is (not (contains? account-actions-classes "py-3")))
        (is (contains? account-mobile-text "Account Equity"))
        (is (contains? account-mobile-text "Deposit"))
        (is (contains? account-mobile-text "Withdraw"))
        (is (not (some #(str/starts-with? % "Balances") account-mobile-text)))
        (is (not (some #(str/starts-with? % "Open Orders") account-mobile-text)))
        (is (not (contains? account-mobile-text "Trade History")))))))

(deftest trade-view-computes-account-equity-metrics-once-per-rendered-equity-surface-test
  (let [metrics-calls (atom 0)
        rendered-account-equity-views (atom 0)
        stub-metrics {:account-value-display 25}
        account-view (assoc-in trade-view-test-state
                               [:trade-ui :mobile-surface]
                               :account)]
    (with-viewport-width
      430
      (fn []
        (with-redefs [account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! metrics-calls inc)
                                                                   stub-metrics)
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! rendered-account-equity-views inc)
                                                                 [:div {:data-role "stub-account-equity"
                                                                        :data-metrics nil}])
                                                                ([_state opts]
                                                                 (swap! rendered-account-equity-views inc)
                                                                 [:div {:data-role "stub-account-equity"
                                                                        :data-metrics (:metrics opts)}]))
                      account-equity-view/funding-actions-view (fn
                                                                 ([_state]
                                                                  [:div {:data-role "stub-mobile-funding-actions"}])
                                                                 ([_state _opts]
                                                                  [:div {:data-role "stub-mobile-funding-actions"}]))]
          (let [view-node (trade-view/trade-view account-view)
                stub-equity-nodes (find-nodes view-node
                                              #(= "stub-account-equity"
                                                  (get-in % [1 :data-role])))]
            (is (= 1 @metrics-calls))
            (is (= 1 @rendered-account-equity-views))
            (is (= 1 (count stub-equity-nodes)))
            (is (every? #(= stub-metrics
                            (get-in % [1 :data-metrics]))
                        stub-equity-nodes))))))))

(deftest trade-view-renders-heavy-surfaces-once-on-desktop-layout-test
  (with-viewport-width
    1280
    (fn []
      (let [active-asset-calls (atom 0)
            chart-calls (atom 0)
            orderbook-calls (atom 0)
            order-form-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)]
        (with-redefs [active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])
                      trade-modules/render-trade-chart-view (fn [_state]
                                                              (swap! chart-calls inc)
                                                              [:div {:data-role "stub-chart"}])
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])
                      account-info-view/account-info-view (fn
                                                            ([_state]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}])
                                                            ([_state _options]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}]))
                      account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! equity-metrics-calls inc)
                                                                   {:account-value-display 12})
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}])
                                                                ([_state _opts]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}]))]
          (let [view-node (trade-view/trade-view trade-view-test-state)]
            (is (= 1 @active-asset-calls))
            (is (= 1 @chart-calls))
            (is (= 1 @orderbook-calls))
            (is (= 1 @order-form-calls))
            (is (= 1 @account-info-calls))
            (is (= 1 @equity-metrics-calls))
            (is (= 1 @account-equity-calls))
            (is (= 1 (count (find-nodes view-node #(= "stub-active-asset"
                                                      (get-in % [1 :data-role]))))))
            (is (= 1 (count (find-nodes view-node #(= "stub-orderbook"
                                                      (get-in % [1 :data-role]))))))
            (is (= 1 (count (find-nodes view-node #(= "stub-order-form"
                                                      (get-in % [1 :data-role]))))))
            (is (= 1 (count (find-nodes view-node #(= "stub-account-info"
                                                      (get-in % [1 :data-role]))))))
            (is (= 1 (count (find-nodes view-node #(= "stub-account-equity"
                                                      (get-in % [1 :data-role]))))))))))))

(deftest trade-view-memoizes-non-orderbook-subtrees-across-orderbook-only-updates-test
  (with-viewport-width
    1280
    (fn []
      (let [active-asset-calls (atom 0)
            chart-calls (atom 0)
            orderbook-calls (atom 0)
            order-form-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)
            state-a (assoc trade-view-test-state
                           :active-asset "BTC"
                           :active-market {:coin "BTC"
                                           :market-type :perp
                                           :base "BTC"
                                           :quote "USDC"
                                           :szDecimals 4}
                           :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                               :asks [{:px "101" :sz "1"}]}})
            state-b (assoc state-a
                           :orderbooks {"BTC" {:bids [{:px "98.5" :sz "2.5"}]
                                               :asks [{:px "101.5" :sz "1.5"}]}})]
        (with-redefs [active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])
                      trade-modules/render-trade-chart-view (fn [_state]
                                                              (swap! chart-calls inc)
                                                              [:div {:data-role "stub-chart"}])
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])
                      account-info-view/account-info-view (fn
                                                            ([_state]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}])
                                                            ([_state _options]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}]))
                      account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! equity-metrics-calls inc)
                                                                   {:account-value-display 12})
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}])
                                                                ([_state _opts]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}]))]
          (trade-view/trade-view state-a)
          (trade-view/trade-view state-b)
          (is (= 1 @active-asset-calls))
          (is (= 1 @chart-calls))
          (is (= 2 @orderbook-calls))
          (is (= 2 @order-form-calls))
          (is (= 1 @account-info-calls))
          (is (= 1 @equity-metrics-calls))
          (is (= 1 @account-equity-calls)))))))

(deftest trade-view-skips-websocket-only-rerenders-when-surface-freshness-cues-are-disabled-test
  (with-viewport-width
    1280
    (fn []
      (let [active-asset-calls (atom 0)
            chart-calls (atom 0)
            orderbook-calls (atom 0)
            order-form-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)
            state-a (assoc trade-view-test-state
                           :active-asset "BTC"
                           :active-market {:coin "BTC"
                                           :market-type :perp
                                           :base "BTC"
                                           :quote "USDC"
                                           :szDecimals 4}
                           :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                               :asks [{:px "101" :sz "1"}]}}
                           :websocket {:health {:generated-at-ms 5000}})
            state-b (assoc-in state-a
                              [:websocket :health :generated-at-ms]
                              6000)]
        (with-redefs [active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])
                      trade-modules/render-trade-chart-view (fn [_state]
                                                              (swap! chart-calls inc)
                                                              [:div {:data-role "stub-chart"}])
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])
                      account-info-view/account-info-view (fn
                                                            ([_state]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}])
                                                            ([_state _options]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}]))
                      account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! equity-metrics-calls inc)
                                                                   {:account-value-display 12})
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}])
                                                                ([_state _opts]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}]))]
          (trade-view/trade-view state-a)
          (trade-view/trade-view state-b)
          (is (= 1 @active-asset-calls))
          (is (= 1 @chart-calls))
          (is (= 1 @orderbook-calls))
          (is (= 1 @order-form-calls))
          (is (= 1 @account-info-calls))
          (is (= 1 @equity-metrics-calls))
          (is (= 1 @account-equity-calls)))))))

(deftest trade-view-skips-hidden-heavy-surface-subtrees-on-mobile-chart-layout-test
  (with-viewport-width
    430
    (fn []
      (let [active-asset-calls (atom 0)
            chart-calls (atom 0)
            orderbook-calls (atom 0)
            order-form-calls (atom 0)
            account-info-calls (atom 0)
            equity-metrics-calls (atom 0)
            account-equity-calls (atom 0)]
        (with-redefs [active-asset-view/active-asset-view (fn [_state]
                                                            (swap! active-asset-calls inc)
                                                            [:div {:data-role "stub-active-asset"}])
                      trade-modules/render-trade-chart-view (fn [_state]
                                                              (swap! chart-calls inc)
                                                              [:div {:data-role "stub-chart"}])
                      l2-orderbook-view/l2-orderbook-view (fn [_state]
                                                            (swap! orderbook-calls inc)
                                                            [:div {:data-role "stub-orderbook"}])
                      order-form-view/order-form-view (fn [_state]
                                                        (swap! order-form-calls inc)
                                                        [:div {:data-role "stub-order-form"}])
                      account-info-view/account-info-view (fn
                                                            ([_state]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}])
                                                            ([_state _options]
                                                             (swap! account-info-calls inc)
                                                             [:div {:data-role "stub-account-info"}]))
                      account-equity-view/account-equity-metrics (fn [_state]
                                                                   (swap! equity-metrics-calls inc)
                                                                   {:account-value-display 12})
                      account-equity-view/account-equity-view (fn
                                                                ([_state]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}])
                                                                ([_state _opts]
                                                                 (swap! account-equity-calls inc)
                                                                 [:div {:data-role "stub-account-equity"}]))]
          (let [view-node (trade-view/trade-view trade-view-test-state)
                orderbook-nodes (find-nodes view-node #(= "stub-orderbook"
                                                          (get-in % [1 :data-role])))
                order-form-nodes (find-nodes view-node #(= "stub-order-form"
                                                           (get-in % [1 :data-role])))
                account-equity-nodes (find-nodes view-node #(= "stub-account-equity"
                                                               (get-in % [1 :data-role])))]
            (is (= 1 @active-asset-calls))
            (is (= 1 @chart-calls))
            (is (= 0 @orderbook-calls))
            (is (= 0 @order-form-calls))
            (is (= 1 @account-info-calls))
            (is (= 0 @equity-metrics-calls))
            (is (= 0 @account-equity-calls))
            (is (= 0 (count orderbook-nodes)))
            (is (= 0 (count order-form-nodes)))
            (is (= 0 (count account-equity-nodes)))))))))

(deftest trade-view-primary-mobile-tabs-route-to-market-surfaces-test
  (let [view-node (trade-view/trade-view trade-view-test-state)
        top-buttons (find-nodes view-node #(str/starts-with? (get-in % [1 :data-role] "")
                                                             "trade-mobile-surface-button-"))
        chart-button (find-first-node view-node #(= "trade-mobile-surface-button-chart"
                                                    (get-in % [1 :data-role])))
        orderbook-button (find-first-node view-node #(= "trade-mobile-surface-button-orderbook"
                                                        (get-in % [1 :data-role])))
        trades-button (find-first-node view-node #(= "trade-mobile-surface-button-trades"
                                                     (get-in % [1 :data-role])))]
    (is (= 3 (count top-buttons)))
    (is (= "Chart" (node-text chart-button)))
    (is (= "Order Book" (node-text orderbook-button)))
    (is (= "Trades" (node-text trades-button)))
    (is (= [[:actions/select-trade-mobile-surface :chart]]
           (get-in chart-button [1 :on :click])))
    (is (= [[:actions/select-trade-mobile-surface :orderbook]]
           (get-in orderbook-button [1 :on :click])))
    (is (= [[:actions/select-trade-mobile-surface :trades]]
           (get-in trades-button [1 :on :click])))
    (is (nil? (find-first-node view-node #(= "trade-mobile-surface-button-ticket"
                                             (get-in % [1 :data-role])))))
    (is (nil? (find-first-node view-node #(= "trade-mobile-surface-button-account"
                                             (get-in % [1 :data-role])))))))

(deftest trade-view-reads-runtime-health-snapshot-for-surface-freshness-cues-test
  (let [state (-> trade-view-test-state
                  (assoc :active-asset "BTC")
                  (assoc-in [:websocket :health]
                            {:generated-at-ms 5000
                             :streams {["l2Book" "BTC" nil nil nil]
                                       {:topic "l2Book"
                                        :status :live
                                        :subscribed? true
                                        :last-payload-at-ms 4900
                                        :stale-threshold-ms 5000}}})
                  (assoc-in [:websocket-ui :show-surface-freshness-cues?] true)
                  (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}]
                                            :asks [{:px "101" :sz "1"}]}}))
        view-node (trade-view/trade-view state)
        cue-node (find-first-node view-node
                                  #(= "orderbook-freshness-cue"
                                      (get-in % [1 :data-role])))]
    (is (some? cue-node))
    (is (some #(str/includes? % "Updated")
              (collect-strings cue-node)))))

(deftest footer-view-uses-app-shell-gutter-test
  (let [view-node (footer-view/footer-view {:websocket {:status :connected}})]
    (is (contains-class? view-node "app-shell-gutter"))
    (is (contains-class? view-node "fixed"))
    (is (contains-class? view-node "inset-x-0"))
    (is (contains-class? view-node "bottom-0"))
    (is (contains-class? view-node "z-40"))
    (is (contains-class? view-node "bg-base-200"))
    (is (contains-class? view-node "isolate"))))

(deftest app-view-root-hides-scrollbar-with-trade-xl-scroll-lock-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}))
        root-classes (root-class-set view-node)]
    (is (contains? root-classes "overflow-y-auto"))
    (is (contains? root-classes "scrollbar-hide"))
    (is (contains? root-classes "xl:overflow-y-hidden"))))

(deftest app-view-root-keeps-non-trade-scroll-policy-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/vaults"}
                                            :wallet {}))
        root-classes (root-class-set view-node)]
    (is (contains? root-classes "overflow-y-auto"))
    (is (contains? root-classes "scrollbar-hide"))
    (is (not (contains? root-classes "xl:overflow-y-hidden")))))

(deftest app-view-uses-compact-footer-reserve-for-trade-account-surface-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :trade-ui {:mobile-surface :account}))
        app-main (find-first-node view-node
                                  #(= "app-main"
                                      (get-in % [1 :data-parity-id])))
        app-main-classes (node-class-set app-main)]
    (is (contains? app-main-classes "pb-[calc(3rem+env(safe-area-inset-bottom))]"))
    (is (not (contains? app-main-classes "pb-[5rem]")))))

(deftest app-view-keeps-standard-footer-reserve-for-trade-market-surface-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :trade-ui {:mobile-surface :chart}))
        app-main (find-first-node view-node
                                  #(= "app-main"
                                      (get-in % [1 :data-parity-id])))
        app-main-classes (node-class-set app-main)]
    (is (contains? app-main-classes "pb-[5rem]"))
    (is (not (contains? app-main-classes "pb-[calc(3rem+env(safe-area-inset-bottom))]")))))

(deftest app-view-renders-portfolio-route-with-portfolio-root-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/portfolio"}
                                            :wallet {}))
        portfolio-root (find-first-node view-node
                                        #(= "portfolio-root"
                                            (get-in % [1 :data-parity-id])))
        trade-root (find-first-node view-node
                                    #(= "trade-root"
                                        (get-in % [1 :data-parity-id])))]
    (is (some? portfolio-root))
    (is (nil? trade-root))))

(deftest app-view-renders-funding-comparison-route-with-funding-root-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/funding-comparison"}
                                            :wallet {}
                                            :funding-comparison-ui {:query ""
                                                                    :timeframe :8hour
                                                                    :sort {:column :coin
                                                                           :direction :asc}
                                                                    :loading? false}
                                            :funding-comparison {:predicted-fundings []
                                                                 :error nil
                                                                 :loaded-at-ms nil}
                                            :asset-selector {:favorites #{}
                                                             :market-by-key {}}))
        funding-root (find-first-node view-node
                                      #(= "funding-comparison-root"
                                          (get-in % [1 :data-parity-id])))]
    (is (some? funding-root))))

(deftest app-view-renders-api-wallet-route-with-api-wallet-root-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/API"}
                                            :wallet {}
                                            :api-wallets-ui {:form {:name ""
                                                                    :address ""
                                                                    :days-valid ""}
                                                             :form-error nil
                                                             :sort {:column :name
                                                                    :direction :asc}
                                                             :modal {:open? false
                                                                     :type nil
                                                                     :row nil
                                                                     :error nil
                                                                     :submitting? false}
                                                             :generated {:address nil
                                                                         :private-key nil}}
                                            :api-wallets {:extra-agents []
                                                          :default-agent-row nil
                                                          :owner-webdata2 nil
                                                          :server-time-ms nil
                                                          :loading {:extra-agents? false
                                                                    :default-agent? false}
                                                          :errors {:extra-agents nil
                                                                   :default-agent nil}
                                                          :loaded-at-ms {:extra-agents nil
                                                                         :default-agent nil}}))
        api-root (find-first-node view-node
                                  #(= "api-wallets-root"
                                      (get-in % [1 :data-parity-id])))]
    (is (some? api-root))))

(deftest app-view-renders-vault-routes-with-vault-roots-test
  (let [list-view (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/vaults"}
                                            :wallet {}
                                            :vaults-ui {:search-query ""
                                                        :filter-leading? true
                                                        :filter-deposited? true
                                                        :filter-others? true
                                                        :filter-closed? false
                                                        :snapshot-range :month
                                                        :sort {:column :tvl
                                                               :direction :desc}}
                                            :vaults {:loading {:index? false
                                                               :summaries? false}
                                                     :errors {:index nil
                                                              :summaries nil}
                                                     :user-equity-by-address {}
                                                     :merged-index-rows []}))
        detail-view (app-view/app-view (assoc trade-view-test-state
                                              :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}
                                              :wallet {}
                                              :vaults-ui {:detail-tab :about
                                                          :snapshot-range :month
                                                          :detail-loading? false}
                                              :vaults {:errors {:details-by-address {}
                                                                :webdata-by-vault {}}
                                                       :details-by-address {}
                                                       :webdata-by-vault {}
                                                       :user-equity-by-address {}
                                                       :merged-index-rows []}))
        list-root (find-first-node list-view #(= "vaults-root"
                                                 (get-in % [1 :data-parity-id])))
        detail-root (find-first-node detail-view #(= "vault-detail-root"
                                                     (get-in % [1 :data-parity-id])))]
    (is (some? list-root))
    (is (some? detail-root))))

(deftest app-view-renders-global-order-feedback-toast-when-present-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :ui {:toast {:kind :success
                                                         :message "Order submitted."}}))
        toast-node (find-first-node view-node
                                    #(= "global-toast" (get-in % [1 :data-role])))]
    (is (some? toast-node))
    (is (contains? (set (collect-strings toast-node))
                   "Order submitted."))))

(deftest app-view-renders-stacked-order-feedback-toasts-and-dismiss-actions-test
  (let [view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :ui {:toast {:kind :success
                                                         :message "Sold 1.25 SOL"}
                                                 :toasts [{:id "toast-1"
                                                           :kind :success
                                                           :headline "Bought 6 HYPE"
                                                           :subline "At average price of $31.66667"
                                                           :message "Bought 6 HYPE"}
                                                          {:id "toast-2"
                                                           :kind :success
                                                           :headline "Sold 1.25 SOL"
                                                           :subline "At average price of $90.79"
                                                           :message "Sold 1.25 SOL"}]}))
        toast-nodes (find-nodes view-node #(= "global-toast" (get-in % [1 :data-role])))
        dismiss-nodes (find-nodes view-node #(= "global-toast-dismiss" (get-in % [1 :data-role])))
        toast-class-sets (mapv #(set (get-in % [1 :class])) toast-nodes)
        rendered-strings (set (collect-strings view-node))]
    (is (= 2 (count toast-nodes)))
    (is (= 2 (count dismiss-nodes)))
    (is (every? #(contains? % "bg-[#081b24]/95") toast-class-sets))
    (is (every? #(contains? % "border-[#1f4f4f]") toast-class-sets))
    (is (contains? rendered-strings "Bought 6 HYPE"))
    (is (contains? rendered-strings "Sold 1.25 SOL"))
    (is (contains? rendered-strings "At average price of $31.66667"))
    (is (contains? rendered-strings "At average price of $90.79"))
    (is (= [[:actions/dismiss-order-feedback-toast "toast-1"]]
           (get-in (first dismiss-nodes) [1 :on :click])))
    (is (= [[:actions/dismiss-order-feedback-toast "toast-2"]]
           (get-in (second dismiss-nodes) [1 :on :click])))))

(deftest app-view-renders-spectate-mode-banner-when-active-test
  (let [address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :account-context {:spectate-mode {:active? true
                                                                           :address address}
                                                              :spectate-ui {:modal-open? false}
                                                              :watchlist []}))
        banner-node (find-first-node view-node
                                     #(= "spectate-mode-active-banner"
                                         (get-in % [1 :data-role])))
        manage-button (find-first-node view-node
                                       #(= "spectate-mode-banner-manage"
                                           (get-in % [1 :data-role])))
        stop-button (find-first-node view-node
                                     #(= "spectate-mode-banner-stop"
                                         (get-in % [1 :data-role])))]
    (is (some? banner-node))
    (is (contains? (set (collect-strings banner-node)) "Spectate Mode"))
    (is (contains? (set (collect-strings banner-node)) "Currently spectating"))
    (is (= [[:actions/open-spectate-mode-modal :event.currentTarget/bounds]]
           (get-in manage-button [1 :on :click])))
    (is (= [[:actions/stop-spectate-mode]]
           (get-in stop-button [1 :on :click])))))

(deftest app-view-renders-spectate-mode-modal-and-stop-control-when-open-and-active-test
  (let [address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        label "The Assistance Fund"
        view-node (app-view/app-view (assoc trade-view-test-state
                                            :router {:path "/trade"}
                                            :wallet {:copy-feedback {:kind :success
                                                                     :message "Spectate link copied to clipboard"}}
                                            :account-context {:spectate-mode {:active? true
                                                                           :address address}
                                                              :spectate-ui {:modal-open? true
                                                                         :search address
                                                                         :label ""
                                                                         :search-error nil}
                                                              :watchlist [{:address address
                                                                           :label label}]}))
        modal-root (find-first-node view-node
                                    #(= "spectate-mode-modal-root"
                                        (get-in % [1 :data-role])))
        watchlist-row (find-first-node view-node
                                       #(= "spectate-mode-watchlist-row"
                                           (get-in % [1 :data-role])))
        watchlist-label (find-first-node view-node
                                         #(= "spectate-mode-watchlist-label"
                                             (get-in % [1 :data-role])))
        close-button (find-first-node view-node
                                      #(= "spectate-mode-close"
                                          (get-in % [1 :data-role])))
        stop-button (find-first-node view-node
                                     #(= "spectate-mode-stop"
                                         (get-in % [1 :data-role])))
        copy-button (find-first-node view-node
                                     #(= "spectate-mode-watchlist-copy"
                                         (get-in % [1 :data-role])))
        edit-button (find-first-node view-node
                                     #(= "spectate-mode-watchlist-edit"
                                         (get-in % [1 :data-role])))
        link-button (find-first-node view-node
                                     #(= "spectate-mode-watchlist-link"
                                         (get-in % [1 :data-role])))
        copy-feedback-row (find-first-node view-node
                                           #(= "spectate-mode-copy-feedback"
                                               (get-in % [1 :data-role])))
        rendered-strings (set (collect-strings modal-root))]
    (is (some? modal-root))
    (is (some? watchlist-row))
    (is (some? watchlist-label))
    (is (some? close-button))
    (is (contains? (set (collect-strings modal-root)) "Spectate Mode"))
    (is (contains? (set (collect-strings modal-root)) "Currently spectating: "))
    (is (contains? rendered-strings address))
    (is (contains? rendered-strings label))
    (is (contains? rendered-strings "Spectate link copied to clipboard"))
    (is (some? copy-feedback-row))
    (is (not-any? #(str/starts-with? % "[[:li")
                  rendered-strings))
    (is (= [[:actions/close-spectate-mode-modal]]
           (get-in close-button [1 :on :click])))
    (is (= [[:actions/stop-spectate-mode]]
           (get-in stop-button [1 :on :click])))
    (is (= [[:actions/copy-spectate-mode-watchlist-address address]]
           (get-in copy-button [1 :on :click])))
    (is (= [[:actions/edit-spectate-mode-watchlist-address address]]
           (get-in edit-button [1 :on :click])))
    (is (= [[:actions/copy-spectate-mode-watchlist-link address]]
           (get-in link-button [1 :on :click])))))
