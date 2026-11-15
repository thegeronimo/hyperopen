(ns hyperopen.portfolio.optimizer.application.history-cache-test
  "Stale-while-revalidate policy for the per-wallet persisted history bundle:
  what is worth persisting, and every guard that must stop a hydration
  (staleness, wrong wallet, wrong version, data already present)."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-cache :as history-cache]))

(def ^:private address
  "0x1111111111111111111111111111111111111111")

(def ^:private other-address
  "0x2222222222222222222222222222222222222222")

(def ^:private loaded-history-data
  {:api-v2-history {:status :ok
                    :series-by-instrument
                    {"perp:BTC" {:instrument-id "hl:perp:BTC"
                                 :points [{:time-ms 1000 :close 100}]}}}
   :candle-history-by-coin {}
   :funding-history-by-coin {}
   :vault-details-by-address {}
   :warnings []
   :loaded-at-ms 5000
   :current-portfolio-history-data {:api-v2-history {}}
   :request-plan {:candle-requests []}})

(defn- state-with-history
  [history-data]
  {:portfolio {:optimizer {:history-data history-data}}})

(deftest history-cache-record-persists-loaded-bundles-test
  (let [record (history-cache/history-cache-record
                (state-with-history loaded-history-data) address 9000)]
    (is (= 1 (:version record)))
    (is (= address (:address record)))
    (is (= 9000 (:saved-at-ms record)))
    (is (= #{"perp:BTC"}
           (set (keys (get-in record [:history-data
                                      :api-v2-history
                                      :series-by-instrument])))))
    (is (not (contains? (:history-data record) :current-portfolio-history-data))
        "Holdings-moment data and request plans are not cached.")
    (is (not (contains? (:history-data record) :request-plan)))))

(deftest history-cache-record-skips-trivial-states-test
  (is (nil? (history-cache/history-cache-record
             (state-with-history nil) address 9000))
      "Nothing loaded yet - nothing to persist.")
  (is (nil? (history-cache/history-cache-record
             (state-with-history {:loaded-at-ms 5000
                                  :api-v2-history {:series-by-instrument {}}})
             address 9000))
      "An empty bundle is not worth a multi-MB record.")
  (is (nil? (history-cache/history-cache-record
             (state-with-history loaded-history-data) nil 9000))
      "No wallet, no record."))

(defn- fresh-record
  []
  (history-cache/history-cache-record
   (state-with-history loaded-history-data) address 9000))

(deftest hydrate-history-cache-applies-a-fresh-record-test
  (let [hydrated (history-cache/hydrate-history-cache
                  {} (fresh-record) address 10000)
        history-data (get-in hydrated [:portfolio :optimizer :history-data])]
    (is (some? hydrated))
    (is (= 5000 (:loaded-at-ms history-data)))
    (is (= #{"perp:BTC"}
           (set (keys (get-in history-data
                              [:api-v2-history :series-by-instrument])))))
    (is (true? (:restored-from-cache? history-data))
        "Hydrated data is marked so the persist watcher never writes it back.")))

(deftest hydrate-history-cache-guards-test
  (let [record (fresh-record)]
    (is (nil? (history-cache/hydrate-history-cache
               {} record address
               (+ 9000 history-cache/max-hydration-age-ms 1)))
        "A record past the age cap never hydrates.")
    (is (some? (history-cache/hydrate-history-cache
                {} record address
                (+ 9000 history-cache/max-hydration-age-ms)))
        "A record exactly at the cap still hydrates.")
    (is (nil? (history-cache/hydrate-history-cache
               {} record other-address 10000))
        "Another wallet's record never hydrates.")
    (is (nil? (history-cache/hydrate-history-cache
               {} (assoc record :version 999) address 10000))
        "An unknown record version never hydrates.")
    (is (nil? (history-cache/hydrate-history-cache
               (state-with-history loaded-history-data) record address 10000))
        "Hydration never clobbers data a load already delivered.")
    (is (nil? (history-cache/hydrate-history-cache {} nil address 10000)))))
