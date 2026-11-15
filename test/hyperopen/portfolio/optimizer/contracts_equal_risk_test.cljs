(ns hyperopen.portfolio.optimizer.contracts-equal-risk-test
  "Contract coverage for the :equal-risk objective: spec acceptance, draft
  migration/round-trip, and worker-wire codec behavior for the new result
  sections."
  (:require [cljs.spec.alpha :as s]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.contracts.spec-registry]
            [hyperopen.portfolio.optimizer.defaults :as defaults]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.portfolio.optimizer.infrastructure.wire :as wire]))

(deftest equal-risk-objective-is-a-canonical-kind-test
  (is (contains? contracts/objective-kinds :equal-risk)))

(deftest draft-with-equal-risk-objective-validates-and-migrates-test
  (let [draft (-> (defaults/default-draft)
                  (assoc :objective {:kind :equal-risk})
                  (assoc :universe [{:instrument-id "perp:BTC"
                                     :market-type :perp
                                     :coin "BTC"
                                     :shortable? true
                                     :position-side :long}]))]
    (is (s/valid? :hyperopen.portfolio.optimizer.contracts/draft draft)
        (s/explain-str :hyperopen.portfolio.optimizer.contracts/draft draft))
    (testing "migration passes the objective through untouched (round-trip)"
      (is (= {:kind :equal-risk}
             (:objective (contracts/migrate-draft draft)))))
    (testing "existing objective kinds keep validating (no regression)"
      (doseq [kind [:minimum-variance :max-sharpe]]
        (let [existing (assoc draft :objective {:kind kind})]
          (is (s/valid? :hyperopen.portfolio.optimizer.contracts/draft existing)
              (str kind " draft should validate")))))))

(def ^:private equal-risk-result-sections
  {:risk-contributions
   {:method :signed-euler-volatility
    :instrument-ids ["perp:BTC" "perp:ETH"]
    :variance-contributions [0.002 0.002]
    :volatility-contributions [0.03 0.03]
    :relative-contributions [0.5 0.5]
    :target-relative-contributions [0.5 0.5]
    :relative-contributions-by-instrument {"perp:BTC" 0.5 "perp:ETH" 0.5}
    :target-relative-contributions-by-instrument {"perp:BTC" 0.5 "perp:ETH" 0.5}
    :sum-relative-contributions 1.0
    :rms-error 0.0
    :max-absolute-error 0.0
    :negative-contribution-count 0
    :quality :exact}
   :equal-risk-solver
   {:strategy :sequential-equal-risk
    :converged? true
    :termination-reason :step-tolerance
    :iterations 9
    :total-iterations 21
    :initialization-count 4
    :selected-initialization :inverse-volatility
    :objective-value 3.0e-9
    :step-residual 1.0e-10
    :exactness-tolerance 0.005
    :initializations [{:seed-kind :equal-notional
                       :status :completed
                       :objective 4.0e-9
                       :iterations 6
                       :termination-reason :objective-improvement
                       :converged? true}]}})

(deftest equal-risk-scenario-record-round-trips-through-migration-test
  (let [record {:schema-version contracts/scenario-record-schema-version
                :id "equal-risk-record"
                :name "Equal Risk Scenario"
                :status :saved
                :config (-> (defaults/default-draft)
                            (assoc :objective {:kind :equal-risk})
                            (assoc :universe [{:instrument-id "perp:BTC"
                                               :market-type :perp
                                               :coin "BTC"
                                               :shortable? true
                                               :position-side :long}]))
                :saved-run {:request-signature {:scenario-id "equal-risk-record"}
                            :computed-at-ms 1777046400000
                            :result (merge (fixtures/sample-solved-result {})
                                           equal-risk-result-sections)}
                :created-at-ms 1777046400000
                :updated-at-ms 1777046400000}
        migrated (contracts/migrate-scenario-record record)]
    (is (= {:kind :equal-risk} (get-in migrated [:config :objective])))
    (is (= (get-in record [:saved-run :result])
           (get-in migrated [:saved-run :result])))
    (is (s/valid? :hyperopen.portfolio.optimizer.contracts/scenario-record migrated)
        (s/explain-str :hyperopen.portfolio.optimizer.contracts/scenario-record
                       migrated))))

(deftest solved-payload-with-equal-risk-sections-passes-result-spec-test
  (let [payload (merge (fixtures/sample-solved-result {})
                       equal-risk-result-sections)]
    (is (s/valid? :hyperopen.portfolio.optimizer.contracts/result-payload payload)
        (s/explain-str :hyperopen.portfolio.optimizer.contracts/result-payload
                       payload))))

