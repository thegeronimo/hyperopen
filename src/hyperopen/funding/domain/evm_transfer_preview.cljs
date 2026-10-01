(ns hyperopen.funding.domain.evm-transfer-preview
  "Previews and requests for Transfer routes that touch HyperEVM.

   A preview is `{:ok? :display-message :request :blocked :notice}`.
   `:blocked` is `{:code :message :title :fix? :explorer-url}` and explains
   why the route cannot move right now, independent of the amount, so the
   modal can show it (and the gas fix) before anything is typed. Unknown
   HyperEVM data always blocks as `:hyperevm-unavailable` (\"Checking…\"),
   never as a missing-gas or empty-bridge block.

   Requests:

   - Core -> EVM is an ordinary `sendAsset` to the token's system address.
   - EVM -> Core is the client-only pseudo action `hyperEvmToCore`, which the
     submit effect turns into HyperEVM transactions; it is never posted to
     Hyperliquid.

   Client-only keys (`:route`, `:evm`, `:purpose`) stay at the request's top
   level, because the exchange client posts `:action` verbatim."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.evm-transfer-amounts :as evm-amounts]
            [hyperopen.funding.domain.availability :as availability]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.hyperevm.domain.units :as units]))

(def gas-topup-amount
  "HYPE the one-click gas fix sends from Spot to HyperEVM. HYPE moves to
   HyperEVM without a fee."
  "0.05")

(def messages
  {:invalid-route "Choose where to move funds from and to."
   :pooled-perps transfer-route/pooled-perps-evm-reason
   :perps-to-evm transfer-route/perps-to-evm-reason
   :no-provider "Reconnect your wallet to send HyperEVM transactions."
   :no-chain-switch (str "Your wallet couldn't switch to HyperEVM (chain 999), so it can't send this "
                         "transfer. Switch networks in your wallet, or try again.")
   :in-flight transfer-state/in-flight-message
   :meta-missing "Token details haven't loaded yet. Try again in a moment."
   :checking-balances "Checking HyperEVM balances…"
   :balances-unavailable "HyperEVM balances are unavailable right now."
   :balances-partial "Some HyperEVM balances couldn't be read. Retrying…"
   :checking-spot "Checking your Spot balances…"
   :checking-perps "Checking your Perps balance…"
   :checking-bridge "Checking the bridge balance…"
   :bridge-unavailable "The bridge balance couldn't be read. Retrying…"
   :checking-account "Checking your HyperCore account…"
   :bridge-empty-evm transfer-route/bridge-empty-evm-reason
   :bridge-empty-core transfer-route/bridge-empty-core-reason
   :core-account-missing (str "Your HyperCore account isn't active yet. Move more than 1 USDC from "
                              "HyperEVM to Spot first (1 USDC is the one-time activation fee), then "
                              "move this token.")
   :no-evm-gas-title "You need HYPE on HyperEVM to pay gas"})

(defn- block
  ([code message]
   (block code message nil))
  ([code message extra]
   (merge {:code code :message message :title nil :fix? false :explorer-url nil}
          extra)))

(defn blocked-result
  [blocked]
  {:ok? false :display-message (:message blocked) :blocked blocked})

;; --- context ------------------------------------------------------------------

(defn- entry-read?
  [entry]
  (string? (:native-wei entry)))

(defn approx-hype
  "An amount of HYPE rounded UP to two significant digits for display, so the
   estimate never understates what is needed."
  [text]
  (when-let [value (units/parse-units text 18)]
    (if (= units/zero value)
      "0"
      (let [digits (count (.toString value))
            drop (max 0 (- digits 2))
            step (units/pow10 drop)
            rounded (* (/ (+ value (- step (js/BigInt 1))) step) step)]
        (units/format-units rounded 18)))))

(defn- identity-block
  [state]
  (cond
    (account-context/inspected-account-read-only? state)
    (block :read-only (account-context/mutations-blocked-message state))

    (some? (account-context/selected-subaccount-address state))
    (block :subaccount account-context/hyperevm-master-only-message)

    (nil? (account-context/owner-address state))
    (block :no-owner account-context/hyperevm-connect-wallet-message)

    :else nil))

(defn- wallet-blocks
  "EVM -> Core needs a wallet that can sign HyperEVM transactions, and no
   earlier one still confirming."
  [state owner]
  (let [in-flight (transfer-state/in-flight-entry state owner)]
    (cond
      (false? (get-in state [:wallet :connected?]))
      (block :no-provider (:no-provider messages))

      (some? in-flight)
      (block :in-flight (transfer-state/in-flight-blocked-message in-flight)
             {:explorer-url (transfer-state/in-flight-tx-url in-flight)})

      (transfer-state/chain-switch-unsupported? state)
      (block :no-chain-switch (:no-chain-switch messages) {:fix? true})

      :else nil)))

