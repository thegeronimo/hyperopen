(ns hyperopen.portfolio.optimizer.application.view-model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions.common :as action-common]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.view-model :as view-model]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(def ^:private btc-instrument
  {:instrument-id "perp:BTC"
   :market-type :perp
   :coin "BTC"
   :symbol "BTC-USDC"
   :name "Bitcoin"})

(def ^:private eth-instrument
  {:instrument-id "perp:ETH"
   :market-type :perp
   :coin "ETH"
   :symbol "ETH-USDC"
   :name "Ethereum"})

(def ^:private vault-address
  "0x1111111111111111111111111111111111111111")

(def ^:private vault-instrument
  {:instrument-id (str "vault:" vault-address)
   :market-type :vault
   :coin (str "vault:" vault-address)
   :vault-address vault-address
   :name "Alpha Vault"})

(defn- candle-rows
  [time-and-close-pairs]
  (mapv (fn [[time-ms close]]
          {:time time-ms
           :close (str close)})
        time-and-close-pairs))

(defn- ready-workspace-state
  []
  {:router {:path "/portfolio/optimize/new"}
   :portfolio {:optimizer
               {:draft {:id "draft-current"
                        :name "Current Draft"
                        :universe [btc-instrument eth-instrument]
                        :objective {:kind :max-sharpe}
                        :return-model {:kind :historical-mean}
                        :risk-model {:kind :sample-covariance}
                        :constraints {:long-only? true
                                      :max-asset-weight 1.0}
                        :metadata {:dirty? false}}
                :history-data {:candle-history-by-coin
                               {"BTC" (candle-rows [[1000 100]
                                                    [2000 110]
                                                    [3000 108]
                                                    [4000 116]])
                                "ETH" (candle-rows [[1000 50]
                                                    [2000 54]
                                                    [3000 49]
                                                    [4000 55]])}
                               :funding-history-by-coin {}}
                :market-cap-by-coin {}
                :runtime {:as-of-ms 5000
                          :stale-after-ms 60000}
                :run-state {:status :succeeded
                            :run-id "run-1"
                            :completed-at-ms 5100}}}
   :webdata2 {:clearinghouseState
              {:marginSummary {:accountValue "1000"}
               :assetPositions []}}})

(defn- request-signature-for-state
  [state]
  (let [{:keys [request runnable?]} (setup-readiness/build-readiness state)]
    (is runnable?)
    (action-common/build-request-signature request)))

(defn- solved-run-for-state
  [state]
  (fixtures/sample-last-successful-run
   {:computed-at-ms 5100
    :request-signature (request-signature-for-state state)
    :result {:status :solved
             :instrument-ids ["perp:BTC" "perp:ETH"]
             :target-weights [0.6 0.4]
             :current-weights [0.5 0.5]
             :target-weights-by-instrument {"perp:BTC" 0.6
                                            "perp:ETH" 0.4}
             :current-weights-by-instrument {"perp:BTC" 0.5
                                             "perp:ETH" 0.5}
             :expected-return 0.1
             :volatility 0.2
             :performance {:shrunk-sharpe 0.5}
             :diagnostics {:turnover 0.1}
             :rebalance-preview {:status :ready
                                 :capital-usd 1000
                                 :summary {:ready-count 2
                                           :blocked-count 0}
                                 :rows []}}}))

