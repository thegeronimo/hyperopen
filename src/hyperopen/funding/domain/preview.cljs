(ns hyperopen.funding.domain.preview
  (:require [clojure.string :as str]
            [hyperopen.funding.domain.amounts :as amounts]
            [hyperopen.funding.domain.assets :as assets-domain]
            [hyperopen.funding.domain.availability :as availability]))

(defn normalize-mode
  [value]
  (let [mode (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case keyword)
               :else nil)]
    (cond
      (= :send mode) :send
      (= :deposit mode) :deposit
      (= :transfer mode) :transfer
      (= :withdraw mode) :withdraw
      (= :legacy mode) :legacy
      :else nil)))

(defn normalize-deposit-step
  [value]
  (let [step (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case keyword)
               :else nil)]
    (if (= step :amount-entry)
      :amount-entry
      :asset-select)))

(defn normalize-withdraw-step
  [value]
  (let [step (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case keyword)
               :else nil)]
    (if (= step :amount-entry)
      :amount-entry
      :asset-select)))

(defn transfer-preview
  [state modal]
  (let [amount (amounts/parse-input-amount (:amount-input modal))
        to-perp? (true? (:to-perp? modal))
        max-amount (availability/transfer-max-amount state modal)]
    (cond
      (not (amounts/finite-number? max-amount))
      {:ok? false
       :display-message "Unable to determine transfer balance."}

      (<= max-amount 0)
      {:ok? false
       :display-message (if to-perp?
                          "No spot USDC available to transfer."
                          "No perps balance available to transfer.")}

      (not (amounts/finite-number? amount))
      {:ok? false
       :display-message "Enter a valid amount."}

      (<= amount 0)
      {:ok? false
       :display-message "Enter an amount greater than 0."}

      (> amount max-amount)
      {:ok? false
       :display-message "Amount exceeds available balance."}

      :else
      {:ok? true
       :request {:action {:type "usdClassTransfer"
                          :amount (amounts/amount->text amount)
                          :toPerp to-perp?}}})))

(defn send-preview
  [_state modal]
  (let [token (amounts/non-blank-text (:send-token modal))
        symbol (or (amounts/non-blank-text (:send-symbol modal))
                   token
                   "Asset")
        amount-input (amounts/normalize-amount-input (:amount-input modal))
        amount (amounts/parse-input-amount amount-input)
        destination (amounts/normalize-evm-address (:destination-input modal))
        max-amount (amounts/parse-num (:send-max-amount modal))]
    (cond
      (not (seq token))
      {:ok? false
       :display-message "Select an asset to send."}

      (not (amounts/finite-number? max-amount))
      {:ok? false
       :display-message "Unable to determine sendable balance."}

      (<= max-amount 0)
      {:ok? false
       :display-message (str "No sendable " symbol " balance available.")}

      (and (str/blank? amount-input)
           (str/blank? (or (:destination-input modal) "")))
      {:ok? false}

      (nil? destination)
      {:ok? false
       :display-message "Enter a valid destination address."}

      (not (amounts/finite-number? amount))
      {:ok? false
       :display-message "Enter a valid amount."}

      (<= amount 0)
      {:ok? false
       :display-message "Enter an amount greater than 0."}

      (> amount max-amount)
      {:ok? false
       :display-message "Amount exceeds available balance."}

      :else
      {:ok? true
       :request {:action {:type "sendAsset"
                          :destination destination
                          :sourceDex "spot"
                          :destinationDex "spot"
                          :token token
                          :amount (amounts/amount->text amount)
                          :fromSubAccount ""}}})))

(defn withdraw-destination
  [flow-kind destination-input]
  (if (= flow-kind :hyperunit-address)
    (amounts/normalize-withdraw-destination destination-input)
    (amounts/normalize-evm-address destination-input)))

(defn withdraw-preview-error
  [{:keys [selected-asset destination destination-chain max-amount amount min-amount]}]
  (let [flow-kind (:flow-kind selected-asset)
        asset-symbol (or (:symbol selected-asset) "Asset")]
    (cond
      (nil? selected-asset)
      "Select an asset to withdraw."

      (nil? destination)
      "Enter a valid destination address."

      (and (= flow-kind :hyperunit-address)
           (not (seq destination-chain)))
      (str "Withdrawal source chain is unavailable for " asset-symbol ".")

      (<= max-amount 0)
      "No withdrawable balance available."

      (not (amounts/finite-number? amount))
      "Enter a valid amount."

      (and (amounts/finite-number? min-amount)
           (> min-amount 0)
           (< amount min-amount))
      (str "Minimum withdrawal is "
           (amounts/amount->text min-amount)
           " "
           asset-symbol
           ".")

      (> amount max-amount)
      "Amount exceeds withdrawable balance."

      :else nil)))

