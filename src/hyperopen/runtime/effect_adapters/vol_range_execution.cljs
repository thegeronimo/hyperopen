(ns hyperopen.runtime.effect-adapters.vol-range-execution
  "Runtime adapter for Vol Range placement, reconciliation and cancellation
  (HO-SPEC-014 v2.1 sections 8-10, 15.2).

  Builds the executor's collaborators from the app store: the shared info
  request function, the committed journal, the delivery send path (shared
  signer, nonce coordinator and network guard), the account admission
  section, crypto entropy and the live planner inputs. Results land under
  `[:vol-range :execution]`:

    {:grids {grid-id grid} :busy {op-key true} :last {...} :selected-grid-id id}

  Placement and repair (and the repair preview) require the execution gate
  (build flag AND armed in this browser) and respect the local per-ladder
  cap. Cancel, refresh and sync do not need the gate: turning placement off
  must never strand live orders."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default.state :as api-state]
            [hyperopen.api.endpoints.account.portfolio :as portfolio-endpoints]
            [hyperopen.api.projections.user-fees :as user-fees]
            [hyperopen.api.trading.action-coordinator :as action-coordinator]
            [hyperopen.api.trading.delivery :as delivery]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.effect-adapters.vol-range-repair-replace :as repair-replace]
            [hyperopen.runtime.effect-adapters.vol-range-resync :as resync]
            [hyperopen.vol-range.application.grid-store :as grid-store]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.monitoring :as monitoring]
            [hyperopen.vol-range.application.plan-state :as plan-state]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.execution-actions :as execution-actions]
            [hyperopen.vol-range.infrastructure.entropy :as entropy]
            [hyperopen.vol-range.infrastructure.execution-gate :as gate]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-cancel :as executor-cancel]
            [hyperopen.vol-range.infrastructure.executor-place :as executor-place]
            [hyperopen.vol-range.infrastructure.journal :as journal]))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn live-inputs
  "What the executor re-reads at admission and before HTTP (spec 8.1 step 1
  and 8). `:monitoring-blocker` is nil only while live monitoring is `:live`
  for the trading account with a recent watcher tick (spec 9.3: reconcile
  before new placement)."
  [state now-ms]
  ;; The planner context is memoized per minute on inputs WITHOUT provenance
  ;; times, so a fee refresh with an unchanged payload keeps the cached input;
  ;; take the fee load time from state (spec 7.3 freshness).
  {:input (cond-> (:input (plan-state/context-for-state state now-ms))
            (number? (get-in state [:portfolio :user-fees-loaded-at-ms]))
            (assoc-in [:fees :loaded-at-ms] (get-in state [:portfolio :user-fees-loaded-at-ms])))
   :raw-account-mode (let [raw (get-in state [:account :abstraction-raw])] (when (string? raw) (str/trim raw)))
   :read-only? (boolean (account-context/mutations-blocked-message state))
   :execution-enabled? (gate/enabled?)
   :agent-ready? (= :ready (get-in state [:wallet :agent :status]))
   :monitoring-blocker (monitoring/placement-blocker (get-in state [:vol-range :execution :monitoring])
                                                     {:account (account-context/active-trading-account-address state)
                                                      :now-ms now-ms})
   :max-ladder-usdc (gate/max-ladder-usdc)})

(defn executor-deps [store]
  {:post-info! api-state/post-info!
   :journal {:commit! journal/commit! :load-all! journal/load-all!}
   :send! (fn [action opts]
            (delivery/sign-and-post-with-delivery! store (account-context/owner-address @store) action opts))
   :admission! action-coordinator/with-account-admission!
   :live! (fn [] (live-inputs @store (platform/now-ms)))
   :now-ms! platform/now-ms
   :random-id! entropy/random-id!
   :cloid-suffixes! entropy/random-cloid-suffixes!
   :network (contracts/current-network)})

;; ---------------------------------------------------------------------------
;; Store writes
;; ---------------------------------------------------------------------------

(defn- put-grid
  "Merge, never replace: a flow that finishes after another one changed the
  store copy (a stream fill, a cancel) must not erase that change."
  [state grid]
  (grid-store/merge-grid-into-state state grid))

