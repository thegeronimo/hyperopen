(ns hyperopen.views.funding-modal-accessibility-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.platform :as platform]
            [hyperopen.views.funding-modal :as view]
            [hyperopen.views.funding-modal.deposit :as deposit]
            [hyperopen.views.funding-modal.send :as send]
            [hyperopen.views.funding-modal.transfer :as transfer]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]
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

(defn- funding-modal-node
  [view-node]
  (find-first-node view-node #(= "funding-modal"
                                 (get-in % [1 :data-role]))))

(defn- label-for-node
  [node input-id]
  (find-first-node node #(and (= :label (first %))
                              (= input-id (get-in % [1 :for])))))

(defn- make-focus-node
  ([document]
   (make-focus-node document {}))
  ([document attrs]
   (let [focus-calls (atom 0)
         node #js {:isConnected true}]
     (aset node
           "getAttribute"
           (fn [attr-name]
             (get attrs (keyword attr-name))))
     (aset node
           "focus"
           (fn []
             (swap! focus-calls inc)
             (set! (.-activeElement document) node)))
     {:node node
      :focus-calls focus-calls})))

(defn- make-dialog-node
  [document children]
  (let [node #js {:isConnected true}]
    (aset node
          "querySelectorAll"
          (fn [_selector]
            (into-array children)))
    (aset node
          "contains"
          (fn [candidate]
            (boolean
             (or (= candidate node)
                 (some #(= candidate %) children)))))
    (aset node
          "addEventListener"
          (fn [_event-name _handler]
            nil))
    (aset node
          "removeEventListener"
          (fn [_event-name _handler]
            nil))
    (aset node
          "focus"
          (fn []
            (set! (.-activeElement document) node)))
    {:node node}))

(deftest funding-modal-shell-uses-labelledby-and-focus-hook-test
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
        view-node (view/funding-modal-view state)
        modal-node (funding-modal-node view-node)
        title-node (find-first-node view-node #(and (= :h2 (first %))
                                                    (= "funding-modal-title"
                                                       (get-in % [1 :id]))))
        close-button (find-first-node view-node #(= "funding-modal-close"
                                                    (get-in % [1 :data-role])))]
    (is (= "dialog" (get-in modal-node [1 :role])))
    (is (= "funding-modal-title" (get-in modal-node [1 :aria-labelledby])))
    (is (fn? (get-in modal-node [1 :replicant/on-render])))
    (is (some? title-node))
    (is (some? close-button))
    (is (= "Close funding dialog" (get-in close-button [1 :aria-label])))))

(deftest funding-modal-transfer-shell-restores-focus-via-cross-surface-fallback-selector-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :transfer
                         :to-perp? true
                         :amount-input ""})
        view-node (view/funding-modal-view state)
        modal-node (funding-modal-node view-node)
        on-render (get-in modal-node [1 :replicant/on-render])
        original-document (.-document js/globalThis)
        original-get-computed-style (.-getComputedStyle js/globalThis)
        document #js {}
        body-node (:node (make-focus-node document))
        header-opener (make-focus-node document {:data-role "portfolio-action-send"})
        replacement-opener (make-focus-node document {:data-role "portfolio-funding-action-transfer"})
        first-focusable (make-focus-node document)
        {:keys [node]} (make-dialog-node document [(:node first-focusable)])
        queried-selectors* (atom [])
        combined-selector "[data-role=\"funding-action-transfer\"], [data-role=\"portfolio-action-send\"], [data-role=\"portfolio-funding-action-transfer\"]"]
    (set! (.-body document) body-node)
    (set! (.-activeElement document) (:node header-opener))
    (aset document
          "querySelector"
          (fn [selector]
            (swap! queried-selectors* conj selector)
            (case selector
              "[data-role=\"portfolio-action-send\"]" nil
              "[data-role=\"funding-action-transfer\"], [data-role=\"portfolio-action-send\"], [data-role=\"portfolio-funding-action-transfer\"]"
              (:node replacement-opener)
              nil)))
    (set! (.-document js/globalThis) document)
    (set! (.-getComputedStyle js/globalThis)
          (fn [_node]
            #js {:display "block"
                 :visibility "visible"}))
    (try
      (with-redefs [platform/queue-microtask! (fn [f] (f))
                    platform/set-timeout! (fn [_f _ms] :timeout-id)]
        (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                    :replicant/node node
                    :replicant/remember (fn [_memory] nil)})
        (set! (.-isConnected (:node header-opener)) false)
        (dialog-focus/restore-remembered-focus!)
        (is (= 1 @(-> replacement-opener :focus-calls)))
        (is (= (:node replacement-opener) (.-activeElement document)))
        (is (= ["[data-role=\"portfolio-action-send\"]"
                combined-selector]
               @queried-selectors*)))
      (finally
        (set! (.-document js/globalThis) original-document)
        (set! (.-getComputedStyle js/globalThis) original-get-computed-style)))))

