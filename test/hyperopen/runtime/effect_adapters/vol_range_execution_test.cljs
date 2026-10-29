(ns hyperopen.runtime.effect-adapters.vol-range-execution-test
  "The execution adapter's store writes (HO-SPEC-014 v2.1 sections 8.2, 9.1):
  a flow that finishes with an older grid copy merges into the store instead
  of replacing it, the journal sync keeps evidence only the store has seen
  and drops another account's ladders, the open-ladder reconciliation
  resolves to one result per open ladder, successful background operations
  leave `:last` alone, and the `:resync` operation drives live monitoring
  (spec 9.3) for its own account and epoch only. Simulator only; nothing
  reaches an exchange."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default.state :as api-state]
            [hyperopen.platform :as platform]
            [hyperopen.platform.locks :as locks]
            [hyperopen.runtime.effect-adapters.vol-range-execution :as vre]
            [hyperopen.runtime.effect-adapters.vol-range-resync :as resync]
            [hyperopen.vol-range.application.monitoring :as monitoring]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.infrastructure.execution-gate :as gate]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- place-and-reconcile!
  "Place the spec 13.2 ladder in the simulator and reconcile it once."
  [s]
  (locks/reset-in-tab-state!)
  (-> (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope})
      (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
      (.then (fn [{:keys [grid]}] grid))))

(defn- with-runtime
  "Run `(f)` (a promise) with the adapter wired to `deps` and the simulator's
  info reads, the trading account being the simulator's `0xabc` (not a
  40-hex address, so the account context would not resolve it); restores
  everything afterwards."
  [s deps f]
  (let [orig-deps vre/executor-deps
        orig-enabled gate/enabled?
        orig-post api-state/post-info!
        orig-account account-context/active-trading-account-address
        restore! (fn []
                   (set! vre/executor-deps orig-deps)
                   (set! gate/enabled? orig-enabled)
                   (set! api-state/post-info! orig-post)
                   (set! account-context/active-trading-account-address orig-account))]
    (set! account-context/active-trading-account-address (fn [_] (:account-address sim/scope)))
    (set! vre/executor-deps (fn [_] deps))
    (set! gate/enabled? (constantly true))
    (set! api-state/post-info! (sim/post-info s))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f)))
        (.finally restore!))))

