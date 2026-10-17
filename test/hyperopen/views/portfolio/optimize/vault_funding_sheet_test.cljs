(ns hyperopen.views.portfolio.optimize.vault-funding-sheet-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.actions.vault-funding :as actions]
            [hyperopen.portfolio.optimizer.application.vault-funding.run :as run]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.vault-funding-actions-test :as fixture]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.optimize.vault-funding-sheet :as sheet]))

(defn- opened
  [amount]
  (let [state (fixture/base-state)
        state (fixture/apply-saves state (actions/portfolio-optimizer-vault-funding-command state :open fixture/now))]
    (fixture/apply-saves state (actions/set-portfolio-optimizer-vault-funding-field state :amount-input amount))))

(deftest closed-sheet-renders-nothing-test
  (is (nil? (sheet/vault-funding-sheet (fixture/base-state)))))

(deftest every-screen-renders-test
  (let [state (opened "4000")]
    (testing "plan"
      (let [view (sheet/vault-funding-sheet state)]
        (is (= "$3,000.00" (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-total-withdraw"))))
        (is (= "$7,000.00" (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-total-deposit"))))
        (is (= "Review 1 withdrawal + 2 deposits"
               (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-review"))))))
    (testing "review"
      (let [view (sheet/vault-funding-sheet (assoc-in state (conj contracts/ui-vault-funding-path :screen) :review))]
        (is (some? (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-review-screen")))
        (is (= "Withdraw $3,000.00 and deposit $7,000.00"
               (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-confirm"))))))
    (testing "progress, running then partly failed"
      (let [ready (fixture/reviewed state)
            running (fixture/apply-saves ready (actions/portfolio-optimizer-vault-funding-command ready :confirm fixture/now))
            view (sheet/vault-funding-sheet running)]
        (is (some? (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-stop")))
        (let [done (update-in running contracts/ui-vault-funding-run-path
                              (fn [r] (-> r
                                          (run/mark-done (get-in r [:rows 0 :row-id]))
                                          (run/mark-failed (get-in r [:rows 1 :row-id]) "Vault is not accepting deposits")
                                          (run/mark-done (get-in r [:rows 2 :row-id]))
                                          (run/finish 2))))
              view* (sheet/vault-funding-sheet done)]
          (is (= "Retry 1 transfer" (hiccup/node-text (hiccup/find-by-data-role view* "portfolio-optimizer-vault-funding-retry"))))
          (is (re-find #"not accepting deposits" (hiccup/node-text view*))))))))
