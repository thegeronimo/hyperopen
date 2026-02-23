(ns hyperopen.api.service-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.runtime :as api-runtime]
            [hyperopen.api.service :as api-service]))

(defn- stub-client
  [{:keys [stats reset-calls]}]
  {:request-info! (fn [& _] (js/Promise.resolve {}))
   :get-request-stats (fn [] stats)
   :reset! (fn [] (swap! reset-calls inc))})

(deftest service-resets-client-and-runtime-state-test
  (let [reset-calls (atom 0)
        stats {:started {:high 1 :low 0}}
        service (api-service/make-service
                 {:info-client-instance (stub-client {:stats stats
                                                      :reset-calls reset-calls})})
        runtime (api-service/runtime service)]
    (is (= stats (api-service/get-request-stats service)))
    (api-runtime/set-public-webdata2-cache! runtime {:snapshot true})
    (api-runtime/set-ensure-perp-dexs-flight! runtime (js/Promise.resolve :inflight))
    (api-service/reset-service! service)
    (is (= 1 @reset-calls))
    (is (nil? (api-runtime/public-webdata2-cache runtime)))
    (is (nil? (api-runtime/ensure-perp-dexs-flight runtime)))))

(deftest ensure-perp-dexs-data-single-flight-test
  (async done
    (let [reset-calls (atom 0)
          service (api-service/make-service
                   {:info-client-instance (stub-client {:stats {}
                                                        :reset-calls reset-calls})})
          store (atom {:perp-dexs []})
          calls (atom 0)
          resolve! (atom nil)
          request-perp-dexs! (fn [_opts]
                               (swap! calls inc)
                               (js/Promise.
                                (fn [resolve _]
                                  (reset! resolve! resolve))))
          p1 (api-service/ensure-perp-dexs-data! service store request-perp-dexs! {})
          p2 (api-service/ensure-perp-dexs-data! service store request-perp-dexs! {})]
      (is (identical? p1 p2))
      (is (= 1 @calls))
      (@resolve! ["dex-a"])
      (-> (js/Promise.all #js [p1 p2])
          (.then (fn [results]
                   (is (= [{:dex-names ["dex-a"]
                            :fee-config-by-name {}}
                           {:dex-names ["dex-a"]
                            :fee-config-by-name {}}]
                          (js->clj results)))
                   (is (nil? (api-runtime/ensure-perp-dexs-flight (api-service/runtime service))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest ensure-public-webdata2-caches-unless-forced-test
  (async done
    (let [reset-calls (atom 0)
          service (api-service/make-service
                   {:info-client-instance (stub-client {:stats {}
                                                        :reset-calls reset-calls})})
          calls (atom 0)
          request-public-webdata2! (fn [_opts]
                                     (let [n (swap! calls inc)]
                                       (js/Promise.resolve {:snapshot n})))]
      (-> (api-service/ensure-public-webdata2! service request-public-webdata2! {})
          (.then (fn [first-snapshot]
                   (is (= {:snapshot 1} first-snapshot))
                   (api-service/ensure-public-webdata2! service request-public-webdata2! {})))
          (.then (fn [cached-snapshot]
                   (is (= {:snapshot 1} cached-snapshot))
                   (is (= 1 @calls))
                   (api-service/ensure-public-webdata2! service request-public-webdata2! {:force? true})))
          (.then (fn [forced-snapshot]
                   (is (= {:snapshot 2} forced-snapshot))
                   (is (= 2 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
