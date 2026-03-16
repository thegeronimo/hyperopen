(ns hyperopen.trade-modules-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [shadow.loader :as loader]
            [hyperopen.trade-modules :as trade-modules]))

(deftest trade-chart-state-helpers-track-loading-loaded-and-failure-test
  (let [state {:trade-modules (trade-modules/default-state)}
        loading-state (trade-modules/mark-trade-chart-loading state)
        loaded-state (trade-modules/mark-trade-chart-loaded loading-state)
        failed-state (trade-modules/mark-trade-chart-failed state (js/Error. "boom"))]
    (is (true? (trade-modules/trade-chart-loading? loading-state)))
    (is (true? (get-in loaded-state [:trade-modules :chart :loaded?])))
    (is (false? (trade-modules/trade-chart-loading? loaded-state)))
    (is (= "boom" (trade-modules/trade-chart-error failed-state)))))

(deftest trade-chart-ready-requires-a-resolved-exported-view-test
  (with-redefs [trade-modules/resolved-trade-chart-view (fn [] nil)
                hyperopen.trade-modules/resolve-exported-view (fn [] nil)]
    (is (false? (trade-modules/trade-chart-ready? {:trade-modules {:chart {:loaded? true}}})))))

(deftest load-trade-chart-module-fails-when-exported-view-is-missing-test
  (async done
    (let [store (atom {:trade-modules (trade-modules/default-state)})]
      (with-redefs [loader/loaded? (constantly true)
                    trade-modules/resolved-trade-chart-view (fn [] nil)
                    hyperopen.trade-modules/resolve-exported-view (fn [] nil)]
        (let [load-promise (trade-modules/load-trade-chart-module! store)]
          (is (false? (trade-modules/trade-chart-ready? @store)))
          (-> load-promise
              (.then (fn [_result]
                       (is false "expected trade chart module load to reject when the exported view is unresolved")
                       (done)))
              (.catch (fn [err]
                        (is (= "Loaded trade chart module without exported view."
                               (.-message err)))
                        (is (= "Loaded trade chart module without exported view."
                               (trade-modules/trade-chart-error @store)))
                        (done)))))))))
