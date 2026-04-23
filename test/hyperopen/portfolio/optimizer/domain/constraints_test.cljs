(ns hyperopen.portfolio.optimizer.domain.constraints-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]))

(deftest normalize-universe-applies-allowlist-and-blocklist-before-solving-test
  (let [universe [{:instrument-id "A"}
                  {:instrument-id "B"}
                  {:instrument-id "C"}]]
    (is (= [{:instrument-id "A"}]
           (constraints/normalize-universe universe
                                           {:allowlist #{"A" "B"}
                                            :blocklist #{"B"}})))))

(deftest encode-long-only-bounds-applies-global-cap-overrides-and-held-locks-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}
                             {:instrument-id "C"}]
                  :current-weights {"C" 0.2}
                  :constraints {:long-only? true
                                :max-asset-weight 0.6
                                :per-asset-overrides {"B" {:max-weight 0.25}}
                                :held-position-locks #{"C"}}})]
    (is (= ["A" "B" "C"] (:instrument-ids encoded)))
    (is (= [0 0 0.2] (:lower-bounds encoded)))
    (is (= [0.6 0.25 0.2] (:upper-bounds encoded)))
    (is (= [{:instrument-id "C"
             :weight 0.2}]
           (:locked-weights encoded)))
    (is (= :ok (:status encoded)))))

(deftest presolve-reports-infeasible-long-only-cap-before-solver-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.4}})]
    (is (= :infeasible (:status encoded)))
    (is (= [{:code :sum-upper-below-target
             :sum-upper 0.8
             :target-net 1}]
           (:violations encoded)))))

(deftest encode-signed-mode-bounds-supports-gross-and-net-exposure-contract-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :current-weights {"A" 0.3
                                    "B" -0.1}
                  :constraints {:long-only? false
                                :max-asset-weight 0.7
                                :gross-leverage 1.4
                                :max-turnover 0.25
                                :net-exposure {:min -0.2
                                               :max 0.8}}})]
    (is (= [-0.7 -0.7] (:lower-bounds encoded)))
    (is (= [0.7 0.7] (:upper-bounds encoded)))
    (is (= [0.3 -0.1] (:current-weights encoded)))
    (is (= {:max 1.4} (:gross-exposure encoded)))
    (is (= 0.25 (:max-turnover encoded)))
    (is (= {:min -0.2 :max 0.8} (:net-exposure encoded)))))
