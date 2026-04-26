(ns hyperopen.runtime.action-adapters.navigation-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.optimizer.actions :as portfolio-optimizer-actions]
            [hyperopen.runtime.action-adapters.navigation :as navigation-adapters]
            [hyperopen.runtime.effect-order-contract :as effect-order-contract]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.surface-modules :as surface-modules]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.trading-indicators-modules :as trading-indicators-modules]
            [hyperopen.vaults.actions :as vault-actions]))

(deftest navigate-appends-vault-route-effects-after-route-projection-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path]
                                                 [[:effects/save [:vaults-ui :list-loading?] true]
                                                  [:effects/api-fetch-vault-index]])]
    (is (= [[:effects/save [:router :path] "/vaults"]
            [:effects/save [:vaults-ui :list-loading?] true]
            [:effects/push-state "/vaults"]
            [:effects/load-route-module "/vaults"]
            [:effects/api-fetch-vault-index]]
           (navigation-adapters/navigate {} "/vaults")))
    (is (= [[:effects/save [:router :path] "/vaults"]
            [:effects/save [:vaults-ui :list-loading?] true]
            [:effects/replace-state "/vaults"]
            [:effects/load-route-module "/vaults"]
            [:effects/api-fetch-vault-index]]
           (navigation-adapters/navigate {} "/vaults" {:replace? true})))))

(deftest navigate-appends-funding-route-effects-after-route-projection-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route
                (fn [_state _path]
                  [[:effects/save [:funding-comparison-ui :loading?] true]
                   [:effects/api-fetch-predicted-fundings]])]
    (is (= [[:effects/save [:router :path] "/funding-comparison"]
            [:effects/save [:funding-comparison-ui :loading?] true]
            [:effects/push-state "/funding-comparison"]
            [:effects/load-route-module "/funding-comparison"]
            [:effects/api-fetch-predicted-fundings]]
           (navigation-adapters/navigate {} "/funding-comparison")))))

(deftest navigate-appends-leaderboard-route-effects-after-route-projection-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])]
    (is (= [[:effects/save [:router :path] "/leaderboard"]
            [:effects/save [:leaderboard-ui :page] 1]
            [:effects/push-state "/leaderboard"]
            [:effects/load-route-module "/leaderboard"]
            [:effects/api-fetch-leaderboard]]
           (navigation-adapters/navigate {} "/leaderboard")))))

(deftest navigate-appends-staking-route-effects-after-route-projection-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path]
                                                     [[:effects/save [:staking-ui :form-error] nil]
                                                      [:effects/api-fetch-staking-validator-summaries]])]
    (is (= [[:effects/save [:router :path] "/staking"]
            [:effects/save [:staking-ui :form-error] nil]
            [:effects/push-state "/staking"]
            [:effects/load-route-module "/staking"]
            [:effects/api-fetch-staking-validator-summaries]]
           (navigation-adapters/navigate {} "/staking")))))

(deftest navigate-appends-api-wallet-route-effects-after-route-projection-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path]
                                                            [[:effects/save [:api-wallets :loading :extra-agents?] true]
                                                             [:effects/api-load-api-wallets]])]
    (is (= [[:effects/save [:router :path] "/API"]
            [:effects/save [:api-wallets :loading :extra-agents?] true]
            [:effects/push-state "/API"]
            [:effects/load-route-module "/API"]
            [:effects/api-load-api-wallets]]
           (navigation-adapters/navigate {} "/API")))))

(deftest navigate-appends-portfolio-optimizer-route-effects-after-route-module-load-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                portfolio-optimizer-actions/load-portfolio-optimizer-route
                (fn [_state path]
                  [[:effects/load-portfolio-optimizer-scenario "scn_route"]])]
    (is (= [[:effects/save [:router :path] "/portfolio/optimize/scn_route"]
            [:effects/push-state "/portfolio/optimize/scn_route"]
            [:effects/load-route-module "/portfolio/optimize/scn_route"]
            [:effects/load-portfolio-optimizer-scenario "scn_route"]]
           (navigation-adapters/navigate {} "/portfolio/optimize/scn_route")))))

