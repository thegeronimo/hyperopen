(ns hyperopen.portfolio.optimizer.infrastructure.history-client-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.infrastructure.history-client :as history-client]
            [hyperopen.test-support.async :as async-support]))

(deftest request-history-bundle-fetches-arbitrary-candles-and-perp-funding-test
  (async done
    (let [calls (atom [])
          deps {:request-candle-snapshot!
                (fn [coin opts]
                  (swap! calls conj [:candle coin opts])
                  (js/Promise.resolve [{:time 1000 :close (case coin
                                                            "BTC" "100"
                                                            "PURR" "10")}]))
                :request-market-funding-history!
                (fn [coin opts]
                  (swap! calls conj [:funding coin opts])
                  (js/Promise.resolve [{:time-ms 1000
                                        :funding-rate-raw "0.001"}]))}
          request {:universe [{:instrument-id "perp:BTC"
                               :market-type :perp
                               :coin "BTC"}
                              {:instrument-id "spot:PURR"
                               :market-type :spot
                               :coin "PURR"}]
                   :interval :1d
                   :bars 30
                   :priority :low
                   :now-ms 10000
                   :funding-window-ms 5000}]
      (-> (history-client/request-history-bundle! deps request)
          (.then
           (fn [bundle]
             (is (= [[:candle "BTC" {:interval :1d
                                     :bars 30
                                     :priority :low
                                     :cache-key [:portfolio-optimizer :candles "BTC" :1d 30]
                                     :dedupe-key [:portfolio-optimizer :candles "BTC" :1d 30]}]
                     [:candle "PURR" {:interval :1d
                                      :bars 30
                                      :priority :low
                                      :cache-key [:portfolio-optimizer :candles "PURR" :1d 30]
                                      :dedupe-key [:portfolio-optimizer :candles "PURR" :1d 30]}]
                     [:funding "BTC" {:priority :low
                                      :start-time-ms 5000
                                      :end-time-ms 10000
                                      :cache-key [:portfolio-optimizer :funding "BTC" 5000 10000]
                                      :dedupe-key [:portfolio-optimizer :funding "BTC" 5000 10000]}]]
                    @calls))
             (is (= {"BTC" [{:time 1000 :close "100"}]
                     "PURR" [{:time 1000 :close "10"}]}
                    (:candle-history-by-coin bundle)))
             (is (= {"BTC" [{:time-ms 1000
                             :funding-rate-raw "0.001"}]}
                    (:funding-history-by-coin bundle)))
             (is (= [] (:warnings bundle)))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-fetches-vault-details-for-vault-instruments-test
  (async done
    (let [vault-address "0x1111111111111111111111111111111111111111"
          calls (atom [])
          vault-details {:portfolio {:all-time {:accountValueHistory [[1000 100]
                                                                       [2000 110]]
                                                :pnlHistory [[1000 0]
                                                             [2000 10]]}}}
          deps {:request-candle-snapshot!
                (fn [coin opts]
                  (swap! calls conj [:candle coin opts])
                  (js/Promise.resolve [{:time 1000 :close "100"}
                                       {:time 2000 :close "110"}]))
                :request-market-funding-history!
                (fn [coin opts]
                  (swap! calls conj [:funding coin opts])
                  (js/Promise.resolve [{:time-ms 1000
                                        :funding-rate-raw "0.001"}]))
                :request-vault-details!
                (fn [address opts]
                  (swap! calls conj [:vault-details address opts])
                  (js/Promise.resolve vault-details))}
          request {:universe [{:instrument-id "perp:BTC"
                               :market-type :perp
                               :coin "BTC"}
                              {:instrument-id (str "vault:" vault-address)
                               :market-type :vault
                               :coin (str "vault:" vault-address)
                               :vault-address vault-address}]
                   :interval :1d
                   :bars 30
                   :priority :low
                   :now-ms 10000
                   :funding-window-ms 5000}]
      (-> (history-client/request-history-bundle! deps request)
          (.then
           (fn [bundle]
             (is (= [[:candle "BTC" {:interval :1d
                                     :bars 30
                                     :priority :low
                                     :cache-key [:portfolio-optimizer :candles "BTC" :1d 30]
                                     :dedupe-key [:portfolio-optimizer :candles "BTC" :1d 30]}]
                     [:funding "BTC" {:priority :low
                                      :start-time-ms 5000
                                      :end-time-ms 10000
                                      :cache-key [:portfolio-optimizer :funding "BTC" 5000 10000]
                                      :dedupe-key [:portfolio-optimizer :funding "BTC" 5000 10000]}]
                     [:vault-details vault-address {:priority :low
                                                    :cache-key [:portfolio-optimizer :vault-details vault-address]
                                                    :dedupe-key [:portfolio-optimizer :vault-details vault-address]}]]
                    @calls))
             (is (= {vault-address vault-details}
                    (:vault-details-by-address bundle)))
             (is (= [vault-address]
                    (mapv :vault-address (get-in bundle [:request-plan :vault-detail-requests]))))
             (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-history-bundle-keeps-request-plan-warnings-test
  (async done
    (let [deps {:request-candle-snapshot! (fn [_coin _opts]
                                            (js/Promise.resolve []))
                :request-market-funding-history! (fn [_coin _opts]
                                                   (js/Promise.resolve []))}]
      (-> (history-client/request-history-bundle!
           deps
           {:universe [{:instrument-id "missing"
                        :market-type :perp}]
            :now-ms 1000})
          (.then
           (fn [bundle]
             (is (= {} (:candle-history-by-coin bundle)))
             (is (= {} (:funding-history-by-coin bundle)))
             (is (= [{:code :missing-history-coin
                      :instrument-id "missing"
                      :market-type :perp}]
                    (:warnings bundle)))
             (done)))
          (.catch (async-support/unexpected-error done))))))
