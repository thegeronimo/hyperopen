(ns hyperopen.api.market-loader-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.market-loader :as market-loader]))

(deftest request-asset-selector-markets-bootstrap-uses-high-priority-and-default-dex-test
  (async done
    (let [ensure-perp-calls (atom [])
          ensure-spot-calls (atom [])
          ensure-webdata-calls (atom [])
          meta-calls (atom [])
          build-calls (atom [])
          deps {:active-asset "BTC"
                :opts {:phase :bootstrap}
                :ensure-perp-dexs-data! (fn [opts]
                                          (swap! ensure-perp-calls conj opts)
                                          (js/Promise.resolve ["dex-a"]))
                :ensure-spot-meta-data! (fn [opts]
                                          (swap! ensure-spot-calls conj opts)
                                          (js/Promise.resolve {:tokens []}))
                :ensure-public-webdata2! (fn [opts]
                                           (swap! ensure-webdata-calls conj opts)
                                           (js/Promise.resolve {:spotAssetCtxs [{:coin "BTC"}]}))
                :request-meta-and-asset-ctxs! (fn [dex opts]
                                                (swap! meta-calls conj [dex opts])
                                                (js/Promise.resolve [{} []]))
                :build-market-state (fn [active-asset phase dexs spot-meta spot-asset-ctxs perp-results]
                                      (swap! build-calls conj [active-asset phase dexs spot-meta spot-asset-ctxs perp-results])
                                      {:markets [{:coin "BTC"}]})
                :log-fn (fn [& _] nil)}]
      (-> (market-loader/request-asset-selector-markets! deps)
          (.then (fn [result]
                   (is (= {:phase :bootstrap
                           :market-state {:markets [{:coin "BTC"}]}}
                          result))
                   (is (= [{:priority :high}] @ensure-perp-calls))
                   (is (= [{:priority :high}] @ensure-spot-calls))
                   (is (= [{:priority :high}] @ensure-webdata-calls))
                   (is (= [[nil {:priority :high}]] @meta-calls))
                   (is (= 1 (count @build-calls)))
                   (is (= "BTC" (first (first @build-calls))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-asset-selector-markets-full-phase-loads-default-and-named-dex-test
  (async done
    (let [meta-calls (atom [])
          deps {:active-asset "ETH"
                :opts {:phase :full}
                :ensure-perp-dexs-data! (fn [_opts]
                                          (js/Promise.resolve ["dex-a" "dex-b"]))
                :ensure-spot-meta-data! (fn [_opts]
                                          (js/Promise.resolve {:tokens []}))
                :ensure-public-webdata2! (fn [_opts]
                                           (js/Promise.resolve {:spotAssetCtxs []}))
                :request-meta-and-asset-ctxs! (fn [dex opts]
                                                (swap! meta-calls conj [dex opts])
                                                (js/Promise.resolve [{} []]))
                :build-market-state (fn [_active-asset _phase _dexs _spot-meta _spot-asset-ctxs _perp-results]
                                      {:markets []})
                :log-fn (fn [& _] nil)}]
      (-> (market-loader/request-asset-selector-markets! deps)
          (.then (fn [result]
                   (is (= :full (:phase result)))
                   (is (= [[nil {:priority :low}]
                           ["dex-a" {:priority :low}]
                           ["dex-b" {:priority :low}]]
                          @meta-calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
