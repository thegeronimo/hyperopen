(ns hyperopen.views.account-info.tabs.earn-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.history.actions :as history-actions]
            [hyperopen.account.history.shared :as history-shared]
            [hyperopen.runtime.effect-order-contract :as effect-order-contract]
            [hyperopen.startup.route-refresh :as route-refresh]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tab-registry :as tab-registry]
            [hyperopen.views.account-info.tabs.earn :as earn-tab]))

(def owner "0x1111111111111111111111111111111111111111")

(def state
  {:wallet {:address owner :connected? true}
   :router {:path "/trade"}
   :account {:mode :unified :abstraction-raw "unifiedAccount"}
   :spot {:meta {:tokens [{:index 0 :name "USDC" :weiDecimals 8}
                          {:index 150 :name "HYPE" :weiDecimals 8}]}}
   :borrow-lend {:reserves [{:token 0 :oracle-px 1 :ltv 0 :utilization 0.831 :borrow-apy 0.198
                             :supply-apy 0.1484 :total-supplied 100 :total-borrowed 83 :available 17}
                            {:token 150 :oracle-px 40 :ltv 0.65 :utilization 0 :borrow-apy 0.05
                             :supply-apy 0 :total-supplied 1000 :total-borrowed 0 :available 1000}]
                 :user-address owner
                 :user-state {:positions [{:token 0 :supplied 5000 :borrowed 2000}
                                          {:token 150 :supplied 100 :borrowed 0}]}}})

(deftest tab-is-registered-test
  (is (some #{:earn} tab-registry/available-tabs))
  (is (= "Earn (3)" (tab-registry/tab-label :earn {:earn 3})))
  (is (= "Earn" (tab-registry/tab-label :earn {:earn 0})))
  (is (= :earn (history-shared/normalize-account-info-route-tab "earn"))))

(deftest tab-model-test
  (let [{:keys [positions summary utilization-by-token]} (earn-tab/earn-tab-model state)]
    (is (= 3 (earn-tab/tab-count state)))
    (is (= [:supplied :collateral :borrowed] (mapv :kind positions)))
    (is (= "130%" (:health-factor summary)))
    (is (= "$24.24" (:liquidation-px summary)))
    (is (= "83.1%" (get utilization-by-token 0)))))

(deftest render-test
  (let [view (earn-tab/earn-tab-content {:earn (earn-tab/earn-tab-model state)})]
    (testing "one row per position with actions that open the Earn modal"
      (is (some? (hiccup/find-by-data-role view "account-earn-row-0-supply")))
      (is (some? (hiccup/find-by-data-role view "account-earn-row-0-borrow")))
      (is (= [[:actions/navigate "/earn"] [:actions/open-borrow-lend-modal :repay 0]]
             (get-in (hiccup/find-by-data-role view "account-earn-repay-0") [1 :on :click]))))
    (testing "summary strip shows health"
      (is (some #(= "130%" %) (hiccup/collect-strings (hiccup/find-by-data-role view "account-earn-summary")))))))

(deftest empty-render-test
  (let [view (earn-tab/earn-tab-content
              {:earn (earn-tab/earn-tab-model (assoc-in state [:borrow-lend :user-state :positions] []))})]
    (is (some? (hiccup/find-by-data-role view "account-earn-empty")))))

(deftest selecting-tab-loads-lending-data-test
  (let [effects (history-actions/select-account-info-tab state :earn)
        ids (mapv first effects)]
    (is (= :effects/save (first ids)))
    (is (some #{:effects/api-fetch-borrow-lend-user-state} ids))
    (is (some #{:effects/api-fetch-borrow-lend-reserves} ids))
    (testing "satisfies the effect-order contract"
      (is (= effects (effect-order-contract/assert-action-effect-order!
                      :actions/select-account-info-tab effects {:phase :test}))))))

(deftest trade-route-refresh-loads-when-earn-tab-is-shown-test
  (is (some #{[:actions/load-borrow-lend]}
            (route-refresh/current-route-refresh-effects
             (assoc-in state [:account-info :selected-tab] :earn) owner)))
  (is (not (some #{[:actions/load-borrow-lend]}
                 (route-refresh/current-route-refresh-effects
                  (assoc-in state [:account-info :selected-tab] :balances) owner)))))

(deftest nine-tabs-keep-declaration-order-test
  ;; A ninth base tab pushes the merged definitions past array-map size, so
  ;; order must not come from map key order.
  (is (= [:balances :positions :outcomes :open-orders :twap :trade-history
          :funding-history :order-history :earn]
         (tab-registry/available-tabs-for [] [] {})))
  (is (= [:x :balances :positions :outcomes :open-orders :twap :trade-history
          :funding-history :order-history :earn]
         (tab-registry/available-tabs-for [{:id :x :label "X"}] [] {}))))
