(ns hyperopen.views.portfolio.vm.benchmarks-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.system :as system]
            [hyperopen.views.portfolio.vm :as vm]
            [hyperopen.views.account-equity-view :as account-equity-view]))

(def ^:private day-ms
  (* 24 60 60 1000))

(def ^:private fixture-start-ms
  (.getTime (js/Date. "2024-01-01T00:00:00.000Z")))

(defn- now-ms []
  (if (exists? js/performance)
    (.now js/performance)
    (.now js/Date)))

(use-fixtures :each
  (fn [f]
    (vm/reset-portfolio-vm-cache!)
    (reset! @#'vm/last-metrics-request nil)
    (f)
    (vm/reset-portfolio-vm-cache!)
    (reset! @#'vm/last-metrics-request nil)))

(defn- performance-metric-row
  [view-model metric-key]
  (some (fn [{:keys [rows]}]
          (some (fn [row]
                  (when (= metric-key (:key row))
                    row))
                rows))
        (get-in view-model [:performance-metrics :groups])))

(deftest portfolio-vm-builds-returns-benchmark-options-from-asset-selector-markets-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns}
                 :asset-selector {:markets [{:coin "BTC"
                                             :symbol "BTC-USD"
                                             :market-type :perp
                                             :openInterest "900"
                                             :cache-order 1}
                                            {:coin "SPY"
                                             :symbol "SPY"
                                             :market-type :spot
                                             :openInterest "100"
                                             :cache-order 2}]}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0]]
                                                      :accountValueHistory [[1 100] [2 110]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          benchmark-selector (get-in view-model [:selectors :returns-benchmark])]
      (is (= [] (:selected-coins benchmark-selector)))
      (is (= [] (:selected-options benchmark-selector)))
      (is (= "BTC" (:top-coin benchmark-selector)))
      (is (= "" (:coin-search benchmark-selector)))
      (is (= ["BTC-USD (PERP)" "SPY (SPOT)"]
             (mapv :label (:candidates benchmark-selector))))
      (is (= ["BTC" "SPY"]
             (mapv :value (:candidates benchmark-selector))))))

(deftest portfolio-vm-limits-vault-benchmark-options-to-top-100-by-tvl-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [address-for (fn [idx]
                        (let [suffix (str idx)
                              zero-count (max 0 (- 40 (count suffix)))]
                          (str "0x"
                               (apply str (repeat zero-count "0"))
                               suffix)))
          top-address (address-for 105)
          cutoff-address (address-for 6)
          excluded-address (address-for 5)
          vault-rows (mapv (fn [idx]
                             {:name (str "Vault " idx)
                              :vault-address (address-for idx)
                              :relationship {:type :normal}
                              :tvl idx})
                           (range 1 106))
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns
                                :returns-benchmark-coins [(str "vault:" excluded-address)]
                                :returns-benchmark-coin (str "vault:" excluded-address)
                                :returns-benchmark-search "vault"}
                 :asset-selector {:markets [{:coin "BTC"
                                             :symbol "BTC-USD"
                                             :market-type :perp
                                             :openInterest "900"
                                             :cache-order 1}]}
                 :vaults {:merged-index-rows (conj vault-rows
                                                   {:name "Child Vault"
                                                    :vault-address "0x3333333333333333333333333333333333333333"
                                                    :relationship {:type :child}
                                                    :tvl 1000})}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0]]
                                                      :accountValueHistory [[1 100] [2 110]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          benchmark-selector (get-in (vm/portfolio-vm state) [:selectors :returns-benchmark])
          vault-candidates (->> (:candidates benchmark-selector)
                                (filter (fn [{:keys [value]}]
                                          (str/starts-with? value "vault:")))
                                vec)]
      (is (= 100 (count vault-candidates)))
      (is (= (str "vault:" top-address)
             (some-> vault-candidates first :value)))
      (is (= (str "vault:" cutoff-address)
             (some-> vault-candidates last :value)))
      (is (not-any? #(= (str "vault:" excluded-address) (:value %))
                    vault-candidates))
      (is (= [] (:selected-coins benchmark-selector))))))

