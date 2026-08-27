(ns hyperopen.portfolio.optimizer.domain.constraints-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.exposure-reachability
             :as reachability]))

(defn- sparse-cadence
  [interval-count]
  {:kind :sparse
   :sparse? true
   :interval-count interval-count})

(defn- daily-rows
  [n]
  (mapv (fn [idx]
          {:time-ms (* idx 86400000)
           :close (+ 100 idx)})
        (range n)))

(deftest normalize-universe-applies-allowlist-and-blocklist-before-solving-test
  (let [universe [{:instrument-id "A"}
                  {:instrument-id "B"}
                  {:instrument-id "C"}]]
    (is (= [{:instrument-id "A"}]
           (constraints/normalize-universe universe
                                           {:allowlist #{"A" "B"}
                                            :blocklist #{"B"}})))))

(deftest normalize-universe-accepts-vector-filters-from-worker-payload-test
  (let [universe [{:instrument-id "A"}
                  {:instrument-id "B"}
                  {:instrument-id "C"}]]
    (is (= [{:instrument-id "A"}]
           (constraints/normalize-universe universe
                                           {:allowlist ["A" "B"]
                                            :blocklist ["B"]})))))

(deftest normalize-universe-excludes-spot-instruments-unless-included-test
  (let [universe [{:instrument-id "perp:BTC"
                   :market-type :perp}
                  {:instrument-id "spot:PURR"
                   :market-type :spot}
                  {:instrument-id "vault:alpha"
                   :market-type :vault}]]
    (is (= ["perp:BTC" "vault:alpha"]
           (mapv :instrument-id
                 (constraints/normalize-universe universe {}))))
    (is (= ["perp:BTC" "spot:PURR" "vault:alpha"]
           (mapv :instrument-id
                 (constraints/normalize-universe universe
                                                 {:include-spot? true}))))))

(deftest encode-long-only-bounds-applies-global-cap-overrides-and-held-locks-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}
                             {:instrument-id "C"}]
                  :current-weights {"C" 0.2}
                  :constraints {:long-only? true
                                :max-asset-weight 0.6
                                :max-long-weight 0.55
                                :per-asset-overrides {"B" {:max-weight 0.25}}
                                :held-position-locks #{"C"}}})]
    (is (= ["A" "B" "C"] (:instrument-ids encoded)))
    (is (= [0 0 0.2] (:lower-bounds encoded)))
    (is (= [0.55 0.25 0.2] (:upper-bounds encoded)))
    (is (= [{:instrument-id "C"
             :weight 0.2}]
           (:locked-weights encoded)))
    (is (= :ok (:status encoded)))))

(deftest encode-constraints-accepts-vector-held-locks-from-worker-payload-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :current-weights {"B" 0.35}
                  :constraints {:long-only? true
                                :max-asset-weight 0.8
                                :held-position-locks ["B"]}})]
    (is (= [0 0.35] (:lower-bounds encoded)))
    (is (= [0.8 0.35] (:upper-bounds encoded)))
    (is (= [{:instrument-id "B"
             :weight 0.35}]
           (:locked-weights encoded)))))

(deftest presolve-reports-infeasible-long-only-cap-before-solver-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}]
                  :constraints {:long-only? true
                                :max-asset-weight 0.4}})]
    (is (= :infeasible (:status encoded)))
    (is (= [{:code :sum-upper-below-target
             :sum-upper 0.8
             :target-net 1}]
           (:violations encoded)))))

(deftest encode-constraints-defaults-to-signed-perp-bounds-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp}]
                  :constraints {:max-asset-weight 0.8}})]
    (is (= false (:long-only? encoded)))
    (is (nil? (:net-target encoded)))
    (is (= [-0.8] (:lower-bounds encoded)))
    (is (= [0.8] (:upper-bounds encoded)))))

