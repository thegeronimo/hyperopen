(ns hyperopen.portfolio.optimizer.application.request-builder-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.defaults :as defaults]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(deftest build-engine-request-keeps-model-layers-separate-and-attaches-bl-prior-test
  (let [request (request-builder/build-engine-request
                 {:draft {:id "draft-1"
                          :universe [{:instrument-id "perp:BTC"
                                      :market-type :perp
                                      :coin "BTC"}
                                     {:instrument-id "spot:PURR"
                                      :market-type :spot
                                      :coin "PURR"}]
                          :objective {:kind :target-return
                                      :target-return 0.2}
                          :return-model {:kind :black-litterman
                                         :views [{:id "view-1"
                                                  :kind :relative
                                                  :long-instrument-id "perp:BTC"
                                                  :short-instrument-id "spot:PURR"
                                                  :return 0.04
                                                  :confidence 0.8}]}
                          :risk-model {:kind :diagonal-shrink
                                       :shrinkage 0.3}
                          :constraints {:long-only? true
                                        :max-asset-weight 0.4}
                          :execution-assumptions {:slippage-bps 25}}
                  :current-portfolio {:capital {:nav-usdc 1000}
                                      :by-instrument {"perp:BTC" {:weight 0.6}
                                                      "spot:PURR" {:weight 0.4}}}
                  :history-data {:candle-history-by-coin {"BTC" [{:time 1000 :close "100"}
                                                                 {:time 2000 :close "110"}]
                                                          "PURR" [{:time 1000 :close "10"}
                                                                  {:time 2000 :close "12"}]}
                                 :funding-history-by-coin {"BTC" [{:time-ms 1000
                                                                   :funding-rate-raw 0.001}]}}
                  :market-cap-by-coin {"BTC" 900
                                       "PURR" 100}
                  :as-of-ms 2500})]
    (is (= :target-return (get-in request [:objective :kind])))
    (is (= :black-litterman (get-in request [:return-model :kind])))
    (is (= :diagonal-shrink (get-in request [:risk-model :kind])))
    (is (= {:id "view-1"
            :kind :relative
            :long-instrument-id "perp:BTC"
            :short-instrument-id "spot:PURR"
            :return 0.04
            :confidence 0.8
            :weights {"perp:BTC" 1
                      "spot:PURR" -1}}
           (dissoc (first (get-in request [:return-model :views]))
                   :confidence-variance)))
    (is (near? 0.2 (get-in request [:return-model :views 0 :confidence-variance])))
    (is (= :market-cap (get-in request [:black-litterman-prior :source])))
    (is (= ["perp:BTC" "spot:PURR"]
           (mapv :instrument-id (:universe request))))
    (is (= ["perp:BTC" "spot:PURR"]
           (mapv :instrument-id (get-in request [:history :eligible-instruments]))))
    (is (= [] (:warnings request)))))

(deftest build-engine-request-surfaces-excluded-history-rows-and-fallback-prior-test
  (let [request (request-builder/build-engine-request
                 {:draft {:universe [{:instrument-id "perp:BTC"
                                      :market-type :perp
                                      :coin "BTC"}
                                     {:instrument-id "spot:MISSING"
                                      :market-type :spot
                                      :coin "MISSING"}]
                          :objective {:kind :minimum-variance}
                          :return-model {:kind :black-litterman}
                          :risk-model {:kind :sample-covariance}
                          :constraints {}}
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin {"BTC" [{:time 1000 :close "100"}]}}
                  :market-cap-by-coin {}
                  :as-of-ms 2000})]
    (is (= [] (:universe request)))
    (is (= :fallback-current-portfolio
           (get-in request [:black-litterman-prior :source])))
    (is (= #{:insufficient-candle-history
             :missing-candle-history
             :missing-market-cap-prior}
           (set (map :code (:warnings request)))))
    (is (= ["perp:BTC" "spot:MISSING"]
           (mapv :instrument-id (get-in request [:history :excluded-instruments]))))))

