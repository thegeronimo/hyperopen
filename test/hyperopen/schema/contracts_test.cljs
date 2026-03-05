(ns hyperopen.schema.contracts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.schema.order-form-contracts :as order-form-contracts]
            [hyperopen.state.trading.order-form-key-policy :as key-policy]
            [hyperopen.system :as system]))

(def ^:private valid-order-form-ui
  {:pro-order-type-dropdown-open? false
   :margin-mode-dropdown-open? false
   :leverage-popover-open? false
   :size-unit-dropdown-open? false
   :tpsl-unit-dropdown-open? false
   :tif-dropdown-open? false
   :price-input-focused? false
   :tpsl-panel-open? false
   :entry-mode :limit
   :ui-leverage 20
   :leverage-draft 20
   :margin-mode :cross
   :size-input-mode :quote
   :size-input-source :manual
   :size-display ""})

(deftest assert-app-state-rejects-active-market-without-symbol-test
  (let [state (assoc (system/default-store-state)
                     :active-market {:coin "BTC"})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-order-form-ui-with-non-boolean-flags-test
  (let [state (assoc (system/default-store-state)
                     :order-form-ui {:pro-order-type-dropdown-open? false
                                     :margin-mode-dropdown-open? false
                                     :leverage-popover-open? false
                                     :size-unit-dropdown-open? false
                                     :tpsl-unit-dropdown-open? false
                                     :tif-dropdown-open? false
                                     :price-input-focused? "yes"
                                     :tpsl-panel-open? false
                                     :entry-mode :limit
                                     :ui-leverage 20
                                     :leverage-draft 20
                                     :margin-mode :cross
                                     :size-input-mode :quote
                                     :size-input-source :manual
                                     :size-display ""})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-accepts-valid-order-form-ui-state-test
  (let [state (assoc (system/default-store-state)
                     :order-form-ui (assoc valid-order-form-ui
                                           :price-input-focused? true))]
    (is (= state (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-order-form-with-ui-owned-fields-test
  (let [state (assoc (system/default-store-state)
                     :order-form (assoc (:order-form (system/default-store-state))
                                        :entry-mode :pro))]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-order-form-with-policy-defined-deprecated-keys-test
  (let [base (system/default-store-state)]
    (doseq [key key-policy/deprecated-canonical-order-form-keys]
      (let [state (assoc base
                         :order-form (assoc (:order-form base) key true))]
        (is (thrown-with-msg?
             js/Error
             #"app state"
             (contracts/assert-app-state! state {:phase :test}))
            (str "expected app-state contract rejection for " key))))))

(deftest assert-app-state-rejects-order-form-runtime-with-invalid-shape-test
  (let [state (assoc (system/default-store-state)
                     :order-form-runtime {:submitting? "no"
                                          :error 42})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-funding-ui-when-hyperunit-lifecycle-shape-is-invalid-test
  (let [state (assoc-in (system/default-store-state)
                        [:funding-ui :modal :hyperunit-lifecycle]
                        {:direction :deposit
                         :asset-key :btc})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-signed-exchange-payload-requires-action-map-test
  (is (thrown-with-msg?
       js/Error
       #"exchange payload"
       (contracts/assert-signed-exchange-payload!
        {:action nil
         :nonce 42
         :signature {:r "0x1"
                     :s "0x2"
                     :v 27}}
        {:boundary :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-interval-only-test
  (is (= [:interval :1m]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:interval :1m]
          {:phase :test}))))

(deftest assert-effect-args-accepts-sync-active-candle-subscription-interval-test
  (is (= [:interval :1m]
         (contracts/assert-effect-args!
          :effects/sync-active-candle-subscription
          [:interval :1m]
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-interval-and-bars-test
  (is (= [:interval :1m :bars 330]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:interval :1m :bars 330]
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-coin-interval-and-bars-test
  (is (= [:coin "SPY" :interval :1h :bars 800]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:coin "SPY" :interval :1h :bars 800]
          {:phase :test}))))

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-odd-kv-arity-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:interval]
        {:phase :test}))))

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-unknown-key-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:foo 1]
        {:phase :test}))))

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-blank-coin-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:coin "   " :interval :1h]
        {:phase :test}))))

(deftest assert-effect-args-rejects-funding-history-request-id-when-not-non-negative-integer-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/api-fetch-user-funding-history
        ["abc"]
        {:phase :test}))))

