(ns hyperopen.portfolio.optimizer.application.request-builder-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.domain.trading.core :as trading-core]
            [hyperopen.portfolio.optimizer.defaults :as defaults]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

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
    (is (near? (* 100 (:taker trading-core/default-fees))
               (get-in request [:execution-assumptions :default-fee-bps])))
    (is (= [] (:warnings request)))))

(deftest fee-bps-for-mode-resolves-from-canonical-schedule-test
  (is (near? (* 100 (:taker trading-core/default-fees))
             (request-builder/fee-bps-for-mode :taker)))
  (is (near? (* 100 (:maker trading-core/default-fees))
             (request-builder/fee-bps-for-mode :maker)))
  (is (near? (request-builder/fee-bps-for-mode :taker)
             (request-builder/fee-bps-for-mode nil)))
  (is (pos? (request-builder/fee-bps-for-mode :taker))))

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

(deftest build-engine-request-carries-derived-one-year-vault-history-test
  (let [prior-all-time-start (day-start-ms "2024-04-30")
        derived-start (day-start-ms "2025-04-30")
        derived-mid (day-start-ms "2025-10-30")
        direct-month-start (day-start-ms "2026-02-28")
        direct-month-mid (day-start-ms "2026-03-31")
        end (day-start-ms "2026-04-30")
        vault-address "0x1111111111111111111111111111111111111111"
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
                                 {"BTC" [{:time derived-start :close "200"}
                                         {:time derived-mid :close "220"}
                                         {:time direct-month-start :close "230"}
                                         {:time direct-month-mid :close "225"}
                                         {:time end :close "242"}]}
                                 :funding-history-by-coin {}
                                 :vault-details-by-address
                                 {vault-address
                                  {:portfolio
                                   {:all-time (summary-from-points
                                               [[prior-all-time-start 80 -20]
                                                [derived-start 100 0]
                                                [derived-mid 110 10]
                                                [end 121 21]])
                                    :month (summary-from-points
                                            [[direct-month-start 100 0]
                                             [direct-month-mid 95 -5]
                                             [end 90 -10]])}}}}
                  :market-cap-by-coin {}
                  :as-of-ms (+ end day-ms)})]
    (is (= [derived-start derived-mid end]
           (get-in request [:history :calendar])))
    (is (= ["perp:BTC" vault-instrument-id]
           (mapv :instrument-id (get-in request [:history :eligible-instruments]))))
    (is (near? 0.1 (get-in request [:history :return-series-by-instrument vault-instrument-id 0])))
    (is (near? 0.1 (get-in request [:history :return-series-by-instrument vault-instrument-id 1])))
    (is (= :not-applicable
           (get-in request [:history :funding-by-instrument vault-instrument-id :source])))
    (is (= [] (:warnings request)))))

