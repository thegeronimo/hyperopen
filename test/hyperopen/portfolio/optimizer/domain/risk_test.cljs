(ns hyperopen.portfolio.optimizer.domain.risk-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.domain.history-series :as history-series]
            [hyperopen.portfolio.optimizer.domain.risk :as risk]))

(def day-ms
  (* 24 60 60 1000))

(def year-days
  365.2425)

(defn- near?
  ([expected actual]
   (near? expected actual 0.0000001))
  ([expected actual tolerance]
   (< (js/Math.abs (- expected actual)) tolerance)))

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

(defn- native-return-series-by-instrument
  [history instrument-ids]
  (into {}
        (map (fn [instrument-id]
               [instrument-id
                (history-series/simple-return-series
                 (get-in history [:raw-price-series-by-instrument instrument-id]))]))
        instrument-ids))

(defn- annualized-interval-covariance
  [xs ys dt-years]
  (let [n (count xs)
        total-years (* n dt-years)
        mu-x (/ (reduce + xs) total-years)
        mu-y (/ (reduce + ys) total-years)
        acc (reduce + 0
                    (map (fn [x y]
                           (let [ex (- x (* mu-x dt-years))
                                 ey (- y (* mu-y dt-years))]
                             (/ (* ex ey) dt-years)))
                         xs
                         ys))]
    (/ acc (dec n))))

(defn- symmetric-matrix?
  [matrix]
  (every? true?
          (for [row (range (count matrix))
                col (range (count matrix))]
            (near? (get-in matrix [row col])
                   (get-in matrix [col row])))))

(defn- matrix-near?
  ([expected actual]
   (matrix-near? expected actual 0.0000001))
  ([expected actual tolerance]
   (every? true?
           (for [row (range (count expected))
                 col (range (count (nth expected row)))]
             (near? (get-in expected [row col])
                    (get-in actual [row col])
                    tolerance)))))

(defn- mixed-frequency-history
  []
  (let [btc-closes (assoc (mapv #(+ 107818 (* 95 %)) (range 29))
                          0 107818
                          13 110367.37
                          14 108668
                          28 111000)
        eth-closes (mapv #(* 3000 (js/Math.pow 1.002 %)) (range 29))
        hype-closes (mapv #(* 30 (js/Math.pow 1.004 %)) (range 29))
        vault-id "vault:0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"]
    {:vault-id vault-id
     :history {:return-series-by-instrument {"perp:BTC" [0.1 0.2]
                                             "perp:ETH" [0.1 0.2]
                                             "perp:HYPE" [0.1 0.2]
                                             vault-id [0.02 0.02941176470588225]}
               :raw-price-series-by-instrument
               {"perp:BTC" (price-rows "2025-05-28" btc-closes)
                "perp:ETH" (price-rows "2025-05-28" eth-closes)
                "perp:HYPE" (price-rows "2025-05-28" hype-closes)
                vault-id [{:time-ms (day-start-ms "2025-05-28") :close 100}
                          {:time-ms (day-start-ms "2025-06-11") :close 102}
                          {:time-ms (day-start-ms "2025-06-25") :close 105}]}
               :cadence-by-instrument
               {"perp:BTC" {:kind :dense :sparse? false}
                "perp:ETH" {:kind :dense :sparse? false}
                "perp:HYPE" {:kind :dense :sparse? false}
                vault-id {:kind :sparse
                          :sparse? true
                          :observations 3
                          :interval-count 2
                          :median-dt-days 14
                          :max-dt-days 14
                          :density-vs-daily (/ 2 28)}}}}))

(deftest sample-covariance-aligns-instruments-and-annualizes-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :sample-covariance}
                 :periods-per-year 1
                 :history {:return-series-by-instrument {"A" [1 2 3]
                                                         "B" [2 4 6]}}})]
    (is (= :sample-covariance (:model result)))
    (is (= ["A" "B"] (:instrument-ids result)))
    (is (= [[1 2]
            [2 4]]
           (:covariance result)))
    (is (= [] (:warnings result)))))

