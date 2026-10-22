(ns hyperopen.vol-range.infrastructure.freshness-watcher
  "Store watcher that keeps the Vol Range daily candles fresh while the ticket
  is visible (HO-SPEC-014 v2.1 section 5.1). Automatic volatility is only valid
  from a series fetched successfully during the current UTC day and within the
  fetch TTL, so a ticket left open across the UTC rollover, or past the TTL,
  must refetch without the user switching modes.

  The watcher is a pure decision (`actions/daily-refresh-due?`) evaluated on
  store changes; it dispatches the existing refresh action, which stamps the
  request time, so the loop quiesces until the retry window passes (a failed
  fetch retries after `:daily-retry-ms`). It only ever dispatches the candle
  refresh action; no order, signing or storage effect (VR-24)."
  (:require [hyperopen.platform :as platform]
            [hyperopen.vol-range.actions :as actions]))

(def ^:private watch-key ::vol-range-freshness)

(defn install-vol-range-freshness-watcher!
  [{:keys [store dispatch! now-ms-fn]
    :or {now-ms-fn platform/now-ms}}]
  (remove-watch store watch-key)
  (add-watch store watch-key
             (fn [_ _ _ new-state]
               (when (actions/daily-refresh-due? new-state (now-ms-fn))
                 ;; Defer so the dispatch never nests inside the triggering swap!.
                 (platform/queue-microtask!
                  (fn []
                    (when (actions/daily-refresh-due? @store (now-ms-fn))
                      (dispatch! store nil [[:actions/refresh-vol-range-daily-candles]])))))))
  store)
