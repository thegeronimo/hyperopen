(ns hyperopen.funding.application.modal-actions
  (:require [clojure.string :as str]
            [hyperopen.funding.domain.lifecycle :as lifecycle-domain]
            [hyperopen.domain.trading :as trading-domain]))

(def ^:private funding-modal-path
  [:funding-ui :modal])

(def ^:private withdraw-min-usdc
  5)

(def ^:private deposit-min-usdc
  5)

(def ^:private deposit-chain-id-mainnet
  "0xa4b1")

(def ^:private deposit-chain-id-testnet
  "0x66eee")

(def ^:private deposit-quick-amounts
  [5 1000 10000 100000])

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(def ^:private deposit-assets-base
  [{:key :usdc
    :symbol "USDC"
    :name "USDC"
    :network "Arbitrum"
    :flow-kind :bridge2
    :minimum deposit-min-usdc}
   {:key :usdt
    :symbol "USDT"
    :name "Tether"
    :network "Arbitrum"
    :flow-kind :route
    :route-key "lifi"}
   {:key :btc
    :symbol "BTC"
    :name "Bitcoin"
    :network "Bitcoin"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "bitcoin"}
   {:key :eth
    :symbol "ETH"
    :name "Ethereum"
    :network "Ethereum"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "ethereum"}
   {:key :sol
    :symbol "SOL"
    :name "Solana"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :2z
    :symbol "2Z"
    :name "ZZ"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :bonk
    :symbol "BONK"
    :name "Bonk"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :ena
    :symbol "ENA"
    :name "Ethena"
    :network "Ethereum"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "ethereum"}
   {:key :fart
    :symbol "FARTCOIN"
    :name "Fartcoin"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :mon
    :symbol "MON"
    :name "Monad"
    :network "Monad"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "monad"}
   {:key :pump
    :symbol "PUMP"
    :name "Pump"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :spxs
    :symbol "SPX"
    :name "SPX"
    :network "Solana"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "solana"}
   {:key :xpl
    :symbol "XPL"
    :name "Plasma"
    :network "Plasma"
    :flow-kind :hyperunit-address
    :hyperunit-source-chain "plasma"}
   {:key :usdh
    :symbol "USDH"
    :name "USDH"
    :network "Arbitrum"
    :flow-kind :route
    :route-key "arbitrum_across"
    :maximum 1000000}])

(def ^:private deposit-asset-keys
  (set (map :key deposit-assets-base)))

(def ^:private deposit-implemented-asset-keys
  #{:usdc :usdt :btc :eth :sol :2z :bonk :ena :fart :mon :pump :spxs :xpl :usdh})

(def ^:private withdraw-default-asset-key
  :usdc)

(def ^:private withdraw-supported-asset-keys
  (->> deposit-assets-base
       (filter (fn [{:keys [key flow-kind]}]
                 (or (= key :usdc)
                     (= flow-kind :hyperunit-address))))
       (map :key)
       set))

(def ^:private hyperunit-withdraw-minimum-by-asset-key
  {:btc 0.0003
   :eth 0.007
   :sol 0.12
   :2z 150
   :bonk 1800000
   :ena 120
   :fart 55
   :mon 450
   :pump 5500
   :spxs 32
   :xpl 60})

(def ^:private hyperunit-lifecycle-failure-fragments
  ["fail" "error" "revert" "cancel" "refund" "drop"])

(def ^:private hyperunit-explorer-tx-base-by-chain
  {"arbitrum" "https://arbiscan.io/tx/"
   "bitcoin" "https://mempool.space/tx/"
   "ethereum" "https://etherscan.io/tx/"
   "solana" "https://solscan.io/tx/"})

(def ^:private hyperliquid-explorer-tx-base-url
  "https://app.hyperliquid.xyz/explorer/tx/")

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- parse-num
  [value]
  (trading-domain/parse-num value))

(defn- finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)
       (not (js/isNaN value))))

