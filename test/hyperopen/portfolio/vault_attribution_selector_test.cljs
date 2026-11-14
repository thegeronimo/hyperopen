(ns hyperopen.portfolio.vault-attribution-selector-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.vault-attribution-selector :as selector]
            [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]))

(def ^:private address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")
(def ^:private other "0x1111111111111111111111111111111111111111")

(defn- ready-slot
  [& {:as overrides}]
  (merge {:status :ready
          :for-address address
          :ledger-rows [(fx/deposit (- fx/now (* 10 fx/day)) fx/vault-a "1000")]
          :details-by-vault {fx/vault-a {:name "HLP"
                                         :follower-state {:vault-equity 1500}}}
          :errors-by-vault {}
          :loaded-at-ms fx/now
          :error nil}
         overrides))

(defn- state-with
  [slot & {:as extra}]
  (merge {:wallet {:address address}
          :portfolio {:vault-attribution slot}}
         extra))

(defn- vault-value
  [model]
  (some #(when (= fx/vault-a (:vault-address %)) (:value %))
        (get-in model [:attribution :vaults])))

(deftest load-status-test
  (testing "no account"
    (is (= {:load-status :none :refreshing? false :error nil :refresh-error nil
            :ledger-partial? false :partial? false :attribution nil}
           (selector/model {:portfolio {:vault-attribution (ready-slot)}}))))
  (testing "default and foreign data are idle"
    (is (= :idle (:load-status (selector/model (state-with {:status :idle})))))
    (is (= :idle (:load-status (selector/model (state-with (ready-slot :for-address other)))))))
  (testing "loading"
    (let [m (selector/model (state-with {:status :loading :for-address address}))]
      (is (= :loading (:load-status m)))
      (is (nil? (:attribution m)))))
  (testing "error"
    (let [m (selector/model (state-with {:status :error :for-address address :error "boom"}))]
      (is (= :error (:load-status m)))
      (is (= "boom" (:error m)))
      (is (nil? (:attribution m)))))
  (testing "ready"
    (let [m (selector/model (state-with (ready-slot)))]
      (is (= :ready (:load-status m)))
      (is (false? (:partial? m)))
      (is (= :ready (get-in m [:attribution :status]))))))

(deftest stale-while-revalidate-test
  (testing "a refresh over held data for the same address stays ready"
    (let [m (selector/model (state-with (ready-slot :status :loading)))]
      (is (= :ready (:load-status m)))
      (is (true? (:refreshing? m)))
      (is (= 1500 (vault-value m)))))
  (testing "a first load (nothing held) is plain loading"
    (let [m (selector/model (state-with (ready-slot :status :loading :loaded-at-ms nil)))]
      (is (= :loading (:load-status m)))
      (is (false? (:refreshing? m)))
      (is (nil? (:attribution m)))))
  (testing "data held for another address is never shown"
    (let [m (selector/model (state-with (ready-slot :status :loading :for-address other)))]
      (is (= :idle (:load-status m)))
      (is (nil? (:attribution m)))))
  (testing "a settled load is not refreshing"
    (is (false? (:refreshing? (selector/model (state-with (ready-slot))))))))

(deftest partial-flag-test
  (is (true? (:partial? (selector/model
                         (state-with (ready-slot :errors-by-vault {fx/vault-b "x"})))))))

(defn- vault-of
  [model]
  (some #(when (= fx/vault-a (:vault-address %)) %) (get-in model [:attribution :vaults])))

(def ^:private live-rows
  {:vaults {:user-equities [{:vault-address fx/vault-a :equity 1200 :equity-raw "1200.0"}]
            :user-equities-for-address address}})

(deftest equity-source-test
  (testing "the snapshot's follower-state equity wins over the live rows"
    (is (= 1500 (vault-value (selector/model (apply state-with (ready-slot)
                                                    (mapcat identity live-rows)))))))
  (testing "details without a follower state, ledger still open"
    (let [slot (ready-slot :details-by-vault {fx/vault-a {:name "HLP"}})]
      (testing "and the vault in the live rows: unknown, not zero"
        (let [m (selector/model (apply state-with slot (mapcat identity live-rows)))]
          (is (nil? (vault-value m)))
          (is (false? (:flows-complete? (vault-of m))))
          (is (= :open (:status (vault-of m))))))
      (testing "and no live rows at all: unknown"
        (is (nil? (vault-value (selector/model (state-with slot))))))
      (testing "and trusted live rows without it: a known zero"
        (let [m (selector/model (state-with slot :vaults {:user-equities []
                                                          :user-equities-for-address address}))]
          (is (= 0 (vault-value m)))
          (is (= :closed (:status (vault-of m))))))))
  (testing "details without a follower state and a fully withdrawn ledger: known zero"
    (let [m (selector/model (state-with (ready-slot
                                         :details-by-vault {fx/vault-a {:name "HLP"}}
                                         :ledger-rows [(fx/deposit (- fx/now (* 10 fx/day)) fx/vault-a "1000")
                                                       (fx/withdraw (- fx/now fx/day) fx/vault-a
                                                                    {:requested "1100" :commission "0"
                                                                     :basis "1000" :net "1100"})])))]
      (is (= 0 (vault-value m)))
      (is (true? (:flows-complete? (vault-of m))))))
  (testing "failed details fall back to the account's live rows"
    (let [slot (ready-slot :details-by-vault {} :errors-by-vault {fx/vault-a "boom"})]
      (is (= 1200 (vault-value (selector/model (apply state-with slot
                                                      (mapcat identity live-rows))))))
      (testing "but a live list without the vault is no evidence: unknown, never a total loss"
        (let [m (selector/model (state-with slot :vaults {:user-equities []
                                                          :user-equities-for-address address}))]
          (is (nil? (vault-value m)))
          (is (nil? (get-in m [:attribution :totals :pnl])))
          (is (= 1 (get-in m [:attribution :totals :incomplete-count])))))
      (testing "but not to another account's rows: the value is unknown"
        (let [m (selector/model (state-with slot :vaults {:user-equities [{:vault-address fx/vault-a
                                                                           :equity 9}]
                                                          :user-equities-for-address other}))]
          (is (nil? (vault-value m)))
          (is (false? (:flows-complete? (vault-of m))))
          (is (nil? (get-in m [:attribution :totals :pnl])))
          (is (= 1 (get-in m [:attribution :totals :incomplete-count])))))
      (testing "nor when no rows are loaded"
        (is (nil? (vault-value (selector/model (state-with slot)))))))))

(deftest refresh-error-keeps-data-test
  (let [m (selector/model (state-with (ready-slot :status :error :error "boom")))]
    (is (= :ready (:load-status m)))
    (is (= "boom" (:refresh-error m)))
    (is (nil? (:error m)))
    (is (= 1500 (vault-value m))))
  (testing "a first-load failure is still an error"
    (let [m (selector/model (state-with (ready-slot :status :error :error "boom" :loaded-at-ms nil)))]
      (is (= :error (:load-status m)))
      (is (= "boom" (:error m)))
      (is (nil? (:attribution m))))))

(deftest ledger-partial-flag-test
  (let [m (selector/model (state-with (ready-slot :ledger-partial? true)))]
    (is (true? (:ledger-partial? m)))
    (is (true? (:partial? m)))))

(deftest account-rows-filtered-test
  (let [follower-row (assoc-in (fx/withdraw (- fx/now fx/day) fx/vault-a
                                            {:requested "500" :commission "0" :basis "500" :net "500"})
                               [:delta :user] other)
        m (selector/model (state-with (ready-slot :ledger-rows [(fx/deposit (- fx/now (* 10 fx/day))
                                                                             fx/vault-a "1000")
                                                                 follower-row])))]
    (is (= 0 (:withdrawn (vault-of m))) "another user's withdrawal is not the account's")))

(deftest memoization-test
  (let [state (state-with (ready-slot))
        m (selector/model state)]
    (is (identical? m (selector/model state)))
    (is (identical? m (selector/model (assoc state :unrelated {:a 1})))
        "unrelated state changes do not recompute")
    (is (not (identical? m (selector/model (assoc-in state [:portfolio :vault-attribution]
                                                     (ready-slot :loaded-at-ms (inc fx/now)))))))))

(deftest leader-equity-from-followers-row-test
  ;; Hyperliquid lists the vault leader in `followers` as "Leader".
  (let [details {fx/vault-a {:name "Mine" :leader address
                             :followers [{:user "0x9999999999999999999999999999999999999999"
                                          :vault-equity 5}
                                         {:leader? true :vault-equity 1700}]}}]
    (is (= 1700 (vault-value (selector/model (state-with (ready-slot :details-by-vault details))))))
    (testing "an account's own follower row counts too"
      (is (= 1300 (vault-value
                   (selector/model
                    (state-with (ready-slot :details-by-vault
                                            {fx/vault-a {:followers [{:user address
                                                                      :vault-equity 1300}]}})))))))))
