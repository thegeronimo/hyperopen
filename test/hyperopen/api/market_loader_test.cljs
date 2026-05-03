(ns hyperopen.api.market-loader-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.market-loader :as market-loader]))

(deftest request-asset-selector-markets-bootstrap-uses-high-priority-and-default-dex-test
  (async done
    (let [ensure-perp-calls (atom [])
          ensure-spot-calls (atom [])
          ensure-outcome-calls (atom [])
          ensure-webdata-calls (atom [])
          meta-calls (atom [])
          build-calls (atom [])
          deps {:active-asset "BTC"
                :opts {:phase :bootstrap}
                :ensure-perp-dexs-data! (fn [opts]
                                          (swap! ensure-perp-calls conj opts)
                                          (js/Promise.resolve {:dex-names ["dex-a"]
                                                               :fee-config-by-name {"dex-a" {:deployer-fee-scale 0.1}}}))
                :ensure-spot-meta-data! (fn [opts]
                                          (swap! ensure-spot-calls conj opts)
                                          (js/Promise.resolve {:tokens []}))
                :ensure-outcome-meta-data! (fn [opts]
                                             (swap! ensure-outcome-calls conj opts)
                                             (js/Promise.resolve {:outcomes [{:outcome 0}]}))
                :ensure-public-webdata2! (fn [opts]
                                           (swap! ensure-webdata-calls conj opts)
                                           (js/Promise.resolve {:spotAssetCtxs [{:coin "BTC"}]}))
                :request-meta-and-asset-ctxs! (fn [dex opts]
                                                (swap! meta-calls conj [dex opts])
                                                (js/Promise.resolve [{} []]))
                :build-market-state (fn [active-asset phase dexs spot-meta spot-asset-ctxs perp-results outcome-meta]
                                      (swap! build-calls conj [active-asset phase dexs spot-meta spot-asset-ctxs perp-results outcome-meta])
                                      {:markets [{:coin "BTC"}]})
                :log-fn (fn [& _] nil)}]
      (-> (market-loader/request-asset-selector-markets! deps)
          (.then (fn [result]
                   (is (= {:phase :bootstrap
                           :spot-meta {:tokens []}
                           :market-state {:markets [{:coin "BTC"}]}}
                          result))
                   (is (= [] @ensure-perp-calls))
                   (is (= [{:priority :high}] @ensure-spot-calls))
                   (is (= [{:priority :high}] @ensure-outcome-calls))
                   (is (= [{:priority :high}] @ensure-webdata-calls))
                   (is (= [[nil {:priority :high
                                 :dedupe-key :asset-contexts}]]
                          @meta-calls))
                   (is (= 1 (count @build-calls)))
                   (is (= "BTC" (first (first @build-calls))))
                   (is (= [] (nth (first @build-calls) 2)))
                   (is (= {:outcomes [{:outcome 0}]} (nth (first @build-calls) 6)))
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
                :ensure-outcome-meta-data! (fn [opts]
                                             (is (= {:priority :low} opts))
                                             (js/Promise.resolve {:outcomes [{:outcome 0}]}))
                :ensure-public-webdata2! (fn [_opts]
                                           (js/Promise.resolve {:spotAssetCtxs []}))
                :request-meta-and-asset-ctxs! (fn [dex opts]
                                                (swap! meta-calls conj [dex opts])
                                                (js/Promise.resolve [{} []]))
                :build-market-state (fn [_active-asset _phase _dexs _spot-meta _spot-asset-ctxs _perp-results outcome-meta]
                                      (is (= {:outcomes [{:outcome 0}]} outcome-meta))
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
