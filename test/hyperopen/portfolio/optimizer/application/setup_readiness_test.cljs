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

(deftest build-readiness-injects-native-marks-as-execution-prices-test
  ;; A HIP-3 perp prices its returns off an equity proxy, so the last history close is the
  ;; proxy level (here 112), not the native perp mark. build-readiness must seed the execution
  ;; reference price from the live market catalog ([:asset-selector :market-by-key]), so the
  ;; rebalance preview sizes and costs against the native mark (95), not the proxy. The "spot:BTC"
  ;; decoy shares the coin "BTC" with a different price: resolution is per market-type (by the
  ;; instrument's own catalog key), so the perp must NOT pick up the spot price.
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:asset-selector
                     {:market-by-key
                      {"perp:BTC" {:coin "BTC" :markRaw "95.0" :mark 95}
                       "spot:BTC" {:coin "BTC" :markRaw "1.0" :mark 1}}}
                     :portfolio
                     {:optimizer
                      {:history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "112"}]
                         "ETH" [{:time 1000 :close "2000"}
                                {:time 2000 :close "2200"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :ready (:status readiness)))
    ;; native perp mark (95) is seeded as the execution price, keyed by instrument-id — NOT the
    ;; same-coin spot decoy price (1.0)
    (is (= 95.0
           (get-in readiness
                   [:request :execution-assumptions :prices-by-id "perp:BTC"])))
    ;; ETH has no catalog mark -> no native price injected (preview falls back to history close)
    (is (nil? (get-in readiness
                      [:request :execution-assumptions :prices-by-id "perp:ETH"])))))

(deftest build-readiness-allows-empty-black-litterman-views-test
  ;; Zero authored views is a valid views-aware run: the posterior equals the
  ;; baseline expected returns, so readiness must not block on it.
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft {:return-model {:kind :black-litterman
                                              :views []}}
                       :history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]
                         "ETH" [{:time 1000 :close "2000"}
                                {:time 2000 :close "2200"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (= :ready (:status readiness)))
    (is (nil? (:reason readiness)))
    (is (= true (:runnable? readiness)))))

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
                                                  {:instrument-id "perp:short"}
                                                  {:instrument-id "perp:return-missing"}
                                                  {:instrument-id "perp:return-short"}
                                                  {:instrument-id "perp:stale"}
                                                  {:instrument-id "perp:rejected"}]
                             :universe [{:instrument-id "vault:aligned"}
                                        {:instrument-id "perp:stale"}]
                             :warnings [{:code :insufficient-common-history
                                         :observations 1
                                         :required 2}
                                        {:code :missing-vault-history
                                         :instrument-id "vault:missing"}
                                        {:code :insufficient-candle-history
                                         :instrument-id "perp:short"
                                         :observations 1
                                         :required 2}
                                        {:code :missing-return-history
                                         :instrument-id "perp:return-missing"}
                                        {:code :insufficient-return-history
                                         :instrument-id "perp:return-short"
                                         :observations 1
                                         :required 2}
                                        {:code :stale-history
                                         :instrument-id "perp:stale"}
                                        {:code :validation-failed
                                         :instrument-id "perp:rejected"}]}}]
    (is (= {"vault:aligned" :aligned
            "vault:misaligned" :loaded-but-misaligned
            "vault:missing" :missing
            "perp:short" :insufficient
            "perp:return-missing" :missing
            "perp:return-short" :insufficient
            "perp:stale" :stale
            "perp:rejected" :rejected}
           (setup-readiness/history-status-by-instrument readiness)))))

(deftest warning-display-message-describes-api-v2-return-history-test
  (let [request {:requested-universe [{:instrument-id "perp:BTC"
                                       :market-type :perp
                                       :coin "BTC"
                                       :name "Bitcoin"}
                                      {:instrument-id "perp:ETH"
                                       :market-type :perp
                                       :coin "ETH"
                                       :name "Ether"}]}]
    (is (= "Bitcoin: no optimizer return history returned."
           (setup-readiness/warning-display-message
            request
            {:code :missing-return-history
             :instrument-id "perp:BTC"})))
    (is (= "Ether: only 1 usable optimizer return observations; 2 required."
           (setup-readiness/warning-display-message
            request
            {:code :insufficient-return-history
             :instrument-id "perp:ETH"
             :observations 1
             :required 2})))))

