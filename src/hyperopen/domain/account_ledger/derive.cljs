(ns hyperopen.domain.account-ledger.derive
  "Per-type derivation of an Account Activity row from a ledger delta.

   Four columns cannot be read straight off the payload -- From, To, Account
   Change and USD Value -- because their meaning depends on the delta type and,
   for every transfer type, on *which side of the transfer the viewer is on*.
   That last point is the defect this namespace exists to fix: the previous
   implementation decided credit-versus-debit from a hardcoded per-type table,
   so an incoming transfer rendered as money leaving the account.

   Everything here is pure. The viewing address arrives as an argument rather
   than being read from app state, because `/domain/` namespaces may not import
   `hyperopen.views.*` and the boundary checker allows no exception for it."
  (:require [clojure.string :as str]))

(def collateral-asset
  "Symbol of the core collateral the USD-denominated deltas are quoted in."
  "USDC")

(def trading-account-label "Trading Account")
(def perps-label "Perps")
(def spot-label "Spot")
(def bridge-label "Arbitrum")
(def hyper-evm-label "HyperEVM")
(def vault-label "Vault")
(def staking-label "Staking")
(def gas-auction-label "Gas Auction")

(def hyper-evm-system-address-prefix
  "Leading bytes of a HyperEVM system (token) address.

   These addresses are minted as `0x20` followed by the token index padded to
   38 hex digits, so they are `0x20`, then 34 zeros, then a four-digit index.
   Used to recognise a spot transfer that is really a HyperEVM bridge movement."
  "0x20")

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn parse-decimal
  [value]
  (cond
    (finite-number? value) value

    (string? value)
    (let [num (js/parseFloat value)]
      (when (finite-number? num) num))

    :else nil))

