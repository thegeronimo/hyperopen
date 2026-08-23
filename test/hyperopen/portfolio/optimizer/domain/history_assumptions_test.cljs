(ns hyperopen.portfolio.optimizer.domain.history-assumptions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 1e-9))

(deftest return-required-for-objective-test
  (is (history-assumptions/return-required-for-objective? :max-sharpe))
  (is (history-assumptions/return-required-for-objective? :target-return))
  (is (history-assumptions/return-required-for-objective? :target-volatility))
  (is (not (history-assumptions/return-required-for-objective? :minimum-variance))))

(deftest conservative-assumption-complete-test
  (let [full {:behavior :conservative
              :expected-return 0.25
              :volatility 0.9
              :max-weight 0.03
              :correlation-floor 0.75}]
    (testing "minimum-variance needs no expected return"
      (is (history-assumptions/conservative-assumption-complete?
           (assoc full :expected-return nil) false))
      (is (history-assumptions/conservative-assumption-complete? full false)))
    (testing "return-seeking objectives require an expected return"
      (is (not (history-assumptions/conservative-assumption-complete?
                (assoc full :expected-return nil) true)))
      (is (history-assumptions/conservative-assumption-complete? full true)))
    (testing "volatility and cap must be positive"
      (is (not (history-assumptions/conservative-assumption-complete?
                (assoc full :volatility nil) false)))
      (is (not (history-assumptions/conservative-assumption-complete?
                (assoc full :max-weight 0) false))))
    (testing "a proxy entry is never conservative-complete"
      (is (not (history-assumptions/conservative-assumption-complete?
                (assoc full :behavior :proxy) false))))))

(deftest conservative-engine-inputs-extracts-universe-assets-test
  (let [request {:universe [{:instrument-id "perp:BTC"} {:instrument-id "perp:NEW"}]
                 :history-assumptions
                 {"perp:NEW" {:behavior :conservative
                              :expected-return 0.25
                              :volatility 0.9
                              :correlation-floor 0.75
                              :max-weight 0.03}
                  ;; proxy is excluded; off-universe id is excluded
                  "perp:PXY" {:behavior :proxy :volatility 0.8 :max-weight 0.05}
                  "perp:GONE" {:behavior :conservative :volatility 0.5
                               :correlation-floor 0.75 :max-weight 0.03}}}]
    (is (= {"perp:NEW" {:volatility 0.9
                        :correlation-floor 0.75
                        :expected-return 0.25}}
           (history-assumptions/conservative-engine-inputs request)))))

(deftest augment-expected-returns-overrides-conservative-assets-test
  (let [return-result {:expected-returns-by-instrument {"perp:BTC" 0.1}}
        conservative {"perp:NEW" {:volatility 0.9 :correlation-floor 0.75 :expected-return 0.25}
                      "perp:NIL" {:volatility 0.5 :correlation-floor 0.75 :expected-return nil}}
        augmented (history-assumptions/augment-expected-returns return-result conservative)]
    (is (= 0.1 (get-in augmented [:expected-returns-by-instrument "perp:BTC"])))
    (is (= 0.25 (get-in augmented [:expected-returns-by-instrument "perp:NEW"])))
    (is (not (contains? (:expected-returns-by-instrument augmented) "perp:NIL"))
        "An asset with no stated expected return is left to the engine's default.")))

(deftest default-assumption-supports-both-behaviors-test
  (let [conservative (history-assumptions/default-assumption :conservative)
        proxy (history-assumptions/default-assumption :proxy)]
    (is (= :conservative (:behavior conservative)))
    (is (= :proxy (:behavior proxy)))
    (is (= 0.8 (:volatility proxy)) "Both behaviors seed the risky-until-revised vol anchor.")
    (is (= 0.05 (:max-weight proxy)))
    (is (= [] (get-in proxy [:proxy :instrument-ids])))
    (is (= :medium (get-in proxy [:proxy :relationship-strength])))
    (is (nil? (get-in proxy [:proxy :prior-weights])) "Reserved: nil means equal weight.")
    (is (nil? (history-assumptions/default-assumption :unknown)))))

(def ^:private complete-proxy-entry
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC" "perp:ETH"]
           :relationship-strength :medium
           :prior-weights nil}})