(deftest build-readiness-blocks-api-v2-missing-and-rejected-history-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft {:universe [{:instrument-id "perp:BTC"
                                           :market-type :perp
                                           :coin "BTC"
                                           :name "Bitcoin"}
                                          {:instrument-id "perp:BAD"
                                           :market-type :perp
                                           :coin "BAD"
                                           :name "Bad Perp"}]}
                       :history-data
                       {:api-v2-history
                        {:status :partial
                         :common-calendar [1000 2000]
                         :return-calendar [2000]
                         :aligned-returns-by-instrument
                         {"perp:BTC" {:returns [0.1]}}
                         :series-by-instrument
                         {"perp:BTC" {:local-instrument-id "perp:BTC"
                                      :instrument-id "hl:perp:BTC"
                                      :lineage-kind :native
                                      :points [{:time-ms 1000
                                                :close 100
                                                :return nil}
                                               {:time-ms 2000
                                                :close 110
                                                :return 0.1}]
                                      :funding {:status :available
                                                :annualized-carry 0}}
                          "perp:BAD" {:local-instrument-id "perp:BAD"
                                      :instrument-id "hl:perp:BAD"
                                      :lineage-kind :rejected
                                      :points []
                                      :warnings [{:code :validation-failed
                                                  :instrument-id "perp:BAD"}]}}}}}}}))]
    (is (= :blocked (:status readiness)))
    (is (= :incomplete-history (:reason readiness)))
    (is (= ["perp:BAD"]
           (mapv :instrument-id (:blocking-warnings readiness))))
    (is (= "Bad Perp: backend validation rejected optimizer history."
           (get-in readiness [:blocking-warnings 0 :message])))))

(deftest build-readiness-blocks-api-v2-aligned-only-mixed-frequency-risk-test
  (let [day-ms 86400000
        vault-id "vault:0x1111111111111111111111111111111111111111"
        readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft {:universe [{:instrument-id "perp:BTC"
                                           :market-type :perp
                                           :coin "BTC"
                                           :name "Bitcoin"}
                                          {:instrument-id vault-id
                                           :market-type :vault
                                           :coin vault-id
                                           :vault-address "0x1111111111111111111111111111111111111111"
                                           :name "Basis Vault"}]
                               :risk-model {:kind :diagonal-shrink}}
                       :history-data
                       {:api-v2-history
                        {:status :partial
                         :common-calendar [0 (* 14 day-ms) (* 28 day-ms)]
                         :return-calendar [(* 14 day-ms) (* 28 day-ms)]
                         :aligned-returns-by-instrument
                         {"perp:BTC" {:returns [0.1 0.05]}
                          vault-id {:returns [0.02 0.03]}}
                         :series-by-instrument
                         {"perp:BTC" {:local-instrument-id "perp:BTC"
                                      :instrument-id "hl:perp:BTC"
                                      :lineage-kind :native
                                      :points [{:time-ms 0
                                                :close 100
                                                :return nil}
                                               {:time-ms (* 14 day-ms)
                                                :close 110
                                                :return 0.1}
                                               {:time-ms (* 28 day-ms)
                                                :close 115.5
                                                :return 0.05}]
                                      :funding {:status :available
                                                :annualized-carry 0}}
                          vault-id {:local-instrument-id vault-id
                                    :instrument-id "hl:vault:basis"
                                    :lineage-kind :vault-derived
                                    :series-kind :return-index
                                    :points []
                                    :funding {:status :not-applicable}}}}}}}}))]
    (is (= :blocked (:status readiness)))
    (is (= :incomplete-history (:reason readiness)))
    (is (= false (:runnable? readiness)))
    (is (= [{:code :missing-native-risk-history
             :instrument-id vault-id
             :policy :mixed-frequency-requires-native-price-series
             :message "Basis Vault: no native optimizer price history returned for mixed-frequency risk."}]
           (mapv #(select-keys % [:code :instrument-id :policy :message])
                 (:blocking-warnings readiness))))))

