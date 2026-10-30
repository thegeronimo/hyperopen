(ns hyperopen.vol-range.infrastructure.ladder-settings-scenario-test
  "A ladder placed through the executor records its settings in the journal
  (HO-SPEC-014 sections 6.6, 7.4, 10.2 step 1). Simulator only."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.infrastructure.executor-place :as place]
            [hyperopen.vol-range.simulator :as sim]))

(defn- fail [done] (fn [e] (is false (str e (when (instance? js/Error e) (.-stack e)))) (done)))

(defn- fresh []
  (locks/reset-in-tab-state!)
  (sim/new-sim))

(defn- journal-grid [s grid-id]
  (first (filter #(= grid-id (:grid-id %)) (jr/grids (sim/records s) nil))))

(deftest settings-round-trip-through-the-journal-test
  (async done
    (let [s (fresh)
          plan (sim/frozen-plan s)
          settings (ladder-settings/settings-from-plan plan fixtures/draft-13-2)]
      (-> (place/place! (sim/deps s) {:frozen-plan plan :frozen-scope sim/scope :settings settings})
          (.then
           (fn [{:keys [status grid]}]
             (is (= :live status))
             (let [stored (journal-grid s (:grid-id grid))]
               (is (= "190" (get-in stored [:settings :limits :maximum])))
               (is (= "120" (get-in stored [:settings :limits :core])))
               (is (= settings (:settings stored)) "read back exactly as recorded")
               (is (= settings (:settings grid)) "the returned grid carries them too")
               (is (= "40" (get-in stored [:settings :draft :buy :quantity]))))
             (testing "settings never change what is sent"
               (let [[sent] (sim/order-sends s)]
                 (is (= 7 (count (:orders sent))))
                 (is (not-any? #(contains? % :settings) (:orders sent)))))
             (done)))
          (.catch (fail done))))))

(deftest a-placement-without-settings-still-works-test
  (async done
    (let [s (fresh)]
      (-> (place/place! (sim/deps s) {:frozen-plan (sim/frozen-plan s) :frozen-scope sim/scope})
          (.then
           (fn [{:keys [status grid]}]
             (is (= :live status))
             (is (= 7 (count (:orders (first (sim/order-sends s))))))
             (is (not (contains? (journal-grid s (:grid-id grid)) :settings)))
             (done)))
          (.catch (fail done))))))
