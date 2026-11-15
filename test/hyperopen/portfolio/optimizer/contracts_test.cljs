(ns hyperopen.portfolio.optimizer.contracts-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.spec.alpha :as s]
            [hyperopen.portfolio.optimizer.contract-fixtures :as contract-fixtures]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.contracts.migrations :as migrations]
            [hyperopen.portfolio.optimizer.contracts.spec-registry]
            [hyperopen.portfolio.optimizer.fixtures :as optimizer-fixtures]))

(def sample-request
  (contract-fixtures/valid-engine-request))

(def sample-draft
  {:name "Contract Scenario"
   :universe []
   :objective {:kind :minimum-variance}
   :return-model {:kind :historical-mean}
   :risk-model {:kind :diagonal-shrink}
   :constraints {:long-only? false}
   :execution-assumptions {:default-order-type :market}
   :metadata {:dirty? false}})

(defn- draft-with-black-litterman-views
  [views]
  (assoc (contracts/migrate-draft sample-draft)
         :return-model {:kind :black-litterman
                        :views (vec views)}))

(def sample-solved-result
  {:status :solved
   :scenario-id "scn_contract"
   :as-of-ms 5000
   :instrument-ids ["perp:BTC" "perp:ETH"]
   :target-weights [0.6 0.4]
   :current-weights [0.5 0.5]
   :target-weights-by-instrument {"perp:BTC" 0.6
                                  "perp:ETH" 0.4}
   :current-weights-by-instrument {"perp:BTC" 0.5
                                   "perp:ETH" 0.5}
   :expected-returns-by-instrument {"perp:BTC" 0.12
                                    "perp:ETH" 0.08}
   :return-decomposition-by-instrument {"perp:BTC" {:total 0.12}
                                        "perp:ETH" {:total 0.08}}
   :diagnostics {:weight-sensitivity-by-instrument {"perp:BTC" {:max-delta 0.01}}}
   :rebalance-preview {:trades []}})

(defn- allowed?
  [allowed status]
  (contains? allowed status))

(deftest state-paths-and-contract-specs-are-named-test
  (is (= [:portfolio :optimizer]
         contracts/optimizer-path))
  (is (= [:portfolio :optimizer :draft]
         contracts/draft-path))
  (is (= [:portfolio :optimizer :draft :return-model]
         contracts/draft-return-model-path))
  (is (= [:portfolio :optimizer :draft :return-model :views]
         contracts/draft-return-model-views-path))
  (is (= contracts/draft-return-model-path
         (contracts/contract-path :optimizer/draft-return-model)))
  (is (= [:portfolio :optimizer :draft :return-model :views "view-1"]
         (contracts/contract-path :optimizer/draft-return-model-views
                                  "view-1")))
  (is (= [:portfolio :optimizer :scenario-index :by-id "scn_contract"]
         (contracts/contract-path :optimizer/scenario-index
                                  :by-id
                                  "scn_contract")))
  (is (= [:portfolio :optimizer :draft :constraints :max-turnover]
         (contracts/optimizer-state-path :draft :constraints :max-turnover)))
  (is (= [:portfolio-ui :optimizer :results-tab]
         (contracts/optimizer-ui-state-path :results-tab)))
  (is (= [:portfolio :optimizer :draft :metadata :dirty?]
         contracts/draft-dirty-path))
  (is (= [:portfolio :optimizer :run-state]
         contracts/run-state-path))
  (is (= [:portfolio :optimizer :last-successful-run :result]
         contracts/last-successful-run-result-path))
  (is (= [:portfolio :optimizer :refinement]
         contracts/refinement-path))
  (is (= [:portfolio :optimizer :refinement :active?]
         contracts/refinement-active-path))
  (is (= [:portfolio :optimizer :refinement :requested-points]
         contracts/refinement-requested-points-path))
  (is (= [:portfolio :optimizer :refinement :baseline-result]
         contracts/refinement-baseline-result-path))
  (is (= [:portfolio-ui :optimizer :refinement-depth]
         contracts/ui-refinement-depth-path))
  (is (= [:portfolio :optimizer :history-load-state :request-signature]
         contracts/history-load-state-request-signature-path))
  (is (= [:portfolio :optimizer :history-discovery]
         contracts/history-discovery-path))
  (is (= contracts/history-discovery-path
         (contracts/contract-path :optimizer/history-discovery)))
  (is (= [:portfolio :optimizer :execution-modal :error]
         contracts/execution-modal-error-path))
  (is (= [:portfolio :optimizer :tracking]
         contracts/tracking-path))
  (is (= [:portfolio :optimizer :tracking :error]
         contracts/tracking-error-path))
  (is (= [:portfolio-ui :optimizer]
         contracts/optimizer-ui-path))
  (is (= [:portfolio-ui :optimizer :results-tab]
         contracts/ui-results-tab-path))
  (is (= [:portfolio-ui :optimizer :black-litterman-editor]
         contracts/ui-black-litterman-editor-path))
  (is (= [:portfolio-ui :optimizer :frontier-overlay-mode]
         contracts/ui-frontier-overlay-mode-path))
  (is (= ::contracts/draft
         (:optimizer/draft contracts/contract-specs)))
  (is (= ::contracts/request-signature
         (:optimizer/request-signature contracts/contract-specs)))
  (is (= ::contracts/worker-envelope
         (:optimizer/worker-envelope contracts/contract-specs))))

