(ns hyperopen.views.trade.order-form-vm-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.account.context :as account-context]
            [hyperopen.schema.order-form-contracts :as contracts]
            [hyperopen.trading.order-type-registry :as order-type-registry]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.trade.order-form-component-sections :as sections]
            [hyperopen.views.trade.order-form-type-extensions :as type-extensions]
            [hyperopen.views.trade.order-form-vm :as vm]))

(def ^:private ui-owned-order-form-keys
  #{:entry-mode
    :ui-leverage
    :size-input-mode
    :size-input-source
    :size-display})

(defn- base-state
  ([] (base-state {} {}))
  ([order-form-overrides order-form-ui-overrides]
   (let [ui-overrides-from-form (select-keys order-form-overrides ui-owned-order-form-keys)
         normalized-order-form-overrides (reduce dissoc order-form-overrides ui-owned-order-form-keys)
         order-form (merge (trading/default-order-form) normalized-order-form-overrides)
         inferred-entry-mode (when (contains? normalized-order-form-overrides :type)
                               (trading/entry-mode-for-type (:type order-form)))
         final-entry-mode (or (:entry-mode order-form-ui-overrides)
                              (:entry-mode ui-overrides-from-form)
                              inferred-entry-mode)
         order-form-ui (cond-> (merge (trading/default-order-form-ui)
                                      ui-overrides-from-form
                                      order-form-ui-overrides)
                         final-entry-mode
                         (assoc :entry-mode final-entry-mode))]
     {:active-asset "BTC"
      :active-market {:coin "BTC"
                      :quote "USDC"
                      :mark 100
                      :maxLeverage 40
                      :market-type :perp
                      :szDecimals 4}
      :orderbooks {"BTC" {:bids [{:px "99"}]
                          :asks [{:px "101"}]}}
      :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                      :totalMarginUsed "250"}}}
      :order-form order-form
      :order-form-ui order-form-ui})))

(deftest order-form-vm-price-context-mid-vs-ref-test
  (testing "mid context is available when top-of-book midpoint exists"
    (let [state (base-state {:type :limit :price ""} {})
          view-model (vm/order-form-vm state)]
      (is (= "Mid" (get-in view-model [:price :context :label])))
      (is (true? (get-in view-model [:price :context :mid-available?])))))

  (testing "ref context is used when midpoint is unavailable"
    (let [state (assoc (base-state {:type :limit :price ""} {}) :orderbooks {})
          view-model (vm/order-form-vm state)]
      (is (= "Ref" (get-in view-model [:price :context :label])))
      (is (false? (get-in view-model [:price :context :mid-available?]))))))

(deftest order-form-vm-submit-tooltip-and-disable-reason-test
  (let [state (base-state {:type :limit :size "" :price ""} {})
        view-model (vm/order-form-vm state)]
    (is (true? (get-in view-model [:submit :disabled?])))
    (is (= :validation-errors (get-in view-model [:submit :reason])))
    (is (= "Fill required fields: Size."
           (get-in view-model [:submit :tooltip])))))

(deftest order-form-vm-shows-ghost-mode-submit-remediation-tooltip-test
  (let [state (assoc (base-state {:type :limit :size "1" :price "100"} {})
                     :asset-contexts {:BTC {:idx 0}}
                     :account-context {:ghost-mode {:active? true
                                                    :address "0x1234567890abcdef1234567890abcdef12345678"}})
        view-model (vm/order-form-vm state)]
    (is (true? (get-in view-model [:submit :disabled?])))
    (is (= :ghost-mode-read-only (get-in view-model [:submit :reason])))
    (is (= account-context/ghost-mode-read-only-message
           (get-in view-model [:submit :tooltip])))))

(deftest order-form-vm-uses-order-form-ui-flags-test
  (testing "price fallback display is suppressed while input is focused"
    (let [state (base-state {:type :limit :price ""}
                            {:price-input-focused? true})
          view-model (vm/order-form-vm state)]
      (is (true? (get-in view-model [:price :focused?])))
      (is (= "" (get-in view-model [:price :display])))))

  (testing "tpsl panel state is kept for non-scale order types"
    (let [state (base-state {:type :limit}
                            {:tpsl-panel-open? true})
          view-model (vm/order-form-vm state)]
      (is (true? (:tpsl-panel-open? view-model)))))

  (testing "tpsl panel state is forced closed for scale order type"
    (let [state (base-state {:type :scale}
                            {:tpsl-panel-open? true})
          view-model (vm/order-form-vm state)]
      (is (false? (:tpsl-panel-open? view-model))))))

