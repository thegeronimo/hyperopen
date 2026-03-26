(ns hyperopen.funding.domain.policy
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.funding.domain.assets :as assets-domain]
            [hyperopen.funding.domain.lifecycle :as lifecycle-domain]))

(def ^:private hyperunit-lifecycle-failure-fragments
  ["fail" "error" "revert" "cancel" "refund" "drop"])

(def ^:private hyperunit-explorer-tx-base-by-chain
  {"arbitrum" "https://arbiscan.io/tx/"
   "bitcoin" "https://mempool.space/tx/"
   "ethereum" "https://etherscan.io/tx/"
   "solana" "https://solscan.io/tx/"})

(def ^:private hyperliquid-explorer-tx-base-url
  "https://app.hyperliquid.xyz/explorer/tx/")

(def ^:private chain-fee-format-by-chain
  {"bitcoin" {:symbol "BTC" :decimals 8}
   "ethereum" {:symbol "ETH" :decimals 18}
   "solana" {:symbol "SOL" :decimals 9}})

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn parse-num
  [value]
  (trading-domain/parse-num value))

(defn finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)
       (not (js/isNaN value))))

(defn amount->text
  [value]
  (if (finite-number? value)
    (trading-domain/number->clean-string (max 0 value) 6)
    "0"))