(deftest refinement-frontier-points-excluded-from-input-signature-test
  (testing "a refined run (higher :frontier-points) shares an input-signature with its draft"
    (let [draft-request (assoc sample-request
                              :objective {:kind :max-sharpe})
          refined-request (assoc sample-request
                                :objective {:kind :max-sharpe :frontier-points 72})]
      (is (= (contracts/optimizer-input-signature draft-request)
             (contracts/optimizer-input-signature refined-request)))
      (testing "but a genuine objective change still differs"
        (is (not= (contracts/optimizer-input-signature draft-request)
                  (contracts/optimizer-input-signature
                   (assoc sample-request :objective {:kind :minimum-variance}))))))))

(deftest execution-prices-by-id-excluded-from-input-signature-test
  (testing "native marks seeded into :prices-by-id are preview-only and don't churn the signature"
    (let [base (assoc sample-request
                      :execution-assumptions {:fallback-slippage-bps 25})
          marks-a (assoc-in base [:execution-assumptions :prices-by-id] {"perp:BTC" 95.0})
          marks-b (assoc-in base [:execution-assumptions :prices-by-id] {"perp:BTC" 96.5})]
      (is (= (contracts/optimizer-input-signature base)
             (contracts/optimizer-input-signature marks-a)))
      (is (= (contracts/optimizer-input-signature marks-a)
             (contracts/optimizer-input-signature marks-b))))))

(deftest draft-and-record-migrations-stamp-current-versions-test
  (testing "drafts"
    (let [draft (contracts/migrate-draft sample-draft)]
      (is (= contracts/draft-schema-version
             (:schema-version draft)))
      (is (= draft (contracts/migrate-draft draft)))
      (is (s/valid? ::contracts/draft draft))))
  (testing "saved scenarios"
    (let [record (contracts/migrate-scenario-record
                  {:id "scn_contract"
                   :name "Contract Scenario"
                   :status :saved
                   :config (contracts/migrate-draft sample-draft)
                   :saved-run nil
                   :updated-at-ms 3000})]
      (is (= contracts/scenario-record-schema-version
             (:schema-version record)))
      (is (= record (contracts/migrate-scenario-record record)))
      (is (s/valid? ::contracts/scenario-record record))))
  (testing "tracking records"
    (let [record (contracts/migrate-tracking-record
                  {:scenario-id "scn_contract"
                   :updated-at-ms 4000
                   :snapshots []})]
      (is (= contracts/tracking-record-schema-version
             (:schema-version record)))
      (is (= record (contracts/migrate-tracking-record record)))
      (is (s/valid? ::contracts/tracking-record record)))))

