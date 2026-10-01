(ns hyperopen.hyperevm.domain.fees
  "Fee and gas estimates for moving tokens between HyperCore and HyperEVM.

   Two different costs apply, paid in HYPE on different ledgers:

   - Core -> EVM: Hyperliquid charges `200000 × base fee of the next small
     EVM block`, rounded UP to 8 decimals, from the sender's SPOT HYPE. HYPE
     itself moves for free. `eth_gasPrice` is documented as that next base
     fee, so it is the estimate. Checked against two live ledger fees on
     2026-09-30: base fee 0.10371 gwei charged 0.00002075 HYPE and 0.11533
     gwei charged 0.00002307 HYPE.
   - EVM -> Core: an ordinary HyperEVM transaction paid from the wallet's
     native HYPE on HyperEVM. The app sends `maxFeePerGas = 2 × gasPrice`
     (with a 0.2 gwei floor) and an explicit gas limit, so this estimate is
     that worst case.

   Amounts are decimal HYPE strings in and out; nil means unknown."
  (:require [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.units :as units]))

(def ^:private hype-decimals 18)

(def ^:private core-fee-decimals
  "HyperCore charges the Core -> EVM fee in spot HYPE at 8 decimals."
  8)

(def fallback-gas-limits
  "Gas limits used when `eth_estimateGas` is unavailable. Live estimates on
   2026-09-30 were ~22.8k (native HYPE to 0x2222…), ~29.7k (PURR transfer to
   its system address), ~56.2k (USDC approve) and 58-68k (USDC spot deposit);
   these leave headroom above each."
  {:native 30000
   :erc20 60000
   :usdc-approve 70000
   :usdc-deposit 90000})

(def ^:private min-gas-price-wei
  "0.2 gwei. The small-block base fee floor is 0.1 gwei, so a quoted gas
   price below this is still charged as at least this much."
  (js/BigInt 200000000))

(def ^:private max-fee-multiplier
  (js/BigInt 2))

(def ^:private native-min-reserve-hype
  "HYPE kept back on HyperEVM when moving native HYPE with MAX, so the move
   itself (and the next one) can still pay gas."
  "0.001")

(defn- gas-price-units
  [gas-price-wei-text]
  (units/to-bigint gas-price-wei-text))

(defn- native-token?
  [token]
  (or (= :native (:kind token))
      (= "HYPE" (:name token))))

(defn core->evm-fee-hype
  "Estimated spot-HYPE fee for moving `token` from HyperCore to HyperEVM, as
   a decimal string: ceil-to-8-decimals(200000 × gasPrice / 1e18). \"0\" for
   HYPE. `gas-price-wei-text` is a decimal or hex wei quantity; nil when it
   is not one."
  ([gas-price-wei-text token]
   (core->evm-fee-hype chain/mainnet gas-price-wei-text token))
  ([chain-config gas-price-wei-text token]
   (if (native-token? token)
     "0"
     (when-let [gas-price (gas-price-units gas-price-wei-text)]
       (let [system-gas (units/to-bigint (:core->evm-system-gas chain-config))
             fee-wei (* system-gas gas-price)
             step (units/pow10 (- hype-decimals core-fee-decimals))
             fee-units (/ (+ fee-wei (- step (js/BigInt 1))) step)]
         (units/format-units fee-units core-fee-decimals))))))

(defn evm-gas-limit
  "Fallback gas limit for a HyperEVM transaction `kind` (`:native`,
   `:erc20`, `:usdc-approve`, `:usdc-deposit`), or nil for an unknown kind."
  [kind]
  (get fallback-gas-limits kind))

(defn- effective-gas-price
  [gas-price]
  (if (< gas-price min-gas-price-wei) min-gas-price-wei gas-price))

(defn max-fee-per-gas-wei
  "`maxFeePerGas` for a HyperEVM transaction: 2 × max(gasPrice, 0.2 gwei),
   as a BigInt, or nil when the gas price is unknown. The priority fee is
   always 0 (it is burned on HyperEVM)."
  [gas-price-wei-text]
  (when-let [gas-price (gas-price-units gas-price-wei-text)]
    (* max-fee-multiplier (effective-gas-price gas-price))))

(def ^:private estimate-headroom
  "`eth_estimateGas` × 13/10: HyperEVM state can move between the estimate
   and inclusion."
  [13 10])

(defn gas-limit
  "Gas limit for a transaction of `kind`: `estimate-text` (an
   `eth_estimateGas` answer) × 1.3 rounded up, or the fallback table when
   the estimate is missing, zero or invalid. A BigInt, or nil for an unknown
   kind without an estimate."
  [estimate-text kind]
  (let [estimate (units/to-bigint estimate-text)
        [numerator denominator] estimate-headroom]
    (if (and estimate (> estimate units/zero))
      (/ (+ (* estimate (js/BigInt numerator)) (js/BigInt (dec denominator)))
         (js/BigInt denominator))
      (some-> (evm-gas-limit kind) js/BigInt))))

(defn evm-tx-cost-wei
  "Worst-case wei cost of sending the transactions `kinds` on HyperEVM:
   Σ limit × 2 × max(gasPrice, 0.2 gwei). A BigInt, or nil when the gas
   price or any kind is unknown."
  [gas-price-wei-text kinds]
  (let [gas-price (gas-price-units gas-price-wei-text)
        limits (map evm-gas-limit kinds)]
    (when (and gas-price
               (coll? kinds)
               (every? some? limits))
      (let [price (* max-fee-multiplier (effective-gas-price gas-price))]
        (reduce (fn [total limit]
                  (+ total (* (js/BigInt limit) price)))
                units/zero
                limits)))))

(defn evm-tx-cost-hype
  "`evm-tx-cost-wei` as a decimal HYPE string, or nil when unknown."
  [gas-price-wei-text kinds]
  (some-> (evm-tx-cost-wei gas-price-wei-text kinds)
          (units/format-units hype-decimals)))

(defn native-max-reserve-hype
  "HYPE to hold back from a MAX native-HYPE move to Core: max(0.001, the
   worst-case cost of the move). The 0.001 floor dominates at every gas price
   observed so far (it would take ~17 gwei to exceed it), so an unknown gas
   price falls back to the floor rather than to nil."
  ([gas-price-wei-text]
   (native-max-reserve-hype gas-price-wei-text [:native]))
  ([gas-price-wei-text kinds]
   (let [floor-units (units/parse-units native-min-reserve-hype hype-decimals)
         cost (evm-tx-cost-wei gas-price-wei-text kinds)
         reserve (if (and cost (> cost floor-units)) cost floor-units)]
     (units/format-units reserve hype-decimals))))

(defn evm-gas-status
  "Whether HyperEVM native HYPE `evm-hype-text` covers `needed-text` HYPE:

   - `:none` when the balance is zero,
   - `:low` when it is positive but below `needed-text`,
   - `:ok` otherwise (including when `needed-text` is unknown and the
     balance is positive).

   Returns nil when the balance itself is unknown (not loaded or invalid), so
   callers never show a gas warning for a balance they have not read."
  [evm-hype-text needed-text]
  (when-let [balance (units/parse-units evm-hype-text hype-decimals)]
    (let [needed (units/parse-units needed-text hype-decimals)]
      (cond
        (= balance units/zero) :none
        (and needed (< balance needed)) :low
        :else :ok))))
