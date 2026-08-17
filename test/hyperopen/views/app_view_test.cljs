(ns hyperopen.views.app-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.surface-modules :as surface-modules]
            [hyperopen.state.trading :as trading]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.app-view :as app-view]))

(defn- base-state
  []
  {:wallet {}
   :trade-ui {:mobile-surface :chart
              :mobile-asset-details-open? false}
   :account-context (account-context/default-account-context-state)
   :active-asset nil
   :active-market nil
   :orderbooks {}
   :webdata2 {}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :fills []
            :fundings []
            :order-history []
            :ledger []}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}
   :order-form (trading/default-order-form)
   :asset-selector {:visible-dropdown nil
                    :search-term ""
                    :sort-by :volume
                    :sort-direction :desc
                    :markets []
                    :market-by-key {}
                    :loading? false
                    :phase :bootstrap
                    :favorites #{}
                    :missing-icons #{}
                    :favorites-only? false
                    :strict? false
                    :active-tab :all}
   :chart-options {:selected-timeframe :1d
                   :selected-chart-type :candlestick}
   :orderbook-ui {:size-unit :base
                  :size-unit-dropdown-visible? false
                  :price-aggregation-dropdown-visible? false
                  :price-aggregation-by-coin {}
                  :active-tab :orderbook}
   :account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-sort {:column nil :direction :asc}
                  :positions-sort {:column nil :direction :asc}
                  :open-orders-sort {:column "Time" :direction :desc}}
   :vaults {:loading {:index? false
                      :summaries? false}
            :errors {:index nil
                     :summaries nil
                     :details-by-address {}
                     :webdata-by-vault {}}
            :user-equity-by-address {}
            :merged-index-rows []
            :startup-preview nil}
   :vaults-ui {:search-query ""
               :filter-leading? true
               :filter-deposited? true
               :filter-others? true
               :filter-closed? false
               :snapshot-range :month
               :sort {:column :tvl
                      :direction :desc}
               :detail-tab :about
               :detail-loading? false}
   :api-wallets-ui {:form {:name ""
                           :address ""
                           :days-valid ""}
                    :form-error nil
                    :sort {:column :name
                           :direction :asc}
                    :modal {:open? false
                            :type nil
                            :row nil
                            :error nil
                            :submitting? false}
                    :generated {:address nil
                                :private-key nil}}
   :api-wallets {:extra-agents []
                 :default-agent-row nil
                 :owner-webdata2 nil
                 :server-time-ms nil
                 :loading {:extra-agents? false
                           :default-agent? false}
                 :errors {:extra-agents nil
                          :default-agent nil}
                 :loaded-at-ms {:extra-agents nil
                                :default-agent nil}}
   :funding-comparison-ui {:query ""
                           :timeframe :8hour
                           :sort {:column :coin
                                  :direction :asc}
                           :loading? false}
   :funding-comparison {:predicted-fundings []
                        :error nil
                        :loaded-at-ms nil}})

(deftest app-view-root-hides-scrollbar-with-trade-xl-scroll-lock-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/trade"}
                                            :wallet {}))
        root-classes (hiccup/root-class-set view-node)]
    (is (contains? root-classes "overflow-y-auto"))
    (is (contains? root-classes "scrollbar-hide"))
    (is (contains? root-classes "xl:overflow-y-hidden"))))

(deftest app-view-root-keeps-non-trade-scroll-policy-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/vaults"}
                                            :wallet {}))
        root-classes (hiccup/root-class-set view-node)]
    (is (contains? root-classes "overflow-y-auto"))
    (is (contains? root-classes "scrollbar-hide"))
    (is (not (contains? root-classes "xl:overflow-y-hidden")))))

(deftest app-view-uses-compact-footer-reserve-for-trade-account-surface-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :trade-ui {:mobile-surface :account}))
        app-main (hiccup/find-by-parity-id view-node "app-main")
        app-main-classes (hiccup/node-class-set app-main)]
    (is (contains? app-main-classes "pb-[calc(3rem+env(safe-area-inset-bottom))]"))
    (is (not (contains? app-main-classes "pb-[5rem]")))))

