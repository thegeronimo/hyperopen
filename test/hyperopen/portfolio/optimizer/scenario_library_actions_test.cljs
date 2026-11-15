(ns hyperopen.portfolio.optimizer.scenario-library-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(deftest toggle-scenario-menu-opens-and-refreshes-index-test
  (is (= [[:effects/save contracts/ui-scenario-menu-open-path true]
          [:effects/load-portfolio-optimizer-scenario-index]]
         (actions/toggle-portfolio-optimizer-scenario-menu {}))))

(deftest toggle-scenario-menu-closes-without-reload-test
  (is (= [[:effects/save contracts/ui-scenario-menu-open-path false]]
         (actions/toggle-portfolio-optimizer-scenario-menu
          (assoc-in {} contracts/ui-scenario-menu-open-path true)))))

(deftest close-scenario-menu-test
  (is (= [[:effects/save contracts/ui-scenario-menu-open-path false]]
         (actions/close-portfolio-optimizer-scenario-menu {}))))

(deftest scenario-menu-keydown-closes-on-escape-only-test
  (is (= [[:effects/save contracts/ui-scenario-menu-open-path false]]
         (actions/handle-portfolio-optimizer-scenario-menu-keydown {} "Escape")))
  (is (= [] (actions/handle-portfolio-optimizer-scenario-menu-keydown {} "Enter"))))

(deftest new-scenario-closes-menu-and-resets-draft-test
  (is (= [[:effects/save contracts/ui-scenario-menu-open-path false]
          [:effects/reset-portfolio-optimizer-draft]]
         (actions/new-portfolio-optimizer-scenario {}))))
