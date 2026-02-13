(ns hyperopen.api.facade-runtime-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api :as api-instance]
            [hyperopen.api.default :as api]
            [hyperopen.api.service :as api-service]))

(defn- stub-client
  [{:keys [stats reset-calls]}]
  {:request-info! (fn [& _] (js/Promise.resolve {}))
   :get-request-stats (fn [] stats)
   :reset! (fn [] (swap! reset-calls inc))})

(use-fixtures
  :each
  {:before (fn []
             (api/reset-api-service!)
             (api/reset-request-runtime!))
   :after (fn []
            (api/reset-api-service!)
            (api/reset-request-runtime!))})

(deftest install-api-service-switches-active-service-test
  (let [reset-a (atom 0)
        reset-b (atom 0)
        service-a (api-service/make-service
                   {:info-client-instance (stub-client {:stats {:source :a}
                                                        :reset-calls reset-a})})
        service-b (api-service/make-service
                   {:info-client-instance (stub-client {:stats {:source :b}
                                                        :reset-calls reset-b})})]
    (api/install-api-service! service-a)
    (is (= {:source :a}
           (api/get-request-stats)))
    (api/install-api-service! service-b)
    (is (= {:source :b}
           (api/get-request-stats)))))

(deftest configure-api-service-supports-injected-client-test
  (let [reset-calls (atom 0)
        stats {:configured true}
        client (stub-client {:stats stats
                             :reset-calls reset-calls})]
    (api/configure-api-service! {:info-client-instance client
                                 :log-fn (fn [& _] nil)})
    (is (= stats
           (api/get-request-stats)))
    (api/reset-api-service!)
    (is (not= stats
              (api/get-request-stats)))))

(deftest reset-request-runtime-resets-installed-service-client-test
  (let [reset-calls (atom 0)
        service (api-service/make-service
                 {:info-client-instance (stub-client {:stats {}
                                                      :reset-calls reset-calls})})]
    (api/install-api-service! service)
    (api/reset-request-runtime!)
    (is (= 1 @reset-calls))))

(deftest make-api-creates-independent-service-bound-instances-test
  (let [reset-a (atom 0)
        reset-b (atom 0)
        service-a (api-service/make-service
                   {:info-client-instance (stub-client {:stats {:source :a}
                                                        :reset-calls reset-a})})
        service-b (api-service/make-service
                   {:info-client-instance (stub-client {:stats {:source :b}
                                                        :reset-calls reset-b})})
        api-a (api-instance/make-api {:service service-a})
        api-b (api-instance/make-api {:service service-b})]
    (is (= {:source :a}
           ((:get-request-stats api-a))))
    (is (= {:source :b}
           ((:get-request-stats api-b))))
    ((:reset-request-runtime! api-a))
    (is (= 1 @reset-a))
    (is (= 0 @reset-b))))

(deftest make-api-request-ops-use-bound-service-test
  (async done
    (let [client-a {:request-info! (fn [_body _opts]
                                     (js/Promise.resolve [{:name "dex-a"}]))
                    :get-request-stats (fn [] {:source :a})
                    :reset! (fn [] nil)}
          client-b {:request-info! (fn [_body _opts]
                                     (js/Promise.resolve [{:name "dex-b"}]))
                    :get-request-stats (fn [] {:source :b})
                    :reset! (fn [] nil)}
          api-a (api-instance/make-api {:service (api-service/make-service {:info-client-instance client-a})})
          api-b (api-instance/make-api {:service (api-service/make-service {:info-client-instance client-b})})]
      (-> (js/Promise.all
           #js [((:request-perp-dexs! api-a) {})
                ((:request-perp-dexs! api-b) {})])
          (.then (fn [results]
                   (is (= [["dex-a"] ["dex-b"]]
                          (js->clj results)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
