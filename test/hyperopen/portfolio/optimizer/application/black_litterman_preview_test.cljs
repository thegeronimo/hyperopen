(ns hyperopen.portfolio.optimizer.application.black-litterman-preview-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.black-litterman-preview]))

(def ^:private build-preview
  (resolve 'hyperopen.portfolio.optimizer.application.black-litterman-preview/build-preview))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(defn- ready-request
  [views]
  {:status :ready
   :request {:universe [{:instrument-id "A"}
                        {:instrument-id "B"}]
             :return-model {:kind :black-litterman
                            :views views}
             :risk-model {:kind :sample-covariance}
             :history {:return-series-by-instrument {"A" [1 2 3]
                                                     "B" [2 4 6]}}
             :black-litterman-prior {:source :market-cap
                                     :weights-by-instrument {"A" 0.6
                                                             "B" 0.4}}}})

(deftest build-preview-returns-unavailable-when-no-eligible-request-exists-test
  (is (some? build-preview))
  (when build-preview
    (is (= {:status :unavailable
            :reason :no-eligible-request}
           (select-keys (build-preview {:status :blocked
                                        :reason :incomplete-history})
                        [:status :reason])))))

(deftest build-preview-returns-empty-when-no-views-exist-test
  (is (some? build-preview))
  (when build-preview
    (is (= {:status :empty
            :view-count 0}
           (select-keys (build-preview (ready-request []))
                        [:status :view-count])))))

(deftest build-preview-returns-prior-and-posterior-rows-when-readiness-request-is-available-test
  (is (some? build-preview))
  (when build-preview
    (let [preview (build-preview
                   (ready-request
                    [{:id "view-1"
                      :kind :relative
                      :instrument-id "A"
                      :comparator-instrument-id "B"
                      :direction :outperform
                      :return 0.1
                      :confidence 0.5
                      :confidence-variance 1
                      :weights {"A" 1
                                "B" -1}}]))]
      (is (= :ready (:status preview)))
      (is (= ["A" "B"] (mapv :instrument-id (:rows preview))))
      (is (near? 0.6 (get-in preview [:rows 0 :prior-return])))
      (is (near? 0.4 (get-in preview [:rows 1 :prior-return])))
      (is (near? 0.5666666667 (get-in preview [:rows 0 :posterior-return])))
      (is (near? 0.4333333333 (get-in preview [:rows 1 :posterior-return]))))))
