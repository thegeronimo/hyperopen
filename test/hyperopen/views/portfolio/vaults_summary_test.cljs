(ns hyperopen.views.portfolio.vaults-summary-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.summary-cards :as summary-cards]))

(def ^:private selectors
  {:summary-scope {:label "Vaults"
                   :open? false
                   :value :vaults
                   :options [{:value :all :label "Perps + Spot + Vaults"}
                             {:value :perps :label "Perps"}
                             {:value :vaults :label "Vaults"}]}
   :summary-time-range {:label "30D" :open? false :value :month :options []}})

(defn- card
  [vaults]
  (summary-cards/summary-card {:summary {:pnl 0
                                         :volume 999
                                         :total-equity 12345
                                         :show-perps-account-equity? true
                                         :show-vault-equity? true
                                         :vault-equity 5
                                         :show-earn-balance? true
                                         :show-staking-account? true
                                         :spot-equity-label "Spot Account Equity"
                                         :vaults-scope vaults}
                               :selectors selectors}))

(def ^:private ready
  {:status :ready
   :partial? false
   :pnl 120.5
   :max-drawdown-pct 0.0821
   :estimated? true
   :totals {:deposited 1000 :withdrawn 200 :net-deposited 800 :value 1450
            :pnl 650 :realized 100 :unrealized 550
            :money-weighted-return 0.074 :open-count 3 :closed-count 1}})

(deftest ready-rows-and-order-test
  (let [view (card ready)
        all-text (vec (hiccup/collect-strings view))]
    (is (= ["PNL" "Max Drawdown" "Vault PNL" "Realized" "Unrealized"
            "Money-weighted Return" "Vault Equity" "Net Deposited" "Vaults Held"]
           (filterv #{"PNL" "Max Drawdown" "Vault PNL" "Realized" "Unrealized"
                      "Money-weighted Return" "Vault Equity" "Net Deposited" "Vaults Held"}
                    all-text)))
    (is (some #{"All-time"} all-text))
    (is (some #{"+7.4% / yr"} all-text))
    (is (some #{"3 open · 1 closed"} all-text))
    (is (some #{"+$650.00"} all-text))
    (is (some? (hiccup/find-by-data-role view "portfolio-vaults-drawdown-estimated-mark")))
    (is (contains? (hiccup/node-class-set (hiccup/find-by-data-role view "portfolio-vaults-pnl"))
                   "summary-kv-row"))
    (testing "whole-account rows are not shown"
      (doseq [label ["Volume" "Total Equity" "Perps Account Equity" "Spot Account Equity"
                     "Earn Balance" "Staking Account"]]
        (is (not (some #{label} all-text)) label)))
    (is (nil? (hiccup/find-by-data-role view "portfolio-vaults-status")))
    (is (nil? (hiccup/find-by-data-role view "portfolio-vaults-partial")))))

(deftest loading-rows-are-unknown-not-zero-test
  (let [view (card {:status :loading :partial? false :pnl nil :totals nil})
        all-text (vec (hiccup/collect-strings view))]
    (is (some #{"Loading vault history…"} all-text))
    (is (some #{"Vault PNL"} all-text))
    (is (>= (count (filter #{"--"} all-text)) 9))
    (is (not-any? #(re-find #"\$" %) all-text))
    (is (nil? (hiccup/find-by-data-role view "portfolio-vaults-drawdown-estimated-mark")))))

(deftest error-empty-and-partial-lines-test
  (testing "error"
    (let [all-text (vec (hiccup/collect-strings (card {:status :error :totals nil})))]
      (is (some #{"Vault history could not be loaded."} all-text))
      (is (some #{"Vault PNL"} all-text))))
  (testing "empty is a single line"
    (let [all-text (vec (hiccup/collect-strings (card {:status :empty :totals nil})))]
      (is (some #{"No vault deposits found for this account."} all-text))
      (is (not (some #{"Vault PNL"} all-text)))
      (is (not (some #{"PNL"} all-text)))))
  (testing "partial adds a line"
    (let [all-text (vec (hiccup/collect-strings (card (assoc ready :partial? true))))]
      (is (some #{"Some vault histories failed to load; figures may be incomplete."} all-text))))
  (testing "unknown money-weighted return is --"
    (let [view (card (assoc-in ready [:totals :money-weighted-return] nil))]
      (is (some #{"--"} (hiccup/collect-strings view))))))

(deftest other-scopes-render-the-whole-account-card-test
  (let [view (summary-cards/summary-card {:summary {:pnl 1 :volume 2 :total-equity 3
                                                    :show-vault-equity? true :vault-equity 4
                                                    :spot-equity-label "Spot Account Equity"}
                                          :selectors selectors})
        all-text (set (hiccup/collect-strings view))]
    (is (contains? all-text "Volume"))
    (is (contains? all-text "Total Equity"))
    (is (nil? (hiccup/find-by-data-role view "portfolio-vaults-summary")))))

(deftest excluded-ledger-partial-and-refresh-error-lines-test
  (let [view (card (-> ready
                       (assoc :partial? true :ledger-partial? true :refresh-error? true)
                       (assoc-in [:totals :incomplete-count] 2)))
        all-text (set (hiccup/collect-strings view))]
    (is (contains? all-text "2 vaults excluded: incomplete history"))
    (is (contains? all-text "Vault history may be incomplete: the ledger is too long to load in full."))
    (is (contains? all-text "Couldn't refresh vault history. Showing the last loaded figures."))
    (is (some? (hiccup/find-by-data-role view "portfolio-vaults-excluded")))
    (is (some? (hiccup/find-by-data-role view "portfolio-vaults-refresh-error"))))
  (is (nil? (hiccup/find-by-data-role (card (assoc-in ready [:totals :incomplete-count] 0))
                                      "portfolio-vaults-excluded"))))
