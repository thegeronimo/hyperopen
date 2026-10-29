(ns hyperopen.runtime.effect-adapters.vol-range-resync
  "The `:resync` operation of the Vol Range execution adapter (HO-SPEC-014
  v2.1 section 9.3): after live monitoring lost confidence, sync the
  account's ladders from the journal and reconcile every open ladder (raw
  open orders, balances, order statuses and the missed-fill window), then
  tell the monitoring model whether every read was complete.

  A resync only reads and persists through the existing sync and reconcile
  flows; it never sends anything. Its result counts only for the account and
  monitoring epoch it started in. The adapter passes its own `sync!` and
  `reconcile-open!`, so this namespace never requires the adapter (the
  adapter requires it)."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.application.monitoring :as monitoring]))

(def ^:private monitoring-path [:vol-range :execution :monitoring])

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn- error-text [e]
  (if (instance? js/Error e) (.-message e) (str e)))

(defn complete?
  "Whether a sync result and the open-ladder reconcile results prove complete
  reads: the sync is `:ok`, its recovery (when one ran) read a complete open
  orders list, and every reconcile persisted its grid after complete open
  order and fill reads."
  [sync-result reconcile-results]
  (and (= :ok (:status sync-result))
       (or (not (contains? sync-result :orders-complete?))
           (true? (:orders-complete? sync-result)))
       (every? (fn [r]
                 (and (true? (:persisted? r))
                      (true? (get-in r [:reads :orders-complete?]))
                      (true? (get-in r [:reads :fills-complete?]))))
               reconcile-results)))

(defn- incomplete-error [sync-result reconcile-results]
  (or (when-not (= :ok (:status sync-result))
        (or (:error sync-result) (some-> (:status sync-result) name)))
      (some :error reconcile-results)
      "Some reads were incomplete"))

(defn- bounded
  "`p`, or a failed result after `ms`; the timer is cleared when `p` settles."
  [p ms]
  (let [timer (atom nil)]
    (-> (js/Promise.race
         #js [p (js/Promise. (fn [resolve _]
                               (reset! timer (platform/set-timeout!
                                              #(resolve {:ok? false :error "Timed out; retrying."}) ms))))])
        (.finally #(some-> @timer platform/clear-timeout!)))))

(defn- work!
  "Sync, then reconcile the open ladders. Resolves to {:ok? :error :status}."
  [store sync! reconcile-open!]
  (-> (sync! store)
      (.then (fn [sync-result]
               (if (= :journal-unavailable (:status sync-result))
                 {:ok? false :status :journal-unavailable :error (:error sync-result)}
                 (.then (reconcile-open! store)
                        (fn [results]
                          (if (complete? sync-result results)
                            {:ok? true}
                            {:ok? false :error (incomplete-error sync-result results)}))))))))

(defn resync!
  "Run the resync for monitoring `epoch` if that epoch's resync is required;
  resolves to an operation result for the adapter's `finish!`. Any rejection
  or the `timeout-ms` bound finishes the epoch's resync as failed, so the
  monitoring model schedules a retry with backoff."
  [store {:keys [sync! reconcile-open! epoch timeout-ms]}]
  (let [account (lower (account-context/active-trading-account-address @store))
        started (get-in (swap! store update-in monitoring-path monitoring/on-resync-started
                               {:epoch epoch :now-ms (platform/now-ms)})
                        monitoring-path)]
    (if-not (and (= epoch (:epoch started)) (= :running (get-in started [:resync :state])))
      (js/Promise.resolve {:status :ok :skipped? true})
      (-> (bounded (work! store sync! reconcile-open!) timeout-ms)
          (.catch (fn [e] {:ok? false :error (error-text e)}))
          (.then (fn [{:keys [ok? error status]}]
                   (swap! store update-in monitoring-path monitoring/on-resync-finished
                          {:epoch epoch :account account :ok? ok? :error error :now-ms (platform/now-ms)
                           :jitter (- (* 0.4 (platform/random-value)) 0.2)})
                   ;; A storage failure is reported like a failed sync; an
                   ;; incomplete read is reported by the monitoring line only.
                   (cond-> {:status (or status (if ok? :ok :incomplete))}
                     error (assoc :error error))))))))
