(ns hyperopen.vol-range.infrastructure.execution-watcher
  "Keeps Vol Range ladders current in a build that includes placement
  (HO-SPEC-014 v2.1 sections 7.6, 9.3, 9.4).

  - When the trading account or the active market changes, sync ladders from
    the journal and settle/discover for the active spot market, so the
    journal is restored before any new grid.
  - Every `reconcile-interval-ms` while the page is visible, reconcile
    ladders that are not closed (unknown outcomes, cancels, fills).
  - Live monitoring (`[:vol-range :execution :monitoring]`): when WebSocket
    health, the trading account, monitoring or the resync's busy flag
    changes, one coalesced microtask folds health and account into the
    monitoring model. The same interval ticks the model even while hidden
    (a gap over the policy's suspension window means the page was asleep),
    and returning to the page ticks it at once. Whenever a resync is due and
    the page is visible, `:actions/resync-vol-ladders` is dispatched.

  It only dispatches actions and writes the monitoring map; the work runs in
  the vol-range-execute effect. No new timer: the reconcile interval is the
  monitoring tick. Inactive in builds without placement."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.application.monitoring :as monitoring]
            [hyperopen.vol-range.execution-actions :as execution-actions]
            [hyperopen.vol-range.infrastructure.execution-gate :as gate]))

(def ^:private watch-key ::vol-range-execution)
(def reconcile-interval-ms 15000)
(def ^:private monitoring-path [:vol-range :execution :monitoring])
(defonce ^:private interval-id (atom nil))
(defonce ^:private visibility-listener (atom nil))
(defonce ^:private observe-scheduled? (atom false))

(defn- active? []
  (and (config/vol-range-preview-enabled?) (config/vol-range-execution-built?)))

(defn- default-document [] (when (exists? js/document) js/document))

(defn- visible? [doc] (or (nil? doc) (not (.-hidden doc))))

;; ---------------------------------------------------------------------------
;; Monitoring
;; ---------------------------------------------------------------------------

(defn- write-monitoring!
  "Apply `f` to the current monitoring map; write only when it changed."
  [store f]
  (let [m (get-in @store monitoring-path)
        m* (f m)]
    (when-not (= m m*)
      (swap! store assoc-in monitoring-path m*))))

(defn- observe-current [state now-ms m]
  (monitoring/observe m {:account (account-context/active-trading-account-address state)
                         :health (get-in state [:websocket :health])
                         :now-ms now-ms}))

(defn- maybe-resync!
  "Dispatch the resync when one is due and the page is visible. Returns
  true when it dispatched."
  [{:keys [store dispatch! document]}]
  (when (and (monitoring/resync-due? (get-in @store monitoring-path)) (visible? document))
    (dispatch! store nil [[:actions/resync-vol-ladders]])
    true))

(defn- observe! [{:keys [store now-ms-fn] :as ctx}]
  (write-monitoring! store #(observe-current @store (now-ms-fn) %))
  (maybe-resync! ctx))

(defn- tick!
  "One monitoring tick: suspension check, retry timing, then health. Returns
  true when it dispatched a resync."
  [{:keys [store now-ms-fn] :as ctx}]
  (let [now (now-ms-fn)]
    (write-monitoring! store #(->> (monitoring/on-tick % {:now-ms now})
                                   (observe-current @store now)))
    (maybe-resync! ctx)))

(defn- schedule-observe! [ctx]
  (when (compare-and-set! observe-scheduled? false true)
    (platform/queue-microtask!
     (fn []
       (reset! observe-scheduled? false)
       (observe! ctx)))))

(defn- resync-busy? [state] (true? (get-in state [:vol-range :execution :busy [:resync]])))

(defn- monitoring-inputs-changed? [old-state new-state]
  (or (not (identical? (get-in old-state [:websocket :health]) (get-in new-state [:websocket :health])))
      (not (identical? (get-in old-state monitoring-path) (get-in new-state monitoring-path)))
      (not= (resync-busy? old-state) (resync-busy? new-state))
      (not= (account-context/active-trading-account-address old-state)
            (account-context/active-trading-account-address new-state))))

(defn- fresh-monitoring
  "Monitoring for the current account, stale; a reinstall keeps epochs
  increasing so no earlier resync can count."
  [state now-ms]
  (let [old (get-in state monitoring-path)]
    (cond-> (monitoring/initial (account-context/active-trading-account-address state) now-ms)
      (number? (:epoch old)) (assoc :epoch (inc (:epoch old))))))

(defn- install-visibility-listener!
  "Tick at once when the page becomes visible again; a reinstall removes the
  previous listener."
  [{:keys [document] :as ctx}]
  (when-let [{:keys [doc f]} @visibility-listener]
    (.removeEventListener doc "visibilitychange" f))
  (reset! visibility-listener nil)
  (when document
    (let [f (fn [] (when (visible? document) (tick! ctx)))]
      (.addEventListener document "visibilitychange" f)
      (reset! visibility-listener {:doc document :f f}))))

;; ---------------------------------------------------------------------------
;; Install
;; ---------------------------------------------------------------------------

(defn install-vol-range-execution-watcher!
  "`now-ms-fn` and `document` are optional (tests); they default to the
  platform clock and the browser document."
  [{:keys [store dispatch! now-ms-fn document] :as deps}]
  (when (active?)
    (let [ctx {:store store :dispatch! dispatch!
               :now-ms-fn (or now-ms-fn platform/now-ms)
               :document (if (contains? deps :document) document (default-document))}]
      ;; Placement reads the gate and cap live on every operation; this copy
      ;; only drives the dock and commit button and is refreshed after each
      ;; operation.
      (swap! store (fn [state]
                     (update-in state [:vol-range :execution] merge
                                {:enabled? (gate/enabled?) :max-ladder-usdc (gate/max-ladder-usdc)
                                 :monitoring (fresh-monitoring state ((:now-ms-fn ctx)))})))
      (reset! observe-scheduled? false)
      (remove-watch store watch-key)
      (add-watch store watch-key
                 (fn [_ _ old-state new-state]
                   (let [k (execution-actions/sync-key new-state)]
                     (when (and (first k) (not= k (execution-actions/sync-key old-state)))
                       (platform/queue-microtask!
                        #(dispatch! store nil [[:actions/sync-vol-ladders]]))))
                   (when (monitoring-inputs-changed? old-state new-state)
                     (schedule-observe! ctx))))
      (install-visibility-listener! ctx)
      (when-let [id @interval-id] (platform/clear-interval! id))
      (reset! interval-id
              (platform/set-interval!
               (fn []
                 ;; The tick runs while hidden too (suspension detection). REST
                 ;; work only while visible, and never on top of a resync, which
                 ;; reconciles every open ladder itself.
                 (let [resync-dispatched? (tick! ctx)]
                   (when (and (visible? (:document ctx))
                              (not resync-dispatched?)
                              (not (resync-busy? @store)))
                     (dispatch! store nil [[:actions/reconcile-open-vol-ladders]]))))
               reconcile-interval-ms))
      (schedule-observe! ctx)))
  store)
