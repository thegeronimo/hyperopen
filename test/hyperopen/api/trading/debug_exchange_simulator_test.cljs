(ns hyperopen.api.trading.debug-exchange-simulator-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading.debug-exchange-simulator :as simulator]))

(defn- js->kw
  [value]
  (js->clj value :keywordize-keys true))

(defn- read-json
  [response]
  (-> (.json response)
      (.then js->kw)))

(defn- read-text-json
  [response]
  (-> (.text response)
      (.then (fn [text]
               (js->kw (js/JSON.parse text))))))

(deftest install-clear-and-uninstalled-fetch-test
  (try
    (is (true? (simulator/clear!)))
    (is (nil? (simulator/snapshot)))
    (is (nil? (simulator/simulated-fetch-response [[:missing]])))
    (is (true? (simulator/install! {:signedActions {:default {:status "ok"}}})))
    (is (= {:installed true
            :config {:signedActions {:default {:status "ok"}}}
            :calls []}
           (simulator/snapshot)))
    (is (nil? (simulator/simulated-fetch-response [[:unknown] [:fallback]])))
    (is (= {:installed true
            :config {:signedActions {:default {:status "ok"}}}
            :calls [{:paths [[:unknown] [:fallback]]
                     :matchedPath nil}]}
           (simulator/snapshot)))
    (is (true? (simulator/install! {:approveAgent {:status "ok"}})))
    (is (= {:installed true
            :config {:approveAgent {:status "ok"}}
            :calls []}
           (simulator/snapshot)))
    (is (true? (simulator/clear!)))
    (is (nil? (simulator/snapshot)))
    (finally
      (simulator/clear!))))

(deftest map-response-queues-are-consumed-in-order-test
  (async done
    (let [paths [[:signedActions "order"]]]
      (simulator/install!
       {:signedActions {"order" {:responses [{:http-status 201
                                               :body {:status "first"
                                                      :order 1}}
                                              {:body {:status "second"
                                                      :order 2}}]}}})
      (-> (simulator/simulated-fetch-response paths)
          (.then (fn [response]
                   (is (= 201 (.-status response)))
                   (read-json response)))
          (.then (fn [body]
                   (is (= {:status "first"
                           :order 1}
                          body))
                   (is (= {:signedActions {"order" {:responses [{:body {:status "second"
                                                                          :order 2}}]}}}
                          (:config (simulator/snapshot))))
                   (is (= [{:paths paths
                            :matchedPath [:signedActions "order"]
                            :responseStatus "first"
                            :remainingResponses 1}]
                          (:calls (simulator/snapshot))))
                   (simulator/simulated-fetch-response paths)))
          (.then (fn [response]
                   (read-json response)))
          (.then (fn [body]
                   (is (= {:status "second"
                           :order 2}
                          body))
                   (is (= {:signedActions {"order" {:responses []}}}
                          (:config (simulator/snapshot))))
                   (is (= [{:paths paths
                            :matchedPath [:signedActions "order"]
                            :responseStatus "first"
                            :remainingResponses 1}
                           {:paths paths
                            :matchedPath [:signedActions "order"]
                            :responseStatus "second"
                            :remainingResponses 0}]
                          (:calls (simulator/snapshot))))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))))
          (.finally (fn []
                      (simulator/clear!)
                      (done)))))))

(deftest vector-response-queues-are-consumed-in-order-test
  (async done
    (let [paths [[:approveAgent]]]
      (simulator/install!
       {:approveAgent [{:status "ok"
                        :response {:source "first"}}
                       {:status "ok"
                        :response {:source "second"}}]})
      (-> (simulator/simulated-fetch-response paths)
          (.then (fn [response]
                   (read-json response)))
          (.then (fn [body]
                   (is (= {:status "ok"
                           :response {:source "first"}}
                          body))
                   (is (= {:approveAgent [{:status "ok"
                                           :response {:source "second"}}]}
                          (:config (simulator/snapshot))))
                   (is (= [{:paths paths
                            :matchedPath [:approveAgent]
                            :responseStatus "ok"
                            :remainingResponses 1}]
                          (:calls (simulator/snapshot))))
                   (simulator/simulated-fetch-response paths)))
          (.then read-text-json)
          (.then (fn [body]
                   (is (= {:status "ok"
                           :response {:source "second"}}
                          body))
                   (is (= {:approveAgent []}
                          (:config (simulator/snapshot))))
                   (is (= [{:paths paths
                            :matchedPath [:approveAgent]
                            :responseStatus "ok"
                            :remainingResponses 1}
                           {:paths paths
                            :matchedPath [:approveAgent]
                            :responseStatus "ok"
                            :remainingResponses 0}]
                          (:calls (simulator/snapshot))))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))))
          (.finally (fn []
                      (simulator/clear!)
                      (done)))))))