(deftest workspace-model-projects-setup-state-test
  (let [state (ready-workspace-state)
        solved-run (solved-run-for-state state)
        model (view-model/workspace-model
               (-> state
                   (assoc-in [:portfolio :optimizer :active-scenario :loaded-id]
                             "scn_current")
                   (assoc-in [:portfolio :optimizer :run-state :request-signature]
                             (:request-signature solved-run))
                   (assoc-in [:portfolio :optimizer :last-successful-run]
                             solved-run)
                   (assoc-in [:portfolio :optimizer :scenario-save-state :status]
                             :saving)
                   (assoc-in [:portfolio-ui :optimizer :black-litterman-editor]
                             {:selected-kind :absolute}))
               {:scenario-id "new"})]
    (is (= "Current Draft" (get-in model [:draft :name])))
    (is (= :succeeded (get-in model [:run-state :status])))
    (is (= :idle (get-in model [:optimization-progress :status])))
    (is (= :saving (get-in model [:scenario-save-state :status])))
    (is (= :idle (get-in model [:history-load-state :status])))
    (is (= {:selected-kind :absolute} (:editor-state model)))
    (is (= "/portfolio/optimize/draft" (:result-path model)))
    (is (false? (:running? model)))
    (is (true? (:run-triggerable? model)))
    (is (true? (:saving-scenario? model)))
    (is (true? (:current-result? model)))
    (is (map? (:readiness model)))
    (is (map? (:snapshot model)))
    (is (map? (:preview-snapshot model)))))

(deftest scenario-detail-model-scopes-mismatched-route-state-test
  (let [model (view-model/scenario-detail-model
               {:portfolio-ui {:optimizer {:results-tab :tracking}}
                :portfolio {:optimizer
                            {:active-scenario {:loaded-id "scn_old"
                                               :name "Old Scenario"
                                               :status :computed}
                             :draft {:id "scn_old"
                                     :name "Old Scenario"
                                     :universe [btc-instrument]}
                             :last-successful-run (fixtures/sample-last-successful-run
                                                   {:result {:status :solved}})
                             :scenario-load-state {:status :loading
                                                   :scenario-id "scn_new"}}}}
               {:scenario-id "scn_new"})]
    (is (= "scn_new" (:scenario-id model)))
    (is (= :tracking (:selected-tab model)))
    (is (true? (:loading? model)))
    (is (= "Scenario scn_new" (:scenario-name model)))
    (is (= [] (get-in model [:state :portfolio :optimizer :draft :universe])))
    (is (nil? (get-in model [:state :portfolio :optimizer :last-successful-run])))
    (is (= {:loaded-id nil
            :status :loading
            :read-only? true}
           (get-in model [:state :portfolio :optimizer :active-scenario])))
    (is (false? (:current-result? model)))
    (is (false? (:stale? model)))))

(deftest scenario-detail-model-retains-unsaved-draft-route-state-test
  (let [retained-run (fixtures/sample-last-successful-run
                      {:result {:status :solved
                                :instrument-ids ["perp:BTC"]
                                :target-weights [1.0]
                                :current-weights [0.25]}})
        state {:portfolio-ui {:optimizer {:results-tab :recommendation}}
               :portfolio {:optimizer
                           {:active-scenario {:loaded-id nil
                                              :status :computed}
                            :draft {:id "draft-current"
                                    :name "Retained Draft"
                                    :universe [btc-instrument]}
                            :last-successful-run retained-run
                            :scenario-load-state {:status :loading
                                                  :scenario-id "draft"}}}}
        model (view-model/scenario-detail-model state {:scenario-id "draft"})
        computed-model (view-model/scenario-detail-model
                        (assoc-in state
                                  [:portfolio :optimizer :active-scenario :loaded-id]
                                  "draft-current")
                        {:scenario-id "draft"})
        unnamed-model (view-model/scenario-detail-model
                       (assoc-in state [:portfolio :optimizer :draft :name] nil)
                       {:scenario-id "draft"})]
    (is (= "draft" (:scenario-id model)))
    (is (false? (:loading? model)))
    (is (= "Retained Draft" (:scenario-name model)))
    (is (= retained-run (:last-successful-run model)))
    (is (= {:loaded-id nil
            :status :computed}
           (:active-scenario model)))
    (is (= [btc-instrument] (get-in model [:state :portfolio :optimizer :draft :universe])))
    (is (false? (:loading? computed-model)))
    (is (= retained-run (:last-successful-run computed-model)))
    (is (= [btc-instrument]
           (get-in computed-model [:state :portfolio :optimizer :draft :universe])))
    (is (= "Unsaved Optimization" (:scenario-name unnamed-model)))))

