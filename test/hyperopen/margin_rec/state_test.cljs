(ns hyperopen.margin-rec.state-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.margin-rec.state :as state]))

(def now 1780000000000)

(def xyz-position
  {:coin "xyz:TSM"
   :szi "0.36"
   :entryPx "446.441"
   :positionValue "157.5"
   :liquidationPx "424.20"
   :marginUsed "12.42"
   :maxLeverage 10
   :leverage {:type "isolated" :value 10}})

(def cross-position
  {:coin "AAPL"
   :szi "0.126"
   :positionValue "39.66"
   :marginUsed "19.83"
   :leverage {:type "cross" :value 2}})

(defn base-state
  []
  {:webdata2 {:clearinghouseState {:assetPositions [{:position cross-position}]
                                   :withdrawable "800"}}
   :perp-dex-clearinghouse {"xyz" {:assetPositions [{:position xyz-position}]
                                   :withdrawable "500"}}
   :asset-selector {:market-by-key
                    {"perp:xyz:TSM" {:market-type :perp
                                     :coin "xyz:TSM"
                                     :dex "xyz"
                                     :asset-id 100001
                                     :fundingRate 0.0000125
                                     :maxLeverage 10}}}
   :trading-settings {}
   :margin-rec (state/default-state)
   :candles {}})

(defn- fresh-candles
  [n]
  (mapv (fn [i]
          (let [t (- now (* (- n i) 3600000))]
            {:t t :o 440 :c 440 :l 438 :h 442}))
        (range n)))

(deftest isolated-position-detection
  (is (true? (state/isolated-position? xyz-position)))
  (is (false? (state/isolated-position? cross-position)))
  (is (true? (state/isolated-position? {:leverage {:type "strictIsolated"}})))
  (is (true? (state/isolated-position? {:isCross false})))
  (is (false? (state/isolated-position? {:leverage {:type "cross"}}))))

(deftest isolated-positions-extraction
  (let [entries (state/isolated-positions (base-state))]
    (is (= 1 (count entries)))
    (let [entry (first entries)]
      (is (= "xyz:TSM|xyz" (:position-key entry)))
      (is (= "xyz" (:dex entry)))
      (is (= 0.36 (:szi entry)))
      (is (< (js/Math.abs (- (:mark entry) (/ 157.5 0.36))) 1e-9))
      (is (= 12.42 (:equity entry)))
      (is (= 424.2 (:liquidation-px entry)))
      (is (= 10 (:max-leverage entry)))
      (is (= 0.0000125 (:funding-rate-hourly entry))))))

(deftest input-sig-buckets-mark-noise
  (let [entry (first (state/isolated-positions (base-state)))
        tiny-move (assoc entry :mark (* (:mark entry) 0.9995))
        big-move (assoc entry :mark (* (:mark entry) 1.02))]
    (is (= (state/input-sig entry 123)
           (state/input-sig tiny-move 123)))
    (is (not= (state/input-sig entry 123)
              (state/input-sig big-move 123)))))

