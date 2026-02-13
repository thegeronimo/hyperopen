(ns hyperopen.api.gateway.account-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.gateway.account :as account-gateway]))

(deftest fetch-user-funding-history-uses-normalized-window-test
  (async done
    (let [calls (atom [])
          deps {:post-info! (fn [body _opts]
                              (swap! calls conj body)
                              (js/Promise.resolve []))
                :normalize-funding-history-filters (fn [_opts]
                                                     {:start-time-ms 1000
                                                      :end-time-ms 2000})
                :normalize-info-funding-rows identity
                :sort-funding-history-rows identity}]
      (-> (account-gateway/fetch-user-funding-history! deps nil "0xabc" {:priority :high})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (= {"type" "userFunding"
                           "user" "0xabc"
                           "startTime" 1000
                           "endTime" 2000}
                          (first @calls)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest fetch-user-funding-history-skips-empty-address-test
  (async done
    (let [calls (atom 0)
          deps {:post-info! (fn [_body _opts]
                              (swap! calls inc)
                              (js/Promise.resolve []))
                :normalize-funding-history-filters (fn [_opts]
                                                     {:start-time-ms 1000
                                                      :end-time-ms 2000})
                :normalize-info-funding-rows identity
                :sort-funding-history-rows identity}]
      (-> (account-gateway/fetch-user-funding-history! deps nil nil {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-delegates-to-request-data-wrapper-test
  (async done
    (let [called (atom nil)
          deps {:request-user-funding-history-data! (fn [address opts]
                                                      (reset! called [address opts])
                                                      (js/Promise.resolve []))}]
      (-> (account-gateway/request-user-funding-history! deps "0xabc" {:priority :high})
          (.then (fn [_]
                   (is (= ["0xabc" {:priority :high}] @called))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-supports-legacy-fetch-dependency-test
  (async done
    (let [called (atom nil)
          deps {:fetch-user-funding-history! (fn [store address opts]
                                               (reset! called [store address opts])
                                               (js/Promise.resolve []))}]
      (-> (account-gateway/request-user-funding-history! deps "0xabc" {:priority :high})
          (.then (fn [_]
                   (is (= [nil "0xabc" {:priority :high}] @called))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-clearinghouse-state-includes-dex-test
  (async done
    (let [calls (atom [])
          deps {:post-info! (fn [body _opts]
                              (swap! calls conj body)
                              (js/Promise.resolve {:ok true}))}]
      (-> (account-gateway/request-clearinghouse-state! deps "0xabc" "dex-a" {:priority :high})
          (.then (fn [_]
                   (is (= {"type" "clearinghouseState"
                           "user" "0xabc"
                           "dex" "dex-a"}
                          (first @calls)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
