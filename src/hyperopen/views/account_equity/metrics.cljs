(ns hyperopen.views.account-equity.metrics
  "Every number on both account panels, derived once from application state.

   One function serves two panels: `account-equity-view` renders the venue's
   \"Unified Account Summary\" for unified accounts and \"Account Equity\" plus
   \"Perps Overview\" for everyone else, and both read this map. The conversion
   and aggregation live in `hyperopen.views.account-equity.pricing`; the
   unified-only figures live in `hyperopen.views.account-equity.unified`."
  (:require [clojure.string :as str]
            [hyperopen.views.account-equity.format :refer [parse-num pnl-display safe-div]]
            [hyperopen.views.account-equity.pricing :as pricing
             :refer [aggregate-clearinghouse-usd balance-rows-by-token
                     clearinghouse-state-records sum-when-present]]
            [hyperopen.views.account-equity.unified :as unified]
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

(def token-price-usd
  "Re-exported for callers that only need one token's USD price."
  pricing/token-price-usd)

(defn- perps-balance-row?
  "Balance rows carrying perps equity: the base dex's `perps-usdc` row and one
   `perps-usdc-<dex>` row per named dex. They are not spot holdings. Counting
   them as spot is what made the Spot row on a named-dex account report the
   whole perps book."
  [row]
  (boolean (some-> (:key row) (str/starts-with? "perps-usdc"))))

(defn- derive-account-equity-metrics [state]
  (let [webdata2 (:webdata2 state)
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        balance-rows (derived-cache/memoized-balance-rows webdata2 (:spot state) (:account state) market-by-key (:perp-dex-clearinghouse state))
        balance-row-by-token (balance-rows-by-token balance-rows)
        ;; One record per dex the wallet has a snapshot on, base dex included.
        ;; The venue folds them into a single state before deriving anything,
        ;; and so do we: reading `[:webdata2 :clearinghouseState]` alone is the
        ;; base dex only, which reports nothing at all for an account carrying
        ;; its book on a HIP-3 dex.
        records (clearinghouse-state-records state market-by-key)
        aggregate (aggregate-clearinghouse-usd records balance-row-by-token market-by-key)
        cross-account-value (:cross-account-value aggregate)
        cross-total-ntl-pos (:cross-total-ntl-pos aggregate)
        ;; Both panels show the same figure here: the venue renders
        ;; `crossMaintenanceMarginUsed` straight through, and its tooltip says
        ;; "the minimum portfolio value required to keep your *cross* positions
        ;; open". Isolated positions each carry their own maintenance and
        ;; liquidate alone; the unified panel discloses their notional
        ;; separately rather than folding it in here.
        maintenance-margin (:maintenance-margin aggregate)
        positions (derived-cache/memoized-positions webdata2 (:perp-dex-clearinghouse state))
        unrealized-from-positions (let [values (keep #(parse-num (get-in % [:position :unrealizedPnl])) positions)]
                                    (when (seq values)
                                      (reduce + values)))
        ;; A snapshot that lists its positions tells us the book is flat when
        ;; that list is empty. No snapshot at all tells us nothing, and must not
        ;; be reported as a flat book.
        positions-known? (boolean (some #(sequential? (get-in % [:state :assetPositions]))
                                        records))
        unrealized-pnl (cond
                         (some? unrealized-from-positions) unrealized-from-positions
                         positions-known? 0
                         :else nil)
        ;; The venue's two definitions, which our own tooltips already promise:
        ;; Perps is the aggregate account value, and Balance is that net of
        ;; unrealized PNL -- "Total Net Transfers + Total Realized Profit + Total
        ;; Net Funding Fees", the money in the account before the open book is
        ;; marked.
        perps-value (:account-value aggregate)
        base-balance (when (and (number? perps-value)
                                (number? unrealized-pnl))
                       (- perps-value unrealized-pnl))
        spot-values (keep (fn [row]
                            (when-not (perps-balance-row? row)
                              (parse-num (:usdc-value row))))
                          balance-rows)
        spot-equity (when (seq spot-values) (reduce + spot-values))
        portfolio-value (account-projections/portfolio-usdc-value balance-rows)
        cross-margin-ratio (safe-div maintenance-margin cross-account-value)
        cross-account-leverage (safe-div cross-total-ntl-pos cross-account-value)
        unified? (unified-account? state)
        ;; The classic panel adds an Account Value row the venue does not have,
        ;; which carries the obligation to equal the two rows beneath it. Summing
        ;; the balance rows instead would double-count a named dex's equity and
        ;; add its collateral token to a USD total unconverted.
        account-value-display (if unified?
                                (derive-account-value-display portfolio-value spot-equity perps-value)
                                (sum-when-present [spot-equity perps-value]))
        ;; What the cross-only leverage and maintenance figures leave out. The
        ;; panel prints it beside them so an all-isolated book cannot render a
        ;; bare 0.00x that reads as "no exposure".
        unified-isolated-notional (when unified?
                                    (unified/isolated-notional-usd-value records
                                                                         balance-row-by-token
                                                                         market-by-key))
        unified-account-ratio (if unified?
                                (unified/account-ratio records
                                                       balance-row-by-token
                                                       market-by-key)
                                (safe-div maintenance-margin portfolio-value))
        unified-account-leverage (if unified?
                                   (unified/account-leverage aggregate
                                                             records
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
     :maintenance-margin maintenance-margin
     :cross-account-leverage cross-account-leverage
     :unified-account-leverage unified-account-leverage
     :isolated-notional unified-isolated-notional
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