(deftest build-engine-request-normalizes-setup-constraint-keys-test
  (let [draft (assoc (defaults/default-draft)
                     :id "draft-constraints"
                     :universe [{:instrument-id "perp:BTC"
                                 :market-type :perp
                                 :coin "BTC"}]
                     :constraints {:long-only? false
                                   :gross-min 0.9
                                   :gross-max 1.3
                                   :net-min -0.2
                                   :net-max 0.8
                                   :net-band-pct 0.05
                                   :max-asset-weight 0.6
                                   :allowlist ["perp:BTC"]
                                   :blocklist ["spot:PURR"]
                                   :max-long-weight 0.7
                                   :max-short-weight 0.3
                                   :asset-overrides {"perp:BTC" {:max-weight 0.5
                                                                 :max-long-weight 0.45
                                                                 :max-short-weight 0.2
                                                                 :shortable? true}}
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
    (is (= 0.9 (:gross-floor constraints)))
    (is (= {:min -0.2 :max 0.8} (:net-exposure constraints)))
    (is (= 0.05 (:net-band-pct constraints))
        "the percentage net band rides through to the engine constraints")
    (is (= ["perp:BTC"] (:allowlist constraints)))
    (is (= ["spot:PURR"] (:blocklist constraints)))
    (is (= 0.7 (:max-long-weight constraints)))
    (is (= 0.3 (:max-short-weight constraints)))
    (is (= {"perp:BTC" {:max-weight 0.5
                        :max-long-weight 0.45
                        :max-short-weight 0.2
                        :shortable? true}}
           (:per-asset-overrides constraints)))
    (is (= ["perp:BTC"] (:held-position-locks constraints)))
    (is (= {"perp:BTC" {:max-weight 0.4}}
           (:per-perp-leverage-caps constraints)))
    (is (= 0.25 (:max-turnover constraints)))
    (is (not (contains? constraints :gross-max)))
    (is (not (contains? constraints :gross-min)))
    (is (not (contains? constraints :net-min)))
    (is (not (contains? constraints :asset-overrides)))))

(deftest build-engine-request-preserves-disabled-turnover-cap-test
  (let [draft (-> (defaults/default-draft)
                  (assoc :id "draft-no-turnover-cap"
                         :universe [{:instrument-id "perp:BTC"
                                     :market-type :perp
                                     :coin "BTC"}])
                  (assoc-in [:constraints :max-turnover] nil))
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
    (is (contains? constraints :max-turnover))
    (is (nil? (:max-turnover constraints)))))

(deftest build-engine-request-folds-in-conservative-history-assumptions-test
  (let [draft (-> (defaults/default-draft)
                  (assoc :id "draft-history-assumptions"
                         :universe [{:instrument-id "perp:BTC"
                                     :market-type :perp
                                     :coin "BTC"}
                                    {:instrument-id "perp:NEW"
                                     :market-type :perp
                                     :coin "NEW"}
                                    {:instrument-id "perp:PXY"
                                     :market-type :perp
                                     :coin "PXY"}]
                         :history-assumptions
                         {"perp:NEW" {:behavior :conservative
                                      :expected-return nil
                                      :volatility 0.9
                                      :max-weight 0.03
                                      :correlation-floor 0.75}})
                  (assoc-in [:constraints :asset-overrides]
                            {"perp:NEW" {:max-weight 0.5}}))
        request (request-builder/build-engine-request
                 {:draft draft
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms 2500})
        universe-ids (set (map :instrument-id (:universe request)))
        constraints (:constraints request)]
    (is (contains? universe-ids "perp:NEW")
        "A complete conservative assumption re-admits the no-history asset to the engine universe.")
    (is (= 0.03 (get-in constraints [:per-asset-overrides "perp:NEW" :max-weight]))
        "The conservative cap min-merges into the existing per-asset override (0.5 vs 0.03 -> 0.03).")
    (is (= 0.9 (get-in request [:history-assumptions "perp:NEW" :volatility])))
    (is (= 0.75 (get-in request [:history-assumptions "perp:NEW" :correlation-floor])))))

(def ^:private proxy-day-ms
  (* 24 60 60 1000))

(defn- proxy-daily-candles
  [start-day count* base]
  (mapv (fn [idx]
          {:time (* (+ start-day idx) proxy-day-ms)
           :close (str (+ base idx))})
        (range count*)))

(defn- proxy-draft
  [tokenx-assumption]
  (-> (defaults/default-draft)
      (assoc :id "draft-proxy-assumptions"
             :universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                        {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}
                        {:instrument-id "perp:TOKENX" :market-type :perp :coin "TOKENX"}]
             :history-assumptions {"perp:TOKENX" tokenx-assumption})))

(def ^:private complete-proxy-assumption
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC" "perp:ETH"]
           :relationship-strength :high
           :prior-weights nil}})

(defn- proxy-request
  [tokenx-assumption]
  (request-builder/build-engine-request
   {:draft (proxy-draft tokenx-assumption)
    :history-data {:candle-history-by-coin
                   {"BTC" (proxy-daily-candles 0 400 100)
                    "ETH" (proxy-daily-candles 0 400 2000)
                    "TOKENX" (proxy-daily-candles 390 10 10)}
                   :funding-history-by-coin {}}
    :market-cap-by-coin {}
    :as-of-ms (* 401 proxy-day-ms)}))

(deftest build-engine-request-engine-backs-complete-proxy-assumptions-test
  (let [request (proxy-request complete-proxy-assumption)
        entry (get-in request [:history-assumptions "perp:TOKENX"])]
    (is (= ["perp:BTC" "perp:ETH" "perp:TOKENX"]
           (mapv :instrument-id (:universe request)))
        "The complete proxy asset is re-admitted to the engine universe.")
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (get-in request [:history :eligible-instruments])))
        "The proxy asset is excluded from alignment so it cannot shrink the shared window.")
    (is (= 399 (count (get-in request [:history :return-calendar])))
        "The long-history assets keep their full estimation window.")
    (is (= :proxy (:behavior entry)))
    (is (= ["perp:BTC" "perp:ETH"] (:proxy-instrument-ids entry)))
    (is (= :high (:relationship-strength entry)))
    (is (nil? (:proxy-prior-weights entry)) "nil prior => equal weight downstream.")
    (is (not (contains? entry :proxy)) "The nested draft submap is flattened away.")
    (is (= 9 (get-in entry [:regression-series :observations]))
        "The short-overlap regression series rides on the normalized entry.")
    (is (= 9 (count (get-in entry [:regression-series :proxy-returns-by-id "perp:ETH"]))))
    (is (= 0.05
           (get-in request [:constraints :per-asset-overrides "perp:TOKENX" :max-weight]))
        "The proxy cap mirrors into the constraint machinery.")))

