(ns hyperopen.portfolio.optimizer.application.history-cache
  "Stale-while-revalidate policy for the per-wallet persisted history bundle
  (`history-bundle::<address>`, IndexedDB). A restored draft is by definition
  a repeat visit over daily-candle data that barely changes intra-day, so the
  last successful normalized bundle hydrates instantly on restore while the
  normal history load runs in the background and replaces it when it lands.

  Pure record/hydration policy only — IndexedDB IO lives in
  infrastructure.persistence and the restore effect adapter, per
  docs/BROWSER_STORAGE.md."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def history-cache-record-version 1)

(def max-hydration-age-ms
  "Hydration cap: a bundle older than this stays on disk but never hydrates —
  week-old daily candles flashing as current would mislead more than a
  loading state does. The background revalidate overwrites the record on its
  next success either way."
  (* 7 24 60 60 1000))

(def ^:private persisted-history-keys
  [:api-v2-history
   :candle-history-by-coin
   :funding-history-by-coin
   :vault-details-by-address
   :warnings
   :loaded-at-ms])

(defn- history-data-worth-persisting?
  [history-data]
  (boolean
   (and (map? history-data)
        (:loaded-at-ms history-data)
        (or (seq (get-in history-data [:api-v2-history :series-by-instrument]))
            (seq (:candle-history-by-coin history-data))))))

(defn history-cache-record
  "Persistable record of the CURRENT history data, or nil when there is
  nothing worth writing (no load has landed, or the bundle is empty)."
  [state address now-ms]
  (let [address* (account-context/normalize-address address)
        history-data (get-in state contracts/history-data-path)]
    (when (and address*
               (history-data-worth-persisting? history-data))
      {:version history-cache-record-version
       :address address*
       :saved-at-ms now-ms
       :history-data (select-keys history-data persisted-history-keys)})))

(defn- usable-history-cache-record?
  [record address now-ms]
  (let [address* (account-context/normalize-address address)]
    (boolean
     (and (map? record)
          (= history-cache-record-version (:version record))
          address*
          (= address* (account-context/normalize-address (:address record)))
          (number? (:saved-at-ms record))
          (<= (- now-ms (:saved-at-ms record)) max-hydration-age-ms)
          (history-data-worth-persisting? (:history-data record))))))

(defn hydrate-history-cache
  "State with the cached bundle applied, or nil when hydration must not run.
  Hydration NEVER clobbers: it only applies while the in-memory history data
  is still empty (no load landed, nothing merged), so a fetch that raced
  ahead of the IndexedDB read always wins. `history-load-state` is left
  untouched — the background revalidate stamps its own :loading immediately,
  and readiness/cards judge usability from the hydrated data itself."
  [state record address now-ms]
  (let [history-data (get-in state contracts/history-data-path)]
    (when (and (usable-history-cache-record? record address now-ms)
               (not (:loaded-at-ms history-data))
               (empty? (get-in history-data
                               [:api-v2-history :series-by-instrument])))
      (assoc-in state
                contracts/history-data-path
                (assoc (:history-data record) :restored-from-cache? true)))))