(deftest plan-work-fetches-then-computes
  (testing "no isolated positions -> nothing to do"
    (let [empty-state (assoc (base-state)
                             :perp-dex-clearinghouse {}
                             :webdata2 {})
          plan (state/plan-work empty-state "0xabc" now)]
      (is (empty? (:candle-coins plan)))
      (is (nil? (:fills-address plan)))
      (is (nil? (:job plan)))))
  (testing "position without candles -> candle fetch + fills fetch, no job"
    (let [plan (state/plan-work (base-state) "0xabc" now)]
      (is (= ["xyz:TSM"] (:candle-coins plan)))
      (is (= "0xabc" (:fills-address plan)))
      (is (nil? (:job plan)))))
  (testing "candle request cooldown suppresses refetch"
    (let [requested (assoc-in (base-state)
                              (conj state/candle-requests-path "xyz:TSM")
                              (- now 1000))
          plan (state/plan-work requested "0xabc" now)]
      (is (empty? (:candle-coins plan)))))
  (testing "fresh candles -> compute job with full inputs"
    (let [ready (assoc-in (base-state)
                          [:candles "xyz:TSM" state/candle-interval]
                          (fresh-candles 80))
          plan (state/plan-work ready nil now)
          job (:job plan)]
      (is (empty? (:candle-coins plan)))
      (is (some? job))
      (is (= "xyz:TSM|xyz" (:key job)))
      (is (= 0.36 (get-in job [:inputs :szi])))
      (is (= 12.42 (get-in job [:inputs :margin-used])))
      (is (= :balanced (get-in job [:inputs :risk-mode])))
      (is (= 80 (count (get-in job [:inputs :candle-rows]))))))
  (testing "an in-flight compute blocks new jobs"
    (let [ready (-> (base-state)
                    (assoc-in [:candles "xyz:TSM" state/candle-interval]
                              (fresh-candles 80))
                    (assoc-in state/computing-path {:key "other" :started-at now}))]
      (is (nil? (:job (state/plan-work ready nil now))))))
  (testing "recent result with only a mark-bucket change respects the interval"
    (let [entry (first (state/isolated-positions (base-state)))
          ready (assoc-in (base-state)
                          [:candles "xyz:TSM" state/candle-interval]
                          (fresh-candles 80))
          sig (state/input-sig entry 0)
          with-recent (assoc-in ready
                                (conj state/recs-path "xyz:TSM|xyz")
                                {:status :ok
                                 :input-sig sig
                                 :structural-sig (state/structural-sig entry)
                                 :computed-at (- now 1000)})
          with-old (assoc-in with-recent
                             (conj state/recs-path "xyz:TSM|xyz" :computed-at)
                             (- now 600000))]
      (is (nil? (:job (state/plan-work with-recent nil now))))
      (is (some? (:job (state/plan-work with-old nil now))))))
  (testing "structural change recomputes immediately"
    (let [entry (first (state/isolated-positions (base-state)))
          ready (assoc-in (base-state)
                          [:candles "xyz:TSM" state/candle-interval]
                          (fresh-candles 80))
          with-recent (assoc-in ready
                                (conj state/recs-path "xyz:TSM|xyz")
                                {:status :ok
                                 :input-sig [:different]
                                 :structural-sig [999 999]
                                 :computed-at (- now 1000)})]
      (is (some? (:job (state/plan-work with-recent nil now))))
      (is (= (state/structural-sig entry)
             (:structural-sig (:job (state/plan-work with-recent nil now))))))))

(deftest plan-work-prunes-closed-positions
  (let [stale (assoc-in (base-state)
                        (conj state/recs-path "GONE|default")
                        {:status :ok})]
    (is (= ["GONE|default"] (:prune-keys (state/plan-work stale nil now))))))

(deftest watch-fingerprint-ignores-in-bucket-ticks
  (let [a (base-state)
        b (assoc-in a
                    [:perp-dex-clearinghouse "xyz" :assetPositions 0
                     :position :positionValue]
                    "157.45")
        c (assoc-in a
                    [:perp-dex-clearinghouse "xyz" :assetPositions 0
                     :position :positionValue]
                    "170.0")]
    (is (= (state/watch-fingerprint a) (state/watch-fingerprint b)))
    (is (not= (state/watch-fingerprint a) (state/watch-fingerprint c)))))

(deftest fills-and-result-application
  (let [job {:key "xyz:TSM|xyz" :input-sig [:sig] :structural-sig [:s]}
        with-fills (state/apply-fills (base-state)
                                      "0xabc"
                                      [{:coin "TSM" :time 1 :side "B" :sz "1"
                                        :startPosition "0" :px "10" :extra 1}]
                                      now)
        computing (state/apply-computing with-fills job now)
        done (state/apply-result computing job {:status :ok :p-now 0.1} now)
        failed (state/apply-compute-error computing job "boom" now)]
    (is (= :ready (get-in with-fills (conj state/fills-path :status))))
    (is (= [{:coin "TSM" :time 1 :side "B" :sz "1" :startPosition "0"}]
           (state/fills-rows with-fills)))
    (is (= "xyz:TSM|xyz" (:key (get-in computing state/computing-path))))
    (is (nil? (get-in done state/computing-path)))
    (is (= :ok (:status (state/rec-for done "xyz:TSM|xyz"))))
    (is (= [:sig] (:input-sig (state/rec-for done "xyz:TSM|xyz"))))
    (is (= :error (:status (state/rec-for failed "xyz:TSM|xyz"))))
    (is (= "boom" (:error (state/rec-for failed "xyz:TSM|xyz"))))))

