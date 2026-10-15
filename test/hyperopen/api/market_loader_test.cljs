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
                           :spot-meta {}
                           :market-state {:markets [{:coin "BTC"}]}}
                          result))
                   (is (= [] @ensure-perp-calls))
                   ;; Bootstrap is perp-only: the spot/webdata2/outcome catalogs
                   ;; are demand-path (:full) fetches, not boot work.
                   (is (= [] @ensure-spot-calls))
                   (is (= [] @ensure-outcome-calls))
                   (is (= [] @ensure-webdata-calls))
                   (is (= [[nil {:priority :high
                                 :dedupe-key :asset-contexts}]]
                          @meta-calls))
                   (is (= 1 (count @build-calls)))
                   (is (= "BTC" (first (first @build-calls))))
                   (is (= [] (nth (first @build-calls) 2)))
                   (is (= {} (nth (first @build-calls) 3)))
                   (is (= nil (nth (first @build-calls) 4)))
                   (is (= {:outcomes [] :questions []} (nth (first @build-calls) 6)))
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

(defn- full-phase-deps
  [request-perp-annotations!]
  {:active-asset "xyz:HO"
   :opts {:phase :full}
   :ensure-perp-dexs-data! (fn [_opts] (js/Promise.resolve ["xyz"]))
   :ensure-spot-meta-data! (fn [_opts] (js/Promise.resolve {:tokens []}))
   :ensure-outcome-meta-data! (fn [_opts] (js/Promise.resolve {:outcomes []}))
   :ensure-public-webdata2! (fn [_opts] (js/Promise.resolve {:spotAssetCtxs []}))
   :request-perp-annotations! request-perp-annotations!
   :request-meta-and-asset-ctxs! (fn [_dex _opts] (js/Promise.resolve [{} []]))
   :build-market-state (fn [& _]
                         (let [market {:key "perp:xyz:HO" :coin "xyz:HO" :symbol "HO-USDC"
                                       :quote "USDC" :market-type :perp :hip3? true}]
                           {:markets [market]
                            :market-by-key {"perp:xyz:HO" market}
                            :active-market market}))
   :log-fn (fn [& _] nil)})

(deftest request-asset-selector-markets-full-phase-applies-perp-annotations-test
  (async done
    (let [annotation-calls (atom [])]
      (-> (market-loader/request-asset-selector-markets!
           (full-phase-deps (fn [opts]
                              (swap! annotation-calls conj opts)
                              (js/Promise.resolve {"xyz:HO" {:display-name "DIESEL"}}))))
          (.then (fn [{:keys [market-state]}]
                   (is (= [{:priority :low}] @annotation-calls))
                   (is (= "DIESEL-USDC" (get-in market-state [:markets 0 :display-symbol])))
                   (is (= "DIESEL-USDC" (get-in market-state [:market-by-key "perp:xyz:HO" :display-symbol])))
                   (is (= "DIESEL-USDC" (get-in market-state [:active-market :display-symbol])))
                   (is (= "HO-USDC" (get-in market-state [:active-market :symbol])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-asset-selector-markets-annotation-failure-keeps-catalog-test
  (async done
    (-> (market-loader/request-asset-selector-markets!
         (full-phase-deps (fn [_opts] (js/Promise.reject (js/Error. "boom")))))
        (.then (fn [{:keys [market-state]}]
                 (is (= "HO-USDC" (get-in market-state [:markets 0 :symbol])))
                 (is (nil? (get-in market-state [:markets 0 :display-symbol])))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Unexpected error: " err))
                  (done))))))
