(ns hyperopen.portfolio.optimizer.application.history-loader-api-v2-calendar-poisoning-test
  "Regression coverage for the 2026-07-08 live collapse: a backend bundle with
  EMPTY shared calendars plus one young listing whose few served days are
  disjoint from a stale-ended series used to exclude EVERY universe member with
  a single anonymous insufficient-common-history warning. Alignment must peel
  the poisoning member individually (with a per-instrument warning) and align
  the rest, and must always publish pre-alignment served observation counts."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]))

(def day-ms
  (* 24 60 60 1000))

(defn- day-start-ms
  [day]
  (.getTime (js/Date. (str day "T00:00:00.000Z"))))

(def ^:private t0
  (day-start-ms "2026-01-01"))

(defn- day
  [idx]
  (+ t0 (* idx day-ms)))

(defn- api-points
  [start-day n]
  (mapv (fn [idx]
          {:time_ms (day (+ start-day idx))
           :close (+ 100 idx)
           :return (when (pos? idx) 0.01)})
        (range n)))

(defn- perp-instrument
  [symbol]
  {:instrument-id (str "perp:" symbol)
   :market-type :perp
   :coin symbol
   :optimizer-history/instrument-id (str "hl:perp:" symbol)})

(defn- native-series
  [symbol points]
  {:instrument_id (str "hl:perp:" symbol)
   :lineage_kind "native"
   :series_kind "market_price"
   :points points
   :funding {:status "available" :annualized_carry 0.01}
   :warnings []})

(defn- poisoned-bundle
  "The live 2026-07-08 shape: per-instrument series are served, but the backend's
  shared calendars and aligned returns are all EMPTY (status partial)."
  [universe series-by-key]
  (api-v2/normalize-history-bundle
   {:universe universe}
   {:contract_version "optimizer-history-api-v2"
    :request_id "rid-poisoned"
    :dataset_version "dv-poisoned"
    :status "partial"
    :common_calendar []
    :return_calendar []
    :aligned_returns_by_instrument
    (into {}
          (map (fn [[k series]]
                 [k {:instrument_id (:instrument_id series) :returns []}]))
          series-by-key)
    :series_by_instrument series-by-key
    :warnings []}))

(defn- align
  [universe series-by-key]
  (history-loader/align-history-inputs
   {:universe universe
    :api-v2-history (poisoned-bundle universe series-by-key)}))

(deftest poisoning-member-is-peeled-and-survivors-align-test
  (let [universe [(perp-instrument "AAA")
                  (perp-instrument "BBB")
                  (perp-instrument "GRAM")]
        aligned (align universe
                       {"perp:AAA" (native-series "AAA" (api-points 0 40))
                        "perp:BBB" (native-series "BBB" (api-points 0 40))
                        "perp:GRAM" (native-series "GRAM" (api-points 45 5))})]
    (is (= ["perp:AAA" "perp:BBB"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= ["perp:GRAM"]
           (mapv :instrument-id (:excluded-instruments aligned))))
    (is (= 39 (count (:return-calendar aligned))))
    (is (= 39 (count (get-in aligned [:return-series-by-instrument "perp:AAA"]))))
    (is (= 40 (count (get-in aligned [:price-series-by-instrument "perp:AAA"]))))
    (is (= [{:code :insufficient-common-history
             :instrument-id "perp:GRAM"
             :observations 0
             :required 1}]
           (filterv #(= :insufficient-common-history (:code %))
                    (:warnings aligned))))
    (is (= {"perp:AAA" 40 "perp:BBB" 40 "perp:GRAM" 5}
           (:served-observations-by-instrument aligned)))
    (is (= #{"perp:AAA" "perp:BBB"}
           (set (keys (:raw-price-series-by-instrument aligned)))))))

(deftest poisoning-recovery-without-poisoner-is-behavior-preserving-test
  (let [universe [(perp-instrument "AAA")
                  (perp-instrument "BBB")]
        aligned (align universe
                       {"perp:AAA" (native-series "AAA" (api-points 0 40))
                        "perp:BBB" (native-series "BBB" (api-points 0 40))})]
    (is (= ["perp:AAA" "perp:BBB"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= [] (:excluded-instruments aligned)))
    (is (= 39 (count (:return-calendar aligned))))
    (is (= []
           (filterv #(= :insufficient-common-history (:code %))
                    (:warnings aligned))))
    (is (= {"perp:AAA" 40 "perp:BBB" 40}
           (:served-observations-by-instrument aligned)))))

(deftest totally-disjoint-pair-keeps-the-deeper-member-test
  (let [universe [(perp-instrument "DEEP")
                  (perp-instrument "YOUNG")]
        aligned (align universe
                       {"perp:DEEP" (native-series "DEEP" (api-points 0 40))
                        "perp:YOUNG" (native-series "YOUNG" (api-points 45 5))})]
    (is (= ["perp:DEEP"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= ["perp:YOUNG"]
           (mapv :instrument-id (:excluded-instruments aligned))))
    (is (= [{:code :insufficient-common-history
             :instrument-id "perp:YOUNG"
             :observations 0
             :required 1}]
           (filterv #(= :insufficient-common-history (:code %))
                    (:warnings aligned))))))

(deftest legacy-alignment-publishes-served-observations-test
  (let [aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id "perp:AAA"
                              :market-type :perp
                              :coin "AAA"}]
                  :candle-history-by-coin
                  {"AAA" (mapv (fn [idx]
                                 {:t (day idx) :c (+ 100 idx)})
                               (range 12))}})]
    (is (= {"perp:AAA" 12}
           (:served-observations-by-instrument aligned)))))