(defn field
  "Read `k` from a map whose keys may be keywords or strings."
  [m k]
  (or (get m k) (get m (name k))))

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- lower-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn token-system-address?
  "Whether `address` is a HyperEVM system address minted for a spot token.

   The shape -- `0x20`, 34 zeros, a four-hex token index -- is the reference's
   own test. An ordering comparison against the prefix is NOT equivalent and
   matches most ordinary wallet addresses."
  [address]
  (boolean
   (when-let [address* (lower-address address)]
     (and (= 42 (count address*))
          (str/starts-with? address* hyper-evm-system-address-prefix)
          (some? (re-matches #"0+" (subs address* 4 38)))))))

(defn- dex-venue-label
  "Map a Hyperliquid dex identifier to its venue label.

   The base (unnamed) perps dex is the empty string; `spot` is the spot book;
   anything else is a named HIP-3 dex and is shown by name."
  [dex]
  (let [dex* (or (non-blank-text dex) "")]
    (cond
      (= "" dex*) perps-label
      (= "spot" (str/lower-case dex*)) spot-label
      :else (str dex* " Perps"))))

;; --- re-typing ------------------------------------------------------------
;;
;; The reference rewrites a row's effective type before routing it, so a delta
;; lands on the sub-tab its *meaning* implies rather than the one its raw type
;; implies. Three of its four rules are ported here. The fourth -- a `send`
;; whose sender is the native USDC contract becomes a bridge deposit -- is not,
;; because that contract address is derived at runtime from chain config rather
;; than being a literal in the bundle. It is moot for us in any case: `send` is
;; a multi-dex delta Hyperliquid's own API does not emit, so the rules keyed on
;; it are carried for completeness, not because they fire today.

(defn retype
  "Effective `[type-key delta-overrides]` for a delta, applying the re-typing
   rules. Returns the original type unchanged when no rule matches."
  [type-key delta]
  (let [user (lower-address (field delta :user))
        destination (lower-address (field delta :destination))]
    (cond
      ;; A self-send is a movement between the sender's own venues.
      (and (= "send" type-key)
           (some? user)
           (= user destination))
      ["account-class-transfer" {:retyped-from "send"}]

      ;; A spot transfer whose sender is a HyperEVM system address is a bridge
      ;; movement from HyperEVM into spot.
      (and (= "spot-transfer" type-key)
           (token-system-address? (field delta :user)))
      ["account-class-transfer" {:retyped-from "spot-transfer"
                                 :from-label hyper-evm-label
                                 :to-label spot-label}]

      ;; A send *to* a HyperEVM system address is the reverse leg, and the
      ;; reference negates the amount because the payload states it unsigned.
      (and (= "send" type-key)
           (token-system-address? (field delta :destination)))
      ["account-class-transfer" {:retyped-from "send"
                                 :from-label spot-label
                                 :to-label hyper-evm-label
                                 :negate-amount? true}]

      :else
      [type-key nil])))

;; --- endpoints ------------------------------------------------------------

(defn endpoints
  "`{:from-label :to-label :destination-address}` for a delta.

   From and To are venues; Destination is the raw counterparty address, shown
   only for the types that actually have a counterparty."
  [type-key delta {:keys [from-label to-label]}]
  (let [destination (non-blank-text (field delta :destination))
        vault (non-blank-text (field delta :vault))
        to-perp? (true? (field delta :toPerp))
        deposit? (true? (field delta :isDeposit))
        base (case type-key
               "deposit" {:from-label bridge-label :to-label trading-account-label}
               "withdraw" {:from-label trading-account-label :to-label bridge-label}

               "account-class-transfer" (if to-perp?
                                          {:from-label spot-label :to-label perps-label}
                                          {:from-label perps-label :to-label spot-label})

               "send" {:from-label (dex-venue-label (field delta :sourceDex))
                       :to-label (dex-venue-label (field delta :destinationDex))
                       :destination-address destination}

               "activate-dex-abstraction" {:from-label (dex-venue-label (field delta :dex))
                                           :to-label perps-label}

               ("internal-transfer" "sub-account-transfer")
               {:from-label trading-account-label
                :to-label trading-account-label
                :destination-address destination}

               "spot-transfer" {:from-label spot-label
                                :to-label spot-label
                                :destination-address destination}

               ("vault-create" "vault-deposit")
               {:from-label perps-label :to-label vault-label :destination-address vault}

               ("vault-distribution" "vault-withdraw")
               {:from-label vault-label :to-label perps-label :destination-address vault}

               "vault-leader-commission"
               {:from-label vault-label :to-label perps-label :destination-address vault}

               ("c-staking-transfer" "c-deposit" "delegate")
               (if (or (= "c-deposit" type-key)
                       (= "delegate" type-key)
                       deposit?)
                 {:from-label spot-label :to-label staking-label}
                 {:from-label staking-label :to-label spot-label})

               ("c-withdraw" "undelegate")
               {:from-label staking-label :to-label spot-label}

               "deploy-gas-auction" {:from-label spot-label :to-label gas-auction-label}

               ("borrow-lend" "rewards-claim" "spot-genesis" "welcome-bonus")
               {:from-label trading-account-label :to-label trading-account-label}

               {:from-label trading-account-label :to-label trading-account-label})]
    (cond-> base
      ;; A re-typing rule may pin the endpoints it already knows.
      (some? from-label) (assoc :from-label from-label)
      (some? to-label) (assoc :to-label to-label))))

;; --- amount, sign and asset -----------------------------------------------

(defn- magnitude
  "The unsigned scalar for a delta, probing the keys each type actually uses.

   `netWithdrawnUsd` is what makes `vaultWithdraw` reachable at all; the
   previous implementation probed neither it nor `delta`, so vault withdrawals
   and liquidations were dropped for want of an amount."
  [delta]
  (some parse-decimal
        [(field delta :usdc)
         (field delta :amount)
         (field delta :netWithdrawnUsd)
         (field delta :value)
         (field delta :qty)
         (field delta :sz)
         (field delta :delta)]))

(defn- incoming?
  "Whether the viewed account is the recipient of a two-party transfer.

   With no viewing address the direction is genuinely unknowable, so this
   reports outgoing -- the behaviour the table had before the address was
   threaded through, rather than a new guess."
  [delta viewer-address]
  (let [viewer (lower-address viewer-address)
        destination (lower-address (field delta :destination))]
    (and (some? viewer)
         (some? destination)
         (= viewer destination))))

(defn signed-amount
  "Signed account change for a delta, from the payload rather than a type table."
  [type-key delta viewer-address {:keys [negate-amount?]}]
  (when-let [amount (magnitude delta)]
    (let [abs-amount (js/Math.abs amount)
          direction (if (incoming? delta viewer-address) 1 -1)
          value (case type-key
                  "deposit" abs-amount
                  "withdraw" (- abs-amount)

                  ;; Two-party transfers: the sign is which side we are on.
                  ("send" "spot-transfer" "internal-transfer" "sub-account-transfer")
                  (* abs-amount direction)

                  ;; The API already signs these.
                  ("account-class-transfer" "vault-withdraw" "vault-leader-commission")
                  amount

                  ("vault-create" "vault-deposit") (- abs-amount)
                  "vault-distribution" abs-amount

                  ("c-staking-transfer") (if (true? (field delta :isDeposit))
                                           (- abs-amount)
                                           abs-amount)
                  ("c-deposit" "delegate" "deploy-gas-auction") (- abs-amount)
                  ("c-withdraw" "undelegate") abs-amount

                  ("spot-genesis" "rewards-claim" "welcome-bonus" "activate-dex-abstraction")
                  abs-amount

                  "borrow-lend" (if (= "deposit" (some-> (field delta :operation)
                                                         non-blank-text
                                                         str/lower-case))
                                  (- abs-amount)
                                  abs-amount)

                  amount)]
      (if negate-amount? (- value) value))))

(defn asset
  [delta]
  (or (non-blank-text (field delta :token))
      (non-blank-text (field delta :coin))
      (non-blank-text (field delta :asset))
      collateral-asset))

;; --- USD value ------------------------------------------------------------

(def ^:private usd-denominated-types
  #{"deposit" "withdraw" "account-class-transfer" "internal-transfer"
    "sub-account-transfer" "vault-create" "vault-deposit" "vault-distribution"
    "vault-withdraw" "vault-leader-commission" "activate-dex-abstraction"
    "borrow-lend" "rewards-claim" "welcome-bonus"})

(def ^:private no-usd-value-types
  "Types the reference deliberately leaves blank rather than inventing a figure."
  #{"c-staking-transfer" "c-deposit" "c-withdraw" "delegate" "undelegate"
    "deploy-gas-auction"})

(defn usd-value
  "Absolute USD value of a row, or nil when no honest figure is available.

   nil rather than 0 is load-bearing: the column renders `--` for nil, and a
   zero would read as a real, zero-valued movement."
  [type-key delta signed row-asset]
  (let [payload-value (some-> (field delta :usdcValue) parse-decimal js/Math.abs)]
    (cond
      (contains? no-usd-value-types type-key) nil

      (and (some? payload-value) (pos? payload-value)) payload-value

      ;; USDC-denominated types are their own USD value.
      (and (contains? usd-denominated-types type-key)
           (some? signed)
           (= collateral-asset row-asset))
      (js/Math.abs signed)

      ;; A USDC-denominated amount that carries no token label.
      (and (some? signed)
           (= collateral-asset row-asset)
           (not (contains? no-usd-value-types type-key)))
      (js/Math.abs signed)

      :else nil)))

;; --- fee ------------------------------------------------------------------

(defn fee
  "`{:fee :fee-asset}` for a delta, in the asset the fee was actually charged in.

   The previous implementation labelled every fee `USDC` regardless of the row's
   asset, and never probed `feeToken`."
  [type-key delta]
  (let [amount (some parse-decimal [(field delta :fee)
                                    (field delta :withdrawalFee)
                                    (field delta :gasFee)])
        fee-token (non-blank-text (field delta :feeToken))]
    (if (and (some? amount) (not (zero? amount)))
      {:fee amount
       :fee-asset (or fee-token
                      (case type-key
                        ("spot-transfer" "withdraw") collateral-asset
                        (or (non-blank-text (field delta :token))
                            collateral-asset)))}
      {:fee nil :fee-asset nil})))