(deftest diagonal-shrink-preserves-diagonal-and-shrinks-cross-covariance-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :diagonal-shrink
                              :shrinkage 0.5}
                 :periods-per-year 1
                 :history {:return-series-by-instrument {"A" [1 2 3]
                                                         "B" [2 4 6]}}})]
    (is (= :diagonal-shrink (:model result)))
    (is (= [[1 1]
            [1 4]]
           (:covariance result)))
    (is (= {:kind :diagonal
            :shrinkage 0.5}
           (:shrinkage result)))))

(deftest ledoit-wolf-dense-uses-scaled-identity-shrinkage-on-dense-rectangular-history-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf-dense}
                 :periods-per-year 1
                 :history {:return-series-by-instrument {"A" [1 2 3]
                                                         "B" [2 4 6]}}})
        expected-covariance [[1 0.8888888888888888]
                             [0.8888888888888888 2.333333333333333]]
        shrinkage (:shrinkage result)]
    (is (= :ledoit-wolf-dense (:model result)))
    (is (= ["A" "B"] (:instrument-ids result)))
    (is (matrix-near? expected-covariance
                      (:covariance result)
                      0.0000001))
    (is (= :ledoit-wolf (:kind shrinkage)))
    (is (= :scaled-identity (:target shrinkage)))
    (is (near? (/ 1 3)
               (:shrinkage shrinkage)
               0.0000001))
    (is (= 3 (:sample-count result)))
    (is (= 2 (:feature-count result)))
    (is (= [] (:warnings result)))))

(deftest covariance-conditioning-reports-eigenvalue-condition-number-test
  (let [summary (risk/covariance-conditioning [[2 1]
                                               [1 2]])]
    (is (near? 3 (:condition-number summary)))
    (is (near? 1 (:min-eigenvalue summary)))
    (is (near? 3 (:max-eigenvalue summary)))
    (is (= :ok (:status summary)))))

(deftest legacy-ledoit-wolf-kind-is-normalized-to-diagonal-shrink-with-warning-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf
                              :shrinkage 0.5}
                 :periods-per-year 1
                 :history {:return-series-by-instrument {"A" [1 2 3]
                                                         "B" [2 4 6]}}})]
    (is (= :diagonal-shrink (:model result)))
    (is (= [{:code :risk-model-renamed
             :from :ledoit-wolf
             :to :diagonal-shrink}]
           (:warnings result)))))

(deftest mixed-frequency-risk-excludes-aligned-only-instruments-without-native-history-test
  (let [{:keys [history vault-id]} (mixed-frequency-history)
        history* (-> history
                     (assoc-in [:return-series-by-instrument "perp:ETH"] [0.02 0.03])
                     (assoc-in [:price-series-by-instrument "perp:ETH"]
                               [{:time-ms (day-start-ms "2025-05-28") :close 3000}
                                {:time-ms (day-start-ms "2025-06-11") :close 3090}
                                {:time-ms (day-start-ms "2025-06-25") :close 3180}])
                     (update :raw-price-series-by-instrument dissoc "perp:ETH")
                     (update :cadence-by-instrument dissoc "perp:ETH"))
        result (risk/estimate-risk-model
                {:risk-model {:kind :mixed-frequency}
                 :history history*})]
    (is (= ["perp:BTC" "perp:HYPE" vault-id]
           (:instrument-ids result)))
    (is (= {:code :missing-native-risk-history
            :instrument-id "perp:ETH"
            :policy :mixed-frequency-requires-native-price-series}
           (some #(when (= :missing-native-risk-history (:code %)) %)
                 (:warnings result))))))

(deftest sample-covariance-request-warns-when-mixed-frequency-risk-is-required-test
  (let [{:keys [history]} (mixed-frequency-history)
        result (risk/estimate-risk-model
                {:risk-model {:kind :sample-covariance}
                 :history history})]
    (is (= :mixed-frequency (:model result)))
    (is (= :sample-covariance (:requested-model result)))
    (is (= {:code :risk-model-overridden-for-mixed-frequency
            :requested-model :sample-covariance
            :model :mixed-frequency
            :reason :sparse-history}
           (some #(when (= :risk-model-overridden-for-mixed-frequency (:code %)) %)
                 (:warnings result))))))

