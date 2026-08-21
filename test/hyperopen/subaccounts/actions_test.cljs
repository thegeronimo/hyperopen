(ns hyperopen.subaccounts.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.subaccounts.actions :as actions]))

(def owner-address "0x1234567890abcdef1234567890abcdef12345678")

(def subaccount-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def other-subaccount-address
  "0x9999999999999999999999999999999999999999")

(def spectate-address
  "0x7777777777777777777777777777777777777777")

(defn- path-value
  [effects path]
  (let [[_ path-values] (first effects)
        missing (js-obj)]
    (let [value (reduce (fn [result [candidate-path candidate-value]]
                          (if (= path candidate-path)
                            (reduced candidate-value)
                            result))
                        missing
                        path-values)]
      (when-not (identical? missing value)
        value))))

(defn- base-management-state
  []
  {:wallet {:address owner-address}
   :account-context
   {:subaccounts {:rows [{:name "Desk"
                          :master owner-address
                          :sub-account-user subaccount-address}
                         {:name "Ops"
                          :master owner-address
                          :sub-account-user other-subaccount-address}]
                  :create-name ""
                  :rename-name ""
                  :transfer-amount ""
                  :transfer-direction :deposit
                  :transfer-account :trading
                  :transfer-account-menu-open? false
                  :transfer-token "USDC"
                  :transfer-token-menu-open? false}}
   ;; Production `spotMeta` shape: :name, :index, and :tokenId arrive
   ;; separately; Hyperliquid never returns a prejoined "NAME:0x..." string.
   :spot {:meta {:tokens [{:name "USDH"
                           :index 12
                           :tokenId "0xabc"
                           :szDecimals 2
                           :weiDecimals 6}
                          {:name "MEOW"
                           :index 34
                           :tokenId "0xdef"
                           :szDecimals 0
                           :weiDecimals 2}]}}})

(deftest parse-subaccounts-route-supports-hyperliquid-casing-test
  (is (= "/subAccounts" actions/canonical-route))
  (is (= {:kind :page
          :path "/subAccounts"}
         (actions/parse-subaccounts-route "/subAccounts")))
  (is (= {:kind :page
          :path "/subAccounts"}
         (actions/parse-subaccounts-route "/SUBACCOUNTS?tab=manage")))
  (is (= :other (:kind (actions/parse-subaccounts-route "/trade")))))

(deftest parse-subaccounts-route-normalizes-trailing-slashes-and-non-string-input-test
  (is (= {:kind :page
          :path "/subAccounts"}
         (actions/parse-subaccounts-route " /subaccounts///?tab=keys#section ")))
  (is (= {:kind :other
          :path "42"}
         (actions/parse-subaccounts-route 42)))
  (is (true? (actions/subaccounts-route? "/subaccounts////")))
  (is (= {:kind :other
          :path ""}
         (actions/parse-subaccounts-route nil))))

(deftest load-subaccounts-route-emits-projection-before-heavy-load-test
  (let [effects (actions/load-subaccounts-route {:wallet {:address owner-address}}
                                                "/subaccounts")]
    (is (= :effects/save-many (ffirst effects)))
    (is (= :loading (path-value effects [:account-context :subaccounts :status])))
    (is (= [] (path-value effects [:account-context :subaccounts :rows])))
    (is (= owner-address
           (path-value effects [:account-context :subaccounts :loaded-for-owner])))
    (is (nil? (path-value effects [:account-context :subaccounts :error])))
    (is (= [:effects/api-load-subaccounts]
           (second effects)))))

(deftest load-subaccounts-route-uses-spectated-master-address-test
  (let [effects (actions/load-subaccounts-route
                 {:wallet {:address owner-address}
                  :account-context
                  {:spectate-mode {:active? true
                                    :address spectate-address}}}
                 "/subaccounts")]
    (is (= :loading (path-value effects [:account-context :subaccounts :status])))
    (is (= spectate-address
           (path-value effects [:account-context :subaccounts :loaded-for-owner])))
    (is (= [:effects/api-load-subaccounts]
           (second effects)))))