(deftest intent-matching
  (let [intent (state/make-intent {:position-key "xyz:TSM|xyz"
                                   :coin "xyz:TSM"
                                   :dex "xyz"
                                   :expected-size 0.36
                                   :target-equity 18.64
                                   :source :trade}
                                  now)]
    (is (= :pending (:status intent)))
    (is (= (+ now state/intent-ttl-ms) (:expires-at intent)))
    (is (true? (state/intent-matches-position? intent {:szi 0.36})))
    (is (true? (state/intent-matches-position? intent {:szi -0.3564})))
    (is (false? (state/intent-matches-position? intent {:szi 0.2})))
    (is (false? (state/intent-matches-position? intent {:szi 0.5})))))

(deftest ready-recommended-equity-reads-completed-recs
  (let [with-rec (assoc-in (base-state)
                           (conj state/recs-path "xyz:TSM|xyz")
                           {:status :ok
                            :result {:recommended {:equity 18.64}}})]
    (is (= 18.64 (state/ready-recommended-equity with-rec "xyz:TSM|xyz")))
    (is (nil? (state/ready-recommended-equity with-rec "missing")))))

(def ^:private by-mode-result
  {:p-now 0.146
   :risk-level :high
   :recommended {:equity 18.64 :additional 6.22}
   :status :ok
   :by-risk-mode {:conservative {:status :ok
                                 :p-after 0.01
                                 :recommended {:equity 21.0 :additional 8.58}}
                  :balanced {:status :ok
                             :p-after 0.021
                             :recommended {:equity 18.64 :additional 6.22}}
                  :capital-efficient {:status :within-target
                                      :p-after 0.05
                                      :recommended {:equity 15.1 :additional 2.68}}}})

(deftest select-risk-mode-projects-onto-precomputed-mode
  (testing "each mode reads its own alpha-dependent fields, shared fields stay"
    (is (= 21.0 (get-in (state/select-risk-mode by-mode-result :conservative)
                        [:recommended :equity])))
    (is (= 15.1 (get-in (state/select-risk-mode by-mode-result :capital-efficient)
                        [:recommended :equity])))
    (is (= :within-target
           (:status (state/select-risk-mode by-mode-result :capital-efficient))))
    (is (= 0.146 (:p-now (state/select-risk-mode by-mode-result :conservative))))
    (is (= :high (:risk-level (state/select-risk-mode by-mode-result :conservative)))))
  (testing "conservative demands more collateral than capital-efficient"
    (is (> (get-in (state/select-risk-mode by-mode-result :conservative)
                   [:recommended :equity])
           (get-in (state/select-risk-mode by-mode-result :capital-efficient)
                   [:recommended :equity]))))
  (testing "falls back to the stored active mode when the table is absent"
    (let [legacy {:recommended {:equity 9.0} :status :ok}]
      (is (= 9.0 (get-in (state/select-risk-mode legacy :conservative)
                         [:recommended :equity]))))))

(deftest ready-recommended-equity-honors-active-mode
  (let [with-rec (assoc-in (base-state)
                           (conj state/recs-path "xyz:TSM|xyz")
                           {:status :ok :result by-mode-result})
        conservative (assoc-in with-rec
                               [:trading-settings :margin-rec-risk-mode]
                               :conservative)]
    (is (= 21.0 (state/ready-recommended-equity conservative "xyz:TSM|xyz")))))

(def high-risk-result
  {:status :ok
   :risk-level :high
   :p-now 0.14
   :p-after 0.02
   :recommended {:equity 18.64 :additional 6.22 :new-liquidation-px 400.0}})

(defn- with-rec
  [state key rec]
  (assoc-in state (conj state/recs-path key) rec))

