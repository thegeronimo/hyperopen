(ns hyperopen.vol-range.infrastructure.executor-replace
  "Confirm the cancellation of a ladder being updated and refresh the account
  for its replacement (HO-SPEC-014 v2.1 section 10.2 steps 3-4, 13.4; VR-15,
  VR-16).

  Reads only: nothing here signs or sends. The grid is reconciled from
  authoritative reads (open orders, order statuses, fills), which also
  persists it and releases any reservation the cancel settled. The
  replacement basis is then judged for the FROZEN membership, and only when
  it is ready is an awaited account barrier read taken, so the replacement is
  planned from inventory that already includes fills during the cancel.
  Placement admission re-reads again before anything is sent."
  (:require [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.reads :as reads]))

(defn- refresh-account!
  [deps grid basis]
  (let [scope (:scope grid)]
    (-> (reads/read-account-snapshot! (:post-info! deps)
                                      {:account-address (:account-address scope)
                                       :barrier-id ((:random-id! deps) "rb")
                                       :now-ms! (:now-ms! deps)})
        (.then (fn [read]
                 (if-not (:complete? read)
                   {:status :error :errors [:guard/account-unavailable] :error (:error read)
                    :grid grid :basis basis}
                   {:status :ready :grid grid :basis basis
                    :refreshed {:requested-at-ms (:requested-at-ms read)
                                :account (snapshots/account-snapshot-from-read scope read ((:live! deps)))}}))))))

(defn confirm!
  "Reconcile `grid` and judge the replacement basis for the frozen
  membership ({:frozen-cloids :filled-at-freeze} from
  `replace-flow/freeze`). Resolves to
  {:status :ready :grid :basis :refreshed {:requested-at-ms :account}} when a
  replacement may be reviewed, {:status :blocked :grid :basis} while unknown
  or open old legs or missing fills block it, or {:status :error ...} when
  the account read or anything else failed (check again). Never rejects."
  [deps {:keys [grid frozen-cloids filled-at-freeze]}]
  (let [frozen {:frozen-cloids frozen-cloids :filled-at-freeze filled-at-freeze}]
    (-> (ex/reconcile! deps grid)
        (.then (fn [{:keys [grid]}]
                 (let [basis (lifecycle/replacement-basis grid frozen)]
                   (if (:ready? basis)
                     (refresh-account! deps grid basis)
                     {:status :blocked :grid grid :basis basis}))))
        (.catch (fn [e] {:status :error :error (ex/error-text e) :grid grid})))))