(deftest build-engine-request-uses-vault-details-for-vault-history-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        vault-instrument-id (str "vault:" vault-address)
        request (request-builder/build-engine-request
                 {:draft {:universe [{:instrument-id "perp:BTC"
                                      :market-type :perp
                                      :coin "BTC"}
                                     {:instrument-id vault-instrument-id
                                      :market-type :vault
                                      :coin vault-instrument-id
                                      :vault-address vault-address}]
                          :objective {:kind :minimum-variance}
                          :return-model {:kind :historical-mean}
                          :risk-model {:kind :diagonal-shrink}
                          :constraints {}}
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}
                                         {:time 3000 :close "121"}]}
                                 :funding-history-by-coin {}
                                 :vault-details-by-address
                                 {vault-address
                                  {:portfolio
                                   {:all-time
                                    {:accountValueHistory [[1000 100]
                                                           [2000 110]
                                                           [3000 121]]
                                     :pnlHistory [[1000 0]
                                                  [2000 10]
                                                  [3000 21]]}}}}}
                  :market-cap-by-coin {}
                  :as-of-ms 4000})]
    (is (= ["perp:BTC" vault-instrument-id]
           (mapv :instrument-id (:universe request))))
    (is (= ["perp:BTC" vault-instrument-id]
           (mapv :instrument-id (get-in request [:history :eligible-instruments]))))
    (is (near? 0.1 (get-in request [:history :return-series-by-instrument vault-instrument-id 0])))
    (is (= [] (:warnings request)))))

(deftest build-engine-request-normalizes-setup-constraint-keys-test
  (let [draft (assoc (defaults/default-draft)
                     :id "draft-constraints"
                     :universe [{:instrument-id "perp:BTC"
                                 :market-type :perp
                                 :coin "BTC"}]
                     :constraints {:long-only? false
                                   :gross-max 1.3
                                   :net-min -0.2
                                   :net-max 0.8
                                   :max-asset-weight 0.6
                                   :allowlist ["perp:BTC"]
                                   :blocklist ["spot:PURR"]
                                   :asset-overrides {"perp:BTC" {:max-weight 0.5}}
                                   :held-locks ["perp:BTC"]
                                   :perp-leverage {"perp:BTC" {:max-weight 0.4}}
                                   :max-turnover 0.25
                                   :rebalance-tolerance 0.01})
        request (request-builder/build-engine-request
                 {:draft draft
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms 2500})
        constraints (:constraints request)]
    (is (= 1.3 (:gross-leverage constraints)))
    (is (= {:min -0.2 :max 0.8} (:net-exposure constraints)))
    (is (= ["perp:BTC"] (:allowlist constraints)))
    (is (= ["spot:PURR"] (:blocklist constraints)))
    (is (= {"perp:BTC" {:max-weight 0.5}}
           (:per-asset-overrides constraints)))
    (is (= ["perp:BTC"] (:held-position-locks constraints)))
    (is (= {"perp:BTC" {:max-weight 0.4}}
           (:per-perp-leverage-caps constraints)))
    (is (not (contains? constraints :gross-max)))
    (is (not (contains? constraints :net-min)))
    (is (not (contains? constraints :asset-overrides)))))

(deftest build-engine-request-treats-empty-allowlist-as-unbounded-test
  (let [draft (assoc (defaults/default-draft)
                     :id "draft-default-constraints"
                     :universe [{:instrument-id "perp:BTC"
                                 :market-type :perp
                                 :coin "BTC"}])
        request (request-builder/build-engine-request
                 {:draft draft
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms 2500})
        constraints (:constraints request)]
    (is (nil? (:allowlist constraints)))
    (is (= [] (:blocklist constraints)))
    (is (= 1.0 (:gross-leverage constraints)))
    (is (= {:min 1.0 :max 1.0} (:net-exposure constraints)))
    (is (= 1.0 (:max-turnover constraints)))))

(deftest build-engine-request-normalizes-execution-assumptions-test
  (let [draft (assoc (defaults/default-draft)
                     :id "draft-execution-assumptions"
                     :universe [{:instrument-id "perp:BTC"
                                 :market-type :perp
                                 :coin "BTC"}]
                     :execution-assumptions {:default-order-type :market
                                             :slippage-fallback-bps 25
                                             :fee-mode :taker})
        request (request-builder/build-engine-request
                 {:draft draft
                  :current-portfolio {:by-instrument {"perp:BTC" {:weight 1}}}
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms 2500})
        assumptions (:execution-assumptions request)]
    (is (= :market (:default-order-type assumptions)))
    (is (= 25 (:fallback-slippage-bps assumptions)))
    (is (= :taker (:fee-mode assumptions)))
    (is (not (contains? assumptions :slippage-fallback-bps)))))

