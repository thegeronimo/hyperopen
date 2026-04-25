(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-tracking-test
  (:require [cljs.test :refer-macros [async deftest is]]
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
                                    {:result {:status :solved
                                              :scenario-id "scn_track"
                                              :instrument-ids ["perp:BTC"]
                                              :target-weights [0.6]
                                              :expected-return 0.18}}}}
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
