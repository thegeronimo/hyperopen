(ns hyperopen.portfolio.optimizer.application.history-loader-sparse-vault-test
  "A long-but-sparse member (a downsampled Hyperliquid vault history) must join the
  optimized universe WITHOUT touching anyone else's shared daily calendar.

  The primary oracle here is DIFFERENTIAL: align the same dense universe twice,
  with and without the sparse vault, and require the shared calendar to be
  identical. That is the assertion that catches the failure mode a positive-only
  test cannot see - silently collapsing every other asset's estimation window
  while appearing to fix the vault. Live 2026-08-25 the naive day-align fix took a
  universe from ~1095 shared return observations to 2."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]
            [hyperopen.portfolio.optimizer.domain.risk-mixed-frequency :as risk-mixed-frequency]))

(def ^:private day-ms (* 24 60 60 1000))

(defn- day-start-ms
  [day]
  (.getTime (js/Date. (str day "T00:00:00.000Z"))))

(def ^:private d0 (day-start-ms "2024-08-01"))

(def ^:private vault-address "0x1e37a337ed460039d1b15bd3bc489de789768d5e")
(def ^:private vault-id (str "vault:" vault-address))

(defn- dense-points
  "A daily, UTC-midnight-stamped series - what every non-vault instrument gets."
  [n start-close]
  (mapv (fn [idx]
          {:time_ms (+ d0 (* idx day-ms))
           :close (+ start-close idx)
           :return (when (pos? idx)
                     (/ 1 (+ (dec start-close) idx)))})
        (range n)))

(defn- sparse-points
  "Growi-HF-shaped: `n` samples spaced `gap-days` apart. With n=31 and
  gap-days=13 this is 31 samples across 390 days, the real vault's shape."
  [n gap-days]
  (mapv (fn [idx]
          {:time_ms (+ d0 (* idx gap-days day-ms))
           :close (+ 100 idx)
           :return (when (pos? idx) 0.01)})
        (range n)))

(defn- perp
  [coin]
  {:instrument-id (str "perp:" coin)
   :market-type :perp
   :coin coin
   :optimizer-history/instrument-id (str "hl:perp:" coin)})

(def ^:private vault-instrument
  ;; NO :optimizer-history/instrument-id - vaults are not discovered by the
  ;; backend, which is exactly why they route through the legacy fallback.
  {:instrument-id vault-id
   :market-type :vault
   :coin vault-id
   :vault-address vault-address})

