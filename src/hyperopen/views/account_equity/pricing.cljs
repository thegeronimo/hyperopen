(ns hyperopen.views.account-equity.pricing
  "Turning wire figures into USD.

   Hyperliquid reports each perps dex in that dex's own collateral token, so
   nothing can be added across dexes until every figure has been converted. This
   namespace owns that conversion -- resolving a token's USD price from the
   wallet's own balance rows or the market catalogue -- and the aggregation the
   venue performs on top of it.

   The nil discipline here is deliberate and load-bearing: a figure that cannot
   be converted contributes nothing rather than a zero, and a sum with no
   contributors is nil. A panel row with no derivable value must render \"--\";
   a confident $0.00 over an account we failed to read is the failure
   `docs/agent-guides/trading-ui-policy.md` forbids."
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as asset-selector-markets]
            [hyperopen.views.account-equity.format :refer [parse-num]]))

(defn normalized-token-name [value]
  (some-> value str str/trim str/upper-case not-empty))

(defn- normalized-dex-name [value]
  (some-> value str str/trim not-empty))

(defn- scalar-coin-id?
  [value]
  (or (string? value)
      (keyword? value)
      (number? value)))

(defn- same-dex?
  [left right]
  (= (normalized-dex-name left)
     (normalized-dex-name right)))

(defn- stable-dollar-token?
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

(defn perp-market-for-coin
  [market-by-key coin]
  (when-let [coin* (when (scalar-coin-id? coin)
                     (str coin))]
    (let [direct (get market-by-key (str "perp:" coin*))]
      (if (= :perp (:market-type direct))
        direct
        (let [resolved (asset-selector-markets/resolve-market-by-coin market-by-key coin*)]
          (when (= :perp (:market-type resolved))
            resolved))))))

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
  [balance-row-by-token market-by-key token]
  (let [token* (normalized-token-name token)
        row (get balance-row-by-token token*)
        row-price (some-> row balance-row-usd-price)
        market (or (get market-by-key (str "spot:" token*))
                   (asset-selector-markets/resolve-market-by-coin market-by-key token*))]
    (or row-price
        (market-token-usd-price token* market)
        (when (stable-dollar-token? token*) 1))))

(defn- clearinghouse-state-quote-token
  [market-by-key dex clearinghouse-state]
  (or (some->> (or (:assetPositions clearinghouse-state) [])
               (some (fn [row]
                       (let [coin (get-in row [:position :coin])
                             market (perp-market-for-coin market-by-key coin)]
                         (some-> market :quote normalized-token-name)))))
      (some->> (vals market-by-key)
               (some (fn [market]
                       (when (and (= :perp (:market-type market))
                                  (same-dex? dex (:dex market)))
                         (normalized-token-name (:quote market))))))
      (when (nil? dex)
        "USDC")))

(defn clearinghouse-state-records
  [state market-by-key]
  (let [default-state (get-in state [:webdata2 :clearinghouseState])
        named-states (:perp-dex-clearinghouse state)
        default-record (when (map? default-state)
                         {:dex nil
                          :quote-token (clearinghouse-state-quote-token market-by-key nil default-state)
                          :state default-state})]
    (vec
     (concat (when default-record [default-record])
             (keep (fn [[dex clearinghouse-state]]
                     (when (map? clearinghouse-state)
                       {:dex dex
                        :quote-token (clearinghouse-state-quote-token market-by-key dex clearinghouse-state)
                        :state clearinghouse-state}))
                   named-states)))))

(defn sum-when-present
  [values]
  (let [values* (vec (keep identity values))]
    (when (seq values*)
      (reduce + values*))))

(defn- records-usd-sum
  "Sum one wire field across every dex, each record converted to USD through
   its own collateral token. A dex whose collateral has no resolvable price
   contributes nothing rather than a zero, and the whole sum is nil when no
   record contributed -- the nil discipline the rest of this namespace uses, so
   a row with no derivable value renders \"--\" instead of a confident $0.00."
  [records balance-row-by-token market-by-key path]
  (sum-when-present
   (for [{:keys [quote-token state]} records]
     (let [value (parse-num (get-in state path))
           usd-price (token-price-usd balance-row-by-token market-by-key quote-token)]
       (when (and (number? value)
                  (number? usd-price))
         (* value usd-price))))))

(defn aggregate-clearinghouse-usd
  "One synthetic clearinghouse state in USD, summed across every dex the wallet
   has a snapshot for.

   This is how the venue reads an account: it folds every dex into a single
   state first -- converting each dex's figures through that dex's collateral
   token -- and derives every panel row from the sum. Reading only
   `[:webdata2 :clearinghouseState]`, which is the base dex alone, is what put
   $0.00 Balance and 0.00x leverage on screen for accounts carrying their whole
   book on a named dex."
  [records balance-row-by-token market-by-key]
  (let [usd-sum (fn [path]
                  (records-usd-sum records balance-row-by-token market-by-key path))]
    {:account-value (usd-sum [:marginSummary :accountValue])
     :total-ntl-pos (usd-sum [:marginSummary :totalNtlPos])
     :total-raw-usd (usd-sum [:marginSummary :totalRawUsd])
     :cross-account-value (usd-sum [:crossMarginSummary :accountValue])
     :cross-total-ntl-pos (usd-sum [:crossMarginSummary :totalNtlPos])
     :cross-total-margin-used (usd-sum [:crossMarginSummary :totalMarginUsed])
     :maintenance-margin (usd-sum [:crossMaintenanceMarginUsed])}))
