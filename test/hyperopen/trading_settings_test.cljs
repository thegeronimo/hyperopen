(ns hyperopen.trading-settings-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.trading-settings :as trading-settings]))

(deftest open-order-safety-mode-defaults-to-off-test
  (is (= :off (:open-order-safety-mode trading-settings/default-state)))
  (is (= :off (:open-order-safety-mode (trading-settings/normalize-state {}))))
  (is (= :off (:open-order-safety-mode
               (trading-settings/normalize-state
                {:open-order-safety-mode :unknown}))))
  (is (false? (:open-order-safety-chosen? (trading-settings/normalize-state {})))))

(defn- restore-from
  [stored]
  (with-redefs [platform/local-storage-get (fn [_] (js/JSON.stringify (clj->js stored)))]
    (trading-settings/restore-state)))

(deftest restore-turns-an-unchosen-stored-strict-off-test
  (testing "Strict saved as the old default becomes Off"
    (is (= :off (:open-order-safety-mode
                 (restore-from {:open-order-safety-mode "strict" :fill-alerts-enabled? false}))))
    (is (false? (:fill-alerts-enabled?
                 (restore-from {:open-order-safety-mode "strict" :fill-alerts-enabled? false})))
        "the other settings are kept"))
  (testing "a mode the trader picked is kept"
    (is (= :strict (:open-order-safety-mode
                    (restore-from {:open-order-safety-mode "strict"
                                   :open-order-safety-chosen? true}))))
    (is (= :extended (:open-order-safety-mode
                      (restore-from {:open-order-safety-mode "extended"}))))
    (is (= :off (:open-order-safety-mode
                 (restore-from {:open-order-safety-mode "off"}))))))

(deftest optimizer-auto-exit-excluded-defaults-on-test
  (is (true? (:optimizer-auto-exit-excluded? trading-settings/default-state)))
  ;; A stored pre-feature settings blob (key absent) restores as ON; only an
  ;; explicitly persisted false keeps the opt-out.
  (is (true? (:optimizer-auto-exit-excluded? (trading-settings/normalize-state {}))))
  (is (false? (:optimizer-auto-exit-excluded?
               (trading-settings/normalize-state
                {:optimizer-auto-exit-excluded? false}))))
  (is (true? (:optimizer-auto-exit-excluded?
              (trading-settings/normalize-state
               {:optimizer-auto-exit-excluded? true})))))

(deftest open-order-safety-mode-normalizes-supported-values-test
  (is (= :strict (:open-order-safety-mode
                  (trading-settings/normalize-state
                   {:open-order-safety-mode "strict"}))))
  (is (= :extended (:open-order-safety-mode
                    (trading-settings/normalize-state
                     {:open-order-safety-mode "extended"}))))
  (is (= :off (:open-order-safety-mode
               (trading-settings/normalize-state
                {:open-order-safety-mode :off})))))