(deftest ledoit-wolf-dense-request-routes-sparse-history-to-mixed-frequency-test
  (let [{:keys [history]} (mixed-frequency-history)
        result (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf-dense}
                 :history history})]
    (is (= :mixed-frequency (:model result)))
    (is (= :ledoit-wolf-dense (:requested-model result)))
    (is (= {:code :risk-model-overridden-for-mixed-frequency
            :requested-model :ledoit-wolf-dense
            :model :mixed-frequency
            :reason :sparse-history}
           (some #(when (= :risk-model-overridden-for-mixed-frequency (:code %)) %)
                 (:warnings result))))))

(deftest mixed-frequency-ledoit-wolf-request-uses-ledoit-wolf-dense-block-test
  (let [{:keys [history vault-id]} (mixed-frequency-history)
        dense-instrument-ids ["perp:BTC" "perp:ETH" "perp:HYPE"]
        result (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf-dense}
                 :periods-per-year 365
                 :history history})
        native-dense-returns (native-return-series-by-instrument
                              history
                              dense-instrument-ids)
        dense-only (risk/estimate-risk-model
                    {:risk-model {:kind :ledoit-wolf-dense}
                     :periods-per-year 365
                     :history {:return-series-by-instrument
                               native-dense-returns}})
        ids (:instrument-ids result)
        dense-ids (:instrument-ids dense-only)
        mixed-index (zipmap ids (range))
        dense-index (zipmap dense-ids (range))
        pair-meta (get-in result [:pair-metadata "perp:BTC|perp:ETH"])
        btc-hlp-meta (get-in result [:pair-metadata (str "perp:BTC|" vault-id)])
        psd-loading (or (:diagonal-loading
                         (some #(when (= :psd-repair-applied (:code %)) %)
                               (:warnings result)))
                        0)
        sparse-retention (/ 2 (+ 2 30))]
    (is (= :mixed-frequency (:model result)))
    (is (= :ledoit-wolf-dense (:requested-model result)))
    (doseq [left-id dense-ids
            right-id dense-ids]
      (let [dense-covariance (get-in dense-only
                                     [:covariance (dense-index left-id) (dense-index right-id)])
            mixed-covariance (get-in result
                                     [:covariance (mixed-index left-id) (mixed-index right-id)])
            expected (if (= left-id right-id)
                       (+ dense-covariance psd-loading)
                       dense-covariance)]
        (is (near? expected mixed-covariance 0.0000001))))
    (is (= :ledoit-wolf-dense (:estimator pair-meta)))
    (is (true? (:dense-block? pair-meta)))
    (is (= :scaled-identity (:target pair-meta)))
    (is (number? (:shrinkage pair-meta)))
    (is (= :sparse-interval (:calendar-kind btc-hlp-meta)))
    (is (= sparse-retention
           (:correlation-retention btc-hlp-meta)))
    (is (= :ledoit-wolf-dense
           (get-in result [:risk-estimation :dense-block-estimator])))
    (is (= 28
           (get-in result [:risk-estimation :dense-block-sample-count])))
    (when (pos? psd-loading)
      (is (= psd-loading
             (get-in result
                     [:risk-estimation
                      :dense-block-post-repair-diagonal-loading]))))
    (is (= dense-ids
           (get-in result [:risk-estimation :dense-block-instrument-ids])))))

(deftest mixed-frequency-ledoit-wolf-ignores-sparse-collapsed-aligned-dense-returns-test
  (let [{:keys [history]} (mixed-frequency-history)
        history* (update history :return-series-by-instrument dissoc "perp:HYPE")
        result (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf-dense}
                 :history history*})
        pair-meta (get-in result [:pair-metadata "perp:BTC|perp:ETH"])]
    (is (= :mixed-frequency (:model result)))
    (is (= :ledoit-wolf-dense (:estimator pair-meta)))
    (is (= 28
           (get-in result [:risk-estimation :dense-block-sample-count])))
    (is (nil? (some #(when (= :dense-block-ledoit-wolf-unavailable (:code %)) %)
                    (:warnings result))))))