(deftest build-readiness-keeps-api-v2-proxy-vault-and-funding-warnings-nonblocking-test
  (let [vault-id "vault:0x1111111111111111111111111111111111111111"
        readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft {:universe [{:instrument-id "perp:BTC"
                                           :market-type :perp
                                           :coin "BTC"
                                           :name "Bitcoin"}
                                          {:instrument-id vault-id
                                           :market-type :vault
                                           :coin vault-id
                                           :vault-address "0x1111111111111111111111111111111111111111"
                                           :name "Basis Vault"}]}
                       :history-data
                       {:api-v2-history
                        {:status :partial
                         :common-calendar [1000 2000]
                         :return-calendar [2000]
                         :aligned-returns-by-instrument
                         {"perp:BTC" {:returns [0.1]}
                          vault-id {:returns [0.02]}}
                         :series-by-instrument
                         {"perp:BTC" {:local-instrument-id "perp:BTC"
                                      :instrument-id "hl:perp:BTC"
                                      :lineage-kind :stitched-native-proxy
                                      :points [{:time-ms 1000
                                                :close 100
                                                :return nil}
                                               {:time-ms 2000
                                                :close 110
                                                :return 0.1}]
                                      :funding {:status :missing}
                                      :warnings [{:code :proxy-history-used
                                                  :instrument-id "perp:BTC"}
                                                 {:code :funding-history-missing
                                                  :instrument-id "perp:BTC"}]}
                          vault-id {:local-instrument-id vault-id
                                    :instrument-id "hl:vault:basis"
                                    :lineage-kind :vault-derived
                                    :series-kind :return-index
                                    :points [{:time-ms 1000
                                              :close 100
                                              :return nil}
                                             {:time-ms 2000
                                              :close 102
                                              :return 0.02}]
                                    :funding {:status :not-applicable}
                                    :warnings [{:code :vault-derived-history-used
                                                :instrument-id vault-id}]}}}}}}}))]
    (is (= :ready (:status readiness)))
    (is (= true (:runnable? readiness)))
    (is (= #{:proxy-history-used
             :funding-history-missing
             :vault-derived-history-used}
           (set (map :code (:warnings readiness)))))
    (is (= {"perp:BTC" :aligned
            vault-id :aligned}
           (setup-readiness/history-status-by-instrument readiness)))))

(deftest build-readiness-deduplicates-api-v2-warning-copies-test
  (let [warning {:code :proxy-history-used
                 :instrument-id "perp:BTC"
                 :proxy-mapping-id "proxy-review:hl:perp:BTC"}
        readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft {:universe [{:instrument-id "perp:BTC"
                                           :market-type :perp
                                           :coin "BTC"
                                           :name "Bitcoin"}]}
                       :history-data
                       {:api-v2-history
                        {:status :ok
                         :warnings [warning]
                         :common-calendar [1000 2000]
                         :return-calendar [2000]
                         :aligned-returns-by-instrument
                         {"perp:BTC" {:returns [0.1]}}
                         :series-by-instrument
                         {"perp:BTC" {:local-instrument-id "perp:BTC"
                                      :instrument-id "hl:perp:BTC"
                                      :lineage-kind :stitched-native-proxy
                                      :points [{:time-ms 1000
                                                :close 100
                                                :return nil}
                                               {:time-ms 2000
                                                :close 110
                                                :return 0.1}]
                                      :funding {:status :available
                                                :annualized-carry 0}
                                      :warnings [warning]}}}}}}}))]
    (is (= :ready (:status readiness)))
    (is (= [{:code :proxy-history-used
             :instrument-id "perp:BTC"
             :proxy-mapping-id "proxy-review:hl:perp:BTC"}]
           (mapv #(select-keys % [:code :instrument-id :proxy-mapping-id])
                 (:warnings readiness))))))

(deftest warning-display-message-labels-backend-api-v2-warning-with-selected-asset-test
  (let [request {:requested-universe [{:instrument-id "perp:BTC"
                                       :market-type :perp
                                       :coin "BTC"
                                       :name "Bitcoin"
                                       :optimizer-history/instrument-id "hl:perp:BTC"}]}
        message (setup-readiness/warning-display-message
                 request
                 {:code :proxy-history-used
                  :instrument-id "hl:perp:BTC"})]
    (is (= "Bitcoin: approved substitute history is included."
           message))))

;; --- History assumptions (no-history asset: perp:ETH has no candle history) ---

(defn- assumptions-state
  [eth-assumption objective]
  (optimizer-state
   {:portfolio
    {:optimizer
     {:draft
      {:objective objective
       :history-assumptions {"perp:ETH" eth-assumption}}
      :history-data
      {:candle-history-by-coin
       {"BTC" [{:time 1000 :close "100"}
               {:time 2000 :close "110"}]}
       :funding-history-by-coin {}}}}}))

