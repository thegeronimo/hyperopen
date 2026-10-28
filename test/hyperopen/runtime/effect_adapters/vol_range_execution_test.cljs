(ns hyperopen.runtime.effect-adapters.vol-range-execution-test
  "The execution adapter's store writes (HO-SPEC-014 v2.1 sections 8.2, 9.1):
  a flow that finishes with an older grid copy merges into the store instead
  of replacing it, the journal sync keeps evidence only the store has seen
  and drops another account's ladders, and the open-ladder reconciliation
  resolves to one result per open ladder. Simulator only; nothing reaches an
  exchange."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default.state :as api-state]
            [hyperopen.platform.locks :as locks]
            [hyperopen.runtime.effect-adapters.vol-range-execution :as vre]
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
                             (is (= {:op :sync :status :ok}
                                    (select-keys (get-in @store [:vol-range :execution :last]) [:op :status]))))))))))
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
                               (is (= {:op :reconcile-open :status :ok}
                                      (select-keys (get-in @store [:vol-range :execution :last]) [:op :status]))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))
