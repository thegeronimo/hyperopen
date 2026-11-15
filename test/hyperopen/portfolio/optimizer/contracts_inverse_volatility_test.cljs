(ns hyperopen.portfolio.optimizer.contracts-inverse-volatility-test
  "Contract coverage for the Risk-weighted sizing (:inverse-volatility)
  objective (ExecPlan optimizer-inverse-volatility-objective, item 7 plus the
  constants/spec acceptance): the kind is canonical, drafts and engine
  requests carrying it validate, and switching :equal-risk ->
  :inverse-volatility changes the optimizer input signature (stale gate)."
  (:require [cljs.spec.alpha :as s]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.contract-fixtures :as contract-fixtures]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.contracts.signatures :as signatures]
            [hyperopen.portfolio.optimizer.contracts.spec-registry]
            [hyperopen.portfolio.optimizer.defaults :as defaults]))

(deftest inverse-volatility-objective-is-a-canonical-kind-test
  (is (contains? contracts/objective-kinds :inverse-volatility)))

(deftest draft-with-inverse-volatility-objective-validates-and-migrates-test
  (let [draft (-> (defaults/default-draft)
                  (assoc :objective {:kind :inverse-volatility})
                  (assoc :universe [{:instrument-id "perp:BTC"
                                     :market-type :perp
                                     :coin "BTC"
                                     :shortable? true
                                     :position-side :long}]))]
    (is (s/valid? :hyperopen.portfolio.optimizer.contracts/draft draft)
        (s/explain-str :hyperopen.portfolio.optimizer.contracts/draft draft))
    (testing "migration passes the objective through untouched (round-trip)"
      (is (= {:kind :inverse-volatility}
             (:objective (contracts/migrate-draft draft)))))))

(deftest engine-request-with-inverse-volatility-objective-validates-test
  (let [request (assoc (contract-fixtures/valid-engine-request)
                       :objective {:kind :inverse-volatility})]
    (is (s/valid? :hyperopen.portfolio.optimizer.contracts/engine-request request)
        (s/explain-str :hyperopen.portfolio.optimizer.contracts/engine-request
                       request))))

(deftest input-signature-differs-between-equal-risk-and-inverse-volatility-test
  (let [base (contract-fixtures/valid-engine-request)
        equal-risk-request (assoc base :objective {:kind :equal-risk})
        inverse-volatility-request (assoc base
                                          :objective {:kind :inverse-volatility})]
    (is (not= (signatures/optimizer-input-signature equal-risk-request)
              (signatures/optimizer-input-signature inverse-volatility-request)))
    (testing "the same inverse-volatility request keeps a stable signature"
      (is (= (signatures/optimizer-input-signature inverse-volatility-request)
             (signatures/optimizer-input-signature
              (assoc base :objective {:kind :inverse-volatility})))))))
