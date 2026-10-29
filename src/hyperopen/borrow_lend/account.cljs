(ns hyperopen.borrow-lend.account
  "Read-only selectors joining app state with the borrow/lend model: which
  account Earn shows, token metadata, the user's positions and health, and the
  maximum amount for each modal operation. Pure functions of app state."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.borrow-lend.model :as model]
            [hyperopen.domain.trading.core :as trading-core]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private default-wei-decimals 8)

(defn- finite? [x] (and (number? x) (js/isFinite x)))

(defn- num
  [value]
  (let [n (cond (number? value) value
                (string? value) (js/Number value)
                :else js/NaN)]
    (when (js/isFinite n) n)))

(defn token-index
  [value]
  (let [n (num value)]
    (when (and n (>= n 0) (== n (js/Math.floor n)))
      (int n))))

(defn account-address
  "Earn follows the effective account: the spectated or viewed trader, else the
  selected subaccount, else the connected wallet. Submits target the same
  account through `:vault-address`."
  [state]
  (account-context/effective-account-address state))

(defn portfolio-margin?
  [state]
  (trading-core/portfolio-margin-abstraction? (get-in state [:account :abstraction-raw])))

(defn account-mode
  [state]
  (cond (portfolio-margin? state) :portfolio-margin
        (= :unified (get-in state [:account :mode])) :unified
        :else :standard))

(defn token-meta
  [state token]
  (some (fn [t]
          (when (= token (token-index (or (:index t) (get t "index"))))
            t))
        (get-in state [:spot :meta :tokens])))

(defn token-symbol
  [state token]
  (or (some-> (token-meta state token) (as-> t (or (:name t) (get t "name"))))
      (str "Token " token)))

(defn- wei-decimals
  [state token]
  (or (some-> (token-meta state token) (as-> t (or (:weiDecimals t) (get t "weiDecimals"))) num int)
      default-wei-decimals))

(defn reserves
  [state]
  (or (get-in state [:borrow-lend :reserves]) []))

(defn reserves-by-token
  [state]
  (into {} (map (juxt :token identity)) (reserves state)))

(defn user-state-current?
  "The stored user state belongs to the account Earn currently shows."
  [state]
  (let [address (account-context/normalize-address (account-address state))]
    (and (some? address)
         (= address (get-in state [:borrow-lend :user-address])))))

(defn positions
  [state]
  (if (user-state-current? state)
    (or (get-in state [:borrow-lend :user-state :positions]) [])
    []))

(defn position
  [state token]
  (some #(when (= token (:token %)) %) (positions state)))

(defn health
  "Current health plus per-collateral liquidation prices."
  [state]
  (let [by-token (reserves-by-token state)
        ps (positions state)
        collateral (model/collateral-rows ps by-token)
        debt (model/borrowed-usd ps by-token)]
    (assoc (model/health collateral debt)
           :collateral collateral
           :liquidation-prices (model/liquidation-prices collateral debt))))

(defn total-supplied-usd
  "Everything the account has supplied (stablecoins and collateral) at oracle
  prices. This is the portfolio's Earn balance."
  [state]
  (let [by-token (reserves-by-token state)]
    (reduce (fn [acc {:keys [token supplied]}]
              (let [px (get-in by-token [token :oracle-px])]
                (if (and (finite? supplied) (finite? px)) (+ acc (* supplied px)) acc)))
            0
            (positions state))))

(defn net-earn-usd
  "The account's lending value for account totals: supplied plus collateral,
  minus borrowed, at oracle prices. Supplying moves funds out of the spot
  balance and a borrow lands in it, so a total that sums spot must add this.
  Zero unless the stored lending state belongs to the account shown, so a
  previous account's value never leaks into the current total."
  [state]
  (if (user-state-current? state)
    (let [supplied (get-in state [:borrow-lend :total-supplied-usd])
          borrowed (get-in state [:borrow-lend :total-borrowed-usd])]
      (- (if (finite? supplied) supplied 0)
         (if (finite? borrowed) borrowed 0)))
    0))

(defn spot-available
  "Free spot balance of `token` (total minus hold) for the active account."
  [state token]
  (some (fn [row]
          (when (= token (token-index (or (:token row) (get row "token"))))
            (let [total (num (or (:total row) (get row "total")))
                  hold (or (num (or (:hold row) (get row "hold"))) 0)]
              (when (finite? total) (max 0 (- total hold))))))
        (get-in state [:spot :clearinghouse-state :balances])))

(defn modal-max-amount
  "Upper bound for the amount field, in token units, or nil if unknown."
  [state kind token]
  (let [reserve (get (reserves-by-token state) token)
        {:keys [supplied borrowed]} (position state token)
        px (:oracle-px reserve)]
    (case kind
      :supply (spot-available state token)
      :withdraw (when (finite? supplied)
                  (let [ltv (:ltv reserve)
                        pool-cap (min supplied (or (:available reserve) supplied))]
                    ;; Withdrawing collateral may not push the borrow above
                    ;; the remaining limit.
                    (if (and (finite? ltv) (pos? ltv) (finite? px) (pos? px))
                      (let [{:keys [available-to-borrow-usd borrowed-usd]} (health state)]
                        (if (pos? borrowed-usd)
                          (min pool-cap (/ available-to-borrow-usd (* px ltv)))
                          pool-cap))
                      pool-cap)))
      :repay (when (finite? borrowed)
               (if-let [free (spot-available state token)] (min borrowed free) borrowed))
      :borrow (let [{:keys [available-to-borrow-usd]} (health state)]
                (when (and (finite? px) (pos? px))
                  (min (/ available-to-borrow-usd px)
                       (or (:available reserve) js/Infinity))))
      nil)))

(defn over-max-message
  "Why an amount above the modal maximum is refused, per operation."
  [kind]
  (case kind
    :borrow "That is more than you can borrow against your collateral."
    :repay "That is more than you owe."
    :withdraw "That is more than you have supplied."
    "That is more than your available balance."))

(defn- trim-decimal
  [s]
  (if (str/includes? s ".")
    (-> s (str/replace #"0+$" "") (str/replace #"\.$" ""))
    s))

(defn format-amount-input
  "Amount rounded down to the token's wire precision, as an input string."
  [state token amount]
  (if (finite? amount)
    (let [d (min 8 (wei-decimals state token))
          factor (js/Math.pow 10 d)]
      (trim-decimal (.toFixed (/ (js/Math.floor (* amount factor)) factor) d)))
    ""))

(defn wire-amount
  "The decimal string the exchange expects for a typed amount, or nil."
  [state token amount-input]
  (when-let [n (parse-utils/parse-localized-decimal amount-input (get-in state [:ui :locale]))]
    (when (pos? n)
      (format-amount-input state token n))))