(deftest load-subaccounts-route-skips-heavy-load-when-route-is-inactive-or-owner-missing-test
  (is (= []
         (actions/load-subaccounts-route {:wallet {:address owner-address}}
                                         "/trade")))
  (let [effects (actions/load-subaccounts-route {:wallet {:address nil}
                                                :account-context
                                                {:subaccounts {:rows [{:name "Desk"}]
                                                               :selected-address subaccount-address}}}
                                               "/subAccounts")]
    (is (= [[:effects/save-many [[[:account-context :subaccounts :status] :idle]
                                 [[:account-context :subaccounts :loaded-for-owner] nil]
                                 [[:account-context :subaccounts :owner-mode] nil]
                                 [[:account-context :subaccounts :owner-snapshot] nil]
                                 [[:account-context :subaccounts :rows] []]
                                 [[:account-context :subaccounts :error] nil]
                                 [[:account-context :subaccounts :refreshing?] false]
                                 [[:account-context :subaccounts :selected-address] nil]
                                 [[:account-context :subaccounts :create-name] ""]
                                 [[:account-context :subaccounts :create-popover-open?] false]
                                 [[:account-context :subaccounts :rename-name] ""]
                                 [[:account-context :subaccounts :transfer-amount] ""]
                                 [[:account-context :subaccounts :transfer-direction] :deposit]
                                 [[:account-context :subaccounts :transfer-account] :trading]
                                 [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                 [[:account-context :subaccounts :transfer-token] "USDC"]
                                 [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                 [[:account-context :subaccounts :show-zero-balances?] false]
                                 [[:account-context :subaccounts :selection-loaded?] false]]]]
           effects))
    (is (= :idle (path-value effects [:account-context :subaccounts :status])))))

(deftest load-subaccounts-route-is-idempotent-for-same-owner-when-loading-or-loaded-test
  (doseq [status [:loading :loaded]]
    (is (= []
           (actions/load-subaccounts-route
            {:wallet {:address owner-address}
             :account-context {:subaccounts {:status status
                                             :loaded-for-owner owner-address
                                             :rows [{:name "Desk"
                                                     :master owner-address
                                                     :sub-account-user subaccount-address}]}}}
            "/subAccounts"))
        (str "Re-entering the route while " status
             " for the same owner must not blank rows or re-fire the load."))))

(deftest load-subaccounts-route-reloads-when-owner-differs-or-previous-load-errored-test
  (let [error-effects (actions/load-subaccounts-route
                       {:wallet {:address owner-address}
                        :account-context {:subaccounts {:status :error
                                                        :loaded-for-owner owner-address
                                                        :error "boom"}}}
                       "/subAccounts")]
    (is (= :loading (path-value error-effects [:account-context :subaccounts :status])))
    (is (= [:effects/api-load-subaccounts] (second error-effects))))
  (let [stale-owner-effects (actions/load-subaccounts-route
                             {:wallet {:address owner-address}
                              :account-context {:subaccounts {:status :loaded
                                                              :loaded-for-owner other-subaccount-address
                                                              :rows [{:name "Stale"}]}}}
                             "/subAccounts")]
    (is (= :loading (path-value stale-owner-effects [:account-context :subaccounts :status])))
    (is (= [:effects/api-load-subaccounts] (second stale-owner-effects)))))

(deftest refresh-subaccounts-force-loads-without-clearing-rows-test
  (let [effects (actions/refresh-subaccounts
                 {:wallet {:address owner-address}
                  :account-context {:subaccounts {:status :loaded
                                                  :loaded-for-owner owner-address
                                                  :rows [{:name "Desk"
                                                          :master owner-address
                                                          :sub-account-user subaccount-address}]}}})]
    (is (= :effects/save-many (ffirst effects)))
    (is (true? (path-value effects [:account-context :subaccounts :refreshing?])))
    (is (= owner-address
           (path-value effects [:account-context :subaccounts :loaded-for-owner])))
    (is (nil? (path-value effects [:account-context :subaccounts :error])))
    (is (nil? (path-value effects [:account-context :subaccounts :status]))
        "Refresh must not touch :status, so the rendered rows stay visible.")
    (is (nil? (path-value effects [:account-context :subaccounts :rows]))
        "Refresh must not clear the rendered rows.")
    (is (= [:effects/api-refresh-subaccounts] (second effects)))))

(deftest refresh-subaccounts-reloads-spot-metadata-when-missing-test
  ;; Regression: the Send Tokens dialog disables any token it cannot name on the
  ;; wire and tells the user to refresh balances, but
  ;; `:effects/api-refresh-subaccounts` only reloads clearinghouse state — it
  ;; never touches `[:spot :meta]`. Without this the advertised remedy was a
  ;; guaranteed no-op and a failed catalog load could not be recovered from
  ;; without a full page reload.
  (let [state {:wallet {:address owner-address}
               :account-context {:subaccounts {:status :loaded
                                               :loaded-for-owner owner-address
                                               :rows []}}}
        missing-meta (actions/refresh-subaccounts state)
        loaded-meta (actions/refresh-subaccounts
                     (assoc state :spot {:meta {:tokens []}}))]
    (is (= [:effects/fetch-asset-selector-markets {:phase :full}]
           (last missing-meta)))
    (is (not-any? #{:effects/fetch-asset-selector-markets} (map first loaded-meta))
        "Once spot metadata is loaded, Refresh must not re-request the catalog.")
    (is (= [:effects/api-refresh-subaccounts] (second loaded-meta))
        "The ordinary refresh path is unchanged.")))

(deftest refresh-subaccounts-requires-connected-owner-test
  (is (= [[:effects/save [:account-context :subaccounts :error]
           actions/missing-owner-message]]
         (actions/refresh-subaccounts {:wallet {:address nil}}))))

(deftest select-subaccount-validates-owner-and-persists-selected-address-test
  (let [state {:wallet {:address owner-address}
               :account-context {:subaccounts
                                 {:rows [{:name "Desk"
                                          :master owner-address
                                          :sub-account-user subaccount-address}]}}}]
    (is (= [[:effects/save-many [[[:account-context :subaccounts :selected-address]
                                  subaccount-address]
                                 [[:account-context :subaccounts :error] nil]]]
            [:effects/local-storage-set
             (actions/selected-subaccount-storage-key owner-address)
             subaccount-address]
            [:effects/api-load-user-data subaccount-address]]
           (actions/select-subaccount state (str "  " subaccount-address "  "))))
    (is (= [[:effects/save [:account-context :subaccounts :error]
             "Select a subaccount owned by the connected master wallet."]]
           (actions/select-subaccount state other-subaccount-address)))))

(deftest select-master-account-clears-selection-and-refreshes-user-data-test
  (is (= [[:effects/save-many [[[:account-context :subaccounts :selected-address] nil]
                               [[:account-context :subaccounts :error] nil]]]
          [:effects/local-storage-set
           (actions/selected-subaccount-storage-key owner-address)
           ""]
          [:effects/api-load-user-data owner-address]]
         (actions/select-master-account
          {:wallet {:address owner-address}
           :account-context {:subaccounts {:selected-address subaccount-address}}}))))

(deftest selection-actions-require-connected-owner-test
  (is (= [[:effects/save [:account-context :subaccounts :error]
           "Connect your wallet before selecting a subaccount."]]
         (actions/select-subaccount {:wallet {:address nil}} subaccount-address)))
  (is (= [[:effects/save [:account-context :subaccounts :error]
           "Connect your wallet before selecting a subaccount."]]
         (actions/select-master-account {:wallet {:address nil}}))))

(deftest selection-actions-are-read-only-in-spectate-mode-test
  (let [state {:wallet {:address owner-address}
               :account-context
               {:spectate-mode {:active? true
                                 :address spectate-address}
                :subaccounts
                {:rows [{:name "Desk"
                         :master spectate-address
                         :sub-account-user subaccount-address}]}}}]
    (is (= [[:effects/save [:account-context :subaccounts :error]
             account-context/spectate-mode-read-only-message]]
           (actions/select-subaccount state subaccount-address)))
    (is (= [[:effects/save [:account-context :subaccounts :error]
             account-context/spectate-mode-read-only-message]]
           (actions/select-master-account state)))))

(deftest subaccount-management-form-fields-normalize-supported-inputs-test
  (is (= [[:effects/save-many [[[:account-context :subaccounts :create-name] " Desk "]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} :create-name " Desk ")))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transfer-direction] :withdraw]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} "transferDirection" "withdraw")))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transfer-account] :spot]
                                [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} "transferAccount" "spot")))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transfer-account-menu-open?] true]
                                [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} :transfer-account-menu-open? true)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transfer-token] "USDH:0xabc"]
                                [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} :transfer-token "USDH:0xabc")))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transfer-token-menu-open?] true]
                                [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} :transfer-token-menu-open? true)))
  (is (= []
         (actions/set-subaccount-form-field {} :unknown "value"))))