(defn normalize-amount-input
  [value]
  (-> (or value "")
      str
      (str/replace #"," "")
      (str/replace #"\s+" "")))

(defn parse-input-amount
  [value]
  (let [parsed (parse-num (normalize-amount-input value))]
    (when (finite-number? parsed)
      (max 0 parsed))))

(defn normalize-evm-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn normalize-withdraw-destination
  [value]
  (non-blank-text value))

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

(defn- normalize-lifecycle-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (some-> value str/trim str/lower-case keyword)
                    :else nil)]
    (when (contains? #{:deposit :withdraw} direction)
      direction)))

(defn- lifecycle-token
  [value]
  (some-> value
          name
          str/lower-case
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"^-+|-+$" "")))

(defn- lifecycle-fragment-match?
  [value fragments]
  (let [token (lifecycle-token value)]
    (and (seq token)
         (some #(str/includes? token %) fragments))))

(defn- usdc-coin?
  [coin]
  (and (string? coin)
       (str/starts-with? (str/upper-case (str/trim coin)) "USDC")))

(defn- normalize-coin-token
  [value]
  (some-> value str str/trim str/upper-case))

(defn- balance-row-available
  [row]
  (when (map? row)
    (let [available-direct (or (parse-num (:available row))
                               (parse-num (:availableBalance row))
                               (parse-num (:free row)))
          total (or (parse-num (:total row))
                    (parse-num (:totalBalance row)))
          hold (parse-num (:hold row))
          derived (cond
                    (finite-number? total)
                    (if (finite-number? hold)
                      (- total hold)
                      total)

                    :else nil)
          available (or available-direct derived)]
      (when (finite-number? available)
        (max 0 available)))))

(defn- spot-usdc-available
  [state]
  (some (fn [row]
          (when (usdc-coin? (:coin row))
            (balance-row-available row)))
        (get-in state [:spot :clearinghouse-state :balances])))

(defn- unified-account-mode?
  [state]
  (= :unified (get-in state [:account :mode])))

(defn- unified-spot-usdc-available
  [state]
  (when (unified-account-mode? state)
    (spot-usdc-available state)))

(defn- spot-asset-available
  [state symbol]
  (let [target (normalize-coin-token symbol)]
    (some (fn [row]
            (when (= target
                     (normalize-coin-token (:coin row)))
              (balance-row-available row)))
          (get-in state [:spot :clearinghouse-state :balances]))))

(defn- summary-derived-withdrawable
  [summary]
  (let [account-value (parse-num (:accountValue summary))
        margin-used (parse-num (:totalMarginUsed summary))]
    (when (and (finite-number? account-value)
               (finite-number? margin-used))
      (max 0 (- account-value margin-used)))))

(defn- summary-candidates
  [clearinghouse-state]
  (remove nil?
          [(:marginSummary clearinghouse-state)
           (:crossMarginSummary clearinghouse-state)
           (get-in clearinghouse-state [:clearinghouseState :marginSummary])
           (get-in clearinghouse-state [:clearinghouseState :crossMarginSummary])]))

(defn- clearinghouse-withdrawable
  [clearinghouse-state]
  (let [root-candidates (some parse-num
                              [(:withdrawable clearinghouse-state)
                               (:withdrawableUsd clearinghouse-state)
                               (:withdrawableUSDC clearinghouse-state)
                               (:availableToWithdraw clearinghouse-state)
                               (:availableToWithdrawUsd clearinghouse-state)
                               (:availableToWithdrawUSDC clearinghouse-state)
                               (get-in clearinghouse-state [:clearinghouseState :withdrawable])
                               (get-in clearinghouse-state [:clearinghouseState :withdrawableUsd])
                               (get-in clearinghouse-state [:clearinghouseState :withdrawableUSDC])
                               (get-in clearinghouse-state [:clearinghouseState :availableToWithdraw])
                               (get-in clearinghouse-state [:clearinghouseState :availableToWithdrawUsd])
                               (get-in clearinghouse-state [:clearinghouseState :availableToWithdrawUSDC])])]
    (cond
      (finite-number? root-candidates)
      (max 0 root-candidates)

      :else
      (some summary-derived-withdrawable (summary-candidates clearinghouse-state)))))

(defn- clearinghouse-state-candidates
  [state]
  (->> [(get-in state [:webdata2 :clearinghouseState])
        (get-in state [:webdata2 :clearinghouseState :clearinghouseState])]
       (filter map?)
       distinct
       vec))

(defn- perps-withdrawable
  [state]
  (max 0 (or (some clearinghouse-withdrawable
                   (clearinghouse-state-candidates state))
             0)))

(defn- withdrawable-usdc
  [state]
  (max 0 (or (unified-spot-usdc-available state)
             (perps-withdrawable state)
             0)))

(defn- withdraw-available-amount
  [state asset]
  (cond
    (nil? asset)
    0

    (= :usdc (:key asset))
    (withdrawable-usdc state)

    :else
    (or (spot-asset-available state (:symbol asset))
        0)))

(defn- withdraw-available-list-display
  [value]
  (if (pos? (or value 0))
    (amount->text value)
    "-"))

(defn withdraw-assets
  [state]
  (mapv (fn [asset]
          (let [available-amount (withdraw-available-amount state asset)]
            (assoc asset
                   :available-amount available-amount
                   :available-display (withdraw-available-list-display available-amount)
                   :available-detail-display (amount->text available-amount))))
        (assets-domain/withdraw-assets state)))

(defn withdraw-assets-filtered
  [state modal]
  (let [search-term (-> (or (:withdraw-search-input modal) "")
                        str
                        str/trim
                        str/lower-case)
        assets (withdraw-assets state)]
    (if-not (seq search-term)
      assets
      (filterv (fn [{:keys [symbol name network]}]
                 (let [symbol* (str/lower-case (or symbol ""))
                       name* (str/lower-case (or name ""))
                       network* (str/lower-case (or network ""))]
                   (or (str/includes? symbol* search-term)
                       (str/includes? name* search-term)
                       (str/includes? network* search-term))))
               assets))))

(defn withdraw-asset
  [state modal]
  (let [selected-key (or (assets-domain/normalize-withdraw-asset-key
                          (:withdraw-selected-asset-key modal))
                         assets-domain/withdraw-default-asset-key)
        assets (withdraw-assets state)]
    (or (some (fn [asset]
                (when (= selected-key (:key asset))
                  asset))
              assets)
        (first assets))))

(defn transfer-max-amount
  [state {:keys [to-perp?]}]
  (if (true? to-perp?)
    (or (spot-usdc-available state) 0)
    (perps-withdrawable state)))

(defn withdraw-max-amount
  [state selected-asset]
  (withdraw-available-amount state selected-asset))

(defn format-usdc-display
  [value]
  (.toLocaleString (js/Number. (max 0 (or (parse-num value) 0)))
                   "en-US"
                   #js {:minimumFractionDigits 2
                        :maximumFractionDigits 2}))

(defn format-usdc-input
  [value]
  (amount->text (max 0 (or (parse-num value) 0))))

(defn hyperunit-lifecycle-failure?
  [lifecycle]
  (let [lifecycle* (lifecycle-domain/normalize-hyperunit-lifecycle lifecycle)]
    (or (lifecycle-fragment-match? (:state lifecycle*)
                                   hyperunit-lifecycle-failure-fragments)
        (lifecycle-fragment-match? (:status lifecycle*)
                                   hyperunit-lifecycle-failure-fragments)
        (and (lifecycle-domain/hyperunit-lifecycle-terminal? lifecycle*)
             (seq (non-blank-text (:error lifecycle*)))))))

(defn hyperunit-lifecycle-recovery-hint
  [lifecycle]
  (let [lifecycle* (lifecycle-domain/normalize-hyperunit-lifecycle lifecycle)
        refunded? (or (lifecycle-fragment-match? (:state lifecycle*) ["refund"])
                      (lifecycle-fragment-match? (:status lifecycle*) ["refund"]))
        canceled? (or (lifecycle-fragment-match? (:state lifecycle*) ["cancel"])
                      (lifecycle-fragment-match? (:status lifecycle*) ["cancel"]))]
    (cond
      refunded?
      "Funds were refunded on the source chain. Confirm the wallet balance, then retry."

      canceled?
      "The operation was canceled. Retry if you still want to continue."

      (= :withdraw (:direction lifecycle*))
      "Verify the destination address and network, then submit a new withdrawal."

      (= :deposit (:direction lifecycle*))
      "Verify the source transfer network and amount, then generate a new deposit address."

      :else
      "Retry the operation and monitor the lifecycle status.")))

(defn- hyperunit-explorer-tx-base-url
  [direction chain]
  (if (= direction :deposit)
    hyperliquid-explorer-tx-base-url
    (get hyperunit-explorer-tx-base-by-chain chain)))

(defn hyperunit-explorer-tx-url
  [direction chain tx-id]
  (let [direction* (normalize-lifecycle-direction direction)
        chain* (some-> chain non-blank-text str/lower-case)
        tx-id* (non-blank-text tx-id)]
    (when (seq tx-id*)
      (when-let [base-url (hyperunit-explorer-tx-base-url direction* chain*)]
        (str base-url (js/encodeURIComponent tx-id*))))))

(defn hyperunit-fee-entry
  [fee-estimate chain]
  (let [estimate* (lifecycle-domain/normalize-hyperunit-fee-estimate fee-estimate)
        chain* (some-> chain non-blank-text str/lower-case)]
    (when (and (seq chain*)
               (map? (:by-chain estimate*)))
      (get (:by-chain estimate*) chain*))))

(defn hyperunit-withdrawal-queue-entry
  [withdrawal-queue chain]
  (let [queue* (lifecycle-domain/normalize-hyperunit-withdrawal-queue withdrawal-queue)
        chain* (some-> chain non-blank-text str/lower-case)]
    (when (and (seq chain*)
               (map? (:by-chain queue*)))
      (get (:by-chain queue*) chain*))))

(defn- integer-like-number?
  [value]
  (and (number? value)
       (finite-number? value)
       (= value (js/Math.floor value))))

(defn- fee-value->number
  [value]
  (cond
    (and (number? value) (finite-number? value))
    value

    (string? value)
    (parse-input-amount value)

    :else nil))

(defn- fee-value->chain-units
  [value chain]
  (let [chain* (some-> chain non-blank-text str/lower-case)
        {:keys [decimals]} (get chain-fee-format-by-chain chain*)
        decimals* (or decimals 0)
        parsed-number (fee-value->number value)
        raw-text (when (string? value) (non-blank-text value))
        integer-text? (and (string? raw-text)
                           (re-matches #"^\d+$" raw-text))
        integer-like? (or integer-text?
                          (integer-like-number? parsed-number))]
    (cond
      (nil? parsed-number)
      nil

      (and (> decimals* 0) integer-like?)
      (/ parsed-number (js/Math.pow 10 decimals*))

      :else
      parsed-number)))

(defn estimate-fee-display
  [value chain]
  (let [chain* (some-> chain non-blank-text str/lower-case)
        {:keys [symbol decimals]} (get chain-fee-format-by-chain chain*)
        normalized-value (fee-value->chain-units value chain*)
        display-text (when (finite-number? normalized-value)
                       (trading-domain/number->clean-string (max 0 normalized-value)
                                                            (if (number? decimals)
                                                              (min 8 (max 2 decimals))
                                                              6)))
        fallback-text (non-blank-text value)]
    (cond
      (and (seq display-text) (seq symbol))
      (str display-text " " symbol)

      (seq display-text)
      display-text

      :else
      fallback-text)))

(defn transfer-preview
  [state modal]
  (let [amount (parse-input-amount (:amount-input modal))
        to-perp? (true? (:to-perp? modal))
        max-amount (transfer-max-amount state modal)]
    (cond
      (not (finite-number? max-amount))
      {:ok? false
       :display-message "Unable to determine transfer balance."}

      (<= max-amount 0)
      {:ok? false
       :display-message (if to-perp?
                          "No spot USDC available to transfer."
                          "No perps balance available to transfer.")}

      (not (finite-number? amount))
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
                          :amount (amount->text amount)
                          :toPerp to-perp?}}})))

(defn send-preview
  [_state modal]
  (let [token (non-blank-text (:send-token modal))
        symbol (or (non-blank-text (:send-symbol modal))
                   token
                   "Asset")
        amount-input (normalize-amount-input (:amount-input modal))
        amount (parse-input-amount amount-input)
        destination (normalize-evm-address (:destination-input modal))
        max-amount (parse-num (:send-max-amount modal))]
    (cond
      (not (seq token))
      {:ok? false
       :display-message "Select an asset to send."}

      (not (finite-number? max-amount))
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

      (not (finite-number? amount))
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
                          :amount (amount->text amount)
                          :fromSubAccount ""}}})))

