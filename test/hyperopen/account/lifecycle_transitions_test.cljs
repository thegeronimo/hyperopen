(ns hyperopen.account.lifecycle-transitions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.test-support.lifecycle :as lifecycle-fixtures]))

(deftest effective-account-transition-matrix-covers-connected-spectate-route-and-disconnected-test
  (doseq [{:keys [label from-kind to-kind expected-from expected-to
                  expected-to-streams? expected-to-mutations?]}
          [{:label "connected -> spectate"
            :from-kind :owner
            :to-kind :spectate
            :expected-from lifecycle-fixtures/owner-address
            :expected-to lifecycle-fixtures/spectate-address
            :expected-to-streams? true
            :expected-to-mutations? false}
           {:label "spectate -> connected"
            :from-kind :spectate
            :to-kind :owner
            :expected-from lifecycle-fixtures/spectate-address
            :expected-to lifecycle-fixtures/owner-address
            :expected-to-streams? true
            :expected-to-mutations? true}
           {:label "spectate -> disconnected"
            :from-kind :spectate
            :to-kind :disconnected
            :expected-from lifecycle-fixtures/spectate-address
            :expected-to nil
            :expected-to-streams? true
            :expected-to-mutations? true}
           {:label "connected -> disconnected"
            :from-kind :owner
            :to-kind :disconnected
            :expected-from lifecycle-fixtures/owner-address
            :expected-to nil
            :expected-to-streams? true
            :expected-to-mutations? true}
           {:label "route-account -> disconnected"
            :from-kind :trader-route
            :to-kind :disconnected
            :expected-from lifecycle-fixtures/trader-route-address
            :expected-to nil
            :expected-to-streams? true
            :expected-to-mutations? true}]]
    (let [from-state (lifecycle-fixtures/state-for-kind from-kind)
          to-state (lifecycle-fixtures/state-for-kind to-kind)]
      (is (= expected-from
             (account-context/effective-account-address from-state))
          label)
      (is (= expected-to
             (account-context/effective-account-address to-state))
          label)
      (is (= expected-to-streams?
             (account-context/user-stream-subscriptions-enabled? to-state))
          label)
      (is (= expected-to-mutations?
             (account-context/mutations-allowed? to-state))
          label))))
