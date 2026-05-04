(ns hyperopen.portfolio.optimizer.application.history-loader-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]))

(defn- near?
  ([expected actual]
   (near? expected actual 0.0000001))
  ([expected actual tolerance]
   (< (js/Math.abs (- expected actual)) tolerance)))

(def day-ms
  (* 24 60 60 1000))

(defn- day-start-ms
  [day]
  (.getTime (js/Date. (str day "T00:00:00.000Z"))))

(defn- summary-from-points
  [points]
  {:accountValueHistory (mapv (fn [[time-ms account-value _pnl-value]]
                                [time-ms account-value])
                              points)
   :pnlHistory (mapv (fn [[time-ms _account-value pnl-value]]
                       [time-ms pnl-value])
                     points)})

(defn- candle-rows
  [time-and-close-pairs]
  (mapv (fn [[time-ms close]]
          {:time time-ms
           :close (str close)})
        time-and-close-pairs))

(defn- vault-instrument-id
  [vault-address]
  (str "vault:" vault-address))

(defn- align-market-and-vault-history
  [vault-address portfolio candle-history]
  (history-loader/align-history-inputs
   {:universe [{:instrument-id "perp:BTC"
                :market-type :perp
                :coin "BTC"}
               {:instrument-id (vault-instrument-id vault-address)
                :market-type :vault
                :coin (vault-instrument-id vault-address)
                :vault-address vault-address}]
    :candle-history-by-coin {"BTC" (candle-rows candle-history)}
    :vault-details-by-address {vault-address
                               {:portfolio portfolio}}
    :as-of-ms (+ (apply max (map first candle-history)) day-ms)
    :stale-after-ms (* 2 day-ms)}))

(deftest build-history-request-plan-fetches-candles-for-all-assets-and-funding-for-perps-test
  (let [plan (history-loader/build-history-request-plan
              [{:instrument-id "perp:BTC"
                :market-type :perp
                :coin "BTC"}
               {:instrument-id "spot:PURR"
                :market-type :spot
                :coin "PURR"}
               {:instrument-id "perp:BTC-copy"
                :market-type :perp
                :coin "BTC"}
               {:instrument-id "missing-coin"
                :market-type :spot}]
              {:interval :1d
               :bars 180
               :priority :low
               :now-ms 2000000
               :funding-window-ms 86400000})]
    (is (= [{:coin "BTC"
             :instrument-ids ["perp:BTC" "perp:BTC-copy"]
             :opts {:interval :1d
                    :bars 180
                    :priority :low
                    :cache-key [:portfolio-optimizer :candles "BTC" :1d 180]
                    :dedupe-key [:portfolio-optimizer :candles "BTC" :1d 180]}}
            {:coin "PURR"
             :instrument-ids ["spot:PURR"]
             :opts {:interval :1d
                    :bars 180
                    :priority :low
                    :cache-key [:portfolio-optimizer :candles "PURR" :1d 180]
                    :dedupe-key [:portfolio-optimizer :candles "PURR" :1d 180]}}]
           (:candle-requests plan)))
    (is (= [{:coin "BTC"
             :instrument-ids ["perp:BTC" "perp:BTC-copy"]
             :opts {:priority :low
                    :start-time-ms (- 2000000 86400000)
                    :end-time-ms 2000000
                    :cache-key [:portfolio-optimizer :funding "BTC" (- 2000000 86400000) 2000000]
                    :dedupe-key [:portfolio-optimizer :funding "BTC" (- 2000000 86400000) 2000000]}}]
           (:funding-requests plan)))
    (is (= [{:code :missing-history-coin
             :instrument-id "missing-coin"
             :market-type :spot}]
           (:warnings plan)))))

