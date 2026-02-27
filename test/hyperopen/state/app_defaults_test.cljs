(ns hyperopen.state.app-defaults-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.app-defaults :as app-defaults]))

(deftest default-app-state-preserves-injected-defaults-and-core-shape-test
  (let [websocket-health {:transport {:state :connected}}
        default-agent-state {:status :ready :address "0xabc"}
        default-order-form {:side :buy}
        default-order-form-ui {:price-input-focused? false}
        default-order-form-runtime {:submitting? false :error nil}
        default-trade-history {:rows [1 2]}
        default-funding-history {:rows [3]}
        default-order-history {:rows [4 5]}
        state (app-defaults/default-app-state
               {:websocket-health websocket-health
                :default-agent-state default-agent-state
                :default-order-form default-order-form
                :default-order-form-ui default-order-form-ui
                :default-order-form-runtime default-order-form-runtime
                :default-trade-history default-trade-history
                :default-funding-history default-funding-history
                :default-order-history default-order-history})]
    (is (= websocket-health (get-in state [:websocket :health])))
    (is (= default-agent-state (get-in state [:wallet :agent])))
    (is (= default-order-form (get state :order-form)))
    (is (= default-order-form-ui (get state :order-form-ui)))
    (is (= default-order-form-runtime (get state :order-form-runtime)))
    (is (= default-trade-history (get-in state [:account-info :trade-history])))
    (is (= default-funding-history (get-in state [:account-info :funding-history])))
    (is (= default-order-history (get-in state [:account-info :order-history])))
    (is (= "/trade" (get-in state [:router :path])))
    (is (= :bootstrap (get-in state [:asset-selector :phase])))
    (is (= :orderbook (get-in state [:orderbook-ui :active-tab])))
    (is (= :all (get-in state [:portfolio-ui :summary-scope])))
    (is (= :month (get-in state [:portfolio-ui :summary-time-range])))
    (is (= :account-value (get-in state [:portfolio-ui :chart-tab])))
    (is (= :performance-metrics (get-in state [:portfolio-ui :account-info-tab])))
    (is (nil? (get-in state [:portfolio-ui :chart-hover-index])))
    (is (= ["BTC"] (get-in state [:portfolio-ui :returns-benchmark-coins])))
    (is (= "BTC" (get-in state [:portfolio-ui :returns-benchmark-coin])))
    (is (= "" (get-in state [:portfolio-ui :returns-benchmark-search])))
    (is (= false (get-in state [:portfolio-ui :returns-benchmark-suggestions-open?])))
    (is (= false (get-in state [:portfolio-ui :performance-metrics-time-range-dropdown-open?])))
    (is (= "" (get-in state [:vaults-ui :search-query])))
    (is (= true (get-in state [:vaults-ui :filter-leading?])))
    (is (= true (get-in state [:vaults-ui :filter-deposited?])))
    (is (= true (get-in state [:vaults-ui :filter-others?])))
    (is (= false (get-in state [:vaults-ui :filter-closed?])))
    (is (= :month (get-in state [:vaults-ui :snapshot-range])))
    (is (= :tvl (get-in state [:vaults-ui :sort :column])))
    (is (= :desc (get-in state [:vaults-ui :sort :direction])))
    (is (= 10 (get-in state [:vaults-ui :user-vaults-page-size])))
    (is (= 1 (get-in state [:vaults-ui :user-vaults-page])))
    (is (= false (get-in state [:vaults-ui :user-vaults-page-size-dropdown-open?])))
    (is (= :about (get-in state [:vaults-ui :detail-tab])))
    (is (= :positions (get-in state [:vaults-ui :detail-activity-tab])))
    (is (= :pnl (get-in state [:vaults-ui :detail-chart-series])))
    (is (= true (get-in state [:chart-options :volume-visible?])))
    (is (= false (get-in state [:positions-ui :tpsl-modal :open?])))
    (is (= false (get-in state [:positions-ui :reduce-popover :open?])))))

(deftest default-app-state-initializes-empty-runtime-collections-test
  (let [state (app-defaults/default-app-state
               {:websocket-health {}
                :default-agent-state {}
                :default-order-form {}
                :default-order-form-ui {}
                :default-order-form-runtime {}
                :default-trade-history {}
                :default-funding-history {}
                :default-order-history {}})]
    (is (= {} (get-in state [:active-assets :contexts])))
    (is (= [] (get-in state [:orders :open-orders])))
    (is (= #{} (get-in state [:asset-selector :favorites])))
    (is (= #{} (get-in state [:asset-selector :loaded-icons])))
    (is (= #{} (get-in state [:asset-selector :missing-icons])))
    (is (= {} (get-in state [:portfolio :summary-by-key])))
    (is (= [] (get-in state [:vaults :index-rows])))
    (is (= [] (get-in state [:vaults :recent-summaries])))
    (is (= [] (get-in state [:vaults :merged-index-rows])))
    (is (= [] (get-in state [:vaults :user-equities])))
    (is (= {} (get-in state [:vaults :user-equity-by-address])))
    (is (= {} (get-in state [:vaults :details-by-address])))
    (is (= {} (get-in state [:vaults :webdata-by-vault])))
    (is (= {} (get-in state [:vaults :fills-by-vault])))
    (is (= {} (get-in state [:vaults :funding-history-by-vault])))
    (is (= {} (get-in state [:vaults :order-history-by-vault])))
    (is (= {} (get-in state [:vaults :ledger-updates-by-vault])))
    (is (= {} (get-in state [:perp-dex-fee-config-by-name])))
    (is (nil? (get-in state [:portfolio :user-fees])))))
