(ns hyperopen.funding.domain.transfer-invariants
  "Last checks on a Transfer request before anything is signed.

   The preview builds requests from the same catalog, so a mismatch here
   means a bug or tampered state, and the funds would be lost: a token sent
   to another token's system address, or to HYPE's 0x2222…, is never
   credited back. The Transfer and Send submit effects call `check-request`
   and refuse to sign when it returns an error.

   A `sendAsset` to a system address (a HyperCore -> HyperEVM move) must:
   - go to that token's own system address;
   - come from the master account (`fromSubAccount \"\"`), because a
     subaccount's HyperEVM address has no key;
   - leave from Spot (`sourceDex \"spot\"`): a send from a Perps dex to a
     system address has never been seen bridged, so it could strand funds;
   - land in Spot (`destinationDex \"spot\"`);
   - carry no more decimals than the token's Core precision.
   A request marked `:route :core->evm` must target a system address.

   A `hyperEvmToCore` move must match the catalog for its token (kind,
   recipient, contract, spender), send exactly the amount's units, target
   chain 999, deposit into Spot (`destinationDex` 4294967295: Perps settles
   through CoreWriter and cannot revert), and carry no more decimals than the
   Core precision (the rest would be lost on HyperCore)."
  (:require [clojure.string :as str]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.domain.units :as units]))

(def ^:private kind-by-wire
  {"native" :native
   "erc20" :erc20
   "usdcCoreDeposit" :usdc-cdw})

(defn- lower
  [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn- catalog-token-by-wire-id
  [spot-meta wire-id]
  (some #(when (= wire-id (:wire-id %)) %) (tokens/linked-tokens spot-meta)))

(defn- fraction-digits
  "Significant fractional digits of a plain decimal string (trailing zeros
   do not count), or nil when `amount` is not one."
  [amount]
  (when (and (string? amount) (re-matches #"^\d+(\.\d+)?$" amount))
    (let [fraction (second (str/split amount #"\." 2))]
      (count (str/replace (or fraction "") #"0+$" "")))))

(defn- within-core-precision?
  [amount token]
  (let [digits (fraction-digits amount)
        precision (:core-precision token)]
    (and (number? digits) (number? precision) (<= digits precision))))

(defn- check-send-asset
  [spot-meta route {:keys [destination token fromSubAccount sourceDex destinationDex amount]}]
  (let [destination* (lower destination)]
    (if-not (tokens/system-address? destination*)
      (when (= :core->evm route)
        "Refusing to sign: a move to HyperEVM must go to the token's system address.")
      (let [catalog-token (catalog-token-by-wire-id spot-meta token)]
        (cond
          (nil? catalog-token)
          "Refusing to sign: the token being moved to HyperEVM is not a linked token."

          (not= destination* (lower (:system-address catalog-token)))
          (str "Refusing to sign: " (:name catalog-token)
               " must move to its own HyperEVM system address.")

          (not= "" fromSubAccount)
          "Refusing to sign: only the master account can move funds to HyperEVM."

          (not= "spot" sourceDex)
          "Refusing to sign: a move to HyperEVM must leave from Spot."

          (not= "spot" destinationDex)
          "Refusing to sign: a move to HyperEVM must land in Spot."

          (not (within-core-precision? amount catalog-token))
          (str "Refusing to sign: " (:name catalog-token) " moves with at most "
               (:core-precision catalog-token) " decimals.")

          :else nil)))))

(defn- check-hyperevm-to-core
  [spot-meta {:keys [kind tokenIndex recipient tokenAddress spender amount units chainId
                     destinationDex]}]
  (let [token (tokens/token-by-index spot-meta tokenIndex)
        expected-units (when token
                         (units/units-text (units/parse-units amount (:evm-decimals token))))]
    (cond
      (nil? token)
      "Refusing to sign: the token being moved from HyperEVM is not a linked token."

      (not= (:kind token) (get kind-by-wire kind))
      (str "Refusing to sign: " (:name token) " cannot move as a " kind " transfer.")

      (not= (lower (:evm->core-recipient token)) (lower recipient))
      (str "Refusing to sign: " (:name token) " must go to its own bridge address.")

      (not= (lower (:erc20-address token)) (lower tokenAddress))
      (str "Refusing to sign: " (:name token) " has an unexpected HyperEVM contract.")

      (not= (lower (:spender token)) (lower spender))
      (str "Refusing to sign: " (:name token) " has an unexpected approval spender.")

      (or (nil? expected-units) (not= expected-units units))
      "Refusing to sign: the HyperEVM amount does not match the transfer amount."

      (not= (:chain-id chain/mainnet) (lower chainId))
      "Refusing to sign: HyperEVM transfers must target chain 999."

      (not= (get-in chain/mainnet [:core-deposit-destination-dex :spot]) destinationDex)
      "Refusing to sign: a move from HyperEVM must land in Spot."

      (not (within-core-precision? amount token))
      (str "Refusing to sign: " (:name token) " moves with at most "
           (:core-precision token) " decimals.")

      :else nil)))

(defn check-request
  "nil when `request` is safe to sign against app `state`'s spot metadata,
   otherwise a plain error string."
  [state request]
  (let [action (:action request)
        spot-meta (get-in state [:spot :meta])]
    (case (:type action)
      "sendAsset" (check-send-asset spot-meta (:route request) action)
      "hyperEvmToCore" (check-hyperevm-to-core spot-meta action)
      nil)))
