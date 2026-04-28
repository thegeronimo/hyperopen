(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-tracking-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as portfolio-optimizer-adapters]
            [hyperopen.test-support.async :as async-support]))

(deftest refresh-portfolio-optimizer-tracking-effect-persists-snapshot-test
  (async done
    (let [saved-records (atom [])
          ticks (atom [2000])
          store (atom {:portfolio {:optimizer
                                    {:active-scenario {:loaded-id "scn_track"
                                                      :status :executed}
                                    :last-successful-run
                                    (fixtures/sample-last-successful-run
                                     {:result {:scenario-id "scn_track"
                                               :instrument-ids ["perp:BTC"]
                                               :target-weights [0.6]
                                               :target-weights-by-instrument {"perp:BTC" 0.6}
                                               :expected-return 0.18}})}}
                       :webdata2 {:clearinghouseState
                                  {:marginSummary {:accountValue "1000"}
                                   :assetPositions
                                   [{:position {:coin "BTC"
                                                :szi "0.5"
                                                :positionValue "500"}}]}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms*
                    (fn []
                      (let [t (first @ticks)]
                        (swap! ticks rest)
                        t))
                    portfolio-optimizer-adapters/*load-tracking!*
                    (fn [scenario-id]
                      (is (= "scn_track" scenario-id))
                      (js/Promise.resolve {:scenario-id "scn_track"
                                           :snapshots [{:as-of-ms 1000}]}))
                    portfolio-optimizer-adapters/*save-tracking!*
                    (fn [scenario-id tracking-record]
                      (swap! saved-records conj [scenario-id tracking-record])
                      (js/Promise.resolve nil))]
        (-> (portfolio-optimizer-adapters/refresh-portfolio-optimizer-tracking-effect
             nil
             store)
            (.then
             (fn [tracking-record]
               (let [[saved-scenario-id saved-record] (first @saved-records)]
                 (is (= "scn_track" saved-scenario-id))
                 (is (= 2 (count (:snapshots saved-record))))
                 (is (= :tracked
                        (get-in saved-record [:snapshots 1 :status])))
                 (is (= saved-record tracking-record))
                 (is (= saved-record
                        (get-in @store [:portfolio :optimizer :tracking])))
                 (done))))
            (.catch (async-support/unexpected-error done)))))))

(deftest enable-portfolio-optimizer-manual-tracking-effect-persists-scenario-status-test
  (async done
    (let [saved-scenarios (atom [])
          saved-indexes (atom [])
          address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          scenario-record {:schema-version 1
                           :id "scn_track"
                           :name "Saved Tracking Candidate"
                           :address address
                           :status :saved
                           :config {:id "scn_track"
                                    :name "Saved Tracking Candidate"
                                    :status :saved
                                    :objective {:kind :max-sharpe}
                                    :return-model {:kind :historical-mean}
                                    :risk-model {:kind :diagonal-shrink}
                                    :metadata {:dirty? false}}
                           :saved-run (fixtures/sample-last-successful-run
                                       {:computed-at-ms 1000})
                           :created-at-ms 1000
                           :updated-at-ms 1000}
          loaded-index {:ordered-ids ["scn_track"]
                        :by-id {"scn_track" {:id "scn_track"
                                             :status :saved}}}
          store (atom {:wallet {:address address}
                       :portfolio {:optimizer
                                   {:active-scenario {:loaded-id "scn_track"
                                                      :status :saved}
                                    :draft {:id "scn_track"
                                            :status :saved}
                                    :scenario-index loaded-index
                                    :tracking {:scenario-id "old"
                                               :snapshots [{:scenario-id "old"}]}}}})]
      (with-redefs [portfolio-optimizer-adapters/*now-ms* (fn [] 5000)
                    portfolio-optimizer-adapters/*load-scenario!*
                    (fn [scenario-id]
                      (is (= "scn_track" scenario-id))
                      (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*load-scenario-index!*
                    (fn [loaded-address]
                      (is (= address loaded-address))
                      (js/Promise.resolve loaded-index))
                    portfolio-optimizer-adapters/*save-scenario!*
                    (fn [scenario-id record]
                      (swap! saved-scenarios conj [scenario-id record])
                      (js/Promise.resolve nil))
                    portfolio-optimizer-adapters/*save-scenario-index!*
                    (fn [address index]
                      (swap! saved-indexes conj [address index])
                      (js/Promise.resolve nil))]
        (-> (portfolio-optimizer-adapters/enable-portfolio-optimizer-manual-tracking-effect
             nil
             store)
            (.then
             (fn [updated-record]
               (is (= :tracking (:status updated-record)))
               (is (= :tracking (get-in updated-record [:config :status])))
               (is (= 5000 (:updated-at-ms updated-record)))
               (is (= [["scn_track" updated-record]]
                      @saved-scenarios))
               (is (= address (ffirst @saved-indexes)))
               (is (= :tracking
                      (get-in (second (first @saved-indexes))
                              [:by-id "scn_track" :status])))
               (is (= :tracking
                      (get-in @store [:portfolio :optimizer :active-scenario :status])))
               (is (= :tracking
                      (get-in @store [:portfolio :optimizer :draft :status])))
               (is (= {:status :idle
                       :scenario-id "scn_track"
                       :updated-at-ms nil
                       :snapshots []
                       :error nil}
                      (get-in @store [:portfolio :optimizer :tracking])))
               (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-scenario-effect-hydrates-tracking-record-test
  (async done
    (let [scenario-record {:schema-version 1
                           :id "scn_track"
                           :name "Tracked Scenario"
                           :status :executed
                           :config {:id "scn_track"
                                    :name "Tracked Scenario"
                                    :objective {:kind :max-sharpe}
                                    :return-model {:kind :historical-mean}
                                    :risk-model {:kind :diagonal-shrink}
                                    :metadata {:dirty? false}}
                           :saved-run (fixtures/sample-last-successful-run
                                       {:computed-at-ms 2000
                                        :result {:instrument-ids ["perp:BTC"]
                                                 :target-weights [0.6]
                                                 :target-weights-by-instrument {"perp:BTC" 0.6}}})
                           :updated-at-ms 3000}
          tracking-record {:scenario-id "scn_track"
                           :updated-at-ms 4000
                           :snapshots [{:scenario-id "scn_track"
                                        :as-of-ms 4000
                                        :status :tracked
                                        :weight-drift-rms 0.1}]}
          store (atom {:portfolio {:optimizer {}}})
          calls (atom [])]
      (with-redefs [portfolio-optimizer-adapters/*load-scenario!*
                    (fn [scenario-id]
                      (swap! calls conj [:load-scenario scenario-id])
                      (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*load-tracking!*
                    (fn [scenario-id]
                      (swap! calls conj [:load-tracking scenario-id])
                      (js/Promise.resolve tracking-record))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 4100)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-effect
             nil
             store
             "scn_track")
            (.then
             (fn [loaded-record]
               (is (= scenario-record loaded-record))
               (is (= [[:load-scenario "scn_track"]
                       [:load-tracking "scn_track"]]
                      @calls))
               (is (= tracking-record
                      (get-in @store [:portfolio :optimizer :tracking])))
               (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-portfolio-optimizer-scenario-effect-clears-stale-tracking-test
  (async done
    (let [scenario-record {:schema-version 1
                           :id "scn_without_tracking"
                           :name "Untracked Scenario"
                           :status :executed
                           :config {:id "scn_without_tracking"
                                    :name "Untracked Scenario"
                                    :objective {:kind :max-sharpe}
                                    :return-model {:kind :historical-mean}
                                    :risk-model {:kind :diagonal-shrink}
                                    :metadata {:dirty? false}}
                           :saved-run (fixtures/sample-last-successful-run
                                       {:computed-at-ms 2000})
                           :updated-at-ms 3000}
          store (atom {:portfolio
                       {:optimizer
                        {:tracking {:scenario-id "scn_previous"
                                    :updated-at-ms 1000
                                    :snapshots [{:scenario-id "scn_previous"}]}}}})]
      (with-redefs [portfolio-optimizer-adapters/*load-scenario!*
                    (fn [_scenario-id]
                      (js/Promise.resolve scenario-record))
                    portfolio-optimizer-adapters/*load-tracking!*
                    (fn [_scenario-id]
                      (js/Promise.resolve nil))
                    portfolio-optimizer-adapters/*now-ms* (fn [] 4100)]
        (-> (portfolio-optimizer-adapters/load-portfolio-optimizer-scenario-effect
             nil
             store
             "scn_without_tracking")
            (.then
             (fn [_loaded-record]
               (is (= {:status :idle
                       :scenario-id "scn_without_tracking"
                       :updated-at-ms nil
                       :snapshots []
                       :error nil}
                      (get-in @store [:portfolio :optimizer :tracking])))
               (done)))
            (.catch (async-support/unexpected-error done)))))))