(deftest funding-modal-form-fields-associate-labels-with-input-ids-test
  (let [deposit-select (deposit/deposit-select-content {:search {:value ""
                                                                 :placeholder "Search a supported asset"}
                                                        :assets []
                                                        :selected-asset nil})
        deposit-search-label (label-for-node deposit-select "funding-deposit-search-input")
        deposit-search-input (find-first-node deposit-select #(= "funding-deposit-search-input"
                                                                 (get-in % [1 :id])))
        send-content (send/render-content {:asset {:symbol "USDC"
                                                   :prefix-label nil}
                                           :destination {:value ""}
                                           :amount {:value ""
                                                    :max-display "12.500000"
                                                    :symbol "USDC"}
                                           :actions {:submit-label "Send"
                                                     :submit-disabled? false
                                                     :submitting? false}})
        send-destination-label (label-for-node send-content "funding-send-destination-input")
        send-destination-input (find-first-node send-content #(= "funding-send-destination-input"
                                                                 (get-in % [1 :id])))
        send-amount-label (label-for-node send-content "funding-send-amount-input-field")
        send-amount-input (find-first-node send-content #(= "funding-send-amount-input-field"
                                                            (get-in % [1 :id])))
        transfer-content (transfer/render-content {:to-perp? false
                                                   :amount {:value "12"
                                                            :max-display "55.000000"
                                                            :max-input "55.000000"
                                                            :symbol "USDC"}
                                                   :actions {:submit-label "Transfer"
                                                             :submit-disabled? false
                                                             :submitting? false}})
        transfer-amount-label (label-for-node transfer-content "funding-transfer-amount-input-field")
        transfer-amount-input (find-first-node transfer-content #(= "funding-transfer-amount-input-field"
                                                                    (get-in % [1 :id])))
        withdraw-select (withdraw/withdraw-select-content {:search {:value ""
                                                                    :placeholder "Search a supported asset"}
                                                           :assets []
                                                           :selected-asset nil})
        withdraw-search-label (label-for-node withdraw-select "funding-withdraw-search-input")
        withdraw-search-input (find-first-node withdraw-select #(= "funding-withdraw-search-input"
                                                                   (get-in % [1 :id])))
        withdraw-detail (withdraw/withdraw-detail-content {:selected-asset {:symbol "BTC"
                                                                            :network "Bitcoin"}
                                                           :destination {:value ""}
                                                           :amount {:value "0.25"
                                                                    :max-display "1.25"
                                                                    :max-input "1.25"
                                                                    :available-label "1.25 BTC available"
                                                                    :symbol "BTC"}
                                                           :flow {:kind :hyperunit-address
                                                                  :protocol-address nil
                                                                  :fee-estimate {:state :ready
                                                                                 :message nil}
                                                                  :withdrawal-queue {:state :idle
                                                                                     :length nil
                                                                                     :last-operation {:tx-id nil
                                                                                                      :explorer-url nil}
                                                                                     :message nil}}
                                                           :summary {:rows []}
                                                           :lifecycle nil
                                                           :actions {:submit-label "Withdraw"
                                                                     :submit-disabled? false
                                                                     :submitting? false}})
        withdraw-destination-label (label-for-node withdraw-detail "funding-withdraw-destination-input")
        withdraw-destination-input (find-first-node withdraw-detail #(= "funding-withdraw-destination-input"
                                                                        (get-in % [1 :id])))
        withdraw-amount-label (label-for-node withdraw-detail "funding-withdraw-amount-input")
        withdraw-amount-input (find-first-node withdraw-detail #(= "funding-withdraw-amount-input"
                                                                   (get-in % [1 :id])))]
    (is (some? deposit-search-label))
    (is (some? deposit-search-input))
    (is (some? send-destination-label))
    (is (some? send-destination-input))
    (is (some? send-amount-label))
    (is (some? send-amount-input))
    (is (some? transfer-amount-label))
    (is (some? transfer-amount-input))
    (is (some? withdraw-search-label))
    (is (some? withdraw-search-input))
    (is (some? withdraw-destination-label))
    (is (some? withdraw-destination-input))
    (is (some? withdraw-amount-label))
    (is (some? withdraw-amount-input))))
