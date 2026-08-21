(ns hyperopen.account-tab-modules-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [goog.object :as gobj]
            [shadow.loader :as loader]
            [hyperopen.account-tab-modules :as account-tab-modules]))

(defn- ensure-object-path!
  [path-segments]
  (let [root (or (some-> js/goog .-global)
                 js/globalThis)]
    (reduce (fn [acc segment]
              (or (gobj/get acc segment)
                  (let [child #js {}]
                    (gobj/set acc segment child)
                    child)))
            root
            path-segments)))

(defn- restore-export!
  [module-root export-key original-value]
  (if (some? original-value)
    (gobj/set module-root export-key original-value)
    (gobj/remove module-root export-key)))

(deftest load-account-tab-module-resolves-requested-secondary-renderer-test
  (async done
    (let [store (atom {:account-tab-modules (account-tab-modules/default-state)})
          module-root (ensure-object-path! ["hyperopen" "views" "account_orders_module"])
          open-orders-renderer (fn [_view-model] :open-orders)
          order-history-renderer (fn [_view-model] :order-history)
          original-open-orders-renderer (gobj/get module-root "open_orders_tab_renderer")
          original-order-history-renderer (gobj/get module-root "order_history_tab_renderer")
          restore-exports!
          (fn []
            (restore-export! module-root "open_orders_tab_renderer" original-open-orders-renderer)
            (restore-export! module-root "order_history_tab_renderer" original-order-history-renderer))]
      (account-tab-modules/reset-account-tab-module-state!)
      (restore-export! module-root "open_orders_tab_renderer" nil)
      (restore-export! module-root "order_history_tab_renderer" nil)
      (with-redefs [loader/loaded? (constantly false)
                    loader/load (fn [module-name]
                                  (is (= "account_orders" module-name))
                                  (gobj/set module-root "open_orders_tab_renderer" open-orders-renderer)
                                  (gobj/set module-root "order_history_tab_renderer" order-history-renderer)
                                  (.resolve js/Promise nil))]
        (-> (account-tab-modules/load-account-tab-module! store :order-history)
            (.then (fn [resolved]
                     (restore-exports!)
                     (account-tab-modules/reset-account-tab-module-state!)
                     (is (identical? order-history-renderer resolved))
                     (is (not (identical? open-orders-renderer resolved)))
                     (is (contains? (get-in @store [:account-tab-modules :loaded]) :orders))
                     (is (nil? (account-tab-modules/tab-error @store :order-history)))
                     (done)))
            (.catch (fn [err]
                      (restore-exports!)
                      (account-tab-modules/reset-account-tab-module-state!)
                      (is false (str "unexpected secondary account-tab load failure: " err))
                      (done))))))))

(deftest load-account-tab-module-fails-when-requested-secondary-export-is-missing-test
  (async done
    (let [store (atom {:account-tab-modules (account-tab-modules/default-state)})
          module-root (ensure-object-path! ["hyperopen" "views" "account_positions_outcomes_module"])
          positions-renderer (fn [_view-model] :positions)
          original-positions-renderer (gobj/get module-root "positions_tab_renderer")
          original-outcomes-renderer (gobj/get module-root "outcomes_tab_renderer")
          restore-exports!
          (fn []
            (restore-export! module-root "positions_tab_renderer" original-positions-renderer)
            (restore-export! module-root "outcomes_tab_renderer" original-outcomes-renderer))]
      (account-tab-modules/reset-account-tab-module-state!)
      (restore-export! module-root "positions_tab_renderer" nil)
      (restore-export! module-root "outcomes_tab_renderer" nil)
      (with-redefs [loader/loaded? (constantly false)
                    loader/load (fn [module-name]
                                  (is (= "account_positions_outcomes" module-name))
                                  (gobj/set module-root "positions_tab_renderer" positions-renderer)
                                  (gobj/remove module-root "outcomes_tab_renderer")
                                  (.resolve js/Promise nil))]
        (-> (account-tab-modules/load-account-tab-module! store :outcomes)
            (.then (fn [_result]
                     (restore-exports!)
                     (account-tab-modules/reset-account-tab-module-state!)
                     (is false "expected missing requested secondary account-tab export to reject")
                     (done)))
            (.catch (fn [err]
                      (restore-exports!)
                      (account-tab-modules/reset-account-tab-module-state!)
                      (is (= "Loaded account tab module without exported renderer: positions-outcomes/outcomes"
                             (.-message err)))
                      (is (= "Loaded account tab module without exported renderer: positions-outcomes/outcomes"
                             (account-tab-modules/tab-error @store :outcomes)))
                      (is (not (contains? (get-in @store [:account-tab-modules :loaded]) :positions-outcomes)))
                      (is (false? (account-tab-modules/tab-loading? @store :outcomes)))
                      (done))))))))

(deftest load-account-tab-module-records-failure-when-the-chunk-never-arrives-test
  (async done
    (let [store (atom {:account-tab-modules (account-tab-modules/default-state)})]
      (account-tab-modules/reset-account-tab-module-state!)
      (with-redefs [account-tab-modules/module-load-timeout-ms 20
                    loader/loaded? (constantly false)
                    ;; A stalled chunk: shadow's loader never settles this.
                    loader/load (fn [_module-name] (js/Promise. (fn [_ _])))]
        (-> (account-tab-modules/load-account-tab-module! store :open-orders)
            (.then (fn [_]
                     (is false "a stalled module load must not resolve")
                     (done))
                   (fn [_err]
                     (is (some? (account-tab-modules/tab-error @store :open-orders))
                         "a stalled module load must record a retryable error")
                     (is (not (contains? (get-in @store [:account-tab-modules :loading]) :orders))
                         "a failed module must not stay marked as loading")
                     (account-tab-modules/reset-account-tab-module-state!)
                     (done))))))))