(defn- align
  "Run the real api-v2 alignment. `vault-points` nil means no vault in the
  universe at all, which is the control arm of the differential test."
  [vault-points]
  (let [universe (cond-> [(perp "BTC") (perp "ETH")]
                   vault-points (conj vault-instrument))
        dense-a (dense-points 400 100)
        dense-b (dense-points 400 200)
        normalized (api-v2/normalize-history-bundle
                    {:universe universe}
                    {:contract_version "optimizer-history-api-v2"
                     :request_id "rid-sparse"
                     :dataset_version "dv-sparse"
                     :status "ok"
                     :common_calendar (mapv #(+ d0 (* % day-ms)) (range 400))
                     :return_calendar (mapv #(+ d0 (* % day-ms)) (range 1 400))
                     ;; Populated so `use-aligned?` CAN be true - that is what
                     ;; makes the :api-v2-aligned-returns assertion below a real
                     ;; test of the legacy-fallback-used? narrowing rather than a
                     ;; tautology.
                     :aligned_returns_by_instrument
                     {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                     :returns (mapv #(/ 1 (+ 99 %)) (range 1 400))}
                      "hl:perp:ETH" {:instrument_id "hl:perp:ETH"
                                     :returns (mapv #(/ 1 (+ 199 %)) (range 1 400))}}
                     :series_by_instrument
                     {"hl:perp:BTC" {:instrument_id "hl:perp:BTC"
                                     :lineage_kind "native"
                                     :series_kind "market_price"
                                     :points dense-a
                                     :funding {:status "available"
                                               :annualized_carry 0.01}
                                     :warnings []}
                      "hl:perp:ETH" {:instrument_id "hl:perp:ETH"
                                     :lineage_kind "native"
                                     :series_kind "market_price"
                                     :points dense-b
                                     :funding {:status "available"
                                               :annualized_carry 0.02}
                                     :warnings []}}
                     :warnings []})]
    (history-loader/align-history-inputs
     (cond-> {:universe universe
              :api-v2-history normalized
              :as-of-ms (+ d0 (* 400 day-ms))}
       vault-points
       (assoc :vault-details-by-address
              {vault-address
               {:portfolio
                {:all-time
                 {:accountValueHistory (mapv (fn [{:keys [time_ms close]}]
                                               [time_ms (* 1000 close)])
                                             vault-points)
                  :pnlHistory (mapv (fn [{:keys [time_ms close]}]
                                      [time_ms (* 1000 (- close 100))])
                                    vault-points)}}}})))))

(deftest sparse-vault-does-not-change-the-shared-calendar-test
  (testing "THE control: admitting a sparse vault leaves every other asset's window intact"
    (let [without (align nil)
          with (align (sparse-points 31 13))]
      (is (= (:calendar without) (:calendar with))
          "shared calendar must be bit-identical with and without the sparse vault")
      (is (= (:return-calendar without) (:return-calendar with))
          "shared return calendar must be bit-identical")
      (is (= (get (:return-series-by-instrument without) "perp:BTC")
             (get (:return-series-by-instrument with) "perp:BTC"))
          "BTC's return series must be untouched")
      (is (pos? (count (:return-calendar with)))
          "sanity: the dense universe really does have a calendar to protect"))))

(deftest sparse-vault-joins-the-universe-off-calendar-test
  (let [aligned (align (sparse-points 31 13))
        eligible-ids (set (mapv :instrument-id (:eligible-instruments aligned)))]
    (testing "in the run"
      (is (contains? eligible-ids vault-id))
      (is (= [vault-id] (:off-calendar-instrument-ids aligned))))
    (testing "off the calendar-sampled maps (never a vector of nils)"
      (is (not (contains? (:return-series-by-instrument aligned) vault-id)))
      (is (not (contains? (:price-series-by-instrument aligned) vault-id))))
    (testing "present in the NATIVE maps the mixed-frequency estimator reads"
      (is (seq (get-in aligned [:raw-price-series-by-instrument vault-id])))
      (is (seq (get-in aligned [:expected-return-series-by-instrument vault-id])))
      (is (true? (get-in aligned [:cadence-by-instrument vault-id :off-calendar?])))
      (is (true? (get-in aligned [:cadence-by-instrument vault-id :sparse?]))))
    (testing "the whole run switches to the mixed-frequency estimator"
      (is (= :mixed-frequency (get-in aligned [:risk-estimation :kind]))))
    (testing "no exclusion, and the window limiter is never the vault"
      (is (empty? (filterv #(= :insufficient-common-history (:code %))
                           (:warnings aligned))))
      (is (not= vault-id (get-in aligned [:history-window :limiting-instrument-id]))))
    (testing "a lane member does not force the client-side calendar recompute"
      (is (= :api-v2-aligned-returns (get-in aligned [:alignment-source :kind]))))
    (testing "it is disclosed, informationally"
      (is (some #(= :sparse-native-history (:code %)) (:warnings aligned))))))

(deftest sparse-lane-floors-hold-test
  (testing "below the observation floor the member stays ON the calendar (today's behaviour)"
    ;; 29 samples over 377 days: sparse, spans plenty, but under the 30-sample
    ;; floor that is pinned to the readiness assumption gate.
    (let [aligned (align (sparse-points 29 13))]
      (is (nil? (:off-calendar-instrument-ids aligned)))))
  (testing "a young dense listing is never swept into the lane"
    ;; 20 daily bars: this is the shape that caused the 2026-07-08 whole-universe
    ;; poisoning, and it must keep going through the existing peel path.
    (let [aligned (align (sparse-points 20 1))]
      (is (nil? (:off-calendar-instrument-ids aligned))))))

(deftest lane-free-universe-emits-no-new-keys-test
  (testing "signature non-churn: a universe with no lane member is byte-identical"
    (let [aligned (align nil)]
      (is (not (contains? aligned :off-calendar-instrument-ids)))
      (is (not (contains? aligned :freshness-calendar))))))

(deftest sparse-vault-has-real-variance-not-a-risk-free-hole-test
  (testing "the DIAGONAL must be positive: pair-estimate returns 0 for pairs with
            fewer than 2 shared intervals and has no diagonal special case, so a
            lane member whose own variance collapsed to 0 would look risk-free and
            a max-Sharpe objective would pour into it"
    (let [aligned (align (sparse-points 31 13))
          ids (vec (sort (keys (:raw-price-series-by-instrument aligned))))
          {:keys [covariance]} (risk-mixed-frequency/matrix aligned ids)
          idx (.indexOf (to-array ids) vault-id)
          variance (get-in covariance [idx idx])]
      (is (nat-int? idx))
      (is (number? variance))
      (is (pos? variance) "the sparse vault must carry real variance")
      (is (js/isFinite variance)))))