(deftest universe-section-model-projects-search-candidates-test
  (let [calls (atom [])
        candidate {:key "perp:ETH"
                   :market-type :perp
                   :coin "ETH"
                   :symbol "ETH-USDC"
                   :name "Ethereum"
                   :volume24h 125000000
                   :liquidity "deep"}
        candidate-markets-stub (fn
                                 ([_state _universe query]
                                  (swap! calls conj query)
                                  [candidate])
                                 ([_state _universe query _opts]
                                  (swap! calls conj query)
                                  [candidate]))
        blank-model (with-redefs [universe-candidates/candidate-markets
                                  candidate-markets-stub]
                      (view-model/universe-section-model
                       {:portfolio-ui {:optimizer {:universe-search-query "   "}}}
                       {:universe [btc-instrument]}
                       {}))
        search-model (with-redefs [universe-candidates/candidate-markets
                                   candidate-markets-stub]
                       (view-model/universe-section-model
                        {:portfolio-ui {:optimizer {:universe-search-query "eth"
                                                    :universe-search-active-index 3}}}
                        {:universe [btc-instrument]}
                        {}))]
    (is (= ["eth"] @calls))
    (is (= [btc-instrument] (:universe blank-model)))
    (is (= "   " (:search-query blank-model)))
    (is (false? (:searching? blank-model)))
    (is (= [] (:markets blank-model)))
    (is (= [] (:market-keys blank-model)))
    (is (= "eth" (:search-query search-model)))
    (is (true? (:searching? search-model)))
    (is (= [candidate] (:markets search-model)))
    (is (= 0 (:active-index search-model)))
    (is (= ["perp:ETH"] (:market-keys search-model)))))

(deftest selected-history-label-projects-loading-and-readiness-state-test
  (testing "prefetch and load states win before readiness labels"
    (is (= "queued"
           (view-model/selected-history-label
            {:portfolio {:optimizer {:history-prefetch
                                      {:by-instrument-id
                                       {"perp:BTC" {:status :queued}}}}}}
            {}
            {}
            {}
            btc-instrument)))
    (is (= "loading"
           (view-model/selected-history-label
            {}
            {}
            {:status :loading
             :request-signature {:universe [btc-instrument]}}
            {}
            btc-instrument))))
  (testing "readiness and validated history produce stable labels"
    (is (= "sufficient"
           (view-model/selected-history-label
            {}
            {:request {:universe [btc-instrument]}}
            {}
            {}
            btc-instrument)))
    (is (= "missing"
           (view-model/selected-history-label
            {}
            {}
            {:status :succeeded
             :request-signature {:universe [btc-instrument]}}
            {"perp:BTC" :missing}
            btc-instrument)))
    (is (= "insufficient"
           (view-model/selected-history-label
            {:portfolio {:optimizer {:history-data
                                      {:candle-history-by-coin
                                       {"BTC" (candle-rows [[1000 100]])}}}}}
            {}
            {:status :succeeded
             :request-signature {:universe [btc-instrument]}}
            {"perp:BTC" :insufficient}
            btc-instrument)))
    (is (= "stale" (view-model/selected-history-label {} {:request {:universe [btc-instrument]}} {:status :succeeded :request-signature {:universe [btc-instrument]}} {"perp:BTC" :stale} btc-instrument)))
    (is (= "rejected" (view-model/selected-history-label {} {:blocking-warnings [{:code :validation-failed :instrument-id "perp:BTC"}]} {:status :succeeded :request-signature {:universe [btc-instrument]}} {"perp:BTC" :rejected} btc-instrument)))))

(deftest selected-history-label-projects-api-v2-return-history-warning-state-test
  (is (= "missing"
         (view-model/selected-history-label
          {}
          {:blocking-warnings [{:code :missing-return-history
                                :instrument-id "perp:BTC"}]}
          {:status :succeeded
           :request-signature {:universe [btc-instrument]}}
          {}
          btc-instrument)))
  (is (= "insufficient"
         (view-model/selected-history-label
          {}
          {:blocking-warnings [{:code :insufficient-return-history
                                :instrument-id "perp:BTC"}]}
          {:status :succeeded
           :request-signature {:universe [btc-instrument]}}
          {}
          btc-instrument))))

