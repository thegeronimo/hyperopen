(ns hyperopen.vol-range.infrastructure.execution-watcher
  "Keeps Vol Range ladders current in a build that includes placement
  (HO-SPEC-014 v2.1 sections 7.6, 9.3).

  - When the trading account or the active market changes, sync ladders from
    the journal and settle/discover for the active spot market, so the
    journal is restored before any new grid.
  - Every `reconcile-interval-ms` while the page is visible, reconcile
    ladders that are not closed (unknown outcomes, cancels, fills).

  It only dispatches actions; the work runs in the vol-range-execute effect.
  Inactive in builds without placement."
  (:require [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.execution-actions :as execution-actions]
            [hyperopen.vol-range.infrastructure.execution-gate :as gate]))

(def ^:private watch-key ::vol-range-execution)
(def reconcile-interval-ms 15000)
(defonce ^:private interval-id (atom nil))

(defn- active? []
  (and (config/vol-range-preview-enabled?) (config/vol-range-execution-built?)))

(defn install-vol-range-execution-watcher!
  [{:keys [store dispatch!]}]
  (when (active?)
    ;; Placement reads the gate and cap live on every operation; this copy
    ;; only drives the dock and commit button and is refreshed after each
    ;; operation.
    (swap! store update-in [:vol-range :execution] merge
           {:enabled? (gate/enabled?) :max-ladder-usdc (gate/max-ladder-usdc)})
    (remove-watch store watch-key)
    (add-watch store watch-key
               (fn [_ _ old-state new-state]
                 (let [k (execution-actions/sync-key new-state)]
                   (when (and (first k) (not= k (execution-actions/sync-key old-state)))
                     (platform/queue-microtask!
                      #(dispatch! store nil [[:actions/sync-vol-ladders]]))))))
    (when-let [id @interval-id] (platform/clear-interval! id))
    (reset! interval-id
            (platform/set-interval!
             (fn []
               (when (or (not (exists? js/document)) (not (.-hidden js/document)))
                 (dispatch! store nil [[:actions/reconcile-open-vol-ladders]])))
             reconcile-interval-ms)))
  store)
