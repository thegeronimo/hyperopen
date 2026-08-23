(ns hyperopen.portfolio.optimizer.domain.return-plausibility-test
  "The bound on a single-bar return, and the three seams that enforce it.

  Before this bound existed, one corrupt bar reached the covariance intact and -
  under the default Ledoit-Wolf model - flattened the whole book to a single
  impossible volatility. See
  /hyperopen/docs/exec-plans/active/2026-08-23-optimizer-volatility-integrity.md."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.alignment :as alignment]
            [hyperopen.portfolio.optimizer.application.history-loader.calendar :as calendar]
            [hyperopen.portfolio.optimizer.domain.history-series :as history-series]
            [hyperopen.portfolio.optimizer.domain.return-plausibility :as plausibility]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

(def ^:private day-ms
  (* 24 60 60 1000))

(defn- day-start-ms
  [day]
  (.getTime (js/Date. (str day "T00:00:00.000Z"))))

(defn- price-rows
  [start-day closes]
  (let [start-ms (day-start-ms start-day)]
    (mapv (fn [idx close]
            {:time-ms (+ start-ms (* idx day-ms))
             :close close})
          (range)
          closes)))

(deftest bounds-clear-every-real-observation-and-reject-the-corruption-test
  ;; Calibrated on 2026-08-23 against 60 Hyperliquid perpetuals / 51,900 daily
  ;; observations: max real |r| was 1.261 (BOME), and NOTHING exceeded 2.0.
  (is (plausibility/usable-return? 0.0266) "the measured median day")
  (is (plausibility/usable-return? 0.4244) "the measured p99.9")
  (is (plausibility/usable-return? 1.261) "the largest real bar in the sample")
  (is (plausibility/usable-return? -0.95) "a near-total loss is real")
  (is (not (plausibility/usable-return? 30.0)) "+3000% is not a market move")
  (is (not (plausibility/usable-return? 300.0)) "the observed corruption")
  (is (plausibility/implausible-return? -30.0) "the bound is two-sided")
  ;; A non-finite value is NOT 'implausible' here: existing finiteness guards
  ;; own that case, and answering true would silently widen their behaviour.
  (is (not (plausibility/implausible-return? nil)))
  (is (not (plausibility/implausible-return? js/NaN)))
  (is (not (plausibility/usable-return? js/NaN)))
  ;; The advisory tier keeps the observation but discloses it.
  (is (plausibility/extreme-return? 1.261))
  (is (not (plausibility/extreme-return? 0.5)))
  (is (not (plausibility/extreme-return? 30.0)) "past rejection is not merely extreme"))

(deftest volatility-bound-clears-the-most-volatile-real-asset-test
  ;; Measured per-asset annualized volatility across the same sample: min 47%,
  ;; median 110%, p95 188%, max 390%.
  (is (not (plausibility/implausible-volatility? 3.9)) "the most volatile real perp")
  (is (plausibility/implausible-volatility? 71.99) "what HYPE reported on screen"))

(deftest return-series-and-intervals-drop-exactly-the-same-pairs-test
  ;; The desync trap: domain.returns/interval-observations guards with
  ;; (= (count intervals) (count series)) and, on a mismatch, silently falls
  ;; through to a DIFFERENT expected-return estimator with no warning. So the
  ;; plausibility drop must be applied identically to both.
  (let [clean (price-rows "2026-01-01" [100 102 101 103 102 104])
        poisoned (price-rows "2026-01-01" [100 102 101 40000 102 104])]
    (is (= (count (history-series/simple-return-series clean))
           (count (history-series/return-intervals clean))))
    (is (= 5 (count (history-series/simple-return-series clean))))
    (let [series (history-series/simple-return-series poisoned)
          intervals (history-series/return-intervals poisoned)]
      (is (= (count series) (count intervals))
          "A drop in one must be a drop in the other.")
      (is (= 2 (count series))
          "All three pairs touching the suspect close are discarded: the spike, the revert, and the bar before it.")
      (is (every? #(< (js/Math.abs %) 2.0) series)))))

(deftest implausible-observations-are-reported-not-just-dropped-test
  (let [poisoned (price-rows "2026-01-01" [100 102 101 40000 102 104])
        reported (history-series/implausible-observations poisoned)]
    (is (= 1 (count reported))
        "The EVIDENCE is the one bar past the bound; the neighbours are discarded with it but are not themselves evidence.")
    (is (every? #(> (js/Math.abs (:return %)) 2.0) reported))
    (is (every? #(number? (:time-ms %)) reported)
        "The caller needs the timestamp to name the bad day.")))

(deftest point-return-map-drops-implausible-bars-and-names-them-test
  (let [points [{:time-ms 1 :return 0.02}
                {:time-ms 2 :return 300.0}
                {:time-ms 3 :return -0.01}
                {:time-ms 4 :return 1.5}
                {:time-ms 5 :return nil}]]
    (is (= {1 0.02 4 1.5} (calendar/point-return-map points))
        "The implausible bar AND the one after it (which shares its suspect price) leave the shared calendar; extreme-but-real ones stay.")
    (is (= [{:time-ms 2 :return 300.0}] (calendar/implausible-returns points)))
    (is (= [{:time-ms 4 :return 1.5}] (calendar/extreme-returns points))
        "A nil return is an ordinary gap, not an extreme move.")))

(deftest a-corrupt-bar-no-longer-poisons-the-whole-book-test
  ;; End to end through the real estimator: this is the regression that matters.
  ;; Before the guard, ONE +3000% print in one asset drove Ledoit-Wolf shrinkage
  ;; to 1 and gave all three assets the same ~9,000% volatility.
  (let [rows {"A" (price-rows "2026-01-01" [100 102 99 104 101 106 103 108 105 110 107 112])
              "B" (price-rows "2026-01-01" [50 51 50.5 52 51 53 52 54 53 55 54 56])
              "C" (price-rows "2026-01-01" [10 10.1 9.9 10.2 10 10.3 10.1 10.4 10.2 10.5 10.3 10.6])}
        poison (fn [instrument-rows]
                 (assoc-in instrument-rows [3 :close] 41200))
        series-of (fn [rows-by-id]
                    (into {}
                          (map (fn [[id r]]
                                 [id (history-series/simple-return-series r)]))
                          rows-by-id))
        estimate (fn [rows-by-id]
                   (risk/estimate-risk-model
                    {:risk-model {:kind :ledoit-wolf-dense}
                     :periods-per-year 365
                     :history {:return-series-by-instrument (series-of rows-by-id)}}))
        clean (estimate rows)
        guarded (estimate (update rows "A" poison))
        vols (fn [result]
               (mapv #(js/Math.sqrt (get-in (:covariance result) [% %]))
                     (range (count (:instrument-ids result)))))]
    (is (every? #(< % 5.0) (vols clean)))
    (is (every? #(< % 5.0) (vols guarded))
        "The corrupt bar is gone, so no volatility is impossible any more.")
    (is (apply distinct? (vols guarded))
        "Per-asset volatility stays differentiated: no scaled-identity collapse.")
    (is (< (get-in guarded [:shrinkage :shrinkage]) 0.99)
        "The shrinkage no longer saturates.")
    (is (= :ok (:status (risk/covariance-plausibility (:covariance guarded)
                                                      (:instrument-ids guarded)))))))

(deftest aligned-returns-carrying-an-implausible-value-are-not-used-verbatim-test
  ;; When the backend's pre-aligned vector is poisoned, the loader must demote
  ;; to the point-level path rather than copying it through.
  (let [t0 (day-start-ms "2026-01-01")
        times (mapv #(+ t0 (* % day-ms)) (range 6))
        points (fn [returns]
                 (mapv (fn [time-ms return close]
                         {:time-ms time-ms :return return :close close})
                       times
                       returns
                       [100 102 101 103 102 104]))
        history (fn [aligned-a]
                  {:status :ok
                   :return-calendar (vec (rest times))
                   :common-calendar times
                   :series-by-instrument
                   {"perp:A" {:lineage-kind :native
                              :points (points [nil 0.02 -0.01 0.02 -0.01 0.02])}
                    "perp:B" {:lineage-kind :native
                              :points (points [nil 0.01 -0.01 0.01 -0.01 0.01])}}
                   :aligned-returns-by-instrument
                   {"perp:A" {:returns aligned-a}
                    "perp:B" {:returns [0.01 -0.01 0.01 -0.01 0.01]}}})
        universe [{:instrument-id "perp:A" :market-type :perp :coin "A"
                   :optimizer-history/instrument-id "hl:perp:A"}
                  {:instrument-id "perp:B" :market-type :perp :coin "B"
                   :optimizer-history/instrument-id "hl:perp:B"}]
        align (fn [aligned-a]
                (alignment/align-api-v2-history-inputs
                 {:universe universe
                  :api-v2-history (history aligned-a)
                  :as-of-ms (+ (last times) day-ms)
                  :min-observations 2}))
        clean (align [0.02 -0.01 0.02 -0.01 0.02])
        poisoned (align [0.02 -0.01 300.0 -0.01 0.02])]
    (is (= :api-v2-aligned-returns (get-in clean [:alignment-source :kind]))
        "A clean aligned vector is still used verbatim.")
    (is (= :api-v2-point-returns (get-in poisoned [:alignment-source :kind]))
        "A poisoned one demotes to the point-level path, which drops the bar.")))

(deftest dropping-a-bar-keeps-every-series-the-same-length-test
  ;; A per-instrument drop would desync series lengths and trip the Ledoit-Wolf
  ;; ragged path, silently discarding that asset's correlations. The shared
  ;; calendar prevents it: `point-level-return-calendar` keeps only timestamps
  ;; EVERY member can supply, so one member's bad bar leaves the calendar for
  ;; all of them and the vectors stay rectangular.
  ;;
  ;; Verified on real Hyperliquid data (7 perps, 430 daily bars, 2026-08-23):
  ;; injecting a +40,000% close into HYPE took the book from a scaled-identity
  ;; collapse to 242% with per-asset vols [67 106 95 97 97 70 94]% and NO
  ;; warnings, against 257% / [67 107 95 98 97 70 95]% for the untouched series.
  (let [t (fn [i] (* i 86400000))
        points (fn [returns]
                 (mapv (fn [idx return] {:time-ms (t idx) :return return})
                       (range)
                       returns))
        by-id {"A" (points [0.01 300.0 0.02 -0.01 0.02])
               "B" (points [0.02 0.01 -0.02 0.01 -0.01])
               "C" (points [0.01 -0.01 0.01 0.02 -0.02])}
        shared (calendar/point-level-return-calendar by-id (mapv t (range 5)))
        series (calendar/returns-from-point-level by-id shared)
        lengths (mapv (comp count val) series)]
    (is (apply = lengths) "Every series must stay the same length.")
    (is (= 3 (first lengths))
        "The bad bar and the one after it leave the calendar for ALL members.")
    (is (not-any? #(some (fn [r] (>= (js/Math.abs r) 2.0)) %) (vals series)))
    (is (empty? (:warnings (risk/estimate-risk-model
                            {:risk-model {:kind :ledoit-wolf-dense}
                             :periods-per-year 365
                             :history {:return-series-by-instrument series}})))
        "Rectangular input means no ragged fallback and no lost correlations.")))