(deftest selected-history-label-projects-vault-gap-and-cache-state-test
  (testing "vault shared gaps are surfaced before generic readiness labels"
    (is (= "shared gap"
           (view-model/selected-history-label
            {}
            {:request {:universe [vault-instrument]}}
            {:status :succeeded
             :request-signature {:universe [vault-instrument]}}
            {(:instrument-id vault-instrument) :loaded-but-misaligned}
            vault-instrument))))
  (testing "cached vault history allows an insufficient label without a fresh load result"
    (is (= "insufficient"
           (view-model/selected-history-label
            {:portfolio {:optimizer {:history-data
                                      {:vault-details-by-address
                                       {vault-address {:portfolio
                                                       {:month
                                                        {:accountValueHistory
                                                         [[1000 100]
                                                          [2000 101]]}}}}}}}}
            {}
            {:status :idle}
            {(:instrument-id vault-instrument) :insufficient}
            vault-instrument)))))

(deftest tracking-model-projects-latest-snapshot-labels-and-manual-state-test
  (let [vault-id (str "vault:" vault-address)
        tracked-state {:portfolio
                       {:optimizer
                        {:active-scenario {:loaded-id "scn_track"
                                           :status :executed}
                         :draft {:id "draft-track"}
                         :last-successful-run
                         {:result {:labels-by-instrument {vault-id "Alpha Vault"}}}
                         :tracking {:scenario-id "scn_track"
                                    :updated-at-ms 2000
                                    :snapshots
                                    [{:scenario-id "scn_track"
                                      :as-of-ms 1000
                                      :status :tracked
                                      :rows [{:instrument-id "perp:BTC"}]}
                                     {:scenario-id "scn_track"
                                      :as-of-ms 2000
                                      :status :tracked
                                      :rows [{:instrument-id vault-id
                                              :weight-drift 0.05}]}]}}}}
        tracked-model (view-model/tracking-model tracked-state)
        manual-model (view-model/tracking-model
                      {:portfolio {:optimizer {:active-scenario {:loaded-id "scn_saved"
                                                                 :status :saved}
                                               :tracking {:snapshots []}}}})
        unsaved-model (view-model/tracking-model
                       {:portfolio {:optimizer {:active-scenario {:loaded-id nil
                                                                  :status :computed}
                                                :draft {:id nil}
                                                :tracking {:snapshots []}}}})]
    (is (= :executed (:scenario-status tracked-model)))
    (is (true? (:trackable? tracked-model)))
    (is (false? (:manual-tracking? tracked-model)))
    (is (= "scn_track" (:active-scenario-id tracked-model)))
    (is (= "Alpha Vault" (get-in tracked-model [:latest-snapshot :rows 0 :instrument-label])))
    (is (= "Alpha Vault" (get-in tracked-model [:latest-rows 0 :instrument-label])))
    (is (= 2 (count (:snapshots tracked-model))))
    (is (true? (:manual-tracking? manual-model)))
    (is (true? (:manual-tracking-enableable? manual-model)))
    (is (true? (:manual-tracking? unsaved-model)))
    (is (false? (:manual-tracking-enableable? unsaved-model)))))

