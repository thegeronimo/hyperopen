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
  (is (= [[:effects/refresh-portfolio-optimizer-tracking]]
         (actions/refresh-portfolio-optimizer-tracking
          {:portfolio {:optimizer {:active-scenario {:status :tracking}}}})))
  (is (= []
         (actions/refresh-portfolio-optimizer-tracking
          {:portfolio {:optimizer {:active-scenario {:status :saved}}}}))))

(deftest enable-manual-tracking-promotes-active-scenario-to-tracking-test
  (is (= [[:effects/enable-portfolio-optimizer-manual-tracking]]
         (actions/enable-portfolio-optimizer-manual-tracking
          {:portfolio {:optimizer {:active-scenario {:loaded-id "scn_01"
                                                     :status :saved}
                                  :draft {:id "scn_01"
                                          :status :saved}}}})))
  (is (= []
         (actions/enable-portfolio-optimizer-manual-tracking
          {:portfolio {:optimizer {:active-scenario {:status :saved}
                                  :draft {:status :saved}}}})))
  (is (= []
         (actions/enable-portfolio-optimizer-manual-tracking
          {:portfolio {:optimizer {:active-scenario {:status :draft}}}}))))
