(ns hyperopen.views.earn-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.earn-view :as earn-view]))

(def owner "0x1111111111111111111111111111111111111111")

(def state
  {:wallet {:address owner :connected? true}
   :account {:mode :unified :abstraction-raw "unifiedAccount"}
   :spot {:meta {:tokens [{:index 0 :name "USDC" :weiDecimals 8}
                          {:index 150 :name "HYPE" :weiDecimals 8}]}}
   :borrow-lend {:reserves [{:token 0 :oracle-px 1 :ltv 0 :utilization 0.831 :borrow-apy 0.198
                             :supply-apy 0.1484 :total-supplied 100 :total-borrowed 83 :available 17}
                            {:token 150 :oracle-px 40 :ltv 0.65 :utilization 0 :borrow-apy 0.05
                             :supply-apy 0 :total-supplied 1000 :total-borrowed 0 :available 1000}]
                 :user-address owner
                 :interest-address owner
                 :interest [{:time-ms 1 :token "USDC" :supply 1.25 :borrow 0}]
                 :user-state {:positions [{:token 0 :supplied 5000 :borrowed 2000}
                                          {:token 150 :supplied 100 :borrowed 0}]}}
   :borrow-lend-ui {:active-tab :markets
                    :modal {:open? false}}})

(defn- with-tab [tab] (assoc-in state [:borrow-lend-ui :active-tab] tab))

(deftest every-tab-renders-test
  ;; A render that throws leaves the browser half-patched, so each tab and the
  ;; modal must at least build their hiccup.
  (testing "markets"
    (let [view (earn-view/earn-view (with-tab :markets))]
      (is (some? (hiccup/find-by-data-role view "earn-pool-row-0")))
      (is (some? (hiccup/find-by-data-role view "earn-collateral-row-150")))))
  (testing "positions"
    (let [view (earn-view/earn-view (with-tab :positions))]
      (is (some? (hiccup/find-by-data-role view "earn-position-0-supply")))
      (is (some? (hiccup/find-by-data-role view "earn-position-0-borrow")))
      (is (some? (hiccup/find-by-data-role view "earn-position-150-supply")))))
  (testing "history"
    (is (some? (hiccup/find-by-data-role (earn-view/earn-view (with-tab :history)) "earn-history"))))
  (testing "every modal kind"
    (doseq [kind [:supply :withdraw :borrow :repay]
            token [0 150]]
      (let [view (earn-view/earn-view
                  (assoc-in state [:borrow-lend-ui :modal]
                            {:open? true :kind kind :token token :amount "10" :max? false}))]
        (is (some? (hiccup/find-by-data-role view "earn-modal")) (str kind token))))))

(deftest portfolio-margin-disables-borrow-test
  (let [view (earn-view/earn-view (assoc-in (with-tab :markets) [:account :abstraction-raw] "portfolioMargin"))]
    (is (some? (hiccup/find-by-data-role view "earn-pm-notice")))
    (is (true? (get-in (hiccup/find-by-data-role view "earn-borrow-0") [1 :disabled])))))
