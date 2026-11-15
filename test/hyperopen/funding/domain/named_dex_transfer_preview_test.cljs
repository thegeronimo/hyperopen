(ns hyperopen.funding.domain.named-dex-transfer-preview-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.domain.availability :as availability]
            [hyperopen.funding.domain.policy :as policy]))

(defn- named-dex-state
  []
  {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
   :spot {:meta {:tokens [{:index 0
                           :name "USDC"
                           :tokenId "0x6d1e7cde53ba9467b783cb7c530ce054"}]}
          :clearinghouse-state {:balances [{:coin "USDC"
                                            :available "78.22"
                                            :total "78.22"
                                            :hold "0"}]}}
   :webdata2 {:clearinghouseState {:withdrawable "0.0"
                                   :marginSummary {:accountValue "0.0"
                                                   :totalMarginUsed "0.0"}}}
   :perp-dex-clearinghouse {"xyz" {:withdrawable "1923.97"
                                   :marginSummary {:accountValue "1923.97"
                                                   :totalMarginUsed "0.0"}}}})

(deftest transfer-max-amount-reads-named-dex-withdrawable-for-perps-to-spot-test
  (let [state (named-dex-state)]
    (is (= 0 (availability/transfer-max-amount state {:to-perp? false})))
    (is (= 1923.97 (availability/transfer-max-amount state {:to-perp? false
                                                            :transfer-dex "xyz"})))
    (is (= 78.22 (availability/transfer-max-amount state {:to-perp? true
                                                          :transfer-dex "xyz"})))))

(deftest transfer-max-amount-pooled-named-dex-reads-default-perps-not-named-bucket-test
  (let [state (-> (named-dex-state)
                  (assoc :account {:mode :classic :abstraction-raw "dexAbstraction"})
                  (assoc :perp-dex-clearinghouse {})
                  (assoc-in [:webdata2 :clearinghouseState]
                            {:withdrawable "500.0"
                             :marginSummary {:accountValue "500.0"
                                             :totalMarginUsed "0.0"}}))]
    (is (= 500.0 (availability/transfer-max-amount state {:to-perp? false
                                                          :transfer-dex "xyz"})))))

(deftest transfer-dex-name-normalizes-default-and-spot-aliases-test
  (is (nil? (availability/transfer-dex-name nil)))
  (is (nil? (availability/transfer-dex-name "")))
  (is (nil? (availability/transfer-dex-name "  ")))
  (is (nil? (availability/transfer-dex-name "spot")))
  (is (nil? (availability/transfer-dex-name "SPOT")))
  (is (= "xyz" (availability/transfer-dex-name "xyz")))
  (is (= "xyz" (availability/transfer-dex-name "  xyz "))))

(deftest transfer-preview-builds-named-dex-send-asset-for-perps-to-spot-test
  (is (= {:ok? true
          :request {:action {:type "sendAsset"
                             :destination "0x1234567890abcdef1234567890abcdef12345678"
                             :sourceDex "xyz"
                             :destinationDex "spot"
                             :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                             :amount "100"
                             :fromSubAccount ""}}}
         (policy/transfer-preview (named-dex-state)
                                  {:amount-input "100"
                                   :to-perp? false
                                   :transfer-dex "xyz"}))))

(deftest transfer-preview-builds-named-dex-send-asset-for-spot-to-perps-test
  (is (= {:ok? true
          :request {:action {:type "sendAsset"
                             :destination "0x1234567890abcdef1234567890abcdef12345678"
                             :sourceDex "spot"
                             :destinationDex "xyz"
                             :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                             :amount "50"
                             :fromSubAccount ""}}}
         (policy/transfer-preview (named-dex-state)
                                  {:amount-input "50"
                                   :to-perp? true
                                   :transfer-dex "xyz"}))))

(deftest transfer-preview-keeps-usd-class-transfer-for-default-perps-test
  (is (= {:ok? true
          :request {:action {:type "usdClassTransfer"
                             :amount "25"
                             :toPerp true}}}
         (policy/transfer-preview (named-dex-state)
                                  {:amount-input "25"
                                   :to-perp? true})))
  (is (= {:ok? true
          :request {:action {:type "usdClassTransfer"
                             :amount "25"
                             :toPerp true}}}
         (policy/transfer-preview (named-dex-state)
                                  {:amount-input "25"
                                   :to-perp? true
                                   :transfer-dex ""}))))

(deftest transfer-preview-requires-wallet-address-for-named-dex-send-asset-test
  (is (= {:ok? false
          :display-message "Connect your wallet before transferring funds."}
         (policy/transfer-preview (assoc (named-dex-state) :wallet {})
                                  {:amount-input "100"
                                   :to-perp? false
                                   :transfer-dex "xyz"}))))

(deftest pooled-perps-collateral-classifies-dex-abstraction-and-unified-test
  (is (false? (boolean (availability/pooled-perps-collateral? (named-dex-state)))))
  (is (false? (boolean (availability/pooled-perps-collateral?
                        (assoc (named-dex-state)
                               :account {:mode :classic :abstraction-raw "disabled"})))))
  (is (false? (boolean (availability/pooled-perps-collateral?
                        (assoc (named-dex-state)
                               :account {:mode :classic :abstraction-raw "default"})))))
  (is (true? (boolean (availability/pooled-perps-collateral?
                       (assoc (named-dex-state)
                              :account {:mode :classic :abstraction-raw "dexAbstraction"})))))
  (is (true? (boolean (availability/pooled-perps-collateral?
                       (assoc (named-dex-state)
                              :account {:mode :unified :abstraction-raw "unifiedAccount"}))))))

