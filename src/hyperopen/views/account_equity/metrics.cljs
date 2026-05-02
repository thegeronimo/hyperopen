(ns hyperopen.views.account-equity.metrics
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as asset-selector-markets]
            [hyperopen.views.account-equity.format :refer [parse-num pnl-display safe-div]]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections :as account-projections]))

(defonce ^:private account-equity-metrics-cache
  (atom nil))

(defn unified-account? [state]
  (= :unified (get-in state [:account :mode])))

(defn- derive-account-value-display
  [portfolio-value spot-equity perps-value]
  (or portfolio-value
      (when (or (number? spot-equity)
                (number? perps-value))
        (+ (or spot-equity 0)
           (or perps-value 0)))))

(defn- normalized-token-name [value]
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

(defn- perp-market-for-coin
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

(defn- balance-rows-by-token
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

(defn- unified-clearinghouse-state-records
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

(defn- sum-when-present
  [values]
  (let [values* (vec (keep identity values))]
    (when (seq values*)
      (reduce + values*))))

(defn- cross-maintenance-by-token
  [records]
  (reduce (fn [acc {:keys [quote-token state]}]
            (let [maintenance (parse-num (:crossMaintenanceMarginUsed state))]
              (if (and (number? maintenance) quote-token)
                (update acc quote-token (fnil + 0) maintenance)
                acc)))
          {}
          records))

(defn- position-quote-token
  [market-by-key {:keys [quote-token]} position-row]
  (or (let [coin (get-in position-row [:position :coin])
            market (perp-market-for-coin market-by-key coin)]
        (some-> market :quote normalized-token-name))
      quote-token))

(defn- isolated-margin-by-token
  [records market-by-key]
  (reduce (fn [acc record]
            (reduce (fn [acc* position-row]
                      (let [margin-used (parse-num (get-in position-row [:position :marginUsed]))
                            leverage-type (some-> (get-in position-row [:position :leverage :type])
                                                  str
                                                  str/lower-case)
                            quote-token (position-quote-token market-by-key record position-row)]
                        (if (and (= "isolated" leverage-type)
                                 (number? margin-used)
                                 quote-token)
                          (update acc* quote-token (fnil + 0) margin-used)
                          acc*)))
                    acc
                    (or (get-in record [:state :assetPositions]) [])))
          {}
          records))

(defn- unified-account-ratio*
  [records balance-row-by-token market-by-key]
  (let [cross-maintenance (cross-maintenance-by-token records)
        isolated-margin (isolated-margin-by-token records market-by-key)
        ratios (keep (fn [[token maintenance]]
                       (let [spot-total (parse-num (get-in balance-row-by-token [token :total-balance]))
                             available (when (number? spot-total)
                                         (- spot-total (or (get isolated-margin token) 0)))]
                         (when (and (number? maintenance)
                                    (number? available)
                                    (pos? available))
                           (min 1 (/ maintenance available)))))
                     cross-maintenance)]
    (when (seq ratios)
      (reduce max ratios))))

(defn- unified-cross-maintenance-margin*
  [records balance-row-by-token market-by-key]
  (sum-when-present
   (for [{:keys [quote-token state]} records]
     (let [maintenance (parse-num (:crossMaintenanceMarginUsed state))
           usd-price (token-price-usd balance-row-by-token market-by-key quote-token)]
       (when (and (number? maintenance)
                  (number? usd-price))
         (* maintenance usd-price))))))

(defn- unified-collateral-usd-value
  [records balance-row-by-token market-by-key]
  (let [collateral-tokens (set (keep :quote-token records))]
    (sum-when-present
     (for [token collateral-tokens]
       (let [spot-total (parse-num (get-in balance-row-by-token [token :total-balance]))
             usd-price (token-price-usd balance-row-by-token market-by-key token)]
         (when (and (number? spot-total)
                    (number? usd-price))
           (* spot-total usd-price)))))))

(defn- unified-account-leverage*
  [records balance-row-by-token market-by-key]
  (let [cross-total-ntl-pos
        (sum-when-present
         (for [{:keys [state]} records]
           (parse-num (get-in state [:crossMarginSummary :totalNtlPos]))))
        collateral-usd-value (unified-collateral-usd-value records balance-row-by-token market-by-key)]
    (safe-div cross-total-ntl-pos collateral-usd-value)))

(defn- derive-account-equity-metrics [state]
  (let [webdata2 (:webdata2 state)
        clearinghouse-state (:clearinghouseState webdata2)
        margin-summary (:marginSummary clearinghouse-state)
        cross-summary (:crossMarginSummary clearinghouse-state)
        perps-summary (or margin-summary cross-summary {})
        cross-summary (or cross-summary perps-summary {})
        account-value (parse-num (:accountValue perps-summary))
        total-raw-usd (parse-num (:totalRawUsd perps-summary))
        total-ntl-pos (parse-num (:totalNtlPos perps-summary))
        cross-account-value (or (parse-num (:accountValue cross-summary)) account-value)
        cross-total-ntl-pos (or (parse-num (:totalNtlPos cross-summary)) total-ntl-pos)
        cross-total-margin-used (parse-num (:totalMarginUsed cross-summary))
        maintenance-margin (parse-num (:crossMaintenanceMarginUsed clearinghouse-state))
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        balance-rows (derived-cache/memoized-balance-rows webdata2 (:spot state) (:account state) market-by-key)
        balance-row-by-token (balance-rows-by-token balance-rows)
        perps-row (first (filter #(= "perps-usdc" (:key %)) balance-rows))
        perps-row-balance (parse-num (:total-balance perps-row))
        positions (derived-cache/memoized-positions webdata2 (:perp-dex-clearinghouse state))
        unrealized-from-positions (let [vals (keep #(parse-num (get-in % [:position :unrealizedPnl])) positions)]
                                    (when (seq vals)
                                      (reduce + vals)))
        fallback-balance (or total-raw-usd perps-row-balance)
        cross-derived-balance (when (and (number? cross-account-value)
                                         (number? cross-total-margin-used)
                                         (number? cross-total-ntl-pos))
                                (+ cross-account-value cross-total-margin-used cross-total-ntl-pos))
        base-balance (or cross-derived-balance fallback-balance)
        unrealized-from-summary (when (and (number? account-value) (number? fallback-balance))
                                  (- account-value fallback-balance))
        unrealized-pnl (or unrealized-from-positions unrealized-from-summary)
        perps-value (cond
                      (and (number? base-balance) (number? unrealized-pnl))
                      (+ base-balance unrealized-pnl)
                      (number? account-value) account-value
                      :else nil)
        spot-values (keep (fn [row]
                            (when-not (= "perps-usdc" (:key row))
                              (parse-num (:usdc-value row))))
                          balance-rows)
        spot-equity (when (seq spot-values) (reduce + spot-values))
        portfolio-value (account-projections/portfolio-usdc-value balance-rows)
        account-value-display (derive-account-value-display portfolio-value spot-equity perps-value)
        cross-margin-ratio (safe-div maintenance-margin cross-account-value)
        cross-account-leverage (safe-div cross-total-ntl-pos cross-account-value)
        unified-records (when (unified-account? state)
                          (unified-clearinghouse-state-records state market-by-key))
        aggregated-maintenance-margin (when (unified-account? state)
                                        (unified-cross-maintenance-margin* unified-records
                                                                           balance-row-by-token
                                                                           market-by-key))
        unified-account-ratio (or (when (unified-account? state)
                                    (unified-account-ratio* unified-records
                                                            balance-row-by-token
                                                            market-by-key))
                                  (safe-div maintenance-margin portfolio-value))
        unified-account-leverage (or (when (unified-account? state)
                                       (unified-account-leverage* unified-records
                                                                  balance-row-by-token
                                                                  market-by-key))
                                     (safe-div cross-total-ntl-pos portfolio-value))
        pnl-info (pnl-display unrealized-pnl)]
    {:spot-equity spot-equity
     :perps-value perps-value
     :base-balance base-balance
     :unrealized-pnl unrealized-pnl
     :cross-margin-ratio cross-margin-ratio
     :unified-account-ratio unified-account-ratio
     :maintenance-margin (or aggregated-maintenance-margin
                             maintenance-margin)
     :cross-account-leverage cross-account-leverage
     :unified-account-leverage unified-account-leverage
     :cross-account-value cross-account-value
     :portfolio-value portfolio-value
     :account-value-display account-value-display
     :pnl-info pnl-info}))

(defn- memoized-account-equity-metrics
  [state]
  (let [webdata2 (:webdata2 state)
        spot-data (:spot state)
        account (:account state)
        perp-dex-states (:perp-dex-clearinghouse state)
        market-by-key (get-in state [:asset-selector :market-by-key])
        cache @account-equity-metrics-cache
        cache-hit? (and (map? cache)
                        (identical? webdata2 (:webdata2 cache))
                        (identical? spot-data (:spot-data cache))
                        (identical? account (:account cache))
                        (identical? perp-dex-states (:perp-dex-states cache))
                        (identical? market-by-key (:market-by-key cache)))]
    (if cache-hit?
      (:result cache)
      (let [result (derive-account-equity-metrics state)]
        (reset! account-equity-metrics-cache {:webdata2 webdata2
                                              :spot-data spot-data
                                              :account account
                                              :perp-dex-states perp-dex-states
                                              :market-by-key market-by-key
                                              :result result})
        result))))

(defn account-equity-metrics [state]
  (memoized-account-equity-metrics state))

(defn reset-account-equity-metrics-cache!
  []
  (reset! account-equity-metrics-cache nil))
