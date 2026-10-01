(ns hyperopen.funding.domain.evm-transfer-amounts
  "Amounts for HyperCore <-> HyperEVM moves: route precision, MAX, and amount
   validation. Everything is an exact decimal string.

   Each move carries the token's Core precision, min(weiDecimals,
   evmDecimals), in both directions: the most decimals an amount can carry
   and still convert exactly. USDC moves at 6 decimals and UBTC at 8. Amounts
   are FLOORED to it, never rounded, so MAX can never exceed the balance.

   MAX is the source balance, minus the gas reserve when moving native HYPE
   off HyperEVM, capped by what the bridge can pay out on the other side."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.domain.amounts :as amounts]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.units :as units]))

(def ^:private input-decimals
  "Precision used to read what the user typed, before flooring to the route
   precision. Wide enough for any linked token."
  36)

(def usdc-activation-fee
  "HyperCore charges a new account 1 USDC on its first transfer in."
  "1")

(defn route-precision
  [token]
  (:core-precision token))

(defn gas-kinds
  "The HyperEVM transactions an EVM -> Core move of `token` sends."
  [token]
  (case (:kind token)
    :native [:native]
    :erc20 [:erc20]
    :usdc-cdw [:usdc-approve :usdc-deposit]
    []))

(defn- owner-gas-price
  [state owner]
  (:gas-price-wei (get-in state [:hyperevm :balances :by-address owner])))

(defn native-gas-reserve
  "HYPE kept on HyperEVM by a MAX move of native HYPE to Core."
  [state owner]
  (fees/native-max-reserve-hype (owner-gas-price state owner)))

(defn source-available
  "What the route's source holds of `token` that may move: the balance,
   minus the gas reserve for native HYPE leaving HyperEVM. nil when unknown."
  [state route token]
  (let [owner (account-context/owner-address state)
        available (transfer-balances/location-available-text state (:from route) token owner)]
    (if (and (= :hyperevm (:from route)) (= :native (:kind token)))
      (when available
        (or (transfer-balances/sub-text available (native-gas-reserve state owner))
            "0"))
      available)))

(defn capacity
  "What the bridge can deliver of `token` on the route's destination:
   a Core-precision string, `:unlimited`, or nil when unknown. Given the
   clock, a reading older than a minute counts as unknown in both
   directions, and so does a HyperEVM reading that predates the user's last
   Core -> EVM send of the token (`bridge/core->evm-capacity`)."
  [state route token now-ms]
  (case (transfer-route/route-kind route)
    :core->evm (bridge/core->evm-capacity state token now-ms)
    :evm->core (bridge/evm->core-capacity state token now-ms)
    nil))

(defn max-amount-text
  "MAX for an EVM route, floored to the route precision, or nil when the
   balance or the bridge capacity is unknown."
  [state route token now-ms]
  (when token
    (let [available (source-available state route token)
          cap (capacity state route token now-ms)
          limited (cond
                    (or (nil? available) (nil? cap)) nil
                    (= :unlimited cap) available
                    :else (transfer-balances/min-text available cap))]
      (some-> limited (units/floor-to-decimals (route-precision token))))))

(defn percent-amount-text
  "`pct` percent of MAX, floored to the route precision, or nil."
  [state route token now-ms pct]
  (some-> (max-amount-text state route token now-ms)
          (transfer-balances/percent-of-text pct (route-precision token))))

;; --- validation -------------------------------------------------------------

(defn- fraction-digits
  [text]
  (let [[_ fraction] (str/split text #"\." 2)]
    (count (str/replace (or fraction "") #"0+$" ""))))

(defn- min-unit-text
  [precision]
  (units/format-units (js/BigInt 1) precision))

(defn- invalid
  [message]
  {:ok? false :display-message message})

(defn validate-amount
  "Check the typed amount for `token` on `route` after every block has
   passed. Returns `{:ok? true :amount \"floored\" :notice str|nil}` or
   `{:ok? false :display-message str|nil}`; a blank amount is not ok and has
   no message.

   The order is: blank, not a number, not above 0, floor to the route
   precision (with a notice), above the source balance, above the bridge
   capacity, and the USDC activation minimum."
  [state route token modal now-ms {:keys [activation-fee?]}]
  (let [input (amounts/normalize-amount-input (:amount-input modal))
        precision (route-precision token)
        symbol (:name token)
        full (units/parse-units input input-decimals)
        floored (when full (units/floor-to-decimals input precision))
        owner (account-context/owner-address state)
        raw-available (transfer-balances/location-available-text state (:from route) token owner)
        available (source-available state route token)
        cap (capacity state route token now-ms)]
    (cond
      (str/blank? input)
      {:ok? false}

      (nil? full)
      (invalid "Enter a valid amount.")

      (= units/zero full)
      (invalid "Enter an amount greater than 0.")

      (not (transfer-balances/positive-text? floored))
      (invalid (str "Enter at least " (min-unit-text precision) " " symbol "."))

      (nil? available)
      (invalid "Unable to determine transfer balance.")

      (pos? (transfer-balances/compare-text floored available))
      (invalid (if (and (= :native (:kind token))
                        (= :hyperevm (:from route))
                        (not (pos? (or (transfer-balances/compare-text floored raw-available) 1))))
                 (str "Keep at least " (native-gas-reserve state owner)
                      " HYPE on HyperEVM for gas.")
                 "Amount exceeds available balance."))

      (and (string? cap) (pos? (transfer-balances/compare-text floored cap)))
      (invalid (str "The bridge can deliver at most " cap " " symbol " right now."))

      (and activation-fee?
           (not (pos? (transfer-balances/compare-text floored usdc-activation-fee))))
      (invalid "The first transfer in pays a 1 USDC activation fee, so move more than 1 USDC.")

      :else
      {:ok? true
       :amount floored
       :notice (when (> (fraction-digits input) precision)
                 (str "Rounded down to " floored " " symbol " ("
                      precision " decimals max)."))})))