(deftest migrate-draft-canonicalizes-doubled-dex-ids-test
  (let [draft (assoc sample-draft
                     :universe [{:instrument-id "perp:xyz:ORCL"
                                 :market-type :perp
                                 :coin "xyz:ORCL"}
                                {:instrument-id "perp:xyz:xyz:ORCL"
                                 :market-type :perp
                                 :coin "xyz:ORCL"}
                                {:instrument-id "perp:xyz:xyz:AAPL"
                                 :market-type :perp
                                 :coin "xyz:AAPL"}
                                {:instrument-id "perp:BTC"
                                 :market-type :perp
                                 :coin "BTC"}]
                     :history-assumptions {"perp:xyz:xyz:AAPL" {:behavior :conservative}}
                     :constraints {:long-only? false
                                   :held-locks ["perp:xyz:xyz:AAPL" "perp:BTC"]
                                   :per-perp-leverage-caps {"perp:xyz:xyz:AAPL" 3}})
        migrated (contracts/migrate-draft draft)]
    (is (= ["perp:xyz:ORCL" "perp:xyz:xyz:ORCL" "perp:xyz:xyz:AAPL" "perp:BTC"]
           (mapv :instrument-id (:universe draft)))
        "input draft is untouched")
    (is (= ["perp:xyz:ORCL" "perp:xyz:AAPL" "perp:BTC"]
           (mapv :instrument-id (:universe migrated)))
        "doubled ids collapse; a doubled twin of an existing canonical entry is dropped")
    (is (= {"perp:xyz:AAPL" {:behavior :conservative}}
           (:history-assumptions migrated)))
    (is (= ["perp:xyz:AAPL" "perp:BTC"]
           (get-in migrated [:constraints :held-locks])))
    (is (= {"perp:xyz:AAPL" 3}
           (get-in migrated [:constraints :per-perp-leverage-caps])))
    (is (= migrated (contracts/migrate-draft migrated)) "idempotent")))

(deftest canonical-doubled-dex-id-shapes-test
  (is (= "perp:xyz:ORCL" (migrations/canonical-doubled-dex-id "perp:xyz:xyz:ORCL")))
  (is (= "perp:xyz:ORCL" (migrations/canonical-doubled-dex-id "perp:xyz:ORCL"))
      "canonical ids pass through")
  (is (= "perp:BTC" (migrations/canonical-doubled-dex-id "perp:BTC")))
  (is (= "spot:PURR/USDC" (migrations/canonical-doubled-dex-id "spot:PURR/USDC")))
  (is (= "hl:hip3:xyz:AAPL" (migrations/canonical-doubled-dex-id "hl:hip3:xyz:AAPL"))
      "non perp:/spot: id families are never rewritten")
  (is (= :perp (migrations/canonical-doubled-dex-id :perp)) "non-strings pass through")
  (is (nil? (migrations/canonical-doubled-dex-id nil))))

(deftest future-version-migrations-fail-until-format-changes-test
  (testing "current optimizer persisted contracts are still version 1"
    (is (= 1 contracts/draft-schema-version))
    (is (= 1 contracts/scenario-record-schema-version))
    (is (= 1 contracts/tracking-record-schema-version)))
  (testing "unsupported future versions fail loudly until a real migration exists"
    (is (thrown-with-msg?
         js/Error
         #"Unsupported optimizer draft schema version"
         (contracts/migrate-draft (assoc sample-draft :schema-version 2))))
    (is (thrown-with-msg?
         js/Error
         #"Unsupported optimizer scenario record schema version"
         (contracts/migrate-scenario-record
          {:schema-version 2
           :id "scn_contract"
           :name "Contract Scenario"
           :status :saved
           :config (contracts/migrate-draft sample-draft)
           :updated-at-ms 3000})))
    (is (thrown-with-msg?
         js/Error
         #"Unsupported optimizer tracking record schema version"
         (contracts/migrate-tracking-record
          {:schema-version 2
           :scenario-id "scn_contract"
           :updated-at-ms 4000
           :snapshots []})))))

(deftest v1-persisted-contract-fixtures-migrate-and-validate-test
  (let [draft (contracts/migrate-draft contract-fixtures/v1-draft)
        scenario (contracts/migrate-scenario-record
                  contract-fixtures/v1-scenario-record)
        tracking (contracts/migrate-tracking-record
                  contract-fixtures/v1-tracking-record)]
    (testing "history assumptions are backfilled for drafts persisted before the key existed"
      (is (not (contains? contract-fixtures/v1-draft :history-assumptions)))
      (is (= {} (:history-assumptions draft))))
    (is (s/valid? ::contracts/draft draft)
        (s/explain-str ::contracts/draft draft))
    (is (s/valid? ::contracts/scenario-record scenario)
        (s/explain-str ::contracts/scenario-record scenario))
    (is (s/valid? ::contracts/tracking-record tracking)
        (s/explain-str ::contracts/tracking-record tracking))))