(deftest app-view-keeps-standard-footer-reserve-for-trade-market-surface-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :trade-ui {:mobile-surface :chart}))
        app-main (hiccup/find-by-parity-id view-node "app-main")
        app-main-classes (hiccup/node-class-set app-main)]
    (is (contains? app-main-classes "pb-[5rem]"))
    (is (not (contains? app-main-classes "pb-[calc(3rem+env(safe-area-inset-bottom))]")))))

(deftest app-view-renders-portfolio-route-with-portfolio-root-test
  (let [view-node (with-redefs [route-modules/route-ready? (constantly true)
                                route-modules/render-route-view #(when (= "/portfolio" %2) [:div {:data-parity-id "portfolio-root"}])]
                    (app-view/app-view (assoc (base-state) :router {:path "/portfolio"} :wallet {})))
        portfolio-root (hiccup/find-by-parity-id view-node "portfolio-root") trade-root (hiccup/find-by-parity-id view-node "trade-root")]
    (is (some? portfolio-root))
    (is (nil? trade-root))))

(deftest app-view-renders-funding-comparison-route-with-funding-root-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/funding-comparison"}
                                            :wallet {}
                                            :funding-comparison-ui {:query ""
                                                                    :timeframe :8hour
                                                                    :sort {:column :coin
                                                                           :direction :asc}
                                                                    :loading? false}
                                            :funding-comparison {:predicted-fundings []
                                                                 :error nil
                                                                 :loaded-at-ms nil}
                                            :asset-selector {:favorites #{}
                                                             :market-by-key {}}))
        funding-root (hiccup/find-by-parity-id view-node "funding-comparison-root")]
    (is (some? funding-root))))

(deftest app-view-renders-referrals-route-with-referrals-root-test
  (let [view-node (with-redefs [route-modules/route-ready? (constantly true)
                                route-modules/render-route-view
                                (fn [_state route]
                                  (when (= "/referrals" route)
                                    [:div {:data-parity-id "referrals-root"}]))]
                    (app-view/app-view (assoc (base-state)
                                              :router {:path "/referrals"}
                                              :wallet {})))
        referrals-root (hiccup/find-by-parity-id view-node "referrals-root")
        trade-root (hiccup/find-by-parity-id view-node "trade-root")]
    (is (some? referrals-root))
    (is (nil? trade-root))))

(deftest app-view-renders-leaderboard-route-with-leaderboard-root-test
  (let [view-node (with-redefs [route-modules/route-ready? (constantly true)
                                route-modules/render-route-view
                                (fn [_state route]
                                  (when (= "/leaderboard" route)
                                    [:div {:data-parity-id "leaderboard-root"}]))]
                    (app-view/app-view (assoc (base-state)
                                              :router {:path "/leaderboard"}
                                              :wallet {})))
        leaderboard-root (hiccup/find-by-parity-id view-node "leaderboard-root")
        trade-root (hiccup/find-by-parity-id view-node "trade-root")]
    (is (some? leaderboard-root))
    (is (nil? trade-root))))

(deftest app-view-renders-api-wallet-route-with-api-wallet-root-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/API"}
                                            :wallet {}
                                            :api-wallets-ui {:form {:name ""
                                                                    :address ""
                                                                    :days-valid ""}
                                                             :form-error nil
                                                             :sort {:column :name
                                                                    :direction :asc}
                                                             :modal {:open? false
                                                                     :type nil
                                                                     :row nil
                                                                     :error nil
                                                                     :submitting? false}
                                                             :generated {:address nil
                                                                         :private-key nil}}
                                            :api-wallets {:extra-agents []
                                                          :default-agent-row nil
                                                          :owner-webdata2 nil
                                                          :server-time-ms nil
                                                          :loading {:extra-agents? false
                                                                    :default-agent? false}
                                                          :errors {:extra-agents nil
                                                                   :default-agent nil}
                                                          :loaded-at-ms {:extra-agents nil
                                                                         :default-agent nil}}))
        api-root (hiccup/find-by-parity-id view-node "api-wallets-root")]
    (is (some? api-root))))