(deftest assert-effect-args-rejects-order-history-request-id-when-negative-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/api-fetch-historical-orders
        [-1]
        {:phase :test}))))

(deftest assert-effect-args-rejects-export-funding-history-csv-when-not-vector-of-maps-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/export-funding-history-csv
        ["not-a-vector"]
        {:phase :test}))))

(deftest assert-effect-args-validates-funding-predictability-sync-coin-test
  (is (= ["BTC"]
         (contracts/assert-effect-args!
          :effects/sync-active-asset-funding-predictability
          ["BTC"]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/sync-active-asset-funding-predictability
        [""]
        {:phase :test}))))

(deftest assert-action-args-validates-hyperunit-lifecycle-actions-test
  (is (= [{:direction :deposit
           :asset-key :btc
           :operation-id "op_123"}]
         (contracts/assert-action-args!
          :actions/set-hyperunit-lifecycle
          [{:direction :deposit
            :asset-key :btc
            :operation-id "op_123"}]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/clear-hyperunit-lifecycle
          []
          {:phase :test})))
  (is (= ["temporary issue"]
         (contracts/assert-action-args!
          :actions/set-hyperunit-lifecycle-error
          ["temporary issue"]
          {:phase :test})))
  (is (= [nil]
         (contracts/assert-action-args!
          :actions/set-hyperunit-lifecycle-error
          [nil]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"action payload"
       (contracts/assert-action-args!
        :actions/set-hyperunit-lifecycle
        [{:direction :deposit
          :unsupported true}]
        {:phase :test}))))

(deftest assert-action-args-allows-asset-selector-scroll-prefetch-single-or-double-payload-test
  (is (= [5100]
         (contracts/assert-action-args!
          :actions/maybe-increase-asset-selector-render-limit
          [5100]
          {:phase :test})))
  (is (= [5100 1234.5]
         (contracts/assert-action-args!
          :actions/maybe-increase-asset-selector-render-limit
          [5100 1234.5]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"action payload"
       (contracts/assert-action-args!
        :actions/maybe-increase-asset-selector-render-limit
        [5100 1234.5 9999]
        {:phase :test}))))

(deftest assert-action-args-validates-portfolio-chart-tab-selection-test
  (is (= [:pnl]
         (contracts/assert-action-args!
          :actions/select-portfolio-chart-tab
          [:pnl]
          {:phase :test})))
  (is (= ["accountValue"]
         (contracts/assert-action-args!
          :actions/select-portfolio-chart-tab
          ["accountValue"]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"action payload"
       (contracts/assert-action-args!
        :actions/select-portfolio-chart-tab
        [[]]
        {:phase :test}))))

(deftest assert-action-args-validates-portfolio-chart-hover-actions-test
  (is (= [120 {:left 12 :width 360} 40]
         (contracts/assert-action-args!
          :actions/set-portfolio-chart-hover
          [120 {:left 12 :width 360} 40]
          {:phase :test})))
  (is (= [nil {:left 12 :width 360} 40]
         (contracts/assert-action-args!
          :actions/set-portfolio-chart-hover
          [nil {:left 12 :width 360} 40]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/clear-portfolio-chart-hover
          []
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"action payload"
       (contracts/assert-action-args!
        :actions/set-portfolio-chart-hover
        [120 {:left 12 :width "wide"} 40]
        {:phase :test}))))

(deftest assert-action-args-validates-portfolio-returns-benchmark-actions-test
  (is (= []
         (contracts/assert-action-args!
          :actions/select-portfolio-returns-benchmark
          []
          {:phase :test})))
  (is (= ["SPY"]
         (contracts/assert-action-args!
          :actions/select-portfolio-returns-benchmark
          ["SPY"]
          {:phase :test})))
  (is (= [""]
         (contracts/assert-action-args!
          :actions/select-portfolio-returns-benchmark
          [""]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/clear-portfolio-returns-benchmark
          []
          {:phase :test}))))

(deftest assert-action-args-allows-ghost-mode-actions-with-or-without-address-test
  (is (= []
         (contracts/assert-action-args!
          :actions/open-ghost-mode-modal
          []
          {:phase :test})))
  (is (= [{:left 32 :right 96 :top 18 :bottom 52}]
         (contracts/assert-action-args!
          :actions/open-ghost-mode-modal
          [{:left 32 :right 96 :top 18 :bottom 52}]
          {:phase :test})))
  (is (= [""]
         (contracts/assert-action-args!
          :actions/set-ghost-mode-search
          [""]
          {:phase :test})))
  (is (= [""]
         (contracts/assert-action-args!
          :actions/set-ghost-mode-label
          [""]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/start-ghost-mode
          []
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/start-ghost-mode
          ["0x123"]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/add-ghost-mode-watchlist-address
          []
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/add-ghost-mode-watchlist-address
          ["0x123"]
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/remove-ghost-mode-watchlist-address
          ["0x123"]
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/edit-ghost-mode-watchlist-address
          ["0x123"]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/clear-ghost-mode-watchlist-edit
          []
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/copy-ghost-mode-watchlist-address
          ["0x123"]
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/spectate-ghost-mode-watchlist-address
          ["0x123"]
          {:phase :test}))))

(deftest order-form-vm-schema-contracts-test
  (let [valid-vm {:form {:type :limit}
                  :side :buy
                  :type :limit
                  :entry-mode :limit
                  :pro-dropdown-open? false
                  :tpsl-panel-open? false
                  :pro-dropdown-options [:scale]
                  :pro-tab-label "Pro"
                  :spot? false
                  :hip3? false
                  :read-only? false
                  :display {:available-to-trade "0.00 USDC"
                            :current-position "0.0000 BTC"
                            :liquidation-price "N/A"
                            :order-value "N/A"
                            :margin-required "N/A"
                            :slippage "Est 0.0000% / Max 8.00%"
                            :fees {:effective "0.0450% / 0.0150%"
                                   :baseline nil}}
                  :ui-leverage 20
                  :next-leverage 25
                  :size-percent 0
                  :display-size-percent "0"
                  :notch-overlap-threshold 4
                  :size-input-mode :quote
                  :size-display ""
                  :price {:raw ""
                          :display ""
                          :focused? false
                          :fallback nil
                          :context {:label "Ref"
                                    :mid-available? false}}
                  :base-symbol "BTC"
                  :quote-symbol "USDC"
                  :scale-preview-lines {:start "N/A"
                                        :end "N/A"}
                  :error nil
                  :submitting? false
                  :controls {:limit-like? true
                             :show-limit-like-controls? true
                             :show-tpsl-toggle? true
                             :show-tpsl-panel? false
                             :show-post-only? false
                             :show-scale-preview? false
                             :show-liquidation-row? true
                             :show-slippage-row? false}
                  :submit {:form {}
                           :errors []
                           :required-fields []
                           :reason nil
                           :error-message nil
                           :tooltip nil
                           :market-price-missing? false
                           :disabled? false}}
        invalid-vm (assoc valid-vm :unknown true)]
    (is (true? (order-form-contracts/order-form-vm-valid? valid-vm)))
    (is (false? (order-form-contracts/order-form-vm-valid? invalid-vm)))
    (is (= valid-vm
           (order-form-contracts/assert-order-form-vm! valid-vm {:phase :test})))
    (is (thrown-with-msg?
         js/Error
         #"order-form VM schema validation failed"
         (order-form-contracts/assert-order-form-vm! invalid-vm {:phase :test})))))

(deftest order-form-transition-schema-contracts-test
  (let [valid-transition {:order-form {}
                          :order-form-runtime {:submitting? false
                                               :error nil}}
        invalid-transition {:order-form {}
                            :unknown true}]
    (is (true? (order-form-contracts/order-form-transition-valid? valid-transition)))
    (is (false? (order-form-contracts/order-form-transition-valid? invalid-transition)))
    (is (= valid-transition
           (order-form-contracts/assert-order-form-transition! valid-transition {:phase :test})))
    (is (thrown-with-msg?
         js/Error
         #"order-form transition schema validation failed"
         (order-form-contracts/assert-order-form-transition! invalid-transition {:phase :test})))))
