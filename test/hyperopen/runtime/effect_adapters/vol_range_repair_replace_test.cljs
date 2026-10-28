(ns hyperopen.runtime.effect-adapters.vol-range-repair-replace-test
  "The repair operations end to end through `[:effects/vol-range-execute op
  params]` (HO-SPEC-014 sections 8.1, 8.6, 13.4; VR-16 repair half): the
  preview freezes a proposal from awaited reads, the send carries exactly
  the frozen selection with new CLOIDs, survivors are never resent, a stale
  or edited proposal sends nothing, and a signed repair is never reported as
  timed out while its delivery is pending. Simulator only."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.api.default.state :as api-state]
            [hyperopen.platform :as platform]
            [hyperopen.platform.locks :as locks]
            [hyperopen.runtime.effect-adapters.vol-range-execution :as vre]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.repair-plan :as repair-plan]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.infrastructure.execution-gate :as gate]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- wait [ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- place-partial!
  "The 13.4 partial deployment (76.4 buy rejected) with the 13.2 settings,
  reconciled; then the touch moves to 76.00/76.35."
  [s]
  (locks/reset-in-tab-state!)
  (sim/script! s {:kind :normal :reject-prices ["76.4"]})
  (let [plan (sim/frozen-plan s)]
    (-> (place/place! (sim/deps s) {:frozen-plan plan :frozen-scope sim/scope
                                    :settings (ladder-settings/settings-from-plan plan fixtures/draft-13-2)})
        (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
        (.then (fn [{:keys [grid]}]
                 (swap! s assoc :book {:bid "76.00" :ask "76.35"})
                 grid)))))

(defn- store-for [grid]
  (atom {:wallet {:address "0xabc"}
         :vol-range {:execution {:enabled? true :grids {(:grid-id grid) grid}}
                     :repair {:grid-id (:grid-id grid) :status :loading}}}))

(defn- with-runtime
  "Run `(f)` (a promise) with the adapter wired to the simulator: executor
  deps, the gate and the fee read. Restores everything afterwards."
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

(defn- preview! [store grid] (execute! store :repair-preview {:grid-id (:grid-id grid)}))

(defn- repair-params [store]
  (let [r (get-in @store [:vol-range :repair])]
    {:grid-id (:grid-id r) :proposal-id (:proposal-id r) :legs (repair-plan/selected-legs r)}))

(defn- cloids [action] (set (map :c (:orders action))))

(deftest preview-freezes-a-proposal-from-fresh-reads-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-partial! s)
          (.then (fn [grid]
                   (let [store (store-for grid)]
                     (with-runtime s {}
                       #(-> (preview! store grid)
                            (.then (fn [_]
                                     (let [r (get-in @store [:vol-range :repair])]
                                       (is (= :ready (:status r)))
                                       (is (string? (:proposal-id r)))
                                       (is (= (:grid-id grid) (:grid-id r)))
                                       (is (= {:bid "76.00" :ask "76.35"} (select-keys (:bbo r) [:bid :ask])))
                                       (is (= [{:logical-id "buy-0" :price "76.16" :size "7"}]
                                              (repair-plan/selected-legs r))
                                           "76.4 would cross 76.35: one model step further from the book")
                                       (is (= {:core "120" :maximum "190"} (:limits r)))
                                       (is (= :ready (:status (repair-plan/proposal-status r))))
                                       (is (not (get-in @store [:vol-range :execution :busy [:repair-preview (:grid-id grid)]])))
                                       (is (= {:op :repair-preview :status :ok}
                                              (select-keys (get-in @store [:vol-range :execution :last]) [:op :status])))
                                       (is (= 1 (count (sim/order-sends s))) "a preview sends nothing")))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest preview-results-only-land-on-the-same-open-review-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-partial! s)
          (.then (fn [grid]
                   (let [store (store-for grid)]
                     (with-runtime s {}
                       #(let [p (preview! store grid)]
                          ;; The user closes the review while the reads run.
                          (swap! store assoc-in [:vol-range :repair] nil)
                          (.then p (fn [_] (is (nil? (get-in @store [:vol-range :repair]))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest preview-refusals-show-on-the-review-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-partial! s)
          (.then (fn [grid]
                   (let [store (store-for grid)
                         other (assoc (sim/deps s) :live! #(assoc-in (sim/live s) [:input :scope :coin] "OTHER/USDC"))]
                     (-> (with-runtime s {:deps other} #(preview! store grid))
                         (.then (fn [_]
                                  (testing "the ticket is on another market"
                                    (is (= {:status :error :errors [:guard/scope-changed]}
                                           (select-keys (get-in @store [:vol-range :repair]) [:status :errors]))))
                                  (swap! store assoc-in [:vol-range :repair] {:grid-id (:grid-id grid) :status :loading})
                                  (with-runtime s {:enabled? false} #(preview! store grid))))
                         (.then (fn [_]
                                  (testing "placement is off in this browser"
                                    (is (= [:capability/execution-disabled]
                                           (get-in @store [:vol-range :repair :errors])))
                                    (is (= :refused (get-in @store [:vol-range :execution :last :status]))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-stale-or-edited-proposal-sends-nothing-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-partial! s)
          (.then (fn [grid]
                   (let [store (store-for grid)]
                     (with-runtime s {}
                       #(-> (preview! store grid)
                            (.then (fn [_] (execute! store :repair (assoc (repair-params store) :proposal-id "rp-old"))))
                            (.then (fn [_]
                                     (is (= {:op :repair :status :refused :errors [:repair/proposal-mismatch]}
                                            (select-keys (get-in @store [:vol-range :execution :last]) [:op :status :errors])))
                                     (is (= 1 (count (sim/order-sends s))) "nothing is sent")
                                     (is (= :ready (get-in @store [:vol-range :repair :status])) "the review stays open")
                                     (execute! store :repair (assoc (repair-params store)
                                                                    :legs [{:logical-id "buy-0" :price "76.3" :size "7"}]))))
                            (.then (fn [_]
                                     (is (= [:repair/proposal-mismatch] (get-in @store [:vol-range :execution :last :errors])))
                                     (is (= 1 (count (sim/order-sends s))) "edited legs are never sent")
                                     (execute! store :repair (assoc (repair-params store) :legs []))))
                            (.then (fn [_]
                                     (is (= [:repair/proposal-mismatch] (get-in @store [:vol-range :execution :last :errors])))
                                     (is (= 1 (count (sim/order-sends s))))
                                     (with-runtime s {:enabled? false} (fn [] (execute! store :repair (repair-params store))))))
                            (.then (fn [_]
                                     (is (= [:capability/execution-disabled]
                                            (get-in @store [:vol-range :execution :last :errors])))
                                     (is (= 1 (count (sim/order-sends s))) "the gate refuses before anything"))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-repair-sends-only-the-rejected-member-with-a-new-cloid-test
  (async done
    (let [s (sim/new-sim)]
      (-> (place-partial! s)
          (.then (fn [grid]
                   (let [store (store-for grid)
                         rejected (lifecycle/current-cloid (get-in grid [:members "buy-0"]))]
                     (with-runtime s {}
                       #(-> (preview! store grid)
                            (.then (fn [_] (execute! store :repair (repair-params store))))
                            (.then (fn [_]
                                     (let [[first-send second-send] (sim/order-sends s)
                                           [order] (:orders second-send)
                                           stored (get-in @store [:vol-range :execution :grids (:grid-id grid)])]
                                       (is (= 2 (count (sim/order-sends s))))
                                       (is (= 1 (count (:orders second-send))) "exactly the selected order")
                                       (is (= "76.16" (:p order)))
                                       (is (true? (:b order)))
                                       (is (not= rejected (:c order)) "a new CLOID")
                                       (is (not (contains? (cloids first-send) (:c order))))
                                       (is (= 7 (:live (lifecycle/counts stored))))
                                       (is (= :live (lifecycle/status stored)))
                                       (is (= [] (lifecycle/repair-candidates stored)))
                                       (is (= [rejected (:c order)] (get-in stored [:members "buy-0" :cloids])))
                                       (is (nil? (get-in @store [:vol-range :repair])) "the review closes once sent")
                                       (is (not (get-in @store [:vol-range :execution :busy [:repair (:grid-id grid)]])))
                                       (is (= {:op :repair :status :live :delivery :response}
                                              (select-keys (get-in @store [:vol-range :execution :last])
                                                           [:op :status :delivery])))
                                       (testing "the six survivors are never resent"
                                         (is (= 7 (count (:orders first-send))))
                                         (is (empty? (filter (cloids first-send) (map :c (:orders second-send)))))
                                         (is (= 8 (count (mapcat :orders (sim/order-sends s))))
                                             "7 in the placement, 1 repair"))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))

(deftest a-signed-repair-is-never-raced-by-the-operation-timeout-test
  ;; The 60 s bound fires at once here. The repair's delivery is held until
  ;; the test releases it: the busy key must stay set and no failure may be
  ;; reported meanwhile; the preview, by contrast, is still bounded.
  (async done
    (let [s (sim/new-sim)
          release (atom nil)
          held (js/Promise. (fn [resolve _] (reset! release resolve)))]
      (-> (place-partial! s)
          (.then
           (fn [grid]
             (let [store (store-for grid)
                   id (:grid-id grid)
                   base (sim/deps s)
                   slow (assoc base :send! (fn [action opts] (.then held (fn [_] ((:send! base) action opts)))))]
               (-> (with-runtime s {} #(preview! store grid))
                   (.then
                    (fn [_]
                      (with-runtime s {:deps slow :immediate-op-timeout? true}
                        #(let [p (execute! store :repair (repair-params store))]
                           (-> (wait 30)
                               (.then (fn [_]
                                        (is (true? (get-in @store [:vol-range :execution :busy [:repair id]])))
                                        (is (= :repair-preview (get-in @store [:vol-range :execution :last :op]))
                                            "nothing reported while the delivery is pending")
                                        (is (= 1 (count (sim/order-sends s))))
                                        (@release true)
                                        p))
                               (.then (fn [_]
                                        (is (not (get-in @store [:vol-range :execution :busy [:repair id]])))
                                        (is (= {:op :repair :status :live}
                                               (select-keys (get-in @store [:vol-range :execution :last]) [:op :status])))
                                        (is (= 2 (count (sim/order-sends s)))))))))))
                   (.then
                    (fn [_]
                      (swap! store assoc-in [:vol-range :repair] {:grid-id id :status :loading})
                      (with-runtime s {:deps (assoc base :post-info! (fn [_ _] (js/Promise. (fn [_ _] nil))))
                                       :immediate-op-timeout? true}
                        #(preview! store grid))))
                   (.then
                    (fn [_]
                      (testing "a stuck preview is bounded and shows the failure"
                        (is (not (get-in @store [:vol-range :execution :busy [:repair-preview id]])))
                        (is (= {:op :repair-preview :status :error}
                               (select-keys (get-in @store [:vol-range :execution :last]) [:op :status])))
                        (is (= :error (get-in @store [:vol-range :repair :status]))))))))))
          (.then (fn [_] (done)))
          (.catch (fail done))))))
