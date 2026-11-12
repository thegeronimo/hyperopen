(ns hyperopen.views.account-equity.metrics
  "Every number on both account panels, derived once from application state.

   One function serves two panels: `account-equity-view` renders the venue's
   \"Unified Account Summary\" for unified accounts and \"Account Equity\" plus
   \"Perps Overview\" for everyone else, and both read this map. The conversion
   and aggregation live in `hyperopen.views.account-equity.pricing`; the
   unified-only figures live in `hyperopen.views.account-equity.unified`."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.borrow-lend.account :as bl-account]
            [hyperopen.vaults.application.user-equity :as user-equity]
            [hyperopen.views.account-equity.format :refer [parse-num pnl-display safe-div]]
            [hyperopen.views.account-equity.pricing :as pricing
             :refer [aggregate-clearinghouse-usd balance-rows-by-token
                     clearinghouse-state-records sum-when-present]]
            [hyperopen.views.account-equity.unified :as unified]
            [hyperopen.views.account-equity.venues :as venues]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections :as account-projections]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]))

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

(defn- parse-finite-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else
    nil))

(def ^:private hyperevm-funds-summary-key
  :account-equity/hyperevm-funds)

(def ^:private hyperevm-funds-summary-keys
  [:status :address :usd :unpriced-count])

(defn account-equity-hyperevm-funds
  "The value-level HyperEVM summary consumed by classic Account Equity.

   The /trade shell asks this through its lazy account-surfaces export, then
   retains the result rather than raw `:hyperevm` state in its memoized panel
   slice."
  [state]
  (select-keys (hyperevm-funds/hyperevm-funds state)
               hyperevm-funds-summary-keys))

(defn- outcome-shares-inputs
  "Everything `account-equity-outcome-shares` reads, compared by identity."
  [state]
  [(get-in state [:spot :clearinghouse-state :balances])
   (get-in state [:asset-selector :market-by-key])
   (:active-market state)
   (get-in state [:asset-selector :active-market])
   (get-in state [:active-assets :contexts])
   (:asset-contexts state)
   (get-in state [:webdata2 :spotAssetCtxs])
   (:orderbooks state)])

(defn- same-inputs?
  [inputs cached-inputs]
  (and (= (count inputs) (count cached-inputs))
       (every? true? (map identical? inputs cached-inputs))))

(defonce ^:private outcome-shares-memo
  ;; {:inputs <outcome-shares-inputs> :narrow <outcome-rows-inputs> :value}
  (atom nil))

(defn account-equity-outcome-shares
  "Dollars the account's outcome shares are worth at the best bid, before the
   closing fee, rounded to cents. Account value counts them; buying power and
   margin never do. Nil when none are held.

   /trade asks on every store write. A write touching none of the inputs
   costs pointer compares; one touching them (any book tick, any context
   update) narrows them to the held sides first, and the rows are rebuilt
   only when a held side's balance, catalog entry, context or book changed."
  [state]
  (let [inputs (outcome-shares-inputs state)
        memo @outcome-shares-memo]
    (if (and memo (same-inputs? inputs (:inputs memo)))
      (:value memo)
      (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
            options {:active-market (:active-market state)
                     :selector-active-market (get-in state [:asset-selector :active-market])
                     :active-contexts (get-in state [:active-assets :contexts] {})
                     :asset-contexts (:asset-contexts state)
                     :spot-asset-ctxs (get-in state [:webdata2 :spotAssetCtxs])
                     :orderbooks (:orderbooks state)}
            narrow (account-projections/outcome-rows-inputs (:spot state) market-by-key options)
            value (cond
                    (nil? narrow) nil

                    (and memo (account-projections/same-outcome-rows-inputs? narrow (:narrow memo)))
                    (:value memo)

                    :else
                    (some-> (account-projections/build-outcome-rows (:spot state) market-by-key options)
                            account-projections/outcome-shares-value
                            (* 100)
                            js/Math.round
                            (/ 100)))]
        (reset! outcome-shares-memo {:inputs inputs :narrow narrow :value value})
        value))))

(defonce ^:private outcome-shares-cache (atom nil))

(defn- outcome-shares-summary
  "The outcome shares value, recomputed only when one of its inputs changed
   (not on every account-equity read)."
  [state]
  (if (contains? state :account-equity/outcome-shares)
    (get state :account-equity/outcome-shares)
    (let [inputs (outcome-shares-inputs state)
          cache @outcome-shares-cache]
      (if (and (map? cache) (same-inputs? inputs (:inputs cache)))
        (:value cache)
        (let [value (account-equity-outcome-shares state)]
          (reset! outcome-shares-cache {:inputs inputs :value value})
          value)))))

(defn- plus-outcome-shares
  [total outcome-shares]
  (if (and (number? total) (number? outcome-shares))
    (+ total outcome-shares)
    total))

(defn- account-equity-hyperevm-funds-summary
  [state]
  ;; A present key belongs to the reduced /trade slice. In particular, nil
  ;; means the lazy export has not supplied a full-state summary yet; deriving
  ;; from that incomplete slice would invent a second source of truth.
  (if (contains? state hyperevm-funds-summary-key)
    (get state hyperevm-funds-summary-key)
    (account-equity-hyperevm-funds state)))