(deftest build-engine-request-proxy-asset-never-truncates-shared-window-test
  (let [with-proxy (proxy-request complete-proxy-assumption)
        without-thin (request-builder/build-engine-request
                      {:draft (-> (defaults/default-draft)
                                  (assoc :id "draft-no-thin-asset"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]))
                       :history-data {:candle-history-by-coin
                                      {"BTC" (proxy-daily-candles 0 400 100)
                                       "ETH" (proxy-daily-candles 0 400 2000)}
                                      :funding-history-by-coin {}}
                       :market-cap-by-coin {}
                       :as-of-ms (* 401 proxy-day-ms)})]
    (is (= (get-in without-thin [:history :return-calendar])
           (get-in with-proxy [:history :return-calendar]))
        "The shared return calendar with the proxy-configured thin asset equals the calendar without the asset entirely.")
    (is (= 399 (get-in with-proxy [:history :history-window :return-observations]))
        "The card's Covariance window field reads this exact count.")))

(defn- api-v2-points
  [start-day count* base]
  (mapv (fn [idx]
          (let [n (+ start-day idx)]
            {:time_ms (* n proxy-day-ms)
             :close (+ base n)
             :return (when (pos? idx) 0.001)}))
        (range count*)))

(defn- api-v2-series
  [id points]
  {:instrument_id id
   :lineage_kind "native"
   :series_kind "market_price"
   :points points
   :funding {:status "available" :annualized_carry 0}
   :warnings []})

(deftest build-engine-request-api-v2-poisoned-calendar-recovers-full-window-test
  (let [decorate (fn [instrument]
                   (assoc instrument :optimizer-history/instrument-id
                          (str "hl:" (:instrument-id instrument))))
        fetch-universe (mapv decorate (:universe (proxy-draft complete-proxy-assumption)))
        return-days (mapv #(* % proxy-day-ms) (range 391 400))
        normalized (api-v2/normalize-history-bundle
                    {:universe fetch-universe}
                    {:contract_version "optimizer-history-api-v2"
                     :request_id "rid-poisoned"
                     :dataset_version "dv-poisoned"
                     :status "ok"
                     :common_calendar (mapv #(* % proxy-day-ms) (range 390 400))
                     :return_calendar return-days
                     :aligned_returns_by_instrument
                     {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                     :returns (vec (repeat 9 0.001))}
                      "hl:perp:ETH" {:instrument_id "hl:perp:ETH"
                                     :returns (vec (repeat 9 0.001))}
                      "hl:perp:TOKENX" {:instrument_id "hl:perp:TOKENX"
                                        :returns (vec (repeat 9 0.002))}}
                     :series_by_instrument
                     {"hl:perp:BTC" (api-v2-series "hl:perp:BTC"
                                                   (api-v2-points 0 400 100))
                      "hl:perp:ETH" (api-v2-series "hl:perp:ETH"
                                                   (api-v2-points 0 400 2000))
                      "hl:perp:TOKENX" (api-v2-series "hl:perp:TOKENX"
                                                      (api-v2-points 390 10 10))}
                     :warnings []})
        request (request-builder/build-engine-request
                 {:draft (proxy-draft complete-proxy-assumption)
                  :history-data {:api-v2-history normalized
                                 :candle-history-by-coin {}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms (* 401 proxy-day-ms)})]
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (get-in request [:history :eligible-instruments])))
        "The thin proxy asset stays excluded from alignment.")
    (is (= 399 (get-in request [:history :history-window :return-observations]))
        "The poisoned backend calendar (9 obs) is re-intersected client-side over the members' own series.")
    (is (= 9 (get-in request [:history-assumptions "perp:TOKENX"
                              :regression-series :observations]))
        "The regression sub-alignment (no superset) keeps the backend overlap.")))

