(ns hyperopen.borrow-lend.actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.borrow-lend.account :as bl-account]
            [hyperopen.borrow-lend.actions :as actions]))

(def owner "0x1111111111111111111111111111111111111111")

(def reserves
  [{:token 0 :oracle-px 1 :ltv 0 :available 1000000 :total-supplied 5000000 :total-borrowed 4000000
    :utilization 0.8 :borrow-apy 0.05 :supply-apy 0.036}
   {:token 150 :oracle-px 40 :ltv 0.65 :available 0 :total-supplied 100000 :total-borrowed 0
    :utilization 0 :borrow-apy 0.05 :supply-apy 0}])

(defn- base-state
  ([] (base-state {}))
  ([overrides]
   (merge-with
    (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
    {:wallet {:address owner :connected? true}
     :account {:mode :unified :abstraction-raw "unifiedAccount"}
     :spot {:meta {:tokens [{:index 0 :name "USDC" :weiDecimals 8}
                            {:index 150 :name "HYPE" :weiDecimals 8}]}
            :clearinghouse-state {:balances [{:coin "USDC" :token 0 :total "12000" :hold "2000"}]}}
     :borrow-lend {:reserves reserves
                   :user-address owner
                   :user-state {:positions [{:token 0 :supplied 5000 :borrowed 2000}
                                            {:token 150 :supplied 100 :borrowed 0}]}}
     :borrow-lend-ui {:active-tab :markets
                      :modal {:open? false :kind nil :token nil :amount "" :max? false
                              :submitting? false :error nil}}}
    overrides)))

(defn- with-modal [state modal]
  (update-in state [:borrow-lend-ui :modal] merge {:open? true} modal))

(deftest earn-route-test
  (is (actions/earn-route? "/earn"))
  (is (actions/earn-route? "/Earn/?tab=x"))
  (is (not (actions/earn-route? "/earnings")))
  (is (= [] (actions/load-borrow-lend-route (base-state) "/staking"))))

(deftest load-borrow-lend-test
  (testing "projection first, then reserves and account fetches"
    (is (= [[:effects/save [:borrow-lend :requested-address] owner]
            [:effects/api-fetch-borrow-lend-reserves]
            [:effects/api-fetch-borrow-lend-user-state owner]
            [:effects/api-fetch-borrow-lend-interest owner]]
           (actions/load-borrow-lend (base-state)))))
  (testing "no account: clears the previous positions and loads reserves only"
    (is (= [[:effects/save-many [[[:borrow-lend :requested-address] nil]
                                 [[:borrow-lend :user-state] nil]
                                 [[:borrow-lend :user-address] nil]
                                 [[:borrow-lend :total-supplied-usd] 0]]]
            [:effects/api-fetch-borrow-lend-reserves]]
           (actions/load-borrow-lend (assoc (base-state) :wallet {})))))
  (testing "loads on the portfolio page but not the optimizer"
    (is (seq (actions/load-borrow-lend-route (base-state) "/portfolio")))
    (is (= [] (actions/load-borrow-lend-route (base-state) "/portfolio/optimize/new")))))

(deftest tab-test
  (is (= [[:effects/save [:borrow-lend-ui :active-tab] :positions]]
         (actions/set-borrow-lend-tab {} "positions")))
  (is (= [[:effects/save [:borrow-lend-ui :active-tab] :markets]]
         (actions/set-borrow-lend-tab {} :bogus))))

(deftest open-modal-test
  (is (= [[:effects/save [:borrow-lend-ui :modal]
           {:open? true :kind :supply :token 0 :amount "" :max? false :submitting? false :error nil}]]
         (actions/open-borrow-lend-modal (base-state) :supply 0)))
  (testing "portfolio margin turns a borrow into repay"
    (is (= :repay
           (get-in (actions/open-borrow-lend-modal
                    (assoc-in (base-state) [:account :abstraction-raw] "portfolioMargin") :borrow 0)
                   [0 2 :kind]))))
  (testing "bad kind or token opens nothing"
    (is (= [] (actions/open-borrow-lend-modal (base-state) :stake 0)))
    (is (= [] (actions/open-borrow-lend-modal (base-state) :supply "x")))))

(deftest max-amounts-test
  (let [state (base-state)]
    (is (= 10000 (bl-account/modal-max-amount state :supply 0)) "free spot = total - hold")
    (is (= 5000 (bl-account/modal-max-amount state :withdraw 0)))
    (is (= 2000 (bl-account/modal-max-amount state :repay 0)))
    (is (= 600 (bl-account/modal-max-amount state :borrow 0)) "2,600 limit minus 2,000 debt")
    (testing "withdraw is capped by pool liquidity"
      (is (= 0 (bl-account/modal-max-amount state :withdraw 150))
          "HYPE reserve reports no available balance in this fixture"))))

(deftest fraction-test
  (testing "half of the supply max"
    (is (= [[:effects/save-many [[[:borrow-lend-ui :modal :amount] "5000"]
                                 [[:borrow-lend-ui :modal :max?] false]
                                 [[:borrow-lend-ui :modal :error] nil]]]]
           (actions/set-borrow-lend-amount-fraction (with-modal (base-state) {:kind :supply :token 0}) 0.5))))
  (testing "full repay marks max? so the action sends a null amount"
    (is (true? (get-in (actions/set-borrow-lend-amount-fraction
                        (with-modal (base-state) {:kind :repay :token 0}) 1)
                       [0 1 1 1]))))
  (testing "full supply is a concrete amount"
    (is (false? (get-in (actions/set-borrow-lend-amount-fraction
                         (with-modal (base-state) {:kind :supply :token 0}) 1)
                        [0 1 1 1])))))

(deftest submit-test
  (testing "valid supply signs a borrowLend supply"
    (is (= [[:effects/save-many [[[:borrow-lend-ui :modal :submitting?] true]
                                 [[:borrow-lend-ui :modal :error] nil]]]
            [:effects/api-submit-borrow-lend
             {:kind :supply
              :action {:type "borrowLend" :operation "supply" :token 0 :amount "1000.5"}}]]
           (actions/submit-borrow-lend (with-modal (base-state) {:kind :supply :token 0 :amount "1000.50"})))))
  (testing "max repay sends a null amount"
    (is (= {:type "borrowLend" :operation "repay" :token 0 :amount nil}
           (get-in (actions/submit-borrow-lend (with-modal (base-state) {:kind :repay :token 0 :max? true}))
                   [1 1 :action]))))
  (testing "action keys are in wire order"
    (is (= [:type :operation :token :amount]
           (keys (get-in (actions/submit-borrow-lend (with-modal (base-state) {:kind :borrow :token 0 :amount "10"}))
                         [1 1 :action]))))))

(deftest submit-guard-test
  (let [error-of (fn [state] (let [effects (actions/submit-borrow-lend state)]
                               (when (= 1 (count effects)) (get-in effects [0 2]))))]
    (is (= "Enter an amount greater than zero."
           (error-of (with-modal (base-state) {:kind :supply :token 0 :amount "0"}))))
    (is (= "That is more than you can borrow against your collateral."
           (error-of (with-modal (base-state) {:kind :borrow :token 0 :amount "601"}))))
    (is (= "That is more than you owe."
           (error-of (with-modal (base-state) {:kind :repay :token 0 :amount "2500"}))))
    (is (= "Connect your wallet first."
           (error-of (assoc (with-modal (base-state) {:kind :supply :token 0 :amount "1"}) :wallet {}))))
    (is (= "Portfolio margin accounts borrow automatically. Manual borrow is off."
           (error-of (-> (base-state)
                         (assoc-in [:account :abstraction-raw] "portfolioMargin")
                         (with-modal {:kind :borrow :token 0 :amount "1"})))))
    (is (= "Choose an asset first."
           (error-of (with-modal (base-state) {:kind nil :token nil}))))))

(deftest stale-user-state-test
  (testing "positions for another address are ignored"
    (let [state (assoc-in (base-state) [:borrow-lend :user-address] "0x2222222222222222222222222222222222222222")]
      (is (= [] (bl-account/positions state)))
      (is (nil? (bl-account/modal-max-amount state :repay 0))))))

(deftest wire-amount-test
  (let [state (base-state)]
    (is (= "1.23456789" (bl-account/wire-amount state 0 "1.234567891")))
    (is (= "1000" (bl-account/wire-amount state 0 "1,000")))
    (is (nil? (bl-account/wire-amount state 0 "abc")))
    (is (nil? (bl-account/wire-amount state 0 "-5")))))
