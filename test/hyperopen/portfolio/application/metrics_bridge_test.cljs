(ns hyperopen.portfolio.application.metrics-bridge-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.portfolio.application.metrics-bridge :as metrics-bridge]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.system :as system]))

(defn- approx=
  [a b]
  (< (js/Math.abs (- a b)) 1e-9))

(use-fixtures :each
  (fn [f]
    (reset! metrics-bridge/last-metrics-request nil)
    (f)
    (reset! metrics-bridge/last-metrics-request nil)))

(deftest portfolio-vm-metrics-request-signature-captures-time-range-coins-and-source-versions-test
  (let [signature-a (metrics-bridge/metrics-request-signature :month
                                                              ["SPY" "QQQ"]
                                                              101
                                                              {"SPY" 201
                                                               "QQQ" 301})
        signature-b (metrics-bridge/metrics-request-signature :week
                                                              ["SPY" "QQQ"]
                                                              101
                                                              {"SPY" 201
                                                               "QQQ" 301})
        signature-c (metrics-bridge/metrics-request-signature :month
                                                              ["SPY" "IWM"]
                                                              101
                                                              {"SPY" 201
                                                               "IWM" 401})
        signature-d (metrics-bridge/metrics-request-signature :month
                                                              ["SPY" "QQQ"]
                                                              102
                                                              {"SPY" 201
                                                               "QQQ" 301})]
    (is (= :month (:summary-time-range signature-a)))
    (is (= 3 (:metrics-schema-version signature-a)))
    (is (= ["SPY" "QQQ"] (:selected-benchmark-coins signature-a)))
    (is (= [["SPY" 201] ["QQQ" 301]]
           (:benchmark-source-versions signature-a)))
    (is (not= signature-a signature-b))
    (is (not= signature-a signature-c))
    (is (not= signature-a signature-d))))