(def ^:private proxy-ctx
  {:self-id "perp:TOKENX"
   :return-required? false
   :usable-proxy-ids #{"perp:BTC" "perp:ETH" "perp:SOL"}
   :max-asset-weight 0.5})

(deftest proxy-completeness-classifier-test
  (is (nil? (history-assumptions/first-missing-proxy-field complete-proxy-entry proxy-ctx)))
  (is (history-assumptions/proxy-assumption-complete? complete-proxy-entry proxy-ctx))
  (is (= :proxy-instruments
         (history-assumptions/first-missing-proxy-field
          (assoc-in complete-proxy-entry [:proxy :instrument-ids] [])
          proxy-ctx)))
  (is (= :self-proxy
         (history-assumptions/first-missing-proxy-field
          (assoc-in complete-proxy-entry [:proxy :instrument-ids]
                    ["perp:BTC" "perp:TOKENX"])
          proxy-ctx)))
  (is (= :proxy-history
         (history-assumptions/first-missing-proxy-field
          (assoc-in complete-proxy-entry [:proxy :instrument-ids]
                    ["perp:BTC" "perp:GONE"])
          proxy-ctx)))
  (is (= :volatility
         (history-assumptions/first-missing-proxy-field
          (assoc complete-proxy-entry :volatility nil)
          proxy-ctx)))
  (is (= :max-weight
         (history-assumptions/first-missing-proxy-field
          (assoc complete-proxy-entry :max-weight 0)
          proxy-ctx)))
  (is (= :max-weight-exceeds-global
         (history-assumptions/first-missing-proxy-field
          (assoc complete-proxy-entry :max-weight 0.6)
          proxy-ctx)))
  (testing "expected return is required only for return-seeking objectives"
    (let [no-return (assoc complete-proxy-entry :expected-return nil)]
      (is (nil? (history-assumptions/first-missing-proxy-field no-return proxy-ctx)))
      (is (= :expected-return
             (history-assumptions/first-missing-proxy-field
              no-return
              (assoc proxy-ctx :return-required? true))))))
  (testing "unknown usable set skips the history check (view-model context)"
    (is (nil? (history-assumptions/first-missing-proxy-field
               (assoc-in complete-proxy-entry [:proxy :instrument-ids]
                         ["perp:ANYTHING"])
               (dissoc proxy-ctx :usable-proxy-ids))))))

(deftest assumption-complete-dispatches-by-behavior-test
  (is (history-assumptions/assumption-complete? complete-proxy-entry false proxy-ctx))
  (is (not (history-assumptions/assumption-complete?
            (assoc-in complete-proxy-entry [:proxy :instrument-ids] [])
            false proxy-ctx)))
  (is (not (history-assumptions/assumption-complete? {:behavior :unknown} false))))

(deftest proxy-engine-inputs-flattens-universe-entries-test
  (let [request {:universe [{:instrument-id "perp:BTC"}
                            {:instrument-id "perp:TOKENX"}]
                 :history-assumptions
                 {"perp:TOKENX" {:behavior :proxy
                                 :volatility 0.8
                                 :max-weight 0.05
                                 :expected-return 0.0
                                 :proxy-instrument-ids ["perp:BTC"]
                                 :proxy-prior-weights {"perp:BTC" 1}
                                 :relationship-strength :high
                                 :regression-series {:observations 10}}
                  ;; conservative entries are not proxy inputs
                  "perp:NEW" {:behavior :conservative :volatility 0.9
                              :correlation-floor 0.75 :max-weight 0.03}
                  ;; off-universe proxy entries are excluded
                  "perp:GONE" {:behavior :proxy :volatility 0.8 :max-weight 0.05
                               :proxy-instrument-ids ["perp:BTC"]}}}
        inputs (history-assumptions/proxy-engine-inputs request)]
    (is (= ["perp:TOKENX"] (keys inputs)))
    (is (= {:behavior :proxy
            :volatility 0.8
            :max-weight 0.05
            :expected-return 0.0
            :proxy-instrument-ids ["perp:BTC"]
            :proxy-prior-weights {"perp:BTC" 1}
            :relationship-strength :high
            :regression-series {:observations 10}}
           (get inputs "perp:TOKENX")))
    (is (= {:conservative (history-assumptions/conservative-engine-inputs request)
            :proxy inputs}
           (history-assumptions/history-assumption-engine-inputs request)))))