(deftest build-history-request-plan-fetches-vault-details-without-market-candles-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        plan (history-loader/build-history-request-plan
              [{:instrument-id "perp:BTC"
                :market-type :perp
                :coin "BTC"}
               {:instrument-id (str "vault:" vault-address)
                :market-type :vault
                :coin (str "vault:" vault-address)
                :vault-address vault-address}]
              {:interval :1d
               :bars 180
               :priority :low
               :now-ms 2000000
               :funding-window-ms 86400000})]
    (is (= [{:coin "BTC"
             :instrument-ids ["perp:BTC"]
             :opts {:interval :1d
                    :bars 180
                    :priority :low
                    :cache-key [:portfolio-optimizer :candles "BTC" :1d 180]
                    :dedupe-key [:portfolio-optimizer :candles "BTC" :1d 180]}}]
           (:candle-requests plan)))
    (is (= [{:coin "BTC"
             :instrument-ids ["perp:BTC"]
             :opts {:priority :low
                    :start-time-ms (- 2000000 86400000)
                    :end-time-ms 2000000
                    :cache-key [:portfolio-optimizer :funding "BTC" (- 2000000 86400000) 2000000]
                    :dedupe-key [:portfolio-optimizer :funding "BTC" (- 2000000 86400000) 2000000]}}]
           (:funding-requests plan)))
    (is (= [{:vault-address vault-address
             :instrument-ids [(str "vault:" vault-address)]
             :opts {:priority :low
                    :cache-key [:portfolio-optimizer :vault-details vault-address]
                    :dedupe-key [:portfolio-optimizer :vault-details vault-address]}}]
           (:vault-detail-requests plan)))
    (is (= [] (:warnings plan)))))

(deftest align-history-inputs-aligns-common-calendar-and-exposes-funding-carry-test
  (let [aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id "spot:PURR"
                              :market-type :spot
                              :coin "PURR"}]
                  :candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                  {:time 2000 :close "110"}
                                                  {:time 3000 :close "121"}]
                                           "PURR" [{:time-ms 1000 :c "10"}
                                                   {:time-ms 2000 :c "11"}
                                                   {:time-ms 3000 :c "11"}]}
                  :funding-history-by-coin {"BTC" [{:time-ms 1000 :funding-rate-raw "0.001"}
                                                   {:time-ms 2000 :fundingRate 0.002}]}
                  :as-of-ms 4000
                  :stale-after-ms 5000
                  :funding-periods-per-year 100})]
    (is (= [1000 2000 3000] (:calendar aligned)))
    (is (= [2000 3000] (:return-calendar aligned)))
    (is (= ["perp:BTC" "spot:PURR"]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument "perp:BTC" 0])))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument "perp:BTC" 1])))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument "spot:PURR" 0])))
    (is (near? 0.0 (get-in aligned [:return-series-by-instrument "spot:PURR" 1])))
    (is (near? 0.15 (get-in aligned [:funding-by-instrument "perp:BTC" :annualized-carry])))
    (is (= :market-funding-history
           (get-in aligned [:funding-by-instrument "perp:BTC" :source])))
    (is (= :not-applicable
           (get-in aligned [:funding-by-instrument "spot:PURR" :source])))
    (is (= [] (:excluded-instruments aligned)))
    (is (= [] (:warnings aligned)))
    (is (= {:as-of-ms 4000
            :latest-common-ms 3000
            :oldest-common-ms 1000
            :age-ms 1000
            :stale? false}
           (:freshness aligned)))))

(deftest align-history-inputs-aligns-vault-detail-return-history-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        vault-instrument-id (str "vault:" vault-address)
        aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id vault-instrument-id
                              :market-type :vault
                              :coin vault-instrument-id
                              :vault-address vault-address}]
                  :candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                  {:time 2000 :close "110"}
                                                  {:time 3000 :close "121"}]}
                  :vault-details-by-address {vault-address
                                             {:portfolio
                                              {:all-time
                                               {:accountValueHistory [[1000 100]
                                                                      [2000 110]
                                                                      [3000 121]]
                                                :pnlHistory [[1000 0]
                                                             [2000 10]
                                                             [3000 21]]}}}}
                  :as-of-ms 4000
                  :stale-after-ms 5000
                  :funding-periods-per-year 100})]
    (is (= [1000 2000 3000] (:calendar aligned)))
    (is (= ["perp:BTC" vault-instrument-id]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument vault-instrument-id 0])))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument vault-instrument-id 1])))
    (is (= :not-applicable
           (get-in aligned [:funding-by-instrument vault-instrument-id :source])))
    (is (= [] (:excluded-instruments aligned)))
    (is (= [] (:warnings aligned)))))

