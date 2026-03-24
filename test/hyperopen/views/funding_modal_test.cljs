(ns hyperopen.views.funding-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.views.funding-modal :as view]
            [hyperopen.views.funding-modal.deposit :as deposit]
            [hyperopen.views.funding-modal.shared :as shared]
            [hyperopen.views.funding-modal.withdraw :as withdraw]))

(defn- base-state
  []
  {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
   :spot {:clearinghouse-state {:balances [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}]}}
   :webdata2 {:clearinghouseState {:availableToWithdraw "8.5"
                                   :marginSummary {:accountValue "20"
                                                   :totalMarginUsed "11.5"}}}
   :funding-ui {:modal (funding-actions/default-funding-modal-state)}})

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node
  [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- funding-modal-node
  [view-node]
  (find-first-node view-node #(= "funding-modal"
                                 (get-in % [1 :data-role]))))

(deftest deposit-amount-content-renders-minimum-prefill-action-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdc
                         :amount-input ""})
        view-node (view/funding-modal-view state)
        min-button (find-first-node view-node
                                    #(= [[:actions/set-funding-deposit-amount-to-minimum]]
                                        (get-in % [1 :on :click])))
        all-text (set (collect-strings view-node))]
    (is (some? min-button))
    (is (contains? all-text "MIN"))
    (is (not (contains? all-text "MAX")))))

(deftest deposit-flow-does-not-render-withdraw-lifecycle-panel-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :hyperunit-lifecycle {:direction :withdraw
                                               :asset-key :btc
                                               :operation-id "op_btc_5"
                                               :state :queued
                                               :status :pending
                                               :source-tx-confirmations 1
                                               :destination-tx-confirmations nil
                                               :position-in-withdraw-queue 2
                                               :destination-tx-hash nil
                                               :state-next-at 1700000000000
                                               :last-updated-ms 1700000000000
                                               :error nil}})
        view-node (view/funding-modal-view state)
        deposit-step (find-first-node view-node #(= "funding-deposit-address-step"
                                                    (get-in % [1 :data-role])))
        deposit-lifecycle (find-first-node view-node #(= "funding-deposit-lifecycle"
                                                         (get-in % [1 :data-role])))]
    (is (some? deposit-step))
    (is (nil? deposit-lifecycle))))

(deftest closed-funding-modal-does-not-read-dom-for-layout-test
  (let [query-count (atom 0)
        original-document (.-document js/globalThis)]
    (set! (.-document js/globalThis)
          #js {:querySelector (fn [_selector]
                                (swap! query-count inc)
                                nil)})
    (try
      (is (nil? (view/render-funding-modal {:modal {:open? false
                                                    :mode :deposit
                                                    :title "Deposit"}
                                            :content {:kind :deposit/select}})))
      (is (zero? @query-count))
      (finally
        (set! (.-document js/globalThis) original-document)))))

(deftest funding-modal-falls-back-to-selector-anchor-when-stored-anchor-missing-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :asset-select
                         :deposit-search-input ""
                         :deposit-selected-asset-key nil
                         :amount-input ""})
        original-document (.-document js/globalThis)
        original-inner-width (.-innerWidth js/globalThis)
        original-inner-height (.-innerHeight js/globalThis)]
    (set! (.-document js/globalThis)
          #js {:querySelector (fn [selector]
                                (when (= "[data-role='funding-action-deposit']" selector)
                                  #js {:getBoundingClientRect
                                       (fn []
                                         #js {:left 1040
                                              :right 1180
                                              :top 620
                                              :bottom 660
                                              :width 140
                                              :height 40})}))})
    (set! (.-innerWidth js/globalThis) 1440)
    (set! (.-innerHeight js/globalThis) 900)
    (try
      (let [view-node (view/funding-modal-view state)
            modal-node (find-first-node view-node #(= "funding-modal"
                                                      (get-in % [1 :data-role])))
            overlay-node (find-first-node view-node #(= "Close funding dialog"
                                                        (get-in % [1 :aria-label])))]
        (is (= "funding-modal-desktop" (get-in modal-node [1 :data-parity-id])))
        (is (= "448px" (get-in modal-node [1 :style :width])))
        (is (contains? (set (get-in modal-node [1 :class])) "pointer-events-auto"))
        (is (not (contains? (set (get-in modal-node [1 :class])) "w-full")))
        (is (contains? (set (get-in overlay-node [1 :class])) "bg-transparent"))
        (is (not (contains? (set (get-in overlay-node [1 :class])) "bg-black/65"))))
      (finally
        (set! (.-document js/globalThis) original-document)
        (set! (.-innerWidth js/globalThis) original-inner-width)
        (set! (.-innerHeight js/globalThis) original-inner-height)))))