(deftest navigate-trade-route-loads-deferred-chart-module-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                trade-modules/trade-chart-ready? (constantly false)
                trade-modules/trade-chart-loading? (constantly false)
                surface-modules/surface-ready? (constantly false)
                surface-modules/surface-loading? (constantly false)]
    (is (= [[:effects/save [:router :path] "/trade"]
            [:effects/push-state "/trade"]
            [:effects/load-trade-chart-module]
            [:effects/load-surface-module :account-surfaces]]
           (navigation-adapters/navigate {} "/trade")))))

(deftest navigate-trade-route-loads-indicator-runtime-when-active-indicators-exist-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                trade-modules/trade-chart-ready? (constantly false)
                trade-modules/trade-chart-loading? (constantly false)
                trading-indicators-modules/trading-indicators-ready? (constantly false)
                trading-indicators-modules/trading-indicators-loading? (constantly false)
                surface-modules/surface-ready? (constantly false)
                surface-modules/surface-loading? (constantly false)]
    (is (= [[:effects/save [:router :path] "/trade"]
            [:effects/push-state "/trade"]
            [:effects/load-trade-chart-module]
            [:effects/load-trading-indicators-module]
            [:effects/load-surface-module :account-surfaces]]
           (navigation-adapters/navigate {:chart-options {:active-indicators {:sma {:period 20}}}}
                                         "/trade")))))

(deftest navigate-trade-route-skips-account-surfaces-when-they-are-ready-or-loading-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                trade-modules/trade-chart-ready? (constantly true)
                trade-modules/trade-chart-loading? (constantly false)
                surface-modules/surface-ready? (fn [_state surface-id]
                                                 (= :account-surfaces surface-id))
                surface-modules/surface-loading? (constantly false)]
    (is (= [[:effects/save [:router :path] "/trade"]
            [:effects/push-state "/trade"]]
           (navigation-adapters/navigate {} "/trade"))))
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                trade-modules/trade-chart-ready? (constantly true)
                trade-modules/trade-chart-loading? (constantly false)
                surface-modules/surface-ready? (constantly false)
                surface-modules/surface-loading? (fn [_state surface-id]
                                                   (= :account-surfaces surface-id))]
    (is (= [[:effects/save [:router :path] "/trade"]
            [:effects/push-state "/trade"]]
           (navigation-adapters/navigate {} "/trade")))))

(deftest navigate-entering-portfolio-loads-chart-benchmark-effects-test
  (with-redefs [portfolio-actions/select-portfolio-chart-tab (fn [_state tab]
                                                                [[:effects/save-many
                                                                  [[[:portfolio-ui :chart-tab] tab]]]
                                                                 [:effects/fetch-candle-snapshot
                                                                  :coin "BTC"
                                                                  :interval :1h
                                                                  :bars 800]])
                vault-actions/load-vault-route (fn [_state _path]
                                                 [[:effects/save [:vaults-ui :list-loading?] true]
                                                  [:effects/api-fetch-vault-index]])]
    (is (= [[:effects/save [:router :path] "/portfolio"]
            [:effects/save-many
             [[[:portfolio-ui :chart-tab] :returns]]]
            [:effects/save [:vaults-ui :list-loading?] true]
            [:effects/push-state "/portfolio"]
            [:effects/load-route-module "/portfolio"]
            [:effects/fetch-candle-snapshot
             :coin "BTC"
             :interval :1h
             :bars 800]
            [:effects/api-fetch-vault-index]]
           (navigation-adapters/navigate {:router {:path "/trade"}
                                          :portfolio-ui {:chart-tab :returns}}
                                         "/portfolio")))))