(defn- clamp
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(defn- amount->text
  [value]
  (if (finite-number? value)
    (trading-domain/number->clean-string (max 0 value) 6)
    "0"))

(defn- normalize-amount-input
  [value]
  (-> (or value "")
      str
      (str/replace #"," "")
      (str/replace #"\s+" "")))

(defn- parse-input-amount
  [value]
  (let [parsed (parse-num (normalize-amount-input value))]
    (when (finite-number? parsed)
      (max 0 parsed))))

(defn- normalize-evm-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- normalize-withdraw-destination
  [value]
  (non-blank-text value))

(defn- wallet-address
  [state]
  (normalize-evm-address (get-in state [:wallet :address])))

(defn- normalize-mode
  [value]
  (let [mode (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case keyword)
               :else nil)]
    (cond
      (= :deposit mode) :deposit
      (= :transfer mode) :transfer
      (= :withdraw mode) :withdraw
      (= :legacy mode) :legacy
      :else nil)))

(defn- normalize-deposit-step
  [value]
  (let [step (cond
               (keyword? value) value
               (string? value) (some-> value str/trim str/lower-case keyword)
               :else nil)]
    (if (= step :amount-entry)
      :amount-entry
      :asset-select)))

(defn- normalize-chain-id
  [value]
  (let [raw (some-> value str str/trim)]
    (when (seq raw)
      (let [hex? (str/starts-with? raw "0x")
            source (if hex? (subs raw 2) raw)
            base (if hex? 16 10)
            parsed (js/parseInt source base)]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (str "0x" (.toString (js/Math.floor parsed) 16)))))))

(defn- normalize-anchor
  [anchor]
  (when (map? anchor)
    (let [normalized (reduce (fn [acc k]
                               (if-let [num (parse-num (get anchor k))]
                                 (assoc acc k num)
                                 acc))
                             {}
                             anchor-keys)]
      (when (seq normalized)
        normalized))))

(defn- normalize-deposit-asset-key
  [value]
  (let [asset-key (cond
                    (keyword? value) value
                    (string? value) (some-> value str/trim str/lower-case keyword)
                    :else nil)]
    (when (contains? deposit-asset-keys asset-key)
      asset-key)))

(defn- normalize-withdraw-asset-key
  [value]
  (let [asset-key (cond
                    (keyword? value) value
                    (string? value) (some-> value str/trim str/lower-case keyword)
                    :else nil)]
    (when (contains? withdraw-supported-asset-keys asset-key)
      asset-key)))

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

(def hyperunit-lifecycle-terminal? lifecycle-domain/hyperunit-lifecycle-terminal?)
(def default-hyperunit-lifecycle-state lifecycle-domain/default-hyperunit-lifecycle-state)
(def normalize-hyperunit-lifecycle lifecycle-domain/normalize-hyperunit-lifecycle)
(def default-hyperunit-fee-estimate-state lifecycle-domain/default-hyperunit-fee-estimate-state)
(def normalize-hyperunit-fee-estimate lifecycle-domain/normalize-hyperunit-fee-estimate)
(def default-hyperunit-withdrawal-queue-state lifecycle-domain/default-hyperunit-withdrawal-queue-state)
(def normalize-hyperunit-withdrawal-queue lifecycle-domain/normalize-hyperunit-withdrawal-queue)

(defn- resolve-deposit-network
  [state]
  (let [wallet-chain-id (normalize-chain-id (get-in state [:wallet :chain-id]))]
    (if (= wallet-chain-id deposit-chain-id-testnet)
      {:chain-id deposit-chain-id-testnet
       :chain-label "Arbitrum Sepolia"}
      {:chain-id deposit-chain-id-mainnet
       :chain-label "Arbitrum"})))

(defn- deposit-assets
  [state]
  (let [{:keys [chain-id chain-label]} (resolve-deposit-network state)]
    (mapv (fn [asset]
            (if (= :usdc (:key asset))
              (assoc asset
                     :network chain-label
                     :chain-id chain-id)
              asset))
          deposit-assets-base)))

(defn- deposit-asset
  [state modal]
  (let [selected-key (normalize-deposit-asset-key (:deposit-selected-asset-key modal))]
    (some (fn [asset]
            (when (= selected-key (:key asset))
              asset))
          (deposit-assets state))))

(defn- deposit-asset-implemented?
  [asset]
  (contains? deposit-implemented-asset-keys (:key asset)))

(defn- deposit-assets-filtered
  [state modal]
  (let [search-term (-> (or (:deposit-search-input modal) "")
                        str
                        str/trim
                        str/lower-case)
        assets (deposit-assets state)]
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

(defn- withdraw-assets
  [state]
  (->> (deposit-assets state)
       (filterv (fn [asset]
                  (contains? withdraw-supported-asset-keys (:key asset))))))

(defn- withdraw-asset
  [state modal]
  (let [selected-key (or (normalize-withdraw-asset-key (:withdraw-selected-asset-key modal))
                         withdraw-default-asset-key)
        assets (withdraw-assets state)]
    (or (some (fn [asset]
                (when (= selected-key (:key asset))
                  asset))
              assets)
        (first assets))))

(defn default-funding-modal-state
  []
  {:open? false
   :mode nil
   :legacy-kind nil
   :anchor nil
   :deposit-step :asset-select
   :deposit-search-input ""
   :deposit-selected-asset-key nil
   :deposit-generated-address nil
   :deposit-generated-signatures nil
   :deposit-generated-asset-key nil
   :amount-input ""
   :to-perp? true
   :destination-input ""
   :withdraw-selected-asset-key withdraw-default-asset-key
   :withdraw-generated-address nil
   :hyperunit-lifecycle (default-hyperunit-lifecycle-state)
   :hyperunit-fee-estimate (default-hyperunit-fee-estimate-state)
   :hyperunit-withdrawal-queue (default-hyperunit-withdrawal-queue-state)
   :submitting? false
   :error nil})

(defn- modal-state
  [state]
  (let [stored-modal (if (map? (get-in state funding-modal-path))
                       (get-in state funding-modal-path)
                       {})
        modal (merge (default-funding-modal-state)
                     stored-modal)]
    (assoc modal
           :anchor (normalize-anchor (:anchor modal))
           :withdraw-selected-asset-key (or (normalize-withdraw-asset-key
                                             (:withdraw-selected-asset-key modal))
                                            withdraw-default-asset-key)
           :hyperunit-fee-estimate (normalize-hyperunit-fee-estimate
                                    (:hyperunit-fee-estimate modal))
           :hyperunit-withdrawal-queue (normalize-hyperunit-withdrawal-queue
                                        (:hyperunit-withdrawal-queue modal))
           :hyperunit-lifecycle (normalize-hyperunit-lifecycle
                                 (:hyperunit-lifecycle modal)))))

(defn modal-open?
  [state]
  (true? (:open? (modal-state state))))

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

(defn- perps-withdrawable
  [state]
  (let [clearinghouse-state (or (get-in state [:webdata2 :clearinghouseState]) {})
        direct (some parse-num
                     [(:withdrawable clearinghouse-state)
                      (:withdrawableUsd clearinghouse-state)
                      (:withdrawableUSDC clearinghouse-state)
                      (:availableToWithdraw clearinghouse-state)
                      (:availableToWithdrawUsd clearinghouse-state)
                      (:availableToWithdrawUSDC clearinghouse-state)])
        summary (or (:marginSummary clearinghouse-state)
                    (:crossMarginSummary clearinghouse-state)
                    {})
        derived (summary-derived-withdrawable summary)
        value (or direct derived)]
    (if (finite-number? value)
      (max 0 value)
      0)))

(defn- transfer-max-amount
  [state {:keys [to-perp?]}]
  (if (true? to-perp?)
    (or (spot-usdc-available state) 0)
    (perps-withdrawable state)))

(defn- withdraw-max-amount
  [state selected-asset]
  (cond
    (nil? selected-asset)
    0

    (= :usdc (:key selected-asset))
    (perps-withdrawable state)

    :else
    (or (spot-asset-available state (:symbol selected-asset))
        0)))

(defn- format-usdc-display
  [value]
  (.toLocaleString (js/Number. (max 0 (or (parse-num value) 0)))
                   "en-US"
                   #js {:minimumFractionDigits 2
                        :maximumFractionDigits 2}))

(defn- format-usdc-input
  [value]
  (amount->text (max 0 (or (parse-num value) 0))))

(defn- withdraw-minimum-amount
  [asset]
  (let [asset-key (:key asset)]
    (cond
      (= asset-key :usdc)
      withdraw-min-usdc

      (contains? hyperunit-withdraw-minimum-by-asset-key asset-key)
      (get hyperunit-withdraw-minimum-by-asset-key asset-key)

      :else 0)))

(defn- hyperunit-source-chain
  [asset]
  (some-> (:hyperunit-source-chain asset)
          non-blank-text
          str/lower-case))

(defn- hyperunit-lifecycle-failure?
  [lifecycle]
  (let [lifecycle* (normalize-hyperunit-lifecycle lifecycle)]
    (or (lifecycle-fragment-match? (:state lifecycle*)
                                   hyperunit-lifecycle-failure-fragments)
        (lifecycle-fragment-match? (:status lifecycle*)
                                   hyperunit-lifecycle-failure-fragments)
        (and (hyperunit-lifecycle-terminal? lifecycle*)
             (seq (non-blank-text (:error lifecycle*)))))))

(defn- hyperunit-lifecycle-recovery-hint
  [lifecycle]
  (let [lifecycle* (normalize-hyperunit-lifecycle lifecycle)
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

(defn- hyperunit-explorer-tx-url
  [direction chain tx-id]
  (let [direction* (normalize-lifecycle-direction direction)
        chain* (some-> chain non-blank-text str/lower-case)
        tx-id* (non-blank-text tx-id)]
    (when (seq tx-id*)
      (when-let [base-url (hyperunit-explorer-tx-base-url direction* chain*)]
        (str base-url (js/encodeURIComponent tx-id*))))))

(defn- hyperunit-fee-entry
  [fee-estimate chain]
  (let [estimate* (normalize-hyperunit-fee-estimate fee-estimate)
        chain* (some-> chain non-blank-text str/lower-case)]
    (when (and (seq chain*)
               (map? (:by-chain estimate*)))
      (get (:by-chain estimate*) chain*))))

(defn- hyperunit-withdrawal-queue-entry
  [withdrawal-queue chain]
  (let [queue* (normalize-hyperunit-withdrawal-queue withdrawal-queue)
        chain* (some-> chain non-blank-text str/lower-case)]
    (when (and (seq chain*)
               (map? (:by-chain queue*)))
      (get (:by-chain queue*) chain*))))

(def ^:private chain-fee-format-by-chain
  {"bitcoin" {:symbol "BTC" :decimals 8}
   "ethereum" {:symbol "ETH" :decimals 18}
   "solana" {:symbol "SOL" :decimals 9}})

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

(defn- estimate-fee-display
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

(defn- transfer-preview
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

(defn- withdraw-preview
  [state modal]
  (let [selected-asset (withdraw-asset state modal)
        flow-kind (:flow-kind selected-asset)
        asset-key (:key selected-asset)
        asset-symbol (or (:symbol selected-asset) "Asset")
        destination-chain (non-blank-text (:hyperunit-source-chain selected-asset))
        amount (parse-input-amount (:amount-input modal))
        destination (if (= flow-kind :hyperunit-address)
                      (normalize-withdraw-destination (:destination-input modal))
                      (normalize-evm-address (:destination-input modal)))
        max-amount (withdraw-max-amount state selected-asset)
        min-amount (withdraw-minimum-amount selected-asset)]
    (cond
      (nil? selected-asset)
      {:ok? false
       :display-message "Select an asset to withdraw."}

      (nil? destination)
      {:ok? false
       :display-message "Enter a valid destination address."}

      (and (= flow-kind :hyperunit-address)
           (not (seq destination-chain)))
      {:ok? false
       :display-message (str "Withdrawal source chain is unavailable for " asset-symbol ".")}

      (<= max-amount 0)
      {:ok? false
       :display-message "No withdrawable balance available."}

      (not (finite-number? amount))
      {:ok? false
       :display-message "Enter a valid amount."}

      (and (finite-number? min-amount)
           (> min-amount 0)
           (< amount min-amount))
      {:ok? false
       :display-message (str "Minimum withdrawal is "
                             (amount->text min-amount)
                             " "
                             asset-symbol
                             ".")}

      (> amount max-amount)
      {:ok? false
       :display-message "Amount exceeds withdrawable balance."}

      :else
      (if (= flow-kind :hyperunit-address)
        {:ok? true
         :request {:action {:type "hyperunitSendAssetWithdraw"
                            :asset (name asset-key)
                            :token asset-symbol
                            :amount (amount->text amount)
                            :destination destination
                            :destinationChain destination-chain
                            :network (:network selected-asset)}}}
        {:ok? true
         :request {:action {:type "withdraw3"
                            :amount (amount->text amount)
                            :destination destination}}}))))

(defn- deposit-preview
  [state modal]
  (let [deposit-step (normalize-deposit-step (:deposit-step modal))
        selected-asset (deposit-asset state modal)
        amount (parse-input-amount (:amount-input modal))
        min-amount (or (:minimum selected-asset) deposit-min-usdc)
        flow-kind (:flow-kind selected-asset)]
    (cond
      (not= deposit-step :amount-entry)
      {:ok? false}

      (nil? selected-asset)
      {:ok? false
       :display-message "Select an asset to deposit."}

      (not (deposit-asset-implemented? selected-asset))
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
                            :chainId deposit-chain-id-mainnet}}}

        (= (:key selected-asset) :usdh)
        {:ok? true
         :request {:action {:type "acrossUsdcToUsdhDeposit"
                            :asset (name (:key selected-asset))
                            :amount (amount->text amount)
                            :chainId deposit-chain-id-mainnet}}}

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

(defn- preview
  [state modal]
  (case (normalize-mode (:mode modal))
    :deposit (deposit-preview state modal)
    :transfer (transfer-preview state modal)
    :withdraw (withdraw-preview state modal)
    {:ok? false
     :display-message "Funding action unavailable."}))

(defn funding-modal-view-model
  [state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        hyperunit-lifecycle (normalize-hyperunit-lifecycle (:hyperunit-lifecycle modal))
        deposit-step (normalize-deposit-step (:deposit-step modal))
        deposit-assets* (deposit-assets-filtered state modal)
        selected-deposit-asset (deposit-asset state modal)
        withdraw-assets* (withdraw-assets state)
        selected-withdraw-asset (withdraw-asset state modal)
        selected-withdraw-asset-key (:key selected-withdraw-asset)
        selected-withdraw-symbol (or (:symbol selected-withdraw-asset) "USDC")
        selected-withdraw-flow-kind (or (:flow-kind selected-withdraw-asset) :unknown)
        selected-deposit-asset-key (:key selected-deposit-asset)
        selected-deposit-flow-kind (or (:flow-kind selected-deposit-asset) :unknown)
        selected-deposit-implemented? (deposit-asset-implemented? selected-deposit-asset)
        generated-address-asset-key (normalize-deposit-asset-key (:deposit-generated-asset-key modal))
        generated-address-active? (and selected-deposit-asset-key
                                       (= generated-address-asset-key selected-deposit-asset-key))
        generated-address (when generated-address-active?
                            (non-blank-text (:deposit-generated-address modal)))
        generated-signatures (when generated-address-active?
                             (:deposit-generated-signatures modal))
        preview-result (preview state modal)
        preview-ok? (:ok? preview-result)
        hyperunit-fee-estimate (normalize-hyperunit-fee-estimate
                                (:hyperunit-fee-estimate modal))
        hyperunit-fee-estimate-loading? (= :loading
                                           (:status hyperunit-fee-estimate))
        hyperunit-fee-estimate-error (non-blank-text
                                      (:error hyperunit-fee-estimate))
        hyperunit-withdrawal-queue (normalize-hyperunit-withdrawal-queue
                                    (:hyperunit-withdrawal-queue modal))
        hyperunit-withdrawal-queue-loading? (= :loading
                                               (:status hyperunit-withdrawal-queue))
        hyperunit-withdrawal-queue-error (non-blank-text
                                          (:error hyperunit-withdrawal-queue))
        deposit-chain (hyperunit-source-chain selected-deposit-asset)
        withdraw-chain (hyperunit-source-chain selected-withdraw-asset)
        deposit-chain-fee (hyperunit-fee-entry hyperunit-fee-estimate deposit-chain)
        withdraw-chain-fee (hyperunit-fee-entry hyperunit-fee-estimate withdraw-chain)
        withdraw-chain-queue (hyperunit-withdrawal-queue-entry
                              hyperunit-withdrawal-queue
                              withdraw-chain)
        withdraw-queue-length (when (and (= selected-withdraw-flow-kind :hyperunit-address)
                                         (map? withdraw-chain-queue))
                                (:withdrawal-queue-length withdraw-chain-queue))
        withdraw-queue-last-operation-tx-id (when (and (= selected-withdraw-flow-kind :hyperunit-address)
                                                       (map? withdraw-chain-queue))
                                              (non-blank-text
                                               (:last-withdraw-queue-operation-tx-id
                                                withdraw-chain-queue)))
        withdraw-queue-last-operation-explorer-url (when (= selected-withdraw-flow-kind :hyperunit-address)
                                                     (hyperunit-explorer-tx-url
                                                      :withdraw
                                                      withdraw-chain
                                                      withdraw-queue-last-operation-tx-id))
        lifecycle-terminal? (hyperunit-lifecycle-terminal? hyperunit-lifecycle)
        lifecycle-outcome (when lifecycle-terminal?
                            (if (hyperunit-lifecycle-failure? hyperunit-lifecycle)
                              :failure
                              :success))
        lifecycle-outcome-label (case lifecycle-outcome
                                  :failure "Needs Attention"
                                  :success "Completed"
                                  nil)
        lifecycle-recovery-hint (when (= lifecycle-outcome :failure)
                                  (hyperunit-lifecycle-recovery-hint hyperunit-lifecycle))
        lifecycle-destination-explorer-url (hyperunit-explorer-tx-url
                                            (:direction hyperunit-lifecycle)
                                            (when (= :withdraw (:direction hyperunit-lifecycle))
                                              withdraw-chain)
                                            (:destination-tx-hash hyperunit-lifecycle))
        deposit-estimated-time (if (= selected-deposit-flow-kind :hyperunit-address)
                                 (or (when hyperunit-fee-estimate-loading? "Loading...")
                                     (non-blank-text (:deposit-eta deposit-chain-fee))
                                     "Depends on source confirmations")
                                 "~10 seconds")
        deposit-network-fee (if (= selected-deposit-flow-kind :hyperunit-address)
                              (or (when hyperunit-fee-estimate-loading? "Loading...")
                                  (estimate-fee-display (:deposit-fee deposit-chain-fee)
                                                        deposit-chain)
                                  "Paid on source chain")
                              "None")
        withdraw-estimated-time (if (= selected-withdraw-flow-kind :hyperunit-address)
                                  (or (when hyperunit-fee-estimate-loading? "Loading...")
                                      (non-blank-text (:withdrawal-eta withdraw-chain-fee))
                                      "Depends on destination chain")
                                  "~10 seconds")
        withdraw-network-fee (if (= selected-withdraw-flow-kind :hyperunit-address)
                               (or (when hyperunit-fee-estimate-loading? "Loading...")
                                   (estimate-fee-display (:withdrawal-fee withdraw-chain-fee)
                                                         withdraw-chain)
                                   "Paid on destination chain")
                               "None")
        transfer-max (transfer-max-amount state modal)
        withdraw-max (withdraw-max-amount state selected-withdraw-asset)
        withdraw-min-amount (withdraw-minimum-amount selected-withdraw-asset)
        max-amount (case mode
                     :transfer transfer-max
                     :withdraw withdraw-max
                     0)
        deposit-step-amount-entry? (= deposit-step :amount-entry)
        preview-message (:display-message preview-result)
        error (:error modal)
        status-message (or error
                           (when (and (not preview-ok?)
                                      (seq preview-message)
                                      (or (not= mode :deposit)
                                          deposit-step-amount-entry?))
                             preview-message))
        submitting? (true? (:submitting? modal))
        submit-disabled? (or submitting?
                            (and (= mode :deposit)
                                 (not deposit-step-amount-entry?))
                            (not preview-ok?))
        legacy-kind (or (:legacy-kind modal) :unknown)
        title (case mode
                :deposit "Deposit"
                :transfer "Perps <-> Spot"
                :withdraw "Withdraw"
                :legacy (str/capitalize (name legacy-kind))
                "Funding")]
    {:open? (true? (:open? modal))
     :mode mode
     :legacy-kind legacy-kind
     :anchor (:anchor modal)
     :title title
     :deposit-step deposit-step
     :deposit-search-input (or (:deposit-search-input modal) "")
     :deposit-assets deposit-assets*
     :deposit-selected-asset selected-deposit-asset
     :deposit-flow-kind selected-deposit-flow-kind
     :deposit-flow-supported? selected-deposit-implemented?
     :deposit-generated-address generated-address
     :deposit-generated-signatures generated-signatures
     :withdraw-assets withdraw-assets*
     :withdraw-selected-asset selected-withdraw-asset
     :withdraw-selected-asset-key selected-withdraw-asset-key
     :withdraw-flow-kind selected-withdraw-flow-kind
     :withdraw-generated-address (non-blank-text (:withdraw-generated-address modal))
     :amount-input (or (:amount-input modal) "")
     :to-perp? (true? (:to-perp? modal))
     :destination-input (or (:destination-input modal) "")
     :hyperunit-lifecycle hyperunit-lifecycle
     :hyperunit-lifecycle-terminal? lifecycle-terminal?
     :hyperunit-lifecycle-outcome lifecycle-outcome
     :hyperunit-lifecycle-outcome-label lifecycle-outcome-label
     :hyperunit-lifecycle-recovery-hint lifecycle-recovery-hint
     :hyperunit-lifecycle-destination-explorer-url lifecycle-destination-explorer-url
     :hyperunit-withdrawal-queue hyperunit-withdrawal-queue
     :max-display (format-usdc-display max-amount)
     :max-input (format-usdc-input max-amount)
     :max-symbol (if (= mode :withdraw) selected-withdraw-symbol "USDC")
     :submitting? submitting?
     :submit-disabled? submit-disabled?
     :preview-ok? preview-ok?
     :status-message status-message
     :deposit-submit-label (if submitting?
                            (if (and (= mode :deposit)
                                     (= selected-deposit-flow-kind :hyperunit-address))
                              "Generating..."
                              "Submitting...")
                            (if preview-ok?
                              (if (and (= mode :deposit)
                                       (= selected-deposit-flow-kind :hyperunit-address))
                                (if (seq generated-address)
                                  "Regenerate address"
                                  "Generate address")
                                "Deposit")
                              (if (and (= mode :deposit)
                                       (not selected-deposit-implemented?))
                                "Deposit unavailable"
                                (or preview-message "Enter a valid amount"))))
     :deposit-quick-amounts deposit-quick-amounts
     :deposit-min-usdc deposit-min-usdc
     :deposit-min-amount (or (:minimum selected-deposit-asset) deposit-min-usdc)
     :deposit-estimated-time deposit-estimated-time
     :deposit-network-fee deposit-network-fee
     :withdraw-estimated-time withdraw-estimated-time
     :withdraw-network-fee withdraw-network-fee
     :withdraw-queue-length withdraw-queue-length
     :withdraw-queue-last-operation-tx-id withdraw-queue-last-operation-tx-id
     :withdraw-queue-last-operation-explorer-url withdraw-queue-last-operation-explorer-url
     :hyperunit-fee-estimate-loading? hyperunit-fee-estimate-loading?
     :hyperunit-fee-estimate-error hyperunit-fee-estimate-error
     :hyperunit-withdrawal-queue-loading? hyperunit-withdrawal-queue-loading?
     :hyperunit-withdrawal-queue-error hyperunit-withdrawal-queue-error
     :submit-label (if submitting?
                     "Submitting..."
                     (case mode
                       :transfer "Transfer"
                       :withdraw "Withdraw"
                       "Confirm"))
     :min-withdraw-usdc withdraw-min-usdc
     :min-withdraw-amount withdraw-min-amount
     :min-withdraw-symbol selected-withdraw-symbol}))

(defn open-funding-deposit-modal
  ([state]
   (open-funding-deposit-modal state nil))
  ([state anchor]
   (let [base (modal-state state)
         anchor* (normalize-anchor anchor)]
     [[:effects/save funding-modal-path
       (-> (default-funding-modal-state)
           (assoc :open? true
                  :mode :deposit
                  :legacy-kind nil
                  :anchor anchor*
                  :deposit-step :asset-select
                  :deposit-search-input ""
                  :deposit-selected-asset-key nil
                  :amount-input ""
                  :destination-input (or (wallet-address state)
                                         (:destination-input base "")
                                         "")))]
      [:effects/api-fetch-hyperunit-fee-estimate]])))

(defn open-funding-transfer-modal
  ([state]
   (open-funding-transfer-modal state nil))
  ([state anchor]
   (let [anchor* (normalize-anchor anchor)]
     [[:effects/save funding-modal-path
       (-> (default-funding-modal-state)
           (assoc :open? true
                  :mode :transfer
                  :anchor anchor*
                  :to-perp? true
                  :destination-input (or (wallet-address state) "")))]])))

(defn open-funding-withdraw-modal
  ([state]
   (open-funding-withdraw-modal state nil))
  ([state anchor]
   (let [base (modal-state state)
         anchor* (normalize-anchor anchor)
         selected-asset-key (or (normalize-withdraw-asset-key
                                 (:withdraw-selected-asset-key base))
                                withdraw-default-asset-key)]
     [[:effects/save funding-modal-path
       (-> (default-funding-modal-state)
           (assoc :open? true
                  :mode :withdraw
                  :anchor anchor*
                  :withdraw-selected-asset-key selected-asset-key
                  :destination-input (or (wallet-address state) "")))]
      [:effects/api-fetch-hyperunit-withdrawal-queue]
      [:effects/api-fetch-hyperunit-fee-estimate]])))

(defn- open-legacy-funding-modal
  [state legacy-kind]
  (let [legacy* (if (keyword? legacy-kind)
                  legacy-kind
                  (keyword (str/lower-case (str (or legacy-kind "unknown")))))]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :legacy
                 :legacy-kind legacy*
                 :destination-input (or (wallet-address state) "")))]]))

(defn close-funding-modal
  [_state]
  [[:effects/save funding-modal-path (default-funding-modal-state)]])

(defn handle-funding-modal-keydown
  [state key]
  (if (= key "Escape")
    (close-funding-modal state)
    []))

(defn set-funding-modal-field
  [state path value]
  (let [modal (modal-state state)
        path* (if (vector? path) path [path])
        value* (case path*
                 [:amount-input] (normalize-amount-input value)
                 [:destination-input] (str (or value ""))
                 [:deposit-search-input] (str (or value ""))
                 [:deposit-step] (normalize-deposit-step value)
                 [:deposit-selected-asset-key] (normalize-deposit-asset-key value)
                 [:withdraw-selected-asset-key] (or (normalize-withdraw-asset-key value)
                                                    withdraw-default-asset-key)
                 value)
        clear-hyperunit-lifecycle? (or (= path* [:amount-input])
                                       (= path* [:destination-input])
                                       (= path* [:deposit-selected-asset-key])
                                       (= path* [:withdraw-selected-asset-key])
                                       (and (= path* [:deposit-step])
                                            (= value* :asset-select)))
        next-modal (cond-> (-> modal
                               (assoc-in path* value*)
                               (assoc :error nil))
                     clear-hyperunit-lifecycle?
                     (assoc :hyperunit-lifecycle (default-hyperunit-lifecycle-state))

                     clear-hyperunit-lifecycle?
                     (assoc :withdraw-generated-address nil)

                     (= path* [:withdraw-selected-asset-key])
                     (assoc :hyperunit-withdrawal-queue
                            (default-hyperunit-withdrawal-queue-state))

                     (= path* [:deposit-selected-asset-key])
                     (assoc :deposit-step :amount-entry
                            :amount-input ""
                            :deposit-generated-address nil
                            :deposit-generated-signatures nil
                            :deposit-generated-asset-key nil)

                     (and (= path* [:deposit-step])
                          (= value* :asset-select))
                     (assoc :amount-input ""
                            :deposit-generated-address nil
                            :deposit-generated-signatures nil
                            :deposit-generated-asset-key nil))
        next-mode (normalize-mode (:mode next-modal))
        refresh-estimate? (and (contains? #{:deposit :withdraw} next-mode)
                               (or (= path* [:deposit-selected-asset-key])
                                   (= path* [:withdraw-selected-asset-key])))
        refresh-withdraw-queue? (and (= next-mode :withdraw)
                                     (= path* [:withdraw-selected-asset-key]))]
    (cond-> [[:effects/save funding-modal-path next-modal]]
      refresh-estimate?
      (conj [:effects/api-fetch-hyperunit-fee-estimate])

      refresh-withdraw-queue?
      (conj [:effects/api-fetch-hyperunit-withdrawal-queue]))))

(defn set-hyperunit-lifecycle
  [state lifecycle]
  (let [modal (modal-state state)]
    [[:effects/save funding-modal-path
      (-> modal
          (assoc :hyperunit-lifecycle (normalize-hyperunit-lifecycle lifecycle)
                 :error nil))]]))

(defn clear-hyperunit-lifecycle
  [state]
  (let [modal (modal-state state)]
    [[:effects/save funding-modal-path
      (assoc modal :hyperunit-lifecycle (default-hyperunit-lifecycle-state))]]))

(defn set-hyperunit-lifecycle-error
  [state error]
  (let [modal (modal-state state)
        lifecycle (normalize-hyperunit-lifecycle (:hyperunit-lifecycle modal))]
    [[:effects/save funding-modal-path
      (assoc modal :hyperunit-lifecycle (assoc lifecycle
                                               :error (non-blank-text error)))]]))

(defn set-funding-transfer-direction
  [state to-perp?]
  (let [modal (modal-state state)]
    [[:effects/save funding-modal-path
      (-> modal
          (assoc :to-perp? (true? to-perp?)
                 :error nil))]]))

(defn set-funding-amount-to-max
  [state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        max-amount (case mode
                     :transfer (transfer-max-amount state modal)
                     :withdraw (withdraw-max-amount state (withdraw-asset state modal))
                     0)]
    [[:effects/save funding-modal-path
      (-> modal
          (assoc :amount-input (format-usdc-input max-amount)
                 :error nil))]]))

(defn submit-funding-transfer
  [state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        result (if (= :transfer mode)
                 (transfer-preview state modal)
                 {:ok? false
                  :display-message "Transfer modal unavailable."})]
    (if-not (:ok? result)
      [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                            [(conj funding-modal-path :error) (:display-message result)]]]]
      [[:effects/save-many [[(conj funding-modal-path :submitting?) true]
                            [(conj funding-modal-path :error) nil]]]
       [:effects/api-submit-funding-transfer (:request result)]])))

(defn submit-funding-withdraw
  [state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        result (if (= :withdraw mode)
                 (withdraw-preview state modal)
                 {:ok? false
                  :display-message "Withdraw modal unavailable."})]
    (if-not (:ok? result)
      [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                            [(conj funding-modal-path :error) (:display-message result)]]]]
      [[:effects/save-many [[(conj funding-modal-path :submitting?) true]
                            [(conj funding-modal-path :error) nil]]]
       [:effects/api-submit-funding-withdraw (:request result)]])))

(defn submit-funding-deposit
  [state]
  (let [modal (modal-state state)
        mode (normalize-mode (:mode modal))
        result (if (= :deposit mode)
                 (deposit-preview state modal)
                 {:ok? false
                  :display-message "Deposit modal unavailable."})]
    (if-not (:ok? result)
      [[:effects/save-many [[(conj funding-modal-path :submitting?) false]
                            [(conj funding-modal-path :error) (:display-message result)]]]]
      [[:effects/save-many [[(conj funding-modal-path :submitting?) true]
                            [(conj funding-modal-path :error) nil]]]
       [:effects/api-submit-funding-deposit (:request result)]])))

(defn set-funding-modal-compat
  [state modal]
  (let [mode (cond
               (keyword? modal) modal
               (string? modal) (keyword (str/lower-case (str/trim modal)))
               :else nil)]
    (case mode
      nil (close-funding-modal state)
      :deposit (open-funding-deposit-modal state)
      :withdraw (open-funding-withdraw-modal state)
      :send (open-funding-transfer-modal state)
      :transfer (open-funding-transfer-modal state)
      (open-legacy-funding-modal state mode))))