(defn withdraw-request-action
  [selected-asset amount destination destination-chain]
  (if (= :hyperunit-address (:flow-kind selected-asset))
    {:type "hyperunitSendAssetWithdraw"
     :asset (name (:key selected-asset))
     :token (or (:symbol selected-asset) "Asset")
     :amount (amounts/amount->text amount)
     :destination destination
     :destinationChain destination-chain
     :network (:network selected-asset)}
    {:type "withdraw3"
     :amount (amounts/amount->text amount)
     :destination destination}))

(defn withdraw-preview
  [state modal]
  (let [selected-asset (availability/withdraw-asset state modal)
        flow-kind (:flow-kind selected-asset)
        destination-chain (amounts/non-blank-text (:hyperunit-source-chain selected-asset))
        amount (amounts/parse-input-amount (:amount-input modal))
        destination (withdraw-destination flow-kind (:destination-input modal))
        max-amount (availability/withdraw-max-amount state selected-asset)
        min-amount (assets-domain/withdraw-minimum-amount selected-asset)
        display-message (withdraw-preview-error {:selected-asset selected-asset
                                                 :destination destination
                                                 :destination-chain destination-chain
                                                 :max-amount max-amount
                                                 :amount amount
                                                 :min-amount min-amount})]
    (if (seq display-message)
      {:ok? false
       :display-message display-message}
      {:ok? true
       :request {:action (withdraw-request-action selected-asset
                                                  amount
                                                  destination
                                                  destination-chain)}})))

(defn deposit-preview
  [state modal]
  (let [deposit-step (normalize-deposit-step (:deposit-step modal))
        selected-asset (assets-domain/deposit-asset state modal)
        amount (amounts/parse-input-amount (:amount-input modal))
        min-amount (or (:minimum selected-asset)
                       assets-domain/deposit-min-usdc)
        flow-kind (:flow-kind selected-asset)]
    (cond
      (not= deposit-step :amount-entry)
      {:ok? false}

      (nil? selected-asset)
      {:ok? false
       :display-message "Select an asset to deposit."}

      (not (assets-domain/deposit-asset-implemented? selected-asset))
      {:ok? false
       :display-message (str (:symbol selected-asset)
                             " deposits are not implemented yet in Hyperopen.")}

      (= flow-kind :hyperunit-address)
      (if-let [from-chain (amounts/non-blank-text (:hyperunit-source-chain selected-asset))]
        {:ok? true
         :request {:action {:type "hyperunitGenerateDepositAddress"
                            :asset (name (:key selected-asset))
                            :fromChain from-chain
                            :network (:network selected-asset)}}}
        {:ok? false
         :display-message (str (:symbol selected-asset)
                               " address deposits are not implemented yet in Hyperopen.")})

      (= flow-kind :route)
      (cond
        (not (amounts/finite-number? amount))
        {:ok? false
         :display-message "Enter a valid amount."}

        (<= amount 0)
        {:ok? false
         :display-message "Enter an amount greater than 0."}

        (< amount min-amount)
        {:ok? false
         :display-message (str "Minimum deposit is " min-amount " "
                               (:symbol selected-asset) ".")}

        (and (amounts/finite-number? (:maximum selected-asset))
             (> amount (:maximum selected-asset)))
        {:ok? false
         :display-message (str "Maximum deposit is " (:maximum selected-asset) " "
                               (:symbol selected-asset) ".")}

        (= (:key selected-asset) :usdt)
        {:ok? true
         :request {:action {:type "lifiUsdtToUsdcBridge2Deposit"
                            :asset (name (:key selected-asset))
                            :amount (amounts/amount->text amount)
                            :chainId assets-domain/deposit-chain-id-mainnet}}}

        (= (:key selected-asset) :usdh)
        {:ok? true
         :request {:action {:type "acrossUsdcToUsdhDeposit"
                            :asset (name (:key selected-asset))
                            :amount (amounts/amount->text amount)
                            :chainId assets-domain/deposit-chain-id-mainnet}}}

        :else
        {:ok? false
         :display-message (str (:symbol selected-asset)
                               " route deposits are not implemented yet in Hyperopen.")})

      (not= flow-kind :bridge2)
      {:ok? false
       :display-message "Deposit flow unavailable."}

      (not (amounts/finite-number? amount))
      {:ok? false
       :display-message "Enter a valid amount."}

      (<= amount 0)
      {:ok? false
       :display-message "Enter an amount greater than 0."}

      (< amount min-amount)
      {:ok? false
       :display-message (str "Minimum deposit is " min-amount " "
                             (:symbol selected-asset) ".")}

      :else
      {:ok? true
       :request {:action {:type "bridge2Deposit"
                          :asset (name (:key selected-asset))
                          :amount (amounts/amount->text amount)
                          :chainId (:chain-id selected-asset)}}})))

(defn preview
  [state modal]
  (case (normalize-mode (:mode modal))
    :deposit (deposit-preview state modal)
    :send (send-preview state modal)
    :transfer (transfer-preview state modal)
    :withdraw (withdraw-preview state modal)
    {:ok? false
     :display-message "Funding action unavailable."}))