(defn- current-hyperevm-equity
  [state funds]
  (let [effective-address (account-context/effective-account-address state)
        usd (parse-finite-number (:usd funds))
        unpriced-count (:unpriced-count funds)]
    (when (and effective-address
               (= effective-address (:address funds))
               (= :ready (:status funds))
               (some? usd)
               (number? unpriced-count)
               (zero? unpriced-count))
      usd)))

(defn- derive-account-equity-metrics [state hyperevm-funds-summary outcome-shares]
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
        unified? (unified-account? state)
        spot-values (keep (fn [row]
                            (when-not (perps-balance-row? row)
                              (parse-num (:usdc-value row))))
                          balance-rows)
        ;; Retain the existing public metric whenever spot rows are unavailable.
        ;; Only a classic account's confirmed empty balance snapshot is zero for
        ;; the new four-part total; Unified keeps its existing fallback.
        spot-equity-from-rows (when (seq spot-values)
                                (reduce + spot-values))
        spot-equity (or spot-equity-from-rows
                        (when (and (not unified?)
                                   (sequential? (get-in state [:spot :clearinghouse-state :balances]))
                                   (empty? (get-in state [:spot :clearinghouse-state :balances])))
                          0))
        portfolio-value (account-projections/portfolio-usdc-value balance-rows)
        cross-margin-ratio (safe-div maintenance-margin cross-account-value)
        cross-account-leverage (safe-div cross-total-ntl-pos cross-account-value)
        ;; The existing classic Account Value presentation remains Spot + Perps.
        ;; Summing balance rows instead would double-count named-dex equity and
        ;; add its collateral token to a USD total unconverted.
        account-value-display (plus-outcome-shares
                               (if unified?
                                 (derive-account-value-display portfolio-value spot-equity perps-value)
                                 (sum-when-present [spot-equity-from-rows perps-value]))
                               outcome-shares)
        vault-equity (when-not unified?
                       (user-equity/current-vault-equity state))
        hyperevm-equity (when-not unified?
                          (current-hyperevm-equity state hyperevm-funds-summary))
        ;; Supplied funds leave the spot balance (and a borrow lands in it),
        ;; so lending is counted here, net of debt, or the total drops when
        ;; the account supplies.
        earn-equity (when-not unified?
                      (bl-account/net-earn-usd state))
        total-account-value-display (when (and (not unified?)
                                               (number? spot-equity)
                                               (number? perps-value)
                                               (number? vault-equity)
                                               (number? hyperevm-equity))
                                      (+ spot-equity perps-value vault-equity hyperevm-equity
                                         (or earn-equity 0)
                                         (or outcome-shares 0)))
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
     :outcome-shares-value outcome-shares
     :vault-equity vault-equity
     :earn-equity earn-equity
     :hyperevm-equity hyperevm-equity
     :total-account-value-display total-account-value-display
     :perp-venues (venues/perp-venues state records balance-row-by-token market-by-key)
     :pnl-info pnl-info}))

(defn- memoized-account-equity-metrics
  [state]
  (let [webdata2 (:webdata2 state)
        spot-data (:spot state)
        account (:account state)
        vaults (:vaults state)
        borrow-lend (:borrow-lend state)
        effective-address (account-context/effective-account-address state)
        hyperevm-funds-summary (account-equity-hyperevm-funds-summary state)
        outcome-shares (outcome-shares-summary state)
        perp-dex-states (:perp-dex-clearinghouse state)
        market-by-key (get-in state [:asset-selector :market-by-key])
        active-venue (venues/active-venue-id (:active-asset state))
        cache @account-equity-metrics-cache
        cache-hit? (and (map? cache)
                        (identical? webdata2 (:webdata2 cache))
                        (identical? spot-data (:spot-data cache))
                        (identical? account (:account cache))
                        (identical? vaults (:vaults cache))
                        (identical? borrow-lend (:borrow-lend cache))
                        (= effective-address (:effective-address cache))
                        (= hyperevm-funds-summary (:hyperevm-funds-summary cache))
                        (= outcome-shares (:outcome-shares cache))
                        (identical? perp-dex-states (:perp-dex-states cache))
                        (= active-venue (:active-venue cache))
                        (identical? market-by-key (:market-by-key cache)))]
    (if cache-hit?
      (:result cache)
      (let [result (derive-account-equity-metrics state hyperevm-funds-summary outcome-shares)]
        (reset! account-equity-metrics-cache {:webdata2 webdata2
                                              :spot-data spot-data
                                              :account account
                                              :vaults vaults
                                              :borrow-lend borrow-lend
                                              :effective-address effective-address
                                              :hyperevm-funds-summary hyperevm-funds-summary
                                              :outcome-shares outcome-shares
                                              :perp-dex-states perp-dex-states
                                              :active-venue active-venue
                                              :market-by-key market-by-key
                                              :result result})
        result))))

(defn account-equity-metrics [state]
  (memoized-account-equity-metrics state))

(defn reset-account-equity-metrics-cache!
  []
  (reset! outcome-shares-cache nil)
  (reset! outcome-shares-memo nil)
  (reset! account-equity-metrics-cache nil))
