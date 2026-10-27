(ns hyperopen.vol-range.infrastructure.lifecycle-scenarios-test
  "Simulator scenarios for the stage 5 exit condition (HO-SPEC-014 sections
  13.4, 15.1 PR 5): partial and unknown outcomes, reload recovery, and
  cancel/fill race behaviour. VR-06, VR-09, VR-11, VR-13 to VR-17."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-cancel :as cancel]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- place!
  "Place the reviewed plan. Pass the plan frozen before earlier orders rested
  to retry the SAME review."
  ([s] (place! s (sim/frozen-plan s)))
  ([s plan] (place/place! (sim/deps s) {:frozen-plan plan :frozen-scope sim/scope})))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- leg-at [grid side price]
  (first (filter #(and (= side (:side %)) (= price (:price %))) (vals (:legs grid)))))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

(deftest partial-deployment-repair-then-cancel-test
  ;; Spec 13.4 steps 1-7.
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :normal :reject-prices ["76.4"]})
      (-> (place! s)
          (.then
           (fn [{:keys [status counts grid]}]
             (is (= :partially-deployed status))
             (is (= 6 (:live counts)))
             (is (= 1 (:rejected counts)))
             (is (= "33" (:live-buy-units counts)))
             (is (= "30" (:live-sell-units counts)))
             (is (= 1 (count (sim/order-sends s))))
             (is (= 7 (count (:orders (first (sim/order-sends s))))))
             ;; Refresh first: the original reservation is released once a
             ;; balance read covers the venue holds.
             (ex/reconcile! (sim/deps s) grid)))
          (.then
           (fn [{:keys [grid]}]
             (let [[candidate] (lifecycle/repair-candidates grid)]
               (is (= "76.4" (:price candidate)))
               (place/repair! (sim/deps s) {:grid grid
                                            :legs [{:logical-id (:logical-id candidate) :price "76.3"
                                                    :size (:original-base candidate)}]}))))
          (.then
           (fn [{:keys [status counts grid]}]
             (is (= :live status) "a repaired grid is no longer partially deployed")
             (is (= 7 (:live counts)))
             (is (= 7 (:members counts)))
             (is (= 1 (:historical-rejections counts)))
             (let [repair (second (sim/order-sends s))]
               (is (= 1 (count (:orders repair))) "the six survivors are never resent")
               (is (= "76.3" (:p (first (:orders repair)))))
               (is (not= (:c (first (:orders repair))) (:cloid (leg-at grid :buy "76.4")))
                   "a new CLOID for the repaired member"))
             (cancel/cancel! (sim/deps s) {:grid grid})))
          (.then
           (fn [{:keys [status counts]}]
             (let [targets (mapcat :cancels (sim/cancel-sends s))]
               (is (= 7 (count targets)))
               (is (= 7 (count (distinct (map :o targets)))) "each current member targeted exactly once"))
             (is (= :closed status))
             (is (= 7 (:canceled counts)))
             (done)))
          (.catch (fail done))))))

(deftest unknown-delivery-is-reconciled-by-original-ids-and-never-resent-test
  ;; VR-13: the venue accepted the batch but the response was lost.
  (async done
    (let [s (fresh)
          plan (sim/frozen-plan s)]
      (sim/script! s {:kind :timeout-after-apply})
      (-> (place! s plan)
          (.then (fn [{:keys [status counts]}]
                   (is (= :checking status))
                   (is (= 7 (:checking counts)))
                   (place! s plan)))
          (.then (fn [r]
                   (is (= :blocked (:status r)) "no new grid while a request might still become live")
                   (is (= 1 (count (sim/order-sends s))))
                   (ex/reconcile! (sim/deps s) (first (jr/grids (sim/records s) nil)))))
          (.then (fn [{:keys [status counts]}]
                   (is (= :live status))
                   (is (= 7 (:live counts)))
                   (is (= 1 (count (sim/order-sends s))) "reconciliation never resends")
                   (done)))
          (.catch (fail done))))))

