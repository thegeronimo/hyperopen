(ns hyperopen.views.portfolio.vm.benchmarks.computation
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.vaults.detail.performance :as vault-performance]
            [hyperopen.views.portfolio.vm.benchmarks.selector :as selector]
            [hyperopen.views.portfolio.vm.history :as vm-history]
            [hyperopen.views.portfolio.vm.summary :as vm-summary]
            [hyperopen.views.portfolio.vm.vaults-scope :as vaults-scope]))

(def ^:private empty-source-version-counter
  0)

(defn- benchmark-details-by-address
  [state vault-address]
  (or (get-in state [:vaults :benchmark-details-by-address vault-address])
      (get-in state [:vaults :details-by-address vault-address])))

(defn- trader-benchmark-summary-by-key
  [state trader-address]
  (if (= (account-context/normalize-address trader-address)
         (account-context/effective-account-address state))
    (get-in state [:portfolio :summary-by-key])
    (get-in state [:portfolio :trader-benchmarks-by-address trader-address :summary-by-key])))

(defn- trader-benchmark-summary
  [state trader-address summary-scope summary-time-range]
  (let [summary-by-key (trader-benchmark-summary-by-key state trader-address)]
    (when (seq summary-by-key)
      (:summary (vm-summary/returns-history-context summary-by-key
                                                    summary-scope
                                                    summary-time-range)))))

(defn sampled-series-source-version-counter
  [rows]
  (let [rows* (or rows [])
        row-count (count rows*)]
    (if (pos? row-count)
      (let [mid-idx (quot row-count 2)
            first-row (nth rows* 0 nil)
            mid-row (nth rows* mid-idx nil)
            last-row (nth rows* (dec row-count) nil)]
        (hash [row-count
               (vm-history/history-point-time-ms first-row)
               (vm-history/history-point-value first-row)
               (vm-history/history-point-time-ms mid-row)
               (vm-history/history-point-value mid-row)
               (vm-history/history-point-time-ms last-row)
               (vm-history/history-point-value last-row)]))
      empty-source-version-counter)))

(defn benchmark-source-version-by-coin
  [benchmark-cumulative-rows-by-coin selected-benchmark-coins]
  (into {}
        (map (fn [coin]
               [coin
                (sampled-series-source-version-counter
                 (get benchmark-cumulative-rows-by-coin coin))]))
        selected-benchmark-coins))

(defn benchmark-cumulative-return-rows-by-coin
  ([state summary-time-range benchmark-coins strategy-cumulative-rows anchor-time-ms end-time-ms]
   (benchmark-cumulative-return-rows-by-coin state
                                             :all
                                             summary-time-range
                                             benchmark-coins
                                             strategy-cumulative-rows
                                             anchor-time-ms
                                             end-time-ms))
  ([state summary-scope summary-time-range benchmark-coins _strategy-cumulative-rows anchor-time-ms end-time-ms]
   (if (seq benchmark-coins)
     (let [{:keys [interval]} (portfolio-actions/returns-benchmark-candle-request summary-time-range)
           normalized-range (portfolio-actions/normalize-summary-time-range summary-time-range)
           summary-scope (if (vaults-scope/vaults-scope? summary-scope)
                           :all
                           summary-scope)]
       (reduce (fn [rows-by-coin coin]
                 (if (seq coin)
                   (if-let [vault-address (selector/vault-benchmark-address coin)]
                     (let [details (benchmark-details-by-address state vault-address)
                           summary (vault-performance/portfolio-summary-by-range details
                                                                                normalized-range)]
                       (assoc rows-by-coin
                              coin
                              (portfolio-metrics/returns-history-rows state summary :all)))
                     (if-let [trader-address (portfolio-actions/trader-benchmark-address coin)]
                       (let [summary (trader-benchmark-summary state
                                                               trader-address
                                                               summary-scope
                                                               normalized-range)]
                         (assoc rows-by-coin
                                coin
                                (portfolio-metrics/returns-history-rows state summary summary-scope)))
                       (let [candles (vm-history/benchmark-candle-points (get-in state [:candles coin interval]))]
                         (assoc rows-by-coin
                                coin
                                (vm-history/cumulative-return-row-pairs
                                 (vm-history/benchmark-market-return-rows candles
                                                                          {:anchor-time-ms anchor-time-ms
                                                                           :end-time-ms end-time-ms}))))))
                   rows-by-coin))
               {}
               benchmark-coins))
     {})))

(defn benchmark-computation-context
  [state summary-context-or-entry summary-scope summary-time-range returns-benchmark-selector]
  (let [summary-context (if (contains? summary-context-or-entry :entry)
                          summary-context-or-entry
                          {:entry summary-context-or-entry
                           :effective-key summary-time-range})
        summary-by-key (vaults-scope/summary-by-key state summary-scope)
        returns-history-context (vm-summary/returns-history-context summary-by-key
                                                                    summary-scope
                                                                    summary-time-range
                                                                    summary-context)
        strategy-summary (:summary returns-history-context)
        summary-time-range* (:effective-range returns-history-context)
        strategy-cumulative-rows (portfolio-metrics/returns-history-rows state
                                                                         strategy-summary
                                                                         summary-scope)
        strategy-time-points (vm-history/cumulative-return-time-points strategy-cumulative-rows)
        end-time-ms (some-> strategy-cumulative-rows last first)
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector)
                                          []))
        anchor-time-ms (vm-history/market-benchmark-anchor-time-ms summary-time-range*
                                                                  strategy-time-points)
        benchmark-cumulative-rows-by-coin (benchmark-cumulative-return-rows-by-coin state
                                                                                    summary-scope
                                                                                    summary-time-range*
                                                                                    selected-benchmark-coins
                                                                                    strategy-cumulative-rows
                                                                                    anchor-time-ms
                                                                                    end-time-ms)
        strategy-source-version (vaults-scope/salt-source-version
                                 summary-scope
                                 (sampled-series-source-version-counter strategy-cumulative-rows))
        benchmark-source-version-map (benchmark-source-version-by-coin benchmark-cumulative-rows-by-coin
                                                                      selected-benchmark-coins)]
    {:strategy-cumulative-rows strategy-cumulative-rows
     :strategy-window returns-history-context
     :benchmark-cumulative-rows-by-coin benchmark-cumulative-rows-by-coin
     :strategy-source-version strategy-source-version
     :benchmark-source-version-map benchmark-source-version-map}))
