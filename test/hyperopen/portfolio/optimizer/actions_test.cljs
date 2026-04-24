(ns hyperopen.portfolio.optimizer.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(deftest run-portfolio-optimizer-emits-registered-worker-effect-test
  (let [request {:scenario-id "scenario-1"
                 :objective {:kind :minimum-variance}}
        signature {:scenario-id "scenario-1"
                   :revision 4}]
    (is (= [[:effects/run-portfolio-optimizer request signature]]
           (actions/run-portfolio-optimizer {} request signature)))))

(deftest set-draft-model-layer-actions-update-draft-and-mark-dirty-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :objective]
                                {:kind :max-sharpe}]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-objective-kind
          {}
          "maxSharpe")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :return-model]
                                {:kind :black-litterman
                                 :views []}]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-return-model-kind
          {}
          :black-litterman)))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :risk-model]
                                {:kind :sample-covariance}]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-risk-model-kind
          {}
          "sampleCovariance"))))

(deftest set-draft-model-layer-actions-ignore-invalid-kinds-test
  (is (= []
         (actions/set-portfolio-optimizer-objective-kind {} "not-real")))
  (is (= []
         (actions/set-portfolio-optimizer-return-model-kind {} "not-real")))
  (is (= []
         (actions/set-portfolio-optimizer-risk-model-kind {} "not-real"))))

(deftest set-draft-constraint-normalizes-supported-values-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :max-asset-weight]
                                0.42]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-constraint
          {}
          :max-asset-weight
          "0.42")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :long-only?]
                                true]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-constraint
          {}
          :long-only?
          true)))
  (is (= []
         (actions/set-portfolio-optimizer-constraint
          {}
          :gross-max
          "not-a-number"))))
