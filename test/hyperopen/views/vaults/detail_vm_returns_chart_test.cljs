(ns hyperopen.views.vaults.detail-vm-returns-chart-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.vaults.detail-vm :as detail-vm]
            [hyperopen.views.vaults.detail-vm-test :as detail-vm-test]))

(use-fixtures :each
  (fn [f]
    (detail-vm/reset-vault-detail-vm-cache!)
    (f)
    (detail-vm/reset-vault-detail-vm-cache!)))

(deftest vault-detail-vm-returns-chart-prefers-richer-all-time-history-over-tail-only-bounded-summary-test
  (let [t0 (.getTime (js/Date. "2025-04-14T00:00:00Z"))
        t1 (.getTime (js/Date. "2025-07-14T00:00:00Z"))
        t2 (.getTime (js/Date. "2025-10-14T00:00:00Z"))
        t3 (.getTime (js/Date. "2026-01-14T00:00:00Z"))
        t4 (.getTime (js/Date. "2026-04-14T00:00:00Z"))
        state (-> detail-vm-test/sample-state
                  (assoc-in [:vaults-ui :detail-chart-series] :returns)
                  (assoc-in [:vaults-ui :snapshot-range] :one-year)
                  (assoc-in [:candles "BTC" :12h] [{:t t0 :c 100}
                                                   {:t t1 :c 94}
                                                   {:t t2 :c 90}
                                                   {:t t3 :c 86}
                                                   {:t t4 :c 88}])
                  (assoc-in [:vaults :details-by-address "0x1234567890abcdef1234567890abcdef12345678" :portfolio]
                            {:one-year {:accountValueHistory [[t3 130]
                                                              [t4 140]]
                                        :pnlHistory [[t3 30]
                                                     [t4 40]]}
                             :all-time {:accountValueHistory [[t0 100]
                                                              [t1 110]
                                                              [t2 120]
                                                              [t3 130]
                                                              [t4 140]]
                                        :pnlHistory [[t0 0]
                                                     [t1 10]
                                                     [t2 20]
                                                     [t3 30]
                                                     [t4 40]]}}))
        vm (detail-vm/vault-detail-vm state)
        strategy-series (first (get-in vm [:chart :series]))
        benchmark-series (second (get-in vm [:chart :series]))]
    (is (= [t0 t1 t2 t3 t4]
           (mapv :time-ms (:points strategy-series))))
    (is (= [t0 t1 t2 t3 t4]
           (mapv :time-ms (:points benchmark-series))))
    (is (detail-vm-test/approx= 0 (get-in strategy-series [:points 0 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 0.2493150684931507 (get-in strategy-series [:points 1 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 0.5013698630136987 (get-in strategy-series [:points 2 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 0.7534246575342466 (get-in strategy-series [:points 3 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 1 (get-in strategy-series [:points 4 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 0 (get-in benchmark-series [:points 0 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 0.2493150684931507 (get-in benchmark-series [:points 1 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 0.5013698630136987 (get-in benchmark-series [:points 2 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 0.7534246575342466 (get-in benchmark-series [:points 3 :x-ratio]) 1e-12))
    (is (detail-vm-test/approx= 1 (get-in benchmark-series [:points 4 :x-ratio]) 1e-12))
    (is (= {:count 5
            :first-time-ms t0
            :last-time-ms t4}
           (get-in vm [:chart :strategy-window :returns-coverage])))))
