(ns hyperopen.funding.domain.hyperunit
  (:require [clojure.string :as str]
            [hyperopen.domain.trading :as trading-domain]
            [hyperopen.funding.domain.amounts :as amounts]
            [hyperopen.funding.domain.lifecycle :as lifecycle-domain]))

(def ^:private hyperunit-lifecycle-failure-fragments
  ["fail" "error" "revert" "cancel" "refund" "drop"])

(def ^:private hyperunit-explorer-tx-base-by-chain
  {"arbitrum" "https://arbiscan.io/tx/" "bitcoin" "https://mempool.space/tx/"
   "ethereum" "https://etherscan.io/tx/" "solana" "https://solscan.io/tx/"})

(def ^:private hyperliquid-explorer-tx-base-url
  "https://app.hyperliquid.xyz/explorer/tx/")

(def ^:private chain-fee-format-by-chain
  {"bitcoin" {:symbol "BTC" :decimals 8} "ethereum" {:symbol "ETH" :decimals 18}
   "solana" {:symbol "SOL" :decimals 9}})

(defn normalize-lifecycle-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (some-> value str/trim str/lower-case keyword)
                    :else nil)]
    (when (contains? #{:deposit :withdraw} direction)
      direction)))

(defn lifecycle-token
  [value]
  (some-> value
          name
          str/lower-case
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"^-+|-+$" "")))

(defn lifecycle-fragment-match?
  [value fragments]
  (let [token (lifecycle-token value)]
    (and (seq token)
         (some #(str/includes? token %) fragments))))

(defn hyperunit-lifecycle-failure?
  [lifecycle]
  (let [lifecycle* (lifecycle-domain/normalize-hyperunit-lifecycle lifecycle)]
    (or (lifecycle-fragment-match? (:state lifecycle*)
                                   hyperunit-lifecycle-failure-fragments)
        (lifecycle-fragment-match? (:status lifecycle*)
                                   hyperunit-lifecycle-failure-fragments)
        (and (lifecycle-domain/hyperunit-lifecycle-terminal? lifecycle*)
             (seq (amounts/non-blank-text (:error lifecycle*)))))))

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

(defn hyperunit-explorer-tx-base-url
  [direction chain]
  (if (= direction :deposit)
    hyperliquid-explorer-tx-base-url
    (get hyperunit-explorer-tx-base-by-chain chain)))

(defn hyperunit-explorer-tx-url
  [direction chain tx-id]
  (let [direction* (normalize-lifecycle-direction direction)
        chain* (some-> chain amounts/non-blank-text str/lower-case)
        tx-id* (amounts/non-blank-text tx-id)]
    (when (seq tx-id*)
      (when-let [base-url (hyperunit-explorer-tx-base-url direction* chain*)]
        (str base-url (js/encodeURIComponent tx-id*))))))

(defn hyperunit-fee-entry
  [fee-estimate chain]
  (let [estimate* (lifecycle-domain/normalize-hyperunit-fee-estimate fee-estimate)
        chain* (some-> chain amounts/non-blank-text str/lower-case)]
    (when (and (seq chain*)
               (map? (:by-chain estimate*)))
      (get (:by-chain estimate*) chain*))))

(defn hyperunit-withdrawal-queue-entry
  [withdrawal-queue chain]
  (let [queue* (lifecycle-domain/normalize-hyperunit-withdrawal-queue withdrawal-queue)
        chain* (some-> chain amounts/non-blank-text str/lower-case)]
    (when (and (seq chain*)
               (map? (:by-chain queue*)))
      (get (:by-chain queue*) chain*))))

(defn integer-like-number?
  [value]
  (and (number? value)
       (amounts/finite-number? value)
       (= value (js/Math.floor value))))

(defn fee-value->number
  [value]
  (cond
    (and (number? value) (amounts/finite-number? value))
    value

    (string? value)
    (amounts/parse-input-amount value)

    :else nil))

(defn fee-value->chain-units
  [value chain]
  (let [chain* (some-> chain amounts/non-blank-text str/lower-case)
        {:keys [decimals]} (get chain-fee-format-by-chain chain*)
        decimals* (or decimals 0)
        parsed-number (fee-value->number value)
        integer-like? (integer-like-number? parsed-number)]
    (cond
      (nil? parsed-number)
      nil

      integer-like?
      (/ parsed-number (js/Math.pow 10 decimals*))

      :else
      parsed-number)))

(defn estimate-fee-display
  [value chain]
  (let [chain* (some-> chain amounts/non-blank-text str/lower-case)
        {:keys [symbol decimals]} (get chain-fee-format-by-chain chain*)
        normalized-value (fee-value->chain-units value chain*)
        display-text (when (amounts/finite-number? normalized-value)
                       (trading-domain/number->clean-string (max 0 normalized-value)
                                                            (if (number? decimals)
                                                              (min 8 (max 2 decimals))
                                                              6)))
        fallback-text (amounts/non-blank-text value)]
    (cond
      (and (seq display-text) (seq symbol))
      (str display-text " " symbol)

      (seq display-text)
      display-text

      :else
      fallback-text)))