(deftest navigate-inside-portfolio-does-not-rebootstrap-portfolio-chart-test
  (let [chart-bootstrap-calls (atom 0)]
    (with-redefs [portfolio-actions/select-portfolio-chart-tab (fn [_state _tab]
                                                                  (swap! chart-bootstrap-calls inc)
                                                                  [[:effects/save [:portfolio-ui :chart-tab] :returns]])
                  vault-actions/load-vault-route (fn [_state _path]
                                                   [[:effects/save [:vaults-ui :list-loading?] true]])]
      (is (= [[:effects/save [:router :path] "/portfolio"]
              [:effects/save [:vaults-ui :list-loading?] true]
              [:effects/push-state "/portfolio"]
              [:effects/load-route-module "/portfolio"]]
             (navigation-adapters/navigate {:router {:path "/portfolio"}
                                            :portfolio-ui {:chart-tab :returns}}
                                           "/portfolio")))
      (is (= 0 @chart-bootstrap-calls)))))

(deftest navigate-preserves-spectate-query-when-spectate-mode-is-active-test
  (with-redefs [portfolio-actions/select-portfolio-chart-tab (fn [_state _tab] [])
                vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])]
    (is (= [[:effects/save [:router :path] "/portfolio"]
            [:effects/push-state "/portfolio?spectate=0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
            [:effects/load-route-module "/portfolio"]]
           (navigation-adapters/navigate
            {:router {:path "/trade"}
             :account-context {:spectate-mode {:active? true
                                               :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}}
            "/portfolio")))))

(deftest navigate-to-trader-portfolio-suppresses-spectate-query-test
  (let [trader-route "/portfolio/trader/0x3333333333333333333333333333333333333333"]
    (with-redefs [portfolio-actions/select-portfolio-chart-tab (fn [_state _tab] [])
                  vault-actions/load-vault-route (fn [_state _path] [])
                  funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                  api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                  staking-actions/load-staking-route (fn [_state _path] [])]
      (is (= [[:effects/save [:router :path] trader-route]
              [:effects/push-state trader-route]
              [:effects/load-route-module trader-route]]
             (navigation-adapters/navigate
              {:router {:path "/leaderboard"}
               :account-context {:spectate-mode {:active? true
                                                 :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}}
              trader-route))))))

(deftest navigate-satisfies-effect-order-contract-for-deferred-routes-test
  (with-redefs [portfolio-actions/select-portfolio-chart-tab
                (fn [_state tab]
                  [[:effects/save-many
                    [[[:portfolio-ui :chart-tab] tab]]]
                   [:effects/fetch-candle-snapshot
                    :coin "BTC"
                    :interval :1h
                    :bars 800]])
                vault-actions/load-vault-route
                (fn [_state _path]
                  [[:effects/save [:vaults-ui :list-loading?] true]
                   [:effects/api-fetch-vault-index]])]
    (let [effects (navigation-adapters/navigate {:router {:path "/trade"}
                                                 :portfolio-ui {:chart-tab :returns}}
                                                "/portfolio")]
      (is (= effects
             (effect-order-contract/assert-action-effect-order!
              :actions/navigate
              effects
              {:phase :action-emission
               :action-id :actions/navigate})))))) 

(deftest navigate-trade-route-satisfies-effect-order-contract-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                trade-modules/trade-chart-ready? (constantly false)
                trade-modules/trade-chart-loading? (constantly false)]
    (let [effects (navigation-adapters/navigate {:router {:path "/portfolio"}} "/trade")]
      (is (= effects
             (effect-order-contract/assert-action-effect-order!
              :actions/navigate
              effects
              {:phase :action-emission
               :action-id :actions/navigate}))))))

(deftest navigate-trade-route-with-active-indicators-satisfies-effect-order-contract-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                trade-modules/trade-chart-ready? (constantly false)
                trade-modules/trade-chart-loading? (constantly false)
                trading-indicators-modules/trading-indicators-ready? (constantly false)
                trading-indicators-modules/trading-indicators-loading? (constantly false)]
    (let [effects (navigation-adapters/navigate {:router {:path "/portfolio"}
                                                 :chart-options {:active-indicators {:sma {:period 20}}}}
                                                "/trade")]
      (is (= effects
             (effect-order-contract/assert-action-effect-order!
              :actions/navigate
              effects
              {:phase :action-emission
               :action-id :actions/navigate})))))) 
