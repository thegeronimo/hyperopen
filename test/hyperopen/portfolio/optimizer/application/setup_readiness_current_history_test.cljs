(ns hyperopen.portfolio.optimizer.application.setup-readiness-current-history-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]))

(defn- readiness-fixture
  [{:keys [current-universe eligible requested-ids]}]
  {:runnable? true
   :request {:requested-universe [{:instrument-id "perp:BTC"}]
             :current-portfolio-universe current-universe
             :current-portfolio-history
             (cond-> {:eligible-instruments (mapv (fn [id] {:instrument-id id})
                                                  eligible)}
               requested-ids
               (assoc :requested-instrument-ids requested-ids))}})

(deftest load-needed-when-holding-was-never-fetched-test
  (is (true? (setup-readiness/current-portfolio-history-load-needed?
              (readiness-fixture
               {:current-universe [{:instrument-id "perp:BTC"}
                                   {:instrument-id "spot:@335"}]
                :eligible ["perp:BTC"]})))))

(deftest load-not-needed-when-unusable-holding-was-already-requested-test
  (is (false? (setup-readiness/current-portfolio-history-load-needed?
               (readiness-fixture
                {:current-universe [{:instrument-id "perp:BTC"}
                                    {:instrument-id "spot:@335"}]
                 :eligible ["perp:BTC"]
                 :requested-ids ["perp:BTC" "spot:@335"]})))))

(deftest load-needed-again-when-a-new-holding-appears-test
  (is (true? (setup-readiness/current-portfolio-history-load-needed?
              (readiness-fixture
               {:current-universe [{:instrument-id "perp:BTC"}
                                   {:instrument-id "spot:@335"}
                                   {:instrument-id "perp:NEW"}]
                :eligible ["perp:BTC"]
                :requested-ids ["perp:BTC" "spot:@335"]})))))

(deftest load-not-needed-when-holdings-subset-of-selection-test
  (is (false? (setup-readiness/current-portfolio-history-load-needed?
               {:runnable? true
                :request {:requested-universe [{:instrument-id "perp:BTC"}]
                          :current-portfolio-universe [{:instrument-id "perp:BTC"}]
                          :current-portfolio-history nil}}))))