(deftest generated-contract-fixtures-validate-test
  (let [draft (contract-fixtures/valid-draft)
        request (contract-fixtures/valid-engine-request)
        signature (contract-fixtures/valid-request-signature)
        result (contract-fixtures/valid-solved-result)]
    (is (s/valid? ::contracts/draft draft)
        (s/explain-str ::contracts/draft draft))
    (is (s/valid? ::contracts/engine-request request)
        (s/explain-str ::contracts/engine-request request))
    (is (= request (:request signature)))
    (is (s/valid? ::contracts/request-signature signature)
        (s/explain-str ::contracts/request-signature signature))
    (is (s/valid? ::contracts/result-payload result)
        (s/explain-str ::contracts/result-payload result))))

(deftest request-signature-canonicalizes-optimizer-inputs-test
  (let [signature (contracts/build-request-signature sample-request)
        changed-volatile (-> sample-request
                             (assoc-in [:execution-assumptions
                                        :cost-contexts-by-id
                                        "perp:BTC"
                                        :best-bid
                                        :sz]
                                       "99")
                             (assoc-in [:history :freshness :age-ms] 9999))
        changed-model (assoc sample-request
                             :return-model
                             {:kind :black-litterman
                              :views [{:instrument-id "perp:BTC"
                                       :return 0.2}]})]
    (is (= contracts/request-signature-schema-version
           (:schema-version signature)))
    (is (= sample-request (:request signature)))
    (is (= (contracts/optimizer-input-signature sample-request)
           (:input-signature signature)))
    (is (= (:input-signature signature)
           (contracts/optimizer-input-signature changed-volatile)))
    (is (not= (:input-signature signature)
              (contracts/optimizer-input-signature changed-model)))
    (is (s/valid? ::contracts/request-signature signature))))

(deftest specs-reject-malformed-stabilized-contracts-test
  (testing "draft maps require the stabilized draft shapes"
    (let [draft (contracts/migrate-draft sample-draft)]
      (is (false? (s/valid? ::contracts/draft
                            (assoc draft :universe {"perp:BTC" true}))))
      (is (false? (s/valid? ::contracts/draft
                            (assoc draft :status :unknown))))
      (is (false? (s/valid? ::contracts/draft
                            (assoc draft :constraints []))))))
  (testing "request signatures must be canonical for their request"
    (is (false? (s/valid? ::contracts/request-signature
                          (assoc (contracts/build-request-signature sample-request)
                                 :input-signature
                                 {:return-model {:kind :not-the-request}})))))
  (testing "scenario and tracking records reject unknown lifecycle statuses"
    (let [draft (contracts/migrate-draft sample-draft)]
      (is (false? (s/valid? ::contracts/scenario-record
                            {:schema-version contracts/scenario-record-schema-version
                             :id "scn_contract"
                             :name "Contract Scenario"
                             :status :missing
                             :config draft
                             :updated-at-ms 3000}))))
    (is (false? (s/valid? ::contracts/tracking-snapshot
                          {:scenario-id "scn_contract"
                           :as-of-ms 4000
                           :status :missing})))
    (is (false? (s/valid? ::contracts/tracking-record
	                          {:schema-version contracts/tracking-record-schema-version
	                           :scenario-id "scn_contract"
	                           :updated-at-ms 4000
	                           :snapshots [{:scenario-id "scn_contract"
	                                        :as-of-ms 4000
	                                        :status :missing}]})))))