(deftest app-view-renders-vault-routes-with-vault-roots-test
  (let [list-view (with-redefs [route-modules/route-ready? (constantly true)
                                route-modules/render-route-view
                                (fn [_state route]
                                  (cond
                                    (= "/vaults" route)
                                    [:div {:data-parity-id "vaults-root"}]

                                    (= "/vaults/0x1234567890abcdef1234567890abcdef12345678" route)
                                    [:div {:data-parity-id "vault-detail-root"}]

                                    :else nil))]
                    (app-view/app-view (assoc (base-state)
                                              :router {:path "/vaults"}
                                              :wallet {})))
        detail-view (with-redefs [route-modules/route-ready? (constantly true)
                                  route-modules/render-route-view
                                  (fn [_state route]
                                    (cond
                                      (= "/vaults" route)
                                      [:div {:data-parity-id "vaults-root"}]

                                      (= "/vaults/0x1234567890abcdef1234567890abcdef12345678" route)
                                      [:div {:data-parity-id "vault-detail-root"}]

                                      :else nil))]
                      (app-view/app-view (assoc (base-state)
                                                :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}
                                                :wallet {})))
        list-root (hiccup/find-by-parity-id list-view "vaults-root")
        detail-root (hiccup/find-by-parity-id detail-view "vault-detail-root")]
    (is (some? list-root))
    (is (some? detail-root))))

(deftest app-view-renders-preview-backed-vault-route-shell-for-unresolved-vault-list-route-test
  (let [view-node (with-redefs [route-modules/route-ready? (constantly false)
                                route-modules/render-route-view (constantly nil)
                                route-modules/route-error (constantly nil)]
                   (app-view/app-view (assoc (base-state)
                                             :router {:path "/vaults"}
                                             :wallet {}
                                             :vaults {:startup-preview {:saved-at-ms 1711022400000
                                                                        :snapshot-range :month
                                                                        :wallet-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                                                        :total-visible-tvl 1234567.89
                                                                        :stale? true
                                                                        :protocol-rows [{:name "Alpha Vault"
                                                                                         :vault-address "0x1111111111111111111111111111111111111111"
                                                                                         :leader "0x2222222222222222222222222222222222222222"
                                                                                         :apr 12.34
                                                                                         :tvl 1000000
                                                                                         :your-deposit 2500
                                                                                         :age-days 3}
                                                                                        {:name "Beta Vault"
                                                                                         :vault-address "0x3333333333333333333333333333333333333333"
                                                                                         :leader "0x4444444444444444444444444444444444444444"
                                                                                         :apr 8.76
                                                                                         :tvl 345678.9
                                                                                         :your-deposit 100
                                                                                         :age-days 11}]
                                                                        :user-rows [{:name "Gamma Vault"
                                                                                     :vault-address "0x5555555555555555555555555555555555555555"
                                                                                     :leader "0x6666666666666666666666666666666666666666"
                                                                                     :apr 6.54
                                                                                     :tvl 42000
                                                                                     :your-deposit 900
                                                                                     :age-days 21}]}})))
        route-shell (hiccup/find-by-data-role view-node "vaults-route-loading-shell")
        refreshing-banner (hiccup/find-by-data-role view-node "vaults-refreshing-banner")
        loading-row (hiccup/find-by-data-role view-node "vault-loading-row")
        rendered-strings (set (hiccup/collect-strings route-shell))]
    (is (some? route-shell))
    (is (= "startup-preview" (get-in route-shell [1 :data-preview-state])))
    (is (some? refreshing-banner))
    (is (nil? loading-row))
    (is (contains? rendered-strings "Refreshing vaults…"))
    (is (contains? rendered-strings "Alpha Vault"))
    (is (contains? rendered-strings "Beta Vault"))
    (is (contains? rendered-strings "Gamma Vault"))
    (is (not (contains? rendered-strings "Loading Route")))
    (is (not (contains? rendered-strings "Route Load Failed")))))

