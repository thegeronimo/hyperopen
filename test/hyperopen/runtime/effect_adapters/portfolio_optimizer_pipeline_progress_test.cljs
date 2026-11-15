(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline-progress-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.progress :as progress]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline]))

(deftest fetch-progress-callback-summarizes-backend-and-info-sources-test
  (let [fetch-progress-callback
        @#'hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline/fetch-progress-callback
        store (atom {:portfolio
                     {:optimizer
                      {:optimization-progress
                       (progress/begin-progress
                        {:run-id "run-debug"
                         :request {:universe [{:instrument-id "perp:BTC"}
                                              {:instrument-id "perp:ETH"}
                                              {:instrument-id "perp:DOGE"}]}})}}})
        progress-path [:portfolio
                       :optimizer
                       :optimization-progress
                       :steps
                       0]
        progress-detail #(get-in @store (conj progress-path :detail))
        progress-percent #(get-in @store (conj progress-path :percent))
        callback (fetch-progress-callback store "run-debug")]
    (callback {:source :backend-api
               :status :started
               :requested-count 3
               :completed 0
               :total 1
               :percent 0})
    (is (= "backend API: loading 3 assets, /info: 0/0 requests"
           (progress-detail)))
    (is (= 5 (progress-percent)))
    (callback {:source :backend-api
               :status :loading
               :requested-count 3
               :loaded-count 2
               :completed 1
               :total 2
               :percent 50})
    (is (= "backend API: 2/3 assets, /info: 0/0 requests"
           (progress-detail)))
    (is (= 50 (progress-percent)))
    (callback {:source :backend-api
               :status :succeeded
               :requested-count 3
               :returned-count 3
               :usable-count 2
               :fallback-asset-count 1
               :completed 1
               :total 1
               :percent 100})
    (callback {:source :info-endpoint
               :status :started
               :asset-count 1
               :completed 0
               :total 2
               :percent 0})
    (callback {:source :info-endpoint
               :kind :candles
               :coin "ETH"
               :completed 1
               :total 2
               :percent 50})
    (is (= "backend API: 2/3 assets, /info: 1/2 requests"
           (progress-detail)))
    (is (= 75 (progress-percent)))))

(deftest fetch-progress-callback-keeps-percent-monotonic-across-phases-test
  (let [fetch-progress-callback
        @#'hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline/fetch-progress-callback
        store (atom {:portfolio
                     {:optimizer
                      {:optimization-progress
                       (progress/begin-progress
                        {:run-id "run-monotonic"
                         :request {:universe [{:instrument-id "perp:BTC"}
                                              {:instrument-id "perp:ETH"}
                                              {:instrument-id "perp:DOGE"}]}})}}})
        progress-path [:portfolio
                       :optimizer
                       :optimization-progress
                       :steps
                       0]
        progress-detail #(get-in @store (conj progress-path :detail))
        progress-percent #(get-in @store (conj progress-path :percent))
        callback (fetch-progress-callback store "run-monotonic")]
    (callback {:source :backend-api
               :status :started
               :requested-count 3
               :completed 0
               :total 1
               :percent 0})
    (callback {:source :backend-api
               :status :loading
               :requested-count 3
               :loaded-count 3
               :completed 2
               :total 2
               :percent 100})
    (is (= 95 (progress-percent)))
    (callback {:source :backend-api
               :status :succeeded
               :requested-count 3
               :returned-count 3
               :usable-count 3
               :fallback-asset-count 0
               :completed 1
               :total 1
               :percent 100})
    (is (= 95 (progress-percent)))
    (callback {:source :backend-api
               :status :started
               :requested-count 1
               :completed 0
               :total 1
               :percent 0})
    (is (= "backend API: loading 1 asset, /info: 0/0 requests"
           (progress-detail)))
    (is (= 95 (progress-percent)))))