(deftest draft-contract-rejects-malformed-nested-shapes-test
  (let [draft (contract-fixtures/valid-draft)
        invalid-drafts [(assoc-in draft [:objective :kind] :unknown-objective)
                        (assoc-in draft [:return-model :kind] :unknown-return-model)
                        (assoc-in draft [:return-model :views] {})
                        (assoc-in draft [:risk-model :kind] :unknown-risk-model)
                        (assoc-in draft [:universe 0] {:coin "BTC"})
                        (assoc-in draft [:constraints :gross-max] "1.0")
                        (assoc-in draft [:execution-assumptions
                                         :default-order-type]
                                  :iceberg)
                        (assoc-in draft [:metadata :dirty?] "false")
                        (assoc draft :history-assumptions
                               {"perp:NEW" {:behavior :unknown
                                            :volatility 0.9
                                            :max-weight 0.03}})
                        (assoc draft :history-assumptions
                               {"perp:NEW" {:behavior :conservative
                                            :volatility 0.9
                                            :max-weight 0.03}})
                        (assoc draft :history-assumptions
                               {"perp:NEW" {:behavior :proxy
                                            :volatility 0.9
                                            :max-weight 0.05
                                            :relationship :extreme}})
                        (assoc draft :history-assumptions
                               {" " {:behavior :conservative
                                     :volatility 0.9
                                     :max-weight 0.03
                                     :correlation-floor 0.75}})]]
    (doseq [invalid-draft invalid-drafts]
      (is (false? (s/valid? ::contracts/draft invalid-draft))
          (s/explain-str ::contracts/draft invalid-draft)))))

(deftest history-assumptions-draft-contract-accepts-supported-shapes-test
  (let [draft (contract-fixtures/valid-draft)
        valid [["empty map" {}]
               ["conservative entry"
                {"perp:NEW" {:behavior :conservative
                             :expected-return 0.25
                             :volatility 0.9
                             :max-weight 0.03
                             :correlation-floor 0.75}}]
               ["blank return/vol still structurally valid (readiness enforces completeness)"
                {"perp:NEW" {:behavior :conservative
                             :expected-return nil
                             :volatility nil
                             :max-weight 0.03
                             :correlation-floor 0.75}}]
               ["engine-backed proxy entry"
                {"perp:NEW" {:behavior :proxy
                             :expected-return 0.0
                             :volatility 0.8
                             :max-weight 0.05
                             :proxy {:instrument-ids ["perp:BTC" "perp:ETH"]
                                     :relationship-strength :medium
                                     :prior-weights nil}
                             :metadata {:source :user :acknowledged? true}}}]
               ["proxy entry mid-configuration (no proxies picked yet)"
                {"perp:NEW" {:behavior :proxy
                             :expected-return 0.0
                             :volatility 0.8
                             :max-weight 0.05
                             :proxy {:instrument-ids []
                                     :relationship-strength :medium
                                     :prior-weights nil}}}]]]
    (doseq [[label assumptions] valid]
      (is (s/valid? ::contracts/draft (assoc draft :history-assumptions assumptions))
          (str label ": "
               (s/explain-str ::contracts/draft
                              (assoc draft :history-assumptions assumptions)))))))

(deftest history-assumptions-draft-contract-rejects-legacy-proxy-shape-test
  (let [draft (contract-fixtures/valid-draft)
        proxy-assumptions {"perp:NEW" {:behavior :proxy
                                       :expected-return 0.25
                                       :volatility 0.9
                                       :proxy-instrument-id "perp:BTC"
                                       :relationship :medium
                                       :max-weight 0.05}}]
    (is (not (s/valid? ::contracts/draft
                       (assoc draft :history-assumptions proxy-assumptions))))))

(deftest migrate-draft-converts-legacy-proxy-assumptions-to-conservative-test
  (let [draft (assoc (contract-fixtures/valid-draft)
                     :history-assumptions
                     {"perp:NEW" {:behavior :proxy
                                  :expected-return 0.25
                                  :volatility 0.9
                                  :proxy-instrument-id "perp:BTC"
                                  :relationship :medium
                                  :max-weight 0.05}})
        migrated (migrations/migrate-draft draft)
        entry (get-in migrated [:history-assumptions "perp:NEW"])]
    (is (= :conservative (:behavior entry))
        "A legacy proxy entry is converted to conservative.")
    (is (= 0.9 (:volatility entry)) "The user's volatility is preserved.")
    (is (= 0.25 (:expected-return entry)) "The user's expected return is preserved.")
    (is (= 0.75 (:correlation-floor entry)) "The conservative correlation floor is seeded.")
    (is (nil? (:proxy-instrument-id entry)) "Proxy-only fields are dropped.")
    (is (nil? (:relationship entry)))
    (is (s/valid? ::contracts/draft migrated)
        "The migrated draft passes the spec.")))

