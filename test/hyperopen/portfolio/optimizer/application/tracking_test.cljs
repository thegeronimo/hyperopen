(ns hyperopen.portfolio.optimizer.application.tracking-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.tracking :as tracking]))

(def solved-run
  {:result {:status :solved
            :scenario-id "scn_track"
            :instrument-ids ["perp:BTC" "perp:ETH"]
            :target-weights [0.6 -0.2]
            :expected-return 0.18
            :volatility 0.32}})

(def current-snapshot
  {:loaded? true
   :capital {:nav-usdc 1000}
   :exposures [{:instrument-id "perp:BTC"
                :signed-notional-usdc 500}
               {:instrument-id "perp:ETH"
                :signed-notional-usdc -100}]})

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 1e-9))

(deftest build-tracking-snapshot-computes-weight-drift-rms-test
  (let [snapshot (tracking/build-tracking-snapshot
                  {:scenario-id "scn_track"
                   :as-of-ms 2000
                   :saved-run solved-run
                   :current-snapshot current-snapshot})]
    (is (= :tracked (:status snapshot)))
    (is (= "scn_track" (:scenario-id snapshot)))
    (is (= 2000 (:as-of-ms snapshot)))
    (is (= 1000 (:nav-usdc snapshot)))
    (is (= 0.18 (:predicted-return snapshot)))
    (is (= 0.32 (:predicted-volatility snapshot)))
    (is (= nil (:realized-return snapshot)))
    (is (< (js/Math.abs (- 0.1 (:max-abs-weight-drift snapshot))) 1e-9))
    (is (< (js/Math.abs (- 0.1 (:weight-drift-rms snapshot))) 1e-9))
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (:rows snapshot))))
    (is (near? -0.1 (get-in snapshot [:rows 0 :weight-drift])))
    (is (near? 0.1 (get-in snapshot [:rows 1 :weight-drift])))))

(deftest append-tracking-snapshot-preserves-existing-series-test
  (let [snapshot (tracking/build-tracking-snapshot
                  {:scenario-id "scn_track"
                   :as-of-ms 2000
                   :saved-run solved-run
                   :current-snapshot current-snapshot})
        tracking-record (tracking/append-tracking-snapshot
                         {:scenario-id "scn_track"
                          :snapshots [{:as-of-ms 1000}]}
                         snapshot)]
    (is (= "scn_track" (:scenario-id tracking-record)))
    (is (= 2000 (:updated-at-ms tracking-record)))
    (is (= [{:as-of-ms 1000} snapshot]
           (:snapshots tracking-record)))))

(deftest append-tracking-snapshot-computes-realized-return-from-baseline-nav-test
  (let [baseline {:scenario-id "scn_track"
                  :as-of-ms 1000
                  :status :tracked
                  :nav-usdc 1000
                  :realized-return nil}
        snapshot {:scenario-id "scn_track"
                  :as-of-ms 2000
                  :status :tracked
                  :nav-usdc 1100
                  :predicted-return 0.18
                  :predicted-volatility 0.32
                  :rows []}
        tracking-record (tracking/append-tracking-snapshot
                         {:scenario-id "scn_track"
                          :snapshots [baseline]}
                         snapshot)]
    (is (near? 0.1
               (get-in tracking-record [:snapshots 1 :realized-return])))
    (is (= 0.18
           (get-in tracking-record [:snapshots 1 :predicted-return])))
    (is (= 0.32
           (get-in tracking-record [:snapshots 1 :predicted-volatility])))))

(deftest build-tracking-snapshot-rejects-missing-current-capital-test
  (let [snapshot (tracking/build-tracking-snapshot
                  {:scenario-id "scn_track"
                   :as-of-ms 2000
                   :saved-run solved-run
                   :current-snapshot {:loaded? true
                                      :capital {:nav-usdc 0}
                                      :exposures []}})]
    (is (= :not-trackable (:status snapshot)))
    (is (= :missing-current-nav
           (get-in snapshot [:warnings 0 :code])))))
