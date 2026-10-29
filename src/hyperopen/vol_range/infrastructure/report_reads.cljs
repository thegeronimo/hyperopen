(ns hyperopen.vol-range.infrastructure.report-reads
  "Bounded, low-priority REST reads for Vol Range reporting (HO-SPEC-014
  v2.1 sections 9.2, 9.4, 12.4).

  Reads go through the shared `post-info!` request policy at `:priority
  :low`, so backfill never competes with reconciliation or cancel. Each
  resolves with the rows read so far plus whether the read is COMPLETE;
  incompleteness (a failed page, an unexpected shape, no time progress, the
  page cap) is reported, never hidden, and never rejects. Nothing here
  writes to the app store or to storage."
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.market :as market]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- normalize-address [a] (some-> a str str/trim str/lower-case not-empty))
(defn- error-text [e] (if (instance? js/Error e) (.-message e) (str e)))
(defn- policy [k] (get contracts/report-policy k))

(defn read-ledger-window!
  "Raw `userNonFundingLedgerUpdates` rows for one account in
  `[start-ms, end-ms]`, paged forward from the newest row's time with
  overlap; overlap duplicates (identical rows) are removed. Resolves to
  {:rows :complete? :requested-at-ms :received-at-ms :error :reason} where
  `:reason` is :ledger/read-error, :ledger/no-progress or :ledger/page-cap
  when incomplete. Never rejects."
  [post-info! {:keys [account-address start-ms end-ms read-id now-ms!]}]
  (let [address (normalize-address account-address)
        now-ms! (or now-ms! #(.now js/Date))
        requested-at (now-ms!)
        page-limit (policy :ledger-page-limit)
        max-pages (policy :max-ledger-pages)
        done (fn [rows complete? error reason]
               {:rows (vec (distinct rows)) :complete? complete? :requested-at-ms requested-at
                :received-at-ms (now-ms!) :error error :reason reason})]
    (if-not (and address (number? start-ms) (number? end-ms))
      (js/Promise.resolve (done [] false "Account and window are required" :ledger/read-error))
      (letfn [(page [from rows n]
                (-> (post-info! {"type" "userNonFundingLedgerUpdates" "user" address
                                 "startTime" (js/Math.floor from) "endTime" (js/Math.floor end-ms)}
                                {:priority :low :cache-ttl-ms 0
                                 :dedupe-key [:vol-range-report :ledger address read-id from]})
                    (.then (fn [resp]
                             (if-not (sequential? resp)
                               (done rows false "Unexpected ledger response shape" :ledger/read-error)
                               (let [rows (into rows resp)
                                     newest (reduce max from (filter number? (map :time resp)))]
                                 (cond
                                   (< (count resp) page-limit) (done rows true nil nil)
                                   (<= newest from) (done rows false "Ledger pagination made no progress"
                                                          :ledger/no-progress)
                                   (>= (inc n) max-pages) (done rows false "Ledger page cap reached"
                                                                :ledger/page-cap)
                                   :else (page newest rows (inc n)))))))
                    (.catch (fn [e] (done rows false (error-text e) :ledger/read-error)))))]
        (page start-ms [] 0)))))

(defn read-hourly-candles!
  "Completed and in-progress 1h candles for `coin` covering
  `[from-ms, to-ms)`, paged BACKWARD by `:end-time-ms` with at most
  `:candle-page-bars` bars per request and `:max-candle-pages` requests.
  Resolves to {:candles [raw rows, distinct, by open time] :complete?
  :requested-at-ms :received-at-ms :error}. An empty page is a market with no
  older candles, not a failure. Never rejects."
  [post-info! {:keys [coin from-ms to-ms now-ms!]}]
  (let [now-ms! (or now-ms! #(.now js/Date))
        requested-at (now-ms!)
        hour (policy :hour-ms)
        page-bars (policy :candle-page-bars)
        max-pages (policy :max-candle-pages)
        done (fn [rows complete? error]
               {:candles (->> rows (filter map?) distinct (sort-by :t) vec)
                :complete? complete? :requested-at-ms requested-at :received-at-ms (now-ms!) :error error})]
    (if-not (and (string? coin) (number? from-ms) (number? to-ms) (< from-ms to-ms))
      (js/Promise.resolve (done [] false "Coin and window are required"))
      (letfn [(page [end rows n]
                (let [bars (min page-bars (js/Math.ceil (/ (- end from-ms) hour)))]
                  (-> (market/request-candle-snapshot! post-info! now-ms! coin
                                                       {:interval :1h :bars bars :priority :low :end-time-ms end})
                      (.then (fn [resp]
                               (if-not (sequential? resp)
                                 (done rows false "Unexpected candle response shape")
                                 (let [rows (into rows resp)
                                       next-end (- end (* bars hour))]
                                   (cond
                                     (<= next-end from-ms) (done rows true nil)
                                     (empty? resp) (done rows true nil)
                                     (>= (inc n) max-pages) (done rows false "Candle page cap reached")
                                     :else (page next-end rows (inc n)))))))
                      (.catch (fn [e] (done rows false (error-text e)))))))]
        (page to-ms [] 0)))))
