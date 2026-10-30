(ns hyperopen.vol-range.infrastructure.repair-scenarios-test
  "Repair inventory bounds through the executor (HO-SPEC-014 sections 6.6,
  8.1 step 8, 8.6, 13.4): the ladder's recorded limits are checked at
  admission and again by the final pre-HTTP guard. Simulator only."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

(defn- place-partial!
  "The 13.4 partial deployment (76.4 buy rejected), reconciled. With
  `settings?` the ladder records the 13.2 settings (maximum 190, core 120)."
  [s settings?]
  (sim/script! s {:kind :normal :reject-prices ["76.4"]})
  (let [plan (sim/frozen-plan s)]
    (-> (place/place! (sim/deps s) (cond-> {:frozen-plan plan :frozen-scope sim/scope}
                                     settings? (assoc :settings (ladder-settings/settings-from-plan
                                                                 plan fixtures/draft-13-2))))
        (.then (fn [{:keys [grid]}] (ex/reconcile! (sim/deps s) grid)))
        (.then (fn [{:keys [grid]}] grid)))))

(defn- repair-buy! [s grid]
  (let [[c] (lifecycle/repair-candidates grid)]
    (place/repair! (sim/deps s) {:grid grid :legs [{:logical-id (:logical-id c) :price "76.3"
                                                     :size (:original-base c)}]})))

(deftest a-repair-inside-the-recorded-limits-is-sent-test
  ;; 150 + 33 surviving buys + 7 = 190: the survivors count once.
  (async done
    (let [s (fresh)]
      (-> (place-partial! s true)
          (.then (fn [grid]
                   (is (= "190" (get-in grid [:settings :limits :maximum])))
                   (repair-buy! s grid)))
          (.then (fn [{:keys [status counts]}]
                   (is (= :live status))
                   (is (= 7 (:live counts)))
                   (is (= 2 (count (sim/order-sends s))))
                   (is (= 1 (count (:orders (second (sim/order-sends s))))))
                   (done)))
          (.catch (fail done))))))

(deftest a-repair-that-would-breach-the-maximum-is-refused-at-admission-test
  ;; An external deposit since placement: 160 + 33 + 7 = 200 > 190.
  (async done
    (let [s (fresh)]
      (-> (place-partial! s true)
          (.then (fn [grid]
                   (swap! s assoc :base "160")
                   (repair-buy! s grid)))
          (.then (fn [r]
                   (is (= :admission-failed (:status r)))
                   (is (= [:repair/max-inventory-breach] (:errors r)))
                   (is (= 1 (count (sim/order-sends s))) "nothing is sent")
                   (done)))
          (.catch (fail done))))))

(deftest the-final-guard-refuses-a-breach-that-appears-after-admission-test
  (async done
    (let [s (fresh)]
      (-> (place-partial! s true)
          (.then (fn [grid]
                   (sim/script! s {:kind :normal :before-guard (fn [s] (swap! s assoc :base "160"))})
                   (repair-buy! s grid)))
          (.then (fn [{:keys [delivery phase error grid]}]
                   (is (= :not-sent delivery))
                   (is (= :final-guard phase))
                   (is (str/includes? (str error) "max-inventory-breach"))
                   (is (= 1 (count (sim/order-sends s))) "nothing is sent")
                   (testing "the member stays repairable"
                     (is (= ["buy-0"] (map :logical-id (lifecycle/repair-candidates grid)))))
                   (done)))
          (.catch (fail done))))))

(deftest a-ladder-without-recorded-limits-keeps-only-the-balance-checks-test
  (async done
    (let [s (fresh)]
      (-> (place-partial! s false)
          (.then (fn [grid]
                   (is (not (contains? grid :settings)))
                   (swap! s assoc :base "160")
                   (repair-buy! s grid)))
          (.then (fn [{:keys [status]}]
                   (is (= :live status))
                   (is (= 2 (count (sim/order-sends s))))
                   (done)))
          (.catch (fail done))))))
