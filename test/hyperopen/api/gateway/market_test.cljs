(ns hyperopen.api.gateway.market-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.gateway.market :as market-gateway]))

(deftest request-candle-snapshot-builds-interval-window-test
  (async done
    (let [calls (atom [])
          deps {:post-info! (fn [body opts]
                              (swap! calls conj {:body body :opts opts})
                              (js/Promise.resolve []))
                :now-ms-fn (fn [] 10000000)}]
      (-> (market-gateway/request-candle-snapshot!
           deps
           "BTC"
           {:interval :1m
            :bars 2
            :priority :low})
          (.then (fn [_]
                   (is (= {"type" "candleSnapshot"
                           "req" {"coin" "BTC"
                                  "interval" "1m"
                                  "startTime" 9880000
                                  "endTime" 10000000}}
                          (get-in @calls [0 :body])))
                   (is (= {:priority :low}
                          (get-in @calls [0 :opts])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-candle-snapshot-skips-nil-asset-test
  (async done
    (let [calls (atom 0)
          deps {:post-info! (fn [_body _opts]
                              (swap! calls inc)
                              (js/Promise.resolve []))
                :now-ms-fn (fn [] 10000000)}]
      (-> (market-gateway/request-candle-snapshot! deps nil {:interval :1m :bars 2})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-asset-selector-markets-delegates-to-loader-test
  (async done
    (let [store (atom {:active-asset nil})
          deps {:store store
                :opts {:phase :bootstrap}
                :ensure-perp-dexs-data! (fn [_store _opts] (js/Promise.resolve []))
                :ensure-spot-meta-data! (fn [_store _opts] (js/Promise.resolve {:tokens [] :universe []}))
                :ensure-public-webdata2! (fn [_opts] (js/Promise.resolve {:spotAssetCtxs []}))
                :fetch-meta-and-asset-ctxs! (fn [_dex _opts] (js/Promise.resolve [nil nil]))
                :build-market-state (fn [_store phase _dexs _spot-meta _spot-asset-ctxs _perp-results]
                                      {:phase phase
                                       :markets []})
                :log-fn (fn [& _] nil)}]
      (-> (market-gateway/request-asset-selector-markets! deps)
          (.then (fn [result]
                   (is (= :bootstrap (:phase result)))
                   (is (= {:phase :bootstrap :markets []}
                          (:market-state result)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