(deftest funding-modal-aligns-popover-right-edge-to-trade-order-entry-divider-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :withdraw
                         :withdraw-step :asset-select
                         :withdraw-search-input ""
                         :anchor {:left 1040
                                  :right 1180
                                  :top 620
                                  :bottom 660
                                  :viewport-width 1440
                                  :viewport-height 900}})
        original-document (.-document js/globalThis)]
    (set! (.-document js/globalThis)
          #js {:querySelector (fn [selector]
                                (when (= "[data-parity-id='trade-order-entry-panel']" selector)
                                  #js {:getBoundingClientRect
                                       (fn []
                                         #js {:left 1120
                                              :right 1400
                                              :top 0
                                              :bottom 900
                                              :width 280
                                              :height 900})}))})
    (try
      (let [view-node (view/funding-modal-view state)
            modal-node (find-first-node view-node #(= "funding-modal"
                                                      (get-in % [1 :data-role])))]
        (is (= "funding-modal-desktop" (get-in modal-node [1 :data-parity-id])))
        (is (= "448px" (get-in modal-node [1 :style :width])))
        (is (= "672px" (get-in modal-node [1 :style :left]))))
      (finally
        (set! (.-document js/globalThis) original-document)))))

(deftest withdraw-flow-renders-hyperunit-protocol-and-lifecycle-details-test
  (let [state (-> (base-state)
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                             {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}])
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
                             :withdraw-step :amount-entry
                             :withdraw-selected-asset-key :btc
                             :amount-input "0.25"
                             :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                             :withdraw-generated-address "bridge-protocol-address"
                             :hyperunit-fee-estimate {:status :error
                                                      :by-chain {}
                                                      :requested-at-ms 1
                                                      :updated-at-ms 2
                                                      :error "Estimator offline"}
                             :hyperunit-withdrawal-queue {:status :error
                                                          :by-chain {"bitcoin" {:chain "bitcoin"
                                                                                :last-withdraw-queue-operation-tx-id
                                                                                "0xqueue123"}}
                                                          :requested-at-ms 3
                                                          :updated-at-ms 4
                                                          :error "Queue service offline"}
                             :hyperunit-lifecycle {:direction :withdraw
                                                   :asset-key :btc
                                                   :operation-id "op_btc_9"
                                                   :state :failed
                                                   :status :terminal
                                                   :source-tx-confirmations 1
                                                   :destination-tx-confirmations nil
                                                   :position-in-withdraw-queue 4
                                                   :destination-tx-hash "0000abc123"
                                                   :state-next-at nil
                                                   :last-updated-ms 1700000000001
                                                   :error "Bridge broadcast failed"}}))
        view-node (view/funding-modal-view state)
        lifecycle-node (find-first-node view-node #(= "funding-withdraw-lifecycle"
                                                      (get-in % [1 :data-role])))
        queue-link (find-first-node view-node #(= "https://mempool.space/tx/0xqueue123"
                                                  (get-in % [1 :href])))
        lifecycle-link (find-first-node view-node #(= "https://mempool.space/tx/0000abc123"
                                                      (get-in % [1 :href])))
        all-text (set (collect-strings view-node))]
    (is (some? lifecycle-node))
    (is (some? queue-link))
    (is (some? lifecycle-link))
    (is (contains? all-text "Withdrawal queue"))
    (is (contains? all-text "Last queue tx"))
    (is (contains? all-text "Live HyperUnit estimates unavailable: Estimator offline"))
    (is (contains? all-text "HyperUnit Protocol Address"))
    (is (contains? all-text "bridge-protocol-address"))
    (is (contains? all-text "Needs Attention"))
    (is (contains? all-text "Bridge broadcast failed"))
    (is (contains? all-text "1.25 BTC available"))))

(deftest deposit-address-content-uses-address-step-role-test
  (let [content (deposit/deposit-address-content {:selected-asset {:symbol "BTC"
                                                                   :network "Bitcoin"}
                                                  :flow {}
                                                  :summary {:rows []}
                                                  :lifecycle nil
                                                  :actions {:submit-label "Deposit"
                                                            :submit-disabled? false}})
        address-step (find-first-node content #(= "funding-deposit-address-step"
                                                  (get-in % [1 :data-role])))
        amount-step (find-first-node content #(= "funding-deposit-amount-step"
                                                 (get-in % [1 :data-role])))]
    (is (some? address-step))
    (is (nil? amount-step))))

