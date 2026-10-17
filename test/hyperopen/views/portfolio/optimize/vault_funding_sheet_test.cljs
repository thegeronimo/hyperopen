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
    (testing "plan: split cards, target beside after, lock-ups, and a send button that names the amounts"
      (let [view (sheet/vault-funding-sheet state)
            header (hiccup/node-text (hiccup/find-first-node view #(= "portfolio-optimizer-vault-funding-rows" (get-in % [1 :data-role]))))]
        (is (= "$3,000.00" (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-total-withdraw"))))
        (is (= "$7,000.00" (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-total-deposit"))))
        (is (re-find #"Include\s+Vault\s+You hold\s+Current\s+After\s+Action\s+Locked until" header))
        (let [rows-node (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-rows")
              header-row (first (hiccup/node-children rows-node))
              first-row (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-row")
              sr-only? #(some #{"sr-only"} (get-in % [1 :class]))]
          (is (= (count (hiccup/node-children first-row)) (count (hiccup/node-children header-row)))
              "one header cell per row cell")
          (is (not-any? sr-only? (hiccup/node-children header-row))
              "no header cell drops out of the grid"))
        (is (= ["—" "100.0%" "—"] (mapv hiccup/node-text (hiccup/find-all-nodes view #(= "portfolio-optimizer-vault-funding-current" (get-in % [1 :data-role])))))
            "only Beta is held today")
        (is (= ["50.0%" "30.0%" "20.0%"] (mapv hiccup/node-text (hiccup/find-all-nodes view #(= "portfolio-optimizer-vault-funding-after" (get-in % [1 :data-role]))))))
        (is (= "true" (str (get-in (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-split-target") [1 :aria-checked]))))
        (is (some? (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-split-equal")))
        (is (re-find #"1 day lock-up" (hiccup/node-text view)))
        (is (re-find #"Hyperliquid locks each deposit" (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-lockup"))))
        (is (re-find #"\w{3} \d+" (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-free-by"))))
        (is (= "Withdraw $3,000.00 and deposit $7,000.00"
               (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-confirm"))))
        (is (re-find #"no further confirmation" (hiccup/node-text (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-block"))))
        (is (nil? (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-review")) "no review step")
        (is (= 3 (count (hiccup/find-all-nodes view #(= "portfolio-optimizer-vault-funding-include" (get-in % [1 :data-role]))))))
        (is (some? (hiccup/find-by-data-role view "portfolio-optimizer-vault-funding-fill-gaps")))))
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
