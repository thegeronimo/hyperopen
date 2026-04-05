(ns hyperopen.account.lifecycle-invariants-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.lifecycle-invariants :as lifecycle-invariants]
            [hyperopen.account.test-support.lifecycle :as lifecycle-fixtures]))

(deftest no-effective-account-surface-failures-accepts-cleared-disconnected-state-test
  (is (= []
         (lifecycle-invariants/no-effective-account-surface-failures
          (lifecycle-fixtures/state-for-kind :disconnected))))
  (is (true? (lifecycle-invariants/no-effective-account-surfaces-cleared?
              (lifecycle-fixtures/state-for-kind :disconnected)))))

(deftest no-effective-account-surface-failures-detects-stale-disconnected-branches-test
  (let [failures (lifecycle-invariants/no-effective-account-surface-failures
                  (lifecycle-fixtures/seed-stale-account-surfaces
                   (lifecycle-fixtures/state-for-kind :disconnected)))
        paths (set (map :path failures))]
    (is (contains? paths [:webdata2]))
    (is (contains? paths [:orders :open-orders]))
    (is (contains? paths [:orders :open-error]))
    (is (contains? paths [:orders :open-orders-snapshot]))
    (is (contains? paths [:orders :fills]))
    (is (contains? paths [:orders :twap-states]))
    (is (contains? paths [:account-info :funding-history :loading?]))
    (is (contains? paths [:account-info :order-history :loaded-for-address]))
    (is (contains? paths [:spot :clearinghouse-state]))
    (is (contains? paths [:spot :loading-balances?]))
    (is (contains? paths [:perp-dex-clearinghouse]))
    (is (contains? paths [:perp-dex-clearinghouse-error]))
    (is (contains? paths [:portfolio :summary-by-key]))
    (is (contains? paths [:portfolio :loading?]))
    (is (contains? paths [:account :mode]))
    (is (thrown-with-msg?
         js/Error
         #"account lifecycle invariant failed"
         (lifecycle-invariants/assert-account-lifecycle-invariants!
          (lifecycle-fixtures/seed-stale-account-surfaces
           (lifecycle-fixtures/state-for-kind :disconnected))
          {:phase :test})))))

(deftest no-effective-account-surface-failures-ignore-populated-states-while-an-account-is-active-test
  (doseq [kind [:owner :spectate :trader-route]]
    (is (= []
           (lifecycle-invariants/no-effective-account-surface-failures
            (lifecycle-fixtures/seed-stale-account-surfaces
             (lifecycle-fixtures/state-for-kind kind))))
        (name kind))))
