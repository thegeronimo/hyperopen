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