(deftest show-zero-balances-is-stored-as-a-boolean-and-leaves-the-menu-open-test
  ;; The default branch of `normalize-form-value` stringifies, which would store
  ;; "false" -- truthy in CLJS -- and pin the list permanently open. And unlike
  ;; the token/account fields, this one must NOT close the dropdown: the user
  ;; toggles it from inside the open menu to reveal the rows it is hiding.
  (is (= [[:effects/save-many [[[:account-context :subaccounts :show-zero-balances?] true]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} :show-zero-balances? true)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :show-zero-balances?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} :show-zero-balances? false)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :show-zero-balances?] true]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field {} "showZeroBalances" "true"))
      "The wire/string form normalizes the same way every other boolean field does."))

(deftest unified-subaccount-transfer-account-selection-resolves-to-spot-test
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transfer-account] :spot]
                                [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/set-subaccount-form-field
          (assoc (base-management-state) :account {:mode :unified})
          "transferAccount"
          "spot"))))

(deftest toggle-transfer-direction-swaps-master-and-subaccount-sides-test
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transfer-direction] :withdraw]
                                [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/toggle-transfer-direction
          (assoc-in (base-management-state)
                    [:account-context :subaccounts :transfer-direction]
                    :deposit))))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transfer-direction] :deposit]
                                [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/toggle-transfer-direction
          (assoc-in (base-management-state)
                    [:account-context :subaccounts :transfer-direction]
                    :withdraw)))))