(deftest align-history-inputs-aligns-vault-and-market-history-by-utc-day-test
  (let [d0 (day-start-ms "2026-04-27")
        d1 (+ d0 day-ms)
        d2 (+ d1 day-ms)
        vault-address "0x1111111111111111111111111111111111111111"
        vault-instrument-id (str "vault:" vault-address)
        aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id vault-instrument-id
                              :market-type :vault
                              :coin vault-instrument-id
                              :vault-address vault-address}]
                  :candle-history-by-coin {"BTC" [{:time d0 :close "100"}
                                                  {:time d1 :close "110"}
                                                  {:time d2 :close "121"}]}
                  :vault-details-by-address {vault-address
                                             {:portfolio
                                              {:all-time
                                               {:accountValueHistory [[(+ d0 (* 23 60 60 1000)) 100]
                                                                      [(+ d1 (* 23 60 60 1000)) 110]
                                                                      [(+ d2 (* 23 60 60 1000)) 121]]
                                                :pnlHistory [[(+ d0 (* 23 60 60 1000)) 0]
                                                             [(+ d1 (* 23 60 60 1000)) 10]
                                                             [(+ d2 (* 23 60 60 1000)) 21]]}}}}
                  :as-of-ms (+ d2 day-ms)
                  :stale-after-ms (* 2 day-ms)})]
    (is (= [d0 d1 d2] (:calendar aligned)))
    (is (= ["perp:BTC" vault-instrument-id]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument vault-instrument-id 0])))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument vault-instrument-id 1])))
    (is (= [] (:warnings aligned)))))

(deftest align-history-inputs-prefers-derived-one-year-vault-history-over-direct-month-test
  (let [prior-all-time-start (day-start-ms "2024-04-30")
        derived-start (day-start-ms "2025-04-30")
        derived-mid (day-start-ms "2025-10-30")
        direct-month-start (day-start-ms "2026-02-28")
        direct-month-second (day-start-ms "2026-03-14")
        direct-month-third (day-start-ms "2026-03-28")
        direct-month-fourth (day-start-ms "2026-04-14")
        end (day-start-ms "2026-04-30")
        vault-address "0x1111111111111111111111111111111111111111"
        vault-instrument-id (str "vault:" vault-address)
        aligned (align-market-and-vault-history
                 vault-address
                 {:all-time (summary-from-points [[prior-all-time-start 80 -20]
                                                  [derived-start 100 0]
                                                  [derived-mid 110 10]
                                                  [end 121 21]])
                  :month (summary-from-points [[direct-month-start 100 0]
                                               [direct-month-second 98 -2]
                                               [direct-month-third 96 -4]
                                               [direct-month-fourth 94 -6]
                                               [end 92 -8]])}
                 [[derived-start 200]
                  [derived-mid 220]
                  [direct-month-start 230]
                  [direct-month-second 228]
                  [direct-month-third 226]
                  [direct-month-fourth 224]
                  [end 242]])]
    (is (= [derived-start derived-mid end] (:calendar aligned)))
    (is (= ["perp:BTC" vault-instrument-id]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument vault-instrument-id 0])))
    (is (near? 0.1 (get-in aligned [:return-series-by-instrument vault-instrument-id 1])))
    (is (= [{:start-ms derived-start
             :end-ms derived-mid
             :dt-days 183
             :dt-years (/ 183 365.2425)}
            {:start-ms derived-mid
             :end-ms end
             :dt-days 182
             :dt-years (/ 182 365.2425)}]
           (:return-intervals aligned)))
    (is (= [] (:warnings aligned)))))

(deftest align-history-inputs-prefers-direct-one-year-vault-summary-over-derived-all-time-test
  (let [prior-all-time-start (day-start-ms "2024-04-30")
        direct-start (day-start-ms "2025-04-30")
        direct-mid (day-start-ms "2025-10-30")
        end (day-start-ms "2026-04-30")
        vault-address "0x1111111111111111111111111111111111111111"
        vault-instrument-id (str "vault:" vault-address)
        aligned (align-market-and-vault-history
                 vault-address
                 {:all-time (summary-from-points [[prior-all-time-start 80 -20]
                                                  [direct-start 100 0]
                                                  [direct-mid 110 10]
                                                  [end 121 21]])
                  :one-year (summary-from-points [[direct-start 100 0]
                                                  [direct-mid 120 20]
                                                  [end 144 44]])
                  :month (summary-from-points [[(day-start-ms "2026-02-28") 100 0]
                                               [(day-start-ms "2026-03-31") 95 -5]
                                               [end 90 -10]])}
                 [[direct-start 200]
                  [direct-mid 220]
                  [(day-start-ms "2026-02-28") 230]
                  [(day-start-ms "2026-03-31") 225]
                  [end 242]])]
    (is (= [direct-start direct-mid end] (:calendar aligned)))
    (is (near? 0.2 (get-in aligned [:return-series-by-instrument vault-instrument-id 0])))
    (is (near? 0.2 (get-in aligned [:return-series-by-instrument vault-instrument-id 1])))
    (is (= [] (:warnings aligned)))))

