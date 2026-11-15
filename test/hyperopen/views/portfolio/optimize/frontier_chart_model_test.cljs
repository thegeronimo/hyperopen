(ns hyperopen.views.portfolio.optimize.frontier-chart-model-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.optimize.frontier-chart-model :as chart-model]))

(deftest chart-model-includes-current-portfolio-point-in-domains-test
  (let [result {:frontier [{:expected-return 0.04
                            :volatility 0.1}
                           {:expected-return 0.08
                            :volatility 0.2}]
                :frontier-overlays {:standalone []
                                    :contribution []}
                :expected-return 0.08
                :volatility 0.2
                :current-weights [0.4 0.1]
                :current-expected-return 0.5
                :current-volatility 0.9
                :current-performance {:in-sample-sharpe 0.56}}
        model (chart-model/chart-model {} result :none false)]
    (is (= {:expected-return 0.5
            :volatility 0.9
            :sharpe 0.56}
           (:current-point model)))
    (is (<= 0.9 (second (:x-domain model))))
    (is (<= 0.5 (second (:y-domain model))))))

(deftest chart-model-omits-current-portfolio-point-without-current-exposure-test
  (let [result {:frontier [{:expected-return 0.04
                            :volatility 0.1}
                           {:expected-return 0.08
                            :volatility 0.2}]
                :frontier-overlays {:standalone []
                                    :contribution []}
                :expected-return 0.08
                :volatility 0.2
                :current-weights [0 0]
                :current-expected-return 0.5
                :current-volatility 0.9
                :current-performance {:in-sample-sharpe 0.56}}
        model (chart-model/chart-model {} result :none false)]
    (is (nil? (:current-point model)))
    (is (< (second (:x-domain model)) 0.9))
    (is (< (second (:y-domain model)) 0.5))))

(deftest chart-model-includes-current-portfolio-point-from-outside-selected-universe-test
  (let [result {:frontier [{:expected-return 0.04
                            :volatility 0.1}
                           {:expected-return 0.08
                            :volatility 0.2}]
                :frontier-overlays {:standalone []
                                    :contribution []}
                :expected-return 0.08
                :volatility 0.2
                :current-weights [0 0]
                :current-portfolio-weights [0.25]
                :current-expected-return 0.5
                :current-volatility 0.9
                :current-performance {:in-sample-sharpe 0.56}}
        model (chart-model/chart-model {} result :none false)]
    (is (= {:expected-return 0.5
            :volatility 0.9
            :sharpe 0.56}
           (:current-point model)))
    (is (<= 0.9 (second (:x-domain model))))
    (is (<= 0.5 (second (:y-domain model))))))

(def ^:private dual-frontier-result
  {:frontiers {:unconstrained [{:expected-return 0.03 :volatility 0.1}
                               {:expected-return 0.09 :volatility 0.25}]
               :constrained [{:expected-return 0.02 :volatility 0.12}
                             {:expected-return 0.05 :volatility 0.18}
                             {:expected-return 0.07 :volatility 0.3}]}
   :frontier [{:expected-return 0.01 :volatility 0.05}]
   :frontier-overlays {:standalone []
                       :contribution []}
   :expected-return 0.09
   :volatility 0.25})

(deftest frontier-points-selects-unconstrained-when-not-constrained-test
  (is (= (get-in dual-frontier-result [:frontiers :unconstrained])
         (chart-model/frontier-points dual-frontier-result false))))

(deftest frontier-points-selects-constrained-when-constrained-test
  (is (= (get-in dual-frontier-result [:frontiers :constrained])
         (chart-model/frontier-points dual-frontier-result true))))

(deftest frontier-points-falls-back-to-frontier-without-frontiers-map-test
  (let [result (dissoc dual-frontier-result :frontiers)]
    (is (= (:frontier result) (chart-model/frontier-points result true)))
    (is (= (:frontier result) (chart-model/frontier-points result false)))))

(deftest chart-model-renders-selected-frontier-by-constrain-flag-test
  (let [unconstrained-model (chart-model/chart-model {} dual-frontier-result :none false)
        constrained-model (chart-model/chart-model {} dual-frontier-result :none true)]
    (is (= [0.1 0.25] (mapv :volatility (:points unconstrained-model))))
    (is (= [0.12 0.18 0.3] (mapv :volatility (:points constrained-model))))
    (is (not= (:points unconstrained-model) (:points constrained-model)))
    (is (not= (:positions unconstrained-model) (:positions constrained-model)))))
