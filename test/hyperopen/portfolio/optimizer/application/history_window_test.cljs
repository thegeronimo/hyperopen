(ns hyperopen.portfolio.optimizer.application.history-window-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]))

(def day-ms
  (* 24 60 60 1000))

(defn- day-start-ms
  [day]
  (.getTime (js/Date. (str day "T00:00:00.000Z"))))

(deftest align-api-v2-history-window-identifies-weekday-proxy-limiter-test
  (let [d0 (day-start-ms "2026-01-01")
        day (fn [n] (+ d0 (* n day-ms)))
        universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}
                  {:instrument-id "external:SP500"
                   :market-type :external
                   :coin "SP500"
                   :optimizer-history/instrument-id "proxy:sp500"}]
        btc-points (mapv (fn [n]
                           {:time_ms (day n)
                            :close (+ 100 n)
                            :return (when (pos? n) 0.001)})
                         (range 10))
        proxy-days [0 1 2 5 6 7 8 9]
        proxy-points (mapv (fn [idx n]
                             {:time_ms (day n)
                              :close (+ 4000 idx)
                              :return (when (pos? idx) 0.0005)})
                           (range)
                           proxy-days)
        return-calendar (mapv day [1 2 5 6 7 8 9])
        normalized (api-v2/normalize-history-bundle
                    {:universe universe}
                    {:contract_version "optimizer-history-api-v2"
                     :request_id "rid-weekday-proxy"
                     :dataset_version "dv-weekday-proxy"
                     :status "ok"
                     :common_calendar (mapv day proxy-days)
                     :return_calendar return-calendar
                     :aligned_returns_by_instrument
                     {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                     :returns (vec (repeat 7 0.001))}
                      "proxy:sp500" {:instrument_id "proxy:sp500"
                                     :returns (vec (repeat 7 0.0005))}}
                     :series_by_instrument
                     {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                     :lineage_kind "native"
                                     :series_kind "market_price"
                                     :points btc-points
                                     :funding {:status "available"
                                               :annualized_carry 0}
                                     :warnings []}
                      "proxy:sp500" {:instrument_id "proxy:sp500"
                                     :lineage_kind "approved_proxy"
                                     :series_kind "market_price"
                                     :points proxy-points
                                     :funding {:status "not_applicable"}
                                     :warnings [{:code "proxy-history-used"}]}}
                     :warnings []})
        aligned (api-v2/align-api-v2-history-inputs
                 {:universe universe
                  :api-v2-history normalized
                  :min-observations 2})]
    (is (= 7
           (get-in aligned [:history-window :return-observations])))
    (is (= 9
           (get-in aligned [:history-window :return-days])))
    (is (= "external:SP500"
           (get-in aligned [:history-window :limiting-instrument-id])))
    (is (= :fewest-return-observations
           (get-in aligned [:history-window :limiting-reason])))
    (is (= 7
           (get-in aligned [:history-window :limiting-source-return-observations])))))

(deftest align-api-v2-poisoned-response-calendar-recomputed-client-side-test
  (let [d0 (day-start-ms "2026-01-01")
        day (fn [n] (+ d0 (* n day-ms)))
        universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}
                  {:instrument-id "perp:ETH"
                   :market-type :perp
                   :coin "ETH"
                   :optimizer-history/instrument-id "hl:perp:ETH"}]
        long-points (fn [base]
                      (mapv (fn [n]
                              {:time_ms (day n)
                               :close (+ base n)
                               :return (when (pos? n) 0.001)})
                            (range 10)))
        thin-points (mapv (fn [n]
                            {:time_ms (day n)
                             :close (+ 10 n)
                             :return (when (= n 9) 0.002)})
                          [8 9])
        series (fn [id points funding-status]
                 {:instrument_id id
                  :lineage_kind "native"
                  :series_kind "market_price"
                  :points points
                  :funding {:status funding-status :annualized_carry 0}
                  :warnings []})
        normalized (api-v2/normalize-history-bundle
                    {:universe universe}
                    {:contract_version "optimizer-history-api-v2"
                     :request_id "rid-poisoned"
                     :dataset_version "dv-poisoned"
                     :status "ok"
                     :common_calendar (mapv day [8 9])
                     :return_calendar (mapv day [9])
                     :aligned_returns_by_instrument
                     {"hl:perp:BTC" {:instrument_id "hl:perp:BTC" :returns [0.001]}
                      "hl:perp:ETH" {:instrument_id "hl:perp:ETH" :returns [0.001]}
                      "hl:perp:NEW" {:instrument_id "hl:perp:NEW" :returns [0.002]}}
                     :series_by_instrument
                     {"hl:perp:BTC" (series "hl:perp:BTC" (long-points 100) "available")
                      "hl:perp:ETH" (series "hl:perp:ETH" (long-points 2000) "available")
                      "hl:perp:NEW" (series "hl:perp:NEW" thin-points "available")}
                     :warnings []})
        aligned (api-v2/align-api-v2-history-inputs
                 {:universe universe
                  :api-v2-history normalized
                  :min-observations 2})]
    (is (= 9 (get-in aligned [:history-window :return-observations]))
        "The shared window is re-intersected over the members' own series, not adopted from the backend's superset intersection.")
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (:eligible-instruments aligned))))))

