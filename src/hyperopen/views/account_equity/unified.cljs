(ns hyperopen.views.account-equity.unified
  "The figures only a unified account has.

   A unified account pools every dex's collateral, so the venue measures it
   against that pool rather than against any one dex's equity. Its own i18n
   bundle states the definition outright: \"Unified Account Leverage = Total
   Cross Positions Value / Total Collateral Balance.\"

   Isolated positions sit outside the leverage and maintenance figures on
   purpose -- each carries its own margin and liquidates alone, so folding them
   in would disagree with the venue about how close the account is to
   liquidation. The notional they represent is reported separately, so the panel
   can say what its cross-only rows leave out instead of printing a bare 0.00x
   over a live book."
  (:require [clojure.string :as str]
            [hyperopen.views.account-equity.format :refer [parse-num safe-div]]
            [hyperopen.views.account-equity.pricing
             :refer [normalized-token-name perp-market-for-coin sum-when-present
                     token-price-usd]]))

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

(defn account-ratio
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

(defn- known-collateral-tokens
  "Every token that backs perp positions anywhere on the venue, not just on the
   dexes this wallet happens to trade.

   The venue builds this set from its whole dex catalogue, so a wallet holding
   a token that collateralises some dex it has never touched still has that
   holding counted as collateral. `flx` settles in USDH, for instance, which is
   why a USDH balance belongs in the denominator of an account whose positions
   are all USDC-quoted. The market catalogue carries the same information:
   every perp market's `:quote` is its dex's collateral symbol. Record quote
   tokens are unioned in so a dex present in state but missing from the
   catalogue still contributes."
  [market-by-key records]
  (into (set (keep :quote-token records))
        (keep (fn [market]
                (when (= :perp (:market-type market))
                  (normalized-token-name (:quote market)))))
        (vals (or market-by-key {}))))

(defn- unified-collateral-usd-value
  [records balance-row-by-token market-by-key]
  (sum-when-present
   (for [token (known-collateral-tokens market-by-key records)]
     (let [spot-total (parse-num (get-in balance-row-by-token [token :total-balance]))
           usd-price (token-price-usd balance-row-by-token market-by-key token)]
       (when (and (number? spot-total)
                  (number? usd-price))
         (* spot-total usd-price))))))

(defn isolated-notional-usd-value
  "Isolated perp notional in USD, so the panel can say what the cross-only
   leverage and maintenance figures leave out. nil when there are no isolated
   positions -- the disclosure line is then omitted rather than reading
   \"excludes $0.00\" -- and nil when any position's quote token has no
   resolvable price, since a partial total would understate what is excluded."
  [records balance-row-by-token market-by-key]
  (let [contributions
        (vec
         (for [record records
               position-row (record-positions record)
               :when (isolated-position? position-row)]
           (let [notional (position-notional position-row)
                 quote-token (position-quote-token market-by-key record position-row)
                 usd-price (token-price-usd balance-row-by-token market-by-key quote-token)]
             (when (and (number? notional)
                        (number? usd-price))
               (* notional usd-price)))))]
    (when (and (seq contributions)
               (not (some nil? contributions)))
      (reduce + 0 contributions))))

(defn account-leverage
  "Cross perp notional over total collateral, which is how the venue defines
   it: \"Unified Account Leverage = Total Cross Positions Value / Total
   Collateral Balance.\" Isolated positions are deliberately outside the
   numerator even though their margin comes from the same collateral pool --
   matching the venue matters more here than a fuller lens would, because the
   venue is what liquidates."
  [aggregate records balance-row-by-token market-by-key]
  (safe-div (:cross-total-ntl-pos aggregate)
            (unified-collateral-usd-value records balance-row-by-token market-by-key)))