(deftest static-map-entries-produce-responses-without-consuming-config-test
  (async done
    (let [paths [[:signedActions "vaultTransfer"]]]
      (simulator/install!
       {:signedActions {"vaultTransfer" {:httpStatus 202
                                          :body {:status "accepted"
                                                 :kind "body-status"}}
                        :default {:response {:status "fallback"
                                             :kind "nested-response"}}}})
      (-> (simulator/simulated-fetch-response paths)
          (.then (fn [response]
                   (is (= 202 (.-status response)))
                   (read-json response)))
          (.then (fn [body]
                   (is (= {:status "accepted"
                           :kind "body-status"}
                          body))
                   (is (= {:signedActions {"vaultTransfer" {:httpStatus 202
                                                             :body {:status "accepted"
                                                                    :kind "body-status"}}
                                           :default {:response {:status "fallback"
                                                                :kind "nested-response"}}}}
                          (:config (simulator/snapshot))))
                   (is (= [{:paths paths
                            :matchedPath [:signedActions "vaultTransfer"]
                            :responseStatus "accepted"
                            :remainingResponses nil}]
                          (:calls (simulator/snapshot))))
                   (simulator/simulated-fetch-response [[:signedActions :default]])))
          (.then (fn [response]
                   (is (= 200 (.-status response)))
                   (read-json response)))
          (.then (fn [body]
                   (is (= {:status "fallback"
                           :kind "nested-response"}
                          body))
                   (is (= [{:paths paths
                            :matchedPath [:signedActions "vaultTransfer"]
                            :responseStatus "accepted"
                            :remainingResponses nil}
                           {:paths [[:signedActions :default]]
                            :matchedPath [:signedActions :default]
                            :responseStatus "fallback"
                            :remainingResponses nil}]
                          (:calls (simulator/snapshot))))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))))
          (.finally (fn []
                      (simulator/clear!)
                      (done)))))))

(deftest scalar-response-entry-is-returned-as-json-payload-test
  (async done
    (simulator/install! {:custom {:ok true}})
    (-> (simulator/simulated-fetch-response [[:custom]])
        (.then read-json)
        (.then (fn [body]
                 (is (= {:ok true}
                        body))
                 (is (= [{:paths [[:custom]]
                          :matchedPath [:custom]
                          :responseStatus nil
                          :remainingResponses nil}]
                        (:calls (simulator/snapshot))))))
        (.catch (fn [err]
                  (is false (str "Unexpected error: " err))))
        (.finally (fn []
                    (simulator/clear!)
                    (done))))))

(deftest schedule-cancel-default-response-is-recorded-test
  (async done
    (let [paths [[:signedActions "scheduleCancel"]
                 [:signedActions :default]]]
      (simulator/install! {:signedActions {:default []}})
      (-> (simulator/simulated-fetch-response paths)
          (.then read-json)
          (.then (fn [body]
                   (is (= {:status "ok"} body))
                   (is (= [{:paths paths
                            :matchedPath [:signedActions "scheduleCancel"]
                            :responseStatus "ok"
                            :remainingResponses nil
                            :defaulted true}]
                          (:calls (simulator/snapshot))))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))))
          (.finally (fn []
                      (simulator/clear!)
                      (done)))))))

(deftest reject-message-response-rejects-the-fetch-promise-test
  (async done
    (simulator/install!
     {:signedActions {:default {:rejectMessage "simulated failure"}}})
    (-> (simulator/simulated-fetch-response [[:signedActions :default]])
        (.then (fn [_response]
                 (is false "Expected simulator response to reject")))
        (.catch (fn [err]
                  (is (re-find #"simulated failure" (str err)))))
        (.finally (fn []
                    (simulator/clear!)
                    (done))))))