(deftest execution-modal-model-projects-confirm-state-history-and-labels-test
  (let [vault-id (str "vault:" vault-address)
        state {:portfolio
               {:optimizer
                {:last-successful-run
                 {:result {:labels-by-instrument {vault-id "Alpha Vault"}}}
                 :execution
                 {:history [{:attempt-id "exec_1"
                             :status :failed
                             :rows [{:instrument-id vault-id
                                     :status :failed
                                     :side :sell
                                     :delta-notional-usd -400
                                     :error {:message "exchange unavailable"}}]}]}
                 :execution-modal
                 {:open? true
                  :submitting? false
                  :plan {:status :partially-blocked
                         :summary {:ready-count 1
                                   :blocked-count 1
                                   :gross-ready-notional-usd 1000}
                         :rows [{:instrument-id vault-id
                                 :status :ready
                                 :side :buy
                                 :delta-notional-usd 1000}
                                {:instrument-id "spot:PURR"
                                 :status :blocked
                                 :reason :spot-submit-unsupported}]}}}}}
        model (view-model/execution-modal-model state)
        submitting-model (view-model/execution-modal-model
                          (assoc-in state
                                    [:portfolio :optimizer :execution-modal :submitting?]
                                    true))
        disabled-model (view-model/execution-modal-model
                        (-> state
                            (assoc-in [:portfolio :optimizer :execution-modal :plan :summary :ready-count]
                                      0)
                            (assoc-in [:portfolio :optimizer :execution-modal :plan :execution-disabled?]
                                      true)))]
    (is (true? (:open? model)))
    (is (true? (:ready? model)))
    (is (false? (:confirm-disabled? model)))
    (is (= "Alpha Vault" (get-in model [:plan :rows 0 :instrument-label])))
    (is (= "Alpha Vault" (get-in model [:latest-attempt :rows 0 :instrument-label])))
    (is (= "exchange unavailable" (get-in model [:latest-attempt :rows 0 :error :message])))
    (is (true? (:confirm-disabled? submitting-model)))
    (is (false? (:ready? disabled-model)))
    (is (true? (:confirm-disabled? disabled-model)))
    (is (= "Order submission wiring is not enabled in this slice."
           (:disabled-message disabled-model)))))

(deftest execution-tab-model-resolves-perp-and-spot-display-labels-test
  (let [state {:spot {:meta {:tokens [{:index 189 :name "PURR"}]
                             :universe [{:name "@189" :tokens [189]}]}}
               :portfolio
               {:optimizer
                {:execution {:status :idle :history []}
                 :execution-modal
                 {:open? true
                  :phase :staged
                  :plan {:status :partially-blocked
                         :summary {:ready-count 2 :blocked-count 1}
                         :rows [{:row-id "perp:xyz:xyz:SILVER" :instrument-id "perp:xyz:xyz:SILVER"
                                 :status :ready :side :sell :delta-notional-usd -100}
                                {:row-id "spot:@189" :instrument-id "spot:@189"
                                 :status :ready :side :buy :delta-notional-usd 100}
                                {:row-id "spot:@999" :instrument-id "spot:@999"
                                 :status :blocked :reason :below-min-notional :delta-notional-usd 1}]}}}}}
        rows (:rows (view-model/execution-tab-model state))]
    (is (= "SILVER" (:instrument-label (nth rows 0))))
    (is (= "PURR" (:instrument-label (nth rows 1))))
    (is (= "@999" (:instrument-label (nth rows 2))))))

(deftest execution-tab-model-shows-live-run-attempt-rows-while-submitting-test
  (let [state {:portfolio
               {:optimizer
                {:execution
                 {:status :submitting
                  :run-attempt {:rows [{:row-id "perp:BTC" :instrument-id "perp:BTC"
                                        :status :submitted :side :buy :delta-notional-usd 100}
                                       {:row-id "perp:ETH" :instrument-id "perp:ETH"
                                        :status :working :side :buy :delta-notional-usd 50}]}}
                 :execution-modal
                 {:open? true
                  :submitting? true
                  :phase :armed
                  :plan {:summary {:ready-count 2}
                         :rows [{:row-id "perp:BTC" :instrument-id "perp:BTC"
                                 :status :ready :side :buy :delta-notional-usd 100}
                                {:row-id "perp:ETH" :instrument-id "perp:ETH"
                                 :status :ready :side :buy :delta-notional-usd 50}]}}}}}
        model (view-model/execution-tab-model state)]
    (is (= :running (:phase model)))
    (is (= [:submitted :working] (mapv :status (:rows model))))))