(deftest subaccount-create-popover-actions-toggle-state-test
  (is (= [[:effects/save-many [[[:account-context :subaccounts :create-popover-open?] true]
                                [[:account-context :subaccounts :create-name] ""]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/open-create-popover {})))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :create-popover-open?] false]
                                [[:account-context :subaccounts :create-name] ""]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/close-create-popover {}))))

(deftest copy-subaccount-address-emits-wallet-copy-effect-test
  (is (= [[:effects/copy-wallet-address subaccount-address]]
         (actions/copy-subaccount-address {} (str " " subaccount-address " ")))))

(deftest subaccount-usdc-amount-parser-uses-raw-micro-usdc-units-test
  (is (= 1230000 (actions/parse-usdc-amount->micros "1.23")))
  (is (= 1 (actions/parse-usdc-amount->micros "0.000001")))
  (is (= 1200000 (actions/parse-usdc-amount->micros "001.2")))
  (is (nil? (actions/parse-usdc-amount->micros "")))
  (is (nil? (actions/parse-usdc-amount->micros "0")))
  (is (nil? (actions/parse-usdc-amount->micros "1.0000001"))))

(deftest submit-create-subaccount-validates-name-and-emits-management-effect-test
  (is (= [[:effects/save-many [[[:account-context :subaccounts :creating?] true]
                                [[:account-context :subaccounts :create-popover-open?] true]
                                [[:account-context :subaccounts :error] nil]]]
          [:effects/api-create-subaccount {:name "Desk"}]]
         (actions/submit-create-subaccount
          (assoc-in (base-management-state)
                    [:account-context :subaccounts :create-name]
                    " Desk "))))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :creating?] false]
                                [[:account-context :subaccounts :error]
                                 "Subaccount name must be 1-16 characters."]]]]
         (actions/submit-create-subaccount
          (assoc-in (base-management-state)
                    [:account-context :subaccounts :create-name]
                    "this-name-is-too-long"))))
  (is (= [[:effects/save [:account-context :subaccounts :error]
           "Connect your wallet before selecting a subaccount."]]
         (actions/submit-create-subaccount {:wallet {:address nil}}))))