(deftest align-api-v2-mixed-cadence-poisoned-response-calendar-recomputed-test
  (let [d0 (day-start-ms "2026-01-01")
        day (fn [n] (+ d0 (* n day-ms)))
        universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}
                  {:instrument-id "external:AAPL"
                   :market-type :external
                   :coin "AAPL"
                   :optimizer-history/instrument-id "proxy:aapl"}]
        points (fn [base days]
                 (mapv (fn [n]
                         {:time_ms (day n)
                          :close (+ base n)
                          :return (when (pos? n) 0.001)})
                       days))
        series (fn [id point-series]
                 {:instrument_id id
                  :lineage_kind "native"
                  :series_kind "market_price"
                  :points point-series
                  :funding {:status "available" :annualized_carry 0}
                  :warnings []})
        normalized (api-v2/normalize-history-bundle
                    {:universe universe}
                    {:contract_version "optimizer-history-api-v2"
                     :request_id "rid-mixed-cadence-poisoned"
                     :dataset_version "dv-mixed-cadence-poisoned"
                     :status "ok"
                     :common_calendar (mapv day [4 5 6 7 8 9])
                     :return_calendar (mapv day [5 6 7 8 9])
                     :aligned_returns_by_instrument {}
                     :series_by_instrument
                     {"hl:perp:BTC" (series "hl:perp:BTC"
                                                   (points 100 (range 10)))
                      "proxy:aapl" (series "proxy:aapl"
                                             (points 200 [0 1 2 7 8]))
                      "hl:perp:SOPH" (series "hl:perp:SOPH"
                                                    (points 10 (range 4 10)))}
                     :warnings []})
        aligned (api-v2/align-api-v2-history-inputs
                 {:universe universe
                  :api-v2-history normalized
                  :min-observations 2})]
    (is (= (mapv day [0 1 2 7 8])
           (:calendar aligned)))
    (is (= (mapv day [1 2 7 8])
           (:return-calendar aligned))
        "A later daily response calendar must not clip earlier timestamps shared by the selected mixed-cadence members.")
    (is (= ["perp:BTC" "external:AAPL"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= #{"perp:BTC" "external:AAPL"}
           (set (keys (:return-series-by-instrument aligned)))))
    (is (not (contains? (:return-series-by-instrument aligned)
                        "hl:perp:SOPH")))
    (is (= :api-v2-point-returns
           (get-in aligned [:alignment-source :kind])))))

