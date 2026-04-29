(ns hyperopen.portfolio.optimizer.frontier-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.frontier-actions :as frontier-actions]))

(deftest set-portfolio-optimizer-frontier-overlay-mode-updates-local-chart-preference-test
  (is (= [[:effects/save [:portfolio-ui :optimizer :frontier-overlay-mode] :standalone]]
         (frontier-actions/set-portfolio-optimizer-frontier-overlay-mode {} :standalone)))
  (is (= [[:effects/save [:portfolio-ui :optimizer :frontier-overlay-mode] :contribution]]
         (frontier-actions/set-portfolio-optimizer-frontier-overlay-mode {} "contribution")))
  (is (= [[:effects/save [:portfolio-ui :optimizer :frontier-overlay-mode] :none]]
         (frontier-actions/set-portfolio-optimizer-frontier-overlay-mode {} :none)))
  (is (= [[:effects/save [:portfolio-ui :optimizer :frontier-overlay-mode] :standalone]]
         (frontier-actions/set-portfolio-optimizer-frontier-overlay-mode {} :wat))))

(deftest set-portfolio-optimizer-constrain-frontier-updates-local-chart-preference-test
  (is (= [[:effects/save [:portfolio-ui :optimizer :constrain-frontier?] true]]
         (frontier-actions/set-portfolio-optimizer-constrain-frontier {} true)))
  (is (= [[:effects/save [:portfolio-ui :optimizer :constrain-frontier?] false]]
         (frontier-actions/set-portfolio-optimizer-constrain-frontier {} false))))
