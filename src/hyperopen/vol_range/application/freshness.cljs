(ns hyperopen.vol-range.application.freshness
  "Daily-candle freshness for automatic volatility (HO-SPEC-014 v2.1 section 5.1).

  A stored 1d series is only trusted when it was SUCCESSFULLY fetched during the
  current UTC day and within the fetch TTL. Otherwise the series may still hold
  an in-progress bar (fetched mid-day) that would enter the Parkinson window
  after the UTC rollover. Provenance lives at
  `[:vol-range :daily-fetch coin]`:

  - `:completed-at-ms` is stamped when a fetch SUCCEEDS (see
    `hyperopen.api.projections.market/apply-candle-snapshot-success`).
  - `:requested-at-ms` is stamped when a fetch is REQUESTED and only throttles
    retries; it never marks data fresh.

  Pure: time is injected."
  (:require [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.volatility :as volatility]))

(defn completed-at-ms [state coin]
  (let [v (get-in state [:vol-range :daily-fetch coin :completed-at-ms])]
    (when (number? v) v)))

(defn requested-at-ms [state coin]
  (let [v (get-in state [:vol-range :daily-fetch coin :requested-at-ms])]
    (when (number? v) v)))

(defn stale-reason
  "Why daily data fetched at `completed-at` cannot be trusted at `now-ms`:
  :never-fetched, :previous-day or :ttl-expired; nil when fresh."
  [completed-at now-ms]
  (cond
    (not (number? completed-at)) :never-fetched
    (< (volatility/utc-day-start-ms completed-at) (volatility/utc-day-start-ms now-ms)) :previous-day
    (> (- now-ms completed-at) (:daily-fetch-ttl-ms contracts/product-policy)) :ttl-expired
    :else nil))

(defn stale?
  [state coin now-ms]
  (some? (stale-reason (completed-at-ms state coin) now-ms)))

(defn refetch-due?
  "True when the daily series is stale and no fetch was requested within the
  retry window, so a failed fetch retries soon instead of after the TTL and an
  in-flight fetch is not duplicated."
  [state coin now-ms]
  (and (stale? state coin now-ms)
       (let [requested (requested-at-ms state coin)]
         (or (nil? requested)
             (> (- now-ms requested) (:daily-retry-ms contracts/product-policy))))))
