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

(def ^:private hour-ms (* 60 60 1000))
(def ^:private day-ms (* 24 hour-ms))
(def ^:private loaded-ms (* 1000 day-ms))

(defn- with-history
  "Positions tab for a USDC supplier who supplied 4,000 ten days before the
  load and received 0.05 USDC every hour since, plus one row from 40 days
  before that falls outside the 30-day interest column."
  [base]
  (let [hours (* 10 24)
        interest (mapv (fn [h] {:time-ms (- loaded-ms (* h hour-ms)) :token "USDC"
                                :supply 0.05 :borrow 0 :hours 1 :daily? false})
                       (range hours 0 -1))]
    (-> base
        (assoc-in [:borrow-lend-ui :active-tab] :positions)
        (assoc-in [:borrow-lend :interest] interest)
        (assoc-in [:borrow-lend :ledger] [{:time-ms (- loaded-ms (* hours hour-ms) 1000)
                                           :token "USDC" :operation :supply :amount 4000}])
        (assoc-in [:borrow-lend :loaded-at-ms :interest] loaded-ms)
        (assoc-in [:borrow-lend :user-state :positions]
                  [{:token 0 :supplied (+ 4000 (* 0.05 hours)) :borrowed 0}]))))

(deftest realized-returns-ladder-test
  (let [view (earn-view/earn-view (with-history state))]
    (testing "periods shorter than the position plus since first supply"
      (is (some? (hiccup/find-by-data-role view "earn-returns-USDC-7d")))
      (is (nil? (hiccup/find-by-data-role view "earn-returns-USDC-1m")))
      (is (some? (hiccup/find-by-data-role view "earn-returns-USDC-all"))))
    (testing "7 days: interest over balance-hours, annualized"
      (let [text (hiccup/node-text (hiccup/find-by-data-role view "earn-returns-USDC-7d"))]
        ;; Rows ending after the window start: 167 × 0.05 = 8.35 USDC on ~4,008 average → ≈10.93%.
        (is (re-find #"10\.9\d%" text) text)
        (is (re-find #"\+8\.35 USDC" text) text)
        (is (re-find #"7 / 7" text) text))))
  (testing "the interest column still covers 30 days only"
    (let [old-row {:time-ms (- loaded-ms (* 40 day-ms)) :token "USDC" :supply 100 :borrow 0 :hours 24 :daily? true}
          s (update-in (with-history state) [:borrow-lend :interest] #(into [old-row] %))
          text (hiccup/node-text (hiccup/find-by-data-role (earn-view/earn-view s) "earn-position-0-supply"))]
      (is (re-find #"\+12\.00 USDC" text) text))))

(deftest realized-returns-states-test
  (testing "waiting for the interest load"
    (is (some? (hiccup/find-by-data-role (earn-view/earn-view (with-tab :positions)) "earn-returns-loading"))))
  (testing "portfolio margin"
    (is (some? (hiccup/find-by-data-role
                (earn-view/earn-view (assoc-in (with-history state) [:account :abstraction-raw] "portfolioMargin"))
                "earn-returns-portfolio-margin"))))
  (testing "history that does not add up to the balance"
    (is (some? (hiccup/find-by-data-role
                (earn-view/earn-view (assoc-in (with-history state) [:borrow-lend :user-state :positions]
                                               [{:token 0 :supplied 5 :borrowed 0}]))
                "earn-returns-USDC-inconsistent"))))
  (testing "no card without a supplied, interest-earning token"
    (is (nil? (hiccup/find-by-data-role
               (earn-view/earn-view (assoc-in (with-history state) [:borrow-lend :user-state :positions]
                                              [{:token 150 :supplied 100 :borrowed 0}]))
               "earn-returns")))))