(deftest readiness-allows-complete-conservative-history-assumption-test
  (let [readiness (setup-readiness/build-readiness
                   (assumptions-state {:behavior :conservative
                                       :expected-return nil
                                       :volatility 0.9
                                       :max-weight 0.03
                                       :correlation-floor 0.75}
                                      {:kind :minimum-variance}))]
    (is (= :ready (:status readiness)))
    (is (nil? (:reason readiness)))
    (is (true? (:runnable? readiness)))
    (is (contains? (set (map :instrument-id (get-in readiness [:request :universe])))
                   "perp:ETH")
        "A complete conservative assumption re-admits the no-history asset, so the run is allowed.")))

(deftest readiness-blocks-conservative-missing-volatility-test
  (let [readiness (setup-readiness/build-readiness
                   (assumptions-state {:behavior :conservative
                                       :expected-return nil
                                       :volatility nil
                                       :max-weight 0.03
                                       :correlation-floor 0.75}
                                      {:kind :minimum-variance}))
        warning (first (filter #(= "perp:ETH" (:instrument-id %))
                               (:blocking-warnings readiness)))]
    (is (= :blocked (:status readiness)))
    (is (= :missing-history-assumptions (:reason readiness)))
    (is (= false (:runnable? readiness)))
    (is (= :history-assumption-incomplete (:code warning)))
    (is (= :volatility (:missing warning)))
    (is (= "ETH needs a modeled annual volatility." (:message warning)))))

(deftest readiness-requires-expected-return-only-for-return-seeking-objectives-test
  (let [conservative {:behavior :conservative
                      :expected-return nil
                      :volatility 0.9
                      :max-weight 0.03
                      :correlation-floor 0.75}
        return-seeking (setup-readiness/build-readiness
                        (assumptions-state conservative {:kind :max-sharpe}))
        return-seeking-warning (first (filter #(= "perp:ETH" (:instrument-id %))
                                              (:blocking-warnings return-seeking)))]
    (is (= :missing-history-assumptions (:reason return-seeking))
        "A return-seeking objective blocks until an expected return is supplied.")
    (is (= :expected-return (:missing return-seeking-warning)))
    (let [supplied (setup-readiness/build-readiness
                    (assumptions-state (assoc conservative :expected-return 0.25)
                                       {:kind :max-sharpe}))]
      (is (= :ready (:status supplied))
          "With the expected return supplied, the return-seeking run is allowed."))))

;; --- Proxy history assumptions -------------------------------------------------

(def ^:private complete-proxy-assumption
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC"]
           :relationship-strength :medium
           :prior-weights nil}})

(deftest readiness-allows-complete-proxy-history-assumption-test
  (let [readiness (setup-readiness/build-readiness
                   (assumptions-state complete-proxy-assumption
                                      {:kind :minimum-variance}))
        request (:request readiness)]
    (is (= :ready (:status readiness)))
    (is (true? (:runnable? readiness)))
    (is (contains? (set (map :instrument-id (:universe request))) "perp:ETH")
        "A complete proxy assumption re-admits the asset to the engine universe.")
    (is (= ["perp:BTC"]
           (get-in request [:history-assumptions "perp:ETH" :proxy-instrument-ids]))
        "The engine request carries the flattened proxy assumption.")
    (is (= 0.05
           (get-in request [:constraints :per-asset-overrides "perp:ETH" :max-weight]))
        "The proxy cap mirrors into the per-asset overrides.")))

(defn- proxy-blocking-warning
  [assumption objective]
  (let [readiness (setup-readiness/build-readiness
                   (assumptions-state assumption objective))]
    {:readiness readiness
     :warning (first (filter #(= "perp:ETH" (:instrument-id %))
                             (:blocking-warnings readiness)))}))

(deftest readiness-blocks-proxy-without-proxies-test
  (let [{:keys [readiness warning]}
        (proxy-blocking-warning (assoc-in complete-proxy-assumption
                                          [:proxy :instrument-ids] [])
                                {:kind :minimum-variance})]
    (is (= :missing-history-assumptions (:reason readiness)))
    (is (= :proxy-instruments (:missing warning)))
    (is (= "ETH is set to be modeled but no proxy assets are selected."
           (:message warning)))))

(deftest readiness-blocks-proxy-self-reference-test
  (let [{:keys [readiness warning]}
        (proxy-blocking-warning (assoc-in complete-proxy-assumption
                                          [:proxy :instrument-ids]
                                          ["perp:BTC" "perp:ETH"])
                                {:kind :minimum-variance})]
    (is (= :blocked (:status readiness)))
    (is (= :self-proxy (:missing warning)))
    (is (= "ETH cannot use itself as a proxy." (:message warning)))))

