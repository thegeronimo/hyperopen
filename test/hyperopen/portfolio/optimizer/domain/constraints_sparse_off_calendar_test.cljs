(ns hyperopen.portfolio.optimizer.domain.constraints-sparse-off-calendar-test
  "`sparse-safety-max-weight`'s ladder terminates in nil at >= 60 intervals, which
  left an OFF-CALENDAR member - the deep-window case the sparse lane admits -
  entirely uncapped. Combined with `risk-mixed-frequency/pair-estimate` returning
  covariance exactly 0 for any pair with fewer than 2 shared intervals, that is an
  apparently-uncorrelated, uncapped asset in front of the solver: precisely what a
  max-Sharpe objective concentrates into."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]))

(defn- sparse-cadence
  [interval-count off-calendar?]
  (cond-> {:kind :sparse
           :sparse? true
           :interval-count interval-count}
    off-calendar? (assoc :off-calendar? true)))

(deftest off-calendar-sparse-member-is-capped-above-the-ladder-test
  (testing "64 intervals off-calendar: ladder says nil, the floor still caps at 20%"
    (let [encoded (constraints/encode-constraints
                   {:universe [{:instrument-id "vault:growi"}
                               {:instrument-id "perp:BTC"}]
                    :history {:cadence-by-instrument
                              {"vault:growi" (sparse-cadence 64 true)}}
                    :constraints {:long-only? false
                                  :max-asset-weight 1}})]
      (is (= :ok (:status encoded)))
      (is (= [0.2 1] (:upper-bounds encoded)))
      (let [warning (first (filterv #(= :sparse-history-weight-cap-applied (:code %))
                                    (:warnings encoded)))]
        (is (= 0.2 (:max-weight warning)))
        (is (= (str "sparse history weight cap applied at 20% because its samples "
                    "are too far apart to join the shared daily window.")
               (:message warning)))))))

(deftest on-calendar-sparse-ladder-is-unchanged-test
  (testing "the existing ladder must stay byte-identical for on-calendar members"
    (let [encoded (constraints/encode-constraints
                   {:universe [{:instrument-id "E"}]
                    :history {:cadence-by-instrument {"E" (sparse-cadence 60 false)}}
                    :constraints {:long-only? false
                                  :max-asset-weight 1}})]
      (is (= [1] (:upper-bounds encoded)))
      (is (empty? (filterv #(= :sparse-history-weight-cap-applied (:code %))
                           (:warnings encoded))))))
  (testing "an off-calendar member still inside the ladder uses the ladder tier"
    (let [encoded (constraints/encode-constraints
                   {:universe [{:instrument-id "E"}]
                    :history {:cadence-by-instrument {"E" (sparse-cadence 29 true)}}
                    :constraints {:long-only? false
                                  :max-asset-weight 1}})]
      (is (= [0.1] (:upper-bounds encoded))))))
