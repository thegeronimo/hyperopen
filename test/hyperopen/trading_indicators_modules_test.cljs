(ns hyperopen.trading-indicators-modules-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [goog.object :as gobj]
            [shadow.loader :as loader]
            [hyperopen.views.trading-chart.indicators-module]
            [hyperopen.trading-indicators-modules :as trading-indicators-modules]))

(defn- loader-thenable
  [value]
  #js {:then (fn [resolve _reject]
               (resolve value))})

(deftest trading-indicators-state-helpers-track-loading-loaded-and-failure-test
  (trading-indicators-modules/reset-trading-indicators-module-state!)
  (let [state {:trade-modules {:indicators (trading-indicators-modules/default-state)}}
        loading-state (trading-indicators-modules/mark-trading-indicators-loading state)
        loaded-state (trading-indicators-modules/mark-trading-indicators-loaded loading-state)
        failed-state (trading-indicators-modules/mark-trading-indicators-failed state (js/Error. "boom"))]
    (is (true? (trading-indicators-modules/trading-indicators-loading? loading-state)))
    (is (true? (get-in loaded-state [:trade-modules :indicators :loaded?])))
    (is (false? (trading-indicators-modules/trading-indicators-loading? loaded-state)))
    (is (= "boom" (trading-indicators-modules/trading-indicators-error failed-state))))
  (trading-indicators-modules/reset-trading-indicators-module-state!))

(deftest load-trading-indicators-module-fails-when-exported-helper-is-missing-test
  (async done
    (let [store (atom {:trade-modules {:indicators (trading-indicators-modules/default-state)}})
          root (or (some-> js/goog .-global)
                   js/globalThis)
          module-root (-> root
                          (gobj/get "hyperopen")
                          (gobj/get "views")
                          (gobj/get "trading_chart")
                          (gobj/get "indicators_module"))
          original-calculate-indicator (some-> module-root
                                              (gobj/get "calculateIndicator"))
          restore-export!
          (fn []
            (when module-root
              (gobj/set module-root "calculateIndicator" original-calculate-indicator)))]
      (trading-indicators-modules/reset-trading-indicators-module-state!)
      (if-not module-root
        (do
          (is false "expected indicators module exports to exist in the test runtime")
          (done))
        (do
          (gobj/set module-root "calculateIndicator" nil)
          (with-redefs [loader/loaded? (constantly true)]
            (let [load-promise (trading-indicators-modules/load-trading-indicators-module! store)]
              (is (false? (trading-indicators-modules/trading-indicators-ready? @store)))
              (-> load-promise
                  (.then (fn [_result]
                           (restore-export!)
                           (trading-indicators-modules/reset-trading-indicators-module-state!)
                           (is false "expected trading indicators module load to reject when exports are unresolved")
                           (done)))
                  (.catch (fn [err]
                            (restore-export!)
                            (trading-indicators-modules/reset-trading-indicators-module-state!)
                            (is (= "Loaded trading indicators module without exported helpers."
                                   (.-message err)))
                            (is (= "Loaded trading indicators module without exported helpers."
                                   (trading-indicators-modules/trading-indicators-error @store)))
                            (done)))))))))))

(deftest load-trading-indicators-module-handles-loader-thenables-without-finally-test
  (async done
    (let [store (atom {:trade-modules {:indicators (trading-indicators-modules/default-state)}})]
      (trading-indicators-modules/reset-trading-indicators-module-state!)
      (with-redefs [loader/loaded? (constantly false)
                    loader/load (fn [_]
                                  (loader-thenable nil))]
        (-> (trading-indicators-modules/load-trading-indicators-module! store)
            (.then (fn [resolved]
                     (trading-indicators-modules/reset-trading-indicators-module-state!)
                     (is (map? resolved))
                     (is (fn? (:calculate-indicator resolved)))
                     (is (true? (get-in @store [:trade-modules :indicators :loaded?])))
                     (done)))
            (.catch (fn [err]
                      (trading-indicators-modules/reset-trading-indicators-module-state!)
                      (is false (str "Expected thenable loader result to resolve, got: " err))
                      (done))))))))