(deftest readiness-blocks-proxy-without-usable-history-test
  (let [{:keys [readiness warning]}
        (proxy-blocking-warning (assoc-in complete-proxy-assumption
                                          [:proxy :instrument-ids]
                                          ["perp:GONE"])
                                {:kind :minimum-variance})]
    (is (= :blocked (:status readiness)))
    (is (= :proxy-history (:missing warning)))
    (is (= ["perp:GONE"] (:proxy-instrument-ids warning)))
    (is (= "ETH proxy asset perp:GONE has no usable optimizer history."
           (:message warning)))))

(deftest readiness-blocks-proxy-missing-volatility-test
  (let [{:keys [readiness warning]}
        (proxy-blocking-warning (assoc complete-proxy-assumption :volatility nil)
                                {:kind :minimum-variance})]
    (is (= :blocked (:status readiness)))
    (is (= :volatility (:missing warning)))
    (is (= "ETH needs a modeled annual volatility." (:message warning)))))

(deftest readiness-blocks-proxy-missing-cap-test
  (let [{:keys [warning]}
        (proxy-blocking-warning (assoc complete-proxy-assumption :max-weight nil)
                                {:kind :minimum-variance})]
    (is (= :max-weight (:missing warning)))
    (is (= "ETH needs a max allocation cap." (:message warning)))))

(deftest readiness-blocks-proxy-cap-above-global-max-test
  ;; The base fixture draft has no explicit :max-asset-weight, so give it one.
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft
                       {:objective {:kind :minimum-variance}
                        :constraints {:max-asset-weight 0.5}
                        :history-assumptions
                        {"perp:ETH" (assoc complete-proxy-assumption :max-weight 0.6)}}
                       :history-data
                       {:candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]}
                        :funding-history-by-coin {}}}}}))
        warning (first (filter #(= "perp:ETH" (:instrument-id %))
                               (:blocking-warnings readiness)))]
    (is (= :blocked (:status readiness)))
    (is (= :max-weight-exceeds-global (:missing warning)))
    (is (= "ETH max allocation cap cannot exceed the global max asset weight."
           (:message warning)))))

(deftest readiness-requires-proxy-expected-return-only-for-return-seeking-test
  (let [no-return (assoc complete-proxy-assumption :expected-return nil)
        min-var (setup-readiness/build-readiness
                 (assumptions-state no-return {:kind :minimum-variance}))
        sharpe (proxy-blocking-warning no-return {:kind :max-sharpe})]
    (is (= :ready (:status min-var))
        "Minimum variance needs no expected return on a proxy assumption.")
    (is (= :expected-return (:missing (:warning sharpe))))
    (is (= "ETH needs an expected annual return for this objective."
           (:message (:warning sharpe))))
    (is (= :ready (:status (setup-readiness/build-readiness
                            (assumptions-state complete-proxy-assumption
                                               {:kind :max-sharpe}))))
        "With the expected return present the return-seeking run is allowed.")))

;; --- Short-history blocking (assumption-required threshold) ---------------------

(def ^:private day-ms* (* 24 60 60 1000))

(defn- daily-candles
  [start-day count* base]
  (mapv (fn [idx]
          {:time (* (+ start-day idx) day-ms*)
           :close (str (+ base idx))})
        (range count*)))

(defn- short-history-state
  [{:keys [btc-days tokenx-days assumptions]}]
  (optimizer-state
   {:portfolio
    {:optimizer
     {:draft
      {:universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                  {:instrument-id "perp:TOKENX" :market-type :perp :coin "TOKENX"}]
       :objective {:kind :minimum-variance}
       :history-assumptions (or assumptions {})}
      :runtime {:as-of-ms (* (+ btc-days 1) day-ms*)
                :stale-after-ms (* 10 day-ms*)
                :funding-periods-per-year 8760}
      :history-data
      {:candle-history-by-coin
       {"BTC" (daily-candles 0 btc-days 100)
        ;; TOKENX's candles cover the LAST tokenx-days of BTC's window.
        "TOKENX" (daily-candles (- btc-days tokenx-days) tokenx-days 10)}
       :funding-history-by-coin {}}}}}))

(deftest readiness-blocks-thin-asset-without-assumption-test
  (let [readiness (setup-readiness/build-readiness
                   (short-history-state {:btc-days 400 :tokenx-days 10}))
        warning (first (filter #(= "perp:TOKENX" (:instrument-id %))
                               (:blocking-warnings readiness)))]
    (is (= :blocked (:status readiness)))
    (is (= :missing-history-assumptions (:reason readiness)))
    (is (= :history-assumption-required (:code warning)))
    (is (= "TOKENX needs a history assumption. Model it from similar assets or use a conservative assumption."
           (:message warning)))))