(defn- top-buy
  "The buy leg nearest the touch."
  [grid]
  (last (sort-by #(js/parseFloat (:price %)) (filter #(= :buy (:side %)) (vals (:legs grid))))))

(defn- stream-fill
  "A fill of all of `leg` that only the store has seen (as a WebSocket frame
  would deliver it); the venue and the journal never hear of it."
  [leg]
  {:coin sim/coin :px (:price leg) :sz (:original-base leg) :side "B" :time 1700000099000
   :oid (js/parseInt (:oid leg)) :cloid (:cloid leg) :tid 777001 :fee "0" :feeToken "USDC"
   :builderFee "0" :crossed false :hash "0x0"})

(defn- with-stream-fill [grid row]
  (evidence/ingest-fills grid {:account-address "0xabc" :rows [row] :received-at-ms 1700000099100}))

(defn- store-with [grids]
  (atom {:wallet {:address "0xabc"}
         :vol-range {:execution {:enabled? true :grids (into {} (map (juxt :grid-id identity)) grids)}}}))

(defn- store-grid [store grid-id] (get-in @store [:vol-range :execution :grids grid-id]))

(defn- journal-grid [s grid-id] (ex/stored-grid (sim/records s) grid-id))

(deftest a-reconcile-finishing-with-an-older-copy-keeps-store-evidence-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-and-reconcile! s)
          (.then
           (fn [grid]
             (let [gid (:grid-id grid)
                   leg (top-buy grid)
                   row (stream-fill leg)
                   store (store-with [grid])
                   injected? (atom false)
                   ;; The fill lands in the store after the reconcile took its
                   ;; copy (at its first read) and before it finishes.
                   deps (assoc (sim/deps s) :post-info!
                               (fn [body opts]
                                 (when (compare-and-set! injected? false true)
                                   (swap! store update-in [:vol-range :execution :grids gid] with-stream-fill row))
                                 ((sim/post-info s) body opts)))]
               (with-runtime s deps
                 #(.then (vre/vol-range-execute nil store :refresh {:grid-id gid})
                         (fn [_]
                           (let [g (store-grid store gid)
                                 l (get-in g [:legs (:cloid leg)])]
                             (is @injected?)
                             (is (= 1 (count (:fills g))) "the stream fill survives the reconcile")
                             (is (= :terminal (:order-state l)))
                             (is (= (:original-base leg) (:filled (lifecycle/leg-quantities g l))))
                             (testing "the reconcile's own copy predates the fill"
                               (is (empty? (:fills (journal-grid s gid))))
                               (is (= :resting (get-in (journal-grid s gid) [:legs (:cloid leg) :order-state]))))
                             (is (= {:op :refresh :status (lifecycle/status g)}
                                    (select-keys (get-in @store [:vol-range :execution :last]) [:op :status])))
                             (is (not (get-in @store [:vol-range :execution :busy [:refresh gid]]))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest sync-keeps-store-evidence-and-drops-other-accounts-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-and-reconcile! s)
          (.then
           (fn [grid]
             (let [gid (:grid-id grid)
                   leg (top-buy grid)
                   previous-account (-> grid
                                        (assoc :grid-id "grid-prev")
                                        (assoc-in [:scope :account-address] "0xdef"))
                   store-only (assoc grid :grid-id "grid-store-only")
                   store (store-with [(with-stream-fill grid (stream-fill leg)) previous-account store-only])]
               (with-runtime s (sim/deps s)
                 #(.then (vre/vol-range-execute nil store :sync {})
                         (fn [_]
                           (let [g (store-grid store gid)]
                             (is (= #{gid} (set (keys (get-in @store [:vol-range :execution :grids]))))
                                 "only the journal's ladders for the trading account remain")
                             (is (= 1 (count (:fills g))) "store-only evidence survives the sync")
                             (is (= :terminal (get-in g [:legs (:cloid leg) :order-state])))
                             (is (empty? (:fills (journal-grid s gid))))
                             (is (nil? (get-in @store [:vol-range :execution :last]))
                                 "a successful background sync is not announced"))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest reconcile-open-resolves-one-result-per-open-ladder-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-and-reconcile! s)
          (.then
           (fn [grid]
             (let [gid (:grid-id grid)
                   closed (-> grid
                              (assoc :grid-id "grid-closed")
                              (update :legs #(into {} (map (fn [[c l]] [c (assoc l :order-state :terminal
                                                                                   :terminal-reason :canceled)]))
                                                   %)))
                   store (store-with [grid closed])]
               (is (= :closed (lifecycle/status closed)))
               (with-runtime s (sim/deps s)
                 #(-> (@#'vre/reconcile-open! store)
                      (.then (fn [results]
                               (is (vector? results))
                               (is (= [gid] (mapv (comp :grid-id :grid) results)))
                               (is (every? :persisted? results))
                               (is (= [(lifecycle/status (store-grid store gid))] (mapv :status results)))))
                      (.then (fn [_] (vre/vol-range-execute nil store :reconcile-open {})))
                      (.then (fn [_]
                               (is (nil? (get-in @store [:vol-range :execution :last]))
                                   "a successful background reconcile is not announced"))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

;; ---------------------------------------------------------------------------
;; Background operations and `:last`
;; ---------------------------------------------------------------------------

(def ^:private repair-last {:op :repair :status :ok :grid-id "grid-1" :at-ms 1})

(deftest background-operations-only-record-failures-in-last-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-and-reconcile! s)
          (.then
           (fn [grid]
             (let [store (store-with [grid])
                   orig-reconcile ex/reconcile!]
               (swap! store assoc-in [:vol-range :execution :last] repair-last)
               (with-runtime s (sim/deps s)
                 #(-> (vre/vol-range-execute nil store :reconcile-open {})
                      (.then (fn [_]
                               (is (= repair-last (get-in @store [:vol-range :execution :last]))
                                   "a successful reconcile keeps the user's last outcome")
                               (set! ex/reconcile! (fn [_ _] (js/Promise.reject (js/Error. "storage broke"))))
                               (vre/vol-range-execute nil store :reconcile-open {})))
                      (.then (fn [_]
                               (is (= {:op :reconcile-open :status :error :error "storage broke"}
                                      (select-keys (get-in @store [:vol-range :execution :last])
                                                   [:op :status :error]))
                                   "a failing one replaces it")
                               (is (not (get-in @store [:vol-range :execution :busy [:reconcile-open]])))))
                      (.finally (fn [] (set! ex/reconcile! orig-reconcile))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

;; ---------------------------------------------------------------------------
;; Resync (spec 9.3)
;; ---------------------------------------------------------------------------

(def ^:private t-now 1700000500000)

(defn- resync-due-monitoring [epoch]
  {:account-address "0xabc" :status :resyncing :reasons [:reconnected] :epoch epoch
   :stale-since-ms (- t-now 1000) :live-since-ms nil :fills-snapshot-at-ms (- t-now 500)
   :last-close-at-ms (- t-now 2000) :last-tick-ms t-now :resync {:state :required :attempt 0}})

(defn- mon [store] (get-in @store [:vol-range :execution :monitoring]))

(defn- with-fixed-time
  "Run `(f)` (a promise) with the platform clock at `t-now` and the random
  source at 0.5 (zero jitter)."
  [f]
  (let [orig-now platform/now-ms
        orig-random platform/random-value]
    (set! platform/now-ms (constantly t-now))
    (set! platform/random-value (constantly 0.5))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f)))
        (.finally (fn []
                    (set! platform/now-ms orig-now)
                    (set! platform/random-value orig-random))))))

(defn- with-info-hook
  "Simulator deps whose info reads call `(hook body)` first."
  [s hook]
  (assoc (sim/deps s) :post-info! (fn [body opts] (hook body) ((sim/post-info s) body opts))))

(defn- resync-store [grid epoch]
  (doto (store-with [grid])
    (swap! assoc-in [:vol-range :execution :monitoring] (resync-due-monitoring epoch))))

(deftest a-complete-resync-makes-monitoring-live-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-and-reconcile! s)
          (.then
           (fn [grid]
             (let [store (resync-store grid 3)
                   sends (count (:sends @s))
                   reads (atom [])
                   deps (with-info-hook s #(swap! reads conj (get % "type")))]
               (with-fixed-time
                 #(with-runtime s deps
                    (fn []
                      (.then (vre/vol-range-execute nil store :resync {:epoch 3})
                             (fn [_]
                               (is (= [:live [] 3 {:state :idle :attempt 0}]
                                      ((juxt :status :reasons :epoch :resync) (mon store))))
                               (is (= t-now (:live-since-ms (mon store))))
                               (is (some #{"frontendOpenOrders"} @reads))
                               (is (some #{"userFillsByTime"} @reads) "the missed-fill window is read")
                               (is (= sends (count (:sends @s))) "a resync never sends anything")
                               (is (nil? (get-in @store [:vol-range :execution :last])))
                               (is (not (get-in @store [:vol-range :execution :busy [:resync]])))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest an-incomplete-fills-window-fails-with-backoff-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-and-reconcile! s)
          (.then
           (fn [grid]
             (let [store (resync-store grid 3)
                   deps (assoc (sim/deps s) :post-info!
                               (fn [body opts]
                                 (if (= "userFillsByTime" (get body "type"))
                                   (js/Promise.resolve {:unexpected "shape"})
                                   ((sim/post-info s) body opts))))]
               (with-fixed-time
                 #(with-runtime s deps
                    (fn []
                      (.then (vre/vol-range-execute nil store :resync {:epoch 3})
                             (fn [_]
                               (let [m (mon store)]
                                 (is (= [:resyncing 3] ((juxt :status :epoch) m)))
                                 (is (= {:state :failed :attempt 1 :next-at-ms (+ t-now 15000)}
                                        (select-keys (:resync m) [:state :attempt :next-at-ms])))
                                 (is (string? (get-in m [:resync :error])))
                                 (is (= :monitoring/resync-required
                                        (monitoring/placement-blocker m {:account "0xabc" :now-ms t-now})))
                                 (is (nil? (get-in @store [:vol-range :execution :last]))
                                     "an incomplete read is reported by monitoring, not announced"))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-resync-counts-only-for-its-epoch-and-account-test
  (async done
    (let [s (sim/new-sim)
          run! (fn [grid during]
                 (let [store (resync-store grid 3)
                       hooked? (atom false)
                       deps (with-info-hook s (fn [_] (when (compare-and-set! hooked? false true)
                                                        (swap! store update-in [:vol-range :execution :monitoring]
                                                               during))))]
                   (with-fixed-time
                     #(with-runtime s deps
                        (fn [] (.then (vre/vol-range-execute nil store :resync {:epoch 3})
                                      (fn [_] (is @hooked?) (mon store))))))))]
      (-> (place-and-reconcile! s)
          (.then
           (fn [grid]
             (-> (run! grid #(monitoring/on-fills-snapshot % {:account "0xabc" :now-ms (+ t-now 10)}))
                 (.then (fn [m]
                          (testing "a re-subscription during the resync starts epoch 4; the result is ignored"
                            (is (= [:resyncing [:resubscribed] 4 :required]
                                   ((juxt :status :reasons :epoch (comp :state :resync)) m))))
                          (run! grid #(assoc % :account-address "0xdef"))))
                 (.then (fn [m]
                          (testing "an account switch during the resync: the result is ignored"
                            (is (= [:resyncing 3 :running] ((juxt :status :epoch (comp :state :resync)) m)))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-resync-not-due-for-its-epoch-is-skipped-test
  (async done
    (let [store (atom {:vol-range {:execution {:monitoring (resync-due-monitoring 5)}}})
          calls (atom 0)
          count! (fn [_] (swap! calls inc) (js/Promise.resolve {:status :ok}))]
      (-> (resync/resync! store {:sync! count! :reconcile-open! count! :epoch 4 :timeout-ms 1000})
          (.then (fn [r]
                   (is (:skipped? r))
                   (is (zero? @calls) "a stale epoch reads nothing")
                   (is (= :required (get-in (mon store) [:resync :state])))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-resync-that-times-out-fails-the-attempt-test
  (async done
    (let [orig-account account-context/active-trading-account-address
          store (atom {:vol-range {:execution {:monitoring (resync-due-monitoring 2)}}})]
      (set! account-context/active-trading-account-address (fn [_] "0xabc"))
      (-> (resync/resync! store {:sync! (fn [_] (js/Promise. (fn [_ _] nil)))
                                 :reconcile-open! (fn [_] (js/Promise.resolve []))
                                 :epoch 2 :timeout-ms 5})
          (.then (fn [r]
                   (is (= :incomplete (:status r)))
                   (is (= {:state :failed :attempt 1}
                          (select-keys (:resync (mon store)) [:state :attempt])))
                   (is (re-find #"Timed out" (get-in (mon store) [:resync :error])))))
          (.finally (fn [] (set! account-context/active-trading-account-address orig-account)))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest completeness-rules-test
  (let [ok-r {:persisted? true :reads {:orders-complete? true :fills-complete? true}}]
    (is (resync/complete? {:status :ok} []))
    (is (resync/complete? {:status :ok :orders-complete? true} [ok-r ok-r]))
    (is (not (resync/complete? {:status :ok :orders-complete? false} [ok-r])) "recovery read incomplete orders")
    (is (not (resync/complete? {:status :journal-unavailable} [ok-r])))
    (is (not (resync/complete? {:status :ok} [(assoc ok-r :persisted? false)])))
    (is (not (resync/complete? {:status :ok} [(assoc-in ok-r [:reads :fills-complete?] false)])))
    (is (not (resync/complete? {:status :ok} [(assoc-in ok-r [:reads :orders-complete?] false)])))
    (is (not (resync/complete? {:status :ok} [{:persisted? false :reads nil}])))))

;; ---------------------------------------------------------------------------
;; Placement gate input (spec 9.3)
;; ---------------------------------------------------------------------------

(deftest live-inputs-report-the-monitoring-blocker-test
  (let [account "0x1234567890abcdef1234567890abcdef12345678"
        live-m {:account-address account :status :live :reasons [] :epoch 4
                :last-tick-ms (- t-now 15000) :resync {:state :idle :attempt 0}}
        blocker (fn [m]
                  (:monitoring-blocker
                   (vre/live-inputs {:wallet {:address account :agent {:status :ready}}
                                     :vol-range {:execution (cond-> {} m (assoc :monitoring m))}}
                                    t-now)))]
    (is (nil? (blocker live-m)) "live for the trading account with a recent tick")
    (is (= :monitoring/stale (blocker nil)) "no monitoring yet")
    (is (= :monitoring/stale (blocker (assoc live-m :status :stale :reasons [:transport-down]))))
    (is (= :monitoring/resync-required (blocker (assoc live-m :status :resyncing :reasons [:reconnected]))))
    (is (= :monitoring/suspended (blocker (assoc live-m :last-tick-ms (- t-now 45001))))
        "the tick gap is judged with the time the executor passes")
    (is (= :monitoring/stale (blocker (assoc live-m :account-address "0x9999999999999999999999999999999999999999")))
        "monitoring for another account never admits this one")))
