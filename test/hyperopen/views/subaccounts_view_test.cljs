(ns hyperopen.views.subaccounts-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.test-support.hiccup :as shared-hiccup]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.subaccounts-view :as view]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def subaccount-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def other-subaccount-address
  "0x9999999999999999999999999999999999999999")

(def spectate-address
  "0x7777777777777777777777777777777777777777")

(defn- base-state []
  {:wallet {:address owner-address}
   :webdata2 {:clearinghouseState {:marginSummary {:accountValue "999.99"}}}
   :spot {:clearinghouse-state {:balances [{:coin "USDC"
                                             :total "50.50"}
                                            {:coin "USDH"
                                             :token "USDH:0xabc"
                                             :total "8.25"}]}}
   :account-context
   {:subaccounts
    {:status :loaded
     :loaded-for-owner owner-address
     :owner-snapshot {:owner owner-address
                      :clearinghouse-state {:marginSummary {:accountValue "999.99"}
                                            :withdrawable "999.99"}
                      :spot-state {:balances [{:coin "USDC"
                                                :total "50.50"}]}
                      :loading? false
                      :error nil}
     :rows [{:name "Desk"
             :master owner-address
             :sub-account-user subaccount-address
             :clearinghouse-state {:marginSummary {:accountValue "123.45"}}
             :spot-state {:balances [{:coin "USDC"
                                       :total "250.25"}
                                      {:coin "MEOW"
                                       :token "MEOW:0xdef"
                                       :total "0.02"}]}}
            {:name "Ops"
             :master owner-address
             :sub-account-user other-subaccount-address
             :clearinghouse-state {:marginSummary {:accountValue "0"}}
             :spot-state {:balances [{:coin "MEOW"
                                       :token "MEOW:0xdef"
                                       :total "0.02"}]}}]
     :error nil
     :selected-address subaccount-address
     :selection-loaded? true}}})