(deftest readiness-keeps-all-young-universe-runnable-test
  ;; With no long-history asset to borrow from, the long-standing thin-history
  ;; behavior stands: the run is not blocked.
  (let [readiness (setup-readiness/build-readiness
                   (short-history-state {:btc-days 100 :tokenx-days 10}))]
    (is (= :ready (:status readiness)))))

(deftest readiness-unblocks-thin-asset-with-proxy-and-preserves-window-test
  (let [readiness (setup-readiness/build-readiness
                   (short-history-state
                    {:btc-days 400
                     :tokenx-days 10
                     :assumptions {"perp:TOKENX" complete-proxy-assumption}}))
        request (:request readiness)
        series (get-in request [:history-assumptions "perp:TOKENX"
                                :regression-series])]
    (is (= :ready (:status readiness)))
    (is (contains? (set (map :instrument-id (:universe request))) "perp:TOKENX"))
    (is (= 399 (count (get-in request [:history :return-calendar])))
        "BTC keeps its full estimation window - the 10-day asset no longer shrinks it.")
    (is (= 9 (:observations series))
        "The regression series carries the 10-day overlap (9 returns).")
    (is (= 9 (count (:x-returns series))))
    (is (= 9 (count (get-in series [:proxy-returns-by-id "perp:BTC"]))))))

(deftest warning-code-summary-distinguishes-staleness-codes-test
  ;; Regression: both staleness codes rendered the identical "use stale cached
  ;; history" sentence, so the readiness panel showed two same-worded groups
  ;; with different counts side by side.
  (let [stale (setup-readiness/warning-code-summary :stale-history 15)
        fetch-failed (setup-readiness/warning-code-summary :source-fetch-failed 6)]
    (is (= "15 assets use stale history" stale))
    (is (= "6 assets could not refresh from the history provider — using cached or native history"
           fetch-failed))
    (is (not= stale fetch-failed))
    ;; "Proxy" is internal mapping language; the grouped summary says
    ;; "substitute history".
    (is (= "8 assets use substitute history"
           (setup-readiness/warning-code-summary :proxy-history-used 8)))
    (is (= "1 asset uses substitute history"
           (setup-readiness/warning-code-summary :proxy-history-used 1)))))

(deftest build-readiness-allows-running-while-refreshing-a-loaded-bundle-test
  ;; The stale-while-revalidate cache hydrates the wallet's last bundle and
  ;; deliberately leaves history-load-state alone, so a repeat visit runs a
  ;; background refresh over data that is already complete. Before 2026-08-24
  ;; that refresh reported :history-loading and set runnable? false for its whole
  ;; duration. :loaded-at-ms is what separates it from a cold load.
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:history-load-state {:status :loading
                                            :request-signature
                                            {:universe [{:instrument-id "perp:BTC"
                                                         :market-type :perp
                                                         :coin "BTC"}
                                                        {:instrument-id "perp:ETH"
                                                         :market-type :perp
                                                         :coin "ETH"}]}}
                       :history-data
                       {:loaded-at-ms 1700000000000
                        :candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]
                         "ETH" [{:time 1000 :close "2000"}
                                {:time 2000 :close "2200"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (true? (:refreshing? readiness)))
    (is (not= :history-loading (:reason readiness)))
    (is (true? (:runnable? readiness)))
    (is (= :ready (:status readiness)))))

(deftest build-readiness-refreshing-still-blocks-an-asset-with-no-history-test
  ;; Running during a refresh must never mean running with a missing asset.
  ;; incomplete-history? is the guard that keeps that true: DOGE is requested but
  ;; has no candles, so it drops out of the built request and the mismatch blocks.
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft {:universe [{:instrument-id "perp:BTC"
                                           :market-type :perp
                                           :coin "BTC"}
                                          {:instrument-id "perp:DOGE"
                                           :market-type :perp
                                           :coin "DOGE"}]}
                       :history-load-state {:status :loading}
                       :history-data
                       {:loaded-at-ms 1700000000000
                        :candle-history-by-coin
                        {"BTC" [{:time 1000 :close "100"}
                                {:time 2000 :close "110"}]}
                        :funding-history-by-coin {}}}}}))]
    (is (true? (:refreshing? readiness)))
    (is (false? (:runnable? readiness)))
    (is (= :blocked (:status readiness)))))