(defn- align-api-v2-common-boundary-fixture
  [response-common-days]
  (let [d0 (day-start-ms "2026-01-01")
        day (fn [n] (+ d0 (* n day-ms)))
        universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :optimizer-history/instrument-id "hl:perp:BTC"}
                  {:instrument-id "perp:ETH"
                   :market-type :perp
                   :coin "ETH"
                   :optimizer-history/instrument-id "hl:perp:ETH"}]
        points (fn [base days]
                 (mapv (fn [n]
                         {:time_ms (day n)
                          :close (+ base n)
                          :return (when (pos? n) 0.001)})
                       days))
        series (fn [id point-series]
                 {:instrument_id id
                  :lineage_kind "native"
                  :series_kind "market_price"
                  :points point-series
                  :funding {:status "available" :annualized_carry 0}
                  :warnings []})
        normalized (api-v2/normalize-history-bundle
                    {:universe universe}
                    {:contract_version "optimizer-history-api-v2"
                     :request_id "rid-common-boundary"
                     :dataset_version "dv-common-boundary"
                     :status "ok"
                     :common_calendar (mapv day response-common-days)
                     :return_calendar (mapv day [1 3])
                     :aligned_returns_by_instrument
                     {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                     :returns [0.01 0.02]}
                      "hl:perp:ETH" {:instrument_id "hl:perp:ETH"
                                     :returns [0.03 0.04]}
                      "hl:perp:SOPH" {:instrument_id "hl:perp:SOPH"
                                      :returns [0.05 0.06]}}
                     :series_by_instrument
                     {"hl:perp:BTC" (series "hl:perp:BTC"
                                                   (points 100 [0 1 3]))
                      "hl:perp:ETH" (series "hl:perp:ETH"
                                                   (points 2000 [0 1 3]))
                      "hl:perp:SOPH" (series "hl:perp:SOPH"
                                                    (points 10 response-common-days))}
                     :warnings []})]
    {:day day
     :aligned (api-v2/align-api-v2-history-inputs
               {:universe universe
                :api-v2-history normalized
                :min-observations 2})}))

(deftest align-api-v2-superset-missing-common-boundary-recomputed-test
  (let [{:keys [day aligned]} (align-api-v2-common-boundary-fixture [1 3])]
    (is (= (mapv day [0 1 3])
           (:calendar aligned))
        "A superset response must not drop a selected member's interval predecessor even when it covers every return timestamp.")
    (is (= (mapv day [1 3])
           (:return-calendar aligned)))
    (is (= [{:start-ms (day 0) :end-ms (day 1) :dt-days 1}
            {:start-ms (day 1) :end-ms (day 3) :dt-days 2}]
           (mapv #(select-keys % [:start-ms :end-ms :dt-days])
                 (:return-intervals aligned))))
    (is (= {"perp:BTC" [0.001 0.001]
            "perp:ETH" [0.001 0.001]}
           (:return-series-by-instrument aligned)))
    (is (= :api-v2-point-returns
           (get-in aligned [:alignment-source :kind])))))

(deftest align-api-v2-superset-covering-member-calendars-preserves-aligned-test
  (let [{:keys [day aligned]} (align-api-v2-common-boundary-fixture [0 1 3])]
    (is (= (mapv day [0 1 3])
           (:calendar aligned)))
    (is (= (mapv day [1 3])
           (:return-calendar aligned)))
    (is (= {"perp:BTC" [0.01 0.02]
            "perp:ETH" [0.03 0.04]}
           (:return-series-by-instrument aligned)))
    (is (= :api-v2-aligned-returns
           (get-in aligned [:alignment-source :kind])))))

(deftest align-history-inputs-history-window-identifies-late-starting-limiter-test
  (let [day (fn [n] (* n day-ms))
        aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id "perp:NEW"
                              :market-type :perp
                              :coin "NEW"}]
                  :candle-history-by-coin {"BTC" [{:time-ms (day 0) :close "100"}
                                                  {:time-ms (day 1) :close "101"}
                                                  {:time-ms (day 2) :close "102"}
                                                  {:time-ms (day 3) :close "103"}]
                                           "NEW" [{:time-ms (day 1) :close "50"}
                                                  {:time-ms (day 2) :close "51"}
                                                  {:time-ms (day 3) :close "52"}]}
                  :funding-history-by-coin {}
                  :as-of-ms (day 4)
                  :stale-after-ms (* 10 day-ms)})]
    (is (= [(day 1) (day 2) (day 3)]
           (:calendar aligned)))
    (is (= 2
           (get-in aligned [:history-window :return-observations])))
    (is (= 2
           (get-in aligned [:history-window :return-days])))
    (is (= "perp:NEW"
           (get-in aligned [:history-window :limiting-instrument-id])))
    (is (= :starts-later
           (get-in aligned [:history-window :limiting-reason])))))
