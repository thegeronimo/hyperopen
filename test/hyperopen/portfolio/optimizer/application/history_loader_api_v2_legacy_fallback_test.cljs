(ns hyperopen.portfolio.optimizer.application.history-loader-api-v2-legacy-fallback-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.legacy-fallback :as legacy-fallback]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.000001))

(deftest align-api-v2-history-uses-legacy-fallback-series-for-api-gaps-test
  (let [universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}
                  {:instrument-id "perp:DOGE"
                   :market-type :perp
                   :coin "DOGE"}]
        api-v2-history
        (api-v2/normalize-history-bundle
         {:universe universe}
         {:contract_version "optimizer-history-api-v2"
          :request_id "rid-api-plus-legacy"
          :dataset_version "dv-api-plus-legacy"
          :status "partial"
          :series_by_instrument
          {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                          :lineage_kind "native"
                          :series_kind "market_price"
                          :points [{:time_ms 1000
                                    :close 100
                                    :return nil}
                                   {:time_ms 2000
                                    :close 110
                                    :return 0.1}]
                          :funding {:status "available"
                                    :annualized_carry 0.01}
                          :warnings []}}
          :warnings []})
        aligned (history-loader/align-history-inputs
                 {:universe universe
                  :api-v2-history api-v2-history
                  :candle-history-by-coin
                  {"DOGE" [{:time 1000 :close "10"}
                           {:time 2000 :close "12"}]}
                  :funding-history-by-coin
                  {"DOGE" [{:time-ms 1000
                            :funding-rate-raw "0.001"}
                           {:time-ms 2000
                            :funding-rate-raw "0.003"}]}
                  :funding-periods-per-year 1000
                  :min-observations 2})]
    (is (= ["perp:BTC" "perp:DOGE"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= [1000 2000] (:calendar aligned)))
    (is (near? 0.1
               (get-in aligned [:return-series-by-instrument "perp:BTC" 0])))
    (is (near? 0.2
               (get-in aligned [:return-series-by-instrument "perp:DOGE" 0])))
    (is (= :api-v2-point-returns
           (get-in aligned [:alignment-source :kind])))
    (is (= :history-api-v2
           (get-in aligned [:funding-by-instrument "perp:BTC" :source])))
    (is (= :legacy-fallback
           (get-in aligned [:funding-by-instrument "perp:DOGE" :source])))
    (is (near? 2
               (get-in aligned
                       [:funding-by-instrument "perp:DOGE" :annualized-carry])))
    (is (= #{:optimizer-history-api-legacy-fallback}
           (set (map :code (:warnings aligned)))))))

(deftest align-api-v2-history-does-not-suppress-hard-api-rejections-with-legacy-data-test
  (let [universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}
                  {:instrument-id "perp:BAD"
                   :market-type :perp
                   :coin "BAD"
                   :optimizer-history/instrument-id "hl:perp:BAD"}]
        api-v2-history
        (api-v2/normalize-history-bundle
         {:universe universe}
         {:contract_version "optimizer-history-api-v2"
          :request_id "rid-hard-warning"
          :dataset_version "dv-hard-warning"
          :status "partial"
          :series_by_instrument
          {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                          :lineage_kind "native"
                          :series_kind "market_price"
                          :points [{:time_ms 1000
                                    :close 100
                                    :return nil}
                                   {:time_ms 2000
                                    :close 110
                                    :return 0.1}]
                          :funding {:status "available"
                                    :annualized_carry 0.01}
                          :warnings []}
           "hl:perp:BAD" {:instrument_id "hl:perp:BAD"
                          :lineage_kind "rejected"
                          :series_kind "market_price"
                          :points []
                          :funding {:status "rejected"}
                          :warnings [{:code "validation-failed"
                                      :instrument_id "hl:perp:BAD"}]}}
          :warnings [{:code "validation-failed"
                      :instrument_id "hl:perp:BAD"}]})
        aligned (history-loader/align-history-inputs
                 {:universe universe
                  :api-v2-history api-v2-history
                  :candle-history-by-coin
                  {"BAD" [{:time 1000 :close "10"}
                          {:time 2000 :close "12"}]}
                  :funding-history-by-coin
                  {"BAD" [{:time-ms 1000
                           :funding-rate-raw "0.001"}
                          {:time-ms 2000
                           :funding-rate-raw "0.003"}]}
                  :funding-periods-per-year 1000
                  :min-observations 2})]
    (is (= ["perp:BTC"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= ["perp:BAD"]
           (mapv :instrument-id (:excluded-instruments aligned))))
    (is (= #{:validation-failed}
           (set (map :code (:warnings aligned)))))
    (is (not= :legacy-fallback
              (get-in aligned
                      [:funding-by-instrument
                       "perp:BAD"
                       :source])))))