(deftest transfer-preview-collapses-pooled-named-dex-to-default-perp-send-asset-test
  (let [state (-> (named-dex-state)
                  (assoc :account {:mode :unified :abstraction-raw "unifiedAccount"})
                  (assoc-in [:webdata2 :clearinghouseState]
                            {:withdrawable "2000.0"
                             :marginSummary {:accountValue "2000.0"
                                             :totalMarginUsed "0.0"}}))]
    (is (= {:ok? true
            :request {:action {:type "sendAsset"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"
                               :sourceDex ""
                               :destinationDex "spot"
                               :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                               :amount "100"
                               :fromSubAccount ""}}}
           (policy/transfer-preview state
                                    {:amount-input "100"
                                     :to-perp? false
                                     :transfer-dex "xyz"})))
    (is (= {:ok? true
            :request {:action {:type "sendAsset"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"
                               :sourceDex "spot"
                               :destinationDex ""
                               :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                               :amount "50"
                               :fromSubAccount ""}}}
           (policy/transfer-preview state
                                    {:amount-input "50"
                                     :to-perp? true
                                     :transfer-dex "xyz"})))))

(deftest transfer-preview-routes-pooled-default-modal-through-default-perp-send-asset-test
  (let [state (assoc (named-dex-state)
                     :account {:mode :classic :abstraction-raw "dexAbstraction"})]
    (is (= {:ok? true
            :request {:action {:type "sendAsset"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"
                               :sourceDex "spot"
                               :destinationDex ""
                               :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                               :amount "25"
                               :fromSubAccount ""}}}
           (policy/transfer-preview state
                                    {:amount-input "25"
                                     :to-perp? true})))))

(deftest transfer-preview-pooled-dex-abstraction-empty-named-bucket-uses-default-perps-test
  (let [state (-> (named-dex-state)
                  (assoc :account {:mode :classic :abstraction-raw "dexAbstraction"})
                  (assoc :perp-dex-clearinghouse {})
                  (assoc-in [:webdata2 :clearinghouseState]
                            {:withdrawable "500.0"
                             :marginSummary {:accountValue "500.0"
                                             :totalMarginUsed "0.0"}}))]
    (is (= {:ok? true
            :request {:action {:type "sendAsset"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"
                               :sourceDex ""
                               :destinationDex "spot"
                               :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                               :amount "100"
                               :fromSubAccount ""}}}
           (policy/transfer-preview state
                                    {:amount-input "100"
                                     :to-perp? false
                                     :transfer-dex "xyz"})))))

(def ^:private subaccount-address "0xbce774ef2382a4eb9376ea6f20408b318b10b63e")

(deftest transfer-preview-uses-selected-subaccount-source-for-named-dex-perps-to-spot-test
  (is (= {:ok? true
          :request {:action {:type "sendAsset"
                             :destination subaccount-address
                             :sourceDex "xyz"
                             :destinationDex "spot"
                             :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                             :amount "100"
                             :fromSubAccount subaccount-address}}}
         (policy/transfer-preview (named-dex-state)
                                  {:amount-input "100"
                                   :to-perp? false
                                   :transfer-dex "xyz"
                                   :transfer-destination-address subaccount-address
                                   :transfer-from-subaccount subaccount-address}))))

(deftest transfer-preview-uses-selected-subaccount-source-for-spot-to-named-dex-test
  (is (= {:ok? true
          :request {:action {:type "sendAsset"
                             :destination subaccount-address
                             :sourceDex "spot"
                             :destinationDex "xyz"
                             :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                             :amount "50"
                             :fromSubAccount subaccount-address}}}
         (policy/transfer-preview (named-dex-state)
                                  {:amount-input "50"
                                   :to-perp? true
                                   :transfer-dex "xyz"
                                   :transfer-destination-address subaccount-address
                                   :transfer-from-subaccount subaccount-address}))))

(deftest transfer-preview-uses-subaccount-source-for-default-perp-not-usd-class-transfer-test
  (let [state (assoc-in (named-dex-state) [:webdata2 :clearinghouseState]
                        {:withdrawable "500.0"
                         :marginSummary {:accountValue "500.0"
                                         :totalMarginUsed "0.0"}})]
    (is (= {:ok? true
            :request {:action {:type "sendAsset"
                               :destination subaccount-address
                               :sourceDex ""
                               :destinationDex "spot"
                               :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                               :amount "100"
                               :fromSubAccount subaccount-address}}}
           (policy/transfer-preview state
                                    {:amount-input "100"
                                     :to-perp? false
                                     :transfer-dex ""
                                     :transfer-destination-address subaccount-address
                                     :transfer-from-subaccount subaccount-address}))))
  (is (= {:ok? true
          :request {:action {:type "sendAsset"
                             :destination subaccount-address
                             :sourceDex "spot"
                             :destinationDex ""
                             :token "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
                             :amount "50"
                             :fromSubAccount subaccount-address}}}
         (policy/transfer-preview (named-dex-state)
                                  {:amount-input "50"
                                   :to-perp? true
                                   :transfer-dex ""
                                   :transfer-destination-address subaccount-address
                                   :transfer-from-subaccount subaccount-address}))))

(deftest transfer-preview-requires-wallet-address-for-pooled-default-perp-send-asset-test
  (is (= {:ok? false
          :display-message "Connect your wallet before transferring funds."}
         (policy/transfer-preview (-> (named-dex-state)
                                      (assoc :wallet {})
                                      (assoc :account {:mode :unified
                                                       :abstraction-raw "unifiedAccount"})
                                      (assoc-in [:webdata2 :clearinghouseState]
                                                {:withdrawable "2000.0"
                                                 :marginSummary {:accountValue "2000.0"
                                                                 :totalMarginUsed "0.0"}}))
                                  {:amount-input "100"
                                   :to-perp? false
                                   :transfer-dex "xyz"}))))
