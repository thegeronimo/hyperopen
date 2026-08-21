(ns hyperopen.subaccounts.transfer-amount
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]))

(def invalid-transfer-amount-message
  "Enter a positive USDC amount with at most 6 decimal places.")

(def invalid-spot-transfer-amount-message
  "Enter a positive spot amount that matches the selected asset precision.")

(def missing-spot-transfer-precision-message
  "Spot asset precision is unavailable. Refresh balances before transferring.")

(def max-spot-atomic-digits
  38)

(def ^:private usdc-decimals
  6)

(def ^:private max-safe-integer-text
  (str js/Number.MAX_SAFE_INTEGER))

(defn- map-value
  [m keys]
  (some (fn [k]
          (when (and (map? m) (contains? m k))
            (get m k)))
        keys))

(defn- normalized-text
  [value]
  (some-> value str str/trim not-empty))

(defn- normalized-token
  [value]
  (some-> value normalized-text str/lower-case))

(defn- token-symbol
  [token]
  (let [token* (normalized-text token)]
    (cond
      (and token* (str/includes? token* ":"))
      (first (str/split token* #":" 2))

      token* token*
      :else nil)))

(defn- usdc-token?
  [token]
  (= "usdc" (some-> (or (token-symbol token) token)
                    normalized-token)))

(defn- parse-optional-int
  [value]
  (cond
    (and (number? value)
         (not (js/isNaN value))
         (js/isFinite value)
         (<= 0 value 38))
    (let [int-value (js/Math.floor value)]
      (when (= value int-value)
        int-value))

    (string? value)
    (let [text (str/trim value)]
      (when (re-matches #"^\d+$" text)
        (parse-optional-int (js/Number text))))

    :else nil))

(defn- decimals-from-map
  [m]
  (parse-optional-int
   (map-value m [:amount-decimals :amountDecimals
                 "amount-decimals" "amountDecimals"
                 :decimals "decimals"
                 :weiDecimals :wei-decimals "weiDecimals" "wei-decimals"
                 :szDecimals :sz-decimals "szDecimals" "sz-decimals"])))

(defn- strip-leading-zeroes
  [text]
  (let [stripped (str/replace (or text "") #"^0+" "")]
    (if (seq stripped) stripped "0")))

(defn parse-usdc-amount->micros
  [value]
  (let [amount-text (str/trim (str (or value "")))]
    (when (re-matches #"^\d+(?:\.\d{0,6})?$" amount-text)
      (let [[whole frac] (str/split amount-text #"\." 2)
            micros-text (str whole
                             (subs (str (or frac "") "000000") 0 6))
            micros-normalized (strip-leading-zeroes micros-text)]
        (when (and (not= "0" micros-normalized)
                   (or (< (count micros-normalized) (count max-safe-integer-text))
                       (and (= (count micros-normalized) (count max-safe-integer-text))
                            (not (pos? (compare micros-normalized max-safe-integer-text))))))
          (js/parseInt micros-normalized 10))))))

(defn- strip-trailing-zeroes
  [text]
  (str/replace (or text "") #"0+$" ""))

(defn- canonical-decimal-text
  [whole frac]
  (let [whole* (strip-leading-zeroes whole)
        frac* (strip-trailing-zeroes frac)]
    (if (seq frac*)
      (str whole* "." frac*)
      whole*)))

(defn- atomic-units-text
  [whole frac decimals]
  (let [frac-padded (subs (str (or frac "") (apply str (repeat decimals "0")))
                          0
                          decimals)
        units (strip-leading-zeroes (str (strip-leading-zeroes whole)
                                         frac-padded))]
    units))

(defn- normalize-decimal-amount
  [value decimals max-atomic-digits]
  (let [amount-text (str/trim (str (or value "")))]
    (when (and (number? decimals)
               (re-matches #"^\d+(?:\.\d+)?$" amount-text))
      (let [[whole frac] (str/split amount-text #"\." 2)
            frac* (or frac "")]
        (when (<= (count frac*) decimals)
          (let [units (atomic-units-text whole frac* decimals)]
            (when (and (not= "0" units)
                       (<= (count units) max-atomic-digits))
              {:amount (canonical-decimal-text whole frac*)
               :amount-display (canonical-decimal-text whole frac*)
               :amount-units units
               :amount-decimals decimals})))))))

(defn- row-address
  [row]
  (account-context/normalize-address
   (map-value row [:sub-account-user :subAccountUser
                   "sub-account-user" "subAccountUser"])))

(defn- spot-state
  [row]
  (or (:spot-state row)
      (:spotState row)
      (get row "spot-state")
      (get row "spotState")))

(defn- balances
  [spot-state*]
  (or (:balances spot-state*)
      (get spot-state* "balances")))

(defn- balance-token
  [balance]
  (or (normalized-text
       (map-value balance [:token :token-id :tokenId
                           "token" "token-id" "tokenId"]))
      (normalized-text
       (map-value balance [:coin :symbol :name "coin" "symbol" "name"]))))

(defn- balance-symbol
  [balance]
  (or (normalized-text
       (map-value balance [:symbol :coin :name "symbol" "coin" "name"]))
      (token-symbol (balance-token balance))))

(defn- token-match?
  [selected-token candidate]
  (let [selected* (normalized-token selected-token)
        candidate* (normalized-token candidate)]
    (and selected*
         candidate*
         (= selected* candidate*))))

(defn- owner-spot-state
  "The master's own spot balances, which is what the Sub-Accounts page lists on
   a deposit. `[:spot :clearinghouse-state]` holds the *active trading*
   account's balances instead, and that can be a sub-account while the master
   is the transfer source, so prefer the owner snapshot and fall back only when
   it has not loaded."
  [state]
  (or (:spot-state (get-in state [:account-context :subaccounts :owner-snapshot]))
      (get-in state [:spot :clearinghouse-state])))

(defn- selected-balance
  [state {:keys [subaccount-address direction token]}]
  (let [spot-state* (if (= :withdraw direction)
                      (some (fn [row]
                              (when (= (account-context/normalize-address subaccount-address)
                                       (row-address row))
                                (spot-state row)))
                            (get-in state [:account-context :subaccounts :rows]))
                      (owner-spot-state state))]
    (some (fn [balance]
            (when (or (token-match? token (balance-token balance))
                      (token-match? token (balance-symbol balance))
                      ;; A selected `NAME:0x<hash>` wire token id never equals a
                      ;; balance row's own fields, which carry a numeric token
                      ;; index and a bare coin name, so compare its symbol too.
                      (token-match? (token-symbol token) (balance-symbol balance)))
              balance))
          (balances spot-state*))))

(defn- spot-token-metadata
  [state]
  (concat
   (get-in state [:spot :meta :tokens])
   (get-in state [:spot :clearinghouse-state :tokens])
   (get-in state [:webdata2 :spotMeta :tokens])
   (get-in state [:webdata2 :spot-meta :tokens])
   (get-in state [:webdata2 :spotMeta "tokens"])
   (get-in state ["webdata2" "spotMeta" "tokens"])))

(defn- metadata-token-candidates
  [m]
  (keep normalized-text
        [(map-value m [:token :token-id :tokenId "token" "token-id" "tokenId"])
         (map-value m [:name :symbol :coin "name" "symbol" "coin"])
         (when-let [index (map-value m [:index "index"])]
           (str index))]))

(defn- selected-token-metadata
  [state token]
  (some (fn [m]
          (when (some #(or (token-match? token %)
                           (token-match? (token-symbol token) %))
                      (metadata-token-candidates m))
            m))
        (spot-token-metadata state)))

(defn- market-coin
  [market]
  (normalized-text
   (map-value market [:coin :base-coin :baseCoin :name "coin" "base-coin" "baseCoin" "name"])))

(defn- spot-market?
  [market]
  (let [market-type (map-value market [:market-type :marketType "market-type" "marketType"])]
    (or (= :spot market-type)
        (= "spot" (some-> market-type str str/lower-case)))))

(defn- selected-market
  [state token]
  (let [markets (concat (vals (or (get-in state [:asset-selector :market-by-key]) {}))
                        (get-in state [:asset-selector :markets]))]
    (some (fn [market]
            (when (and (spot-market? market)
                       (or (token-match? token (market-coin market))
                           (token-match? (token-symbol token) (market-coin market))))
              market))
          markets)))

(defn- token-decimals
  [state {:keys [direction subaccount-address token] :as opts}]
  (or (some-> (selected-balance state opts) decimals-from-map)
      (some-> (selected-token-metadata state token) decimals-from-map)
      (some-> (selected-market state token) decimals-from-map)
      (when (usdc-token? token) usdc-decimals)))

(defn- spot-token-symbol
  [state token opts]
  (or (some-> (selected-balance state opts) balance-symbol)
      (some-> (selected-token-metadata state token)
              (map-value [:symbol :coin :name "symbol" "coin" "name"])
              normalized-text)
      (token-symbol token)
      token))

(defn- normalize-trading-transfer
  [amount-text]
  (if-let [usd (parse-usdc-amount->micros amount-text)]
    {:ok? true
     :payload {:account-kind :trading
               :token "USDC"
               :amount (str/trim (str amount-text))
               :amount-display (str/trim (str amount-text))
               :amount-decimals usdc-decimals
               :amount-units (str usd)
               :usd usd}}
    {:ok? false
     :message invalid-transfer-amount-message}))

(defn- normalize-spot-transfer
  [state {:keys [token amount-text] :as opts}]
  (cond
    (not (seq (normalized-text token)))
    {:ok? false
     :message "Select a spot asset to transfer."}

    :else
    (if-let [decimals (token-decimals state opts)]
      (if-let [amount (normalize-decimal-amount amount-text
                                               decimals
                                               max-spot-atomic-digits)]
        {:ok? true
         :payload (assoc amount
                         :account-kind :spot
                         :token (str/trim (str token))
                         :token-symbol (spot-token-symbol state token opts))}
        {:ok? false
         :message invalid-spot-transfer-amount-message})
      {:ok? false
       :message missing-spot-transfer-precision-message})))

(defn normalize-transfer-amount
  [state {:keys [account-kind amount-text] :as opts}]
  (if (= :spot account-kind)
    (normalize-spot-transfer state opts)
    (normalize-trading-transfer amount-text)))
