(ns hyperopen.domain.trading.indicators.registry-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.domain.trading.indicators.registry :as registry]
            [hyperopen.domain.trading.indicators.result :as result]))

(defn- reset-registry-fixture
  [f]
  (registry/reset-registered-domain-families!)
  (f)
  (registry/reset-registered-domain-families!))

(use-fixtures :each reset-registry-fixture)

(deftest register-domain-family-extension-test
  (let [family {:id :test-family
                :get-indicators (fn []
                                  [{:id :test-indicator
                                    :name "Test Indicator"
                                    :short-name "TEST"
                                    :description "Test extension"
                                    :supports-period? false
                                    :default-config {}}])
                :calculate-indicator (fn [indicator-type _data _params]
                                       (when (= indicator-type :test-indicator)
                                         (result/indicator-result :test-indicator
                                                                  :separate
                                                                  [(result/line-series :test [1.0 nil])])))}]
    (is (nil? (registry/calculate-domain-indicator :test-indicator [] {})))
    (registry/register-domain-family! family)
    (let [definitions (registry/get-domain-indicators)
          calc-result (registry/calculate-domain-indicator :test-indicator [{:time 1}] {})]
      (is (some #(= :test-indicator (:id %)) definitions))
      (is (some? calc-result))
      (is (= :test-indicator (:type calc-result)))
      (is (= :separate (:pane calc-result)))
      (is (= :test (get-in calc-result [:series 0 :id]))))))

(deftest register-domain-family-invalid-input-test
  (let [count-before (count (registry/get-domain-indicators))]
    (is (nil? (registry/register-domain-family! {:id :broken-family})))
    (is (= count-before
           (count (registry/get-domain-indicators))))))
