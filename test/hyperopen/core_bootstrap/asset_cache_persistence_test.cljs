(ns hyperopen.core-bootstrap.asset-cache-persistence-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.asset-selector.markets-cache :as markets-cache]
            [hyperopen.core :as app-core]
            [hyperopen.core.compat :as core]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.startup.watchers :as startup-watchers]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]
            [hyperopen.core-bootstrap.test-support.fixtures :as fixtures]
            [hyperopen.test-support.async :as async-support]))

(use-fixtures :once fixtures/runtime-bootstrap-fixture)
(use-fixtures :each fixtures/per-test-runtime-fixture)

(def with-test-local-storage browser-mocks/with-test-local-storage)
(def with-test-indexed-db browser-mocks/with-test-indexed-db)

(deftest restore-active-asset-hydrates-cached-active-market-display-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "active-asset" "ETH")
      (.setItem js/localStorage
                "active-market-display"
                (js/JSON.stringify
                 (clj->js {:coin "ETH"
                           :symbol "ETH-USDC"
                           :base "ETH"
                           :quote "USDC"
                           :market-type "perp"
                           :dex "hyna"
                           :maxLeverage "25"})))
      (let [store (atom {:active-asset nil
                         :selected-asset nil
                         :active-market nil})]
        (with-redefs [ws-client/connected? (fn [] false)]
          (core/restore-active-asset! store))
        (is (= "ETH" (:active-asset @store)))
        (is (= "ETH-USDC" (get-in @store [:active-market :symbol])))
        (is (= :perp (get-in @store [:active-market :market-type])))
        (is (= 25 (get-in @store [:active-market :maxLeverage])))))))

(deftest restore-active-asset-ignores-mismatched-cached-market-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "active-asset" "ETH")
      (.setItem js/localStorage
                "active-market-display"
                (js/JSON.stringify (clj->js {:coin "BTC"
                                             :symbol "BTC-USDC"
                                             :market-type "perp"
                                             :maxLeverage 40})))
      (let [store (atom {:active-asset nil
                         :selected-asset nil
                         :active-market nil})]
        (with-redefs [ws-client/connected? (fn [] false)]
          (core/restore-active-asset! store))
        (is (= "ETH" (:active-asset @store)))
        (is (nil? (:active-market @store)))))))

(deftest restore-active-asset-prefers-trade-route-asset-over-local-storage-test
  (with-test-local-storage
    (fn []
      (.setItem js/localStorage "active-asset" "ETH")
      (let [store (atom {:router {:path "/trade/xyz:GOLD"}
                         :active-asset nil
                         :selected-asset nil
                         :active-market nil})]
        (with-redefs [ws-client/connected? (fn [] false)]
          (core/restore-active-asset! store))
        (is (= "xyz:GOLD" (:active-asset @store)))
        (is (= "xyz:GOLD" (:selected-asset @store)))
        (is (= "xyz:GOLD" (.getItem js/localStorage "active-asset")))))))

(deftest subscribe-active-asset-persists-active-market-display-cache-test
  (with-test-local-storage
    (fn []
      (let [market {:key "perp:ETH"
                    :coin "ETH"
                    :symbol "ETH-USDC"
                    :base "ETH"
                    :quote "USDC"
                    :market-type :perp
                    :maxLeverage 25}
            store (atom {:asset-selector {:market-by-key {"perp:ETH" market}}
                         :chart-options {:selected-timeframe :1d}
                         :active-assets {:contexts {}
                                         :loading false}
                         :active-market nil})]
        (with-redefs [active-ctx/subscribe-active-asset-ctx! (fn [_] nil)
                      effect-adapters/fetch-candle-snapshot (fn [& _] nil)]
          (core/subscribe-active-asset nil store "ETH"))
        (let [cached (js->clj (js/JSON.parse (.getItem js/localStorage "active-market-display"))
                              :keywordize-keys true)]
          (is (= "ETH" (:coin cached)))
          (is (= "ETH-USDC" (:symbol cached)))
          (is (= "perp" (:market-type cached)))
          (is (= 25 (:maxLeverage cached))))))))

(deftest active-market-store-projection-persists-display-cache-test
  (with-test-local-storage
    (fn []
      (let [original-state @app-core/store
            market {:key "perp:ETH"
                    :coin "ETH"
                    :symbol "ETH-USDC"
                    :base "ETH"
                    :quote "USDC"
                    :market-type :perp
                    :dex "hyna"
                    :maxLeverage 25}]
        (try
          (swap! app-core/store assoc :active-market nil)
          (swap! app-core/store assoc :active-market market)
          (let [cached (js->clj (js/JSON.parse (.getItem js/localStorage "active-market-display"))
                                :keywordize-keys true)]
            (is (= "ETH" (:coin cached)))
            (is (= "ETH-USDC" (:symbol cached)))
            (is (= "perp" (:market-type cached)))
            (is (= 25 (:maxLeverage cached))))
          (finally
            (reset! app-core/store original-state)))))))