(deftest mixed-frequency-ledoit-wolf-falls-back-when-native-dense-block-has-too-few-returns-test
  (let [{:keys [history]} (mixed-frequency-history)
        history* (update-in history
                            [:raw-price-series-by-instrument "perp:HYPE"]
                            #(subvec (vec %) 0 2))
        result (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf-dense}
                 :history history*})
        pair-meta (get-in result [:pair-metadata "perp:BTC|perp:ETH"])
        warning (some #(when (= :dense-block-ledoit-wolf-unavailable (:code %)) %)
                      (:warnings result))]
    (is (= :mixed-frequency (:model result)))
    (is (= :pairwise-mixed-frequency (:estimator pair-meta)))
    (is (= :insufficient-dense-return-observations (:reason warning)))
    (is (= 1 (:observations warning)))
    (is (= 2 (:required warning)))))

(deftest mixed-frequency-ledoit-wolf-falls-back-when-native-dense-returns-are-non-rectangular-test
  (let [{:keys [history]} (mixed-frequency-history)
        result (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf-dense}
                 :history (assoc-in history
                                    [:raw-price-series-by-instrument
                                     "perp:HYPE"
                                     1
                                     :close]
                                    0)})
        pair-meta (get-in result [:pair-metadata "perp:BTC|perp:ETH"])
        warning (some #(when (= :dense-block-ledoit-wolf-unavailable (:code %)) %)
                      (:warnings result))]
    (is (= :mixed-frequency (:model result)))
    (is (= :pairwise-mixed-frequency (:estimator pair-meta)))
    (is (= :non-rectangular-dense-return-series (:reason warning)))
    (is (= ["perp:BTC" "perp:ETH" "perp:HYPE"] (:instrument-ids warning)))))

(deftest mixed-frequency-risk-aggregates-dense-assets-over-sparse-intervals-test
  (let [{:keys [history vault-id]} (mixed-frequency-history)
        result (risk/estimate-risk-model
                {:risk-model {:kind :mixed-frequency}
                 :history history})
        ids (:instrument-ids result)
        btc-idx (.indexOf ids "perp:BTC")
        eth-idx (.indexOf ids "perp:ETH")
        vault-idx (.indexOf ids vault-id)
        btc-correct (mapv js/Math.log
                          [(/ 108668 107818)
                           (/ 111000 108668)])
        btc-wrong-daily (js/Math.log (/ 108668 110367.37))
        hlp-logs (mapv js/Math.log [(/ 102 100)
                                    (/ 105 102)])
        dt-years (/ 14 year-days)
        sparse-retention (/ 2 (+ 2 30))
        expected-cross (* sparse-retention
                          (annualized-interval-covariance btc-correct
                                                          hlp-logs
                                                          dt-years))
        wrong-cross (* sparse-retention
                       (annualized-interval-covariance [btc-wrong-daily
                                                        (second btc-correct)]
                                                       hlp-logs
                                                       dt-years))
        btc-hlp-key (str "perp:BTC|" vault-id)
        btc-eth-key "perp:BTC|perp:ETH"
        conditioning (risk/covariance-conditioning (:covariance result))]
    (is (= :mixed-frequency (:model result)))
    (is (= ["perp:BTC" "perp:ETH" "perp:HYPE" vault-id]
           ids))
    (is (near? expected-cross
               (get-in result [:covariance btc-idx vault-idx])
               0.0000001))
    (is (not (near? wrong-cross
                    (get-in result [:covariance btc-idx vault-idx])
                    0.0000001))
        "BTC/HLP covariance must use BTC close-to-close interval return, not the single daily return on 2025-06-11.")
    (is (= :daily
           (get-in result [:pair-metadata btc-eth-key :calendar-kind])))
    (is (= 28
           (get-in result [:pair-metadata btc-eth-key :observations])))
    (is (= :sparse-interval
           (get-in result [:pair-metadata btc-hlp-key :calendar-kind])))
    (is (= 2
           (get-in result [:pair-metadata btc-hlp-key :observations])))
    (is (= sparse-retention
           (get-in result [:pair-metadata btc-hlp-key :correlation-retention])))
    (is (symmetric-matrix? (:covariance result)))
    (is (not= :not-positive-semidefinite (:status conditioning)))
    (is (some #(and (= :sparse-history-risk-estimation (:code %))
                    (= vault-id (:instrument-id %))
                    (= 2 (:interval-count %))
                    (not (str/includes? (:message %) vault-id)))
              (:warnings result)))))