(deftest signed-default-request-constraints-leave-room-for-short-legs-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp}
                             {:instrument-id "perp:ETH"
                              :market-type :perp}
                             {:instrument-id "perp:SOL"
                              :market-type :perp}]
                  :constraints {:long-only? false
                                :gross-leverage 2.0
                                :net-exposure {:min 1.0 :max 1.0}
                                :max-asset-weight 0.5}})]
    (is (= :ok (:status encoded)))
    (is (= false (:long-only? encoded)))
    (is (nil? (:net-target encoded)))
    (is (= [-0.5 -0.5 -0.5] (:lower-bounds encoded)))
    (is (= [0.5 0.5 0.5] (:upper-bounds encoded)))))

(deftest encode-constraints-applies-runtime-sparse-cap-tiers-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"}
                             {:instrument-id "B"}
                             {:instrument-id "C"}
                             {:instrument-id "D"}
                             {:instrument-id "E"}]
                  :history {:cadence-by-instrument
                            {"A" (sparse-cadence 1)
                             "B" (sparse-cadence 7)
                             "C" (sparse-cadence 29)
                             "D" (sparse-cadence 59)
                             "E" (sparse-cadence 60)}}
                  :constraints {:long-only? false
                                :max-asset-weight 1}})]
    (is (= :ok (:status encoded)))
    (is (= [0 0.05 0.1 0.2 1]
           (:upper-bounds encoded)))
    (is (= [:sparse-history-weight-cap-applied
            :sparse-history-weight-cap-applied
            :sparse-history-weight-cap-applied
            :sparse-history-weight-cap-applied]
           (mapv :code (:warnings encoded))))))

(deftest encode-constraints-keeps-runtime-sparse-cap-when-cap-makes-request-infeasible-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "vault:solo"
                              :market-type :vault}]
                  :history {:cadence-by-instrument
                            {"vault:solo" (sparse-cadence 1)}}
                  :constraints {:long-only? true
                                :max-asset-weight 1}})]
    (is (= :infeasible (:status encoded)))
    (is (= [0] (:upper-bounds encoded)))
    (is (= [{:code :sum-upper-below-target
             :sum-upper 0
             :target-net 1}]
           (:violations encoded)))
    (is (= :sparse-history-weight-cap-applied
           (get-in encoded [:warnings 0 :code])))))

(deftest encode-constraints-treats-vault-without-native-cadence-as-sparse-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "vault:manual"
                              :market-type :vault}]
                  :history {:price-series-by-instrument
                            {"vault:manual" (daily-rows 30)}}
                  :constraints {:long-only? false
                                :max-asset-weight 1}})
        warning (first (:warnings encoded))]
    (is (= :ok (:status encoded)))
    (is (= [0.2] (:upper-bounds encoded)))
    (is (= :sparse-history-weight-cap-applied (:code warning)))
    (is (nil? (:interval-count warning)))
    (is (= 0.2 (:max-weight warning)))))

(deftest encode-signed-mode-bounds-supports-gross-and-net-exposure-contract-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A"
                              :instrument-type :perp}
                             {:instrument-id "B"
                              :instrument-type :perp}]
                  :current-weights {"A" 0.3
                                    "B" -0.1}
                  :constraints {:long-only? false
                                :max-asset-weight 0.7
                                :gross-leverage 1.4
                                :max-turnover 0.25
                                :net-exposure {:min -0.2
                                               :max 0.8}}})]
    (is (= [-0.7 -0.7] (:lower-bounds encoded)))
    (is (= [0.7 0.7] (:upper-bounds encoded)))
    (is (= [0.3 -0.1] (:current-weights encoded)))
    (is (= {:max 1.4} (:gross-exposure encoded)))
    (is (= 0.25 (:max-turnover encoded)))
    (is (= {:min -0.2 :max 0.8} (:net-exposure encoded)))))

(deftest encode-shortability-aware-bounds-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:BTC"
                              :instrument-type :perp}
                             {:instrument-id "spot:PURR"
                              :instrument-type :spot}
                             {:instrument-id "vault:alpha"
                              :instrument-type :vault
                              :vault-address "0x1111111111111111111111111111111111111111"}
                             {:instrument-id "unknown:THING"}
                             {:instrument-id "spot:FORCED"
                              :instrument-type :spot}]
                  :constraints {:long-only? false
                                :include-spot? true
                                :max-long-weight 0.6
                                :max-short-weight 0.25
                                :per-asset-overrides
                                {"spot:FORCED" {:shortable? true
                                                :max-short-weight 0.1}}}})]
    (is (= [-0.25 0 0 0 -0.1] (:lower-bounds encoded)))
    (is (= [0.6 0.6 0.2 0.6 0.6] (:upper-bounds encoded)))))

