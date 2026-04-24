(ns hyperopen.portfolio.optimizer.defaults-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.defaults :as defaults]))

(deftest default-draft-preserves-v1-model-layer-contract-test
  (let [draft (defaults/default-draft)]
    (is (= :draft (:status draft)))
    (is (= :minimum-variance (get-in draft [:objective :kind])))
    (is (= :historical-mean (get-in draft [:return-model :kind])))
    (is (= :ledoit-wolf (get-in draft [:risk-model :kind])))
    (is (= false (get-in draft [:metadata :dirty?])))
    (is (= false (get-in draft [:constraints :long-only?])))
    (is (contains? (:constraints draft) :max-asset-weight))
    (is (contains? (:constraints draft) :gross-max))
    (is (contains? (:constraints draft) :net-min))
    (is (contains? (:constraints draft) :net-max))
    (is (contains? (:constraints draft) :dust-usdc))
    (is (contains? (:constraints draft) :perp-leverage))
    (is (contains? (:constraints draft) :allowlist))
    (is (contains? (:constraints draft) :blocklist))
    (is (contains? (:constraints draft) :max-turnover))
    (is (contains? (:constraints draft) :rebalance-tolerance))))

(deftest default-optimizer-state-preserves-run-and-scenario-contract-test
  (is (= {:status :idle
          :run-id nil
          :scenario-id nil
          :request-signature nil
          :started-at-ms nil
          :completed-at-ms nil
          :error nil
          :result nil}
         (:run-state (defaults/default-optimizer-state))))
  (is (= {:loaded-id nil
          :status :idle
          :read-only? false}
         (:active-scenario (defaults/default-optimizer-state))))
  (is (= {:ordered-ids []
          :by-id {}}
         (get-in (defaults/default-optimizer-state)
                 [:scenario-index]))))

(deftest default-optimizer-ui-state-matches-route-query-defaults-test
  (is (= {:list-filter :active
          :list-sort :updated-desc
          :workspace-panel :setup
          :results-tab :allocation
          :diagnostics-tab :conditioning}
         (defaults/default-optimizer-ui-state))))