(def ^:private background-ops #{:sync :reconcile-open :resync})

(defn- record-last?
  "Background operations report only failures in `:last`: a successful tick
  must neither be announced nor erase the outcome of the user's last action
  (spec 11.4); the monitoring line reports background state."
  [op {:keys [status]}]
  (or (not (contains? background-ops op))
      (contains? #{:error :journal-unavailable} status)))

(defn- finish! [store busy-key {:keys [grid] :as result} op]
  (when (= :repair-preview op)
    (repair-replace/settle-preview! store (second busy-key) result))
  (swap! store (fn [state]
                 (cond-> (-> state
                             (put-grid grid)
                             (grid-store/note-persisted result)
                             (update-in [:vol-range :execution :busy] dissoc busy-key)
                             (update-in [:vol-range :execution] merge
                                        {:enabled? (gate/enabled?) :max-ladder-usdc (gate/max-ladder-usdc)}))
                   (record-last? op result)
                   (assoc-in [:vol-range :execution :last]
                             (-> result
                                 (select-keys [:status :errors :delivery :phase :error :counts :in-flight?])
                                 (assoc :op op :grid-id (:grid-id grid) :at-ms (platform/now-ms))
                                 ;; A refused placement leaves the review open; this
                                 ;; lets the review show the refusal for its own plan.
                                 (cond-> (= :place op)
                                   (assoc :plan-id (get-in state [:vol-range :frozen-review :plan-id])))
                                 ;; Lets the open repair review show why ITS send was refused.
                                 (cond-> (= :repair op)
                                   (assoc :proposal-id (:proposal-id result)))))))))

(defn- fail! [store busy-key op e]
  (finish! store busy-key {:status :error :error (ex/error-text e)} op))

;; ---------------------------------------------------------------------------
;; Operations
;; ---------------------------------------------------------------------------

(defn- refresh-fees!
  "Fees must be at most 5 minutes old at the final guard (spec 7.3), and the
  app loads them once; refresh them before placing."
  [store]
  (let [address (account-context/active-trading-account-address @store)]
    (if (or (nil? address) (account-context/mutations-blocked-message @store))
      (js/Promise.resolve nil)
      (-> (portfolio-endpoints/request-user-fees! api-state/post-info! address {:cache-ttl-ms 0})
          (.then (fn [payload]
                   ;; Only for the account that is still active.
                   (when (and (map? payload)
                              (= (lower address) (lower (account-context/active-trading-account-address @store))))
                     (swap! store user-fees/apply-success address payload))))
          (.catch (fn [_] nil))))))

(declare sync!)

(defn- sync-after-send
  "Anything past admission may have committed a grid (and sent orders); load
  it from the journal so it is visible and cancelable even if the result was
  lost."
  [store result]
  (if (contains? #{:refused :busy :coordination-unavailable} (:status result))
    result
    (.then (sync! store) (fn [_] result))))

(defn- place! [store]
  (let [plan (get-in @store [:vol-range :frozen-review])
        ;; Recorded on the grid only; never part of what is sent.
        settings (ladder-settings/settings-from-plan plan (get-in @store [:vol-range :frozen-context :draft]))]
    (-> (refresh-fees! store)
        (.then (fn [_] (executor-place/place! (executor-deps store) {:frozen-plan plan :frozen-scope (:scope plan)
                                                                     :settings settings})))
        (.then (fn [{:keys [delivery] :as result}]
                 ;; Once anything may have been sent, the review is over: the
                 ;; dock now owns the outcome.
                 (when (or (contains? #{:response :unknown} delivery) (= :error (:status result)))
                   (swap! store #(-> %
                                     (assoc-in [:vol-range :ui :review-open?] false)
                                     (assoc-in [:vol-range :frozen-review] nil)
                                     (assoc-in [:vol-range :frozen-context] nil)
                                     (assoc-in [:vol-range :execution :selected-grid-id]
                                               (get-in result [:grid :grid-id])))))
                 (sync-after-send store result))))))

(defn- repair!
  "Send the frozen repair selection (fees refreshed first, like placement);
  the repair adapter closes the review once anything may have been sent."
  [store params]
  (-> (refresh-fees! store)
      (.then (fn [_] (repair-replace/repair! store (executor-deps store) params)))
      (.then (fn [result] (sync-after-send store result)))))

(defn- grid-in-state [store grid-id] (get-in @store [:vol-range :execution :grids grid-id]))

(defn- sync-once!
  "Load this account's grids from the journal; then settle and discover for
  the active spot market (spec 7.6: restore the journal before a new grid)."
  [store]
  (let [deps (executor-deps store)
        state @store
        account (account-context/active-trading-account-address state)
        scope (snapshots/current-scope state)]
    (-> (ex/load! deps)
        (.then (fn [{:keys [ok? records error]}]
                 (if-not ok?
                   {:status :journal-unavailable :error error}
                   ;; Every journal grid for the trading account, any market,
                   ;; merged with the store copy so store-only evidence survives.
                   (do (swap! store grid-store/merge-account-grids account (jr/grids records nil))
                       (if (and account (= :spot (:market-type scope)))
                         (ex/recover! deps {:scope scope :scope-key (contracts/scope-key scope)})
                         {:status :ok :grids []})))))
        (.then (fn [{:keys [grids] :as r}]
                 (swap! store (fn [s] (reduce put-grid s grids)))
                 (dissoc r :grids))))))

(defn- sync!
  "Sync, then sync again if the account or market changed meanwhile: a
  change during a sync is otherwise dropped as busy, and the dock would show
  the previous account's ladders."
  ([store] (sync! store 3))
  ([store tries]
   (let [k (execution-actions/sync-key @store)]
     (.then (sync-once! store)
            (fn [r]
              (if (and (pos? tries) (not= k (execution-actions/sync-key @store)))
                (sync! store (dec tries))
                r))))))

(defn- reconcile-open!
  "Reconcile, one at a time, each of the account's grids that is not closed
  or that stream evidence closed only in the store
  (`grid-store/reconcile-targets`), so such a close is persisted too. Each
  grid is reconciled from its store copy as of its turn. Resolves to the
  vector of `ex/reconcile!` results, one per grid reconciled."
  [store]
  (let [deps (executor-deps store)
        ids (mapv :grid-id (grid-store/reconcile-targets @store))]
    (reduce (fn [p gid]
              (.then p (fn [results]
                         (let [state @store
                               g (get-in state [:vol-range :execution :grids gid])]
                           (if-not (and g (grid-store/reconcile-target? state g))
                             results
                             (.then (ex/reconcile! deps g)
                                    (fn [{:keys [grid] :as result}]
                                      (swap! store #(-> % (put-grid grid) (grid-store/note-persisted result)))
                                      (conj results result))))))))
            (js/Promise.resolve [])
            ids)))

(def op-timeout-ms
  "Cancel, refresh, sync and the repair preview give up waiting after this
  long, so a stuck storage or lock never leaves their buttons disabled.
  Placement and repair are bounded by the delivery path's own timeouts
  instead: a signed action may still be in flight, and reporting a failure
  would invite a second click."
  60000)

(def ^:private unbounded-ops
  "Placement and repair may have a signed action in flight; the resync
  bounds itself (`op-timeout-ms`) so its timeout also fails the monitoring
  epoch's attempt."
  #{:place :repair :resync})

(defn- bounded [op p]
  (if (contains? unbounded-ops op)
    p
    (js/Promise.race
     #js [p (js/Promise. (fn [resolve _]
                           (platform/set-timeout! #(resolve {:status :error :error "Timed out; try again."})
                                                  op-timeout-ms)))])))

(defn vol-range-execute
  "Effect `[:effects/vol-range-execute op params]`. Ops: :place, :cancel
  {:grid-id}, :refresh {:grid-id}, :sync, :reconcile-open, :repair-preview
  {:grid-id}, :repair {:grid-id :proposal-id :legs}, :resync {:epoch}."
  [_ctx store op params]
  (let [grid-id (:grid-id params)
        busy-key (if grid-id [op grid-id] [op])]
    (swap! store assoc-in [:vol-range :execution :busy busy-key] true)
    (-> (bounded
         op
         (-> (js/Promise.resolve nil)
             (.then (fn [_]
                      (case op
                        :place (if (gate/enabled?)
                                 (place! store)
                                 {:status :refused :errors [:capability/execution-disabled]})
                        :cancel (if-let [g (grid-in-state store grid-id)]
                                  (executor-cancel/cancel! (executor-deps store) {:grid g})
                                  {:status :error :error "Unknown ladder"})
                        :refresh (if-let [g (grid-in-state store grid-id)]
                                   (ex/reconcile! (executor-deps store) g)
                                   {:status :error :error "Unknown ladder"})
                        :sync (sync! store)
                        :reconcile-open (.then (reconcile-open! store) (fn [_] {:status :ok}))
                        :resync (resync/resync! store {:sync! sync! :reconcile-open! reconcile-open!
                                                       :epoch (:epoch params) :timeout-ms op-timeout-ms})
                        :repair-preview (if (gate/enabled?)
                                          (repair-replace/repair-preview! store (executor-deps store) params)
                                          {:status :refused :errors [:capability/execution-disabled]})
                        :repair (if (gate/enabled?)
                                  (repair! store params)
                                  {:status :refused :errors [:capability/execution-disabled]})
                        {:status :error :error (str "Unknown operation " op)})))))
        (.then (fn [result] (finish! store busy-key result op)))
        (.catch (fn [e] (fail! store busy-key op e))))))