(deftest encode-position-side-bounds-restrict-long-and-short-legs-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:BTC"
                              :instrument-type :perp
                              :position-side :long}
                             {:instrument-id "perp:ARB"
                              :instrument-type :perp
                              :position-side :short}
                             {:instrument-id "spot:PURR"
                              :instrument-type :spot
                              :position-side :short}]
                  :constraints {:long-only? false
                                :include-spot? true
                                :max-long-weight 0.6
                                :max-short-weight 0.25}})]
    (is (= [0 -0.25 0] (:lower-bounds encoded)))
    (is (= [0.6 0 0.6] (:upper-bounds encoded)))
    (is (= :ok (:status encoded)))))

(deftest encode-short-cap-precedence-and-backwards-compatible-max-weight-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:A"
                              :instrument-type :perp}
                             {:instrument-id "perp:B"
                              :instrument-type :perp}
                             {:instrument-id "perp:C"
                              :instrument-type :perp}]
                  :constraints {:long-only? false
                                :max-asset-weight 0.9
                                :max-long-weight 0.8
                                :max-short-weight 0.4
                                :per-asset-overrides
                                {"perp:A" {:max-weight 0.7
                                           :max-long-weight 0.6
                                           :max-short-weight 0.2}
                                 "perp:B" {:max-weight 0.5}
                                 "perp:C" {:max-weight 0.25}}}})]
    (is (= [-0.2 -0.4 -0.25] (:lower-bounds encoded)))
    (is (= [0.6 0.5 0.25] (:upper-bounds encoded)))))

(deftest encode-shortability-overrides-metadata-and-locks-current-weight-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:DISABLED"
                              :instrument-type :perp
                              :shortable? false}
                             {:instrument-id "spot:LOCKED"
                              :instrument-type :spot}]
                  :current-weights {"spot:LOCKED" -0.15}
                  :constraints {:long-only? false
                                :include-spot? true
                                :max-asset-weight 0.5
                                :held-position-locks #{"spot:LOCKED"}}})]
    (is (= [0 -0.15] (:lower-bounds encoded)))
    (is (= [0.5 -0.15] (:upper-bounds encoded)))
    (is (= :infeasible (:status encoded)))
    (is (some #(= :locked-short-non-shortable (:code %))
              (:violations encoded)))))

(deftest encode-gross-and-net-feasibility-violations-test
  (let [gross-too-low (constraints/encode-constraints
                       {:universe [{:instrument-id "perp:A"
                                    :instrument-type :perp
                                    :shortable? true}
                                   {:instrument-id "perp:B"
                                    :instrument-type :perp
                                    :shortable? true}]
                        :constraints {:long-only? false
                                      :gross-leverage 0.5
                                      :net-exposure {:min 1.0
                                                     :max 1.0}}})
        net-outside-bounds (constraints/encode-constraints
                            {:universe [{:instrument-id "spot:A"
                                         :instrument-type :spot}]
                             :constraints {:long-only? false
                                           :max-asset-weight 1.0
                                           :gross-leverage 1.0
                                           :net-exposure {:min -1.0
                                                          :max -0.5}}})
        locked-gross-too-high (constraints/encode-constraints
                               {:universe [{:instrument-id "perp:A"
                                            :instrument-type :perp
                                            :shortable? true}
                                           {:instrument-id "perp:B"
                                            :instrument-type :perp
                                            :shortable? true}]
                                :current-weights {"perp:A" 0.4
                                                  "perp:B" -0.3}
                                :constraints {:long-only? false
                                              :gross-leverage 0.6
                                              :held-position-locks #{"perp:A"
                                                                     "perp:B"}}})]
    (is (= :infeasible (:status gross-too-low)))
    (is (some #(= :gross-below-required-net (:code %))
              (:violations gross-too-low)))
    (is (= :infeasible (:status net-outside-bounds)))
    (is (some #(= :sum-lower-above-net-max (:code %))
              (:violations net-outside-bounds)))
    (is (= :infeasible (:status locked-gross-too-high)))
    (is (some #(= :locked-gross-above-gross-max (:code %))
              (:violations locked-gross-too-high)))))