(defn- black-litterman-request
  [views]
  (request-builder/build-engine-request
   {:draft {:id "draft-bl-views"
            :universe [{:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"}
                       {:instrument-id "perp:SOL"
                        :market-type :perp
                        :coin "SOL"}
                       {:instrument-id "perp:HYPE"
                        :market-type :perp
                        :coin "HYPE"}]
            :objective {:kind :max-sharpe}
            :return-model {:kind :black-litterman
                           :views views}
            :risk-model {:kind :sample-covariance}
            :constraints {}}
    :current-portfolio {:by-instrument {"perp:ETH" {:weight 0.5}
                                        "perp:SOL" {:weight 0.3}
                                        "perp:HYPE" {:weight 0.2}}}
    :history-data {:candle-history-by-coin
                   {"ETH" [{:time 1000 :close "100"}
                           {:time 2000 :close "105"}
                           {:time 3000 :close "110"}]
                    "SOL" [{:time 1000 :close "50"}
                           {:time 2000 :close "52"}
                           {:time 3000 :close "55"}]
                    "HYPE" [{:time 1000 :close "10"}
                            {:time 2000 :close "12"}
                            {:time 3000 :close "14"}]}
                   :funding-history-by-coin {}}
    :market-cap-by-coin {"ETH" 600
                         "SOL" 300
                         "HYPE" 100}
    :as-of-ms 4000}))

(deftest build-engine-request-normalizes-new-black-litterman-view-shapes-test
  (let [request (black-litterman-request
                 [{:id "view-abs"
                   :kind :absolute
                   :instrument-id "perp:HYPE"
                   :return 0.45
                   :confidence 0.75
                   :horizon :1y
                   :notes "Momentum conviction"}
                  {:id "view-rel-out"
                   :kind :relative
                   :instrument-id "perp:ETH"
                   :comparator-instrument-id "perp:SOL"
                   :direction :outperform
                   :return 0.05
                   :confidence 0.5
                   :horizon :6m}
                  {:id "view-rel-under"
                   :kind :relative
                   :instrument-id "perp:ETH"
                   :comparator-instrument-id "perp:SOL"
                   :direction :underperform
                   :return 0.03
                   :confidence 0.25
                   :horizon :3m}])
        [absolute-view outperform-view underperform-view]
        (get-in request [:return-model :views])]
    (is (= {:id "view-abs"
            :kind :absolute
            :instrument-id "perp:HYPE"
            :return 0.45
            :confidence 0.75
            :weights {"perp:HYPE" 1}}
           (select-keys absolute-view
                        [:id :kind :instrument-id :return :confidence :weights])))
    (is (near? 0.25 (:confidence-variance absolute-view)))
    (is (= {:id "view-rel-out"
            :kind :relative
            :instrument-id "perp:ETH"
            :comparator-instrument-id "perp:SOL"
            :direction :outperform
            :return 0.05
            :confidence 0.5
            :weights {"perp:ETH" 1
                      "perp:SOL" -1}}
           (select-keys outperform-view
                        [:id :kind :instrument-id :comparator-instrument-id :direction
                         :return :confidence :weights])))
    (is (near? 0.5 (:confidence-variance outperform-view)))
    (is (= {:id "view-rel-under"
            :kind :relative
            :instrument-id "perp:ETH"
            :comparator-instrument-id "perp:SOL"
            :direction :underperform
            :return 0.03
            :confidence 0.25
            :weights {"perp:ETH" -1
                      "perp:SOL" 1}}
           (select-keys underperform-view
                        [:id :kind :instrument-id :comparator-instrument-id :direction
                         :return :confidence :weights])))
    (is (near? 0.75 (:confidence-variance underperform-view)))
    (is (= [] (:warnings request)))))

(deftest build-engine-request-drops-malformed-legacy-black-litterman-views-with-warning-test
  (let [request (black-litterman-request
                 [{:id "view-good"
                   :kind :absolute
                   :instrument-id "perp:HYPE"
                   :return 0.2
                   :confidence 0.75}
                  {:id "legacy-bad"
                   :kind :relative
                   :long-instrument-id "perp:ETH"
                   :short-instrument-id "perp:ETH"
                   :return 0.04
                   :confidence 0.8}])
        warnings (filterv #(= :invalid-black-litterman-view (:code %))
                          (:warnings request))]
    (is (= ["view-good"]
           (mapv :id (get-in request [:return-model :views]))))
    (is (= [{:code :invalid-black-litterman-view
             :view-id "legacy-bad"}]
           (mapv #(select-keys % [:code :view-id]) warnings)))))
