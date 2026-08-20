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

(defn- record-positions
  [record]
  (or (get-in record [:state :assetPositions]) []))

(defn- isolated-position?
  [position-row]
  (= "isolated"
     (some-> (get-in position-row [:position :leverage :type])
             str
             str/lower-case)))

(defn- position-notional
  [position-row]
  (let [value (parse-num (get-in position-row [:position :positionValue]))]
    (when (number? value)
      (js/Math.abs value))))

(defn- position-maintenance-margin
  "Maintenance margin one position requires, in that position's quote token.
   Hyperliquid charges half the initial rate implied by the asset's max
   leverage, so the rate is 1 / (2 * maxLeverage); replaying a live position's
   own `liquidationPx` from that rate confirms the convention. Returns nil when
   `maxLeverage` is absent so callers can report an incomplete total rather than
   one they know is too small."
  [position-row]
  (let [notional (position-notional position-row)
        max-leverage (parse-num (get-in position-row [:position :maxLeverage]))]
    (when (and (number? notional)
               (number? max-leverage)
               (pos? max-leverage))
      (/ notional (* 2 max-leverage)))))

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
                            quote-token (position-quote-token market-by-key record position-row)]
                        (if (and (isolated-position? position-row)
                                 (number? margin-used)
                                 quote-token)
                          (update acc* quote-token (fnil + 0) margin-used)
                          acc*)))
                    acc
                    (record-positions record)))
          {}
          records))

(defn- cross-notional-total
  "Sum of `crossMarginSummary.totalNtlPos` across records. Only ever compared
   against zero -- to tell \"no cross positions\" apart from \"cross positions
   worth nothing\" -- so the quote-token mix across dexes does not matter."
  [records]
  (sum-when-present
   (for [{:keys [state]} records]
     (let [value (parse-num (get-in state [:crossMarginSummary :totalNtlPos]))]
       (when (number? value)
         (js/Math.abs value))))))

(defn- isolated-notional-total
  [records]
  (sum-when-present
   (for [record records
         position-row (record-positions record)
         :when (isolated-position? position-row)]
     (position-notional position-row))))

(defn- unified-account-ratio*
  "Cross-liquidation risk: cross maintenance margin against the collateral still
   free once isolated margin is held back.

   Isolated positions stay out of the numerator on purpose -- each liquidates on
   its own and cannot take the portfolio down with it. That also means the ratio
   describes nothing on a book that is entirely isolated, so this returns nil
   there rather than a 0% that reads as \"no risk\". A genuinely flat account
   still reports 0%."
  [records balance-row-by-token market-by-key]
  (let [cross-notional (or (cross-notional-total records) 0)
        isolated-notional (or (isolated-notional-total records) 0)]
    (when-not (and (zero? cross-notional)
                   (pos? isolated-notional))
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
          (reduce max ratios))))))

(defn- unified-cross-maintenance-margin*
  [records balance-row-by-token market-by-key]
  (sum-when-present
   (for [{:keys [quote-token state]} records]
     (let [maintenance (parse-num (:crossMaintenanceMarginUsed state))
           usd-price (token-price-usd balance-row-by-token market-by-key quote-token)]
       (when (and (number? maintenance)
                  (number? usd-price))
         (* maintenance usd-price))))))

(defn- unified-isolated-maintenance-margin*
  "Maintenance margin held against isolated positions, in USD. nil when any
   isolated position withholds the `maxLeverage` the rate is derived from: an
   under-count would understate account risk, so report nothing instead."
  [records balance-row-by-token market-by-key]
  (let [contributions
        (vec
         (for [record records
               position-row (record-positions record)
               :when (isolated-position? position-row)]
           (let [maintenance (position-maintenance-margin position-row)
                 quote-token (position-quote-token market-by-key record position-row)
                 usd-price (token-price-usd balance-row-by-token market-by-key quote-token)]
             (when (and (number? maintenance)
                        (number? usd-price))
               (* maintenance usd-price)))))]
    (when-not (some nil? contributions)
      (reduce + 0 contributions))))

(defn- unified-maintenance-margin*
  "Total perps maintenance margin in USD: cross plus isolated. Isolated
   positions post margin out of the same unified collateral pool, so a
   cross-only figure reported $0.00 for accounts carrying real liquidation
   risk."
  [records balance-row-by-token market-by-key]
  (let [cross (unified-cross-maintenance-margin* records balance-row-by-token market-by-key)
        isolated (unified-isolated-maintenance-margin* records balance-row-by-token market-by-key)]
    (cond
      (nil? isolated) nil
      (number? cross) (+ cross isolated)
      (pos? isolated) isolated
      :else nil)))

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

(defn- unified-notional-usd-value
  "Whole-book perp notional in USD, taken from each record's
   `marginSummary.totalNtlPos` (cross *and* isolated) rather than the cross-only
   summary, and converted through the record's quote token like the collateral
   side already is."
  [records balance-row-by-token market-by-key]
  (sum-when-present
   (for [{:keys [quote-token state]} records]
     (let [notional (parse-num (get-in state [:marginSummary :totalNtlPos]))
           usd-price (token-price-usd balance-row-by-token market-by-key quote-token)]
       (when (and (number? notional)
                  (number? usd-price))
         (* notional usd-price))))))

(defn- unified-account-leverage*
  "Perp notional over unified collateral. The numerator counts isolated
   positions because on a unified account their margin comes out of the same
   collateral pool; a cross-only numerator reported 0.00x for an account whose
   entire book is isolated."
  [records balance-row-by-token market-by-key]
  (safe-div (unified-notional-usd-value records balance-row-by-token market-by-key)
            (unified-collateral-usd-value records balance-row-by-token market-by-key)))

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
        balance-rows (derived-cache/memoized-balance-rows webdata2 (:spot state) (:account state) market-by-key (:perp-dex-clearinghouse state))
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
        unified? (unified-account? state)
        unified-records (when unified?
                          (unified-clearinghouse-state-records state market-by-key))
        ;; Unified accounts never fall back to the classic cross-only formulas.
        ;; That fallback is what turned an all-isolated book into 0.00x / 0.00%
        ;; / $0.00; when a unified figure cannot be derived the row shows "--"
        ;; instead of a zero that reads as "no exposure".
        unified-maintenance-margin (when unified?
                                     (unified-maintenance-margin* unified-records
                                                                  balance-row-by-token
                                                                  market-by-key))
        unified-account-ratio (if unified?
                                (unified-account-ratio* unified-records
                                                        balance-row-by-token
                                                        market-by-key)
                                (safe-div maintenance-margin portfolio-value))
        unified-account-leverage (if unified?
                                   (unified-account-leverage* unified-records
                                                              balance-row-by-token
                                                              market-by-key)
                                   (safe-div cross-total-ntl-pos portfolio-value))
        pnl-info (pnl-display unrealized-pnl)]
    {:spot-equity spot-equity
     :perps-value perps-value
     :base-balance base-balance
     :unrealized-pnl unrealized-pnl
     :cross-margin-ratio cross-margin-ratio
     :unified-account-ratio unified-account-ratio
     :maintenance-margin (if unified?
                           unified-maintenance-margin
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