(deftest encode-invalid-negative-caps-yield-violations-without-inverted-bounds-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:A"
                              :instrument-type :perp}]
                  :constraints {:long-only? false
                                :max-short-weight -0.1
                                :per-asset-overrides
                                {"perp:A" {:max-long-weight -0.2}}}})
        violations (:violations encoded)]
    (is (= :infeasible (:status encoded)))
    (is (= [-1] (:lower-bounds encoded))
        "Invalid negative caps are ignored for bounds rather than inverted.")
    (is (= [1] (:upper-bounds encoded)))
    (is (some #(and (= :invalid-weight-cap (:code %))
                    (= :max-short-weight (:field %)))
              violations))
    (is (some #(and (= :invalid-weight-cap (:code %))
                    (= :max-long-weight (:field %))
                    (= "perp:A" (:instrument-id %)))
              violations))))

(deftest encode-constraints-emits-signed-gross-floor-for-single-signed-universe-test
  ;; Seed-from-current fixes each asset's side, so every bound is single-signed
  ;; and a gross floor is a convex signed-linear inequality: long => +1, short => -1.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:A" :market-type :perp
                              :position-side :long}
                             {:instrument-id "perp:B" :market-type :perp
                              :position-side :short}]
                  :constraints {:long-only? false
                                :gross-floor 1.0
                                :gross-leverage 3.0
                                :max-asset-weight 2.0}})]
    (is (= :ok (:status encoded)))
    (is (= {:min 1.0 :signs [1 -1]} (:gross-floor encoded)))))

(deftest encode-constraints-drops-gross-floor-for-two-sided-universe-test
  ;; Without a fixed side a perp bound straddles zero ([-cap, cap]); gross is then
  ;; not linear in the weights, so the floor must be dropped (nil), not enforced.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:A" :market-type :perp}]
                  :constraints {:long-only? false
                                :gross-floor 1.0
                                :gross-leverage 3.0
                                :max-asset-weight 2.0}})]
    (is (= [-2.0] (:lower-bounds encoded)))
    (is (= [2.0] (:upper-bounds encoded)))
    (is (nil? (:gross-floor encoded)))))

(deftest presolve-reports-gross-floor-above-gross-max-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "perp:A" :market-type :perp
                              :position-side :long}]
                  :constraints {:long-only? false
                                :gross-floor 3.0
                                :gross-leverage 2.0
                                :max-asset-weight 5.0}})]
    (is (= :infeasible (:status encoded)))
    (is (some #(= :gross-floor-above-gross-max (:code %))
              (:violations encoded)))))

(deftest net-band-pct-encodes-spec-with-signed-gross-representation-test
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A" :market-type :perp :position-side :long}
                             {:instrument-id "B" :market-type :perp :position-side :short}]
                  :constraints {:long-only? false
                                :gross-leverage 15.0
                                :net-band-pct 0.05
                                :net-exposure {:min 0.0 :max 0.0}
                                :max-asset-weight 10.0}})]
    (is (= :ok (:status encoded)))
    (is (= {:pct 0.05 :signs [1 -1] :min 0.0 :max 0.0}
           (:net-band-spec encoded))
        "the band rides the single-signed gross representation (realized gross)")
    (is (= [] (:warnings encoded)))))