(deftest app-view-renders-cold-vault-route-shell-for-unresolved-vault-list-route-test
  (let [view-node (with-redefs [route-modules/route-ready? (constantly false)
                                route-modules/render-route-view (constantly nil)
                                route-modules/route-error (constantly nil)]
                   (app-view/app-view (assoc (base-state)
                                             :router {:path "/vaults"}
                                             :wallet {})))
        route-shell (hiccup/find-by-data-role view-node "vaults-route-loading-shell")
        refreshing-banner (hiccup/find-by-data-role view-node "vaults-refreshing-banner")
        loading-row (hiccup/find-by-data-role view-node "vault-loading-row")
        rendered-strings (set (hiccup/collect-strings route-shell))]
    (is (some? route-shell))
    (is (nil? (get-in route-shell [1 :data-preview-state])))
    (is (nil? refreshing-banner))
    (is (some? loading-row))
    (is (contains? rendered-strings "Vaults"))
    (is (contains? rendered-strings "Loading vaults…"))
    (is (not (contains? rendered-strings "Loading Route")))
    (is (not (contains? rendered-strings "Route Load Failed")))))

(deftest app-view-keeps-generic-route-loader-for-route-errors-and-other-routes-test
  (let [route-error-view (with-redefs [route-modules/route-ready? (constantly false)
                                      route-modules/render-route-view (constantly nil)
                                      route-modules/route-error (constantly "Route module failed to load.")]
                         (app-view/app-view (assoc (base-state)
                                                   :router {:path "/vaults"}
                                                   :wallet {}
                                                   :vaults {:startup-preview {:protocol-rows [{:name "Alpha Vault"
                                                                                               :vault-address "0x1111111111111111111111111111111111111111"
                                                                                               :leader "0x2222222222222222222222222222222222222222"
                                                                                               :apr 12.34
                                                                                               :tvl 1000000
                                                                                               :your-deposit 2500
                                                                                               :age-days 3}]}})))
        other-route-view (with-redefs [route-modules/route-ready? (constantly false)
                                       route-modules/render-route-view (constantly nil)
                                       route-modules/route-error (constantly nil)]
                          (app-view/app-view (assoc (base-state)
                                                    :router {:path "/portfolio"}
                                                    :wallet {}
                                                    :vaults {:startup-preview {:protocol-rows [{:name "Alpha Vault"
                                                                                                :vault-address "0x1111111111111111111111111111111111111111"
                                                                                                :leader "0x2222222222222222222222222222222222222222"
                                                                                                :apr 12.34
                                                                                                :tvl 1000000
                                                                                                :your-deposit 2500
                                                                                                :age-days 3}]}})))
        route-error-shell (hiccup/find-by-parity-id route-error-view "app-route-module-shell")
        other-route-shell (hiccup/find-by-parity-id other-route-view "app-route-module-shell")
        route-error-strings (set (hiccup/collect-strings route-error-shell))
        other-route-strings (set (hiccup/collect-strings other-route-shell))]
    (is (some? route-error-shell))
    (is (some? other-route-shell))
    (is (contains? route-error-strings "Route Load Failed"))
    (is (contains? route-error-strings "Retry"))
    (is (contains? other-route-strings "Loading Route"))
    (is (not (contains? other-route-strings "Refreshing vaults…")))
    (is (not (contains? other-route-strings "Alpha Vault")))))

(deftest app-view-renders-global-order-feedback-toast-when-present-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :ui {:toast {:kind :success
                                                         :message "Order submitted."}}))
        toast-node (hiccup/find-by-data-role view-node "global-toast")]
    (is (some? toast-node))
    (is (contains? (set (hiccup/collect-strings toast-node))
                   "Order submitted."))))

