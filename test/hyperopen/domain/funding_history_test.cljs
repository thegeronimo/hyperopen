(ns hyperopen.domain.funding-history-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.domain.funding-history :as funding-history]))

(deftest normalize-info-funding-row-maps-and-validates-shape-test
  (let [row (funding-history/normalize-info-funding-row
             {:time 1700000000000
              :delta {:type "funding"
                      :coin "HYPE"
                      :usdc "-1.2500"
                      :szi "-250.5"
                      :fundingRate "0.00045"}})]
    (is (= "HYPE" (:coin row)))
    (is (= 1700000000000 (:time-ms row)))
    (is (= :short (:position-side row)))
    (is (= 250.5 (:size-raw row)))
    (is (= -1.25 (:payment-usdc-raw row)))
    (is (= 4.5e-4 (:funding-rate-raw row)))))

(deftest normalize-info-funding-row-supports-direct-row-shape-test
  (let [row (funding-history/normalize-info-funding-row
             {:time 1700000000000
              :coin "BTC"
              :usdc "0.1250"
              :szi "15.5"
              :fundingRate "0.0002"})]
    (is (= "BTC" (:coin row)))
    (is (= 1700000000000 (:time-ms row)))
    (is (= :long (:position-side row)))
    (is (= 15.5 (:size-raw row)))
    (is (= 0.125 (:payment-usdc-raw row)))
    (is (= 2.0e-4 (:funding-rate-raw row)))))

(deftest normalize-funding-history-filters-is-deterministic-for-fixed-now-test
  (let [now 1700600000000
        filters {:coin-set #{"BTC" "ETH"}
                 :start-time-ms 1700700000000
                 :end-time-ms 1700100000000}
        a (funding-history/normalize-funding-history-filters filters now)
        b (funding-history/normalize-funding-history-filters filters now)]
    (is (= a b))
    ;; Inverted input range is normalized.
    (is (= [1700100000000 1700700000000]
           [(:start-time-ms a) (:end-time-ms a)]))))

(deftest merge-and-filter-funding-history-rows-dedupes-by-id-test
  (let [row-a (funding-history/normalize-ws-funding-row {:time 1700000000000
                                                          :coin "HYPE"
                                                          :usdc "1.0"
                                                          :szi "100.0"
                                                          :fundingRate "0.0001"})
        row-b (funding-history/normalize-ws-funding-row {:time 1700003600000
                                                          :coin "BTC"
                                                          :usdc "-2.0"
                                                          :szi "-50.0"
                                                          :fundingRate "-0.0003"})
        merged (funding-history/merge-funding-history-rows [row-a row-b row-a] [])
        normalized-filters (funding-history/normalize-funding-history-filters
                            {:coin-set #{"BTC"}
                             :start-time-ms 0
                             :end-time-ms 2000000000000}
                            1700000000000)
        projected (funding-history/filter-funding-history-rows merged normalized-filters)]
    (is (= 2 (count merged)))
    (is (= [1700003600000 1700000000000] (mapv :time-ms merged)))
    (is (= ["BTC"] (mapv :coin projected)))))

(deftest normalize-info-funding-rows-drops-invalid-and-non-funding-deltas-test
  (let [rows [{:time 1700000000000
               :delta {:type "funding"
                       :coin "BTC"
                       :usdc "1.0"
                       :szi "10"
                       :fundingRate "0.0001"}}
              {:time 1700000000001
               :delta {:type "trade"
                       :coin "BTC"
                       :usdc "2.0"
                       :szi "20"
                       :fundingRate "0.0002"}}
              {:time 1700000000002
               :delta {:type "funding"
                       :coin nil
                       :usdc "3.0"
                       :szi "30"
                       :fundingRate "0.0003"}}]
        normalized (funding-history/normalize-info-funding-rows rows)]
    (is (= 1 (count normalized)))
    (is (= :info (:source (first normalized))))
    (is (= "BTC" (:coin (first normalized))))))

(deftest normalize-ws-funding-rows-drops-invalid-rows-test
  (let [rows [{:time 1700000000000
               :coin "ETH"
               :usdc "1.5"
               :szi "-25"
               :fundingRate "-0.0004"}
              {:time nil
               :coin "ETH"
               :usdc "2.5"
               :szi "10"
               :fundingRate "0.0002"}
              {:time 1700000000500
               :coin 42
               :usdc "1.0"
               :szi "5"
               :fundingRate "0.0001"}]
        normalized (funding-history/normalize-ws-funding-rows rows)]
    (is (= 1 (count normalized)))
    (is (= :ws (:source (first normalized))))
    (is (= "ETH" (:coin (first normalized))))))

(deftest merge-funding-history-rows-prefers-latest-row-for-duplicate-id-test
  (let [existing [{:id "dup"
                   :time-ms 1700000000000
                   :coin "BTC"
                   :payment 1}]
        incoming [{:id "dup"
                   :time-ms 1700000000000
                   :coin "BTC"
                   :payment 2}]
        merged (funding-history/merge-funding-history-rows existing incoming)]
    (is (= 1 (count merged)))
    (is (= 2 (:payment (first merged))))))

(deftest filter-funding-history-rows-applies-window-coin-and-tie-break-sort-test
  (let [rows [{:id "b" :time-ms 200 :coin "BTC"}
              {:id "a" :time-ms 200 :coin "BTC"}
              {:id "c" :time-ms 150 :coin "ETH"}
              {:id "d" :time-ms 99 :coin "BTC"}
              {:id "e" :time-ms 250 :coin "SOL"}
              {:id "f" :time-ms nil :coin "BTC"}]
        filters {:coin-set #{"BTC" "ETH"}
                 :start-time-ms 100
                 :end-time-ms 250}
        projected (funding-history/filter-funding-history-rows rows filters)]
    (is (= ["a" "b" "c"] (mapv :id projected)))))

(deftest normalize-info-funding-row-maps-delta-shape-test
  (let [row (funding-history/normalize-info-funding-row
             {:time 1700000000000
              :delta {:type "funding"
                      :coin "HYPE"
                      :usdc "-1.2500"
                      :szi "-250.5"
                      :fundingRate "0.00045"}})]
    (is (= "HYPE" (:coin row)))
    (is (= 1700000000000 (:time-ms row)))
    (is (= :short (:position-side row)))
    (is (= 250.5 (:size-raw row)))
    (is (= -1.25 (:payment-usdc-raw row)))
    (is (= 4.5e-4 (:funding-rate-raw row)))))

(deftest funding-history-merge-and-filter-are-deterministic-test
  (let [row-a (funding-history/normalize-ws-funding-row {:time 1700000000000
                                                          :coin "HYPE"
                                                          :usdc "1.0"
                                                          :szi "100.0"
                                                          :fundingRate "0.0001"})
        row-b (funding-history/normalize-ws-funding-row {:time 1700003600000
                                                          :coin "BTC"
                                                          :usdc "-2.0"
                                                          :szi "-50.0"
                                                          :fundingRate "-0.0003"})
        merged (funding-history/merge-funding-history-rows [row-a row-b row-a] [])
        filters (funding-history/normalize-funding-history-filters
                 {:coin-set #{"BTC"}
                  :start-time-ms 0
                  :end-time-ms 2000000000000}
                 1700000000000
                 funding-history/default-window-ms)]
    (is (= 2 (count merged)))
    (is (= [1700003600000 1700000000000] (mapv :time-ms merged)))
    (is (= ["BTC"]
           (mapv :coin
                 (funding-history/filter-funding-history-rows merged filters))))))
