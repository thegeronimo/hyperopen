(ns hyperopen.portfolio.vault-attribution-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.api.projections.portfolio-vault-attribution :as projections]
            [hyperopen.portfolio.vault-attribution-actions :as actions]))

(def ^:private address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
(def ^:private other "0x1111111111111111111111111111111111111111")
(def ^:private now 1800000000000)

(defn- state-with
  [slot]
  {:wallet {:address address}
   :portfolio {:vault-attribution slot}})

(def ^:private fetch [[:effects/api-fetch-portfolio-vault-attribution address]])

(deftest load-vault-attribution-fetches-when-nothing-loaded-test
  (is (= fetch (actions/load-portfolio-vault-attribution {:wallet {:address address}} now)))
  (is (= fetch (actions/load-portfolio-vault-attribution (state-with {:status :idle}) now))))

(deftest load-vault-attribution-skips-loading-and-fresh-test
  (testing "in flight for this address"
    (is (= [] (actions/load-portfolio-vault-attribution
               (state-with {:status :loading :for-address address}) now))))
  (testing "ready and younger than five minutes"
    (is (= [] (actions/load-portfolio-vault-attribution
               (state-with {:status :ready :for-address address
                            :loaded-at-ms (- now 299999)})
               now)))))

(deftest load-vault-attribution-refetches-stale-failed-or-foreign-data-test
  (testing "ready but five minutes old"
    (is (= fetch (actions/load-portfolio-vault-attribution
                  (state-with {:status :ready :for-address address
                               :loaded-at-ms (- now actions/fresh-for-ms)})
                  now))))
  (testing "ready without a timestamp"
    (is (= fetch (actions/load-portfolio-vault-attribution
                  (state-with {:status :ready :for-address address}) now))))
  (testing "a failed load retries"
    (is (= fetch (actions/load-portfolio-vault-attribution
                  (state-with {:status :error :for-address address}) now))))
  (testing "data for another address is neither loading nor fresh"
    (is (= fetch (actions/load-portfolio-vault-attribution
                  (state-with {:status :ready :for-address other :loaded-at-ms now}) now)))
    (is (= fetch (actions/load-portfolio-vault-attribution
                  (state-with {:status :loading :for-address other}) now)))))

(deftest load-vault-attribution-without-account-test
  (is (= [] (actions/load-portfolio-vault-attribution {} now)))
  (is (= [] (actions/load-portfolio-vault-attribution
             {:portfolio {:vault-attribution {:status :idle}}} now))))

(deftest load-vault-attribution-uses-clock-for-single-arity-test
  (is (= [] (actions/load-portfolio-vault-attribution
             (state-with {:status :ready :for-address address
                          :loaded-at-ms (.now js/Date)})))))

(def ^:private vault "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303")

(defn- snapshot-state
  [live-equity loaded-at-ms]
  (assoc (state-with {:status :ready :for-address address :loaded-at-ms loaded-at-ms
                      :details-by-vault {vault {:follower-state {:vault-equity 1000}}}
                      :errors-by-vault {}})
         :vaults {:user-equities [{:vault-address vault :equity live-equity}]
                  :user-equities-for-address address}))

(deftest load-vault-attribution-refetches-when-marked-stale-test
  (is (= fetch (actions/load-portfolio-vault-attribution
                (state-with {:status :ready :for-address address :stale? true
                             :loaded-at-ms (- now 1000)})
                now))))

(deftest load-vault-attribution-refetches-on-live-equity-drift-test
  (testing "live equity moved more than 2% since the snapshot"
    (is (= fetch (actions/load-portfolio-vault-attribution
                  (snapshot-state 1500 (- now 120000)) now))))
  (testing "within 2% is the same snapshot"
    (is (= [] (actions/load-portfolio-vault-attribution
               (snapshot-state 1010 (- now 120000)) now))))
  (testing "a snapshot younger than the live poll is not second-guessed"
    (is (= [] (actions/load-portfolio-vault-attribution
               (snapshot-state 1500 (- now 1000)) now))))
  (testing "live rows for another account are ignored"
    (is (= [] (actions/load-portfolio-vault-attribution
               (assoc-in (snapshot-state 1500 (- now 120000))
                         [:vaults :user-equities-for-address] other)
               now)))))

(deftest load-vault-attribution-retries-a-failed-refresh-test
  (is (= fetch (actions/load-portfolio-vault-attribution
                (state-with {:status :error :for-address address :error "x"
                             :loaded-at-ms (- now 1000)})
                now))))

(def ^:private orphan "0x3333333333333333333333333333333333333333")

(defn- loaded
  "State after a real load (through the projection) at `loaded-at-ms`, with
   `live` rows already on screen at load time."
  [live loaded-at-ms]
  (let [state {:wallet {:address address}
               :portfolio {:vault-attribution {:status :loading :for-address address}}
               :vaults {:user-equities live
                        :user-equities-for-address address}}]
    (projections/apply-success
     state address
     {:ledger-rows [{:time 1 :delta {:type "vaultDeposit" :vault vault :usdc "1000"}}]
      :details-by-vault {vault {:follower-state {:vault-equity 1000}}}
      :errors-by-vault {}
      :loaded-at-ms loaded-at-ms})))

(deftest load-vault-attribution-drift-does-not-loop-test
  (testing "a live vault the snapshot cannot value (orphan) is not drift after the load"
    (let [state (loaded [{:vault-address vault :equity 1000}
                         {:vault-address orphan :equity 5000}]
                        (- now 120000))]
      (is (= [] (actions/load-portfolio-vault-attribution state now)))))
  (testing "a vault whose details failed is not drift after the load"
    (let [state (-> (loaded [{:vault-address vault :equity 1000}
                             {:vault-address orphan :equity 5000}]
                            (- now 120000))
                    (assoc-in [:portfolio :vault-attribution :errors-by-vault] {orphan "boom"}))]
      (is (= [] (actions/load-portfolio-vault-attribution state now)))))
  (testing "a live vault that appears after the load drifts once, then is quiet"
    (let [state (loaded [{:vault-address vault :equity 1000}] (- now 120000))
          later (assoc-in state [:vaults :user-equities]
                          [{:vault-address vault :equity 1000}
                           {:vault-address orphan :equity 5000}])]
      (is (= fetch (actions/load-portfolio-vault-attribution later now)))
      (let [reloaded (projections/apply-success
                      (assoc-in later [:portfolio :vault-attribution :status] :loading)
                      address
                      {:ledger-rows [] :details-by-vault {vault {:follower-state {:vault-equity 1000}}}
                       :errors-by-vault {} :loaded-at-ms (- now 120000)})]
        (is (= [] (actions/load-portfolio-vault-attribution reloaded now))))))
  (testing "dust in a new live vault is not drift"
    (let [state (loaded [{:vault-address vault :equity 1000}] (- now 120000))]
      (is (= [] (actions/load-portfolio-vault-attribution
                 (assoc-in state [:vaults :user-equities]
                           [{:vault-address vault :equity 1000}
                            {:vault-address orphan :equity 0.5}])
                 now))))))