(defn- withdraw-destination
  [flow-kind destination-input]
  (if (= flow-kind :hyperunit-address)
    (normalize-withdraw-destination destination-input)
    (normalize-evm-address destination-input)))

(defn- withdraw-preview-error
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

      (not (finite-number? amount))
      "Enter a valid amount."

      (and (finite-number? min-amount)
           (> min-amount 0)
           (< amount min-amount))
      (str "Minimum withdrawal is "
           (amount->text min-amount)
           " "
           asset-symbol
           ".")

      (> amount max-amount)
      "Amount exceeds withdrawable balance."

      :else nil)))

(defn- withdraw-request-action
  [selected-asset amount destination destination-chain]
  (if (= :hyperunit-address (:flow-kind selected-asset))
    {:type "hyperunitSendAssetWithdraw"
     :asset (name (:key selected-asset))
     :token (or (:symbol selected-asset) "Asset")
     :amount (amount->text amount)
     :destination destination
     :destinationChain destination-chain
     :network (:network selected-asset)}
    {:type "withdraw3"
     :amount (amount->text amount)
     :destination destination}))

(defn withdraw-preview
  [state modal]
  (let [selected-asset (withdraw-asset state modal)
        flow-kind (:flow-kind selected-asset)
        destination-chain (non-blank-text (:hyperunit-source-chain selected-asset))
        amount (parse-input-amount (:amount-input modal))
        destination (withdraw-destination flow-kind (:destination-input modal))
        max-amount (withdraw-max-amount state selected-asset)
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
        amount (parse-input-amount (:amount-input modal))
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
      (if-let [from-chain (non-blank-text (:hyperunit-source-chain selected-asset))]
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
        (not (finite-number? amount))
        {:ok? false
         :display-message "Enter a valid amount."}

        (<= amount 0)
        {:ok? false
         :display-message "Enter an amount greater than 0."}

        (< amount min-amount)
        {:ok? false
         :display-message (str "Minimum deposit is " min-amount " "
                               (:symbol selected-asset) ".")}

        (and (finite-number? (:maximum selected-asset))
             (> amount (:maximum selected-asset)))
        {:ok? false
         :display-message (str "Maximum deposit is " (:maximum selected-asset) " "
                               (:symbol selected-asset) ".")}

        (= (:key selected-asset) :usdt)
        {:ok? true
         :request {:action {:type "lifiUsdtToUsdcBridge2Deposit"
                            :asset (name (:key selected-asset))
                            :amount (amount->text amount)
                            :chainId assets-domain/deposit-chain-id-mainnet}}}

        (= (:key selected-asset) :usdh)
        {:ok? true
         :request {:action {:type "acrossUsdcToUsdhDeposit"
                            :asset (name (:key selected-asset))
                            :amount (amount->text amount)
                            :chainId assets-domain/deposit-chain-id-mainnet}}}

        :else
        {:ok? false
         :display-message (str (:symbol selected-asset)
                               " route deposits are not implemented yet in Hyperopen.")})

      (not= flow-kind :bridge2)
      {:ok? false
       :display-message "Deposit flow unavailable."}

      (not (finite-number? amount))
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
                          :amount (amount->text amount)
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