(deftest app-view-renders-stacked-order-feedback-toasts-and-dismiss-actions-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :ui {:toast {:kind :success
                                                         :message "Sold 1.25 SOL"}
                                                 :toasts [{:id "toast-1"
                                                           :kind :success
                                                           :headline "Bought 6 HYPE"
                                                           :subline "At average price of $31.66667"
                                                           :message "Bought 6 HYPE"}
                                                          {:id "toast-2"
                                                           :kind :success
                                                           :headline "Sold 1.25 SOL"
                                                           :subline "At average price of $90.79"
                                                           :message "Sold 1.25 SOL"}]}))
        toast-nodes (hiccup/find-all-nodes view-node #(= "global-toast" (get-in % [1 :data-role])))
        dismiss-nodes (hiccup/find-all-nodes view-node #(= "global-toast-dismiss" (get-in % [1 :data-role])))
        toast-class-sets (mapv hiccup/node-class-set toast-nodes)
        rendered-strings (set (hiccup/collect-strings view-node))]
    (is (= 2 (count toast-nodes)))
    (is (= 2 (count dismiss-nodes)))
    (is (every? #(contains? % "global-toast-surface") toast-class-sets))
    (is (= ["success" "success"]
           (mapv #(get-in % [1 :data-toast-kind]) toast-nodes)))
    (is (contains? rendered-strings "Bought 6 HYPE"))
    (is (contains? rendered-strings "Sold 1.25 SOL"))
    (is (contains? rendered-strings "At average price of $31.66667"))
    (is (contains? rendered-strings "At average price of $90.79"))
    (is (= [[:actions/dismiss-order-feedback-toast "toast-1"]]
           (get-in (first dismiss-nodes) [1 :on :click])))
    (is (= [[:actions/dismiss-order-feedback-toast "toast-2"]]
           (get-in (second dismiss-nodes) [1 :on :click])))))

(deftest app-view-renders-spectate-mode-banner-when-active-test
  (let [address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :account-context {:spectate-mode {:active? true
                                                                              :address address}
                                                              :spectate-ui {:modal-open? false}
                                                              :watchlist []}))
        banner-node (hiccup/find-by-data-role view-node "spectate-mode-active-banner")
        address-node (hiccup/find-by-data-role view-node "spectate-mode-active-banner-address")
        label-node (hiccup/find-by-data-role view-node "spectate-mode-active-banner-label")
        manage-button (hiccup/find-by-data-role view-node "spectate-mode-banner-manage")
        stop-button (hiccup/find-by-data-role view-node "spectate-mode-banner-stop")]
    (is (some? banner-node))
    (is (contains? (set (hiccup/collect-strings banner-node)) "Spectate Mode"))
    (is (contains? (set (hiccup/collect-strings banner-node)) "Currently spectating"))
    (is (nil? label-node))
    (is (= ["0xabcd…abcd"] (hiccup/collect-strings address-node)))
    (is (= [[:actions/open-spectate-mode-modal :event.currentTarget/bounds]]
           (get-in manage-button [1 :on :click])))
    (is (= [[:actions/stop-spectate-mode]]
           (get-in stop-button [1 :on :click])))))

(deftest app-view-spectate-banner-prefers-watchlist-label-over-truncated-address-test
  (let [address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :account-context {:spectate-mode {:active? true
                                                                              :address address}
                                                              :spectate-ui {:modal-open? false}
                                                              :watchlist [{:address address
                                                                           :label "hyperliquidated"}]}))
        label-node (hiccup/find-by-data-role view-node "spectate-mode-active-banner-label")
        address-node (hiccup/find-by-data-role view-node "spectate-mode-active-banner-address")]
    (is (= ["hyperliquidated"] (hiccup/collect-strings label-node)))
    (is (= address (get-in label-node [1 :title])))
    (is (not (contains? (set (get-in label-node [1 :class])) "num")))
    (is (= ["0xabcd…abcd"] (hiccup/collect-strings address-node)))
    (is (contains? (set (get-in address-node [1 :class])) "num"))))