(deftest align-history-inputs-falls-back-to-direct-month-vault-summary-when-one-year-cannot-be-derived-test
  (let [month-start (day-start-ms "2026-02-28")
        month-mid (day-start-ms "2026-03-31")
        end (day-start-ms "2026-04-30")
        vault-address "0x1111111111111111111111111111111111111111"
        vault-instrument-id (str "vault:" vault-address)
        aligned (align-market-and-vault-history
                 vault-address
                 {:all-time (summary-from-points [[end 130 30]])
                  :month (summary-from-points [[month-start 100 0]
                                               [month-mid 105 5]
                                               [end 110 10]])}
                 [[month-start 200]
                  [month-mid 210]
                  [end 220]])]
    (is (= [month-start month-mid end] (:calendar aligned)))
    (is (= ["perp:BTC" vault-instrument-id]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (near? 0.05 (get-in aligned [:return-series-by-instrument vault-instrument-id 0])))
    (is (near? (/ 5 105) (get-in aligned [:return-series-by-instrument vault-instrument-id 1])))
    (is (= [] (:warnings aligned)))))

(deftest align-history-inputs-falls-back-to-common-direct-vault-window-when-preferred-windows-do-not-overlap-test
  (let [hlp-address "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"
        growi-address "0x1e37a337ed460039d1b15bd3bc489de789768d5e"
        systemic-address "0xd6e56265890b76413d1d527eb9b75e334c0c5b42"
        hlp-id (vault-instrument-id hlp-address)
        growi-id (vault-instrument-id growi-address)
        systemic-id (vault-instrument-id systemic-address)
        h0 (day-start-ms "2025-05-03")
        h1 (day-start-ms "2025-10-30")
        m0 (day-start-ms "2026-04-02")
        m1 (day-start-ms "2026-04-12")
        m2 (day-start-ms "2026-04-23")
        m3 (day-start-ms "2026-05-03")
        month-summary (summary-from-points [[m0 100 0]
                                            [m1 105 5]
                                            [m2 110 10]
                                            [m3 115 15]])
        sparse-derived-summary (summary-from-points [[h0 90 -10]
                                                     [h1 100 0]
                                                     [m3 115 15]])
        aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id hlp-id
                              :market-type :vault
                              :coin hlp-id
                              :vault-address hlp-address
                              :name "Hyperliquidity Provider (HLP)"}
                             {:instrument-id growi-id
                              :market-type :vault
                              :coin growi-id
                              :vault-address growi-address
                              :name "Growi HF"}
                             {:instrument-id systemic-id
                              :market-type :vault
                              :coin systemic-id
                              :vault-address systemic-address
                              :name "[ Systemic Strategies ] HyperGrowth"}]
                  :vault-details-by-address
                  {hlp-address {:portfolio {:all-time sparse-derived-summary
                                             :month month-summary}}
                   growi-address {:portfolio {:all-time sparse-derived-summary
                                               :month month-summary}}
                   systemic-address {:portfolio {:all-time (summary-from-points [[m3 115 15]])
                                                  :month month-summary}}}
                  :as-of-ms (+ m3 day-ms)
                  :stale-after-ms (* 2 day-ms)})]
    (is (= [m0 m1 m2 m3] (:calendar aligned)))
    (is (= [m1 m2 m3] (:return-calendar aligned)))
    (is (= [hlp-id growi-id systemic-id]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= {:kind :common-vault-window
            :window :month
            :observations 4}
           (:alignment-source aligned)))
    (is (not-any? #(= :insufficient-common-history (:code %))
                  (:warnings aligned)))))

