(ns hyperopen.runtime.account-lifecycle-validation-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.test-support.lifecycle :as lifecycle-fixtures]
            [hyperopen.runtime.validation :as validation]))

(deftest install-store-state-validation-rejects-disconnected-stale-account-surfaces-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [store (atom (lifecycle-fixtures/seed-stale-account-surfaces
                       (lifecycle-fixtures/state-for-kind :disconnected)))]
      (is (thrown-with-msg?
           js/Error
           #"account lifecycle invariant failed"
           (validation/install-store-state-validation! store))))))

(deftest install-store-state-validation-allows-account-surfaces-while-effective-account-is-present-test
  (with-redefs [validation/validation-enabled? (constantly true)]
    (let [store (atom (lifecycle-fixtures/seed-stale-account-surfaces
                       (lifecycle-fixtures/state-for-kind :owner)))]
      (validation/install-store-state-validation! store)
      (is (thrown-with-msg?
           js/Error
           #"account lifecycle invariant failed"
           (reset! store
                   (lifecycle-fixtures/seed-stale-account-surfaces
                    (lifecycle-fixtures/state-for-kind :disconnected))))))))

