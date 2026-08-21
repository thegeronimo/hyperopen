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
   ;; Production shape. A `spotClearinghouseState` balance row carries `:token`
   ;; as a NUMERIC INDEX; the `NAME:0x<hash>` wire token id exists only in
   ;; spotMeta, joined by index. Fixtures that put a prejoined wire id in the
   ;; balance row let `resolve-with` short-circuit on its first clause and hid a
   ;; production bug where `[:spot :meta]` was never populated at all.
   :spot {:meta {:tokens [{:name "USDC" :index 0 :tokenId "0x6d1e7cde53ba9467b783cb7c530ce054"}
                          {:name "USDH" :index 34 :tokenId "0xabc"}
                          {:name "MEOW" :index 77 :tokenId "0xdef"}]}
          :clearinghouse-state {:balances [{:coin "USDC"
                                            :token 0
                                            :total "50.50"}
                                           {:coin "USDH"
                                            :token 34
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
                                       :token 77
                                       :total "0.02"}]}}
            {:name "Ops"
             :master owner-address
             :sub-account-user other-subaccount-address
             :clearinghouse-state {:marginSummary {:accountValue "0"}}
             :spot-state {:balances [{:coin "MEOW"
                                       :token 77
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

(deftest transfer-token-option-is-disabled-only-when-spot-metadata-is-missing-test
  ;; Regression: this is the reported symptom. Hyperliquid names a spot token on
  ;; the wire as `NAME:0x<hash>`, and spotMeta is the only source that joins a
  ;; balance row's numeric token index to that id. When `[:spot :meta]` is nil
  ;; the join fails for every token except USDC (which alone has a hard-coded
  ;; mainnet constant to fall back on), so the dropdown listed the user's real
  ;; HYPE balance as "unavailable" and refused to select it.
  (let [open-token-menu (fn [state]
                          (-> state
                              (assoc-in [:account-context :subaccounts :transfer-direction] :withdraw)
                              (assoc-in [:account-context :subaccounts :transfer-account] :spot)
                              (assoc-in [:account-context :subaccounts :transfer-token-menu-open?] true)
                              (assoc-in [:account-context :subaccounts :transferring-address]
                                        other-subaccount-address)))
        option-for (fn [state]
                     (hiccup/find-by-data-role
                      (view/subaccounts-view (open-token-menu state))
                      (str "subaccounts-transfer-token-option-" other-subaccount-address "-MEOW:0xdef")))
        resolved (option-for (base-state))
        unresolved-menu (hiccup/find-by-data-role
                         (view/subaccounts-view
                          (open-token-menu (assoc-in (base-state) [:spot :meta] nil)))
                         (str "subaccounts-transfer-token-menu-" other-subaccount-address))]
    (is (some? resolved)
        "With spotMeta loaded the balance index joins to a wire token id.")
    (is (not (true? (get-in resolved [1 :disabled])))
        "A resolved token must be selectable.")
    (is (= [[:actions/set-subaccount-form-field :transfer-token "MEOW:0xdef"]]
           (get-in resolved [1 :on :click]))
        "Selecting it must carry the joined wire token id, never the bare index.")
    (is (nil? (option-for (assoc-in (base-state) [:spot :meta] nil)))
        "Without spotMeta the option cannot be keyed by a wire token id at all.")
    (is (contains? (set (hiccup/collect-strings unresolved-menu)) "unavailable")
        "Without spotMeta the token is listed but disabled, rather than hidden or signed with a rejected identifier.")))

(deftest outcome-position-rows-are-not-offered-as-transferable-tokens-test
  ;; Regression from live mainnet data (master 0x49208e12..., 2026-08-21):
  ;; `spotClearinghouseState` returns outcome (prediction-market) positions in the
  ;; same :balances array as real spot tokens. They carry NO :token field and have
  ;; no spotMeta entry, so they can never resolve to a `NAME:0x<hash>` wire token
  ;; id and can never travel the spot sendAsset path. Listing them disabled with a
  ;; "refresh balances" hint promises a recovery that cannot happen; a real token
  ;; that merely has not resolved yet must still be listed, because that IS
  ;; transient.
  (let [state (-> (base-state)
                  (assoc-in [:account-context :subaccounts :owner-snapshot :spot-state :balances]
                            [{:coin "USDC" :token 0 :total "0.00426478" :hold "0.0"}
                             {:coin "HYPE" :token 150 :total "101.20315319" :hold "0.0"}
                             {:coin "o458" :total "0.0" :hold "0.0"}
                             {:coin "o459" :total "0.0" :hold "0.0"}])
                  (assoc-in [:spot :meta :tokens]
                            [{:name "USDC" :index 0 :tokenId "0x6d1e7cde53ba9467b783cb7c530ce054"}
                             {:name "HYPE" :index 150 :tokenId "0x0d01dc56dcaaca66ad901c959b4011ec"}])
                  (assoc-in [:account-context :subaccounts :transfer-direction] :deposit)
                  (assoc-in [:account-context :subaccounts :transfer-account] :spot)
                  (assoc-in [:account-context :subaccounts :transfer-token-menu-open?] true)
                  (assoc-in [:account-context :subaccounts :transferring-address] subaccount-address))
        menu (hiccup/find-by-data-role (view/subaccounts-view state)
                                       (str "subaccounts-transfer-token-menu-" subaccount-address))
        strings (set (hiccup/collect-strings menu))]
    (is (contains? strings "HYPE")
        "A real spot token resolves through spotMeta and is offered.")
    (is (contains? strings "101.203153")
        "It is offered with its real balance, not the word \"unavailable\".")
    (is (not (contains? strings "o458"))
        "Outcome positions are not spot tokens and must not be offered at all.")
    (is (not (contains? strings "o459")))
    (is (not (contains? strings "unavailable"))
        "Nothing in this list is merely disabled: every offered row genuinely resolved.")
    (is (contains? (set (hiccup/collect-strings
                         (hiccup/find-by-data-role
                          (view/subaccounts-view
                           (assoc-in state
                                     [:account-context :subaccounts :owner-snapshot :spot-state :balances]
                                     [{:coin "USDC" :total "5.0" :hold "0"}
                                      {:coin "o458" :total "0.0" :hold "0.0"}]))
                          (str "subaccounts-transfer-token-menu-" subaccount-address))))
                   "USDC")
        "A row missing its index but still nameable (USDC has a known mainnet id) must survive the outcome-row exclusion.")))

(defn- live-balance-state
  "The reporting master's real balance mix (0x49208e12..., 2026-08-21): two
   sendable tokens, four fully-withdrawn ones at exactly zero."
  []
  (-> (base-state)
      (assoc-in [:account-context :subaccounts :owner-snapshot :spot-state :balances]
                [{:coin "USDC" :token 0 :total "0.00426478" :hold "0.0"}
                 {:coin "KHYPE" :token 121 :total "0.0" :hold "0.0"}
                 {:coin "HYPE" :token 150 :total "101.20315319" :hold "0.0"}
                 {:coin "USDT0" :token 268 :total "0.0" :hold "0.0"}
                 {:coin "UENA" :token 338 :total "0.0" :hold "0.0"}
                 {:coin "USDH" :token 360 :total "0.0" :hold "0.0"}])
      (assoc-in [:spot :meta :tokens]
                [{:name "USDC" :index 0 :tokenId "0x6d1e7cde53ba9467b783cb7c530ce054"}
                 {:name "KHYPE" :index 121 :tokenId "0xbc8a"}
                 {:name "HYPE" :index 150 :tokenId "0x0d01"}
                 {:name "USDT0" :index 268 :tokenId "0x25fa"}
                 {:name "UENA" :index 338 :tokenId "0x5934"}
                 {:name "USDH" :index 360 :tokenId "0x54e0"}])
      (assoc-in [:account-context :subaccounts :transfer-direction] :deposit)
      (assoc-in [:account-context :subaccounts :transfer-account] :spot)
      (assoc-in [:account-context :subaccounts :transfer-token-menu-open?] true)
      (assoc-in [:account-context :subaccounts :transferring-address] subaccount-address)))

(defn- token-menu-strings
  [state]
  (set (hiccup/collect-strings
        (hiccup/find-by-data-role (view/subaccounts-view state)
                                  (str "subaccounts-transfer-token-menu-" subaccount-address)))))

(deftest token-dropdown-hides-zero-balance-tokens-until-the-user-asks-for-them-test
  (let [hidden (token-menu-strings (live-balance-state))
        shown (token-menu-strings (assoc-in (live-balance-state)
                                            [:account-context :subaccounts :show-zero-balances?]
                                            true))]
    (is (contains? hidden "HYPE")
        "A sendable balance is always offered.")
    (is (contains? hidden "USDC"))
    (is (not (contains? hidden "KHYPE"))
        "A zero balance can never be sent (Send is disabled for it), so it is noise by default.")
    (is (not (contains? hidden "USDT0")))
    (is (not (contains? hidden "UENA")))
    (is (not (contains? hidden "USDH")))
    (is (every? shown ["USDC" "KHYPE" "HYPE" "USDT0" "UENA" "USDH"])
        "Checking the box reveals every row, sendable or not.")))

(deftest zero-balance-toggle-names-what-it-is-hiding-test
  (let [view-node (view/subaccounts-view (live-balance-state))
        label (hiccup/find-by-data-role
               view-node (str "subaccounts-transfer-show-zero-label-" subaccount-address))
        checkbox (hiccup/find-by-data-role
                  view-node (str "subaccounts-transfer-show-zero-" subaccount-address))
        checked-node (hiccup/find-by-data-role
                      (view/subaccounts-view
                       (assoc-in (live-balance-state)
                                 [:account-context :subaccounts :show-zero-balances?] true))
                      (str "subaccounts-transfer-show-zero-" subaccount-address))]
    (is (contains? (set (hiccup/collect-strings label)) "Show 4 zero-balance tokens")
        "Naming the count answers the question a shortened list provokes.")
    (is (false? (get-in checkbox [1 :checked])))
    (is (= [[:actions/set-subaccount-form-field :show-zero-balances? true]]
           (get-in checkbox [1 :on :change])))
    (is (true? (get-in checked-node [1 :checked])))
    (is (= [[:actions/set-subaccount-form-field :show-zero-balances? false]]
           (get-in checked-node [1 :on :change]))
        "Once shown, the same control must turn them back off.")))

(deftest zero-balance-toggle-is-absent-when-it-would-change-nothing-test
  (let [all-sendable (-> (live-balance-state)
                         (assoc-in [:account-context :subaccounts :owner-snapshot :spot-state :balances]
                                   [{:coin "USDC" :token 0 :total "5.0" :hold "0.0"}
                                    {:coin "HYPE" :token 150 :total "101.20315319" :hold "0.0"}]))]
    (is (nil? (hiccup/find-by-data-role (view/subaccounts-view all-sendable)
                                        (str "subaccounts-transfer-show-zero-" subaccount-address)))
        "With nothing to hide the checkbox is clutter.")))

(deftest all-zero-balances-still-render-rather-than-emptying-the-dropdown-test
  ;; An empty dropdown would leave `selected-transfer-token` on its bare-symbol
  ;; placeholder, and a bare symbol is not a sendable wire token id.
  (let [all-zero (-> (live-balance-state)
                     (assoc-in [:account-context :subaccounts :owner-snapshot :spot-state :balances]
                               [{:coin "USDC" :token 0 :total "0.0" :hold "0.0"}
                                {:coin "HYPE" :token 150 :total "0.0" :hold "0.0"}]))
        strings (token-menu-strings all-zero)]
    (is (contains? strings "USDC"))
    (is (contains? strings "HYPE"))
    (is (nil? (hiccup/find-by-data-role (view/subaccounts-view all-zero)
                                        (str "subaccounts-transfer-show-zero-" subaccount-address)))
        "The fallback shows everything, so the checkbox must not claim to hide rows that are on screen.")))

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
                                   :token 34
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