(deftest align-history-inputs-keeps-common-history-warning-when-no-vault-window-overlaps-test
  (let [vault-a "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        vault-b "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        vault-c "0xcccccccccccccccccccccccccccccccccccccccc"
        vault-a-id (vault-instrument-id vault-a)
        vault-b-id (vault-instrument-id vault-b)
        vault-c-id (vault-instrument-id vault-c)
        a0 (day-start-ms "2026-04-01")
        a1 (day-start-ms "2026-04-02")
        b0 (day-start-ms "2026-04-10")
        b1 (day-start-ms "2026-04-11")
        c0 (day-start-ms "2026-04-20")
        c1 (day-start-ms "2026-04-21")
        aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id vault-a-id
                              :market-type :vault
                              :coin vault-a-id
                              :vault-address vault-a}
                             {:instrument-id vault-b-id
                              :market-type :vault
                              :coin vault-b-id
                              :vault-address vault-b}
                             {:instrument-id vault-c-id
                              :market-type :vault
                              :coin vault-c-id
                              :vault-address vault-c}]
                  :vault-details-by-address
                  {vault-a {:portfolio {:month (summary-from-points [[a0 100 0]
                                                                      [a1 101 1]])}}
                   vault-b {:portfolio {:month (summary-from-points [[b0 100 0]
                                                                      [b1 101 1]])}}
                   vault-c {:portfolio {:month (summary-from-points [[c0 100 0]
                                                                      [c1 101 1]])}}}
                  :as-of-ms (+ c1 day-ms)
                  :stale-after-ms (* 2 day-ms)})]
    (is (= [] (:calendar aligned)))
    (is (= [] (:eligible-instruments aligned)))
    (is (= [vault-a-id vault-b-id vault-c-id]
           (mapv :instrument-id (:excluded-instruments aligned))))
    (is (= {:code :insufficient-common-history
            :observations 0
            :required 2}
           (last (:warnings aligned))))))

(deftest align-history-inputs-tries-derived-one-year-when-direct-one-year-window-does-not-overlap-test
  (let [vault-a "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        vault-b "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        vault-a-id (vault-instrument-id vault-a)
        vault-b-id (vault-instrument-id vault-b)
        prior (day-start-ms "2025-05-02")
        cutoff (day-start-ms "2025-05-03")
        a-direct (day-start-ms "2025-06-01")
        b-direct (day-start-ms "2025-07-01")
        mid (day-start-ms "2025-11-03")
        end (day-start-ms "2026-05-03")
        derived-source (summary-from-points [[prior 90 -10]
                                             [mid 100 0]
                                             [end 110 10]])
        aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id vault-a-id
                              :market-type :vault
                              :coin vault-a-id
                              :vault-address vault-a}
                             {:instrument-id vault-b-id
                              :market-type :vault
                              :coin vault-b-id
                              :vault-address vault-b}]
                  :vault-details-by-address
                  {vault-a {:portfolio {:one-year (summary-from-points [[a-direct 100 0]
                                                                         [end 110 10]])
                                         :all-time derived-source}}
                   vault-b {:portfolio {:one-year (summary-from-points [[b-direct 100 0]
                                                                         [end 110 10]])
                                         :all-time derived-source}}}
                  :as-of-ms (+ end day-ms)
                  :stale-after-ms (* 2 day-ms)})]
    (is (= [cutoff mid end] (:calendar aligned)))
    (is (= [vault-a-id vault-b-id]
           (mapv :instrument-id (:eligible-instruments aligned))))
    (is (= {:kind :common-vault-window
            :window :one-year
            :observations 3}
           (:alignment-source aligned)))
    (is (not-any? #(= :insufficient-common-history (:code %))
                  (:warnings aligned)))))

(deftest align-history-inputs-reports-missing-and-insufficient-history-without-silent-drops-test
  (let [aligned (history-loader/align-history-inputs
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id "spot:MISSING"
                              :market-type :spot
                              :coin "MISSING"}]
                  :candle-history-by-coin {"BTC" [{:time 1000 :close "100"}]}
                  :min-observations 2
                  :as-of-ms 20000
                  :stale-after-ms 1000})]
    (is (= [] (:eligible-instruments aligned)))
    (is (= ["perp:BTC" "spot:MISSING"]
           (mapv :instrument-id (:excluded-instruments aligned))))
    (is (= [{:code :insufficient-candle-history
             :instrument-id "perp:BTC"
             :coin "BTC"
             :observations 1
             :required 2}
            {:code :missing-candle-history
             :instrument-id "spot:MISSING"
             :coin "MISSING"}]
           (:warnings aligned)))
    (is (= {:as-of-ms 20000
            :latest-common-ms nil
            :oldest-common-ms nil
            :age-ms nil
            :stale? true}
           (:freshness aligned)))))
