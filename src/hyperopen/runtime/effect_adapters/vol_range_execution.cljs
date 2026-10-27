(ns hyperopen.runtime.effect-adapters.vol-range-execution
  "Runtime adapter for Vol Range placement, reconciliation and cancellation
  (HO-SPEC-014 v2.1 sections 8-10, 15.2).

  Builds the executor's collaborators from the app store: the shared info
  request function, the committed journal, the delivery send path (shared
  signer, nonce coordinator and network guard), the account admission
  section, crypto entropy and the live planner inputs. Results land under
  `[:vol-range :execution]`:

    {:grids {grid-id grid} :busy {op-key true} :last {...} :selected-grid-id id}

  Placement requires the execution gate (build flag AND armed in this
  browser) and respects the local per-ladder cap. Cancel, refresh and sync do
  not need the gate: turning placement off must never strand live orders."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default.state :as api-state]
            [hyperopen.api.endpoints.account.portfolio :as portfolio-endpoints]
            [hyperopen.api.projections.user-fees :as user-fees]
            [hyperopen.api.trading.action-coordinator :as action-coordinator]
            [hyperopen.api.trading.delivery :as delivery]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
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
  and 8)."
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

(defn- put-grid [state grid]
  (cond-> state grid (assoc-in [:vol-range :execution :grids (:grid-id grid)] grid)))

(defn- finish! [store busy-key {:keys [grid] :as result} op]
  (swap! store (fn [state]
                 (-> state
                     (put-grid grid)
                     (update-in [:vol-range :execution :busy] dissoc busy-key)
                     (update-in [:vol-range :execution] merge
                                {:enabled? (gate/enabled?) :max-ladder-usdc (gate/max-ladder-usdc)})
                     (assoc-in [:vol-range :execution :last]
                               (-> result
                                   (select-keys [:status :errors :delivery :phase :error :counts :in-flight?])
                                   (assoc :op op :grid-id (:grid-id grid) :at-ms (platform/now-ms))))))))

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

(defn- place! [store]
  (let [plan (get-in @store [:vol-range :frozen-review])]
    (-> (refresh-fees! store)
        (.then (fn [_] (executor-place/place! (executor-deps store) {:frozen-plan plan :frozen-scope (:scope plan)})))
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
                 ;; Anything past admission may have committed a grid (and
                 ;; sent orders); load it from the journal so it is visible
                 ;; and cancelable even if the placement result was lost.
                 (if (contains? #{:refused :busy :coordination-unavailable} (:status result))
                   result
                   (.then (sync! store) (fn [_] result))))))))

(defn- grid-in-state [store grid-id] (get-in @store [:vol-range :execution :grids grid-id]))

(defn- account-grids
  "Every journal grid for the active trading account, any market."
  [records account]
  (filterv #(= (lower account) (lower (get-in % [:scope :account-address]))) (jr/grids records nil)))

(declare sync!)

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
                   (do (swap! store assoc-in [:vol-range :execution :grids]
                              (into {} (map (juxt :grid-id identity)) (account-grids records account)))
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
  "Reconcile each of the account's grids that is not closed, one at a time."
  [store]
  (let [deps (executor-deps store)
        open (remove #(contains? #{:closed :not-placed} (lifecycle/status %))
                     (vals (get-in @store [:vol-range :execution :grids])))]
    (reduce (fn [p g]
              (.then p (fn [_] (.then (ex/reconcile! deps g)
                                      (fn [{:keys [grid]}] (swap! store put-grid grid))))))
            (js/Promise.resolve nil)
            open)))

(def op-timeout-ms
  "Cancel, refresh and sync give up waiting after this long, so a stuck
  storage or lock never leaves their buttons disabled. Placement is bounded
  by the delivery path's own timeouts instead."
  60000)

(defn- bounded [op p]
  (if (= :place op)
    p
    (js/Promise.race
     #js [p (js/Promise. (fn [resolve _]
                           (platform/set-timeout! #(resolve {:status :error :error "Timed out; try again."})
                                                  op-timeout-ms)))])))

(defn vol-range-execute
  "Effect `[:effects/vol-range-execute op params]`. Ops: :place, :cancel
  {:grid-id}, :refresh {:grid-id}, :sync, :reconcile-open."
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
                        {:status :error :error (str "Unknown operation " op)})))))
        (.then (fn [result] (finish! store busy-key result op)))
        (.catch (fn [e] (fail! store busy-key op e))))))
