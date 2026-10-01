(ns hyperopen.hyperevm.domain.tokens
  "The catalog of spot tokens linked between HyperCore and HyperEVM.

   Built from Hyperliquid's `spotMeta` payload, which app state stores
   verbatim at `[:spot :meta]`. Each `:tokens` entry looks like

       {:name \"PURR\" :index 1 :tokenId \"0xc1fb…\" :weiDecimals 5
        :evmContract {:address \"0x9b49…\" :evm_extra_wei_decimals 13}}

   and the info client keywordizes it while keeping the snake_case key.

   Four traps shape this namespace (all verified live on 2026-09-30):

   - HYPE is linked but mainnet reports `evmContract null` (testnet reports
     the zero address). HYPE is native on HyperEVM, so it is found by NAME,
     never by a hard-coded index (150 on mainnet, 1105 on testnet), and it
     always has 18 EVM decimals.
   - USDC's `evmContract.address` is Circle's CoreDepositWallet, not an
     ERC-20. Balances are read from the chain's native USDC instead, and the
     CoreDepositWallet becomes the approve spender, but only when it is the
     chain config's known CoreDepositWallet.
   - A token's position in the `:tokens` array is not its index; they diverge
     from position 458 on mainnet (FUNT is index 478). Everything is keyed by
     `:index`.
   - HYPE's system address is 0x2222…2222. Sending any other token there
     loses it, so the generic `system-address` rule never applies to HYPE."
  (:require [clojure.string :as str]
            [hyperopen.hyperevm.domain.chain :as chain]))

(def ^:private hype-name "HYPE")
(def ^:private usdc-name "USDC")

(def ^:private native-evm-decimals
  "Native HYPE on HyperEVM uses 18 decimals, whatever spotMeta reports."
  18)

(def ^:private usdc-evm-decimals
  "Circle's native USDC ERC-20 has 6 decimals. It matches USDC's
   weiDecimals 8 + evm_extra_wei_decimals -2 on both networks."
  6)

(def ^:private max-evm-decimals 36)

(def ^:private verified-token-names
  "Tokens whose HyperEVM contract the app vouches for. Everything else shows a
   contract-verification notice: on-chain `decimals()` disagrees with
   spotMeta for some linked tokens (HOPE returns 0, JOFF reverts)."
  #{"HYPE" "PURR" "USDC"})

(defn- field
  "Read `k` from a map whose keys may be keywords or strings."
  [m k]
  (when (map? m)
    (let [value (get m k)]
      (if (some? value) value (get m (name k))))))

(defn- non-blank-text
  [value]
  (when (string? value)
    (let [text (str/trim value)]
      (when (seq text) text))))

(defn- integer-number?
  [value]
  (and (number? value) (js/Number.isInteger value)))

(defn- index-number?
  [value]
  (and (integer-number? value) (not (neg? value))))

(defn- evm-decimals-in-range?
  [value]
  (and (integer-number? value) (<= 0 value max-evm-decimals)))

(defn- core-precision
  "Most decimals an amount can carry and still convert exactly both ways."
  [wei-decimals evm-decimals]
  (min wei-decimals evm-decimals))

(defn- evm-address
  [value]
  (when-let [text (some-> (non-blank-text value) str/lower-case)]
    (when (re-matches #"^0x[0-9a-f]{40}$" text)
      text)))

(defn system-address
  "The HyperEVM system address bridging the spot token at `index`: `0x20`
   followed by the index in lowercase hex, left-padded to 38 digits. Index 0
   (USDC) is 0x2000…0000 and index 1 (PURR) is 0x2000…0001.

   This is the generic rule. HYPE's system address is 0x2222…2222 and never
   comes from here. Returns nil for a negative or non-integer index."
  [index]
  (when (index-number? index)
    (let [hex (.toString index 16)]
      (when (<= (count hex) 38)
        (str "0x20" (apply str (repeat (- 38 (count hex)) "0")) hex)))))

(defn system-address?
  "Whether `address` is a HyperEVM system address: HYPE's 0x2222…2222, or
   `0x20`, 34 zeros and a four-hex-digit token index. This is the account
   ledger's `token-system-address?` shape (the tokens test pins that the two
   agree), kept here so the funding domain need not load the ledger
   derivations into the main bundle."
  [address]
  (boolean
   (when-let [address* (evm-address address)]
     (or (= (:hype-system-address chain/mainnet) address*)
         (and (str/starts-with? address* "0x20")
              (some? (re-matches #"0+" (subs address* 4 38))))))))

(defn- token-kind
  [name* index evm-contract]
  (cond
    (= hype-name name*) :native
    (and (= usdc-name name*) (= 0 index) (some? evm-contract)) :usdc-cdw
    (some? evm-contract) :erc20
    :else nil))

(defn- evm-decimals
  [kind wei-decimals extra]
  (case kind
    :native native-evm-decimals
    :usdc-cdw usdc-evm-decimals
    :erc20 (when (integer-number? extra)
             (+ wei-decimals extra))
    nil))

(defn- linked-token
  [chain-config entry]
  (let [name* (non-blank-text (field entry :name))
        index (field entry :index)
        token-id (non-blank-text (field entry :tokenId))
        wei-decimals (field entry :weiDecimals)
        evm-contract (let [contract (field entry :evmContract)]
                       (when (map? contract) contract))
        contract-address (evm-address (field evm-contract :address))
        extra (field evm-contract :evm_extra_wei_decimals)
        kind (when (and name*
                        (index-number? index)
                        (integer-number? wei-decimals))
               (token-kind name* index evm-contract))
        evm-decimals* (evm-decimals kind wei-decimals extra)]
    (when (and kind
               (evm-decimals-in-range? evm-decimals*)
               (or (= :native kind) contract-address)
               ;; The USDC approve grants this address a spend allowance, so
               ;; it must be the chain's known CoreDepositWallet. A mismatch
               ;; means spotMeta and chain config are from different networks.
               (or (not= :usdc-cdw kind)
                   (= contract-address (:usdc-core-deposit-wallet chain-config))))
      {:index index
       :name name*
       :token-id token-id
       :wire-id (when token-id (str name* ":" token-id))
       :wei-decimals wei-decimals
       :evm-decimals evm-decimals*
       :core-precision (core-precision wei-decimals evm-decimals*)
       :kind kind
       :erc20-address (case kind
                        :native nil
                        :usdc-cdw (:usdc-token-address chain-config)
                        contract-address)
       :spender (when (= :usdc-cdw kind) contract-address)
       :system-address (if (= :native kind)
                         (:hype-system-address chain-config)
                         (system-address index))
       :evm->core-recipient (case kind
                              :native (:hype-system-address chain-config)
                              :erc20 (system-address index)
                              ;; A plain USDC transfer to 0x2000…0000 is not
                              ;; credited; USDC goes through `deposit` on
                              ;; the CoreDepositWallet (`:spender`).
                              :usdc-cdw nil)
       :verified? (contains? verified-token-names name*)})))

(defn- build-catalog
  [spot-meta chain-config]
  (let [entries (field spot-meta :tokens)
        tokens (->> (when (sequential? entries) entries)
                    (keep #(linked-token chain-config %))
                    ;; spotMeta names and indexes are unique; keep the first
                    ;; row per index so a malformed payload cannot yield two.
                    (reduce (fn [acc token]
                              (if (contains? acc (:index token))
                                acc
                                (assoc acc (:index token) token)))
                            {}))
        sorted (vec (sort-by :index (vals tokens)))]
    {:tokens sorted
     :by-index tokens
     :by-name (into {} (map (juxt (comp str/upper-case :name) identity)) sorted)}))

(def ^:private catalog-cache
  (atom nil))

(defn- catalog
  "The linked-token catalog for `spot-meta`, memoized on the `identical?`
   spot-meta and chain config. app state replaces `[:spot :meta]` wholesale
   when it reloads, so identity is the right cache key and the catalog is
   rebuilt only when the payload actually changes."
  [spot-meta chain-config]
  (let [cached @catalog-cache]
    (if (and cached
             (identical? spot-meta (:spot-meta cached))
             (identical? chain-config (:chain cached)))
      (:catalog cached)
      (let [result (build-catalog spot-meta chain-config)]
        (reset! catalog-cache {:spot-meta spot-meta
                               :chain chain-config
                               :catalog result})
        result))))

(defn linked-tokens
  "Linked tokens from `spot-meta`, sorted by `:index`. Each is

       {:index :name :token-id :wire-id :wei-decimals :evm-decimals
        :core-precision :kind :erc20-address :spender :system-address
        :evm->core-recipient :verified?}

   `:kind` is `:native` (HYPE), `:usdc-cdw` (USDC via Circle's
   CoreDepositWallet) or `:erc20`.

   The two directions use different addresses, and mixing them up loses
   funds:

   - `:system-address` is the HyperCore `sendAsset` destination for a
     Core->EVM move. It is valid for every kind, USDC included.
   - `:evm->core-recipient` is where a HyperEVM transaction sends the tokens
     for an EVM->Core move: the `to` of a native HYPE transfer, or the
     recipient of an ERC-20 `transfer` on `:erc20-address`. It is nil for
     `:usdc-cdw`, because native USDC sent to its system address is NOT
     credited; USDC moves with `approve` + `deposit` on the CoreDepositWallet
     (`:spender`). EVM-side code must branch on `:kind` exhaustively, never on
     which addresses happen to be present.

   `:core-precision` is
   min(weiDecimals, evmDecimals): the most decimals an amount can carry and
   still convert exactly in both directions. Tokens whose EVM decimals are
   negative or above 36 are excluded. Returns [] when spot metadata has not
   loaded."
  ([spot-meta]
   (linked-tokens spot-meta chain/mainnet))
  ([spot-meta chain-config]
   (:tokens (catalog spot-meta chain-config))))

(defn token-by-index
  "The linked token at spot token `index`, or nil."
  ([spot-meta index]
   (token-by-index spot-meta chain/mainnet index))
  ([spot-meta chain-config index]
   (get (:by-index (catalog spot-meta chain-config)) index)))

(defn token-by-name
  "The linked token named `token-name` (case-insensitive), or nil."
  ([spot-meta token-name]
   (token-by-name spot-meta chain/mainnet token-name))
  ([spot-meta chain-config token-name]
   (when-let [name* (some-> (non-blank-text token-name) str/upper-case)]
     (get (:by-name (catalog spot-meta chain-config)) name*))))
