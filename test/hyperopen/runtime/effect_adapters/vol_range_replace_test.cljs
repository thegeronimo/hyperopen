(ns hyperopen.runtime.effect-adapters.vol-range-replace-test
  "The ladder update operations end to end through
  `[:effects/vol-range-execute op params]` (HO-SPEC-014 sections 10.2, 13.4;
  VR-15, VR-16): the cancel freezes the membership of the journal-refreshed
  ladder, a fill during the cancel is never replenished, unknown outcomes
  block, a failed or timed-out check never leaves the update :canceling, a
  stopped update is never written, and the replacement placement carries
  its predecessor. Simulator only: nothing here reaches a real exchange."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.api.default.state :as api-state]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.platform :as platform]
            [hyperopen.platform.locks :as locks]
            [hyperopen.runtime.effect-adapters.vol-range-execution :as vre]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.infrastructure.execution-gate :as gate]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- place-live!
  "The 13.2 ladder, live and reconciled."
  [s]
  (locks/reset-in-tab-state!)
  (-> (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope})
      (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
      (.then (fn [{:keys [grid]}] grid))))

(defn- update-for [grid phase]
  {:grid-id (:grid-id grid) :scope-key (:scope-key grid) :phase phase :dialog-open? true :started-at-ms 5
   :settings-recorded? false :frozen-cloids nil :filled-at-freeze nil :basis nil :include-rejected #{}
   :refreshed nil :confirmed-at-ms nil})

(defn- store-for [grid]
  ;; What `cancel-vol-ladder-for-update` leaves: the update is :canceling.
  (atom {:wallet {:address "0xabc"}
         :vol-range {:execution {:enabled? true :grids {(:grid-id grid) grid}}
                     :replace (update-for grid :canceling)}}))

(defn- with-runtime
  "Run `(f)` (a promise) with the adapter wired to the simulator."
  [s {:keys [deps enabled? immediate-op-timeout?] :or {enabled? true}} f]
  (let [orig-deps vre/executor-deps
        orig-enabled gate/enabled?
        orig-post api-state/post-info!
        orig-timeout platform/set-timeout!
        restore! (fn []
                   (set! vre/executor-deps orig-deps)
                   (set! gate/enabled? orig-enabled)
                   (set! api-state/post-info! orig-post)
                   (set! platform/set-timeout! orig-timeout))]
    (set! vre/executor-deps (fn [_] (or deps (sim/deps s))))
    (set! gate/enabled? (constantly enabled?))
    (set! api-state/post-info! (sim/post-info s))
    (when immediate-op-timeout?
      (set! platform/set-timeout! (fn [g ms] (if (= ms vre/op-timeout-ms) (js/setTimeout g 0) (orig-timeout g ms)))))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f)))
        (.finally restore!))))

(defn- execute! [store op params] (vre/vol-range-execute nil store op params))

