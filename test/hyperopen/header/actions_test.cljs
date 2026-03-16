(ns hyperopen.header.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.header.actions :as actions]))

(deftest mobile-header-open-and-close-actions-save-deterministic-state-test
  (is (= [[:effects/save [:header-ui :mobile-menu-open?] true]]
         (actions/open-mobile-header-menu {})))
  (is (= [[:effects/save [:header-ui :mobile-menu-open?] false]]
         (actions/close-mobile-header-menu {}))))

(deftest navigate-mobile-header-menu-closes-before-route-transition-test
  (is (= [[:effects/save [:header-ui :mobile-menu-open?] false]
          [:effects/save [:router :path] "/trade"]
          [:effects/push-state "/trade"]
          [:effects/load-trade-chart-module]]
         (actions/navigate-mobile-header-menu {} "/trade"))))

(deftest open-spectate-mode-mobile-header-menu-closes-before-opening-modal-test
  (let [bounds {:left 10 :top 20 :right 30 :bottom 40 :width 20 :height 20}
        effects (actions/open-spectate-mode-mobile-header-menu
                 {:account-context {:spectate-ui {}
                                    :watchlist []}}
                 bounds)
        first-effect (first effects)
        second-effect (second effects)
        saved-path-values (second second-effect)]
    (is (= [:effects/save [:header-ui :mobile-menu-open?] false]
           first-effect))
    (is (= :effects/save-many (first second-effect)))
    (is (= true
           (some (fn [[path value]]
                   (and (= path [:account-context :spectate-ui :modal-open?])
                        (= true value)))
                 saved-path-values)))
    (is (= bounds
           (some (fn [[path value]]
                   (when (= path [:account-context :spectate-ui :anchor])
                     value))
                 saved-path-values)))))