(deftest deposit-amount-content-disables-quick-controls-while-submitting-test
  (let [content (deposit/deposit-amount-content {:selected-asset {:symbol "USDC"
                                                                  :network "Ethereum"}
                                                 :amount {:minimum-value "5"
                                                          :value "12.5"
                                                          :quick-amounts [25 1000]}
                                                 :summary {:rows []}
                                                 :actions {:submit-label "Submitting..."
                                                           :submit-disabled? true
                                                           :submitting? true}})
        min-button (find-first-node content
                                    #(= [[:actions/set-funding-deposit-amount-to-minimum]]
                                        (get-in % [1 :on :click])))
        quick-button (find-first-node content
                                      #(= [[:actions/enter-funding-deposit-amount "25"]]
                                          (get-in % [1 :on :click])))
        input-node (find-first-node content
                                    #(and (= :input (first %))
                                          (= "decimal" (get-in % [1 :inputmode]))))]
    (is (true? (get-in min-button [1 :disabled])))
    (is (true? (get-in quick-button [1 :disabled])))
    (is (= "decimal" (get-in input-node [1 :inputmode])))
    (is (nil? (get-in input-node [1 :input-mode])))))

(deftest withdraw-detail-content-renders-unavailable-queue-and-error-copy-from-flow-model-test
  (let [selected-asset {:key :btc
                        :symbol "BTC"
                        :network "Bitcoin"}
        content (withdraw/withdraw-detail-content {:selected-asset selected-asset
                                                   :destination {:value "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"}
                                                   :amount {:value "0.25"
                                                            :max-display "1.25"
                                                            :max-input "1.25"
                                                            :available-label "1.25 BTC available"
                                                            :symbol "BTC"}
                                                   :flow {:kind :hyperunit-address
                                                          :protocol-address "bridge-protocol-address"
                                                          :fee-estimate {:state :error
                                                                         :message "Estimator offline"}
                                                          :withdrawal-queue {:state :error
                                                                             :length nil
                                                                             :last-operation {:tx-id "0xqueue123"
                                                                                              :explorer-url "https://mempool.space/tx/0xqueue123"}
                                                                             :message "Queue service offline"}}
                                                   :summary {:rows [{:label "Estimated time"
                                                                     :value "Depends on destination chain"}]}
                                                   :lifecycle nil
                                                   :actions {:submit-label "Withdraw"
                                                             :submit-disabled? false
                                                             :submitting? false}})
        queue-link (find-first-node content #(= "https://mempool.space/tx/0xqueue123"
                                                (get-in % [1 :href])))
        all-text (set (collect-strings content))]
    (is (some? queue-link))
    (is (contains? all-text "Withdrawal queue"))
    (is (contains? all-text "Unavailable"))
    (is (contains? all-text "Last queue tx"))
    (is (contains? all-text "Live queue status unavailable: Queue service offline"))
    (is (contains? all-text "Live HyperUnit estimates unavailable: Estimator offline"))
    (is (contains? all-text "HyperUnit Protocol Address"))
    (is (contains? all-text "bridge-protocol-address"))
    (is (contains? all-text "1.25 BTC available"))))

(deftest withdraw-select-step-renders-asset-list-with-withdrawable-amounts-test
  (let [state (-> (base-state)
                  (assoc :account {:mode :unified})
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "360.793551" :total "360.793551" :hold "0"}
                             {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}])
                  (assoc-in [:webdata2 :clearinghouseState]
                            {:availableToWithdraw "0"
                             :marginSummary {:accountValue "0"
                                             :totalMarginUsed "0"}})
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
                             :withdraw-step :asset-select
                             :withdraw-search-input ""}))
        view-node (view/funding-modal-view state)
        select-step (find-first-node view-node #(= "funding-withdraw-select-step"
                                                   (get-in % [1 :data-role])))
        asset-list (find-first-node view-node #(= "funding-withdraw-asset-list"
                                                  (get-in % [1 :data-role])))
        all-text (set (collect-strings view-node))]
    (is (some? select-step))
    (is (some? asset-list))
    (is (contains? all-text "Withdraw funds from your Hyperliquid account. You can deposit at any time."))
    (is (contains? all-text "360.793551"))
    (is (contains? all-text "1.25"))
    (is (not (contains? all-text "Destination Address")))))

