(ns hyperopen.portfolio.optimizer.application.history-loader-api-v2-split-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader.calendar :as history-calendar]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.alignment :as api-v2-alignment]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.bundle :as api-v2-bundle]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.codec :as api-v2-codec]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.discovery :as api-v2-discovery]))

(def day-ms
  (* 24 60 60 1000))

(deftest api-v2-focused-namespaces-match-public-facade-test
  (let [universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}
                  {:instrument-id "spot:PURR/USDC"
                   :market-type :spot
                   :coin "PURR/USDC"
                   :optimizer-history/instrument-id "hl:spot:PURR/USDC"}]
        discovery-body {:contract_version "optimizer-history-api-v2"
                        :request_id "rid-discovery-focused"
                        :dataset_version "dv-focused"
                        :status "ok"
                        :instruments
                        [{:instrument_id "hl:perp:BTC"
                          :display_symbol "BTC"
                          :instrument_kind "hl_perp"
                          :aliases {:hyperopen_market_key "perp:BTC"}
                          :history {:status "available"
                                    :quality_status "passed"}}
                         {:instrument_id "hl:spot:PURR/USDC"
                          :display_symbol "PURR/USDC"
                          :instrument_kind "hl_spot"
                          :aliases {:hyperopen_market_key "spot:PURR/USDC"}
                          :history {:status "available"
                                    :quality_status "passed"}}]
                        :warnings [{:code "stale-history"
                                    :instrument_id "hl:perp:BTC"}]}
        history-body {:contract_version "optimizer-history-api-v2"
                      :request_id "rid-history-focused"
                      :dataset_version "dv-focused"
                      :status "ok"
                      :common_calendar [1000 2000 3000]
                      :return_calendar [2000 3000]
                      :aligned_returns_by_instrument
                      {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                      :returns [0.05 0.02]}
                       "hl:spot:PURR/USDC" {:instrument_id "hl:spot:PURR/USDC"
                                            :returns [0.03 0.04]}}
                      :series_by_instrument
                      {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                      :lineage_kind "native"
                                      :series_kind "market_price"
                                      :points [{:time_ms 1000 :close 100 :return nil}
                                               {:time_ms 2000 :close 105 :return 0.05}
                                               {:time_ms 3000 :close 107.1 :return 0.02}]
                                      :funding {:status "available"
                                                :annualized_carry 0.01}
                                      :warnings []}
                       "hl:spot:PURR/USDC" {:instrument_id "hl:spot:PURR/USDC"
                                            :lineage_kind "native"
                                            :series_kind "market_price"
                                            :points [{:time_ms 1000 :close 10 :return nil}
                                                     {:time_ms 2000 :close 10.3 :return 0.03}
                                                     {:time_ms 3000 :close 10.712 :return 0.04}]
                                            :funding {:status "not_applicable"}
                                            :warnings []}}
                      :warnings []}
        request {:universe universe}
        focused-discovery (api-v2-discovery/normalize-discovery discovery-body)
        facade-discovery (api-v2/normalize-discovery discovery-body)
        focused-bundle (api-v2-bundle/normalize-history-bundle request history-body)
        facade-bundle (api-v2/normalize-history-bundle request history-body)
        focused-aligned (api-v2-alignment/align-api-v2-history-inputs
                         {:universe universe
                          :api-v2-history focused-bundle
                          :as-of-ms 4000
                          :stale-after-ms 10000
                          :min-observations 2})
        facade-aligned (api-v2/align-api-v2-history-inputs
                        {:universe universe
                         :api-v2-history facade-bundle
                         :as-of-ms 4000
                         :stale-after-ms 10000
                         :min-observations 2})]
    (is (= {:outer-key {:inner-key 1}
            :items [{:camel-case-key 2}]}
           (api-v2-codec/normalize-api-map
            {"outer_key" {"innerKey" 1}
             "items" [{"camelCaseKey" 2}]})))
    (is (= (api-v2/normalize-api-map {"outer_key" {"innerKey" 1}})
           (api-v2-codec/normalize-api-map {"outer_key" {"innerKey" 1}})))
    (is (= facade-discovery focused-discovery))
    (is (= facade-bundle focused-bundle))
    (is (= facade-aligned focused-aligned))
    (is (= [1000 2000]
           (history-calendar/common-calendar [[{:time-ms 1000}
                                               {:time-ms 2000}
                                               {:time-ms 3000}]
                                              [{:time-ms 1000}
                                               {:time-ms 2000}]])))
    (is (= [{:start-ms 1000
             :end-ms 2000
             :dt-days (/ 1000 day-ms)
             :dt-years (/ (/ 1000 day-ms) 365.2425)}]
           (history-calendar/return-intervals [1000 2000])))
    (is (= {:as-of-ms 4000
            :latest-common-ms 3000
            :oldest-common-ms 1000
            :age-ms 1000
            :stale? false}
           (history-calendar/freshness [1000 2000 3000] 4000 10000)))))

(deftest api-v2-codec-key-normalization-is-cached-and-stable-test
  (let [payload {"time_ms" 1 :closePrice 2 "indexValue" {"innerKey" 3} 4 "raw"}
        expected {:time-ms 1 :close-price 2 :index-value {:inner-key 3} 4 "raw"}]
    (is (= expected (api-v2-codec/normalize-api-map payload)))
    (is (= expected (api-v2-codec/normalize-api-map payload))
        "A second pass over cached keys yields the identical structure.")
    (is (= (api-v2-codec/normalize-api-map {:time_ms 1})
           (api-v2-codec/normalize-api-map {"time_ms" 1}))
        "Keyword and string spellings of a key share one normalized form.")
    (is (= :not-applicable
           (api-v2-codec/keyword-like "not_applicable")
           (api-v2-codec/keyword-like "not_applicable"))
        "keyword-like stays stable across cached repeats.")
    (is (nil? (api-v2-codec/keyword-like nil))
        "Non-string inputs keep the uncached coercion behavior.")))