(defn- entry-block
  [entry]
  (when-not (entry-read? entry)
    (block :hyperevm-unavailable
           (if (hyperevm-balances/first-read-failed? entry)
             (:balances-unavailable messages)
             (:checking-balances messages)))))

(defn- source-block
  "Before an asset is chosen: why the route's source can't list what it
   holds yet (its balances are still loading or couldn't be read), or nil.
   The asset list says nothing about an unknown source, so it never claims
   the user holds nothing there."
  [state route owner]
  (let [source (:from route)]
    (cond
      (= :hyperevm source)
      (or (entry-block (hyperevm-balances/entry state owner))
          ;; A token never answered is unknown, so the list's empty or
          ;; short state would claim the user holds nothing of it.
          (when (hyperevm-balances/partial-read? state owner)
            (block :hyperevm-unavailable (:balances-partial messages))))

      (and (contains? #{:spot :perps} source)
           (not (transfer-balances/location-known? state source owner)))
      (block :hyperevm-unavailable (if (= :spot source)
                                     (:checking-spot messages)
                                     (:checking-perps messages)))

      :else nil)))

(defn- gas-block
  [state owner token]
  (let [kinds (evm-amounts/gas-kinds token)
        status (hyperevm-balances/evm-gas-status state owner kinds)]
    (when (contains? #{:none :low} status)
      (let [entry (hyperevm-balances/entry state owner)
            have (or (hyperevm-balances/native-hype-text state owner) "0")
            needed (approx-hype (fees/evm-tx-cost-hype (:gas-price-wei entry) kinds))]
        (block :no-evm-gas
               (str "You have " have " HYPE there."
                    (when needed (str " This move needs about " needed " HYPE.")))
               {:title (:no-evm-gas-title messages) :fix? true})))))

(defn core-fee-hype
  "Spot-HYPE fee for moving `token` Core -> EVM at the owner's last read gas
   price, or nil when unknown. \"0\" for HYPE."
  [state token]
  (let [owner (account-context/owner-address state)
        entry (hyperevm-balances/entry state owner)]
    (fees/core->evm-fee-hype (:gas-price-wei entry) token)))

(defn- hype-token
  [state]
  (tokens/token-by-name (get-in state [:spot :meta]) "HYPE"))

(defn- core-fee-block
  [state token]
  (let [fee (core-fee-hype state token)]
    (when (and fee (transfer-balances/positive-text? fee))
      (let [spot-hype (some->> (hype-token state)
                               (transfer-balances/spot-available-text state))]
        (cond
          (nil? spot-hype)
          (block :hyperevm-unavailable (:checking-spot messages))

          (pos? (transfer-balances/compare-text fee spot-hype))
          (block :no-core-fee
                 (str "You need about " fee " HYPE on Spot to pay the HyperEVM transfer fee."))

          :else nil)))))

(defn- capacity-blocks
  "`[definitive checking]` capacity blocks: an empty bridge side, or a
   capacity not yet known. A HyperCore read that failed says so rather than
   \"Checking…\"; the Transfer commands and the balance poller read it
   again (`bridge/core-capacity-refresh-due?`)."
  [state route token now-ms]
  (let [cap (evm-amounts/capacity state route token now-ms)
        kind (transfer-route/route-kind route)]
    (cond
      (= "0" cap)
      [(block :bridge-empty (if (= :core->evm kind)
                              (:bridge-empty-evm messages)
                              (:bridge-empty-core messages)))
       nil]

      (nil? cap)
      [nil (block :hyperevm-unavailable
                  (if (and (= :evm->core kind) (bridge/core-capacity-error state token))
                    (:bridge-unavailable messages)
                    (:checking-bridge messages)))]

      :else [nil nil])))

(defn- activation-status
  [state]
  (account-context/core-account-activation-status state))

(defn- route-block
  [state route]
  (when-not (:valid? route)
    (block :invalid-route
           (cond
             (and (= :hyperevm (:from route)) (= :perps (:to route)))
             transfer-route/evm-to-perps-reason

             (and (= :perps (:from route)) (= :hyperevm (:to route)))
             (if (availability/pooled-perps-collateral? state)
               (:pooled-perps messages)
               (:perps-to-evm messages))

             :else (:invalid-route messages)))))

(defn blocked-reason
  "Why the route cannot move `token` right now, or nil. Evaluated before and
   independent of the amount. `token` may be nil (no asset chosen yet)."
  [state modal route token now-ms]
  (let [kind (transfer-route/route-kind route)
        owner (account-context/owner-address state)
        evm->core? (= :evm->core kind)]
    (or (route-block state route)
        (identity-block state)
        (when evm->core? (wallet-blocks state owner))
        (when (empty? (transfer-route/linked-tokens state))
          (block :meta-missing (:meta-missing messages)))
        (when (and (some? (:transfer-asset modal)) (nil? token))
          (block :meta-missing (:meta-missing messages)))
        (when (nil? token)
          (source-block state route owner))
        (when token
          (let [health (bridge/token-health-status state token)
                [empty-block capacity-checking] (capacity-blocks state route token now-ms)
                status (activation-status state)]
            (or (when (= :bad health)
                  (block :unmovable-token transfer-route/unmovable-token-reason))
                (when (and (= :core->evm kind) (nil? (:wire-id token)))
                  (block :meta-missing (:meta-missing messages)))
                (entry-block (hyperevm-balances/entry state owner))
                empty-block
                (if evm->core?
                  (gas-block state owner token)
                  (core-fee-block state token))
                (when (and evm->core? (= :missing status) (not= :usdc-cdw (:kind token)))
                  (block :core-account-missing (:core-account-missing messages)))
                (when (= :unknown health)
                  (block :hyperevm-unavailable transfer-route/checking-token-reason))
                capacity-checking
                (when (and evm->core? (nil? status))
                  (block :hyperevm-unavailable (:checking-account messages)))))))))

;; --- requests -----------------------------------------------------------------

(def ^:private evm->core-kind-wire
  {:native "native"
   :erc20 "erc20"
   :usdc-cdw "usdcCoreDeposit"})

(defn- evm-meta
  [state token amount]
  {:token-index (:index token)
   :symbol (:name token)
   :amount amount
   :owner (account-context/owner-address state)
   :chain-id (:chain-id chain/mainnet)})

(defn core->evm-request
  "A HyperCore -> HyperEVM `sendAsset`. It always leaves Spot: a send from
   the Perps dex to a system address is not a supported route (see
   `transfer-route/supported-pair?`), and the pre-signing invariant refuses
   any other `sourceDex`."
  [state route token amount]
  {:action {:type "sendAsset"
            :destination (:system-address token)
            :sourceDex "spot"
            :destinationDex "spot"
            :token (:wire-id token)
            :amount amount
            :fromSubAccount ""}
   :route :core->evm
   :evm (assoc (evm-meta state token amount) :from (:from route))})

(defn evm->core-request
  [state token amount]
  {:action {:type "hyperEvmToCore"
            :kind (get evm->core-kind-wire (:kind token))
            :tokenIndex (:index token)
            :symbol (:name token)
            :tokenAddress (:erc20-address token)
            :spender (:spender token)
            :recipient (:evm->core-recipient token)
            :amount amount
            :units (units/units-text (units/parse-units amount (:evm-decimals token)))
            :destinationDex (get-in chain/mainnet [:core-deposit-destination-dex :spot])
            :chainId (:chain-id chain/mainnet)}
   :route :evm->core
   :evm (assoc (evm-meta state token amount)
               :gas-kinds (evm-amounts/gas-kinds token))})

(defn evm-transfer-preview
  "Preview a Transfer route that touches HyperEVM. `now-ms` is the clock
   used to age the HyperCore bridge capacity; without it the capacity is
   trusted regardless of age, so production callers always pass it."
  ([state modal]
   (evm-transfer-preview state modal nil))
  ([state modal now-ms]
   (let [route (transfer-route/transfer-route modal)
         token (transfer-route/route-token state modal)
         blocked (blocked-reason state modal route token now-ms)]
     (cond
       blocked
       (blocked-result blocked)

       (nil? token)
       {:ok? false}

       :else
       (let [evm->core? (= :evm->core (transfer-route/route-kind route))
             activation-fee? (and evm->core?
                                  (= :usdc-cdw (:kind token))
                                  (= :missing (activation-status state)))
             result (evm-amounts/validate-amount state route token modal now-ms
                                                 {:activation-fee? activation-fee?})]
         (if-not (:ok? result)
           result
           {:ok? true
            :notice (:notice result)
            :request (if evm->core?
                       (evm->core-request state token (:amount result))
                       (core->evm-request state route token (:amount result)))}))))))

(defn gas-topup-request
  "The one-click gas fix: 0.05 HYPE from Spot to HyperEVM as a `sendAsset`.
   `{:ok? true :request ..}` or `{:ok? false :display-message ..}`."
  [state]
  (let [hype (hype-token state)
        spot-hype (when hype (transfer-balances/spot-available-text state hype))
        identity (identity-block state)]
    (cond
      identity
      {:ok? false :display-message (:message identity)}

      (or (nil? hype) (nil? (:wire-id hype)))
      {:ok? false :display-message (:meta-missing messages)}

      (nil? spot-hype)
      {:ok? false :display-message (:checking-spot messages)}

      (neg? (transfer-balances/compare-text spot-hype gas-topup-amount))
      {:ok? false
       :display-message (str "You need at least " gas-topup-amount " HYPE on Spot to add gas.")}

      :else
      {:ok? true
       :request (assoc (core->evm-request state {:from :spot :to :hyperevm} hype gas-topup-amount)
                       :purpose :gas-topup)})))