(deftest standard-withdraw-detail-uses-generic-destination-copy-without-hyperunit-sections-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :withdraw
                         :withdraw-step :amount-entry
                         :withdraw-selected-asset-key :usdc
                         :amount-input ""
                         :destination-input "0x1234567890abcdef1234567890abcdef12345678"})
        view-node (view/funding-modal-view state)
        destination-node (find-first-node view-node #(= "0x..."
                                                        (get-in % [1 :placeholder])))
        amount-node (find-first-node view-node #(= "funding-withdraw-amount-input"
                                                   (get-in % [1 :data-role])))
        max-node (find-first-node view-node #(= [[:actions/set-funding-amount-to-max]]
                                                (get-in % [1 :on :click])))
        quick-amount-node (find-first-node view-node #(= [[:actions/enter-funding-withdraw-amount "5"]]
                                                         (get-in % [1 :on :click])))
        lifecycle-node (find-first-node view-node #(= "funding-withdraw-lifecycle"
                                                      (get-in % [1 :data-role])))
        all-text (set (collect-strings view-node))]
    (is (some? destination-node))
    (is (some? amount-node))
    (is (some? max-node))
    (is (contains? all-text "Destination Address"))
    (is (= "0x1234567890abcdef1234567890abcdef12345678"
           (get-in destination-node [1 :value])))
    (is (contains? (set (get-in amount-node [1 :class])) "text-right"))
    (is (nil? quick-amount-node))
    (is (contains? all-text "8.5 USDC available"))
    (is (not (contains? all-text "Withdrawal queue")))
    (is (not (contains? all-text "HyperUnit Protocol Address")))
    (is (nil? lifecycle-node))))

(deftest lifecycle-panel-renders-plain-text-destination-tx-when-explorer-is-missing-test
  (let [panel (shared/lifecycle-panel {:stage-label "Queued"
                                       :status-label "Pending"
                                       :destination-tx {:hash "tx-raw"
                                                        :explorer-url nil}
                                       :next-check-label "Scheduled"}
                                      "funding-lifecycle-test")
        tx-node (find-first-node panel #(and (= :p (first %))
                                             (= "tx-raw" (last %))))
        link-node (find-first-node panel #(= :a (first %)))
        all-text (set (collect-strings panel))]
    (is (= "funding-lifecycle-test" (get-in panel [1 :data-role])))
    (is (some? tx-node))
    (is (nil? link-node))
    (is (contains? all-text "Destination tx hash"))
    (is (contains? all-text "tx-raw"))
    (is (contains? all-text "Scheduled"))))

(deftest funding-modal-renders-explicit-fallback-for-unknown-content-kind-test
  (let [view-node (view/render-funding-modal {:modal {:open? true
                                                      :mode :deposit
                                                      :title "Funding"}
                                              :content {:kind :mystery/state}})
        fallback-node (find-first-node view-node #(= "funding-unknown-content"
                                                     (get-in % [1 :data-role])))
        close-button (find-first-node view-node
                                      #(and (= :button (first %))
                                            (= "×" (last %))))
        all-text (set (collect-strings view-node))]
    (is (some? fallback-node))
    (is (contains? all-text "This funding modal state is not supported yet."))
    (is (contains? all-text "Unhandled content kind: :mystery/state"))
    (is (= "Close funding dialog" (get-in close-button [1 :aria-label])))))

(deftest funding-send-modal-renders-mobile-sheet-layout-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :send
                         :send-token "xyz:GOLD"
                         :send-symbol "GOLD"
                         :send-prefix-label "xyz"
                         :send-max-amount 4.25
                         :send-max-display "4.250000"
                         :send-max-input "4.250000"
                         :amount-input ""
                         :destination-input ""
                         :anchor {:viewport-width 430
                                  :viewport-height 932}})
        original-inner-width (.-innerWidth js/globalThis)
        original-inner-height (.-innerHeight js/globalThis)]
    (set! (.-innerWidth js/globalThis) 430)
    (set! (.-innerHeight js/globalThis) 932)
    (try
      (let [view-node (view/funding-modal-view state)
            layer-node (find-first-node view-node #(= "funding-mobile-sheet-layer"
                                                      (get-in % [1 :data-role])))
            backdrop-node (find-first-node view-node #(= "funding-mobile-sheet-backdrop"
                                                         (get-in % [1 :data-role])))
            modal-node (find-first-node view-node #(= "funding-modal"
                                                      (get-in % [1 :data-role])))
            send-step (find-first-node view-node #(= "funding-send-step"
                                                     (get-in % [1 :data-role])))
            destination-input (find-first-node view-node #(= "funding-send-destination-input"
                                                             (get-in % [1 :data-role])))
            amount-input (find-first-node view-node #(= "funding-send-amount-input"
                                                        (get-in % [1 :data-role])))
            all-text (set (collect-strings view-node))]
        (is (some? layer-node))
        (is (some? backdrop-node))
        (is (contains? (set (get-in modal-node [1 :class])) "absolute"))
        (is (contains? (set (get-in modal-node [1 :class])) "bottom-0"))
        (is (contains? (set (get-in modal-node [1 :class])) "rounded-t-[22px]"))
        (is (contains? (set (get-in modal-node [1 :class])) "bg-[#06131a]"))
        (is (= "true" (get-in modal-node [1 :data-funding-mobile-sheet-surface])))
        (is (some? send-step))
        (is (some? destination-input))
        (is (some? amount-input))
        (is (contains? all-text "Send Tokens"))
        (is (contains? all-text "Trading Account"))
        (is (contains? all-text "GOLD"))
        (is (contains? all-text "xyz"))
        (is (contains? all-text "MAX: 4.250000 GOLD")))
      (finally
        (set! (.-innerWidth js/globalThis) original-inner-width)
        (set! (.-innerHeight js/globalThis) original-inner-height)))))

