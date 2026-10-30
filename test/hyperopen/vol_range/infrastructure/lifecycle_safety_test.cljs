(ns hyperopen.vol-range.infrastructure.lifecycle-safety-test
  "Simulator scenarios for the second review round and local real-money
  safety (HO-SPEC-014 sections 7.3, 8, 10, 15.2)."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-cancel :as cancel]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- place!
  ([s] (place! s (sim/frozen-plan s)))
  ([s plan] (place/place! (sim/deps s) {:frozen-plan plan :frozen-scope sim/scope})))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

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

;; ---------------------------------------------------------------------------
;; Local real-money safety
;; ---------------------------------------------------------------------------

(deftest the-local-ladder-cap-refuses-before-anything-happens-test
  (async done
    (let [s (fresh)]
      (swap! s assoc :live {:max-ladder-usdc "100"})
      (-> (place! s)
          (.then (fn [r]
                   (is (= :refused (:status r)))
                   (is (= [:guard/over-ladder-cap] (:errors r)))
                   (is (empty? (sim/records s)) "no intent, no journal write")
                   (is (empty? (:sends @s)))
                   (done)))
          (.catch (fail done))))))

(deftest a-quiet-market-still-places-with-a-fresh-book-read-test
  ;; The store's book is minutes old (it only changes when the book does); the
  ;; awaited book read supplies the fresh best bid and ask.
  (async done
    (let [s (fresh)
          plan (sim/frozen-plan s)]
      (swap! s assoc :bbo-at (- (sim/now s) 300000))
      (-> (place! s plan)
          (.then (fn [{:keys [status]}]
                   (is (= :live status))
                   (is (= 1 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))

(deftest the-raw-exchange-reply-is-kept-for-diagnostics-test
  (async done
    (let [s (fresh)]
      (-> (place! s)
          (.then (fn [_]
                   (let [attempt (first (filter #(= :send-attempt (:kind %)) (sim/records s)))]
                     (is (re-find #"resting" (:raw-response attempt)))
                     (is (= 200 (:http-status attempt))))
                   (done)))
          (.catch (fail done))))))