(deftest migrate-draft-passes-engine-backed-proxy-assumptions-through-test
  (let [assumptions {"perp:NEW" {:behavior :proxy
                                 :expected-return 0.0
                                 :volatility 0.8
                                 :max-weight 0.05
                                 :proxy {:instrument-ids ["perp:BTC"]
                                         :relationship-strength :high
                                         :prior-weights nil}}}
        draft (assoc (contract-fixtures/valid-draft)
                     :history-assumptions assumptions)
        migrated (migrations/migrate-draft draft)]
    (is (= assumptions (:history-assumptions migrated))
        "The engine-backed multi-proxy shape survives migration unchanged.")
    (is (s/valid? ::contracts/draft migrated))))

(deftest migrate-draft-backfills-proxy-reference-instruments-test
  (let [legacy (dissoc (contract-fixtures/valid-draft) :proxy-reference-instruments)
        migrated (migrations/migrate-draft legacy)]
    (is (= [] (:proxy-reference-instruments migrated))
        "A pre-existing draft backfills an empty reference-instruments vector.")
    (is (s/valid? ::contracts/draft migrated)))
  (let [with-refs (assoc (contract-fixtures/valid-draft)
                         :proxy-reference-instruments
                         [{:instrument-id "perp:SOL" :market-type :perp :coin "SOL"}])]
    (is (s/valid? ::contracts/draft with-refs)
        "A populated reference-instruments vector is a valid draft.")))

(deftest black-litterman-view-contract-accepts-supported-shapes-test
  (let [valid-views [["absolute instrument view"
                      {:kind :absolute
                       :instrument-id "perp:BTC"
                       :return 0.12
                       :confidence 0.75
                       :direction :outperform}]
                     ["absolute weighted view"
                      {:kind :absolute
                       :weights {"perp:BTC" 1.0}
                       :return -0.02
                       :confidence-variance 0.1}]
                     ["relative comparator view"
                      {:kind :relative
                       :instrument-id "perp:BTC"
                       :comparator-instrument-id "perp:ETH"
                       :return 0.03
                       :confidence 0.5
                       :confidence-variance 0.2
                       :direction :underperform}]
                     ["relative long-short view"
                      {:kind :relative
                       :long-instrument-id "perp:SOL"
                       :short-instrument-id "perp:BTC"
                       :return 0.04}]
                     ["relative weighted view"
                      {:kind :relative
                       :weights {"perp:BTC" 0.5
                                 "perp:ETH" -0.5}
                       :return 0.01}]]]
    (doseq [[label view] valid-views]
      (let [draft (draft-with-black-litterman-views [view])]
        (is (s/valid? ::contracts/draft draft)
            (str label ": " (s/explain-str ::contracts/draft draft)))))))

(deftest black-litterman-view-contract-rejects-malformed-shapes-test
  (let [invalid-views [["unknown view kind"
                        {:kind :tilted
                         :instrument-id "perp:BTC"
                         :return 0.1}]
                       ["missing return"
                        {:kind :absolute
                         :instrument-id "perp:BTC"}]
                       ["non-finite return"
                        {:kind :absolute
                         :instrument-id "perp:BTC"
                         :return js/NaN}]
                       ["non-finite confidence"
                        {:kind :absolute
                         :instrument-id "perp:BTC"
                         :return 0.1
                         :confidence "high"}]
                       ["invalid direction"
                        {:kind :absolute
                         :instrument-id "perp:BTC"
                         :return 0.1
                         :direction :sideways}]
                       ["weights must be a map"
                        {:kind :absolute
                         :instrument-id "perp:BTC"
                         :return 0.1
                         :weights [["perp:BTC" 1.0]]}]
                       ["absolute view needs an instrument or weights"
                        {:kind :absolute
                         :instrument-id " "
                         :return 0.1}]
                       ["relative comparator instruments must differ"
                        {:kind :relative
                         :instrument-id "perp:BTC"
                         :comparator-instrument-id "perp:BTC"
                         :return 0.1}]
                       ["relative long-short instruments must differ"
                        {:kind :relative
                         :long-instrument-id "perp:BTC"
                         :short-instrument-id "perp:BTC"
                         :return 0.1}]
                       ["relative view needs a pair or weights"
                        {:kind :relative
                         :return 0.1}]]]
    (doseq [[label view] invalid-views]
      (let [draft (draft-with-black-litterman-views [view])]
        (is (false? (s/valid? ::contracts/draft draft))
            label)))))

