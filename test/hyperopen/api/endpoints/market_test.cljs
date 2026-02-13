(ns hyperopen.api.endpoints.market-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.market :as market]))

(deftest request-meta-and-asset-ctxs-builds-dedupe-keys-by-dex-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve []))]
    (market/request-meta-and-asset-ctxs! post-info! nil {})
    (market/request-meta-and-asset-ctxs! post-info! "vault" {})
    (is (= [{"type" "metaAndAssetCtxs"}
            {"type" "metaAndAssetCtxs" "dex" "vault"}]
           (mapv first @calls)))
    (is (= [:meta-and-asset-ctxs-default
            [:meta-and-asset-ctxs "vault"]]
           (mapv (comp :dedupe-key second) @calls)))))

(deftest request-perp-dexs-parses-named-dexes-test
  (async done
    (let [post-info! (fn [_body _opts]
                       (js/Promise.resolve [{:name "vault"}
                                            {:name ""}
                                            {:foo "bar"}
                                            {:name "partner"}]))]
      (-> (market/request-perp-dexs! post-info! {})
          (.then (fn [dexs]
                   (is (= ["vault" "partner"] dexs))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-candle-snapshot-builds-time-window-and-skips-missing-coin-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve []))
          now-ms-fn (fn [] 10000)]
      (-> (market/request-candle-snapshot! post-info!
                                           now-ms-fn
                                           "BTC"
                                           {:interval :1m
                                            :bars 10
                                            :priority :low})
          (.then
           (fn []
             (let [[body opts] (first @calls)]
               (is (= {"type" "candleSnapshot"
                       "req" {"coin" "BTC"
                              "interval" "1m"
                              "startTime" -590000
                              "endTime" 10000}}
                      body))
               (is (= {:priority :low} opts)))
             (-> (market/request-candle-snapshot! post-info! now-ms-fn nil {})
                 (.then (fn [result]
                          (is (nil? result))
                          (is (= 1 (count @calls)))
                          (done)))
                 (.catch (fn [err]
                           (is false (str "Unexpected error: " err))
                           (done))))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-asset-contexts-normalizes-response-shape-test
  (async done
    (let [post-info! (fn [_body _opts]
                       (js/Promise.resolve [{:universe [{:name "BTC" :marginTableId 1}
                                                        {:name "ETH" :marginTableId 1}]
                                             :marginTables [[1 {:max-leverage 5}]]}
                                            [{:dayNtlVlm "10" :openInterest "20"}
                                             {:dayNtlVlm "0" :openInterest "20"}]]))]
      (-> (market/request-asset-contexts! post-info! {})
          (.then (fn [contexts]
                   (is (= #{:BTC} (set (keys contexts))))
                   (is (= 0 (get-in contexts [:BTC :idx])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
