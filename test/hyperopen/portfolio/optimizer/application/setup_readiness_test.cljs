(ns hyperopen.portfolio.optimizer.application.setup-readiness-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left)
                    (map? right))
             (deep-merge left right)
             right))
         maps))

(defn- optimizer-state
  [overrides]
  (deep-merge
   {:portfolio
    {:optimizer
     {:draft
      {:universe [{:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"}
                  {:instrument-id "perp:ETH"
                   :market-type :perp
                   :coin "ETH"}]
       :objective {:kind :minimum-variance}
       :return-model {:kind :historical-mean}
       :risk-model {:kind :diagonal-shrink}
       :constraints {:long-only? true}}
      :runtime {:as-of-ms 2500
                :stale-after-ms 5000
                :funding-periods-per-year 1095}}}}
   overrides))

(deftest build-readiness-blocks-when-retained-history-misses-new-universe-assets-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :blocked (:status readiness)))
    (is (= :incomplete-history (:reason readiness)))
    (is (= false (:runnable? readiness)))
    (is (= ["perp:BTC"]
           (mapv :instrument-id (get-in readiness [:request :universe]))))
    (is (= #{:missing-candle-history}
           (set (map :code (:warnings readiness)))))))

(deftest build-readiness-identifies-the-assets-blocking-history-completeness-test
  (let [loaded-vault-address "0x1111111111111111111111111111111111111111"
        missing-vault-address "0x2222222222222222222222222222222222222222"
        loaded-vault-id (str "vault:" loaded-vault-address)
        missing-vault-id (str "vault:" missing-vault-address)
        readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft
                       {:universe [{:instrument-id loaded-vault-id
                                    :market-type :vault
                                    :coin loaded-vault-id
                                    :vault-address loaded-vault-address
                                    :name "Loaded Vault"}
                                   {:instrument-id missing-vault-id
                                    :market-type :vault
                                    :coin missing-vault-id
                                    :vault-address missing-vault-address
                                    :name "pmaIt"}]
                        :objective {:kind :minimum-variance}
                        :return-model {:kind :historical-mean}
                        :risk-model {:kind :diagonal-shrink}
                        :constraints {:long-only? true}}
                       :history-data
                       {:vault-details-by-address
                        {loaded-vault-address
                         {:portfolio
                          {:all-time
                           {:accountValueHistory [[1000 100]
                                                  [2000 110]
                                                  [3000 121]]
                            :pnlHistory [[1000 0]
                                         [2000 10]
                                         [3000 21]]}}}}}}}}))]
    (is (= :incomplete-history (:reason readiness)))
    (is (= [missing-vault-id]
           (mapv :instrument-id (:blocking-warnings readiness))))
    (is (= [{:code :missing-vault-history
             :instrument-id missing-vault-id
             :vault-address missing-vault-address
             :message "pmaIt: vault details returned no usable return history."}]
           (mapv #(select-keys % [:code :instrument-id :vault-address :message])
                 (:blocking-warnings readiness))))))

(deftest build-readiness-blocks-while-history-reload-is-pending-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:history-load-state {:status :loading
                                            :request-signature {:universe [{:instrument-id "perp:BTC"
                                                                           :market-type :perp
                                                                           :coin "BTC"}
                                                                          {:instrument-id "perp:ETH"
                                                                           :market-type :perp
                                                                           :coin "ETH"}]}}
                       :history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]
                         "ETH" [{:time 1000 :close "2000"}
                                {:time 2000 :close "2200"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :blocked (:status readiness)))
    (is (= :history-loading (:reason readiness)))
    (is (= false (:runnable? readiness)))
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (get-in readiness [:request :universe]))))))

(deftest build-readiness-injects-orderbook-cost-contexts-into-request-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:orderbooks {"BTC" {:timestamp 2400
                                         :render {:best-bid {:px-num 99}
                                                  :best-ask {:px-num 101}}}}
                     :portfolio
                     {:optimizer
                      {:draft {:execution-assumptions {:fallback-slippage-bps 35}}
                       :history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]
                         "ETH" [{:time 1000 :close "2000"}
                                {:time 2000 :close "2200"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :ready (:status readiness)))
    (is (= {:best-bid {:px-num 99}
            :best-ask {:px-num 101}
            :source :live-orderbook
            :stale? false}
           (get-in readiness
                   [:request :execution-assumptions :cost-contexts-by-id "perp:BTC"])))
    (is (= :fallback-cost-assumption
           (get-in readiness
                   [:request :execution-assumptions :cost-contexts-by-id "perp:ETH" :source])))
    (is (= 35
           (get-in readiness
                   [:request :execution-assumptions :cost-contexts-by-id "perp:ETH" :fallback-bps])))))

(deftest build-readiness-applies-manual-capital-when-snapshot-has-no-nav-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft {:execution-assumptions {:manual-capital-usdc 100000}}
                       :history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]
                         "ETH" [{:time 1000 :close "2000"}
                                {:time 2000 :close "2200"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :ready (:status readiness)))
    (is (= 100000
           (get-in readiness [:request :current-portfolio :capital :nav-usdc])))
    (is (= :manual
           (get-in readiness [:request :current-portfolio :capital :source])))
    (is (= true
           (get-in readiness [:request :current-portfolio :capital-ready?])))
    (is (= #{:manual-capital-base}
           (set (map :code (get-in readiness [:request :current-portfolio :warnings])))))))

(deftest history-status-by-instrument-distinguishes-aligned-misaligned-and-missing-history-test
  (let [readiness {:request {:requested-universe [{:instrument-id "vault:aligned"}
                                                  {:instrument-id "vault:misaligned"}
                                                  {:instrument-id "vault:missing"}
                                                  {:instrument-id "perp:short"}]
                             :universe [{:instrument-id "vault:aligned"}]
                             :warnings [{:code :insufficient-common-history
                                         :observations 1
                                         :required 2}
                                        {:code :missing-vault-history
                                         :instrument-id "vault:missing"}
                                        {:code :insufficient-candle-history
                                         :instrument-id "perp:short"
                                         :observations 1
                                         :required 2}]}}]
    (is (= {"vault:aligned" :aligned
            "vault:misaligned" :loaded-but-misaligned
            "vault:missing" :missing
            "perp:short" :insufficient}
           (setup-readiness/history-status-by-instrument readiness)))))
