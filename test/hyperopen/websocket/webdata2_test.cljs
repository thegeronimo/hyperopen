(ns hyperopen.websocket.webdata2-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.utils.data-normalization :as data-normalization]
            [hyperopen.websocket.webdata2 :as webdata2]))

(deftest create-webdata2-handler-caches-meta-index-and-patches-incrementally-test
  (let [store (atom {:asset-contexts {:legacy {:idx 999}}})
        build-call-count (atom 0)
        patch-seeds (atom [])
        handler (webdata2/create-webdata2-handler store)
        meta-a {:universe [{:name "BTC" :marginTableId 1}]
                :marginTables [{1 {:leverage 10}}]}
        meta-a-same-shape {:universe [{:name "BTC" :marginTableId 1}]
                           :marginTables [{1 {:leverage 10}}]}
        meta-b {:universe [{:name "BTC" :marginTableId 1}
                           {:name "ETH" :marginTableId 2}]
                :marginTables [{1 {:leverage 10}} {2 {:leverage 5}}]}]
    (with-redefs [data-normalization/build-asset-context-meta-index
                  (fn [meta]
                    (swap! build-call-count inc)
                    (mapv (fn [[idx {:keys [name] :as info}]]
                            {:idx idx
                             :asset-key (keyword name)
                             :info info
                             :margin nil})
                          (map-indexed vector (:universe meta))))
                  data-normalization/patch-asset-contexts
                  (fn [seed _meta-index asset-ctxs]
                    (swap! patch-seeds conj seed)
                    (assoc seed :patched (count asset-ctxs)))]
      (handler {:channel "webData2"
                :data {:meta meta-a
                       :assetCtxs [{:dayNtlVlm "1" :openInterest "1"}]}})
      (handler {:channel "webData2"
                :data {:meta meta-a-same-shape
                       :assetCtxs [{:dayNtlVlm "2" :openInterest "2"}]}})
      (handler {:channel "webData2"
                :data {:meta meta-b
                       :assetCtxs [{:dayNtlVlm "3" :openInterest "3"}
                                   {:dayNtlVlm "4" :openInterest "4"}]}}))

    (is (= 2 @build-call-count))
    (is (= [{}
            {:patched 1}
            {}]
           @patch-seeds))
    (is (= {:patched 2}
           (:asset-contexts @store)))
    (is (= {:meta meta-b
            :assetCtxs [{:dayNtlVlm "3" :openInterest "3"}
                        {:dayNtlVlm "4" :openInterest "4"}]}
           (:webdata2 @store)))))

(deftest create-webdata2-handler-updates-webdata2-without-asset-context-normalization-test
  (let [store (atom {:asset-contexts {:BTC {:idx 0}}})
        build-called? (atom false)
        patch-called? (atom false)
        handler (webdata2/create-webdata2-handler store)
        payload {:channel "webData2"
                 :data {:clearinghouseState {:withdrawable "12.34"}}}]
    (with-redefs [data-normalization/build-asset-context-meta-index
                  (fn [_]
                    (reset! build-called? true)
                    [])
                  data-normalization/patch-asset-contexts
                  (fn [& _]
                    (reset! patch-called? true)
                    {})]
      (handler payload))
    (is (false? @build-called?))
    (is (false? @patch-called?))
    (is (= {:BTC {:idx 0}} (:asset-contexts @store)))
    (is (= (:data payload) (:webdata2 @store)))))

(deftest create-webdata2-handler-ignores-inactive-address-payloads-test
  (let [active-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        stale-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        store (atom {:wallet {:address active-address}})
        handler (webdata2/create-webdata2-handler store)]
    (handler {:channel "webData2"
              :user stale-address
              :data {:clearinghouseState {:withdrawable "1.0"}}})
    (is (nil? (:webdata2 @store)))
    (handler {:channel "webData2"
              :user active-address
              :data {:clearinghouseState {:withdrawable "2.0"}}})
    (is (= {:clearinghouseState {:withdrawable "2.0"}}
           (:webdata2 @store)))))
