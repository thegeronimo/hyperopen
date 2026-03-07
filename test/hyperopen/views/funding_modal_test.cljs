(ns hyperopen.views.funding-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.views.funding-modal :as view]))

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
        deposit-step (find-first-node view-node #(= "funding-deposit-amount-step"
                                                    (get-in % [1 :data-role])))
        deposit-lifecycle (find-first-node view-node #(= "funding-deposit-lifecycle"
                                                         (get-in % [1 :data-role])))]
    (is (some? deposit-step))
    (is (nil? deposit-lifecycle))))

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
        (is (= "448px" (get-in modal-node [1 :style :width])))
        (is (contains? (set (get-in modal-node [1 :class])) "pointer-events-auto"))
        (is (not (contains? (set (get-in modal-node [1 :class])) "w-full")))
        (is (contains? (set (get-in overlay-node [1 :class])) "bg-transparent"))
        (is (not (contains? (set (get-in overlay-node [1 :class])) "bg-black/65"))))
      (finally
        (set! (.-document js/globalThis) original-document)
        (set! (.-innerWidth js/globalThis) original-inner-width)
        (set! (.-innerHeight js/globalThis) original-inner-height)))))

(deftest withdraw-flow-renders-hyperunit-protocol-and-lifecycle-details-test
  (let [state (-> (base-state)
                  (assoc-in [:spot :clearinghouse-state :balances]
                            [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                             {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}])
                  (assoc-in [:funding-ui :modal]
                            {:open? true
                             :mode :withdraw
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
    (is (contains? all-text "Bridge broadcast failed"))))

(deftest withdraw-content-renders-unavailable-queue-and-error-copy-from-flow-model-test
  (let [selected-asset {:key :btc
                        :symbol "BTC"
                        :network "Bitcoin"}
        content (@#'view/withdraw-content {:assets [selected-asset]
                                          :selected-asset selected-asset
                                          :destination {:value "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"}
                                          :amount {:value "0.25"
                                                   :max-display "1.25"
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
    (is (contains? all-text "bridge-protocol-address"))))

(deftest standard-withdraw-flow-uses-generic-destination-copy-without-hyperunit-sections-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :withdraw
                         :withdraw-selected-asset-key :usdc
                         :amount-input ""
                         :destination-input ""})
        view-node (view/funding-modal-view state)
        destination-node (find-first-node view-node #(= "0x..."
                                                        (get-in % [1 :placeholder])))
        amount-node (find-first-node view-node #(= "funding-withdraw-amount-input"
                                                   (get-in % [1 :data-role])))
        lifecycle-node (find-first-node view-node #(= "funding-withdraw-lifecycle"
                                                      (get-in % [1 :data-role])))
        all-text (set (collect-strings view-node))]
    (is (some? destination-node))
    (is (some? amount-node))
    (is (contains? all-text "Destination Address"))
    (is (not (contains? all-text "Withdrawal queue")))
    (is (not (contains? all-text "HyperUnit Protocol Address")))
    (is (nil? lifecycle-node))))

(deftest lifecycle-panel-renders-plain-text-destination-tx-when-explorer-is-missing-test
  (let [panel (@#'view/lifecycle-panel {:stage-label "Queued"
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