(deftest batch-candidates-filters-and-sorts
  (testing "only :ok recs with high/elevated risk and an actionable top-up"
    (let [state (with-rec (base-state) "xyz:TSM|xyz"
                          {:status :ok :result high-risk-result})
          [candidate :as candidates] (state/batch-candidates state :balanced)]
      (is (= 1 (count candidates)))
      (is (= "xyz:TSM|xyz" (:position-key candidate)))
      (is (= "xyz" (:dex candidate)))
      (is (= 6.22 (:additional candidate)))
      (is (= 18.64 (:target-equity candidate)))
      (is (= 0.14 (:p-now candidate)))
      (is (= :high (:risk-level candidate)))
      (is (map? (:position-data candidate)))))
  (testing "normal risk, non-ok status, and dust top-ups are excluded"
    (let [normal (with-rec (base-state) "xyz:TSM|xyz"
                           {:status :ok
                            :result (assoc high-risk-result :risk-level :normal)})
          computing (with-rec (base-state) "xyz:TSM|xyz"
                              {:status :computing :result nil})
          dust (with-rec (base-state) "xyz:TSM|xyz"
                         {:status :ok
                          :result (assoc-in high-risk-result
                                            [:recommended :additional] 0.005)})]
      (is (empty? (state/batch-candidates normal :balanced)))
      (is (empty? (state/batch-candidates computing :balanced)))
      (is (empty? (state/batch-candidates dust :balanced)))))
  (testing "amounts follow the requested risk mode via :by-risk-mode"
    (let [result (assoc high-risk-result
                        :by-risk-mode
                        {:conservative {:status :ok
                                        :p-after 0.01
                                        :recommended {:equity 21.0 :additional 8.58}}
                         :balanced {:status :ok
                                    :p-after 0.02
                                    :recommended {:equity 18.64 :additional 6.22}}})
          state (with-rec (base-state) "xyz:TSM|xyz" {:status :ok :result result})]
      (is (= 8.58 (:additional (first (state/batch-candidates state :conservative)))))
      (is (= 6.22 (:additional (first (state/batch-candidates state :balanced))))))))

(deftest batch-computing-count-counts-unresolved-recs
  (is (= 1 (state/batch-computing-count (base-state))))
  (is (= 1 (state/batch-computing-count
            (with-rec (base-state) "xyz:TSM|xyz" {:status :computing}))))
  (is (zero? (state/batch-computing-count
              (with-rec (base-state) "xyz:TSM|xyz"
                        {:status :ok :result high-risk-result})))))

(deftest batch-available-pools-one-pool-per-dex
  (let [state (with-rec (base-state) "xyz:TSM|xyz"
                        {:status :ok :result high-risk-result})
        candidates (state/batch-candidates state :balanced)]
    (is (= {"xyz" 500} (state/batch-available-pools state candidates)))))

(deftest batch-coverage-drains-pools-most-severe-first
  (let [worse {:position-key "a" :dex "xyz" :additional 6 :p-now 0.2}
        better {:position-key "b" :dex "xyz" :additional 5 :p-now 0.1}]
    (testing "pool covers everything"
      (is (= {:total 11 :covered 11 :fundable-count 2 :skipped-count 0}
             (dissoc (state/batch-coverage [worse better] {"xyz" 500}) :pools))))
    (testing "short pool funds the earlier (worse) candidate first"
      (let [{:keys [total covered fundable-count skipped-count]}
            (state/batch-coverage [worse better] {"xyz" 6.5})]
        (is (= 11 total))
        (is (= 6 covered))
        (is (= 1 fundable-count))
        (is (= 1 skipped-count))))
    (testing "sub-dollar remainders are skipped, not submitted"
      (let [{:keys [covered fundable-count skipped-count]}
            (state/batch-coverage [worse better] {"xyz" 6.4})]
        (is (= 6 covered))
        (is (= 1 fundable-count))
        (is (= 1 skipped-count))))))

(deftest batch-ui-slice-carries-batch-fields
  (let [state (with-rec (base-state) "xyz:TSM|xyz"
                        {:status :ok :result high-risk-result})
        slice (state/ui-slice state)]
    (is (= {:open? false :anchor nil :deselected #{}} (:batch slice)))
    (is (= 1 (count (:batch-candidates slice))))
    (is (zero? (:batch-computing-count slice)))
    (is (= {"xyz" 500} (:batch-available-pools slice)))))