(deftest management-actions-are-read-only-in-spectate-mode-test
  (let [state (assoc-in (base-management-state)
                        [:account-context :spectate-mode]
                        {:active? true
                         :address spectate-address})
        blocked [[:effects/save [:account-context :subaccounts :error]
                  account-context/spectate-mode-read-only-message]]]
    (is (= blocked (actions/open-create-popover state)))
    (is (= blocked (actions/submit-create-subaccount state)))
    (is (= blocked (actions/start-rename-subaccount state subaccount-address)))
    (is (= blocked (actions/submit-rename-subaccount state subaccount-address)))
    (is (= blocked (actions/start-transfer-subaccount state subaccount-address)))
    (is (= blocked (actions/submit-transfer-subaccount
                    (assoc-in state
                              [:account-context :subaccounts :transfer-amount]
                              "1.23")
                    subaccount-address)))))

(deftest rename-subaccount-actions-validate-ownership-and-emit-management-effect-test
  (is (= [[:effects/save-many [[[:account-context :subaccounts :renaming-address]
                                 subaccount-address]
                                [[:account-context :subaccounts :rename-name] "Desk"]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/start-rename-subaccount (base-management-state)
                                          subaccount-address)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :renaming-address] nil]
                                [[:account-context :subaccounts :rename-name] ""]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/cancel-rename-subaccount {})))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :renaming-address]
                                 subaccount-address]
                                [[:account-context :subaccounts :error] nil]]]
          [:effects/api-rename-subaccount {:sub-account-user subaccount-address
                                           :name "Ops"}]]
         (actions/submit-rename-subaccount
          (assoc-in (base-management-state)
                    [:account-context :subaccounts :rename-name]
                    " Ops ")
          subaccount-address)))
  (is (= [[:effects/save [:account-context :subaccounts :error]
           "Select a subaccount owned by the connected master wallet."]]
         (actions/start-rename-subaccount (base-management-state)
                                          "0x0000000000000000000000000000000000000000"))))

