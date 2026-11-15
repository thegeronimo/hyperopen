(ns hyperopen.portfolio.optimizer.application.history-cache-roundtrip-test
  "End-to-end unit repro of the stale-while-revalidate pipeline: a normalized
  api-v2 bundle -> cache record -> edn persistence round trip -> hydration ->
  readiness must keep a proxy asset (BTC) usable exactly as the live bundle
  did — the assumption cards configure from cache iff this holds."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-cache :as history-cache]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]))

(def ^:private day-ms 86400000)

(def ^:private address
  "0x1111111111111111111111111111111111111111")

(def ^:private btc-instrument
  {:instrument-id "perp:BTC"
   :market-type :perp
   :coin "BTC"
   :symbol "BTC-USDC"
   :optimizer-history/instrument-id "hl:perp:BTC"})

(def ^:private wlfi-instrument
  {:instrument-id "perp:WLFI"
   :market-type :perp
   :coin "WLFI"
   :symbol "WLFI-USDC"})

(def ^:private api-body
  (let [points (vec (for [i (range 1 401)]
                      {:time_ms (* i day-ms)
                       :close (+ 100 (mod i 7))
                       :return (when (> i 1) 0.001)}))]
    {:contract_version "optimizer-history-api-v2"
     :request_id "rid-roundtrip"
     :dataset_version "dv-roundtrip"
     :status "partial"
     :common_calendar (mapv #(* % day-ms) (range 1 401))
     :return_calendar (mapv #(* % day-ms) (range 2 401))
     :aligned_returns_by_instrument
     {"perp:BTC" {:instrument_id "hl:perp:BTC"
                  :returns (vec (repeat 399 0.001))}}
     :series_by_instrument
     {"perp:BTC" {:instrument_id "hl:perp:BTC"
                  :lineage_kind "native"
                  :series_kind "market_price"
                  :points points
                  :funding {:status "available" :annualized_carry 0.01}
                  :warnings []}}
     :warnings [{:code "missing-candle-history"
                 :instrument_id "perp:WLFI"}]}))

(def ^:private draft
  (assoc (optimizer-defaults/default-draft)
         :universe [wlfi-instrument btc-instrument]
         :history-assumptions
         {"perp:WLFI" {:behavior :proxy
                       :expected-return 0
                       :volatility 0.8
                       :max-weight 0.05
                       :proxy {:instrument-ids ["perp:BTC"]
                               :relationship-strength :medium
                               :prior-weights nil}}}))

(defn- state-with-history
  [history-data]
  {:wallet {:address address}
   :portfolio {:optimizer {:draft draft
                           :history-data history-data
                           :runtime {:as-of-ms (* 401 day-ms)
                                     :stale-after-ms (* 10 day-ms)}}}})

(defn- usable-ids
  [readiness]
  (request-builder/usable-proxy-id-set
   (get-in readiness [:request :history :eligible-instruments])
   (:history-assumptions draft)))

(defn- eligible-ids
  [readiness]
  (mapv :instrument-id
        (get-in readiness [:request :history :eligible-instruments])))

(deftest cached-bundle-round-trip-keeps-proxy-usable-test
  (let [normalized (api-v2/normalize-history-bundle
                    {:universe [wlfi-instrument btc-instrument]}
                    api-body)
        live-history {:api-v2-history normalized
                      :warnings (:warnings normalized)
                      :loaded-at-ms (* 401 day-ms)}
        live-state (state-with-history live-history)
        readiness-live (setup-readiness/build-readiness live-state)
        record (history-cache/history-cache-record live-state
                                                   address
                                                   (* 401 day-ms))
        record* (reader/read-string (pr-str record))
        hydrated (history-cache/hydrate-history-cache
                  (state-with-history nil) record* address (* 401 day-ms))
        readiness-cached (setup-readiness/build-readiness hydrated)]
    (is (= ["perp:BTC"] (eligible-ids readiness-live))
        "Live bundle: BTC's history reaches the risk model.")
    (is (contains? (usable-ids readiness-live) "perp:BTC"))
    (is (= record record*)
        "The edn persistence round trip is faithful.")
    (is (some? hydrated))
    (is (= ["perp:BTC"] (eligible-ids readiness-cached))
        "Cached bundle: alignment eligibility survives the round trip.")
    (is (contains? (usable-ids readiness-cached) "perp:BTC")
        "Cached bundle: BTC stays usable, so the assumption card configures
        from cache.")))

(deftest cached-bundle-hydration-matches-browser-restore-conditions-test
  (let [normalized (api-v2/normalize-history-bundle
                    {:universe [wlfi-instrument btc-instrument]}
                    api-body)
        live-history {:api-v2-history normalized
                      :warnings (:warnings normalized)
                      :loaded-at-ms (* 401 day-ms)}
        record* (reader/read-string
                 (pr-str (history-cache/history-cache-record
                          (state-with-history live-history)
                          address
                          (* 401 day-ms))))
        restored-draft (contracts/migrate-draft
                        (reader/read-string (pr-str draft)))
        hydrated (-> (state-with-history nil)
                     (assoc-in [:portfolio :optimizer :draft] restored-draft)
                     (history-cache/hydrate-history-cache record*
                                                          address
                                                          (* 401 day-ms))
                     (assoc-in [:portfolio :optimizer :history-load-state]
                               {:status :loading
                                :request-signature {:universe [:revalidate]}
                                :started-at-ms (* 401 day-ms)
                                :completed-at-ms nil
                                :error nil
                                :warnings []}))
        readiness (setup-readiness/build-readiness hydrated)]
    (is (= ["perp:BTC"] (eligible-ids readiness))
        "Migrated-draft + in-flight revalidate: BTC still aligns from cache.")
    (is (contains? (request-builder/usable-proxy-id-set
                    (get-in readiness [:request :history :eligible-instruments])
                    (:history-assumptions restored-draft))
                   "perp:BTC"))))
