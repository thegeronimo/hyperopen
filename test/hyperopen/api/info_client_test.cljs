(ns hyperopen.api.info-client-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.info-client :as info-client]))

(defn- fake-http-response
  [status payload]
  (doto (js-obj)
    (aset "status" status)
    (aset "ok" (= status 200))
    (aset "json" (fn [] (js/Promise.resolve (clj->js payload))))))

(deftest request-info-shares-single-flight-for-identical-dedupe-keys-test
  (async done
    (let [fetch-calls (atom [])
          now-ms (atom 0)
          payload #js {"meta" #js {}
                       "assetCtxs" #js []}
          fetch-fn (fn [url opts]
                     (swap! fetch-calls conj [url (js->clj opts :keywordize-keys true)])
                     (js/Promise.resolve
                      #js {:ok true
                           :status 200
                           :json (fn []
                                   (js/Promise.resolve payload))}))
          client (info-client/make-info-client
                  {:fetch-fn fetch-fn
                   :now-ms-fn (fn []
                                (swap! now-ms inc))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)
          opts {:priority :high
                :dedupe-key :asset-contexts}
          p1 (request-info! {"type" "metaAndAssetCtxs"} opts)
          p2 (request-info! {"type" "metaAndAssetCtxs"} opts)]
      (is (identical? p1 p2))
      (-> (js/Promise.all #js [p1 p2])
          (.then (fn [results]
                   (is (= 1 (count @fetch-calls)))
                   (let [[url request-opts] (first @fetch-calls)]
                     (is (= "https://api.hyperliquid.xyz/info" url))
                     (is (= "POST" (:method request-opts))))
                   (is (= [{:meta {} :assetCtxs []}
                           {:meta {} :assetCtxs []}]
                          (js->clj results)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-info-clears-single-flight-after-settlement-test
  (async done
    (let [fetch-calls (atom 0)
          client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (swap! fetch-calls inc)
                               (js/Promise.resolve
                                (fake-http-response 200 {:ok @fetch-calls})))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})
          request-info! (:request-info! client)
          first-promise (request-info! {"type" "meta"} {:dedupe-key :meta})]
      (-> first-promise
          (.then (fn [first-response]
                   (is (= {:ok 1} first-response))
                   (let [next-promise (request-info! {"type" "meta"} {:dedupe-key :meta})]
                     (is (not (identical? first-promise next-promise)))
                     next-promise)))
          (.then (fn [second-response]
                   (is (= {:ok 2} second-response))
                   (is (= 2 @fetch-calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-info-supports-three-arity-entry-point-test
  (async done
    (let [client (info-client/make-info-client
                  {:fetch-fn (fn [_ _]
                               (js/Promise.resolve
                                (fake-http-response 200 {:ok true})))
                   :sleep-ms-fn (fn [_] (js/Promise.resolve nil))
                   :log-fn (fn [& _] nil)})]
      (-> ((:request-info! client) {"type" "meta"} {:priority :low} 2)
          (.then (fn [response]
                   (is (= {:ok true} response))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