(deftest transfer-subaccount-actions-validate-amount-and-emit-management-effect-test
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transferring-address]
                                 subaccount-address]
                                [[:account-context :subaccounts :transfer-amount] ""]
                                [[:account-context :subaccounts :transfer-direction] :deposit]
                                [[:account-context :subaccounts :transfer-account] :trading]
                                [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                [[:account-context :subaccounts :transfer-token] "USDC"]
                                [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/start-transfer-subaccount (base-management-state)
                                            subaccount-address)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transferring-address] nil]
                                [[:account-context :subaccounts :transfer-amount] ""]
                                [[:account-context :subaccounts :transfer-direction] :deposit]
                                [[:account-context :subaccounts :transfer-account] :trading]
                                [[:account-context :subaccounts :transfer-account-menu-open?] false]
                                [[:account-context :subaccounts :transfer-token] "USDC"]
                                [[:account-context :subaccounts :transfer-token-menu-open?] false]
                                [[:account-context :subaccounts :error] nil]]]]
         (actions/cancel-transfer-subaccount {})))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transferring-address]
                                 subaccount-address]
                                [[:account-context :subaccounts :error] nil]]]
          [:effects/api-transfer-subaccount {:sub-account-user subaccount-address
                                             :is-deposit true
                                             :usd 1230000
                                             :amount "1.23"
                                             :amount-display "1.23"
                                             :amount-units "1230000"
                                             :amount-decimals 6
                                             :account-kind :trading
                                             :token "USDC"}]]
         (actions/submit-transfer-subaccount
          (-> (base-management-state)
              (assoc-in [:account-context :subaccounts :transfer-amount] "1.23")
              (assoc-in [:account-context :subaccounts :transfer-direction] :deposit))
          subaccount-address)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transferring-address]
                                 subaccount-address]
                                [[:account-context :subaccounts :error] nil]]]
          [:effects/api-transfer-subaccount {:sub-account-user subaccount-address
                                             :is-deposit false
                                             :usd 1
                                             :amount "0.000001"
                                             :amount-display "0.000001"
                                             :amount-units "1"
                                             :amount-decimals 6
                                             :account-kind :trading
                                             :token "USDC"}]]
         (actions/submit-transfer-subaccount
          (-> (base-management-state)
              (assoc-in [:account-context :subaccounts :transfer-amount] "0.000001")
              (assoc-in [:account-context :subaccounts :transfer-direction] :withdraw))
          subaccount-address)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transferring-address]
                                 subaccount-address]
                                [[:account-context :subaccounts :error] nil]]]
          [:effects/api-transfer-subaccount {:sub-account-user subaccount-address
                                             :is-deposit true
                                             :amount "2.5"
                                             :amount-display "2.5"
                                             :amount-units "2500000"
                                             :amount-decimals 6
                                             :account-kind :spot
                                             :token "USDH:0xabc"
                                             :token-symbol "USDH"}]]
         (actions/submit-transfer-subaccount
          (-> (base-management-state)
              (assoc-in [:account-context :subaccounts :transfer-amount] "2.5")
              (assoc-in [:account-context :subaccounts :transfer-account] :spot)
              (assoc-in [:account-context :subaccounts :transfer-token] "USDH:0xabc"))
          subaccount-address)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transferring-address] nil]
                                [[:account-context :subaccounts :error]
                                 "Enter a positive spot amount that matches the selected asset precision."]]]]
         (actions/submit-transfer-subaccount
          (-> (base-management-state)
              (assoc-in [:account-context :subaccounts :transfer-amount] "0.001")
              (assoc-in [:account-context :subaccounts :transfer-account] :spot)
              (assoc-in [:account-context :subaccounts :transfer-token] "MEOW:0xdef"))
          subaccount-address)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transferring-address] nil]
                                [[:account-context :subaccounts :error]
                                 "Spot asset precision is unavailable. Refresh balances before transferring."]]]]
         (actions/submit-transfer-subaccount
          (-> (base-management-state)
              (assoc-in [:spot :meta :tokens] [])
              (assoc-in [:account-context :subaccounts :transfer-amount] "1")
              (assoc-in [:account-context :subaccounts :transfer-account] :spot)
              (assoc-in [:account-context :subaccounts :transfer-token] "UNKNOWN:0xaaa"))
          subaccount-address)))
  (is (= [[:effects/save-many [[[:account-context :subaccounts :transferring-address] nil]
                                [[:account-context :subaccounts :error]
                                 "Enter a positive USDC amount with at most 6 decimal places."]]]]
         (actions/submit-transfer-subaccount
          (-> (base-management-state)
              (assoc-in [:account-context :subaccounts :transfer-amount] "0.0000001"))
          subaccount-address))))
