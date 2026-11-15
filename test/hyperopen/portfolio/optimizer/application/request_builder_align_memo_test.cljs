(ns hyperopen.portfolio.optimizer.application.request-builder-align-memo-test
  "The align-history memo inside request building must be keyed on DATA inputs
  only. Nothing in production writes the runtime as-of override, so the as-of
  falls back to a wall-clock bucket - keying the memo on it re-ran the full
  multi-second alignment every bucket roll (owner trace 2026-07-08: 3.3s
  main-thread stalls on a ~5s cadence). Only the O(1) :freshness stamp may
  depend on the as-of."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]))

(defn- build-inputs
  [coin as-of-ms]
  {:draft {:id "draft-align-memo"
           :universe [{:instrument-id (str "perp:" coin)
                       :market-type :perp
                       :coin coin}]
           :objective {:kind :min-variance}
           :constraints {:long-only? true}}
   :current-portfolio {:capital {:nav-usdc 1000}
                       :by-instrument {}}
   :history-data {:candle-history-by-coin {coin [{:time 1000 :close "100"}
                                                 {:time 2000 :close "110"}
                                                 {:time 3000 :close "105"}]}
                  :funding-history-by-coin {coin [{:time-ms 1000
                                                   :funding-rate-raw 0.001}]}}
   :market-cap-by-coin {coin 900}
   :as-of-ms as-of-ms})

(deftest align-history-memo-survives-as-of-changes-test
  (let [original-align history-loader/align-history-inputs
        align-calls (atom 0)
        [first-request second-request]
        (with-redefs [history-loader/align-history-inputs
                      (fn [inputs]
                        (swap! align-calls inc)
                        (original-align inputs))]
          [(request-builder/build-engine-request (build-inputs "MEMOA" 2500))
           (request-builder/build-engine-request (build-inputs "MEMOA" 7500))])]
    (is (= 1 @align-calls))
    (is (= 2500 (get-in first-request [:history :freshness :as-of-ms])))
    (is (= 7500 (get-in second-request [:history :freshness :as-of-ms])))
    (is (= (-> first-request
               (dissoc :as-of-ms)
               (update :history dissoc :freshness))
           (-> second-request
               (dissoc :as-of-ms)
               (update :history dissoc :freshness))))))

(deftest align-history-freshness-tracks-caller-staleness-window-test
  (let [request-fresh (request-builder/build-engine-request
                       (build-inputs "MEMOB" 3500))
        request-stale (request-builder/build-engine-request
                       (assoc (build-inputs "MEMOB" 900000)
                              :stale-after-ms 60000))]
    (is (false? (get-in request-fresh [:history :freshness :stale?])))
    (is (true? (get-in request-stale [:history :freshness :stale?])))
    (is (= 3000 (get-in request-stale [:history :freshness :latest-common-ms])))))