(deftest worker-result-normalization-covers-nil-js-and-nested-status-maps-test
  (is (nil? (metrics-bridge/normalize-worker-metric-values nil)))
  (is (= {:cagr 1
          :sharpe 2
          :metric-status {}
          :metric-reason {}}
         (metrics-bridge/normalize-worker-metric-values #js {:cagr 1 :sharpe 2})))
  (is (= {:metric-status {:cagr :suppressed}
          :metric-reason {:cagr :core-gate-failed}}
         (metrics-bridge/normalize-worker-metric-values
          #js {:metric-status #js {:cagr "suppressed"}
               :metric-reason #js {:cagr "core-gate-failed"}})))
  (is (= {:portfolio-values {:metric-status {:time-in-market :ok}
                             :metric-reason {}}
          :benchmark-values-by-coin {"SPY" {:metric-status {}
                                            :metric-reason {:r2 :benchmark-coverage-gate-failed}}}}
         (metrics-bridge/normalize-worker-metrics-result
          #js {:portfolio-values #js {:metric-status #js {:time-in-market "ok"}}
               :benchmark-values-by-coin #js {"SPY" #js {:metric-reason #js {:r2 "benchmark-coverage-gate-failed"}}}}))))

(deftest normalize-worker-metrics-result-deserializes-nested-status-maps-test
  (let [worker-result {:portfolio-values {:cumulative-return 0.1
                                          :time-in-market 0.9
                                          :metric-status {:time-in-market "ok"
                                                          :r2 "suppressed"}
                                          :metric-reason {:r2 "benchmark-coverage-gate-failed"}}
                       :benchmark-values-by-coin {"SPY" {:metric-status {:time-in-market "ok"}}}}
        deserialized (-> worker-result
                         clj->js
                         (metrics-bridge/normalize-worker-metrics-result))]
    (is (= :ok (get-in deserialized [:portfolio-values :metric-status :time-in-market])))
    (is (= :suppressed (get-in deserialized [:portfolio-values :metric-status :r2])))
    (is (= :benchmark-coverage-gate-failed (get-in deserialized [:portfolio-values :metric-reason :r2])))
    (is (contains? (:benchmark-values-by-coin deserialized) "SPY"))))

(deftest request-metrics-computation-dedupes-and-posts-through-worker-test
  (let [posted-message (atom nil)
        store (atom {:portfolio-ui {}})
        signature-a {:summary-time-range :month
                     :selected-benchmark-coins ["SPY"]
                     :strategy-source-version 101
                     :benchmark-source-versions [["SPY" 201]]}
        signature-b (assoc signature-a :strategy-source-version 102)
        fake-worker #js {}]
    (set! (.-postMessage fake-worker)
          (fn [message]
            (reset! posted-message (js->clj message :keywordize-keys true))))
    (with-redefs [system/store store
                  metrics-bridge/metrics-worker fake-worker]
      (metrics-bridge/request-metrics-computation! {:seed 1} signature-a)
      ;; `:id` is the correlation token the worker already echoes back. Without
      ;; it every reply was applied no matter which request it answered.
      (is (= {:id (metrics-bridge/request-id signature-a)
              :type "compute-metrics"
              :payload {:seed 1}}
             @posted-message))
      (is (= signature-a (:signature @metrics-bridge/last-metrics-request)))
      (is (true? (get-in @store [:portfolio-ui :metrics-loading?])))
      (reset! posted-message nil)
      (metrics-bridge/request-metrics-computation! {:seed 2} signature-a)
      (is (nil? @posted-message))
      (metrics-bridge/request-metrics-computation! {:seed 3} signature-b)
      (is (= {:id (metrics-bridge/request-id signature-b)
              :type "compute-metrics"
              :payload {:seed 3}}
             @posted-message))
      (is (not= (metrics-bridge/request-id signature-a)
                (metrics-bridge/request-id signature-b))
          "different requests must be distinguishable")
      (is (= signature-b (:signature @metrics-bridge/last-metrics-request))))))

(deftest apply-worker-metrics-result-ignores-a-superseded-reply-test
  (let [signature-a {:summary-time-range :month
                     :selected-benchmark-coins ["BTC"]
                     :strategy-source-version 1
                     :benchmark-source-versions [["BTC" 11]]}
        signature-b {:summary-time-range :two-year
                     :selected-benchmark-coins ["BTC"]
                     :strategy-source-version 1
                     :benchmark-source-versions [["BTC" 22]]}
        state {:portfolio-ui {}}]
    (with-redefs [metrics-bridge/last-metrics-request (atom {:signature signature-b})]
      (let [ignored (metrics-bridge/apply-worker-metrics-result
                     state
                     (metrics-bridge/request-id signature-a)
                     {:portfolio-values {:sharpe 1}})
            applied (metrics-bridge/apply-worker-metrics-result
                     state
                     (metrics-bridge/request-id signature-b)
                     {:portfolio-values {:sharpe 2}})]
        (is (= state ignored)
            "a reply answering the 30D request is dropped once 2Y has been requested")
        (is (= {:portfolio-values {:sharpe 2}}
               (get-in applied [:portfolio-ui :metrics-result])))
        (is (= signature-b (get-in applied [:portfolio-ui :metrics-result-signature]))
            "the applied signature is recorded so the view model can label stale numbers")
        (is (false? (get-in applied [:portfolio-ui :metrics-loading?])))))))

(deftest apply-worker-metrics-result-accepts-an-untagged-reply-test
  ;; Defensive: a reply with no id (an older worker build served from cache)
  ;; still applies rather than wedging the tearsheet on stale numbers forever.
  (let [signature {:summary-time-range :month
                   :selected-benchmark-coins []
                   :strategy-source-version 1
                   :benchmark-source-versions []}]
    (with-redefs [metrics-bridge/last-metrics-request (atom {:signature signature})]
      (let [applied (metrics-bridge/apply-worker-metrics-result
                     {:portfolio-ui {}}
                     nil
                     {:portfolio-values {:sharpe 3}})]
        (is (= {:portfolio-values {:sharpe 3}}
               (get-in applied [:portfolio-ui :metrics-result])))))))

(deftest request-metrics-computation-keeps-existing-metrics-visible-test
  (let [store (atom {:portfolio-ui {:metrics-loading? false
                                    :metrics-result {:portfolio-values {:metric-status {}
                                                                        :metric-reason {}}}}})]
    (with-redefs [system/store store
                  metrics-bridge/metrics-worker nil]
      (metrics-bridge/request-metrics-computation! {:seed 7}
                                                   {:summary-time-range :month
                                                    :selected-benchmark-coins []
                                                    :strategy-source-version 1
                                                    :benchmark-source-versions []})
      (is (false? (get-in @store [:portfolio-ui :metrics-loading?]))))))

(deftest metrics-request-and-sync-helpers-cover-signature-and-row-defaults-test
  (let [signature-a (metrics-bridge/metrics-request-signature :month
                                                              ["SPY" "QQQ"]
                                                              101
                                                              {"SPY" 201
                                                               "QQQ" 301})
        signature-b (metrics-bridge/metrics-request-signature :week
                                                              ["SPY" "QQQ"]
                                                              101
                                                              {"SPY" 201
                                                               "QQQ" 301})]
    (is (= :month (:summary-time-range signature-a)))
    (is (= 3 (:metrics-schema-version signature-a)))
    (is (= ["SPY" "QQQ"] (:selected-benchmark-coins signature-a)))
    (is (= [["SPY" 201] ["QQQ" 301]]
           (:benchmark-source-versions signature-a)))
    (is (not= signature-a signature-b)))
  (is (= [{:time-ms 1}]
         (metrics-bridge/request-benchmark-daily-rows {:benchmark-daily-rows [{:time-ms 1}]})))
  (is (= [[1 5]]
         (metrics-bridge/request-strategy-daily-rows {:strategy-daily-rows [[1 5]]})))
  (let [portfolio-daily-rows [{:time-ms 1 :value 0} {:time-ms 2 :value 5}]
        spy-daily-rows [{:time-ms 1 :value 0} {:time-ms 2 :value 3}]
        captured-requests (atom [])]
    (with-redefs [portfolio-metrics/daily-compounded-returns (fn [rows]
                                                               (mapv (fn [[time-ms value]]
                                                                       {:time-ms time-ms :value value})
                                                                     rows))
                  portfolio-metrics/compute-performance-metrics (fn [{:keys [strategy-daily-rows] :as request}]
                                                                  (swap! captured-requests conj request)
                                                                  (if (= portfolio-daily-rows strategy-daily-rows)
                                                                    {:beta :portfolio-vs-spy-beta
                                                                     :alpha :portfolio-vs-spy-alpha
                                                                     :correlation :portfolio-vs-spy-correlation
                                                                     :treynor-ratio :portfolio-vs-spy-treynor
                                                                     :metric-status {:beta :ok
                                                                                     :alpha :low-confidence
                                                                                     :correlation :ok
                                                                                     :treynor-ratio :ok}
                                                                     :metric-reason {:alpha :daily-coverage-gate-failed}}
                                                                    {:cumulative-return :spy-standalone-return
                                                                     :beta :spy-standalone-beta
                                                                     :metric-status {:beta :suppressed}
                                                                     :metric-reason {:beta :benchmark-coverage-gate-failed}}))]
      (is (= {:portfolio-values {:beta :portfolio-vs-spy-beta
                                 :alpha :portfolio-vs-spy-alpha
                                 :correlation :portfolio-vs-spy-correlation
                                 :treynor-ratio :portfolio-vs-spy-treynor
                                 :metric-status {:beta :ok
                                                 :alpha :low-confidence
                                                 :correlation :ok
                                                 :treynor-ratio :ok}
                                 :metric-reason {:alpha :daily-coverage-gate-failed}}
              :benchmark-values-by-coin {"SPY" {:cumulative-return :spy-standalone-return
                                                :beta :portfolio-vs-spy-beta
                                                :alpha :portfolio-vs-spy-alpha
                                                :correlation :portfolio-vs-spy-correlation
                                                :treynor-ratio :portfolio-vs-spy-treynor
                                                :metric-status {:beta :ok
                                                                :alpha :low-confidence
                                                                :correlation :ok
                                                                :treynor-ratio :ok}
                                                :metric-reason {:alpha :daily-coverage-gate-failed}}}}
             (metrics-bridge/compute-metrics-sync
              {:portfolio-request {:strategy-cumulative-rows [[1 0] [2 5]]
                                   :strategy-daily-rows portfolio-daily-rows
                                   :benchmark-cumulative-rows [[1 0] [2 3]]}
               :benchmark-requests [{:coin "SPY"
                                     :request {:strategy-cumulative-rows [[1 0] [2 3]]}}]})))
      (is (= [portfolio-daily-rows
              spy-daily-rows
              portfolio-daily-rows]
             (mapv :strategy-daily-rows @captured-requests)))
      (is (= [spy-daily-rows
              nil
              spy-daily-rows]
             (mapv :benchmark-daily-rows @captured-requests))))))

(deftest compute-metrics-sync-overlays-portfolio-relative-metrics-into-each-benchmark-result-test
  (let [portfolio-cumulative [[1 0] [2 5]]
        portfolio-daily [{:time-ms 1 :return 0.01}
                         {:time-ms 2 :return 0.04}]
        spy-cumulative [[1 0] [2 3]]
        qqq-cumulative [[1 0] [2 4]]
        spy-daily [{:time-ms 2 :return 0.03}]
        qqq-daily [{:time-ms 2 :return 0.04}]
        captured-requests (atom [])]
    (with-redefs [portfolio-metrics/daily-compounded-returns (fn [rows]
                                                               (cond
                                                                 (= rows spy-cumulative) spy-daily
                                                                 (= rows qqq-cumulative) qqq-daily
                                                                 :else []))
                  portfolio-metrics/compute-performance-metrics
                  (fn [{:keys [strategy-cumulative-rows benchmark-daily-rows] :as request}]
                    (swap! captured-requests conj request)
                    (cond
                      (= benchmark-daily-rows spy-daily)
                      {:r2 0.7
                       :information-ratio 0.2
                       :metric-status {:r2 :low-confidence
                                       :information-ratio :low-confidence}
                       :metric-reason {:r2 :benchmark-sparse-intervals
                                       :information-ratio :benchmark-sparse-intervals}}

                      (= benchmark-daily-rows qqq-daily)
                      {:r2 0.4
                       :information-ratio -0.1
                       :metric-status {:r2 :low-confidence
                                       :information-ratio :low-confidence}
                       :metric-reason {:r2 :benchmark-sparse-intervals
                                       :information-ratio :benchmark-sparse-intervals}}

                      (= strategy-cumulative-rows portfolio-cumulative)
                      {:cumulative-return 0.25
                       :r2 0.99
                       :metric-status {:cumulative-return :ok
                                       :r2 :low-confidence}
                       :metric-reason {:r2 :benchmark-sparse-intervals}}

                      (= strategy-cumulative-rows spy-cumulative)
                      {:cumulative-return 0.08
                       :r2 nil
                       :metric-status {:cumulative-return :ok
                                       :r2 :suppressed}
                       :metric-reason {:r2 :benchmark-coverage-gate-failed}}

                      (= strategy-cumulative-rows qqq-cumulative)
                      {:cumulative-return 0.12
                       :r2 nil
                       :metric-status {:cumulative-return :ok
                                       :r2 :suppressed}
                       :metric-reason {:r2 :benchmark-coverage-gate-failed}}))]
      (is (= {:portfolio-values {:cumulative-return 0.25
                                  :r2 0.99
                                  :metric-status {:cumulative-return :ok
                                                  :r2 :low-confidence}
                                  :metric-reason {:r2 :benchmark-sparse-intervals}}
              :benchmark-values-by-coin {"SPY" {:cumulative-return 0.08
                                                :r2 0.7
                                                :information-ratio 0.2
                                                :metric-status {:cumulative-return :ok
                                                                :r2 :low-confidence
                                                                :information-ratio :low-confidence}
                                                :metric-reason {:r2 :benchmark-sparse-intervals
                                                                :information-ratio :benchmark-sparse-intervals}}
                                         "QQQ" {:cumulative-return 0.12
                                                :r2 0.4
                                                :information-ratio -0.1
                                                :metric-status {:cumulative-return :ok
                                                                :r2 :low-confidence
                                                                :information-ratio :low-confidence}
                                                :metric-reason {:r2 :benchmark-sparse-intervals
                                                                :information-ratio :benchmark-sparse-intervals}}}}
             (metrics-bridge/compute-metrics-sync
              {:portfolio-request {:strategy-cumulative-rows portfolio-cumulative
                                   :strategy-daily-rows portfolio-daily}
               :benchmark-requests [{:coin "SPY"
                                     :request {:strategy-cumulative-rows spy-cumulative}}
                                    {:coin "QQQ"
                                     :request {:strategy-cumulative-rows qqq-cumulative}}]})))
      (is (= 5 (count @captured-requests))))))

(deftest vault-snapshot-and-alignment-helpers-cover-branches-test
  (is (= ["1d" "7d" "30d"]
         (metrics-bridge/vault-snapshot-range-keys)))
  (is (= 2 (metrics-bridge/vault-snapshot-point-value [1 "2"])))
  (is (= 3 (metrics-bridge/vault-snapshot-point-value {:value "3"})))
  (is (nil? (metrics-bridge/vault-snapshot-point-value nil)))
  (is (= 4
         (metrics-bridge/normalize-vault-snapshot-return "1d" {:returns {"1d" 4}})))
  (is (nil?
       (metrics-bridge/normalize-vault-snapshot-return "7d" {:returns {"7d" js/Infinity}})))
  (is (= {"1d" 1 "7d" nil "30d" 3}
         (metrics-bridge/vault-benchmark-snapshot-values
          {:returns {"1d" 1 "30d" 3}})))
  (let [aligned (metrics-bridge/aligned-vault-return-rows
                 {:history [[1 100]
                            [2 110]
                            [3 121]]}
                 [{:time-ms 2}
                  {:time-ms 3}
                  {:time-ms 4}])]
    (is (= [2 3] (mapv :time-ms aligned)))
    (is (approx= 0 (get-in aligned [0 :value])))
    (is (approx= 10 (get-in aligned [1 :value]))))
  (is (= []
         (metrics-bridge/aligned-vault-return-rows
          {:history [[10 50]
                     [11 55]]}
          [{:time-ms 9}
           {:time-ms 10}])))
  (is (= []
         (metrics-bridge/aligned-vault-return-rows
          {:history [[1 100]]}
          []))))