(deftest augment-expected-returns-covers-proxy-assets-test
  (let [augmented (history-assumptions/augment-expected-returns
                   {:expected-returns-by-instrument {"perp:BTC" 0.1}}
                   {"perp:TOKENX" {:volatility 0.8 :expected-return 0.12}})]
    (is (= 0.12 (get-in augmented [:expected-returns-by-instrument "perp:TOKENX"]))
        "The user's stated return is used - never a raw short-history mean.")))

(deftest augment-risk-result-appends-no-history-asset-test
  (let [base {:model :diagonal-shrink
              :instrument-ids ["perp:BTC"]
              :covariance [[0.04]]}
        augmented (risk/augment-risk-result-with-assumptions
                   base
                   {"perp:NEW" {:volatility 0.9 :correlation-floor 0.75}})
        cov (:covariance augmented)]
    (is (= ["perp:BTC" "perp:NEW"] (:instrument-ids augmented)))
    (is (near? 0.04 (get-in cov [0 0])) "BTC variance is preserved.")
    (is (near? 0.81 (get-in cov [1 1])) "NEW variance = vol^2 = 0.9^2.")
    (is (near? 0.135 (get-in cov [0 1])) "Synthetic covariance = floor * vol_btc * vol_new = 0.75*0.2*0.9.")
    (is (near? 0.135 (get-in cov [1 0])) "Covariance stays symmetric.")))

(deftest augment-risk-result-overrides-short-history-row-test
  (let [base {:model :diagonal-shrink
              :instrument-ids ["perp:BTC" "perp:NEW"]
              :covariance [[0.04 0.01]
                           [0.01 0.0009]]}
        augmented (risk/augment-risk-result-with-assumptions
                   base
                   {"perp:NEW" {:volatility 0.9 :correlation-floor 0.75}})
        cov (:covariance augmented)]
    (is (= ["perp:BTC" "perp:NEW"] (:instrument-ids augmented))
        "An already-present (short-history) asset is not duplicated.")
    (is (near? 0.04 (get-in cov [0 0])))
    (is (near? 0.81 (get-in cov [1 1])) "Thin realized variance is replaced by the conservative assumption.")
    (is (near? 0.135 (get-in cov [0 1])) "Off-diagonal is replaced by the floored covariance.")))

(deftest augment-risk-result-is-a-noop-without-usable-assumptions-test
  (let [base {:instrument-ids ["perp:BTC"] :covariance [[0.04]]}]
    (is (= base (risk/augment-risk-result-with-assumptions base {})))
    (is (= base (risk/augment-risk-result-with-assumptions
                 base {"perp:NEW" {:volatility nil :correlation-floor 0.75}}))
        "An assumption without a usable volatility is ignored.")))

(deftest augment-risk-result-keeps-the-psd-repair-warning-test
  ;; A conservative volatility assumption plus a correlation floor can push the
  ;; matrix indefinite; repair-psd diagonally loads it back into shape. The
  ;; sibling call site in estimate-risk-model threads that warning through,
  ;; but this one destructured only :covariance and dropped it, so the user was
  ;; never told their covariance had been altered.
  (let [base {:model :diagonal-shrink
              :instrument-ids ["perp:BTC" "perp:ETH"]
              ;; BTC and ETH strongly ANTI-correlated (-0.9), while the floor
              ;; asserts +0.99 from each to the new asset. No such geometry
              ;; exists, so the matrix is indefinite and must be repaired.
              :covariance [[0.04 -0.036]
                           [-0.036 0.04]]
              :warnings []}
        augmented (risk/augment-risk-result-with-assumptions
                   base
                   {"perp:NEW" {:volatility 0.9 :correlation-floor 0.99}})
        conditioning (risk/covariance-conditioning (:covariance augmented))]
    (is (= 3 (count (:instrument-ids augmented))))
    (is (not= :not-positive-semidefinite (:status conditioning))
        "The matrix is repaired, so the published one is usable...")
    (is (some #(= :psd-repair-applied (:code %)) (:warnings augmented))
        "...and the run says the repair happened.")))