(deftest unknown-oid-is-not-proof-of-non-placement-test
  (async done
    (let [s (fresh)
          plan (sim/frozen-plan s)]
      (sim/script! s {:kind :timeout-lost})
      (-> (place! s plan)
          (.then (fn [_] (ex/reconcile! (sim/deps s) (first (jr/grids (sim/records s) nil)))))
          (.then (fn [{:keys [status reads]}]
                   (is (= :checking status) "absent orders and unknownOid keep the grid Checking")
                   (is (= 7 (:status-lookups reads)) "every leg is queried by its CLOID")
                   (place! s plan)))
          (.then (fn [r]
                   (is (= :blocked (:status r)))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest reload-after-crash-mid-send-recovers-original-ids-test
  ;; The tab died after committing the attempt; the venue accepted the batch.
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :crash})
      (place! s) ; never settles
      (-> (js/Promise. (fn [res _] (js/setTimeout res 20)))
          (.then (fn [_]
                   (is (= 7 (count (filter #(= "open" (:status %)) (vals (:orders @s))))))
                   (ex/recover! (sim/deps s) {:scope sim/scope :scope-key (:scope-key (first (jr/grids (sim/records s) nil)))})))
          (.then (fn [{:keys [grids blockers]}]
                   (is (= :submitting (lifecycle/status (first grids)))
                       "another tab may still be sending: left in flight before its deadline")
                   (is (seq blockers))
                   (sim/advance! s (+ ex/action-deadline-ms 41000))
                   (ex/recover! (sim/deps s) {:scope sim/scope :scope-key (:scope-key (first grids))})))
          (.then (fn [{:keys [grids]}]
                   (is (= :checking (lifecycle/status (first grids))) "a possible send becomes unknown")
                   (ex/reconcile! (sim/deps s) (first grids))))
          (.then (fn [{:keys [status counts]}]
                   (is (= :live status))
                   (is (= 7 (:live counts)))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest crash-before-attempt-record-is-provably-not-sent-test
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :crash-before-attempt})
      (place! s)
      (-> (js/Promise. (fn [res _] (js/setTimeout res 20)))
          (.then (fn [_]
                   (sim/advance! s (+ ex/action-deadline-ms 6000))
                   (ex/recover! (sim/deps s) {:scope sim/scope
                                              :scope-key (:scope-key (first (jr/grids (sim/records s) nil)))})))
          (.then (fn [{:keys [grids blockers]}]
                   (is (= :not-placed (lifecycle/status (first grids))))
                   (is (empty? blockers))
                   (is (empty? (sim/order-sends s)))
                   (ex/reconcile! (sim/deps s) (first grids))))
          (.then (fn [_]
                   (is (= "0" (:usdc (jr/pending-reservations (sim/records s) "0xabc")))
                       "the reservation of an unsent operation is released")
                   (done)))
          (.catch (fail done))))))

(deftest cancel-fill-race-and-replacement-basis-test
  ;; Spec 13.4: 3 of a 10-unit sell fill while cancellation is pending.
  (async done
    (let [s (fresh)
          sell-cloid (atom nil)]
      (-> (place! s)
          (.then (fn [{:keys [grid]}]
                   (let [leg (first (sort-by :rung-index (filter #(= :sell (:side %)) (vals (:legs grid)))))]
                     (is (= "10" (:original-base leg)))
                     (reset! sell-cloid (:cloid leg))
                     (sim/advance! s 10)
                     (sim/fill! s (:cloid leg) "3")
                     (cancel/cancel! (sim/deps s) {:grid grid}))))
          (.then (fn [{:keys [status grid]}]
                   (is (= :closed status))
                   (let [leg (get-in grid [:legs @sell-cloid])
                         q (lifecycle/leg-quantities grid leg)]
                     (is (= :canceled (:terminal-reason leg)))
                     (is (= "3" (:filled q)) "the fill is preserved")
                     (is (= "7" (:canceled q)) "the remaining 7 units were canceled"))
                   (let [basis (lifecycle/replacement-basis grid)]
                     (is (:ready? basis))
                     (is (= "27" (get-in basis [:remaining :sell])) "never replenishes filled quantity")
                     (is (= "40" (get-in basis [:remaining :buy])))
                     (is (= "3" (get-in basis [:filled :sell]))))
                   (done)))
          (.catch (fail done))))))

(deftest missing-order-cancel-reply-is-resolved-by-status-test
  ;; The order fully filled before the cancel arrived.
  (async done
    (let [s (fresh)]
      (-> (place! s)
          (.then (fn [{:keys [grid]}]
                   (let [leg (first (filter #(= :buy (:side %)) (vals (:legs grid))))]
                     (sim/fill! s (:cloid leg) (:original-base leg))
                     (-> (cancel/cancel! (sim/deps s) {:grid grid})
                         (.then (fn [{:keys [grid status]}]
                                  (is (= :filled (get-in grid [:legs (:cloid leg) :terminal-reason])))
                                  (is (= :closed status))
                                  (done)))))))
          (.catch (fail done))))))

(deftest unknown-cancel-blocks-replacement-test
  ;; VR-15: a partial or unknown cancel outcome blocks unsafe replacement.
  (async done
    (let [s (fresh)]
      (-> (place! s)
          (.then (fn [{:keys [grid]}]
                   (sim/script! s {:kind :timeout-lost})
                   (cancel/cancel! (sim/deps s) {:grid grid})))
          (.then (fn [{:keys [grid status]}]
                   (is (= :checking status) "the lost cancel could still take effect before its deadline")
                   (is (not (:ready? (lifecycle/replacement-basis grid))))
                   (sim/advance! s (+ ex/action-deadline-ms 41000))
                   (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [{:keys [grid status]}]
                   (is (= :live status) "after its deadline the cancel failed; the orders are live")
                   (is (every? #(= :failed (:cancel-state %)) (vals (:legs grid))))
                   (is (= 7 (count (lifecycle/cancel-targets grid))) "they can be canceled again")
                   (is (not (:ready? (lifecycle/replacement-basis grid))))
                   (done)))
          (.catch (fail done))))))

(deftest journal-failure-blocks-placement-but-not-cancel-test
  ;; VR-11.
  (async done
    (let [s (fresh)
          plan (sim/frozen-plan s)]
      (-> (place! s plan)
          (.then (fn [{:keys [grid]}]
                   (swap! s assoc :journal-fail? true)
                   (-> (place! s plan)
                       (.then (fn [r]
                                (is (= :journal-unavailable (:status r)))
                                (is (= 1 (count (sim/order-sends s))) "no placement without a committed intent")
                                (cancel/cancel! (sim/deps s) {:grid grid}))))))
          (.then (fn [{:keys [grid]}]
                   (is (= 7 (count (mapcat :cancels (sim/cancel-sends s)))) "cancel still sends")
                   (is (= 7 (count (filter #(= :canceled (:terminal-reason %)) (vals (:legs grid))))))
                   (done)))
          (.catch (fail done))))))

(deftest final-guard-refuses-stale-or-materially-changed-placement-test
  ;; VR-09: a fill-like balance change, then a stale book, between admission and HTTP.
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :normal :before-guard (fn [s] (swap! s assoc :usdc "100"))})
      (-> (place! s)
          (.then (fn [{:keys [status delivery phase grid]}]
                   (is (= :not-sent delivery))
                   (is (= :final-guard phase))
                   (is (= :not-placed status))
                   (is (empty? (sim/order-sends s)))
                   (is (empty? (lifecycle/releasable-operations grid)) "already released")
                   (is (= "0" (:usdc (jr/pending-reservations (sim/records s) "0xabc"))))
                   (swap! s assoc :usdc "3000" :bbo-at (sim/now s))
                   (sim/script! s {:kind :normal :before-guard (fn [s] (sim/advance! s 3000))})
                   (place! s)))
          (.then (fn [{:keys [delivery phase]}]
                   (is (= :not-sent delivery) "a book older than 2 s refuses the send")
                   (is (= :final-guard phase))
                   (is (empty? (sim/order-sends s)))
                   (done)))
          (.catch (fail done))))))

(deftest discovered-prefixed-orders-become-a-recovered-grid-test
  ;; VR-17: live Vol Range orders with no local manifest.
  (async done
    (let [s (fresh)
          plan (sim/frozen-plan s)]
      (sim/rest-order! s {:side :sell :price "90" :size "5" :cloid "0x484f5652aaaaaaaaaaaaaaaaaaaaaaaa"})
      (sim/rest-order! s {:side :sell :price "91" :size "5" :cloid "0x11112222aaaaaaaaaaaaaaaaaaaaaaaa"})
      (-> (place! s plan)
          (.then (fn [r]
                   (is (= :blocked (:status r)))
                   (is (= [:grid/recovered-orders] (:errors r)))
                   (ex/recover! (sim/deps s) {:scope sim/scope :scope-key "k"})))
          (.then (fn [{:keys [grids blockers]}]
                   (let [g (first grids)]
                     (is (:recovered? g))
                     (is (= 1 (count (:legs g))) "only the prefixed order, never other CLOIDs")
                     (is (= [:grid/recovered-orders] blockers))
                     (cancel/cancel! (sim/deps s) {:grid g}))))
          (.then (fn [{:keys [status]}]
                   (is (= :closed status))
                   (is (= 1 (count (mapcat :cancels (sim/cancel-sends s)))))
                   (done)))
          (.catch (fail done))))))

(deftest reservation-waits-for-a-balance-read-after-the-evidence-test
  ;; VR-06: order visibility alone never releases the pending reservation.
  ;; The response was lost, so the first evidence of the orders is the open
  ;; orders read whose paired balance read was requested no later.
  (async done
    (let [s (fresh)
          pending #(jr/pending-reservations (sim/records s) "0xabc")]
      (sim/script! s {:kind :timeout-after-apply})
      (-> (place! s)
          (.then (fn [{:keys [grid]}]
                   (is (not= "0" (:usdc (pending))) "pending right after placement")
                   (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [{:keys [grid]}]
                   (is (not= "0" (:usdc (pending)))
                       "the balance read was requested no later than the evidence it would cover")
                   (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [_]
                   (is (= "0" (:usdc (pending))))
                   (done)))
          (.catch (fail done))))))

;; ---------------------------------------------------------------------------
;; Review regressions
;; ---------------------------------------------------------------------------

(deftest a-stale-copy-never-erases-a-repair-test
  ;; A flow holding an older grid copy persists after a repair went live.
  (async done
    (let [s (fresh)
          stale (atom nil)]
      (sim/script! s {:kind :normal :reject-prices ["76.4"]})
      (-> (place! s)
          (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [{:keys [grid]}]
                   (reset! stale grid)
                   (let [[c] (lifecycle/repair-candidates grid)]
                     (place/repair! (sim/deps s) {:grid grid :legs [{:logical-id (:logical-id c) :price "76.3"
                                                                     :size (:original-base c)}]}))))
          (.then (fn [_] (ex/reconcile! (sim/deps s) @stale)))
          (.then (fn [{:keys [grid status counts]}]
                   (is (= :live status))
                   (is (= 7 (:live counts)) "the repair leg survived the stale write")
                   (is (empty? (lifecycle/repair-candidates grid)) "nothing to repair twice")
                   (is (= 7 (count (lifecycle/cancel-targets grid))))
                   (done)))
          (.catch (fail done))))))

(deftest cancel-during-submit-refuses-the-send-test
  (async done
    (let [s (fresh)
          cancel-result (atom nil)]
      (sim/script! s {:kind :normal
                      :before-guard (fn [s]
                                      (-> (cancel/cancel! (sim/deps s) {:grid (first (jr/grids (sim/records s) nil))})
                                          (.then #(reset! cancel-result %))))})
      (-> (place! s)
          (.then (fn [{:keys [status delivery phase]}]
                   (is (= :busy (:status @cancel-result)) "cancel reports the in-flight placement")
                   (is (= :not-sent delivery) "the requested cancel refuses the send at the final guard")
                   (is (= :final-guard phase))
                   (is (= :not-placed status))
                   (is (empty? (sim/order-sends s)))
                   (done)))
          (.catch (fail done))))))

(deftest repair-is-refused-after-cancel-and-bounded-by-the-rejected-quantity-test
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :normal :reject-prices ["76.4"]})
      (-> (place! s)
          (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [{:keys [grid]}]
                   (let [[c] (lifecycle/repair-candidates grid)]
                     (-> (place/repair! (sim/deps s) {:grid grid :legs [{:logical-id (:logical-id c) :price "76.3"
                                                                         :size "8"}]})
                         (.then (fn [r]
                                  (is (= :admission-failed (:status r)))
                                  (is (some #{:repair/exceeds-rejected-quantity} (:errors r)))
                                  (cancel/cancel! (sim/deps s) {:grid grid})))
                         (.then (fn [{:keys [grid]}]
                                  (place/repair! (sim/deps s) {:grid grid :legs [{:logical-id (:logical-id c)
                                                                                  :price "76.3" :size "7"}]})))))))
          (.then (fn [r]
                   (is (= :blocked (:status r)))
                   (is (= [:grid/cancel-requested] (:errors r)))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest a-failed-release-write-is-retried-test
  (async done
    (let [s (fresh)
          pending #(:usdc (jr/pending-reservations (sim/records s) "0xabc"))]
      (-> (place! s)
          (.then (fn [{:keys [grid]}]
                   (swap! s assoc :journal-fail? true)
                   (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [{:keys [grid persisted?]}]
                   (is (false? persisted?))
                   (is (empty? (:released-operation-ids grid)) "not marked released when the write failed")
                   (swap! s assoc :journal-fail? false)
                   (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [_]
                   (is (= "0" (pending)) "the release record is written on the next persist")
                   (done)))
          (.catch (fail done))))))

(deftest cancel-for-another-account-is-refused-test
  ;; VR-18
  (async done
    (let [s (fresh)]
      (-> (place! s)
          (.then (fn [{:keys [grid]}]
                   (swap! s assoc :live {:input (assoc (sim/live-input s) :scope (assoc sim/scope :account-address "0xdef"))})
                   (cancel/cancel! (sim/deps s) {:grid grid})))
          (.then (fn [r]
                   (is (= :refused (:status r)))
                   (is (= [:guard/scope-changed] (:errors r)))
                   (is (empty? (sim/cancel-sends s)))
                   (done)))
          (.catch (fail done))))))

;; ---------------------------------------------------------------------------
;; Second review regressions
;; ---------------------------------------------------------------------------

(deftest one-repair-never-places-a-member-twice-test
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :normal :reject-prices ["76.4"]})
      (-> (place! s)
          (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
          (.then (fn [{:keys [grid]}]
                   (let [[c] (lifecycle/repair-candidates grid)
                         leg {:logical-id (:logical-id c) :size "3"}]
                     (place/repair! (sim/deps s) {:grid grid :legs [(assoc leg :price "76.3")
                                                                    (assoc leg :price "76.2")]}))))
          (.then (fn [r]
                   (is (= :admission-failed (:status r)))
                   (is (some #{:repair/duplicate-member} (:errors r)))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest a-late-attempt-commit-never-blocks-the-market-test
  ;; The delivery timed out before HTTP; the attempt record lands afterwards as
  ;; :sending. The delivery's not-sent outcome is proof, so placement resumes.
  (async done
    (let [s (fresh)
          plan (sim/frozen-plan s)]
      (sim/script! s {:kind :late-attempt})
      (-> (place! s plan)
          (.then (fn [{:keys [status]}]
                   (is (= :not-placed status))
                   (js/Promise. (fn [res _] (js/setTimeout res 30)))))
          (.then (fn [_]
                   (is (some #(and (= :send-attempt (:kind %)) (= :sending (:delivery %))) (sim/records s))
                       "the late commit really landed")
                   (place! s plan)))
          (.then (fn [{:keys [status]}]
                   (is (= :live status))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest a-failed-journal-read-never-overwrites-the-grid-test
  (async done
    (let [s (fresh)]
      (-> (place! s)
          (.then (fn [{:keys [grid]}]
                   (let [before (:journal @s)]
                     (swap! s assoc :journal-load-fail? true)
                     (-> (ex/persist-grid! (sim/deps s) (assoc grid :legs {}) [])
                         (.then (fn [{:keys [ok?]}]
                                  (is (false? ok?))
                                  (is (= before (:journal @s)) "nothing was written blind")
                                  (done)))))))
          (.catch (fail done))))))

(deftest cancel-settles-a-crashed-send-without-a-reload-test
  ;; The sending tab died mid-HTTP; the venue accepted the batch.
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :crash})
      (place! s)
      (-> (js/Promise. (fn [res _] (js/setTimeout res 20)))
          (.then (fn [_]
                   (sim/advance! s (+ ex/action-deadline-ms 41000))
                   (cancel/cancel! (sim/deps s) {:grid (first (jr/grids (sim/records s) nil))})))
          (.then (fn [{:keys [status]}]
                   (is (= :closed status) "the unknown orders were canceled by CLOID and confirmed")
                   (is (= 7 (count (mapcat :cancels (sim/cancel-sends s)))))
                   (is (empty? (filter #(= "open" (:status %)) (vals (:orders @s)))))
                   (done)))
          (.catch (fail done))))))