(deftest engine-request-contract-rejects-malformed-nested-shapes-test
  (let [request (contract-fixtures/valid-engine-request)
        invalid-requests [(assoc-in request [:objective :kind] :unknown-objective)
                          (assoc-in request [:history :eligible-instruments 0]
                                    {:coin "BTC"})
                          (assoc-in request [:history :return-series-by-instrument]
                                    [])
                          (assoc-in request [:execution-assumptions
                                             :fallback-slippage-bps]
                                    "25")
                          (assoc request :warnings {})
                          (contract-fixtures/dissoc-contract-in request
                                                                 [:history
                                                                  :freshness])]]
    (doseq [invalid-request invalid-requests]
      (is (false? (s/valid? ::contracts/engine-request invalid-request))
          (s/explain-str ::contracts/engine-request invalid-request)))))

(deftest request-signature-contract-validates-nested-request-test
  (let [signature (contract-fixtures/valid-request-signature)
        malformed-request (assoc-in (:request signature)
                                    [:return-model :kind]
                                    :unknown-return-model)
        malformed-signature (-> signature
                                (assoc :request malformed-request)
                                (assoc :input-signature
                                       (contracts/optimizer-input-signature
                                        malformed-request)))]
    (is (false? (s/valid? ::contracts/request-signature malformed-signature))
        (s/explain-str ::contracts/request-signature malformed-signature))))

(deftest result-payload-contract-validates-solved-payloads-test
  (is (s/valid? ::contracts/result-payload sample-solved-result))
  (is (s/valid? ::contracts/result-payload
                {:status :infeasible
                 :reason :insufficient-history}))
  (is (false? (s/valid? ::contracts/result-payload
                        {:status :solved})))
  (is (false? (s/valid? ::contracts/result-payload
                        (assoc sample-solved-result
                               :target-weights
                               [0.6]))))
  (is (false? (s/valid? ::contracts/result-payload
                        (assoc sample-solved-result
                               :target-weights-by-instrument
                               {"perp:BTC" 0.6})))))

(deftest result-payload-contract-rejects-malformed-solved-nested-shapes-test
  (let [result (contract-fixtures/valid-solved-result)
        invalid-results [(assoc-in result [:target-weights 0] js/NaN)
                         (assoc result :current-weights ["0.45" 0.3 0.05])
                         (assoc result :warnings {})
                         (assoc result :frontier {})
                         (assoc-in result [:rebalance-preview :rows] {})]]
    (doseq [invalid-result invalid-results]
      (is (false? (s/valid? ::contracts/result-payload invalid-result))
          (s/explain-str ::contracts/result-payload invalid-result)))))

(deftest result-payload-contract-accepts-real-solved-fixture-test
  (let [payload (optimizer-fixtures/get-optimizer-in
                 (optimizer-fixtures/sample-scenario-state)
                 [:last-successful-run :result])]
    (is (= :solved (:status payload)))
    (is (s/valid? ::contracts/result-payload payload)
        (s/explain-str ::contracts/result-payload payload))))

(deftest optimizer-contract-status-sets-cover-current-producers-test
  (testing "draft statuses"
    (doseq [status [:draft :saved :archived :tracking]]
      (is (allowed? contracts/draft-statuses status))))
  (testing "scenario record statuses"
    (doseq [status [:saved :archived :executed :partially-executed :tracking :failed]]
      (is (allowed? contracts/scenario-record-statuses status))))
  (testing "tracking snapshot statuses"
    (doseq [status [:tracked :not-trackable]]
      (is (allowed? contracts/tracking-snapshot-statuses status))))
  (testing "result payload statuses"
    (doseq [status [:solved :infeasible :error :failed]]
      (is (allowed? contracts/result-payload-statuses status)))))