(deftest portfolio-vm-memoizes-benchmark-selector-options-by-markets-identity-and-signature-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [build-count (atom 0)
          original-builder vm/*build-benchmark-selector-options*
          markets-a [{:coin "BTC"
                      :symbol "BTC-USD"
                      :market-type :perp
                      :openInterest "900"
                      :cache-order 1}
                     {:coin "ETH"
                      :symbol "ETH-USD"
                      :market-type :perp
                      :openInterest "800"
                      :cache-order 2}]
          markets-b (mapv identity markets-a)
          markets-c (assoc-in markets-b [1 :openInterest] "1200")
          base-state {:account {:mode :classic}
                      :portfolio-ui {:summary-scope :all
                                     :summary-time-range :month
                                     :chart-tab :returns}
                      :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0]]
                                                           :accountValueHistory [[1 100] [2 110]]
                                                           :vlm 10}}}
                      :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                                :totalVaultEquity 0}
                      :borrow-lend {:total-supplied-usd 0}}
          build-vm (fn [markets]
                     (vm/portfolio-vm (assoc base-state :asset-selector {:markets markets})))]
      (is (false? (identical? markets-a markets-b)))
      (with-redefs [vm/*build-benchmark-selector-options*
                    (fn [markets]
                      (swap! build-count inc)
                      (original-builder markets))]
        (let [selector-a (get-in (build-vm markets-a) [:selectors :returns-benchmark])]
          (is (= 1 @build-count))
          (let [selector-identity-hit (get-in (build-vm markets-a) [:selectors :returns-benchmark])]
            (is (= 1 @build-count))
            (let [selector-signature-hit (get-in (build-vm markets-b) [:selectors :returns-benchmark])
                  selector-invalidated (get-in (build-vm markets-c) [:selectors :returns-benchmark])]
              (is (= 2 @build-count))
              (is (= ["BTC" "ETH"] (mapv :value (:candidates selector-a))))
              (is (= ["BTC" "ETH"] (mapv :value (:candidates selector-identity-hit))))
              (is (= ["BTC" "ETH"] (mapv :value (:candidates selector-signature-hit))))
              (is (= ["ETH" "BTC"] (mapv :value (:candidates selector-invalidated))))
              (is (= "BTC" (:top-coin selector-a)))
              (is (= "ETH" (:top-coin selector-invalidated))))))))))

(deftest portfolio-vm-builds-vault-benchmark-series-and-performance-columns-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
          vault-ref (str "vault:" vault-address)
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :returns
                                :returns-benchmark-coins [vault-ref]
                                :returns-benchmark-coin vault-ref}
                 :vaults {:merged-index-rows [{:name "Growi HF"
                                               :vault-address vault-address
                                               :relationship {:type :normal}
                                               :tvl 200
                                               :snapshot-by-key {:month [0.05 0.15]}}]}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 0] [3 0]]
                                                      :accountValueHistory [[1 100] [2 110] [3 120]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          benchmark-series (->> (get-in view-model [:chart :series])
                                (filter #(= :benchmark-0 (:id %)))
                                first)]
      (is (= "Growi HF (VAULT)" (:label benchmark-series)))
      (is (= [5 15 15]
             (mapv :value (:points benchmark-series))))
      (is (= [vault-ref]
             (get-in view-model [:performance-metrics :benchmark-coins])))
      (is (= "Growi HF (VAULT)"
             (get-in view-model [:performance-metrics :benchmark-label]))))))

(deftest portfolio-vm-performance-metrics-include-all-selected-benchmarks-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [t0 fixture-start-ms
          t1 (+ fixture-start-ms day-ms)
          t2 (+ fixture-start-ms (* 2 day-ms))
          state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :returns-benchmark-coins ["SPY" "QQQ"]}
                 :asset-selector {:markets [{:coin "SPY"
                                             :symbol "SPY"
                                             :market-type :spot
                                             :openInterest "200"
                                             :cache-order 1}
                                            {:coin "QQQ"
                                             :symbol "QQQ"
                                             :market-type :spot
                                             :openInterest "100"
                                             :cache-order 2}]}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                   [t1 11]
                                                                   [t2 19]]
                                                      :accountValueHistory [[t0 100]
                                                                            [t1 111]
                                                                            [t2 119]]
                                                      :vlm 0}}}
                 :candles {"SPY" {:1h [{:t t0 :c 50}
                                       {:t t1 :c 53}
                                       {:t t2 :c 57}]}
                           "QQQ" {:1h [{:t t0 :c 100}
                                       {:t t1 :c 101}
                                       {:t t2 :c 103}]}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)
          cumulative-return-row (performance-metric-row view-model :cumulative-return)
          r2-row (performance-metric-row view-model :r2)
          information-ratio-row (performance-metric-row view-model :information-ratio)]
      (is (true? (get-in view-model [:performance-metrics :benchmark-selected?])))
      (is (= ["SPY" "QQQ"] (get-in view-model [:performance-metrics :benchmark-coins])))
      (is (= [{:coin "SPY"
               :label "SPY (SPOT)"}
              {:coin "QQQ"
               :label "QQQ (SPOT)"}]
             (get-in view-model [:performance-metrics :benchmark-columns])))
      (is (= "SPY" (get-in view-model [:performance-metrics :benchmark-coin])))
      (is (= "SPY (SPOT)" (get-in view-model [:performance-metrics :benchmark-label])))
      (is (number? (:portfolio-value cumulative-return-row)))
      (is (number? (:benchmark-value cumulative-return-row)))
      (is (number? (get-in cumulative-return-row [:benchmark-values "SPY"])))
      (is (number? (get-in cumulative-return-row [:benchmark-values "QQQ"])))
      (is (number? (:value r2-row)))
      (is (number? (:value information-ratio-row))))))


(deftest portfolio-vm-sync-metrics-derives-benchmark-daily-rows-from-cumulative-test
  (let [t0 fixture-start-ms
        t1 (+ fixture-start-ms day-ms)
        t2 (+ fixture-start-ms (* 2 day-ms))
        strategy-cumulative-rows [[t0 0]
                                  [t1 11]
                                  [t2 19]]
        spy-cumulative-rows [[t0 0]
                             [t1 6]
                             [t2 14]]
        expected-strategy-daily (portfolio-metrics/daily-compounded-returns strategy-cumulative-rows)
        expected-spy-daily (portfolio-metrics/daily-compounded-returns spy-cumulative-rows)
        captured-requests (atom [])
        request-data {:portfolio-request {:strategy-cumulative-rows strategy-cumulative-rows
                                          :strategy-daily-rows expected-strategy-daily
                                          :benchmark-cumulative-rows spy-cumulative-rows}
                      :benchmark-requests [{:coin "SPY"
                                            :request {:strategy-cumulative-rows spy-cumulative-rows}}]}]
    (with-redefs [portfolio-metrics/compute-performance-metrics (fn [request]
                                                                   (swap! captured-requests conj request)
                                                                   {:metric-status {}
                                                                    :metric-reason {}})]
      (@#'vm/compute-metrics-sync request-data)
      (is (= 2 (count @captured-requests)))
      (is (= expected-spy-daily
             (:benchmark-daily-rows (first @captured-requests))))
      (is (= expected-spy-daily
             (:strategy-daily-rows (second @captured-requests)))))))

(deftest portfolio-vm-request-metrics-computation-dedupes-by-lightweight-signature-test
  (let [write-count (atom 0)
        store (atom {:portfolio-ui {}})
        signature-a {:summary-time-range :month
                     :selected-benchmark-coins ["SPY"]
                     :strategy-source-version 101
                     :benchmark-source-versions [["SPY" 201]]}
        signature-b (assoc signature-a :strategy-source-version 102)
        request-a {:portfolio-request {:strategy-cumulative-rows [[1 0]
                                                                  [2 1]]}
                   :benchmark-requests [{:coin "SPY"
                                         :request {:strategy-cumulative-rows [[1 0]
                                                                              [2 3]]}}]}
        request-b {:portfolio-request {:strategy-cumulative-rows [[1 0]
                                                                  [2 50]
                                                                  [3 99]]}
                   :benchmark-requests [{:coin "SPY"
                                         :request {:strategy-cumulative-rows [[1 0]
                                                                              [2 60]
                                                                              [3 120]]}}]}]
    (add-watch store ::metrics-request-writes
               (fn [_ _ _ _]
                 (swap! write-count inc)))
    (try
      (with-redefs [system/store store]
        (@#'vm/request-metrics-computation! request-a signature-a)
        (@#'vm/request-metrics-computation! request-b signature-a)
        (@#'vm/request-metrics-computation! request-b signature-b)
        (is (= 2 @write-count))
        (is (= signature-b
               (get (deref @#'vm/last-metrics-request) :signature)))
        (is (true? (get-in @store [:portfolio-ui :metrics-loading?]))))
      (finally
        (remove-watch store ::metrics-request-writes)))))

(deftest portfolio-vm-request-metrics-computation-keeps-existing-metrics-visible-test
  (let [write-count (atom 0)
        store (atom {:portfolio-ui {:metrics-loading? false
                                    :metrics-result {:portfolio-values {:metric-status {}
                                                                        :metric-reason {}}}}})
        signature {:summary-time-range :month
                   :selected-benchmark-coins ["SPY"]
                   :strategy-source-version 101
                   :benchmark-source-versions [["SPY" 201]]}
        request-data {:portfolio-request {:strategy-cumulative-rows [[1 0]
                                                                     [2 1]]}
                      :benchmark-requests [{:coin "SPY"
                                            :request {:strategy-cumulative-rows [[1 0]
                                                                                 [2 3]]}}]}]
    (add-watch store ::metrics-request-writes
               (fn [_ _ _ _]
                 (swap! write-count inc)))
    (try
      (with-redefs [system/store store]
        (@#'vm/request-metrics-computation! request-data signature)
        (is (= 0 @write-count))
        (is (false? (get-in @store [:portfolio-ui :metrics-loading?])))
        (is (= signature
               (get (deref @#'vm/last-metrics-request) :signature))))
      (finally
        (remove-watch store ::metrics-request-writes)))))

(deftest portfolio-vm-metrics-request-signature-captures-time-range-coins-and-source-versions-test
  (let [signature-a (@#'vm/metrics-request-signature :month
                                                      ["SPY" "QQQ"]
                                                      101
                                                      {"SPY" 201
                                                       "QQQ" 301})
        signature-b (@#'vm/metrics-request-signature :week
                                                      ["SPY" "QQQ"]
                                                      101
                                                      {"SPY" 201
                                                       "QQQ" 301})
        signature-c (@#'vm/metrics-request-signature :month
                                                      ["SPY" "IWM"]
                                                      101
                                                      {"SPY" 201
                                                       "IWM" 401})
        signature-d (@#'vm/metrics-request-signature :month
                                                      ["SPY" "QQQ"]
                                                      102
                                                      {"SPY" 201
                                                       "QQQ" 301})]
    (is (= :month (:summary-time-range signature-a)))
    (is (= ["SPY" "QQQ"] (:selected-benchmark-coins signature-a)))
    (is (= [["SPY" 201] ["QQQ" 301]]
           (:benchmark-source-versions signature-a)))
    (is (not= signature-a signature-b))
    (is (not= signature-a signature-c))
    (is (not= signature-a signature-d))))

(deftest portfolio-vm-performance-metrics-model-skips-request-data-build-when-worker-signature-unchanged-test
  (let [strategy-cumulative-rows [[1 0]
                                  [2 11]
                                  [3 19]]
        benchmark-cumulative-rows-by-coin {"SPY" [[1 0]
                                                  [2 4]
                                                  [3 8]]
                                           "QQQ" [[1 0]
                                                  [2 2]
                                                  [3 5]]}
        selected-benchmark-coins ["SPY" "QQQ"]
        summary-time-range :month
        benchmark-context {:strategy-cumulative-rows strategy-cumulative-rows
                           :benchmark-cumulative-rows-by-coin benchmark-cumulative-rows-by-coin
                           :strategy-source-version 101
                           :benchmark-source-version-map {"SPY" 201
                                                          "QQQ" 301}}
        request-signature (@#'vm/metrics-request-signature summary-time-range
                                                            selected-benchmark-coins
                                                            (:strategy-source-version benchmark-context)
                                                            (:benchmark-source-version-map benchmark-context))
        state {:portfolio-ui {:metrics-loading? false
                              :metrics-result {:portfolio-values {:metric-status {}
                                                                  :metric-reason {}}
                                               :benchmark-values-by-coin {"SPY" {:metric-status {}
                                                                                  :metric-reason {}}
                                                                          "QQQ" {:metric-status {}
                                                                                  :metric-reason {}}}}}}
        benchmark-selector {:selected-coins selected-benchmark-coins
                            :label-by-coin {"SPY" "SPY (SPOT)"
                                            "QQQ" "QQQ (SPOT)"}}
        request-build-count (atom 0)
        request-dispatch-count (atom 0)]
    (reset! @#'vm/last-metrics-request {:signature request-signature})
    (with-redefs [vm/metrics-worker (delay #js {:postMessage (fn [_payload] nil)})
                  vm/build-metrics-request-data (fn [& _]
                                                  (swap! request-build-count inc)
                                                  {})
                  vm/request-metrics-computation! (fn [& _]
                                                    (swap! request-dispatch-count inc))]
      (let [model (@#'vm/performance-metrics-model state
                                                   summary-time-range
                                                   benchmark-selector
                                                   benchmark-context)]
        (is (= 0 @request-build-count))
        (is (= 0 @request-dispatch-count))
        (is (= ["SPY" "QQQ"] (:benchmark-coins model)))
        (is (= [{:coin "SPY" :label "SPY (SPOT)"}
                {:coin "QQQ" :label "QQQ (SPOT)"}]
               (:benchmark-columns model)))))))

(deftest portfolio-vm-performance-metrics-signature-gate-timing-note-test
  (let [iterations 250
        row-count 2400
        strategy-cumulative-rows (mapv (fn [idx]
                                         (let [step (inc idx)]
                                           [(* step day-ms)
                                            (* 0.01 step)]))
                                       (range row-count))
        benchmark-cumulative-rows-by-coin {"SPY" (mapv (fn [idx]
                                                         (let [step (inc idx)]
                                                           [(* step day-ms)
                                                            (* 0.008 step)]))
                                                       (range row-count))
                                           "QQQ" (mapv (fn [idx]
                                                         (let [step (inc idx)]
                                                           [(* step day-ms)
                                                            (* 0.006 step)]))
                                                       (range row-count))}
        selected-benchmark-coins ["SPY" "QQQ"]
        summary-time-range :month
        strategy-source-version 101
        benchmark-source-version-map {"SPY" 201
                                      "QQQ" 301}
        request-signature (@#'vm/metrics-request-signature summary-time-range
                                                            selected-benchmark-coins
                                                            strategy-source-version
                                                            benchmark-source-version-map)
        baseline-daily-call-count (atom 0)
        gated-daily-call-count (atom 0)
        heavy-daily-compounded-returns (fn [rows]
                                         (let [rows* (or rows [])
                                               seed (count rows*)
                                               row-sum (reduce (fn [acc [time-ms value]]
                                                                 (+ acc
                                                                    (or time-ms 0)
                                                                    (or value 0)))
                                                               0
                                                               rows*)
                                               sqrt-sum (loop [idx 0
                                                               acc 0]
                                                          (if (< idx (* 60 seed))
                                                            (recur (inc idx)
                                                                   (+ acc
                                                                      (js/Math.sqrt (+ idx (* seed 3)))))
                                                            acc))]
                                           (when (or (js/isNaN row-sum)
                                                     (js/isNaN sqrt-sum))
                                             (throw (js/Error. "timing harness produced NaN")))
                                           []))
        baseline-ms (atom 0)
        gated-ms (atom 0)]
    (reset! @#'vm/last-metrics-request {:signature request-signature})
    (with-redefs [portfolio-metrics/daily-compounded-returns (fn [rows]
                                                               (swap! baseline-daily-call-count inc)
                                                               (heavy-daily-compounded-returns rows))]
      (let [started-at (now-ms)]
        (dotimes [_ iterations]
          (@#'vm/build-metrics-request-data strategy-cumulative-rows
                                            benchmark-cumulative-rows-by-coin
                                            selected-benchmark-coins)
          (let [signature (@#'vm/metrics-request-signature summary-time-range
                                                           selected-benchmark-coins
                                                           strategy-source-version
                                                           benchmark-source-version-map)]
            (when (not= signature
                        (get (deref @#'vm/last-metrics-request) :signature))
              nil)))
        (reset! baseline-ms (- (now-ms) started-at))))
    (reset! @#'vm/last-metrics-request {:signature request-signature})
    (with-redefs [portfolio-metrics/daily-compounded-returns (fn [rows]
                                                               (swap! gated-daily-call-count inc)
                                                               (heavy-daily-compounded-returns rows))]
      (let [started-at (now-ms)]
        (dotimes [_ iterations]
          (let [signature (@#'vm/metrics-request-signature summary-time-range
                                                           selected-benchmark-coins
                                                           strategy-source-version
                                                           benchmark-source-version-map)]
            (when (not= signature
                        (get (deref @#'vm/last-metrics-request) :signature))
              (@#'vm/build-metrics-request-data strategy-cumulative-rows
                                                benchmark-cumulative-rows-by-coin
                                                selected-benchmark-coins))))
        (reset! gated-ms (- (now-ms) started-at))))
    (println (str "[portfolio-metrics-signature-gate]"
                  " baseline-ms=" (.toFixed @baseline-ms 3)
                  " gated-ms=" (.toFixed @gated-ms 3)
                  " baseline-daily-calls=" @baseline-daily-call-count
                  " gated-daily-calls=" @gated-daily-call-count
                  " iterations=" iterations))
    (is (> @baseline-daily-call-count 0))
    (is (= 0 @gated-daily-call-count))
    (is (> @baseline-ms @gated-ms))))

(deftest portfolio-vm-reuses-benchmark-candle-request-for-chart-and-metrics-test
  (let [original-request portfolio-actions/returns-benchmark-candle-request
        request-calls (atom 0)]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  portfolio-actions/returns-benchmark-candle-request (fn [summary-time-range]
                                                                        (swap! request-calls inc)
                                                                        (original-request summary-time-range))]
      (let [t0 fixture-start-ms
            t1 (+ fixture-start-ms day-ms)
            t2 (+ fixture-start-ms (* 2 day-ms))
            state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :returns
                                  :returns-benchmark-coins ["SPY" "QQQ"]}
                   :asset-selector {:markets [{:coin "SPY"
                                               :symbol "SPY"
                                               :market-type :spot
                                               :openInterest "200"
                                               :cache-order 1}
                                              {:coin "QQQ"
                                               :symbol "QQQ"
                                               :market-type :spot
                                               :openInterest "100"
                                               :cache-order 2}]}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                     [t1 11]
                                                                     [t2 19]]
                                                        :accountValueHistory [[t0 100]
                                                                              [t1 111]
                                                                              [t2 119]]
                                                        :vlm 0}}}
                   :candles {"SPY" {:1h [{:t t0 :c 50}
                                         {:t t1 :c 53}
                                         {:t t2 :c 57}]}
                             "QQQ" {:1h [{:t t0 :c 100}
                                         {:t t1 :c 101}
                                         {:t t2 :c 103}]}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            view-model (vm/portfolio-vm state)]
        (is (= 1 @request-calls))
        (is (= [:strategy :benchmark-0 :benchmark-1]
               (mapv :id (get-in view-model [:chart :series]))))
        (is (= ["SPY" "QQQ"]
               (get-in view-model [:performance-metrics :benchmark-coins])))))))

(deftest portfolio-vm-skips-benchmark-request-when-no-benchmarks-selected-test
  (let [original-request portfolio-actions/returns-benchmark-candle-request
        request-calls (atom 0)]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  portfolio-actions/returns-benchmark-candle-request (fn [summary-time-range]
                                                                        (swap! request-calls inc)
                                                                        (original-request summary-time-range))]
      (let [t0 fixture-start-ms
            t1 (+ fixture-start-ms day-ms)
            t2 (+ fixture-start-ms (* 2 day-ms))
            state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :returns}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                     [t1 11]
                                                                     [t2 19]]
                                                        :accountValueHistory [[t0 100]
                                                                              [t1 111]
                                                                              [t2 119]]
                                                        :vlm 0}}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            view-model (vm/portfolio-vm state)]
        (is (= 0 @request-calls))
        (is (= [:strategy]
               (mapv :id (get-in view-model [:chart :series]))))
        (is (= [] (get-in view-model [:performance-metrics :benchmark-columns])))
        (is (false? (get-in view-model [:performance-metrics :benchmark-selected?])))))))

(deftest portfolio-vm-computes-returns-history-once-per-build-test
  (let [t0 fixture-start-ms
        t1 (+ fixture-start-ms day-ms)
        t2 (+ fixture-start-ms (* 2 day-ms))
        returns-history-calls (atom 0)]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  portfolio-metrics/returns-history-rows (fn [_state _summary _summary-scope]
                                                           (swap! returns-history-calls inc)
                                                           [[t0 0]
                                                            [t1 11]
                                                            [t2 19]])]
      (let [state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :returns
                                  :returns-benchmark-coins ["SPY"]}
                   :asset-selector {:markets [{:coin "SPY"
                                               :symbol "SPY"
                                               :market-type :spot
                                               :openInterest "200"
                                               :cache-order 1}]}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                     [t1 11]
                                                                     [t2 19]]
                                                        :accountValueHistory [[t0 100]
                                                                              [t1 111]
                                                                              [t2 119]]
                                                        :vlm 0}}}
                   :candles {"SPY" {:1h [{:t t0 :c 50}
                                         {:t t1 :c 53}
                                         {:t t2 :c 57}]}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            view-model (vm/portfolio-vm state)]
        (is (= 1 @returns-history-calls))
        (is (= [:strategy :benchmark-0]
               (mapv :id (get-in view-model [:chart :series]))))
        (is (true? (get-in view-model [:performance-metrics :benchmark-selected?])))))))

(deftest portfolio-vm-non-returns-chart-tab-keeps-benchmark-metrics-and-single-request-test
  (let [original-request portfolio-actions/returns-benchmark-candle-request
        request-calls (atom 0)]
    (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                                {:spot-equity 10
                                                                 :perps-value 10
                                                                 :cross-account-value 10
                                                                 :unrealized-pnl 0})
                  portfolio-actions/returns-benchmark-candle-request (fn [summary-time-range]
                                                                        (swap! request-calls inc)
                                                                        (original-request summary-time-range))]
      (let [t0 fixture-start-ms
            t1 (+ fixture-start-ms day-ms)
            t2 (+ fixture-start-ms (* 2 day-ms))
            state {:account {:mode :classic}
                   :portfolio-ui {:summary-scope :all
                                  :summary-time-range :month
                                  :chart-tab :pnl
                                  :returns-benchmark-coins ["SPY" "QQQ"]}
                   :asset-selector {:markets [{:coin "SPY"
                                               :symbol "SPY"
                                               :market-type :spot
                                               :openInterest "200"
                                               :cache-order 1}
                                              {:coin "QQQ"
                                               :symbol "QQQ"
                                               :market-type :spot
                                               :openInterest "100"
                                               :cache-order 2}]}
                   :portfolio {:summary-by-key {:month {:pnlHistory [[t0 0]
                                                                     [t1 11]
                                                                     [t2 19]]
                                                        :accountValueHistory [[t0 100]
                                                                              [t1 111]
                                                                              [t2 119]]
                                                        :vlm 0}}}
                   :candles {"SPY" {:1h [{:t t0 :c 50}
                                         {:t t1 :c 53}
                                         {:t t2 :c 57}]}
                             "QQQ" {:1h [{:t t0 :c 100}
                                         {:t t1 :c 101}
                                         {:t t2 :c 103}]}}
                   :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                             :totalVaultEquity 0}
                   :borrow-lend {:total-supplied-usd 0}}
            view-model (vm/portfolio-vm state)]
        (is (= 1 @request-calls))
        (is (= :pnl (get-in view-model [:chart :selected-tab])))
        (is (= [:strategy]
               (mapv :id (get-in view-model [:chart :series]))))
        (is (false? (get-in view-model [:chart :benchmark-selected?])))
        (is (true? (get-in view-model [:performance-metrics :benchmark-selected?])))
        (is (= ["SPY" "QQQ"]
               (get-in view-model [:performance-metrics :benchmark-coins])))))))

(deftest portfolio-vm-chart-y-axis-uses-readable-step-ticks-test
  (with-redefs [account-equity-view/account-equity-metrics (fn [_]
                                                              {:spot-equity 10
                                                               :perps-value 10
                                                               :cross-account-value 10
                                                               :unrealized-pnl 0})]
    (let [state {:account {:mode :classic}
                 :portfolio-ui {:summary-scope :all
                                :summary-time-range :month
                                :chart-tab :pnl}
                 :portfolio {:summary-by-key {:month {:pnlHistory [[1 0] [2 90000]]
                                                      :accountValueHistory [[1 100] [2 200]]
                                                      :vlm 10}}}
                 :webdata2 {:clearinghouseState {:marginSummary {:accountValue 10}}
                           :totalVaultEquity 0}
                 :borrow-lend {:total-supplied-usd 0}}
          view-model (vm/portfolio-vm state)]
      (is (= [90000 60000 30000 0]
             (mapv :value (get-in view-model [:chart :y-ticks]))))))))
