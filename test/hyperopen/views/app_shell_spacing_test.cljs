(ns hyperopen.views.app-shell-spacing-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.footer-view :as footer-view]
            [hyperopen.views.header-view :as header-view]
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

(defn- root-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))]
    (set (class-values (:class attrs)))))

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

(deftest header-view-uses-app-shell-gutter-test
  (let [view-node (header-view/header-view {:wallet {}})]
    (is (contains-class? view-node "app-shell-gutter"))))

(deftest trade-view-does-not-use-app-shell-gutter-test
  (let [view-node (trade-view/trade-view trade-view-test-state)]
    (is (not (contains-class? view-node "app-shell-gutter")))))

(deftest trade-view-root-and-right-column-layout-test
  (let [view-node (trade-view/trade-view trade-view-test-state)
        root-classes (root-class-set view-node)]
    (is (not (contains? root-classes "overflow-auto")))
    (is (contains? root-classes "min-h-0"))
    (is (contains-class? view-node "right-[340px]"))
    (is (contains-class? view-node "lg:grid-cols-[minmax(0,1fr)_340px]"))
    (is (contains-class? view-node "xl:grid-cols-[minmax(0,1fr)_340px_340px]"))
    (is (contains-class? view-node "xl:row-span-2"))
    (is (not (contains-class? view-node "xl:row-start-2")))))

(deftest footer-view-uses-app-shell-gutter-test
  (let [view-node (footer-view/footer-view {:websocket {:status :connected}})]
    (is (contains-class? view-node "app-shell-gutter"))
    (is (contains-class? view-node "sticky"))
    (is (contains-class? view-node "bottom-0"))
    (is (contains-class? view-node "z-40"))
    (is (contains-class? view-node "bg-base-200"))
    (is (contains-class? view-node "isolate"))))