(deftest align-api-v2-history-skips-legacy-series-work-for-api-served-instruments-test
  (let [universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}
                  {:instrument-id "perp:DOGE"
                   :market-type :perp
                   :coin "DOGE"}]
        api-v2-history
        (api-v2/normalize-history-bundle
         {:universe universe}
         {:contract_version "optimizer-history-api-v2"
          :request_id "rid-lazy-fallback"
          :dataset_version "dv-lazy-fallback"
          :status "partial"
          :series_by_instrument
          {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                          :lineage_kind "native"
                          :series_kind "market_price"
                          :points [{:time_ms 1000
                                    :close 100
                                    :return nil}
                                   {:time_ms 2000
                                    :close 110
                                    :return 0.1}]
                          :funding {:status "available"
                                    :annualized_carry 0.01}
                          :warnings []}}
          :warnings []})
        inputs {:universe universe
                :api-v2-history api-v2-history
                :candle-history-by-coin
                {"DOGE" [{:time 1000 :close "10"}
                         {:time 2000 :close "12"}]}
                :funding-history-by-coin
                {"DOGE" [{:time-ms 1000
                          :funding-rate-raw "0.001"}
                         {:time-ms 2000
                          :funding-rate-raw "0.003"}]}
                :funding-periods-per-year 1000
                :min-observations 2}
        original-series legacy-fallback/series
        calls (atom [])
        aligned (with-redefs [legacy-fallback/series
                              (fn [instrument & args]
                                (swap! calls conj (:instrument-id instrument))
                                (apply original-series instrument args))]
                  (history-loader/align-history-inputs inputs))]
    (is (= ["perp:DOGE"] @calls))
    (is (= ["perp:BTC" "perp:DOGE"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= :history-api-v2
           (get-in aligned [:funding-by-instrument "perp:BTC" :source])))
    (is (= :legacy-fallback
           (get-in aligned [:funding-by-instrument "perp:DOGE" :source])))
    (is (= #{:optimizer-history-api-legacy-fallback}
           (set (map :code (:warnings aligned)))))))

(deftest align-api-v2-history-never-builds-legacy-series-for-fully-served-universe-test
  (let [universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}]
        api-v2-history
        (api-v2/normalize-history-bundle
         {:universe universe}
         {:contract_version "optimizer-history-api-v2"
          :request_id "rid-no-fallback"
          :dataset_version "dv-no-fallback"
          :status "ok"
          :series_by_instrument
          {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                          :lineage_kind "native"
                          :series_kind "market_price"
                          :points [{:time_ms 1000
                                    :close 100
                                    :return nil}
                                   {:time_ms 2000
                                    :close 110
                                    :return 0.1}]
                          :funding {:status "available"
                                    :annualized_carry 0.01}
                          :warnings []}}
          :warnings []})
        calls (atom 0)
        aligned (with-redefs [legacy-fallback/series
                              (fn [& _] (swap! calls inc) nil)]
                  (history-loader/align-history-inputs
                   {:universe universe
                    :api-v2-history api-v2-history
                    :candle-history-by-coin {"BTC" [{:time 1000 :close "99"}]}
                    :funding-history-by-coin {}
                    :funding-periods-per-year 1000
                    :min-observations 2}))]
    (is (zero? @calls))
    (is (= ["perp:BTC"]
           (mapv :instrument-id (:eligible-instruments aligned))))))