(deftest execution-tab-model-projects-resting-phase-for-orders-on-the-book-test
  (let [state {:portfolio
               {:optimizer
                {:execution
                 {:status :resting
                  :history [{:status :resting
                             :rows [{:row-id "perp:BTC" :instrument-id "perp:BTC"
                                     :status :resting :side :buy :delta-notional-usd 100}
                                    {:row-id "perp:ETH" :instrument-id "perp:ETH"
                                     :status :resting :side :buy :delta-notional-usd 50}]}]}
                 :execution-modal
                 {:open? true
                  :phase :armed
                  :plan {:summary {:ready-count 2}
                         :rows [{:row-id "perp:BTC" :instrument-id "perp:BTC"
                                 :status :ready :side :buy :delta-notional-usd 100}
                                {:row-id "perp:ETH" :instrument-id "perp:ETH"
                                 :status :ready :side :buy :delta-notional-usd 50}]}}}}}
        model (view-model/execution-tab-model state)]
    (is (= :resting (:phase model)))
    (is (true? (:terminal? model)))
    (is (= [:resting :resting] (mapv :status (:rows model))))))

(deftest inputs-audit-model-projects-draft-scenario-and-readable-input-labels-test
  (let [vault-id (str "vault:" vault-address)
        model (view-model/inputs-audit-model
               {:portfolio
                {:optimizer
                 {:active-scenario {:loaded-id "scn_inputs"}
                  :draft {:id "draft_inputs"
                          :universe [btc-instrument
                                     {:instrument-id vault-id
                                      :market-type :vault
                                      :coin vault-id
                                      :vault-address vault-address
                                      :name "Alpha Vault"}]
                          :objective {:kind :target-volatility}
                          :return-model {:kind :black-litterman
                                         :views [{:kind :relative
                                                  :long-instrument-id "perp:BTC"
                                                  :short-instrument-id vault-id
                                                  :direction :outperform
                                                  :return 0.04}
                                                 {:kind :absolute
                                                  :instrument-id vault-id
                                                  :return 0.08}]}
                          :risk-model {:kind :diagonal-shrink}
                          :constraints {:long-only? true}
                          :execution-assumptions {:fee-mode :taker}}}}})]
    (is (= "scn_inputs" (:scenario-id model)))
    (is (= :target-volatility (:objective-kind model)))
    (is (= :black-litterman (:return-model-kind model)))
    (is (= :diagonal-shrink (:risk-model-kind model)))
    (is (= "perp:BTC" (get-in model [:universe-rows 0 :audit-label])))
    (is (= "Alpha Vault" (get-in model [:universe-rows 1 :audit-label])))
    (is (= "BTC" (get-in model [:view-rows 0 :primary-label])))
    (is (= "Alpha Vault" (get-in model [:view-rows 0 :comparator-label])))
    (is (= "Alpha Vault" (get-in model [:view-rows 1 :primary-label])))
    (is (= [{:kind :relative
             :long-instrument-id "perp:BTC"
             :short-instrument-id vault-id
             :direction :outperform
             :return 0.04}
            {:kind :absolute
             :instrument-id vault-id
             :return 0.08}]
           (mapv #(dissoc % :primary-label :comparator-label)
                 (:view-rows model))))))

(deftest universe-panel-model-projects-asset-query-candidates-test
  (let [model (view-model/universe-panel-model
               {:portfolio-ui {:optimizer {:universe-search-query "hype"
                                           :universe-search-active-index 1}}
                :asset-selector
                {:markets [{:key "perp:HYPE"
                            :market-type :perp
                            :coin "HYPE"
                            :symbol "HYPE-USDC"
                            :volume24h 2000}
                           {:key "spot:HYPE"
                            :market-type :spot
                            :coin "HYPE"
                            :symbol "HYPE"
                            :base "HYPE"
                            :quote "USDC"
                            :volume24h 1000}]}}
               {:universe [btc-instrument]})]
    (is (= [btc-instrument] (:universe model)))
    (is (= "hype" (:search-query model)))
    (is (= ["perp:HYPE" "spot:HYPE"] (:market-keys model)))
    (is (= 1 (:active-index model)))))
