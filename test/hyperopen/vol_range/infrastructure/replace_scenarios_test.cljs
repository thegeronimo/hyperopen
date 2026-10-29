(ns hyperopen.vol-range.infrastructure.replace-scenarios-test
  "Replacement core through the executor (HO-SPEC-014 sections 10.2, 13.4;
  VR-15, VR-16): frozen membership, confirmation with fills during the
  cancel, refreshed inventory and predecessor admission. Simulator only:
  nothing here reaches a real exchange."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.replace-flow :as replace-flow]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-cancel :as cancel]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.infrastructure.executor-replace :as replace]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

(defn- leg-at [grid side rung]
  (first (filter #(and (= side (:side %)) (= rung (:rung-index %))) (vals (:legs grid)))))

(defn- replacement-draft
  "The 13.2 draft with the replacement quantities (and inventory tilt)."
  [{:keys [buy sell tilt?] :or {buy "40" sell "27"}}]
  (-> fixtures/draft-13-2
      (assoc-in [:buy :quantity] buy)
      (assoc-in [:sell :quantity] sell)
      (assoc-in [:inventory :tilt-enabled?] (boolean tilt?))
      ;; Tilt moves the buys up; leave room under the cap.
      (cond-> tilt? (assoc-in [:buy :max-usdc-debit] "2990"))))

(defn- plan-from-pre-fill-account
  "A replacement plan reviewed from the OLD, pre-fill inventory (150 base,
  3000 USDC, none of the ladder's orders counted)."
  [s]
  (planner/prepare-plan (assoc (sim/live-input s)
                               :plan-id "stale-plan"
                               :account (fixtures/account-snapshot {:observed-at-ms (sim/now s)})
                               :remediations? false)))

(defn- predecessor [grid-id {:keys [frozen-cloids]} basis]
  {:grid-id grid-id :frozen-cloids frozen-cloids :remaining (:remaining basis)
   :filled-during-update (:filled-during-update basis) :included-rejected []})

(defn- place-replacement! [s plan pred]
  (place/place! (sim/deps s) {:frozen-plan plan :frozen-scope sim/scope :predecessor pred}))

(defn- canceled-update!
  "Place the 13.2 ladder, freeze its membership from the journal-refreshed
  grid, let `fill-during` (default 3) of the 10-unit sell at 86 fill while
  the cancel is pending, cancel, then confirm. `before-freeze` runs on the
  placed grid first (resolves to a grid). Resolves to
  {:old grid :frozen {...} :confirm result :sell-cloid c}."
  [s & [{:keys [fill-during before-freeze] :or {fill-during "3"}}]]
  (let [ctx (atom {})]
    (-> (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope})
        (.then (fn [{:keys [grid]}] (if before-freeze (before-freeze grid) grid)))
        (.then (fn [grid] (ex/refresh-grid! (sim/deps s) grid)))
        (.then (fn [grid]
                 (let [frozen (replace-flow/freeze grid)
                       sell (:cloid (leg-at grid :sell 0))]
                   (swap! ctx assoc :frozen frozen :sell-cloid sell)
                   (sim/advance! s 10)
                   (sim/fill! s sell fill-during)
                   (cancel/cancel! (sim/deps s) {:grid grid}))))
        (.then (fn [{:keys [grid]}]
                 (replace/confirm! (sim/deps s) (assoc (:frozen @ctx) :grid grid))))
        (.then (fn [r] (assoc @ctx :old (:grid r) :confirm r))))))

(deftest replacement-from-the-unfilled-remainder-test
  ;; Spec 13.4: 3 of a 10-unit sell fill while the cancel is pending.
  (async done
    (let [s (fresh)
          ctx (atom nil)]
      (-> (canceled-update! s)
          (.then
           (fn [{:keys [old frozen confirm sell-cloid] :as c}]
             (reset! ctx c)
             (is (= 7 (count (:frozen-cloids frozen))) "every working leg is frozen")
             (is (every? #(= "0" %) (vals (:filled-at-freeze frozen))))
             (is (= :ready (:status confirm)) (pr-str (:basis confirm)))
             (is (= :closed (lifecycle/status old)))
             (let [{:keys [basis refreshed]} confirm
                   q (lifecycle/leg-quantities old (get-in old [:legs sell-cloid]))]
               (is (= {:buy "40" :sell "27"} (:remaining basis)) "never replenishes the filled quantity")
               (is (= {:buy "0" :sell "3"} (:filled-during-update basis)))
               (is (= ["3" "7"] [(:filled q) (:canceled q)]) "the fill is preserved; 7 units were canceled")
               (is (= "147" (get-in refreshed [:account :base :total])) "inventory after the fill, not 150")
               (is (= :ready (get-in refreshed [:account :status])))
               (is (number? (:requested-at-ms refreshed))))
             (is (= 1 (count (sim/order-sends s))) "confirming never sends an order")
             (swap! s assoc :draft (replacement-draft {}))
             (let [plan (sim/frozen-plan s)]
               (is (= :ready (:status plan)) (pr-str (:errors plan)))
               (place-replacement! s plan (predecessor (:grid-id old) frozen (:basis confirm))))))
          (.then
           (fn [{:keys [status grid] :as r}]
             (is (= :live status) (pr-str (select-keys r [:status :errors :error])))
             (is (= (:grid-id (:old @ctx)) (get-in grid [:predecessor :grid-id])))
             (is (= {:buy "40" :sell "27"} (get-in grid [:predecessor :remaining])))
             (is (= {:buy "0" :sell "3"} (get-in grid [:predecessor :filled-during-update])))
             (is (= (get-in (:frozen @ctx) [:frozen-cloids]) (get-in grid [:predecessor :frozen-cloids])))
             (is (= 2 (count (sim/order-sends s))))
             (let [sells (remove :b (:orders (second (sim/order-sends s))))]
               (is (= "27" (precision/decimal->string
                            (reduce precision/add (precision/parse-decimal "0")
                                    (map #(precision/parse-decimal (:s %)) sells))))))
             (testing "the journal copy carries the lineage; the old ladder is not rewritten"
               (let [records (sim/records s)]
                 (is (= (:grid-id (:old @ctx))
                        (get-in (ex/stored-grid records (:grid-id grid)) [:predecessor :grid-id])))
                 (is (nil? (:predecessor (ex/stored-grid records (:grid-id (:old @ctx))))))))
             ;; A second replacement of the same ladder (a stale tab, a double click).
             (place-replacement! s (plan-from-pre-fill-account s)
                                 (predecessor (:grid-id (:old @ctx)) (:frozen @ctx) (get-in @ctx [:confirm :basis])))))
          (.then
           (fn [r]
             (is (= :blocked (:status r)))
             (is (some #{:replace/already-replaced} (:errors r)) (pr-str (:errors r)))
             (is (= 2 (count (sim/order-sends s))) "nothing more is sent")
             (done)))
          (.catch (fail done))))))

(deftest a-replacement-reviewed-from-pre-fill-inventory-is-refused-test
  ;; Spec 13.4: no replacement order may use the old pre-fill inventory.
  (async done
    (let [s (fresh)]
      (-> (canceled-update! s)
          (.then
           (fn [{:keys [old frozen confirm]}]
             (swap! s assoc :draft (replacement-draft {:tilt? true}))
             (let [stale (plan-from-pre-fill-account s)]
               (is (= :ready (:status stale)) (pr-str (:errors stale)))
               (testing "with tilt on the legs depend on inventory"
                 (is (not= (mapv :price (:legs stale)) (mapv :price (:legs (sim/frozen-plan s))))))
               (place-replacement! s stale (predecessor (:grid-id old) frozen (:basis confirm))))))
          (.then
           (fn [r]
             (is (= :admission-failed (:status r)) (pr-str r))
             (is (some #{:guard/plan-changed} (:errors r)))
             (is (= 1 (count (sim/order-sends s))) "nothing is sent")
             (done)))
          (.catch (fail done))))))

(deftest an-unknown-cancel-outcome-blocks-the-replacement-test
  ;; VR-15.
  (async done
    (let [s (fresh)
          ctx (atom nil)]
      (-> (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope})
          (.then (fn [{:keys [grid]}] (ex/refresh-grid! (sim/deps s) grid)))
          (.then (fn [grid]
                   (reset! ctx {:frozen (replace-flow/freeze grid) :grid-id (:grid-id grid)})
                   (sim/script! s {:kind :timeout-lost})
                   (cancel/cancel! (sim/deps s) {:grid grid})))
          (.then (fn [{:keys [grid status]}]
                   (is (= :checking status))
                   (replace/confirm! (sim/deps s) (assoc (:frozen @ctx) :grid grid))))
          (.then (fn [{:keys [status basis refreshed]}]
                   (is (= :blocked status))
                   (is (some #{:replace/unresolved-outcomes} (:blocking basis)))
                   (is (nil? refreshed) "no replacement review without a settled cancel")
                   (swap! s assoc :draft (replacement-draft {}))
                   (place-replacement! s (plan-from-pre-fill-account s)
                                       (predecessor (:grid-id @ctx) (:frozen @ctx) basis))))
          (.then (fn [r]
                   (is (= :blocked (:status r)))
                   (is (some #{:replace/predecessor-not-settled} (:errors r)) (pr-str (:errors r)))
                   (is (some #{:grid/active-in-market} (:errors r)) "the old ladder is not closed either")
                   (is (= 1 (count (sim/order-sends s))) "nothing is sent")
                   (done)))
          (.catch (fail done))))))

(deftest a-predecessor-from-another-scope-is-refused-test
  ;; VR-18: lineage never crosses markets or accounts.
  (async done
    (let [s (fresh)]
      (-> (canceled-update! s)
          (.then
           (fn [{:keys [old frozen confirm]}]
             (let [elsewhere (assoc old :grid-id "grid-elsewhere" :scope-key "another-market")]
               (-> ((:commit! (sim/journal s)) [(jr/grid-record elsewhere (sim/now s))])
                   (.then (fn [_]
                            (swap! s assoc :draft (replacement-draft {}))
                            (place-replacement! s (sim/frozen-plan s)
                                                (predecessor "grid-elsewhere" frozen (:basis confirm)))))))))
          (.then (fn [r]
                   (is (= :blocked (:status r)))
                   (is (= [:replace/predecessor-scope] (:errors r)))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest a-missing-predecessor-is-refused-test
  (async done
    (let [s (fresh)]
      (-> (canceled-update! s)
          (.then (fn [{:keys [frozen confirm]}]
                   (swap! s assoc :draft (replacement-draft {}))
                   (place-replacement! s (sim/frozen-plan s) (predecessor "grid-gone" frozen (:basis confirm)))))
          (.then (fn [r]
                   (is (= [:replace/predecessor-missing] (:errors r)))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest leaving-after-the-cancel-places-nothing-test
  ;; Spec 10.2: the old ladder stays canceled and no new orders are placed.
  (async done
    (let [s (fresh)]
      (-> (canceled-update! s)
          (.then (fn [{:keys [old confirm]}]
                   (is (= :ready (:status confirm)))
                   (is (= :closed (lifecycle/status old)))
                   (is (= 1 (count (sim/order-sends s))) "only the original placement")
                   (is (= 7 (count (mapcat :cancels (sim/cancel-sends s)))) "each old order canceled once")
                   (done)))
          (.catch (fail done))))))

(deftest fills-before-the-freeze-and-legs-canceled-before-it-test
  ;; 2 of the 86 sell fill and the 76.4 buy is canceled from Open Orders
  ;; before the update starts; 3 more of the sell fill during the cancel.
  (async done
    (let [s (fresh)]
      (-> (canceled-update!
           s {:before-freeze
              (fn [grid]
                (sim/fill! s (:cloid (leg-at grid :sell 0)) "2")
                (swap! s assoc-in [:orders (:oid (sim/order-by-cloid s (:cloid (leg-at grid :buy 0)))) :status]
                       "canceled")
                (sim/advance! s 10)
                (.then (ex/reconcile! (sim/deps s) grid) (fn [r] (:grid r))))})
          (.then (fn [{:keys [frozen confirm sell-cloid]}]
                   (is (= 6 (count (:frozen-cloids frozen))) "the canceled buy is not frozen")
                   (is (= "2" (get-in frozen [:filled-at-freeze sell-cloid])))
                   (let [{:keys [basis]} confirm]
                     (is (:ready? basis) (pr-str (:blocking basis)))
                     (is (= {:buy "0" :sell "3"} (:filled-during-update basis)) "the earlier 2 are not during")
                     (is (= {:buy "33" :sell "25"} (:remaining basis))
                         "the canceled remainder of the frozen legs only")
                     (is (= "5" (get-in basis [:filled :sell])) "lifetime fills are kept"))
                   (done)))
          (.catch (fail done))))))

(deftest stale-monitoring-refuses-the-replacement-but-not-the-cancel-test
  ;; Live-fills gate: placement is refused until monitoring is live; the
  ;; cancel and its confirmation still run.
  (async done
    (let [s (fresh)]
      (-> (canceled-update! s {:before-freeze (fn [grid]
                                                (swap! s assoc :live {:monitoring-blocker :monitoring/stale})
                                                grid)})
          (.then (fn [{:keys [old frozen confirm]}]
                   (is (= :ready (:status confirm)) "cancel and confirm ran")
                   (is (= 7 (count (mapcat :cancels (sim/cancel-sends s)))))
                   (swap! s assoc :draft (replacement-draft {}))
                   (place-replacement! s (sim/frozen-plan s) (predecessor (:grid-id old) frozen (:basis confirm)))))
          (.then (fn [r]
                   (is (= {:status :refused :errors [:monitoring/stale]} r))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest confirm-without-a-complete-account-read-asks-to-check-again-test
  (async done
    (let [s (fresh)]
      (-> (canceled-update! s)
          (.then (fn [{:keys [old frozen]}]
                   (let [post-info (sim/post-info s)
                         deps (assoc (sim/deps s) :post-info!
                                     (fn [body opts]
                                       (if (= "spotClearinghouseState" (get body "type"))
                                         (js/Promise.reject (js/Error. "network down"))
                                         (post-info body opts))))]
                     (replace/confirm! deps (assoc frozen :grid old)))))
          (.then (fn [r]
                   (is (= :error (:status r)))
                   (is (= [:guard/account-unavailable] (:errors r)))
                   (is (nil? (:refreshed r)))
                   (done)))
          (.catch (fail done))))))
