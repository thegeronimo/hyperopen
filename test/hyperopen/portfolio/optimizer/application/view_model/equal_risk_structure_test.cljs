(ns hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure-test
  "Read models behind the Equal Risk correlation/breakdown views: the derived
  position-P&L sign flips, tooltip copy, selection fallback rules, the
  breakdown join, and the shared decomposition scale."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]))

(def ^:private solved-result
  {:status :solved
   :as-of-ms 1752200000000
   :instrument-ids ["perp:BTC" "perp:ETH" "perp:MSTR" "perp:DUST"]
   :labels-by-instrument {"perp:BTC" "BTC"
                          "perp:ETH" "ETH"
                          "perp:MSTR" "MSTR"
                          "perp:DUST" "DUST"}
   :target-weights-by-instrument {"perp:BTC" 0.75
                                  "perp:ETH" 0.5
                                  "perp:MSTR" -0.25
                                  "perp:DUST" 0.0}
   :risk-contributions
   {:instrument-ids ["perp:BTC" "perp:ETH" "perp:MSTR" "perp:DUST"]
    :relative-contributions [0.5 0.375 0.125 0.0]
    :target-relative-contributions [0.25 0.25 0.25 0.25]
    :relative-contributions-by-instrument {"perp:BTC" 0.5
                                           "perp:ETH" 0.375
                                           "perp:MSTR" 0.125
                                           "perp:DUST" 0.0}
    :target-relative-contributions-by-instrument {"perp:BTC" 0.25
                                                  "perp:ETH" 0.25
                                                  "perp:MSTR" 0.25
                                                  "perp:DUST" 0.25}
    :rms-error 0.125
    :max-absolute-error 0.25
    :negative-contribution-count 0
    :quality :approximate}
   :risk-structure
   {:method :signed-euler-decomposition
    :portfolio-volatility 0.5
    :current-diversification {:modeled-volatility 0.20
                              :all-move-together-volatility 0.30
                              :zero-correlation-volatility 0.18
                              :reduction-vs-all-move-together 0.10
                              :reduction-ratio-vs-all-move-together (/ 1 3)
                              :modeled-minus-zero-correlation 0.02}
    :target-diversification {:modeled-volatility 0.40
                             :all-move-together-volatility 0.80
                             :zero-correlation-volatility 0.30
                             :reduction-vs-all-move-together 0.40
                             :reduction-ratio-vs-all-move-together 0.50
                             :modeled-minus-zero-correlation 0.10}
    :standalone-share-by-instrument {"perp:BTC" 0.625
                                     "perp:ETH" 0.5
                                     "perp:MSTR" 0.25
                                     "perp:DUST" 0.0}
    :diversification-share-by-instrument {"perp:BTC" -0.125
                                          "perp:ETH" -0.125
                                          "perp:MSTR" -0.125
                                          "perp:DUST" 0.0}
    :pnl-portfolio-correlation-by-instrument {"perp:BTC" 0.75
                                              "perp:ETH" 0.5
                                              "perp:MSTR" 0.25}
    :correlation {:instrument-ids ["perp:BTC" "perp:ETH" "perp:MSTR"]
                  :matrix [[1.0 0.75 0.5]
                           [0.75 1.0 0.25]
                           [0.5 0.25 1.0]]
                  :hidden-count 1}}})

(deftest correlation-model-derives-position-pnl-signs-test
  (let [{:keys [entries cells hidden-count]}
        (structure-model/correlation-model solved-result)]
    (is (= ["BTC" "ETH" "MSTR"] (mapv :label entries)))
    (is (= [:long :long :short] (mapv :side entries)))
    (is (= 1 hidden-count))
    (testing "long × long keeps the underlying sign"
      (let [cell (get-in cells [0 1])]
        (is (= 0.75 (:underlying cell)))
        (is (= 0.75 (:position cell)))
        (is (= :amplifying (:effect cell)))))
    (testing "long × short flips the P&L correlation negative"
      (let [cell (get-in cells [0 2])]
        (is (= 0.5 (:underlying cell)))
        (is (= -0.5 (:position cell)))
        (is (= :diversifying (:effect cell)))))
    (testing "the diagonal is self-correlation, no effect verdict"
      (let [cell (get-in cells [2 2])]
        (is (true? (:diagonal? cell)))
        (is (nil? (:effect cell)))))))

(deftest correlation-model-nil-without-structure-test
  (is (nil? (structure-model/correlation-model
             (dissoc solved-result :risk-structure)))))

(deftest cell-title-explains-both-correlations-test
  (let [{:keys [entries cells]} (structure-model/correlation-model solved-result)
        title (structure-model/cell-title (nth entries 0)
                                          (nth entries 2)
                                          (get-in cells [0 2]))]
    (is (= (str "BTC Long × MSTR Short"
                "\nUnderlying-return correlation +0.50"
                "\nPosition-P&L correlation -0.50"
                "\nEffect on portfolio risk: Diversifying")
           title))))

(deftest selection-fallback-rules-test
  (testing "explicit id wins when the result still carries it"
    (is (= "perp:ETH"
           (structure-model/selected-instrument solved-result "perp:ETH"))))
  (testing "a stale id falls back to the default"
    (is (= "perp:BTC"
           (structure-model/selected-instrument solved-result "perp:GONE"))))
  (testing "the default prefers the most negative net contributor"
    (let [hedged (assoc-in solved-result
                           [:risk-contributions
                            :relative-contributions-by-instrument
                            "perp:MSTR"]
                           -0.25)]
      (is (= "perp:MSTR"
             (structure-model/selected-instrument hedged nil)))))
  (testing "an all-positive book defaults to the largest |net| contributor"
    (is (= "perp:BTC" (structure-model/selected-instrument solved-result nil)))))

(deftest selected-breakdown-joins-the-identity-test
  (let [{:keys [label side standalone diversification net target-share]}
        (structure-model/selected-breakdown solved-result "perp:MSTR")]
    (is (= "MSTR" label))
    (is (= :short side))
    (is (= 0.25 standalone))
    (is (= -0.125 diversification))
    (is (= 0.125 net))
    (is (= 0.25 target-share))
    (is (= net (+ standalone diversification))))
  (is (nil? (structure-model/selected-breakdown
             (dissoc solved-result :risk-structure)
             "perp:MSTR"))))

(deftest breakdown-rows-enrich-balance-rows-test
  (let [rows (structure-model/breakdown-rows
              solved-result
              [{:instrument-id "perp:MSTR" :label "MSTR" :share 0.125}])]
    (is (= [{:instrument-id "perp:MSTR"
             :label "MSTR"
             :share 0.125
             :side :short
             :standalone 0.25
             :diversification -0.125}]
           rows)))
  (is (nil? (structure-model/breakdown-rows
             (dissoc solved-result :risk-structure)
             []))))

(deftest breakdown-asset-options-lists-held-assets-sorted-test
  (testing "held assets sort by label; the zero-weight zero-net DUST is
            unheld and never offered for inspection"
    (is (= [{:instrument-id "perp:BTC" :label "BTC" :side :long}
            {:instrument-id "perp:ETH" :label "ETH" :side :long}
            {:instrument-id "perp:MSTR" :label "MSTR" :side :short}]
           (structure-model/breakdown-asset-options solved-result))))
  (testing "no contributions, no options"
    (is (= [] (structure-model/breakdown-asset-options {})))))

(deftest asset-breakdown-tiles-tell-the-four-stories-test
  (let [result (assoc solved-result
                      :equal-risk-solver
                      {:allocation-freedom {:status :limited
                                            :free-degrees 1
                                            :binding-count 2
                                            :books {:long 2 :short 1}}})
        selected (structure-model/selected-breakdown result "perp:MSTR")
        tiles (structure-model/asset-breakdown-tiles result selected)
        by-key (into {} (map (juxt :key identity)) tiles)]
    (is (= [:summary :diversification :net :freedom] (mapv :key tiles)))
    (testing "equal-risk summary reads the book-level RMS against the target"
      (is (= "RMS deviation 12.5 pts" (:value (by-key :summary))))
      (is (= "Assets spread around 25.0% target" (:sub (by-key :summary)))))
    (testing "a negative cross-covariance effect is an offset in green"
      (is (= "MSTR cross covariance" (:label (by-key :diversification))))
      (is (= "-12.5 pts offsets" (:value (by-key :diversification))))
      (is (= "Offsets risk at final weights" (:sub (by-key :diversification))))
      (is (= "long" (:icon-tone (by-key :diversification)))))
    (testing "net contribution states the signed deviation from target"
      (is (= "12.5% of total risk" (:value (by-key :net))))
      (is (= "-12.5 pts vs 25.0% target" (:sub (by-key :net)))))
    (testing "allocation freedom reuses the why-card copy"
      (is (= "Limited · 2 binding caps" (:value (by-key :freedom))))
      (is (= "Caps constrain exact equality" (:sub (by-key :freedom))))
      (is (= :lock (:icon (by-key :freedom))))
      (is (= "warn" (:icon-tone (by-key :freedom)))))))

(deftest asset-breakdown-tiles-amplification-and-degradation-test
  (testing "a positive cross-covariance effect is an honest amplification"
    (let [amplified (assoc-in solved-result
                              [:risk-structure
                               :diversification-share-by-instrument
                               "perp:BTC"]
                              0.125)
          selected (structure-model/selected-breakdown amplified "perp:BTC")
          tile (nth (structure-model/asset-breakdown-tiles amplified selected)
                    1)]
      (is (= "+12.5 pts amplifies" (:value tile)))
      (is (= "Amplifies risk at final weights" (:sub tile)))
      (is (= "short" (:icon-tone tile)))))
  (testing "a persisted result without allocation freedom degrades honestly"
    (let [selected (structure-model/selected-breakdown solved-result
                                                       "perp:MSTR")
          freedom (nth (structure-model/asset-breakdown-tiles solved-result
                                                              selected)
                       3)]
      (is (= "—" (:value freedom)))
      (is (= "Not recorded on this result" (:sub freedom)))))
  (testing "nil selection yields no tiles"
    (is (nil? (structure-model/asset-breakdown-tiles solved-result nil)))))

(deftest pnl-portfolio-correlation-lookup-test
  (is (= 0.25 (structure-model/pnl-portfolio-correlation solved-result
                                                          "perp:MSTR")))
  (is (nil? (structure-model/pnl-portfolio-correlation solved-result
                                                       "perp:DUST")))
  (is (nil? (structure-model/pnl-portfolio-correlation
             (dissoc solved-result :risk-structure)
             "perp:BTC"))))

(deftest fit-scale-covers-zero-and-rounds-to-ticks-test
  (let [{:keys [lo hi x]} (structure-model/fit-scale [0.25 -0.125 0.125])]
    (is (<= lo -0.125))
    (is (>= hi 0.25))
    (is (zero? (js/Math.round (mod (* 100 lo) 5))))
    (is (zero? (js/Math.round (mod (* 100 hi) 5))))
    (is (= 0 (x lo)))
    (is (= 100 (x hi)))
    (is (< 0 (x 0) 100)))
  (testing "ticks stay within the domain and capped"
    (let [scale (structure-model/fit-scale [0.25 -0.125 0.125])
          ticks (structure-model/scale-ticks scale)]
      (is (<= 2 (count ticks) 9))
      (is (every? #(<= (:lo scale) % (:hi scale)) ticks)))))

(deftest radio-identity-helpers-test
  (is (= "optimizer-risk-view-1752200000000"
         (structure-model/risk-view-radio-name solved-result)))
  (is (= "optimizer-risk-view-1752200000000-correlation"
         (structure-model/risk-view-radio-id solved-result "correlation")))
  (is (= "optimizer-risk-view-result"
         (structure-model/risk-view-radio-name {}))))

(deftest diversification-comparison-read-model-uses-one-absolute-scale-test
  (let [{:keys [cards scale-max] :as model}
        (structure-model/diversification-comparison-model solved-result)
        by-key (into {} (map (juxt :key identity)) cards)
        current (by-key :current)
        target (by-key :target)]
    (is (= [:current :target] (mapv :key cards)))
    (is (= ["Current" "Recommended"] (mapv :label cards)))
    (is (= 0.80 scale-max))
    (is (= scale-max (:scale-max current) (:scale-max target)))
    (doseq [card cards]
      (is (= [:all-move-together :zero-correlation :modeled]
             (mapv :key (:benchmarks card))))
      (is (string? (:benefit-copy card)))
      (is (contains? #{:amplifies :offsets :neutral}
                     (:correlation-direction card))))
    (is (= :amplifies (:correlation-direction current)))
    (is (= :amplifies (:correlation-direction target)))
    (is (< (get-in current [:positions :modeled])
           (get-in target [:positions :modeled]))
        "marker geometry follows absolute modeled volatility, not benefit percentage")
    (is (= 50.0 (get-in target [:positions :modeled])))
    (is (= 25.0 (get-in current [:positions :modeled])))
    (is (= model
           (structure-model/diversification-comparison-model solved-result))
        "read model remains pure and deterministic")))