(defn- sell-86 [grid]
  (:cloid (first (filter #(and (= :sell (:side %)) (= 0 (:rung-index %))) (vals (:legs grid))))))

(defn- on-first-cancel
  "Deps that run `(f)` just before the first cancel action is sent: after
  the freeze, while the cancel is pending."
  [s f]
  (let [base (sim/deps s)
        done? (atom false)]
    (assoc base :send! (fn [action opts]
                         (when (and (not @done?) (str/starts-with? (str (:type action)) "cancel"))
                           (reset! done? true)
                           (f))
                         ((:send! base) action opts)))))

(defn- fill-during-cancel
  "3 of the 10-unit sell fill while the cancel is pending (spec 13.4)."
  [s grid]
  (on-first-cancel s (fn [] (sim/advance! s 10) (sim/fill! s (sell-86 grid) "3"))))

(defn- sum-sizes [orders]
  (precision/decimal->string (reduce precision/add (precision/parse-decimal "0")
                                     (map #(precision/parse-decimal (:s %)) orders))))

(deftest cancel-confirm-and-replacement-through-the-adapter-test
  (async done
    (let [s (sim/new-sim)
          ctx (atom {})]
      (-> (place-live! s)
          (.then
           (fn [grid]
             (let [store (store-for grid)]
               (swap! ctx assoc :store store :grid grid)
               ;; Placement off in this browser: cancellation must still run.
               (with-runtime s {:deps (fill-during-cancel s grid) :enabled? false}
                 #(execute! store :replace-cancel {:grid-id (:grid-id grid)})))))
          (.then
           (fn [_]
             (let [{:keys [store grid]} @ctx
                   u (get-in @store [:vol-range :replace])
                   old (get-in @store [:vol-range :execution :grids (:grid-id grid)])]
               (is (= :canceled (:phase u)) (pr-str (:basis u)))
               (is (= 7 (count (:frozen-cloids u))) "every working order frozen")
               (is (every? #(= "0" %) (vals (:filled-at-freeze u))) "the fill came after the freeze")
               (is (= {:buy "40" :sell "27"} (get-in u [:basis :remaining])) "the filled 3 are never replenished")
               (is (= {:buy "0" :sell "3"} (get-in u [:basis :filled-during-update])))
               (is (= "147" (get-in u [:refreshed :account :base :total])) "inventory after the fill")
               (is (number? (:confirmed-at-ms u)))
               (is (= :closed (lifecycle/status old)) "the store copy is the confirmed one")
               (is (= {:op :replace-cancel :status :ready}
                      (select-keys (get-in @store [:vol-range :execution :last]) [:op :status])))
               (is (not (get-in @store [:vol-range :execution :busy [:replace-cancel (:grid-id grid)]])))
               (is (= 7 (count (mapcat :cancels (sim/cancel-sends s)))) "each old order canceled once")
               (is (= 1 (count (sim/order-sends s))) "nothing placed")
               ;; Review replacement (the pure action) and the ordinary review.
               (swap! s assoc :draft (-> fixtures/draft-13-2
                                         (assoc-in [:buy :quantity] "40")
                                         (assoc-in [:sell :quantity] "27")))
               (swap! store #(-> %
                                 (assoc-in [:vol-range :replace :phase] :ready)
                                 (assoc-in [:vol-range :frozen-review] (sim/frozen-plan s))
                                 (assoc-in [:vol-range :frozen-context]
                                           {:replace {:predecessor {:grid-id (:grid-id grid)
                                                                    :frozen-cloids (:frozen-cloids u)
                                                                    :remaining (get-in u [:basis :remaining])
                                                                    :filled-during-update
                                                                    (get-in u [:basis :filled-during-update])
                                                                    :included-rejected []}}})
                                 (assoc-in [:vol-range :ui :review-open?] true)))
               (with-runtime s {} #(execute! store :place {})))))
          (.then
           (fn [_]
             (let [{:keys [store grid]} @ctx
                   last-result (get-in @store [:vol-range :execution :last])
                   new-id (get-in @store [:vol-range :execution :selected-grid-id])
                   new-grid (get-in @store [:vol-range :execution :grids new-id])]
               (is (= {:op :place :status :live} (select-keys last-result [:op :status])) (pr-str last-result))
               (is (= (:grid-id grid) (get-in new-grid [:predecessor :grid-id])) "the replacement records its predecessor")
               (is (= {:buy "40" :sell "27"} (get-in new-grid [:predecessor :remaining])))
               (is (nil? (get-in @store [:vol-range :replace])) "the update is over once the replacement is sent")
               (is (= 2 (count (sim/order-sends s))))
               (is (= "27" (sum-sizes (remove :b (:orders (second (sim/order-sends s))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest an-unknown-cancel-outcome-leaves-the-update-blocked-test
  ;; VR-15: no replacement review while an old order's outcome is unknown.
  (async done
    (let [s (sim/new-sim)
          ctx (atom {})]
      (-> (place-live! s)
          (.then (fn [grid]
                   (let [store (store-for grid)]
                     (swap! ctx assoc :store store :grid grid)
                     (sim/script! s {:kind :timeout-lost})
                     (with-runtime s {} #(execute! store :replace-cancel {:grid-id (:grid-id grid)})))))
          (.then (fn [_]
                   (let [{:keys [store grid]} @ctx
                         u (get-in @store [:vol-range :replace])]
                     (is (= :blocked (:phase u)))
                     (is (some #{:replace/unresolved-outcomes} (get-in u [:basis :blocking])))
                     (is (nil? (:refreshed u)))
                     (is (= :blocked (get-in @store [:vol-range :execution :last :status])))
                     (is (= 7 (count (:frozen-cloids u))))
                     (with-runtime s {} #(execute! store :replace-confirm {:grid-id (:grid-id grid)})))))
          (.then (fn [_]
                   (let [{:keys [store]} @ctx]
                     (is (= :blocked (get-in @store [:vol-range :replace :phase])) "Check again stays blocked")
                     (is (= 1 (count (sim/order-sends s))) "nothing is placed"))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest cancel-remaining-orders-keeps-the-first-freeze-test
  ;; A cancel refused before anything was sent leaves the old orders open;
  ;; canceling again later must keep the membership frozen the first time.
  (async done
    (let [s (sim/new-sim)
          ctx (atom {})]
      (-> (place-live! s)
          (.then (fn [grid]
                   (let [store (store-for grid)]
                     (swap! ctx assoc :store store :grid grid)
                     (swap! s assoc :live {:agent-ready? false})
                     (with-runtime s {} #(execute! store :replace-cancel {:grid-id (:grid-id grid)})))))
          (.then (fn [_]
                   (let [{:keys [store grid]} @ctx
                         u (get-in @store [:vol-range :replace])]
                     (is (= {:op :replace-cancel :status :refused :errors [:signer/agent-not-ready]}
                            (select-keys (get-in @store [:vol-range :execution :last]) [:op :status :errors])))
                     (is (= :blocked (:phase u)))
                     (is (= [:replace/legs-still-open] (get-in u [:basis :blocking])))
                     (is (= 0 (count (sim/cancel-sends s))))
                     (swap! ctx assoc :frozen (:frozen-cloids u))
                     (swap! s assoc :live {})
                     ;; The executor would also re-freeze differently once a
                     ;; leg is terminal; the update's freeze is kept.
                     (sim/fill! s (sell-86 grid) "10")
                     (swap! store assoc-in [:vol-range :replace :phase] :canceling)
                     (with-runtime s {} #(execute! store :replace-cancel {:grid-id (:grid-id grid)})))))
          (.then (fn [_]
                   (let [{:keys [store frozen]} @ctx
                         u (get-in @store [:vol-range :replace])]
                     (is (= frozen (:frozen-cloids u)) "the first freeze is kept")
                     (is (= :canceled (:phase u)) (pr-str (:basis u)))
                     (is (= {:buy "40" :sell "20"} (get-in u [:basis :remaining])))
                     (is (= {:buy "0" :sell "10"} (get-in u [:basis :filled-during-update])))
                     (is (= 1 (count (sim/order-sends s)))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-timed-out-check-asks-to-check-again-test
  (async done
    (let [s (sim/new-sim)
          ctx (atom {})]
      (-> (place-live! s)
          (.then (fn [grid]
                   (let [store (store-for grid)]
                     (swap! ctx assoc :store store :grid grid)
                     (swap! store assoc-in [:vol-range :replace]
                            (assoc (update-for grid :canceled) :frozen-cloids (lifecycle/replace-membership grid)
                                   :filled-at-freeze {}))
                     (with-runtime s {:deps (assoc (sim/deps s) :post-info! (fn [_ _] (js/Promise. (fn [_ _] nil))))
                                      :immediate-op-timeout? true}
                       #(execute! store :replace-confirm {:grid-id (:grid-id grid)})))))
          (.then (fn [_]
                   (let [{:keys [store grid]} @ctx
                         u (get-in @store [:vol-range :replace])]
                     (is (= :blocked (:phase u)))
                     (is (= [:replace/check-failed] (get-in u [:basis :blocking])))
                     (is (false? (get-in u [:basis :ready?])))
                     (is (not (get-in @store [:vol-range :execution :busy [:replace-confirm (:grid-id grid)]])))
                     (is (= {:op :replace-confirm :status :error}
                            (select-keys (get-in @store [:vol-range :execution :last]) [:op :status]))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-stopped-update-is-never-written-test
  ;; Stop updating while the cancel runs: the cancel the user asked for
  ;; completes, the update stays gone and nothing is placed.
  (async done
    (let [s (sim/new-sim)
          ctx (atom {})]
      (-> (place-live! s)
          (.then (fn [grid]
                   (let [store (store-for grid)]
                     (swap! ctx assoc :store store)
                     (with-runtime s {:deps (on-first-cancel s #(swap! store assoc-in [:vol-range :replace] nil))}
                       #(execute! store :replace-cancel {:grid-id (:grid-id grid)})))))
          (.then (fn [_]
                   (let [{:keys [store]} @ctx]
                     (is (nil? (get-in @store [:vol-range :replace])))
                     (is (= 7 (count (mapcat :cancels (sim/cancel-sends s)))))
                     (is (= 1 (count (sim/order-sends s))) "nothing placed"))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest refresh-balances-keeps-a-ready-update-ready-test
  (async done
    (let [s (sim/new-sim)
          ctx (atom {})]
      (-> (place-live! s)
          (.then (fn [grid]
                   (let [store (store-for grid)]
                     (swap! ctx assoc :store store :grid grid)
                     (with-runtime s {} #(execute! store :replace-cancel {:grid-id (:grid-id grid)})))))
          (.then (fn [_]
                   (let [{:keys [store grid]} @ctx]
                     (is (= :canceled (get-in @store [:vol-range :replace :phase])))
                     (swap! store assoc-in [:vol-range :replace :phase] :ready)
                     (swap! s assoc :usdc "3500")
                     (with-runtime s {} #(execute! store :replace-confirm {:grid-id (:grid-id grid)})))))
          (.then (fn [_]
                   (let [{:keys [store]} @ctx
                         u (get-in @store [:vol-range :replace])]
                     (is (= :ready (:phase u)))
                     (is (= "3500" (get-in u [:refreshed :account :quote :total])) "the new read")
                     (is (= 1 (count (sim/order-sends s)))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))