(deftest app-view-hides-spectate-banner-on-trader-portfolio-route-test
  (let [address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        trader "0x3333333333333333333333333333333333333333"
        view-node (with-redefs [route-modules/route-ready? (constantly true)
                                route-modules/render-route-view
                                (fn [_state route]
                                  (when (= (str "/portfolio/trader/" trader) route)
                                    [:div {:data-parity-id "portfolio-root"}]))]
                    (app-view/app-view (assoc (base-state)
                                              :router {:path (str "/portfolio/trader/" trader)}
                                              :wallet {}
                                              :account-context {:spectate-mode {:active? true
                                                                                :address address}
                                                                :spectate-ui {:modal-open? false}
                                                                :watchlist []})))
        banner-node (hiccup/find-by-data-role view-node "spectate-mode-active-banner")
        portfolio-root (hiccup/find-by-parity-id view-node "portfolio-root")]
    (is (nil? banner-node))
    (is (some? portfolio-root))))

(deftest app-view-renders-spectate-mode-modal-through-surface-module-loader-test
  (let [address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        label "The Assistance Fund"
        rendered-surface-ids (atom [])
        view-node (with-redefs [surface-modules/render-surface-view
                                (fn [state* surface-id]
                                  (swap! rendered-surface-ids conj surface-id)
                                  (when (= :spectate-mode-modal surface-id)
                                    (is (= address
                                           (get-in state* [:account-context :spectate-ui :search])))
                                    (is (= label
                                           (get-in state* [:account-context :watchlist 0 :label])))
                                    [:div {:data-role "spectate-mode-lazy-surface"}
                                     "Spectate modal loaded from surface module"]))]
                    (app-view/app-view (assoc (base-state)
                                              :router {:path "/trade"}
                                              :wallet {:copy-feedback {:kind :success
                                                                       :message "Spectate link copied to clipboard"}}
                                              :account-context {:spectate-mode {:active? true
                                                                                :address address}
                                                                :spectate-ui {:modal-open? true
                                                                              :search address
                                                                              :label ""
                                                                              :search-error nil}
                                                                :watchlist [{:address address
                                                                             :label label}]})))
        lazy-surface (hiccup/find-by-data-role view-node "spectate-mode-lazy-surface")
        direct-modal-root (hiccup/find-by-data-role view-node "spectate-mode-modal-root")]
    (is (= {:surface-requested? true
            :lazy-surface-rendered? true
            :direct-modal-rendered? false}
           {:surface-requested? (contains? (set @rendered-surface-ids)
                                           :spectate-mode-modal)
            :lazy-surface-rendered? (some? lazy-surface)
            :direct-modal-rendered? (some? direct-modal-root)}))))

(deftest app-view-renders-order-submit-confirmation-modal-when-open-test
  (let [view-node (app-view/app-view (assoc (base-state)
                                            :router {:path "/trade"}
                                            :wallet {}
                                            :order-submit-confirmation {:open? true
                                                                        :variant :open-order
                                                                        :message "Submit?"
                                                                        :request {:action {:type "order"}}
                                                                        :path-values []}))
        dialog (hiccup/find-by-data-role view-node "order-submit-confirmation-dialog")
        backdrop (hiccup/find-by-data-role view-node "order-submit-confirmation-backdrop")
        cancel-button (hiccup/find-by-data-role view-node "order-submit-confirmation-cancel")
        confirm-button (hiccup/find-by-data-role view-node "order-submit-confirmation-submit")
        close-button (hiccup/find-by-data-role view-node "order-submit-confirmation-close")
        rendered-strings (set (hiccup/collect-strings dialog))]
    (is (some? dialog))
    (is (contains? rendered-strings "Submit Order?"))
    (is (contains? rendered-strings "This order will be sent immediately to the exchange."))
    (is (contains? rendered-strings "Turn off open-order confirmation in Trading settings if you prefer one-click submits."))
    (is (= [[:actions/handle-order-submission-confirmation-keydown [:event/key]]]
           (get-in dialog [1 :on :keydown])))
    (is (= [[:actions/dismiss-order-submission-confirmation]]
           (get-in backdrop [1 :on :click])))
    (is (= [[:actions/dismiss-order-submission-confirmation]]
           (get-in cancel-button [1 :on :click])))
    (is (= [[:actions/dismiss-order-submission-confirmation]]
           (get-in close-button [1 :on :click])))
    (is (= [[:actions/confirm-order-submission]]
           (get-in confirm-button [1 :on :click])))))
