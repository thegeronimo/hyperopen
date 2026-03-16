(ns hyperopen.runtime.action-adapters-test
  (:require [cljs.test :refer-macros [deftest is]]
            [nexus.registry :as nxr]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.effect-order-contract :as effect-order-contract]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.vaults.actions :as vault-actions]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.connection-runtime :as wallet-connection-runtime]))

(deftest enable-agent-trading-injects-platform-now-ms-fn-test
  (let [captured-now-ms (atom nil)]
    (with-redefs [platform/now-ms (fn [] 4242)
                  agent-runtime/enable-agent-trading!
                  (fn [{:keys [now-ms-fn]}]
                    (reset! captured-now-ms (now-ms-fn))
                    nil)]
      (action-adapters/enable-agent-trading nil (atom {}) {}))
    (is (= 4242 @captured-now-ms))))

(deftest subscribe-to-asset-emits-funding-predictability-sync-test
  (is (= [[:effects/subscribe-active-asset "BTC"]
          [:effects/subscribe-orderbook "BTC"]
          [:effects/subscribe-trades "BTC"]
          [:effects/sync-active-asset-funding-predictability "BTC"]]
         (action-adapters/subscribe-to-asset {} "BTC"))))

(deftest navigate-appends-vault-route-effects-after-route-projection-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path]
                                                 [[:effects/save [:vaults-ui :list-loading?] true]
                                                  [:effects/api-fetch-vault-index]])]
    (is (= [[:effects/save [:router :path] "/vaults"]
            [:effects/save [:vaults-ui :list-loading?] true]
            [:effects/push-state "/vaults"]
            [:effects/load-route-module "/vaults"]
            [:effects/api-fetch-vault-index]]
           (action-adapters/navigate {} "/vaults")))
    (is (= [[:effects/save [:router :path] "/vaults"]
            [:effects/save [:vaults-ui :list-loading?] true]
            [:effects/replace-state "/vaults"]
            [:effects/load-route-module "/vaults"]
            [:effects/api-fetch-vault-index]]
           (action-adapters/navigate {} "/vaults" {:replace? true})))))

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
           (action-adapters/navigate {} "/funding-comparison")))))

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
           (action-adapters/navigate {} "/staking")))))

(deftest navigate-trade-route-loads-deferred-chart-module-test
  (with-redefs [vault-actions/load-vault-route (fn [_state _path] [])
                funding-comparison-actions/load-funding-comparison-route (fn [_state _path] [])
                api-wallets-actions/load-api-wallet-route (fn [_state _path] [])
                staking-actions/load-staking-route (fn [_state _path] [])
                trade-modules/trade-chart-ready? (constantly false)
                trade-modules/trade-chart-loading? (constantly false)]
    (is (= [[:effects/save [:router :path] "/trade"]
            [:effects/push-state "/trade"]
            [:effects/load-trade-chart-module]]
           (action-adapters/navigate {} "/trade")))))

(deftest navigate-entering-portfolio-loads-chart-benchmark-effects-test
  (with-redefs [portfolio-actions/select-portfolio-chart-tab (fn [_state tab]
                                                                [[:effects/save-many
                                                                  [[[:portfolio-ui :chart-tab] tab]
                                                                   [[:portfolio-ui :chart-hover-index] nil]]]
                                                                 [:effects/fetch-candle-snapshot
                                                                  :coin "BTC"
                                                                  :interval :1h
                                                                  :bars 800]])
                vault-actions/load-vault-route (fn [_state _path]
                                                 [[:effects/save [:vaults-ui :list-loading?] true]
                                                  [:effects/api-fetch-vault-index]])]
    (is (= [[:effects/save [:router :path] "/portfolio"]
            [:effects/save-many
             [[[:portfolio-ui :chart-tab] :returns]
              [[:portfolio-ui :chart-hover-index] nil]]]
            [:effects/save [:vaults-ui :list-loading?] true]
            [:effects/push-state "/portfolio"]
            [:effects/load-route-module "/portfolio"]
            [:effects/fetch-candle-snapshot
             :coin "BTC"
             :interval :1h
             :bars 800]
            [:effects/api-fetch-vault-index]]
           (action-adapters/navigate {:router {:path "/trade"}
                                      :portfolio-ui {:chart-tab :returns}}
                                     "/portfolio")))))

(deftest navigate-inside-portfolio-does-not-rebootstrap-portfolio-chart-test
  (let [chart-bootstrap-calls (atom 0)]
    (with-redefs [portfolio-actions/select-portfolio-chart-tab (fn [_state _tab]
                                                                  (swap! chart-bootstrap-calls inc)
                                                                  [[:effects/save
                                                                    [:portfolio-ui :chart-hover-index]
                                                                    nil]])
                  vault-actions/load-vault-route (fn [_state _path]
                                                   [[:effects/save [:vaults-ui :list-loading?] true]])]
      (is (= [[:effects/save [:router :path] "/portfolio"]
              [:effects/save [:vaults-ui :list-loading?] true]
              [:effects/push-state "/portfolio"]
              [:effects/load-route-module "/portfolio"]]
             (action-adapters/navigate {:router {:path "/portfolio"}
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
           (action-adapters/navigate {:router {:path "/trade"}
                                      :account-context {:spectate-mode {:active? true
                                                                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}}}
                                     "/portfolio")))))

(deftest navigate-satisfies-effect-order-contract-for-deferred-routes-test
  (with-redefs [portfolio-actions/select-portfolio-chart-tab
                (fn [_state tab]
                  [[:effects/save-many
                    [[[:portfolio-ui :chart-tab] tab]
                     [[:portfolio-ui :chart-hover-index] nil]]]
                   [:effects/fetch-candle-snapshot
                    :coin "BTC"
                    :interval :1h
                    :bars 800]])
                vault-actions/load-vault-route
                (fn [_state _path]
                  [[:effects/save [:vaults-ui :list-loading?] true]
                   [:effects/api-fetch-vault-index]])]
    (let [effects (action-adapters/navigate {:router {:path "/trade"}
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
    (let [effects (action-adapters/navigate {:router {:path "/portfolio"}} "/trade")]
      (is (= effects
             (effect-order-contract/assert-action-effect-order!
              :actions/navigate
              effects
              {:phase :action-emission
               :action-id :actions/navigate}))))))

(deftest handle-wallet-connected-refreshes-vault-route-when-active-test
  (let [dispatch-calls (atom [])]
    (with-redefs [wallet-connection-runtime/handle-wallet-connected!
                  (fn [_]
                    :handled)
                  nxr/dispatch (fn [store _ctx effects]
                                 (swap! dispatch-calls conj [store effects]))]
      (let [store (atom {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}})
            result (action-adapters/handle-wallet-connected store "0xabc")]
        (is (= :handled result))
        (is (= [[store [[:actions/load-vault-route "/vaults/0x1234567890abcdef1234567890abcdef12345678"]]]]
               @dispatch-calls))))))

(deftest handle-wallet-connected-refreshes-staking-route-when-active-test
  (let [dispatch-calls (atom [])]
    (with-redefs [wallet-connection-runtime/handle-wallet-connected!
                  (fn [_]
                    :handled)
                  nxr/dispatch (fn [store _ctx effects]
                                 (swap! dispatch-calls conj [store effects]))]
      (let [store (atom {:router {:path "/staking"}})
            result (action-adapters/handle-wallet-connected store "0xabc")]
        (is (= :handled result))
        (is (= [[store [[:actions/load-staking-route "/staking"]]]]
               @dispatch-calls))))))