(deftest mixed-frequency-risk-applies-final-diagonal-shrink-when-requested-test
  (let [{:keys [history vault-id]} (mixed-frequency-history)
        shrinkage 0.25
        mixed (risk/estimate-risk-model
               {:risk-model {:kind :mixed-frequency}
                :history history})
        shrunk (risk/estimate-risk-model
                {:risk-model {:kind :diagonal-shrink
                              :shrinkage shrinkage}
                 :history history})
        legacy (risk/estimate-risk-model
                {:risk-model {:kind :ledoit-wolf
                              :shrinkage shrinkage}
                 :history history})
        ids (:instrument-ids mixed)
        btc-idx (.indexOf ids "perp:BTC")
        vault-idx (.indexOf ids vault-id)]
    (is (= :mixed-frequency (:model shrunk)))
    (is (= :diagonal-shrink (:requested-model shrunk)))
    (is (= {:kind :diagonal
            :shrinkage shrinkage}
           (:shrinkage shrunk)))
    (is (near? (get-in mixed [:covariance btc-idx btc-idx])
               (get-in shrunk [:covariance btc-idx btc-idx])))
    (is (near? (* (- 1 shrinkage)
                  (get-in mixed [:covariance btc-idx vault-idx]))
               (get-in shrunk [:covariance btc-idx vault-idx])
               0.0000001))
    (is (= :mixed-frequency (:model legacy)))
    (is (= :diagonal-shrink (:requested-model legacy)))
    (is (= {:kind :diagonal
            :shrinkage shrinkage}
           (:shrinkage legacy)))
    (is (some #(= {:code :risk-model-renamed
                   :from :ledoit-wolf
                   :to :diagonal-shrink}
                  %)
              (:warnings legacy)))))

;; --- annualization guards ---------------------------------------------------
;; Four deftests above pass `:periods-per-year 1`, which neutralizes the
;; annualizer: mutating risk.cljs to `(* periods-per-year periods-per-year
;; (sample-covariance ...))` left the whole 6,000-test suite green. A squared
;; factor is a 19.1x volatility error - the same order as the defect these
;; guards exist to prevent - so the factor is pinned end to end here.

(def ^:private annualization-series
  ;; A: mean 0.02, deviations [-0.01 0 0.01] -> variance 0.0001
  ;; B: mean 0.04, deviations [-0.02 0 0.02] -> variance 0.0004, cov 0.0002
  {"A" [0.01 0.02 0.03]
   "B" [0.02 0.04 0.06]})

(deftest sample-covariance-annualizes-exactly-once-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :sample-covariance}
                 :periods-per-year 365
                 :history {:return-series-by-instrument annualization-series}})
        covariance (:covariance result)]
    ;; 0.0001 * 365, NOT 0.0001 * 365 * 365 (which would be 13.3225).
    (is (near? 0.0365 (get-in covariance [0 0]) 1e-12))
    (is (near? 0.146 (get-in covariance [1 1]) 1e-12))
    (is (near? 0.073 (get-in covariance [0 1]) 1e-12))
    (is (near? 0.073 (get-in covariance [1 0]) 1e-12))
    (is (near? 0.1910497 (js/Math.sqrt (get-in covariance [0 0])) 1e-6)
        "19.1% annualized, not 365%.")))

(deftest diagonal-shrink-annualizes-exactly-once-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :diagonal-shrink :shrinkage 0.1}
                 :periods-per-year 365
                 :history {:return-series-by-instrument annualization-series}})
        covariance (:covariance result)]
    (is (near? 0.0365 (get-in covariance [0 0]) 1e-12)
        "Shrinkage leaves the diagonal alone, so it pins the annualizer too.")
    (is (near? 0.146 (get-in covariance [1 1]) 1e-12))
    (is (near? (* 0.9 0.073) (get-in covariance [0 1]) 1e-12))))

(deftest omitted-periods-per-year-defaults-to-daily-annualization-test
  (let [result (risk/estimate-risk-model
                {:risk-model {:kind :sample-covariance}
                 :history {:return-series-by-instrument annualization-series}})]
    (is (= 365 risk/default-periods-per-year))
    (is (near? 0.0365 (get-in (:covariance result) [0 0]) 1e-12)
        "An absent :periods-per-year must annualize daily, not leave the estimate per-period.")))
