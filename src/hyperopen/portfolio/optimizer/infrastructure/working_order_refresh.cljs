(ns hyperopen.portfolio.optimizer.infrastructure.working-order-refresh
  "Keeps the open-orders view fresh while an execution run has WORKING orders.

  None of the streaming feeds cover named-dex (HIP-3) open orders — the websocket
  `openOrders` channel is default-dex only, webData2 is main-dex only, and the
  per-dex `frontendOpenOrders` snapshots are fetched only when explicitly
  refreshed. So once a run leaves a passive order resting on a named dex, the
  merged book never sees it (the row can't be amended) and never sees it LEAVE
  (a cancel elsewhere, or partial-fill corroboration, can't reconcile). This
  watcher polls the same surface refresh the staging flow uses, only while there
  is something working to watch.

  The store-watch gate must stay CHEAP (it runs on every mutation, and market
  data ticks constantly), so it reads only the frozen ledger — whose rows stay
  :resting forever by design (the ledger is the audit record; live status is a
  render-time reconcile). The reconcile therefore runs inside the slow interval
  tick, which self-stops the first time no row is still working."
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.application.view-model.execution-reconcile
             :as reconcile]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private watch-key ::working-order-refresh)

(def default-interval-ms
  "Fills already stream via userFills; the poll only serves amendability,
  partial-fill stamping, and cancel freshness. ~10 REST calls per tick (base +
  every named dex) at 20s stays far inside the /info budget."
  20000)

(defn- optimizer-route?
  [state]
  (str/starts-with? (str (get-in state [:router :path]))
                    "/portfolio/optimize"))

(defn latest-ledger-rows
  [state]
  (:rows (last (get-in state contracts/execution-history-path))))

(defn watch-candidate?
  "Cheap gate evaluated on every store change: only the frozen latest ledger is
  consulted (a run that ever rested reads true until it is discarded/re-staged).
  Whether those rows are STILL working is the interval's (reconciling) job."
  [state]
  (boolean
   (and (optimizer-route? state)
        (coercion/non-blank-text (get-in state [:wallet :address]))
        (some #(= :resting (:status %)) (latest-ledger-rows state)))))

(defn working-rows?
  "True while any row of the latest ledger attempt still reconciles to :resting
  against the live order/fill feeds — i.e. there is a live working order left."
  [state]
  (boolean
   (some #(= :resting (:status %))
         (reconcile/reconcile-rows state (latest-ledger-rows state)))))

(defn install-working-order-refresh!
  "Installs the store watcher + interval. Injectable timers/refresh keep tests
  synchronous; `refresh-open-orders!` is the same account-surface refresh the
  staging flow dispatches (base + per-dex frontendOpenOrders)."
  [{:keys [store refresh-open-orders! set-interval-fn clear-interval-fn
           interval-ms]
    :or {set-interval-fn platform/set-interval!
         clear-interval-fn platform/clear-interval!
         interval-ms default-interval-ms}}]
  (let [timer (atom nil)
        stop! (fn []
                (when-let [id @timer]
                  (clear-interval-fn id)
                  (reset! timer nil)))
        tick! (fn []
                (if (working-rows? @store)
                  (refresh-open-orders! store)
                  (stop!)))
        start! (fn []
                 (when-not @timer
                   (reset! timer (set-interval-fn tick! interval-ms))))]
    (remove-watch store watch-key)
    (add-watch store watch-key
               (fn [_ _ old-state new-state]
                 (let [was? (watch-candidate? old-state)
                       now? (watch-candidate? new-state)]
                   (when (not= was? now?)
                     (if now? (start!) (stop!))))))
    (if (watch-candidate? @store) (start!) (stop!))
    store))
