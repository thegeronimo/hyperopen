(ns hyperopen.runtime.effect-adapters.leaderboard-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.leaderboard.cache :as leaderboard-cache]
            [hyperopen.leaderboard.effects :as leaderboard-effects]
            [hyperopen.leaderboard.preferences :as leaderboard-preferences]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.effect-adapters :as effect-adapters]
            [hyperopen.runtime.effect-adapters.leaderboard :as leaderboard-adapters]))

(deftest facade-leaderboard-adapters-delegate-to-leaderboard-module-test
  (is (identical? leaderboard-adapters/api-fetch-leaderboard-effect
                  effect-adapters/api-fetch-leaderboard-effect))
  (is (identical? leaderboard-adapters/persist-leaderboard-preferences-effect
                  effect-adapters/persist-leaderboard-preferences-effect)))

(deftest leaderboard-fetch-adapter-wires-api-cache-and-projection-dependencies-test
  (let [store (atom {})
        calls (atom [])
        opts {:limit 25
              :refresh? true}]
    (with-redefs [leaderboard-effects/api-fetch-leaderboard!
                  (fn [deps]
                    (swap! calls conj deps)
                    :fetch-result)]
      (is (= :fetch-result
             (leaderboard-adapters/api-fetch-leaderboard-effect nil store)))
      (is (= :fetch-result
             (leaderboard-adapters/api-fetch-leaderboard-effect nil store opts))))
    (is (= 2 (count @calls)))
    (let [[default-deps opts-deps] @calls]
      (doseq [deps [default-deps opts-deps]]
        (is (= store (:store deps)))
        (is (identical? api/request-leaderboard!
                        (:request-leaderboard! deps)))
        (is (identical? api/request-vault-index!
                        (:request-vault-index! deps)))
        (is (identical? leaderboard-cache/load-leaderboard-cache-record!
                        (:load-leaderboard-cache-record! deps)))
        (is (identical? leaderboard-cache/persist-leaderboard-cache-record!
                        (:persist-leaderboard-cache-record! deps)))
        (is (identical? api-projections/begin-leaderboard-load
                        (:begin-leaderboard-load deps)))
        (is (identical? api-projections/apply-leaderboard-cache-hydration
                        (:apply-leaderboard-cache-hydration deps)))
        (is (identical? api-projections/apply-leaderboard-success
                        (:apply-leaderboard-success deps)))
        (is (identical? api-projections/apply-leaderboard-error
                        (:apply-leaderboard-error deps)))
        (is (= #{"0x2d1e9d7702fc42a1dc0d19c5a4e46925d5b7d9ac"}
               (:known-excluded-addresses deps)))
        (is (identical? platform/now-ms
                        (:now-ms-fn deps))))
      (is (nil? (:opts default-deps)))
      (is (= opts (:opts opts-deps))))))

(deftest leaderboard-preferences-adapters-delegate-store-state-correctly-test
  (let [persisted-states (atom [])
        restored-stores (atom [])
        store (atom {:leaderboard-ui {:sort :pnl
                                      :page 3}})]
    (with-redefs [leaderboard-preferences/persist-leaderboard-preferences!
                  (fn
                    ([state]
                     (swap! persisted-states conj state)
                     :persisted)
                    ([state _opts]
                     (swap! persisted-states conj state)
                     :persisted))
                  leaderboard-preferences/restore-leaderboard-preferences!
                  (fn
                    ([store*]
                     (swap! restored-stores conj store*)
                     :restored)
                    ([store* _opts]
                     (swap! restored-stores conj store*)
                     :restored))]
      (is (= :persisted
             (leaderboard-adapters/persist-leaderboard-preferences-effect nil store)))
      (is (= :restored
             (leaderboard-adapters/restore-leaderboard-preferences! store))))
    (is (= [{:leaderboard-ui {:sort :pnl
                              :page 3}}]
           @persisted-states))
    (is (= [store] @restored-stores))))