(deftest net-band-pct-zero-or-missing-encodes-no-spec-test
  (let [base {:universe [{:instrument-id "A" :market-type :perp :position-side :long}
                         {:instrument-id "B" :market-type :perp :position-side :short}]
              :constraints {:long-only? false
                            :gross-leverage 2.0
                            :net-exposure {:min 1.0 :max 1.0}
                            :max-asset-weight 1.0}}]
    (is (nil? (:net-band-spec (constraints/encode-constraints base)))
        "no :net-band-pct key ⇒ no band spec")
    (is (nil? (:net-band-spec (constraints/encode-constraints
                               (assoc-in base [:constraints :net-band-pct] 0.0))))
        "0% must reproduce the exact-net-target behavior")))

(deftest net-band-pct-with-mixed-sign-bounds-drops-band-with-warning-test
  ;; Free-sign bounds make gross non-linear, so the coupled band rows would be
  ;; unsound; the encoder holds the exact net bounds (conservative) and says so.
  (let [encoded (constraints/encode-constraints
                 {:universe [{:instrument-id "A" :market-type :perp}
                             {:instrument-id "B" :market-type :perp}]
                  :constraints {:long-only? false
                                :gross-leverage 2.0
                                :net-band-pct 0.05
                                :net-exposure {:min 0.0 :max 0.0}
                                :max-asset-weight 1.0}})]
    (is (nil? (:net-band-spec encoded)))
    (is (= [:net-band-requires-fixed-sides] (mapv :code (:warnings encoded))))))

(deftest net-band-pct-widens-presolve-feasibility-test
  ;; Side-assigned book: A long (bounds [0, 0.5]), B short (bounds [-10, 0]).
  ;; sum-upper = 0.5 < net target 1.0 ⇒ infeasible with an exact target...
  (let [base {:universe [{:instrument-id "A" :market-type :perp :position-side :long
                          :shortable? true}
                         {:instrument-id "B" :market-type :perp :position-side :short
                          :shortable? true}]
              :constraints {:long-only? false
                            :gross-leverage 10.0
                            :net-exposure {:min 1.0 :max 1.0}
                            :max-asset-weight 10.0
                            :per-asset-overrides {"A" {:max-weight 0.5}}}}
        exact (constraints/encode-constraints base)
        banded (constraints/encode-constraints
                (assoc-in base [:constraints :net-band-pct] 0.1))]
    (is (= :infeasible (:status exact)))
    ;; ... but a 10% band widens the reachable net by pct·capacity, so the
    ;; presolve no longer rejects it outright.
    (is (= :ok (:status banded)))))

;; `gross-floor-spec` returns nil the moment one member's bounds straddle zero,
;; dropping the requested floor for the WHOLE book. No request the app can BUILD
;; reaches that arm - contracts.migrations stamps :position-side on every
;; universe instrument and request_builder migrates before it encodes - which is
;; why the encoded result no longer publishes a "floor set but silently dropped"
;; distinction for the banners to disagree about. This test pins the guarantee
;; that makes the arm unreachable, so a migration that stopped stamping sides
;; fails here rather than silently re-opening it.

(deftest migrated-universes-always-encode-a-signed-box-test
  (let [draft {:schema-version 1
               :universe [{:instrument-id "perp:A" :market-type :perp}
                          {:instrument-id "perp:B" :market-type :perp
                           :shortable? true :position-side :short}
                          {:instrument-id "perp:C" :market-type :perp
                           :position-side "long"}]}
        migrated (contracts/migrate-draft draft)
        encoded (constraints/encode-constraints
                 {:universe (:universe migrated)
                  :constraints {:long-only? false
                                :gross-leverage 2.0
                                :gross-floor 0.5
                                :max-asset-weight 1.0}})]
    (is (every? #(contains? % :position-side) (:universe migrated))
        "migrate-draft stamps a side on EVERY universe instrument")
    (is (some? (reachability/gross-floor-signs (:lower-bounds encoded)
                                               (:upper-bounds encoded)))
        "so no encoded box straddles zero, and no floor is ever dropped")
    (is (= {:min 0.5 :signs [1 -1 1]} (:gross-floor encoded))
        "the requested floor survives encoding for a migrated universe")
    ;; sum of max(|lower|, |upper|): the largest gross the boxes can reach.
    (is (= 3.0 (:gross-capacity encoded)))))