(deftest build-engine-request-keeps-tighter-existing-proxy-cap-test
  (let [draft (-> (proxy-draft complete-proxy-assumption)
                  (assoc-in [:constraints :asset-overrides]
                            {"perp:TOKENX" {:max-weight 0.03}}))
        request (request-builder/build-engine-request
                 {:draft draft
                  :history-data {:candle-history-by-coin
                                 {"BTC" (proxy-daily-candles 0 400 100)
                                  "ETH" (proxy-daily-candles 0 400 2000)
                                  "TOKENX" (proxy-daily-candles 390 10 10)}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms (* 401 proxy-day-ms)})]
    (is (= 0.03
           (get-in request [:constraints :per-asset-overrides "perp:TOKENX" :max-weight]))
        "The tighter of the existing override and the proxy cap wins.")))

(deftest build-engine-request-keeps-incomplete-proxy-assumptions-out-of-engine-test
  (let [request (proxy-request (assoc-in complete-proxy-assumption
                                         [:proxy :instrument-ids] []))]
    (is (= ["perp:BTC" "perp:ETH" "perp:TOKENX"]
           (mapv :instrument-id (get-in request [:history :eligible-instruments])))
        "An incomplete proxy asset stays in alignment like any thin asset.")
    (is (= 9 (count (get-in request [:history :return-calendar])))
        "Without an engine-backed assumption the shared window still shrinks - the
        readiness gate is what protects the run.")
    (is (nil? (get-in request [:history-assumptions "perp:TOKENX" :regression-series]))
        "No regression series is computed for an entry that is not engine-backed.")
    (is (= [] (get-in request [:history-assumptions "perp:TOKENX" :proxy-instrument-ids]))
        "The entry still normalizes to the flattened engine shape.")))

(deftest build-engine-request-rejects-assumption-backed-assets-as-proxies-test
  (let [draft (-> (proxy-draft (assoc-in complete-proxy-assumption
                                         [:proxy :instrument-ids] ["perp:ETH"]))
                  (update :history-assumptions assoc
                          "perp:ETH" {:behavior :conservative
                                      :expected-return nil
                                      :volatility 0.9
                                      :max-weight 0.03
                                      :correlation-floor 0.75}))
        request (request-builder/build-engine-request
                 {:draft draft
                  :history-data {:candle-history-by-coin
                                 {"BTC" (proxy-daily-candles 0 400 100)
                                  "ETH" (proxy-daily-candles 0 400 2000)
                                  "TOKENX" (proxy-daily-candles 390 10 10)}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms (* 401 proxy-day-ms)})
        universe-ids (set (map :instrument-id (:universe request)))]
    (is (contains? universe-ids "perp:ETH")
        "The conservative asset is engine-backed as usual.")
    (is (not (contains? universe-ids "perp:TOKENX"))
        "The proxy asset is not engine-backed while it points at an assumption-backed proxy.")))

(deftest build-engine-request-aligns-reference-only-proxy-but-excludes-from-allocation-test
  (let [draft (-> (defaults/default-draft)
                  (assoc :id "draft-reference-proxy"
                         :universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                                    {:instrument-id "perp:TOKENX" :market-type :perp :coin "TOKENX"}]
                         :history-assumptions
                         {"perp:TOKENX" (assoc-in complete-proxy-assumption
                                                  [:proxy :instrument-ids] ["perp:SOL"])}
                         :proxy-reference-instruments
                         [{:instrument-id "perp:SOL" :market-type :perp :coin "SOL"}]))
        request (request-builder/build-engine-request
                 {:draft draft
                  :history-data {:candle-history-by-coin
                                 {"BTC" (proxy-daily-candles 0 400 100)
                                  "SOL" (proxy-daily-candles 0 400 150)
                                  "TOKENX" (proxy-daily-candles 390 10 10)}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms (* 401 proxy-day-ms)})
        engine-ids (set (map :instrument-id (:universe request)))
        eligible-ids (set (map :instrument-id (get-in request [:history :eligible-instruments])))
        entry (get-in request [:history-assumptions "perp:TOKENX"])]
    (is (contains? eligible-ids "perp:SOL")
        "The reference-only proxy is aligned, so its covariance is available.")
    (is (contains? engine-ids "perp:TOKENX")
        "The thin asset is re-admitted to the allocatable universe.")
    (is (not (contains? engine-ids "perp:SOL"))
        "But the reference-only proxy is NOT allocatable.")
    (is (= ["perp:SOL"] (:proxy-instrument-ids entry)))
    (is (= 9 (get-in entry [:regression-series :observations]))
        "The overlap regression series resolves the out-of-universe proxy's returns.")
    (is (= 9 (count (get-in entry [:regression-series :proxy-returns-by-id "perp:SOL"]))))))

(deftest build-engine-request-keeps-universe-member-listed-as-reference-allocatable-test
  (let [draft (-> (defaults/default-draft)
                  (assoc :id "draft-stale-reference"
                         :universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                                    {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}
                                    {:instrument-id "perp:TOKENX" :market-type :perp :coin "TOKENX"}]
                         :history-assumptions
                         {"perp:TOKENX" (assoc-in complete-proxy-assumption
                                                  [:proxy :instrument-ids] ["perp:SOL"])}
                         :proxy-reference-instruments
                         [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                          {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}
                          {:instrument-id "perp:SOL" :market-type :perp :coin "SOL"}]))
        request (request-builder/build-engine-request
                 {:draft draft
                  :history-data {:candle-history-by-coin
                                 {"BTC" (proxy-daily-candles 0 400 100)
                                  "ETH" (proxy-daily-candles 0 400 2000)
                                  "SOL" (proxy-daily-candles 0 400 150)
                                  "TOKENX" (proxy-daily-candles 390 10 10)}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms (* 401 proxy-day-ms)})
        engine-ids (mapv :instrument-id (:universe request))
        eligible-ids (mapv :instrument-id (get-in request [:history :eligible-instruments]))]
    (is (= ["perp:BTC" "perp:ETH" "perp:TOKENX"] engine-ids)
        "Universe members listed as stale references stay allocatable exactly once; the true reference stays out.")
    (is (= ["perp:BTC" "perp:ETH" "perp:SOL"] eligible-ids)
        "Alignment covers each member once plus the genuine reference-only proxy.")))

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
    (is (= false (:include-spot? constraints)))
    (is (= 2.0 (:gross-leverage constraints)))
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

(deftest build-engine-request-does-not-use-selected-cache-for-outside-current-history-test
  (let [request (request-builder/build-engine-request
                 {:draft {:id "draft-outside-current-cache"
                          :universe [{:instrument-id "perp:BTC"
                                      :market-type :perp
                                      :coin "BTC"}
                                     {:instrument-id "perp:ETH"
                                      :market-type :perp
                                      :coin "ETH"}]
                          :objective {:kind :minimum-variance}
                          :return-model {:kind :historical-mean}
                          :risk-model {:kind :diagonal-shrink}
                          :constraints {:long-only? true}}
                  :current-portfolio {:capital {:nav-usdc 10000}
                                      :by-instrument {"perp:HYPE"
                                                      {:instrument-id "perp:HYPE"
                                                       :market-type :perp
                                                       :coin "HYPE"
                                                       :weight 0.25}}}
                  :history-data {:candle-history-by-coin
                                 {"BTC" [{:time 1000 :close "100"}
                                         {:time 2000 :close "110"}]
                                  "ETH" [{:time 1000 :close "200"}
                                         {:time 2000 :close "220"}]
                                  "HYPE" [{:time 1000 :close "10"}
                                          {:time 2000 :close "12"}]}
                                 :funding-history-by-coin {}}
                  :market-cap-by-coin {}
                  :as-of-ms 2500})]
    (is (= ["perp:HYPE"]
           (mapv :instrument-id (:current-portfolio-universe request))))
    (is (nil? (:current-portfolio-history request))
        "Outside-universe current history must come from the separate current bundle, not stale selected-cache data.")))

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

(deftest build-engine-request-drops-black-litterman-views-outside-eligible-universe-test
  (let [request (black-litterman-request
                 [{:id "stale-btc"
                   :kind :absolute
                   :instrument-id "BTC"
                   :return 0.2
                   :confidence 0.75}
                  {:id "valid-hype"
                   :kind :absolute
                   :instrument-id "perp:HYPE"
                   :return 0.35
                   :confidence 0.75}])
        warnings (filterv #(= :black-litterman-view-outside-universe (:code %))
                          (:warnings request))]
    (is (= ["valid-hype"]
           (mapv :id (get-in request [:return-model :views]))))
    (is (= [{:code :black-litterman-view-outside-universe
             :view-id "stale-btc"
             :instrument-ids ["BTC"]}]
           (mapv #(select-keys % [:code :view-id :instrument-ids]) warnings)))))