(deftest order-form-vm-read-only-identity-test
  (let [state (assoc (base-state {:type :limit} {})
                     :active-asset "ETH/USDC"
                     :active-market {:coin "ETH/USDC"
                                     :quote "USDC"
                                     :market-type :spot})
        view-model (vm/order-form-vm state)]
    (is (true? (:spot? view-model)))
    (is (true? (:read-only? view-model)))
    (is (= "ETH" (:base-symbol view-model)))
    (is (= :quote (:size-input-mode view-model)))
    (is (= "USDC" (:quote-symbol view-model)))))

(deftest order-type-plugin-config-contract-test
  (let [config order-type-registry/order-type-config
        pro-types (set (order-type-registry/pro-order-types))
        advanced-types (set trading/advanced-order-types)
        rendered-sections (sections/supported-order-type-sections)]
    (is (every? #(contains? (set (keys config)) %) pro-types))
    (is (contains? config :market))
    (is (contains? config :limit))
    (is (= advanced-types pro-types))
    (doseq [order-type (order-type-registry/pro-order-types)]
      (let [entry (get config order-type)
            label (:label entry)
            section-ids (:sections entry)]
        (is (string? label))
        (is (seq label))
        (is (vector? section-ids))
        (is (every? keyword? section-ids))
        (is (every? rendered-sections section-ids))
        (is (= section-ids (vm/order-type-sections order-type))))))
  (let [state (base-state {:size "1" :price "100"} {})]
    (doseq [order-type (order-type-registry/pro-order-types)]
      (let [view-model (vm/order-form-vm (-> state
                                             (assoc :order-form (assoc (:order-form state)
                                                                       :type order-type))
                                             (assoc-in [:order-form-ui :entry-mode] :pro)))
            submit (:submit view-model)]
        (is (map? submit))
        (is (contains? submit :disabled?))
        (is (contains? submit :reason))))))

(deftest order-form-vm-contract-and-order-type-invariants-test
  (let [state (base-state {:size "1" :price "100"} {})
        all-order-types (concat [:market :limit] (order-type-registry/pro-order-types))
        pro-types (set (order-type-registry/pro-order-types))]
    (doseq [order-type all-order-types]
      (let [entry-mode (trading/entry-mode-for-type order-type)
            view-model (vm/order-form-vm (assoc state
                                                :order-form (assoc (:order-form state)
                                                                   :type order-type)
                                                :order-form-ui (assoc (:order-form-ui state)
                                                                      :entry-mode entry-mode)))]
        (is (contracts/order-form-vm-valid? view-model) (str "contract failed for " order-type))
        (is (= order-type (:type view-model)))
        (if (= entry-mode :pro)
          (is (contains? pro-types (:type view-model)))
          (is (= entry-mode (:entry-mode view-model))))))))

(deftest order-type-extension-registry-invariants-test
  (is (true? (type-extensions/assert-valid-extension-registry!))))

(deftest order-type-extension-registry-rejects-unknown-sections-test
  (with-redefs [order-type-registry/order-type-config
                (assoc order-type-registry/order-type-config
                       :scale (assoc (get order-type-registry/order-type-config :scale)
                                     :sections [:unknown-section]))]
    (is (thrown-with-msg?
         js/Error
         #"Invalid order-form type extension registry"
         (type-extensions/assert-valid-extension-registry!)))))

(deftest order-form-vm-controls-are-registry-driven-test
  (testing "scale disables tpsl and liquidation row while enabling scale preview"
    (let [view-model (vm/order-form-vm (base-state {:entry-mode :pro :type :scale} {}))
          controls (:controls view-model)]
      (is (false? (:show-tpsl-toggle? controls)))
      (is (false? (:show-liquidation-row? controls)))
      (is (true? (:show-scale-preview? controls)))))

  (testing "market enables slippage row and hides limit-like controls"
    (let [view-model (vm/order-form-vm (base-state {:entry-mode :market :type :market} {}))
          controls (:controls view-model)]
      (is (true? (:show-slippage-row? controls)))
      (is (false? (:show-limit-like-controls? controls)))))

  (testing "pro limit-like type enables post-only capability"
    (let [view-model (vm/order-form-vm (base-state {:entry-mode :pro :type :stop-limit} {}))
          controls (:controls view-model)]
      (is (true? (:show-post-only? controls)))
      (is (true? (:show-limit-like-controls? controls))))))