(deftest restore-asset-selector-markets-cache-hydrates-symbols-test
  (async done
    (with-test-indexed-db
      (fn []
        (let [store (atom {:active-asset "ETH"
                           :active-market nil
                           :asset-selector {:markets []
                                            :market-by-key {}
                                            :phase :bootstrap}})
              cached-markets [{:key "perp:ETH"
                               :coin "ETH"
                               :symbol "ETH-USDC"
                               :base "ETH"
                               :quote "USDC"
                               :market-type :perp
                               :category :crypto
                               :hip3? false
                               :maxLeverage 25}
                              {:key "spot:PURR/USDC"
                               :coin "PURR/USDC"
                               :symbol "PURR/USDC"
                               :base "PURR"
                               :quote "USDC"
                               :market-type :spot
                               :category :spot
                               :hip3? false}]]
          (-> (markets-cache/persist-asset-selector-markets-cache! cached-markets
                                                                   {:asset-selector {:sort-by :volume
                                                                                     :sort-direction :desc}})
              (.then (fn [_]
                       (-> (core/restore-asset-selector-markets-cache! store)
                           (.then (fn [_]
                                    (is (= 2 (count (get-in @store [:asset-selector :markets]))))
                                    (is (= :perp (get-in @store [:asset-selector :market-by-key "perp:ETH" :market-type])))
                                    (is (= :spot (get-in @store [:asset-selector :market-by-key "spot:PURR/USDC" :market-type])))
                                    (is (= "ETH-USDC" (get-in @store [:asset-selector :market-by-key "perp:ETH" :symbol])))
                                    (is (= "ETH" (get-in @store [:active-market :coin])))
                                    (is (= 25 (get-in @store [:active-market :maxLeverage])))
                                    (is (= true (get-in @store [:asset-selector :cache-hydrated?])))
                                    (done)))
                           (.catch (async-support/unexpected-error done)))))
              (.catch (async-support/unexpected-error done))))))))

(deftest restore-asset-selector-markets-cache-keeps-existing-markets-test
  (async done
    (with-test-indexed-db
      (fn []
        (let [existing-market {:key "perp:BTC"
                               :coin "BTC"
                               :symbol "BTC-USDC"
                               :base "BTC"
                               :market-type :perp}
              store (atom {:asset-selector {:markets [existing-market]
                                            :market-by-key {"perp:BTC" existing-market}
                                            :phase :full}})]
          (-> (markets-cache/persist-asset-selector-markets-cache!
               [{:key "perp:ETH"
                 :coin "ETH"
                 :symbol "ETH-USDC"
                 :base "ETH"
                 :market-type :perp}]
               {:asset-selector {:sort-by :volume
                                 :sort-direction :desc}})
              (.then (fn [_]
                       (-> (core/restore-asset-selector-markets-cache! store)
                           (.then (fn [_]
                                    (is (= [existing-market]
                                           (get-in @store [:asset-selector :markets])))
                                    (is (= {"perp:BTC" existing-market}
                                           (get-in @store [:asset-selector :market-by-key])))
                                    (done)))
                           (.catch (async-support/unexpected-error done)))))
              (.catch (async-support/unexpected-error done))))))))

(deftest asset-selector-markets-store-projection-persists-cache-test
  (async done
    (with-test-indexed-db
      (fn []
        (js/Promise.
         (fn [resolve reject]
           (let [original-state @app-core/store
                 markets [{:key "perp:ETH"
                           :coin "ETH"
                           :symbol "ETH-USDC"
                           :base "ETH"
                           :quote "USDC"
                           :market-type :perp
                           :category :crypto
                           :hip3? false
                           :mark 1900.1
                           :volume24h 1000}
                          {:key "spot:PURR/USDC"
                           :coin "PURR/USDC"
                           :symbol "PURR/USDC"
                           :base "PURR"
                           :quote "USDC"
                           :market-type :spot
                           :category :spot
                           :mark 0.21
                           :volume24h 2000}]
                 fail! (fn [error]
                         (reset! app-core/store original-state)
                         ((async-support/unexpected-error done) error)
                         (reject error))]
             (try
               (startup-watchers/install-store-cache-watchers!
                app-core/store
                {:persist-active-market-display! (fn [_] nil)
                 :persist-asset-selector-markets-cache! effect-adapters/persist-asset-selector-markets-cache!
                 :request-animation-frame! (fn [f]
                                             (f 0)
                                             :frame-id)})
               (swap! app-core/store assoc-in [:asset-selector :sort-by] :volume)
               (swap! app-core/store assoc-in [:asset-selector :sort-direction] :desc)
               (swap! app-core/store assoc-in [:asset-selector :markets] [])
               (swap! app-core/store assoc-in [:asset-selector :market-by-key] {})
               (swap! app-core/store (fn [state]
                                       (-> state
                                           (assoc-in [:asset-selector :loaded-at-ms] 123)
                                           (assoc-in [:asset-selector :markets] markets))))
               (js/setTimeout
                (fn []
                  (-> (markets-cache/load-asset-selector-markets-cache)
                      (.then (fn [cached]
                               (is (= 2 (count cached)))
                               (is (= "PURR/USDC" (:symbol (first cached))))
                               (is (= "ETH-USDC" (:symbol (second cached))))
                               (is (= :spot (:market-type (first cached))))
                               (is (= [0 1] (mapv :cache-order cached)))
                               (is (nil? (:mark (first cached))))
                               (reset! app-core/store original-state)
                               (done)
                               (resolve true)))
                      (.catch fail!)))
                20)
               (catch :default e
                 (reset! app-core/store original-state)
                 (reject e))))))))))