(deftest subaccounts-view-renders-master-and-subaccount-selection-actions-test
  (let [view-node (view/subaccounts-view (base-state))
        root (shared-hiccup/find-by-parity-id view-node "subaccounts-root")
        console (hiccup/find-by-data-role view-node "subaccounts-console")
        create-panel (hiccup/find-by-data-role view-node "subaccounts-create-panel")
        master-row (hiccup/find-by-data-role view-node "subaccounts-master-row")
        refresh-button (hiccup/find-by-data-role view-node "subaccounts-refresh")
        copy-master (hiccup/find-by-data-role view-node "subaccounts-copy-master")
        copy-selected (hiccup/find-by-data-role view-node (str "subaccounts-copy-" subaccount-address))
        selected-row (hiccup/find-by-data-role view-node (str "subaccounts-row-" subaccount-address))
        other-row (hiccup/find-by-data-role view-node (str "subaccounts-row-" other-subaccount-address))
        select-master (hiccup/find-by-data-role view-node "subaccounts-select-master")
        select-other (hiccup/find-by-data-role view-node (str "subaccounts-select-" other-subaccount-address))
        root-style (get-in root [1 :style])
        strings (set (hiccup/collect-strings view-node))]
    (is (some? root))
    (is (= "#061b20" (:background-color root-style)))
    (is (re-find #"radial-gradient\(120% 80% at 15% -10%" (:background-image root-style)))
    (is (not (re-find #"88% 112%" (:background-image root-style))))
    (is (contains? strings "Sub-Accounts"))
    (is (contains? strings "Master Account"))
    (is (contains? strings "Perps Account Equity"))
    (is (contains? strings "Spot Account Equity"))
    (is (contains? strings "Desk"))
    (is (contains? strings "Ops"))
    (is (contains? strings "$999.99"))
    (is (contains? strings "$50.50"))
    (is (contains? strings "$123.45"))
    (is (contains? strings "$250.25"))
    (is (contains? strings "Trade"))
    (is (some? master-row))
    (is (some? console))
    (is (some? create-panel))
    (is (some? copy-master))
    (is (some? copy-selected))
    (is (some? selected-row))
    (is (some? other-row))
    (is (= [[:actions/refresh-subaccounts]]
           (get-in refresh-button [1 :on :click])))
    (is (= [[:actions/select-master-account]]
           (get-in select-master [1 :on :click])))
    (is (= [[:actions/copy-subaccount-address owner-address]]
           (get-in copy-master [1 :on :click])))
    (is (= [[:actions/copy-subaccount-address subaccount-address]]
           (get-in copy-selected [1 :on :click])))
    (is (= [[:actions/select-subaccount other-subaccount-address]]
           (get-in select-other [1 :on :click])))))

(deftest selected-subaccount-active-state-does-not-render-as-master-balance-test
  (let [view-node (view/subaccounts-view
                   (-> (base-state)
                       ;; In the user report the header is already trading as the
                       ;; selected subaccount, so top-level account state belongs
                       ;; to Tenor/Desk, not to the master wallet.
                       (assoc-in [:webdata2 :clearinghouseState]
                                 {:marginSummary {:accountValue "2002.20"
                                                  :totalMarginUsed "0"}
                                  :withdrawable "2002.19691"})
                       (assoc-in [:account-context :subaccounts :owner-snapshot]
                                 {:owner owner-address
                                  :clearinghouse-state
                                  {:marginSummary {:accountValue "0"
                                                   :totalMarginUsed "0"}
                                   :withdrawable "0"}
                                  :spot-state {:balances [{:coin "USDC"
                                                           :total "0"
                                                           :hold "0"}]}
                                  :loading? false
                                  :error nil})
                       (assoc-in [:account-context :subaccounts :rows 0 :clearinghouse-state]
                                 {:marginSummary {:accountValue "2002.20"
                                                  :totalMarginUsed "0"}
                                  :withdrawable "2002.19691"})
                       (assoc-in [:account-context :subaccounts :transferring-address]
                                 subaccount-address)))
        master-row (hiccup/find-by-data-role view-node "subaccounts-master-row")
        selected-row (hiccup/find-by-data-role view-node (str "subaccounts-row-" subaccount-address))
        transfer-max (hiccup/find-by-data-role view-node
                                               (str "subaccounts-transfer-max-" subaccount-address))
        transfer-submit (hiccup/find-by-data-role view-node
                                                  (str "subaccounts-transfer-submit-" subaccount-address))
        master-strings (set (hiccup/collect-strings master-row))
        selected-strings (set (hiccup/collect-strings selected-row))]
    (is (contains? selected-strings "$2,002.20")
        "The selected subaccount row still shows the selected account's row balance.")
    (is (contains? master-strings "$0.00")
        "The master row must come from the owner snapshot, not the active selected subaccount.")
    (is (not (contains? master-strings "$2,002.20"))
        "The selected subaccount's active top-level state must not be duplicated into the master row.")
    (is (contains? (set (hiccup/collect-strings transfer-max))
                   "MAX: 0 USDC"))
    (is (true? (get-in transfer-submit [1 :disabled]))
        "A zero master source max must disable the default Master -> subaccount submit.")))

(deftest subaccounts-transfer-max-uses-withdrawable-source-not-equity-test
  (let [base (-> (base-state)
                 (assoc-in [:account-context :subaccounts :owner-snapshot]
                           {:owner owner-address
                            :clearinghouse-state
                            {:marginSummary {:accountValue "2002.20"
                                             :totalMarginUsed "0"}
                             :withdrawable "12.34"}
                            :spot-state {:balances [{:coin "USDC"
                                                     :total "50.50"}]}
                            :loading? false
                            :error nil})
                 (assoc-in [:account-context :subaccounts :rows 0 :clearinghouse-state]
                           {:marginSummary {:accountValue "2002.20"
                                            :totalMarginUsed "0"}
                            :withdrawable "7.89"})
                 (assoc-in [:account-context :subaccounts :transferring-address]
                           subaccount-address))
        deposit-node (view/subaccounts-view base)
        withdraw-node (view/subaccounts-view
                       (assoc-in base
                                 [:account-context :subaccounts :transfer-direction]
                                 :withdraw))
        deposit-max (hiccup/find-by-data-role deposit-node
                                              (str "subaccounts-transfer-max-" subaccount-address))
        withdraw-max (hiccup/find-by-data-role withdraw-node
                                               (str "subaccounts-transfer-max-" subaccount-address))]
    (is (contains? (set (hiccup/collect-strings deposit-max))
                   "MAX: 12.34 USDC"))
    (is (contains? (set (hiccup/collect-strings withdraw-max))
                   "MAX: 7.89 USDC"))))

(deftest subaccounts-view-renders-empty-and-error-states-test
  (let [empty-node (view/subaccounts-view
                    {:wallet {:address owner-address}
                     :account-context {:subaccounts {:status :loaded
                                                     :rows []
                                                     :selected-address nil}}})
        error-node (view/subaccounts-view
                    {:wallet {:address owner-address}
                     :account-context {:subaccounts {:status :error
                                                     :rows []
                                                     :error "boom"}}})
        disconnected-node (view/subaccounts-view
                           {:wallet {:address nil}
                            :account-context {:subaccounts {:status :idle
                                                            :rows []}}})]
    (is (contains? (set (hiccup/collect-strings empty-node))
                   "No subaccounts found for this master account."))
    (is (contains? (set (hiccup/collect-strings error-node))
                   "boom"))
    (is (contains? (set (hiccup/collect-strings disconnected-node))
                   "Not connected"))))

(deftest subaccounts-view-keeps-rows-visible-while-refreshing-test
  (let [view-node (view/subaccounts-view
                   (assoc-in (base-state)
                             [:account-context :subaccounts :refreshing?] true))
        refresh-button (hiccup/find-by-data-role view-node "subaccounts-refresh")
        strings (set (hiccup/collect-strings view-node))]
    (is (some? (hiccup/find-by-data-role view-node (str "subaccounts-row-" subaccount-address)))
        "A refresh must keep the rendered subaccount rows visible.")
    (is (contains? strings "Desk"))
    (is (not (contains? strings "Loading subaccounts...")))
    (is (= "Refreshing..." (last refresh-button)))
    (is (true? (get-in refresh-button [1 :disabled])))
    (is (= [[:actions/refresh-subaccounts]]
           (get-in refresh-button [1 :on :click])))))

(deftest subaccounts-view-keeps-rows-visible-while-status-is-loading-test
  (let [view-node (view/subaccounts-view
                   (assoc-in (base-state)
                             [:account-context :subaccounts :status] :loading))
        strings (set (hiccup/collect-strings view-node))]
    (is (some? (hiccup/find-by-data-role view-node (str "subaccounts-row-" subaccount-address)))
        "Rows (and their transfer controls) must stay reachable even while a load is in flight.")
    (is (contains? strings "Desk"))
    (is (not (contains? strings "Loading subaccounts..."))
        "The loading placeholder must not replace already-rendered rows.")))

(deftest subaccounts-view-shows-loading-placeholder-only-when-no-rows-test
  (let [view-node (view/subaccounts-view
                   {:wallet {:address owner-address}
                    :account-context {:subaccounts {:status :loading
                                                    :rows []
                                                    :selected-address nil}}})
        strings (set (hiccup/collect-strings view-node))]
    (is (contains? strings "Loading subaccounts...")
        "With no rows yet, the loading placeholder is the right empty state.")))

(deftest unified-owner-mode-popover-offers-one-spot-option-even-when-active-account-classic-test
  (let [view-node (view/subaccounts-view
                   (-> (base-state)
                       ;; Active trading account is classic, but the master/owner
                       ;; is unified: the popover must follow the master.
                       (assoc :account {:mode :classic})
                       (assoc-in [:account-context :subaccounts :owner-mode]
                                 {:owner owner-address
                                  :mode :unified})
                       (assoc-in [:spot :clearinghouse-state :balances]
                                 [{:coin "USDC"
                                   :total "301.12859"
                                   :hold "0"}])
                       (assoc-in [:account-context :subaccounts :transfer-account] :spot)
                       (assoc-in [:account-context :subaccounts :transfer-account-menu-open?] true)
                       (assoc-in [:account-context :subaccounts :transferring-address] subaccount-address)))
        transfer-account-menu (hiccup/find-by-data-role view-node
                                                        (str "subaccounts-transfer-account-menu-" subaccount-address))
        transfer-spot-option (hiccup/find-by-data-role view-node
                                                       (str "subaccounts-transfer-account-option-"
                                                            subaccount-address
                                                            "-spot"))
        transfer-trading-option (hiccup/find-by-data-role view-node
                                                          (str "subaccounts-transfer-account-option-"
                                                               subaccount-address
                                                               "-trading"))]
    (is (contains? (set (hiccup/collect-strings transfer-account-menu)) "Spot Account"))
    (is (not (contains? (set (hiccup/collect-strings transfer-account-menu)) "Trading Account")))
    (is (some? transfer-spot-option)
        "A unified master pools spot and perps collateral and moves funds through the spot path, so its single option is the spot one.")
    (is (nil? transfer-trading-option)
        "A unified master must not offer a choice of funding source, regardless of the active account mode.")))

(deftest subaccounts-view-renders-create-rename-and-transfer-controls-test
  (let [view-node (view/subaccounts-view
                   (-> (base-state)
                       (assoc-in [:account-context :subaccounts :create-name] "New Desk")
                       (assoc-in [:account-context :subaccounts :create-popover-open?] true)
                       (assoc-in [:account-context :subaccounts :rename-name] "Desk B")
                       (assoc-in [:account-context :subaccounts :transfer-amount] "1.23")
                       (assoc-in [:account-context :subaccounts :transfer-direction] :withdraw)
                       (assoc-in [:account-context :subaccounts :transfer-account] :spot)
                       (assoc-in [:account-context :subaccounts :transfer-account-menu-open?] true)
                       (assoc-in [:account-context :subaccounts :transfer-token] "MEOW:0xdef")
                       (assoc-in [:account-context :subaccounts :transfer-token-menu-open?] true)
                       (assoc-in [:account-context :subaccounts :renaming-address] subaccount-address)
                       (assoc-in [:account-context :subaccounts :transferring-address] other-subaccount-address)))
        create-open (hiccup/find-by-data-role view-node "subaccounts-open-create-popover")
        create-popover (hiccup/find-by-data-role view-node "subaccounts-create-popover")
        create-cancel (hiccup/find-by-data-role view-node "subaccounts-create-cancel")
        create-input (hiccup/find-by-data-role view-node "subaccounts-create-name")
        create-submit (hiccup/find-by-data-role view-node "subaccounts-create-submit")
        rename-button (hiccup/find-by-data-role view-node
                                                (str "subaccounts-rename-" subaccount-address))
        rename-input (hiccup/find-by-data-role view-node
                                               (str "subaccounts-rename-name-" subaccount-address))
        rename-submit (hiccup/find-by-data-role view-node
                                                (str "subaccounts-rename-submit-" subaccount-address))
        transfer-button (hiccup/find-by-data-role view-node
                                                  (str "subaccounts-transfer-" other-subaccount-address))
        transfer-amount (hiccup/find-by-data-role view-node
                                                  (str "subaccounts-transfer-amount-" other-subaccount-address))
        transfer-popover (hiccup/find-by-data-role view-node
                                                   (str "subaccounts-transfer-popover-" other-subaccount-address))
        transfer-source (hiccup/find-by-data-role view-node
                                                 (str "subaccounts-transfer-source-" other-subaccount-address))
        transfer-destination (hiccup/find-by-data-role view-node
                                                      (str "subaccounts-transfer-destination-" other-subaccount-address))
        transfer-max (hiccup/find-by-data-role view-node
                                               (str "subaccounts-transfer-max-" other-subaccount-address))
        transfer-token (hiccup/find-by-data-role view-node
                                                 (str "subaccounts-transfer-token-" other-subaccount-address))
        transfer-token-menu (hiccup/find-by-data-role view-node
                                                      (str "subaccounts-transfer-token-menu-" other-subaccount-address))
        transfer-meow-option (hiccup/find-by-data-role view-node
                                                       (str "subaccounts-transfer-token-option-" other-subaccount-address "-MEOW:0xdef"))
        transfer-toggle (hiccup/find-by-data-role view-node
                                                  (str "subaccounts-transfer-toggle-direction-" other-subaccount-address))
        transfer-flow-arrow (hiccup/find-by-data-role view-node
                                                       (str "subaccounts-transfer-flow-arrow-" other-subaccount-address))
        transfer-direction (hiccup/find-by-data-role view-node
                                                     (str "subaccounts-transfer-direction-" other-subaccount-address))
        transfer-account-menu (hiccup/find-by-data-role view-node
                                                        (str "subaccounts-transfer-account-menu-" other-subaccount-address))
        transfer-trading-option (hiccup/find-by-data-role view-node
                                                          (str "subaccounts-transfer-account-option-"
                                                               other-subaccount-address
                                                               "-trading"))
        transfer-submit (hiccup/find-by-data-role view-node
                                                  (str "subaccounts-transfer-submit-" other-subaccount-address))]
    (is (= "New Desk" (get-in create-input [1 :value])))
    (is (= [[:actions/open-subaccount-create-popover]]
           (get-in create-open [1 :on :click])))
    (is (some? create-popover))
    (is (= [[:actions/close-subaccount-create-popover]]
           (get-in create-cancel [1 :on :click])))
    (is (= [[:actions/set-subaccount-form-field :create-name [:event.target/value]]]
           (get-in create-input [1 :on :input])))
    (is (= [[:actions/submit-create-subaccount]]
           (get-in create-submit [1 :on :click])))
    (is (= [[:actions/start-rename-subaccount subaccount-address]]
           (get-in rename-button [1 :on :click])))
    (is (= "Desk B" (get-in rename-input [1 :value])))
    (is (= [[:actions/set-subaccount-form-field :rename-name [:event.target/value]]]
           (get-in rename-input [1 :on :input])))
    (is (= [[:actions/submit-rename-subaccount subaccount-address]]
           (get-in rename-submit [1 :on :click])))
    (is (= [[:actions/start-transfer-subaccount other-subaccount-address]]
           (get-in transfer-button [1 :on :click])))
    (is (some? transfer-popover))
    (is (contains? (set (hiccup/collect-strings transfer-popover)) "Send Tokens"))
    (is (contains? (set (hiccup/collect-strings transfer-popover))
                   "Transfer tokens between sub-account and master account."))
    (is (contains? (set (hiccup/collect-strings transfer-source)) "Ops"))
    (is (contains? (set (hiccup/collect-strings transfer-destination)) "Master Account"))
    (is (contains? (set (hiccup/collect-strings transfer-max)) "MAX: 0.02 MEOW"))
    (is (contains? (set (hiccup/collect-strings transfer-flow-arrow)) "->"))
    (is (= "Reverse transfer direction"
           (get-in transfer-toggle [1 :aria-label])))
    (is (= [[:actions/toggle-transfer-direction]]
           (get-in transfer-toggle [1 :on :click])))
    (is (contains? (set (hiccup/collect-strings transfer-token)) "MEOW"))
    (is (some? transfer-token-menu))
    (is (contains? (set (hiccup/collect-strings transfer-token-menu)) "MEOW"))
    (is (contains? (set (hiccup/collect-strings transfer-token-menu)) "0.02"))
    (is (= [[:actions/set-subaccount-form-field :transfer-token "MEOW:0xdef"]]
           (get-in transfer-meow-option [1 :on :click])))
    (is (= "1.23" (get-in transfer-amount [1 :value])))
    (is (= [[:actions/set-subaccount-form-field :transfer-amount [:event.target/value]]]
           (get-in transfer-amount [1 :on :input])))
    (is (= :button (first transfer-direction)))
    (is (= "listbox" (get-in transfer-direction [1 :aria-haspopup])))
    (is (= "true" (get-in transfer-direction [1 :aria-expanded])))
    (is (contains? (set (hiccup/collect-strings transfer-direction)) "Spot Account"))
    (is (some? transfer-account-menu))
    (is (contains? (set (hiccup/collect-strings transfer-account-menu)) "Trading Account"))
    (is (= [[:actions/set-subaccount-form-field :transfer-account :trading]]
           (get-in transfer-trading-option [1 :on :click])))
    (is (= [[:actions/submit-transfer-subaccount other-subaccount-address]]
           (get-in transfer-submit [1 :on :click])))))

(deftest subaccounts-view-renders-spectated-master-read-only-test
  (let [view-node (view/subaccounts-view
                   (-> (base-state)
                       (assoc-in [:account-context :spectate-mode]
                                 {:active? true
                                  :address spectate-address})
                       (assoc-in [:account-context :subaccounts :loaded-for-owner]
                                 spectate-address)
                       (assoc-in [:account-context :subaccounts :selected-address]
                                 nil)
                       (assoc-in [:account-context :subaccounts :rows]
                                 [{:name "Spectated Desk"
                                   :master spectate-address
                                   :sub-account-user subaccount-address
                                   :clearinghouse-state
                                   {:marginSummary {:accountValue "123.45"}}
                                   :spot-state
                                   {:balances [{:coin "USDC"
                                                :total "250.25"}]}}])))
        strings (set (hiccup/collect-strings view-node))
        refresh-button (hiccup/find-by-data-role view-node "subaccounts-refresh")
        create-open (hiccup/find-by-data-role view-node "subaccounts-open-create-popover")
        copy-master (hiccup/find-by-data-role view-node "subaccounts-copy-master")
        master-trade (hiccup/find-by-data-role view-node "subaccounts-select-master")
        subaccount-row (hiccup/find-by-data-role view-node
                                                 (str "subaccounts-row-" subaccount-address))
        subaccount-trade (hiccup/find-by-data-role view-node
                                                   (str "subaccounts-select-" subaccount-address))
        rename-button (hiccup/find-by-data-role view-node
                                                (str "subaccounts-rename-" subaccount-address))
        transfer-button (hiccup/find-by-data-role view-node
                                                  (str "subaccounts-transfer-" subaccount-address))]
    (is (contains? strings account-context/spectate-mode-read-only-message))
    (is (contains? strings "Spectated Desk"))
    (is (contains? strings "$123.45"))
    (is (contains? strings "$250.25"))
    (is (some? subaccount-row))
    (is (false? (get-in refresh-button [1 :disabled])))
    (is (= [[:actions/copy-subaccount-address spectate-address]]
           (get-in copy-master [1 :on :click])))
    (is (true? (get-in create-open [1 :disabled])))
    (is (true? (get-in master-trade [1 :disabled])))
    (is (true? (get-in subaccount-trade [1 :disabled])))
    (is (nil? rename-button))
    (is (nil? transfer-button))))

(deftest unified-subaccounts-transfer-popover-offers-a-single-spot-option-test
  (let [view-node (view/subaccounts-view
                   (-> (base-state)
                       (assoc :account {:mode :unified})
                       (assoc-in [:spot :clearinghouse-state :balances]
                                 [{:coin "USDC"
                                   :available "301.12859"
                                   :total "301.12859"
                                   :hold "0"}
                                  {:coin "USDH"
                                   :token "USDH:0xabc"
                                   :total "8.25"}])
                       (assoc-in [:webdata2 :clearinghouseState]
                                 {:withdrawable "0.01"
                                  :marginSummary {:accountValue "999.99"
                                                  :totalMarginUsed "0"}})
                       (assoc-in [:account-context :subaccounts :owner-snapshot]
                                 {:owner owner-address
                                  :clearinghouse-state
                                  {:withdrawable "0.01"
                                   :marginSummary {:accountValue "999.99"
                                                   :totalMarginUsed "0"}}
                                  :spot-state {:balances [{:coin "USDC"
                                                           :available "301.12859"
                                                           :total "301.12859"
                                                           :hold "0"}]}
                                  :loading? false
                                  :error nil})
                       (assoc-in [:account-context :subaccounts :transfer-account] :spot)
                       (assoc-in [:account-context :subaccounts :transfer-account-menu-open?] true)
                       (assoc-in [:account-context :subaccounts :transferring-address] subaccount-address)))
        transfer-direction (hiccup/find-by-data-role view-node
                                                     (str "subaccounts-transfer-direction-" subaccount-address))
        transfer-account-menu (hiccup/find-by-data-role view-node
                                                        (str "subaccounts-transfer-account-menu-" subaccount-address))
        transfer-spot-option (hiccup/find-by-data-role view-node
                                                       (str "subaccounts-transfer-account-option-"
                                                            subaccount-address
                                                            "-spot"))
        transfer-trading-option (hiccup/find-by-data-role view-node
                                                          (str "subaccounts-transfer-account-option-"
                                                               subaccount-address
                                                               "-trading"))
        transfer-max (hiccup/find-by-data-role view-node
                                               (str "subaccounts-transfer-max-" subaccount-address))]
    (is (contains? (set (hiccup/collect-strings transfer-direction))
                   "Spot Account"))
    (is (contains? (set (hiccup/collect-strings transfer-account-menu))
                   "Spot Account"))
    (is (not (contains? (set (hiccup/collect-strings transfer-account-menu))
                        "Trading Account")))
    (is (some? transfer-spot-option))
    (is (nil? transfer-trading-option))
    (is (contains? (set (hiccup/collect-strings transfer-max))
                   "MAX: 301.12859 USDC"))))