(deftest funding-send-modal-uses-mobile-sheet-layout-without-anchor-on-mobile-viewport-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :send
                         :send-token "USDC"
                         :send-symbol "USDC"
                         :send-max-amount 12.5
                         :send-max-display "12.500000"
                         :send-max-input "12.500000"
                         :amount-input ""
                         :destination-input ""})
        original-inner-width (.-innerWidth js/globalThis)
        original-inner-height (.-innerHeight js/globalThis)]
    (set! (.-innerWidth js/globalThis) 430)
    (set! (.-innerHeight js/globalThis) 932)
    (try
      (let [view-node (view/funding-modal-view state)
            layer-node (find-first-node view-node #(= "funding-mobile-sheet-layer"
                                                      (get-in % [1 :data-role])))
            modal-node (find-first-node view-node #(= "funding-modal"
                                                      (get-in % [1 :data-role])))]
        (is (some? layer-node))
        (is (contains? (set (get-in modal-node [1 :class])) "bottom-0"))
        (is (= "true" (get-in modal-node [1 :data-funding-mobile-sheet-surface]))))
      (finally
        (set! (.-innerWidth js/globalThis) original-inner-width)
        (set! (.-innerHeight js/globalThis) original-inner-height)))))

(deftest funding-mobile-action-modals-render-as-bottom-sheets-on-mobile-test
  (let [mobile-anchor {:viewport-width 430
                       :viewport-height 932}
        cases [{:name "deposit"
                :modal {:open? true
                        :mode :deposit
                        :deposit-step :asset-select
                        :deposit-search-input ""
                        :deposit-selected-asset-key nil
                        :amount-input ""
                        :anchor mobile-anchor}
                :expected-text "Deposit"}
               {:name "transfer"
                :modal {:open? true
                        :mode :transfer
                        :to-perp? true
                        :amount-input ""
                        :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                        :anchor mobile-anchor}
                :expected-text "Perps <-> Spot"}
               {:name "withdraw"
                :modal {:open? true
                        :mode :withdraw
                        :withdraw-selected-asset-key :usdc
                        :amount-input ""
                        :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                        :anchor mobile-anchor}
                :expected-text "Withdraw"}]
        original-inner-width (.-innerWidth js/globalThis)
        original-inner-height (.-innerHeight js/globalThis)]
    (set! (.-innerWidth js/globalThis) 430)
    (set! (.-innerHeight js/globalThis) 932)
    (try
      (doseq [{:keys [name modal expected-text]} cases]
        (let [state (assoc-in (base-state) [:funding-ui :modal] modal)
              view-node (view/funding-modal-view state)
              layer-node (find-first-node view-node #(= "funding-mobile-sheet-layer"
                                                        (get-in % [1 :data-role])))
              backdrop-node (find-first-node view-node #(= "funding-mobile-sheet-backdrop"
                                                           (get-in % [1 :data-role])))
              modal-node (funding-modal-node view-node)
              all-text (set (collect-strings view-node))]
          (is (some? layer-node) (str name " uses mobile sheet layer"))
          (is (some? backdrop-node) (str name " uses mobile sheet backdrop"))
          (is (= "true" (get-in modal-node [1 :data-funding-mobile-sheet-surface]))
              (str name " marks the surface as a mobile sheet"))
          (is (contains? (set (get-in modal-node [1 :class])) "bottom-0")
              (str name " anchors the surface to the bottom"))
          (is (contains? (set (get-in modal-node [1 :class])) "rounded-t-[22px]")
              (str name " uses sheet top rounding"))
          (is (contains? all-text expected-text)
              (str name " renders expected content"))))
      (finally
        (set! (.-innerWidth js/globalThis) original-inner-width)
        (set! (.-innerHeight js/globalThis) original-inner-height)))))