(deftest equal-risk-sections-survive-the-worker-wire-round-trip-test
  (let [payload {:status "solved"
                 :risk-contributions
                 {:method "signed-euler-volatility"
                  :instrument-ids ["perp:BTC" "perp:ETH"]
                  :relative-contributions [0.5 0.5]
                  :target-relative-contributions [0.5 0.5]
                  :relative-contributions-by-instrument {(keyword "perp:BTC") 0.5
                                                         (keyword "perp:ETH") 0.5}
                  :target-relative-contributions-by-instrument {(keyword "perp:BTC") 0.5
                                                                (keyword "perp:ETH") 0.5}
                  :quality "approximate"}
                 :equal-risk-solver
                 {:strategy "sequential-equal-risk"
                  :converged? true
                  :termination-reason "max-iterations"
                  :selected-initialization "current-weights"
                  :initializations [{:seed-kind "equal-notional"
                                     :status "completed"}]}}
        normalized (wire/normalize-worker-boundary payload)]
    (is (= {"perp:BTC" 0.5 "perp:ETH" 0.5}
           (get-in normalized [:risk-contributions
                               :relative-contributions-by-instrument])))
    (is (= {"perp:BTC" 0.5 "perp:ETH" 0.5}
           (get-in normalized [:risk-contributions
                               :target-relative-contributions-by-instrument])))
    (is (= :signed-euler-volatility
           (get-in normalized [:risk-contributions :method])))
    (is (= :approximate (get-in normalized [:risk-contributions :quality])))
    (is (= :sequential-equal-risk
           (get-in normalized [:equal-risk-solver :strategy])))
    (is (= :max-iterations
           (get-in normalized [:equal-risk-solver :termination-reason])))
    (is (= :current-weights
           (get-in normalized [:equal-risk-solver :selected-initialization])))
    (is (= :equal-notional
           (get-in normalized [:equal-risk-solver :initializations 0 :seed-kind])))))

(deftest legacy-and-summary-bearing-risk-structure-contracts-round-trip-test
  (let [legacy-structure {:method :signed-euler-decomposition
                          :portfolio-volatility 0.2
                          :standalone-share-by-instrument {"perp:BTC" 1.0}
                          :diversification-share-by-instrument {"perp:BTC" 0.0}
                          :pnl-portfolio-correlation-by-instrument {"perp:BTC" 1.0}
                          :correlation {:instrument-ids ["perp:BTC"]
                                        :matrix [[1.0]]
                                        :hidden-count 0}}
        summary {:modeled-volatility 0.2
                 :all-move-together-volatility 0.2
                 :zero-correlation-volatility 0.2
                 :reduction-vs-all-move-together 0.0
                 :reduction-ratio-vs-all-move-together 0.0
                 :modeled-minus-zero-correlation 0.0}
        legacy (assoc (merge (fixtures/sample-solved-result {})
                             equal-risk-result-sections)
                      :risk-structure legacy-structure)
        extended (assoc legacy :risk-structure
                        (assoc legacy-structure
                               :target-diversification summary
                               :current-diversification summary))]
    (testing "pre-summary saved results remain accepted without fabricated keys"
      (is (s/valid? :hyperopen.portfolio.optimizer.contracts/result-payload legacy))
      (is (not (contains? (:risk-structure legacy) :target-diversification))))
    (testing "finite scalar summaries extend the result contract"
      (is (s/valid? :hyperopen.portfolio.optimizer.contracts/result-payload extended)
          (s/explain-str :hyperopen.portfolio.optimizer.contracts/result-payload
                         extended))
      (let [normalized (wire/normalize-worker-boundary
                        (js->clj (clj->js extended) :keywordize-keys true))]
        (is (= summary (get-in normalized
                               [:risk-structure :target-diversification])))
        (is (nil? (get-in normalized [:risk-structure :covariance])))
        (is (nil? (get-in normalized [:risk-structure :observations])))))))

(deftest current-diversification-cannot-exist-without-target-test
  (let [summary {:modeled-volatility 0.2
                 :all-move-together-volatility 0.3
                 :zero-correlation-volatility 0.18
                 :reduction-vs-all-move-together 0.1
                 :reduction-ratio-vs-all-move-together (/ 1 3)
                 :modeled-minus-zero-correlation 0.02}
        current-only-structure
        {:method :signed-euler-decomposition
         :portfolio-volatility 0.2
         :current-diversification summary
         :standalone-share-by-instrument {"perp:BTC" 1.0}
         :diversification-share-by-instrument {"perp:BTC" 0.0}
         :pnl-portfolio-correlation-by-instrument {"perp:BTC" 1.0}
         :correlation {:instrument-ids ["perp:BTC"]
                       :matrix [[1.0]]
                       :hidden-count 0}}
        payload (assoc (merge (fixtures/sample-solved-result {})
                              equal-risk-result-sections)
                       :risk-structure current-only-structure)]
    (is (not (s/valid? :hyperopen.portfolio.optimizer.contracts/result-payload
                       payload))
        "current is a comparison to the canonical target, never a standalone result")))
