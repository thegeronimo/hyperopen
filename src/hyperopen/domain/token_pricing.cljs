(ns hyperopen.domain.token-pricing
  "A token's USD price, resolved from the wallet's own balance rows or the
   market catalogue.

   This is the pure resolver behind `hyperopen.views.account-equity.pricing`.
   It lives outside `views/` so non-view code (the funding modal's view-model,
   which may not import views) can value an amount the same way the account
   panels do.

   The nil discipline is deliberate: a token whose price cannot be resolved
   yields nil, never 0, so callers render \"--\" instead of a confident $0.00."
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as asset-selector-markets]))

(defn- parse-num
  "Same contract as `hyperopen.views.account-equity.format/parse-num`."
  [value]
  (cond
    (number? value) value
    (string? value) (let [s (str/trim value)
                          n (js/parseFloat s)]
                      (when (and (not (str/blank? s)) (not (js/isNaN n))) n))
    :else nil))

(defn normalized-token-name [value]
  (some-> value str str/trim str/upper-case not-empty))

(defn stable-dollar-token?
  [token]
  (let [token* (normalized-token-name token)]
    (or (= "USDC" token*)
        (= "USDE" token*)
        (= "USDH" token*)
        (some-> token* (str/starts-with? "USDT"))
        (some-> token* (str/starts-with? "USD")))))

(defn- market-mark-price [market]
  (let [mark (parse-num (:mark market))
        mark-raw (parse-num (:markRaw market))]
    (cond
      (and (number? mark) (pos? mark)) mark
      (and (number? mark-raw) (pos? mark-raw)) mark-raw
      :else nil)))

(defn- market-token-usd-price
  [token market]
  (let [mark-price (market-mark-price market)
        base (normalized-token-name (:base market))
        quote (normalized-token-name (:quote market))]
    (cond
      (and (number? mark-price) (pos? mark-price) (= token base) (= "USDC" quote))
      mark-price
      (and (number? mark-price) (pos? mark-price) (= token quote) (= "USDC" base))
      (/ 1 mark-price)
      :else nil)))

(defn- balance-row-token-key
  [row]
  (normalized-token-name (or (:selection-coin row)
                             (:coin row))))

(defn balance-rows-by-token
  [balance-rows]
  (reduce (fn [acc row]
            (if-let [token (balance-row-token-key row)]
              (assoc acc token row)
              acc))
          {}
          (or balance-rows [])))

(defn- balance-row-usd-price
  [row]
  (let [total-balance (parse-num (:total-balance row))
        usdc-value (parse-num (:usdc-value row))]
    (cond
      (and (number? total-balance)
           (not (zero? total-balance))
           (number? usdc-value))
      (/ usdc-value total-balance)

      (stable-dollar-token? (balance-row-token-key row))
      1

      :else nil)))

(defn token-price-usd
  "USD price of `token`: from its balance row when the wallet holds it, else
   from its USDC spot market, else 1 for a stable dollar token, else nil.

   The market is resolved only when the balance row gives no price, because
   `resolve-market-by-coin` can scan the whole catalogue. USDC skips it: a
   market prices a token only against USDC, so USDC's own price is always
   the stable fallback, 1."
  [balance-row-by-token market-by-key token]
  (let [token* (normalized-token-name token)
        row (get balance-row-by-token token*)
        row-price (some-> row balance-row-usd-price)]
    (or row-price
        (when (= "USDC" token*) 1)
        (market-token-usd-price
         token*
         (or (get market-by-key (str "spot:" token*))
             (asset-selector-markets/resolve-market-by-coin market-by-key token*)))
        (when (stable-dollar-token? token*) 1))))

(defn market-token-price-usd
  "`token-price-usd` from app state's market catalogue alone, for callers
   that have no balance rows (the funding modal). Nil when unpriced."
  [state token]
  (token-price-usd {}
                   (get-in state [:asset-selector :market-by-key] {})
                   token))
