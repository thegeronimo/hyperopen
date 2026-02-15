(ns hyperopen.api.gateway.account-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.account :as account-endpoints]
            [hyperopen.api.fetch-compat :as fetch-compat]
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

(deftest account-gateway-wrapper-delegation-coverage-test
  (let [called (atom [])
        post-info! (fn [& _] nil)]
    (with-redefs [account-endpoints/request-spot-clearinghouse-state! (fn [& args]
                                                                         (swap! called conj [:request-spot args])
                                                                         {:ok :request-spot})
                  account-endpoints/request-user-abstraction! (fn [& args]
                                                                (swap! called conj [:request-abstraction args])
                                                                {:ok :request-abstraction})
                  fetch-compat/fetch-spot-clearinghouse-state! (fn [& args]
                                                                 (swap! called conj [:fetch-spot args])
                                                                 {:ok :fetch-spot})
                  fetch-compat/fetch-user-abstraction! (fn [& args]
                                                         (swap! called conj [:fetch-abstraction args])
                                                         {:ok :fetch-abstraction})
                  fetch-compat/fetch-clearinghouse-state! (fn [& args]
                                                           (swap! called conj [:fetch-clearinghouse args])
                                                           {:ok :fetch-clearinghouse})
                  fetch-compat/fetch-perp-dex-clearinghouse-states! (fn [& args]
                                                                      (swap! called conj [:fetch-perp-batch args])
                                                                      {:ok :fetch-perp-batch})]
      (is (= {:ok :request-spot}
             (account-gateway/request-spot-clearinghouse-state! {:post-info! post-info!}
                                                                "0xabc"
                                                                {:priority :high})))
      (is (= {:ok :fetch-spot}
             (account-gateway/fetch-spot-clearinghouse-state! {:log-fn identity
                                                                :request-spot-clearinghouse-state! identity
                                                                :begin-spot-balances-load identity
                                                                :apply-spot-balances-success identity
                                                                :apply-spot-balances-error identity}
                                                               nil
                                                               "0xabc"
                                                               {:priority :low})))
      (is (= {:ok :request-abstraction}
             (account-gateway/request-user-abstraction! {:post-info! post-info!}
                                                        "0xabc"
                                                        {:priority :high})))
      (is (= {:ok :fetch-abstraction}
             (account-gateway/fetch-user-abstraction! {:log-fn identity
                                                       :request-user-abstraction! identity
                                                       :normalize-user-abstraction-mode identity
                                                       :apply-user-abstraction-snapshot identity}
                                                      nil
                                                      "0xabc"
                                                      {:priority :low})))
      (is (= {:ok :fetch-clearinghouse}
             (account-gateway/fetch-clearinghouse-state! {:log-fn identity
                                                           :request-clearinghouse-state! identity
                                                           :apply-perp-dex-clearinghouse-success identity
                                                           :apply-perp-dex-clearinghouse-error identity}
                                                          nil
                                                          "0xabc"
                                                          "dex-a"
                                                          {:priority :high})))
      (is (= {:ok :fetch-perp-batch}
             (account-gateway/fetch-perp-dex-clearinghouse-states! {:fetch-clearinghouse-state! identity}
                                                                    nil
                                                                    "0xabc"
                                                                    ["dex-a" "dex-b"]
                                                                    {:priority :high})))
      (is (some #(= :request-spot (first %)) @called))
      (is (some #(= :fetch-clearinghouse (first %)) @called)))))
