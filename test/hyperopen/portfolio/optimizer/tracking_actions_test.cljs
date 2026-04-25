(ns hyperopen.portfolio.optimizer.tracking-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(deftest refresh-tracking-dispatches-only-for-executed-scenarios-test
  (is (= [[:effects/refresh-portfolio-optimizer-tracking]]
         (actions/refresh-portfolio-optimizer-tracking
          {:portfolio {:optimizer {:active-scenario {:status :executed}}}})))
  (is (= [[:effects/refresh-portfolio-optimizer-tracking]]
         (actions/refresh-portfolio-optimizer-tracking
          {:portfolio {:optimizer {:active-scenario {:status :partially-executed}}}})))
  (is (= []
         (actions/refresh-portfolio-optimizer-tracking
          {:portfolio {:optimizer {:active-scenario {:status :saved}}}}))))
