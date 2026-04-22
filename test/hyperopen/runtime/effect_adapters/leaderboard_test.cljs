(ns hyperopen.runtime.effect-adapters.leaderboard-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.leaderboard.cache :as leaderboard-cache]
            [hyperopen.leaderboard.effects :as leaderboard-effects]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.leaderboard :as adapters]))

(deftest facade-leaderboard-adapters-delegate-to-leaderboard-module-test
  (is (identical? adapters/api-fetch-leaderboard-effect
                  effect-adapters/api-fetch-leaderboard-effect))
  (is (identical? adapters/persist-leaderboard-preferences-effect
                  effect-adapters/persist-leaderboard-preferences-effect)))

(deftest api-fetch-leaderboard-effect-wires-production-dependencies-test
  (let [store (atom {})
        captured (atom nil)
        opts {:force-refresh? true}]
    (with-redefs [leaderboard-effects/api-fetch-leaderboard!
                  (fn [deps]
                    (reset! captured deps)
                    :leaderboard-result)]
      (is (= :leaderboard-result
             (adapters/api-fetch-leaderboard-effect nil store opts))))
    (is (= store (:store @captured)))
    (is (= opts (:opts @captured)))
    (is (identical? api/request-leaderboard!
                    (:request-leaderboard! @captured)))
    (is (identical? api/request-vault-index!
                    (:request-vault-index! @captured)))
    (is (identical? leaderboard-cache/load-leaderboard-cache-record!
                    (:load-leaderboard-cache-record! @captured)))
    (is (identical? leaderboard-cache/persist-leaderboard-cache-record!
                    (:persist-leaderboard-cache-record! @captured)))
    (is (identical? api-projections/begin-leaderboard-load
                    (:begin-leaderboard-load @captured)))
    (is (identical? api-projections/apply-leaderboard-cache-hydration
                    (:apply-leaderboard-cache-hydration @captured)))
    (is (identical? api-projections/apply-leaderboard-success
                    (:apply-leaderboard-success @captured)))
    (is (identical? api-projections/apply-leaderboard-error
                    (:apply-leaderboard-error @captured)))
    (is (= #{"0x2d1e9d7702fc42a1dc0d19c5a4e46925d5b7d9ac"}
           (:known-excluded-addresses @captured)))
    (is (identical? platform/now-ms
                    (:now-ms-fn @captured)))))

(deftest api-fetch-leaderboard-effect-respects-route-gate-with-real-boundary-test
  (async done
    (let [store (atom {:router {:path "/trade"}})]
      (-> (adapters/api-fetch-leaderboard-effect nil store)
          (.then (fn [result]
                   (is (nil? result))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected leaderboard route-gate error: " err))
                    (done)))))))

(deftest leaderboard-preference-effects-use-real-preference-boundaries-test
  (async done
    (let [store (atom {:leaderboard-ui {:timeframe :week
                                        :sort {:column :roi
                                               :direction :asc}
                                        :page-size 25}})
          persist-result (adapters/persist-leaderboard-preferences-effect nil store)]
      (is (instance? js/Promise persist-result))
      (-> persist-result
          (.then (fn [persisted?]
                   (is (boolean? persisted?))
                   (let [restore-result (adapters/restore-leaderboard-preferences! store)]
                     (is (instance? js/Promise restore-result))
                     restore-result)))
          (.then (fn [record]
                   (when record
                     (is (= :week (:timeframe record)))
                     (is (= {:column :roi
                             :direction :asc}
                            (:sort record)))
                     (is (= 25 (:page-size record))))
                   (is (= {:timeframe :week
                           :sort {:column :roi
                                  :direction :asc}
                           :page-size 25}
                          (select-keys (get @store :leaderboard-ui)
                                       [:timeframe :sort :page-size])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected preference adapter error: " err))
                    (done)))))))