(deftest wire-codec-normalizes-worker-boundary-test
  (let [perp-id (keyword "perp:BTC")
        normalized (contracts/normalize-worker-boundary
                    {:type "optimizer-result"
                     :payload {:status "solved"
                               :expected-returns-by-instrument {perp-id 0.12}
                               :current-portfolio-weights-by-instrument {perp-id 0.5}
                               :diagnostics {:weight-sensitivity-by-instrument
                                             {perp-id {:max-delta 0.01}}}}
                     :return-model {:kind "black-litterman"
                                    :views [{:kind "absolute"
                                             :instrument-id "perp:BTC"
                                             :weights {perp-id 1}}]}})]
    (is (= :optimizer-result (:type normalized)))
    (is (= :solved (get-in normalized [:payload :status])))
    (is (= {"perp:BTC" 0.12}
           (get-in normalized [:payload :expected-returns-by-instrument])))
    (is (= {"perp:BTC" 0.5}
           (get-in normalized
                   [:payload :current-portfolio-weights-by-instrument])))
    (is (= {"perp:BTC" {:max-delta 0.01}}
           (get-in normalized
                   [:payload :diagnostics :weight-sensitivity-by-instrument])))
    (is (= {"perp:BTC" 1}
           (get-in normalized [:return-model :views 0 :weights])))
    (is (s/valid? ::contracts/worker-envelope
                  {:type (:type normalized)
                   :payload (:payload normalized)}))))

(deftest migrate-draft-converts-absolute-net-band-to-percentage-test
  (let [draft (assoc-in (optimizer-fixtures/sample-draft) [:constraints]
                        {:long-only? false
                         :gross-max 15.35
                         :net-min -0.5
                         :net-max 0.5})
        constraints (:constraints (contracts/migrate-draft draft))]
    (is (= 0.0 (:net-min constraints)))
    (is (= 0.0 (:net-max constraints)))
    (is (= 0.032573 (:net-band-pct constraints))
        "0.50x band at a 15.35x gross target ⇒ 3.2573% of gross")))

(deftest migrate-draft-net-band-uses-gross-midpoint-and-clamps-test
  (let [constraints (:constraints
                     (contracts/migrate-draft
                      (assoc-in (optimizer-fixtures/sample-draft) [:constraints]
                                {:gross-min 1.0 :gross-max 3.0
                                 :net-min 0.0 :net-max 6.0})))]
    (is (= 3.0 (:net-min constraints)))
    (is (= 3.0 (:net-max constraints)))
    (is (= 1.0 (:net-band-pct constraints))
        "a 3.0x half-width at a 2.0x gross target clamps to the 100% ceiling")))

(deftest migrate-draft-net-band-resets-safely-without-a-gross-target-test
  (doseq [gross [nil 0 -1 js/NaN]]
    (let [constraints (:constraints
                       (contracts/migrate-draft
                        (assoc-in (optimizer-fixtures/sample-draft) [:constraints]
                                  {:gross-max gross
                                   :net-min 0.5 :net-max 1.5})))]
      (is (= 1.0 (:net-min constraints)))
      (is (= 1.0 (:net-max constraints)))
      (is (= 0.0 (:net-band-pct constraints))
          "no usable gross target ⇒ reset to the 0% default, never divide"))))

(deftest migrate-draft-net-band-is-idempotent-and-preserves-new-drafts-test
  (let [migrated (:constraints
                  (contracts/migrate-draft
                   (assoc-in (optimizer-fixtures/sample-draft) [:constraints]
                             {:gross-max 2.0 :net-min -0.5 :net-max 0.5})))
        again (:constraints (contracts/migrate-draft
                             (assoc (optimizer-fixtures/sample-draft) :constraints migrated)))]
    (is (= migrated again) "re-running the migration changes nothing"))
  (let [new-shape {:gross-max 2.0 :net-min -0.2 :net-max 0.8 :net-band-pct 0.05}]
    (is (= new-shape
           (:constraints (contracts/migrate-draft
                          (assoc (optimizer-fixtures/sample-draft) :constraints new-shape))))
        "a draft already carrying :net-band-pct keeps its advanced net range")))
