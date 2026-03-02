(ns hyperopen.funding.actions
  (:require [clojure.string :as str]
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

(defn- normalize-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- wallet-address
  [state]
  (normalize-address (get-in state [:wallet :address])))

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

(defn- normalize-deposit-asset-key
  [value]
  (let [asset-key (cond
                    (keyword? value) value
                    (string? value) (some-> value str/trim str/lower-case keyword)
                    :else nil)]
    (when (contains? deposit-asset-keys asset-key)
      asset-key)))

(defn- normalize-lifecycle-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (some-> value str/trim str/lower-case keyword)
                    :else nil)]
    (when (contains? #{:deposit :withdraw} direction)
      direction)))

(defn- normalize-lifecycle-keyword
  [value]
  (cond
    (keyword? value) value
    (string? value)
    (let [text (-> value
                   str
                   str/trim
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" "-")
                   (str/replace #"^-+|-+$" ""))]
      (when (seq text)
        (keyword text)))
    :else nil))

(defn- normalize-lifecycle-non-negative-int
  [value]
  (let [parsed (parse-num value)]
    (when (and (finite-number? parsed)
               (>= parsed 0))
      (js/Math.floor parsed))))

(defn default-hyperunit-lifecycle-state
  []
  {:direction nil
   :asset-key nil
   :operation-id nil
   :state nil
   :status nil
   :source-tx-confirmations nil
   :destination-tx-confirmations nil
   :position-in-withdraw-queue nil
   :destination-tx-hash nil
   :state-next-at nil
   :last-updated-ms nil
   :error nil})

(defn normalize-hyperunit-lifecycle
  [lifecycle]
  (let [lifecycle* (if (map? lifecycle) lifecycle {})]
    {:direction (normalize-lifecycle-direction (:direction lifecycle*))
     :asset-key (normalize-deposit-asset-key (:asset-key lifecycle*))
     :operation-id (non-blank-text (:operation-id lifecycle*))
     :state (normalize-lifecycle-keyword (:state lifecycle*))
     :status (normalize-lifecycle-keyword (:status lifecycle*))
     :source-tx-confirmations (normalize-lifecycle-non-negative-int (:source-tx-confirmations lifecycle*))
     :destination-tx-confirmations (normalize-lifecycle-non-negative-int (:destination-tx-confirmations lifecycle*))
     :position-in-withdraw-queue (normalize-lifecycle-non-negative-int (:position-in-withdraw-queue lifecycle*))
     :destination-tx-hash (non-blank-text (:destination-tx-hash lifecycle*))
     :state-next-at (normalize-lifecycle-non-negative-int (:state-next-at lifecycle*))
     :last-updated-ms (normalize-lifecycle-non-negative-int (:last-updated-ms lifecycle*))
     :error (non-blank-text (:error lifecycle*))}))

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

(defn default-funding-modal-state
  []
  {:open? false
   :mode nil
   :legacy-kind nil
   :deposit-step :asset-select
   :deposit-search-input ""
   :deposit-selected-asset-key nil
   :deposit-generated-address nil
   :deposit-generated-signatures nil
   :deposit-generated-asset-key nil
   :amount-input ""
   :to-perp? true
   :destination-input ""
   :hyperunit-lifecycle (default-hyperunit-lifecycle-state)
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
           :hyperunit-lifecycle (normalize-hyperunit-lifecycle
                                 (:hyperunit-lifecycle modal)))))

(defn modal-open?
  [state]
  (true? (:open? (modal-state state))))

(defn- usdc-coin?
  [coin]
  (and (string? coin)
       (str/starts-with? (str/upper-case (str/trim coin)) "USDC")))

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
  [state]
  (perps-withdrawable state))

(defn- format-usdc-display
  [value]
  (.toLocaleString (js/Number. (max 0 (or (parse-num value) 0)))
                   "en-US"
                   #js {:minimumFractionDigits 2
                        :maximumFractionDigits 2}))

(defn- format-usdc-input
  [value]
  (amount->text (max 0 (or (parse-num value) 0))))

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
  (let [amount (parse-input-amount (:amount-input modal))
        destination (normalize-address (:destination-input modal))
        max-amount (withdraw-max-amount state)]
    (cond
      (nil? destination)
      {:ok? false
       :display-message "Enter a valid destination address."}

      (<= max-amount 0)
      {:ok? false
       :display-message "No withdrawable balance available."}

      (not (finite-number? amount))
      {:ok? false
       :display-message "Enter a valid amount."}

      (< amount withdraw-min-usdc)
      {:ok? false
       :display-message (str "Minimum withdrawal is " withdraw-min-usdc " USDC.")}

      (> amount max-amount)
      {:ok? false
       :display-message "Amount exceeds withdrawable balance."}

      :else
      {:ok? true
       :request {:action {:type "withdraw3"
                          :amount (amount->text amount)
                          :destination destination}}})))

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
        transfer-max (transfer-max-amount state modal)
        withdraw-max (withdraw-max-amount state)
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
     :title title
     :deposit-step deposit-step
     :deposit-search-input (or (:deposit-search-input modal) "")
     :deposit-assets deposit-assets*
     :deposit-selected-asset selected-deposit-asset
     :deposit-flow-kind selected-deposit-flow-kind
     :deposit-flow-supported? selected-deposit-implemented?
     :deposit-generated-address generated-address
     :deposit-generated-signatures generated-signatures
     :amount-input (or (:amount-input modal) "")
     :to-perp? (true? (:to-perp? modal))
     :destination-input (or (:destination-input modal) "")
     :hyperunit-lifecycle hyperunit-lifecycle
     :max-display (format-usdc-display max-amount)
     :max-input (format-usdc-input max-amount)
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
     :submit-label (if submitting?
                     "Submitting..."
                     (case mode
                       :transfer "Transfer"
                       :withdraw "Withdraw"
                       "Confirm"))
     :min-withdraw-usdc withdraw-min-usdc}))

(defn open-funding-deposit-modal
  [state]
  (let [base (modal-state state)]
    [[:effects/save funding-modal-path
      (-> (default-funding-modal-state)
          (assoc :open? true
                 :mode :deposit
                 :legacy-kind nil
                 :deposit-step :asset-select
                 :deposit-search-input ""
                 :deposit-selected-asset-key nil
                 :amount-input ""
                 :destination-input (or (wallet-address state)
                                        (:destination-input base "")
                                        "")))]]))

(defn open-funding-transfer-modal
  [state]
  [[:effects/save funding-modal-path
    (-> (default-funding-modal-state)
        (assoc :open? true
               :mode :transfer
               :to-perp? true
               :destination-input (or (wallet-address state) "")))]])

(defn open-funding-withdraw-modal
  [state]
  [[:effects/save funding-modal-path
    (-> (default-funding-modal-state)
        (assoc :open? true
               :mode :withdraw
               :destination-input (or (wallet-address state) "")))]])

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
                 value)
        clear-hyperunit-lifecycle? (or (= path* [:amount-input])
                                       (= path* [:destination-input])
                                       (= path* [:deposit-selected-asset-key])
                                       (and (= path* [:deposit-step])
                                            (= value* :asset-select)))
        next-modal (cond-> (-> modal
                               (assoc-in path* value*)
                               (assoc :error nil))
                     clear-hyperunit-lifecycle?
                     (assoc :hyperunit-lifecycle (default-hyperunit-lifecycle-state))

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
                            :deposit-generated-asset-key nil))]
    [[:effects/save funding-modal-path next-modal]]))

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
                     :withdraw (withdraw-max-amount state)
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
