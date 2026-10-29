(ns hyperopen.vol-range.infrastructure.monitoring-gate-test
  "The live monitoring gate in the executor's single refusal seam
  (HO-SPEC-014 v2.1 sections 8.1 step 8, 9.3): placement and repair are
  refused while `live!` reports a `:monitoring/*` blocker, at the click and
  again at the final pre-HTTP guard (recorded as provably not sent, the
  reservation released); cancel never consults it. Simulator only."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-cancel :as cancel]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

(defn- place! [s] (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope}))

(defn- block! [s code] (swap! s assoc :live {:monitoring-blocker code}))

(defn- not-sent-attempt? [s]
  (some #(and (= :send-attempt (:kind %)) (= :not-sent (:delivery %))) (sim/records s)))

(defn- place-partial!
  "The 13.4 partial deployment (the 76.4 buy rejected), reconciled."
  [s]
  (sim/script! s {:kind :normal :reject-prices ["76.4"]})
  (-> (place! s)
      (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
      (.then (fn [{:keys [grid]}] grid))))

(defn- repair-buy! [s grid]
  (let [[c] (lifecycle/repair-candidates grid)]
    (place/repair! (sim/deps s) {:grid grid :legs [{:logical-id (:logical-id c) :price "76.3"
                                                     :size (:original-base c)}]})))

(deftest a-resync-in-progress-refuses-placement-before-anything-happens-test
  (async done
    (let [s (fresh)]
      (block! s :monitoring/resync-required)
      (-> (place! s)
          (.then (fn [r]
                   (is (= {:status :refused :errors [:monitoring/resync-required]} r))
                   (is (empty? (sim/records s)) "no intent, no journal write")
                   (is (empty? (:sends @s)))
                   (done)))
          (.catch (fail done))))))

(deftest a-blocker-after-admission-is-refused-at-the-final-guard-test
  ;; Admitted while live; the socket drops before HTTP.
  (async done
    (let [s (fresh)]
      (sim/script! s {:kind :normal :before-guard (fn [s] (block! s :monitoring/stale))})
      (-> (place! s)
          (.then (fn [{:keys [status delivery phase error grid]}]
                   (is (= :not-sent delivery))
                   (is (= :final-guard phase))
                   (is (str/includes? (str error) "monitoring/stale"))
                   (is (= :not-placed status))
                   (is (empty? (sim/order-sends s)) "no HTTP call")
                   (is (not-sent-attempt? s) "the attempt is recorded as provably not sent")
                   (is (empty? (lifecycle/releasable-operations grid)) "already released")
                   (is (= "0" (:usdc (jr/pending-reservations (sim/records s) "0xabc"))))
                   (done)))
          (.catch (fail done))))))

(deftest repair-is-refused-the-same-way-test
  (async done
    (let [s (fresh)]
      (-> (place-partial! s)
          (.then (fn [grid]
                   (block! s :monitoring/suspended)
                   (-> (repair-buy! s grid)
                       (.then (fn [r]
                                (is (= {:status :refused :errors [:monitoring/suspended]} r))
                                (is (= 1 (count (sim/order-sends s))) "only the placement was sent")
                                (swap! s assoc :live {})
                                (sim/script! s {:kind :normal
                                                :before-guard (fn [s] (block! s :monitoring/resync-required))})
                                (repair-buy! s grid))))))
          (.then (fn [{:keys [delivery phase error grid]}]
                   (testing "and at the final guard"
                     (is (= :not-sent delivery))
                     (is (= :final-guard phase))
                     (is (str/includes? (str error) "monitoring/resync-required"))
                     (is (= 1 (count (sim/order-sends s))))
                     (is (= ["buy-0"] (map :logical-id (lifecycle/repair-candidates grid)))
                         "the member stays repairable"))
                   (done)))
          (.catch (fail done))))))

(deftest cancel-still-sends-while-monitoring-is-not-live-test
  (async done
    (let [s (fresh)]
      (-> (place! s)
          (.then (fn [{:keys [grid]}]
                   (is (= :live (lifecycle/status grid)))
                   (block! s :monitoring/stale)
                   (cancel/cancel! (sim/deps s) {:grid grid})))
          (.then (fn [{:keys [status]}]
                   (is (= :closed status))
                   (is (= 7 (count (mapcat :cancels (sim/cancel-sends s)))))
                   (done)))
          (.catch (fail done))))))
