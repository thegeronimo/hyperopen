(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-history-facade-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]
            [hyperopen.test-support.async :as async-support]))

(deftest load-portfolio-optimizer-history-effect-passes-root-facade-fetchers-to-history-owner-test
  (async done
    (let [calls (atom [])
          store (atom {:portfolio {:optimizer
                                    {:draft {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}]}
                                     :runtime {:as-of-ms 3000
                                               :stale-after-ms 60000}}}})
          bundle {:candle-history-by-coin {"BTC" []}
                  :funding-history-by-coin {}
                  :vault-details-by-address {}
                  :warnings []
                  :request-plan {:candle-requests []}}]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 12000)
                    portfolio-optimizer-adapters/*request-candle-snapshot!*
                    (fn [& args]
                      (swap! calls conj (into [:candle] args))
                      {:candles []})
                    portfolio-optimizer-adapters/*request-market-funding-history!*
                    (fn [& args]
                      (swap! calls conj (into [:funding] args))
                      {:funding []})
                    portfolio-optimizer-adapters/*request-vault-details!*
                    (fn [& args]
                      (swap! calls conj (into [:vault-details] args))
                      {:details []})
                    portfolio-optimizer-adapters/*request-history-bundle!*
                    (fn [deps request]
                      (swap! calls conj [:history-request request])
                      ((:request-candle-snapshot! deps)
                       "BTC"
                       {:interval "4h"
                        :bars 2
                        :priority :high})
                      ((:request-market-funding-history! deps) "BTC" {:limit 2})
                      ((:request-vault-details! deps) "0xvault")
                      (js/Promise.resolve bundle))]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-history-effect
             nil
             store
             {:bars 2})
            (.then
             (fn [result]
               (let [history-request (second (first @calls))]
                 (is (= bundle result))
                 (is (= [{:instrument-id "perp:BTC"
                          :market-type :perp
                          :coin "BTC"}]
                        (:universe history-request)))
                 (is (= 2 (:bars history-request)))
                 (is (= 3000 (:now-ms history-request)))
                 (is (= 60000 (:stale-after-ms history-request)))
                 (is (= [:candle "BTC" :interval "4h" :bars 2 :priority :high]
                        (second @calls)))
                 (is (= [:funding "BTC" {:limit 2}]
                        (nth @calls 2)))
                 (is (= [:vault-details "0xvault"]
                        (nth @calls 3))))
               (done)))
            (.catch (async-support/unexpected-error done)))))))
