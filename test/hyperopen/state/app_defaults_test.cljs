(ns hyperopen.state.app-defaults-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.i18n.locale :as i18n-locale]
            [hyperopen.platform :as platform]
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
    (is (string? (get-in state [:ui :locale])))
    (is (= "/trade" (get-in state [:router :path])))
    (is (= :bootstrap (get-in state [:asset-selector :phase])))
    (is (= :orderbook (get-in state [:orderbook-ui :active-tab])))
    (is (= :all (get-in state [:portfolio-ui :summary-scope])))
    (is (= :month (get-in state [:portfolio-ui :summary-time-range])))
    (is (= :returns (get-in state [:portfolio-ui :chart-tab])))
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
    (is (= :performance-metrics (get-in state [:vaults-ui :detail-activity-tab])))
    (is (= {} (get-in state [:vaults-ui :detail-activity-sort-by-tab])))
    (is (= :all (get-in state [:vaults-ui :detail-activity-direction-filter])))
    (is (= false (get-in state [:vaults-ui :detail-activity-filter-open?])))
    (is (= :returns (get-in state [:vaults-ui :detail-chart-series])))
    (is (= ["BTC"] (get-in state [:vaults-ui :detail-returns-benchmark-coins])))
    (is (= "BTC" (get-in state [:vaults-ui :detail-returns-benchmark-coin])))
    (is (= "" (get-in state [:vaults-ui :detail-returns-benchmark-search])))
    (is (= false (get-in state [:vaults-ui :detail-returns-benchmark-suggestions-open?])))
    (is (= false (get-in state [:vaults-ui :vault-transfer-modal :open?])))
    (is (= :deposit (get-in state [:vaults-ui :vault-transfer-modal :mode])))
    (is (= "" (get-in state [:vaults-ui :vault-transfer-modal :amount-input])))
    (is (= "" (get-in state [:funding-comparison-ui :query])))
    (is (= :8hour (get-in state [:funding-comparison-ui :timeframe])))
    (is (= :open-interest (get-in state [:funding-comparison-ui :sort :column])))
    (is (= :desc (get-in state [:funding-comparison-ui :sort :direction])))
    (is (= false (get-in state [:funding-comparison-ui :loading?])))
    (is (= [] (get-in state [:funding-comparison :predicted-fundings])))
    (is (nil? (get-in state [:funding-comparison :error])))
    (is (= false (get-in state [:account-context :spectate-mode :active?])))
    (is (nil? (get-in state [:account-context :spectate-mode :address])))
    (is (= false (get-in state [:account-context :spectate-ui :modal-open?])))
    (is (nil? (get-in state [:account-context :spectate-ui :anchor])))
    (is (= "" (get-in state [:account-context :spectate-ui :search])))
    (is (= "" (get-in state [:account-context :spectate-ui :label])))
    (is (nil? (get-in state [:account-context :spectate-ui :editing-watchlist-address])))
    (is (= "" (get-in state [:account-context :spectate-ui :last-search])))
    (is (nil? (get-in state [:account-context :spectate-ui :search-error])))
    (is (= [] (get-in state [:account-context :watchlist])))
    (is (= false (get-in state [:account-context :watchlist-loaded?])))
    (is (= true (get-in state [:chart-options :volume-visible?])))
    (is (= false (get-in state [:positions-ui :tpsl-modal :open?])))
    (is (= false (get-in state [:positions-ui :reduce-popover :open?])))
    (is (= false (get-in state [:positions-ui :margin-modal :open?])))
    (is (= false (get-in state [:funding-ui :modal :open?])))
    (is (nil? (get-in state [:funding-ui :modal :mode])))
    (is (= {:direction nil
            :asset-key nil
            :operation-id nil
            :state nil
            :status nil
            :source-tx-confirmations nil
            :destination-tx-confirmations nil
            :position-in-withdraw-queue nil
            :destination-tx-hash nil
            :state-next-at nil
            :last-updated-ms nil
            :error nil}
           (get-in state [:funding-ui :modal :hyperunit-lifecycle])))
    (is (= {:status :idle
            :by-chain {}
            :requested-at-ms nil
            :updated-at-ms nil
            :error nil}
           (get-in state [:funding-ui :modal :hyperunit-fee-estimate])))
    (is (= {:status :idle
            :by-chain {}
            :requested-at-ms nil
            :updated-at-ms nil
            :error nil}
           (get-in state [:funding-ui :modal :hyperunit-withdrawal-queue])))))

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
    (is (= {} (get-in state [:active-assets :funding-predictability :by-coin])))
    (is (= {} (get-in state [:active-assets :funding-predictability :loading-by-coin])))
    (is (= {} (get-in state [:active-assets :funding-predictability :error-by-coin])))
    (is (= {} (get-in state [:active-assets :funding-predictability :loaded-at-ms-by-coin])))
    (is (= [] (get-in state [:orders :open-orders])))
    (is (= #{} (get-in state [:asset-selector :favorites])))
    (is (= {} (get-in state [:asset-selector :market-index-by-key])))
    (is (= #{} (get-in state [:asset-selector :loaded-icons])))
    (is (= #{} (get-in state [:asset-selector :missing-icons])))
    (is (= [] (get-in state [:account-context :watchlist])))
    (is (= {} (get-in state [:funding-ui :hypothetical-position-by-coin])))
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
    (is (= [] (get-in state [:funding-comparison :predicted-fundings])))
    (is (= {} (get-in state [:perp-dex-fee-config-by-name])))
    (is (nil? (get-in state [:portfolio :user-fees])))))

(deftest default-app-state-loads-persisted-summary-and-vault-ranges-when-present-test
  (let [state (with-redefs [platform/local-storage-get (fn [key]
                                                         (case key
                                                           "portfolio-summary-time-range" "2y"
                                                           "vaults-snapshot-range" "one-year"
                                                           nil))]
                (app-defaults/default-app-state
                 {:websocket-health {}
                  :default-agent-state {}
                  :default-order-form {}
                  :default-order-form-ui {}
                  :default-order-form-runtime {}
                  :default-trade-history {}
                  :default-funding-history {}
                  :default-order-history {}}))]
    (is (= :two-year (get-in state [:portfolio-ui :summary-time-range])))
    (is (= :one-year (get-in state [:vaults-ui :snapshot-range])))))

(deftest default-app-state-loads-persisted-ui-locale-when-valid-test
  (let [state (with-redefs [platform/local-storage-get (fn [key]
                                                         (case key
                                                           "ui-locale" "en_US"
                                                           nil))]
                (app-defaults/default-app-state
                 {:websocket-health {}
                  :default-agent-state {}
                  :default-order-form {}
                  :default-order-form-ui {}
                  :default-order-form-runtime {}
                  :default-trade-history {}
                  :default-funding-history {}
                  :default-order-history {}}))]
    (is (= "en-US" (get-in state [:ui :locale])))))

(deftest default-app-state-falls-back-to-browser-ui-locale-when-invalid-test
  (let [state (with-redefs [platform/local-storage-get (fn [key]
                                                         (case key
                                                           "ui-locale" "not-a-locale"
                                                           nil))
                            i18n-locale/resolve-browser-locale (fn [] "de-DE")]
                (app-defaults/default-app-state
                 {:websocket-health {}
                  :default-agent-state {}
                  :default-order-form {}
                  :default-order-form-ui {}
                  :default-order-form-runtime {}
                  :default-trade-history {}
                  :default-funding-history {}
                  :default-order-history {}}))]
    (is (= "de-DE" (get-in state [:ui :locale])))))

(deftest default-app-state-falls-back-to-default-ui-locale-when-browser-unavailable-test
  (let [state (with-redefs [platform/local-storage-get (fn [key]
                                                         (case key
                                                           "ui-locale" "not-a-locale"
                                                           nil))
                            i18n-locale/resolve-browser-locale (fn [] nil)]
                (app-defaults/default-app-state
                 {:websocket-health {}
                  :default-agent-state {}
                  :default-order-form {}
                  :default-order-form-ui {}
                  :default-order-form-runtime {}
                  :default-trade-history {}
                  :default-funding-history {}
                  :default-order-history {}}))]
    (is (= "en-US" (get-in state [:ui :locale])))))
