(ns hyperopen.views.portfolio.summary-cards-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.summary-cards :as summary-cards]))

(deftest summary-card-renders-selector-hooks-and-account-breakdown-test
  (let [view (summary-cards/summary-card
              {:summary {:pnl -12.34
                         :volume 456.78
                         :max-drawdown-pct 0.12
                         :total-equity 890.12
                         :show-perps-account-equity? true
                         :perps-account-equity 222.22
                         :spot-equity-label "Spot Account Equity"
                         :spot-account-equity 333.33
                         :show-vault-equity? true
                         :vault-equity 444.44
                         :show-earn-balance? true
                         :earn-balance 555.55
                         :show-staking-account? true
                         :staking-account-hype 7}
               :selectors {:summary-scope {:label "Perps + Spot + Vaults"
                                           :open? true
                                           :value :all
                                           :options [{:value :all :label "Perps + Spot + Vaults"}
                                                     {:value :perps :label "Perps only"}]}
                           :summary-time-range {:label "30D"
                                                :open? true
                                                :value :month
                                                :options [{:value :month :label "30D"}
                                                          {:value :day :label "24H"}]}}})
        scope-trigger (hiccup/find-by-data-role view "portfolio-summary-scope-selector-trigger")
        scope-perps-option (hiccup/find-by-data-role view "portfolio-summary-scope-selector-option-perps")
        time-range-trigger (hiccup/find-by-data-role view "portfolio-summary-time-range-selector-trigger")
        time-range-day-option (hiccup/find-by-data-role view "portfolio-summary-time-range-selector-option-day")
        negative-pnl (hiccup/find-first-node view #(and (= :span (first %))
                                                        (contains? (hiccup/direct-texts %) "-$12.34")))
        all-text (set (hiccup/collect-strings view))]
    (is (= [[:actions/toggle-portfolio-summary-scope-dropdown]]
           (get-in scope-trigger [1 :on :click])))
    (is (true? (get-in scope-trigger [1 :aria-expanded])))
    (is (= [[:actions/select-portfolio-summary-scope :perps]]
           (get-in scope-perps-option [1 :on :click])))
    (is (= [[:actions/toggle-portfolio-summary-time-range-dropdown]]
           (get-in time-range-trigger [1 :on :click])))
    (is (= [[:actions/select-portfolio-summary-time-range :day]]
           (get-in time-range-day-option [1 :on :click])))
    (is (contains? (hiccup/node-class-set negative-pnl) "text-error"))
    (is (contains? all-text "Perps Account Equity"))
    (is (contains? all-text "Vault Equity"))
    (is (contains? all-text "Earn Balance"))
    (is (contains? all-text "Staking Account"))
    (is (contains? all-text "7 HYPE"))))

(deftest metric-cards-render-stable-volume-and-fee-copy-test
  (let [view (summary-cards/metric-cards {:volume-14d-usd 0
                                          :fees {:taker 0.45
                                                 :maker 0.15}
                                          :fee-schedule {:open? true}})
        volume-card (hiccup/find-by-data-role view "portfolio-14d-volume-card")
        fees-card (hiccup/find-by-data-role view "portfolio-fees-card")
        fee-schedule-trigger (hiccup/find-by-data-role view "portfolio-fee-schedule-trigger")
        all-text (set (hiccup/collect-strings view))]
    (is (some? volume-card))
    (is (some? fees-card))
    (is (= "button" (get-in fee-schedule-trigger [1 :type])))
    (is (= "dialog" (get-in fee-schedule-trigger [1 :aria-haspopup])))
    (is (= "true" (get-in fee-schedule-trigger [1 :aria-expanded])))
    (is (= [[:actions/open-portfolio-fee-schedule
             :event.currentTarget/bounds]]
           (get-in fee-schedule-trigger [1 :on :click])))
    (is (contains? all-text "14 Day Volume"))
    (is (some #(re-find #"^\$0(?:\.0)?$" %) all-text))
    (is (contains? all-text "Fees (Taker / Maker)"))
    (is (contains? all-text "0.450% / 0.150%"))
    (is (contains? all-text "View Volume"))
    (is (contains? all-text "View Fee Schedule"))))
