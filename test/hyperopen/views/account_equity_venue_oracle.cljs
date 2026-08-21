(ns hyperopen.views.account-equity-venue-oracle
  "An executable model of the Hyperliquid frontend's own account panels.

   This namespace answers one question: given a wallet's per-dex clearinghouse
   states and spot holdings, what would the venue print? It is transcribed from
   the venue's production bundle, and it is deliberately written in the venue's
   shape -- fold every dex into one synthetic clearinghouse state first, then
   derive each row from that sum. Our implementation derives per record and
   never materialises an aggregate, so the two are structurally different
   programs. That is the point: two differently-shaped implementations agreeing
   is evidence, whereas a formula agreeing with a copy of itself is not.

   For the same reason this namespace must not require anything under
   `hyperopen.views.account-equity.*`, down to the number parser. Sharing a
   helper is how two implementations quietly become one.

   The aggregator, verbatim from `app.hyperliquid.xyz/assets/config-Dq8XJCAJ.js`
   as shipped on 2026-08-21:

       function HM(e,t,n,r){let i=x8(),a=x8(),o=0,s=0,c=[];
       for(let l of Object.values(t)){let t=e[l.pdi];if(t){let e=n_(l.collateralToken,n,r);
       i=S8(i,t.marginSummary,e),a=S8(a,t.crossMarginSummary,e),
       o+=t.crossMaintenanceMarginUsed*e,s+=t.withdrawable*e,c.push(...t.assetPositions)}}
       return{assetPositions:c,marginSummary:i,crossMarginSummary:a,
       crossMaintenanceMarginUsed:o,withdrawable:s}}

   `x8()` is a zero summary, `S8(acc, summary, price)` adds a price-scaled
   summary onto an accumulator, and `n_(token, ctxs, meta)` is that token's USD
   price. Note that `assetPositions` are concatenated raw: the venue does not
   USD-convert position-level unrealized PNL, so neither does this oracle.

   The classic rows, from `index-CyXjSnYq.js`, with `g` bound to the aggregate
   `marginSummary`, `_` to the aggregate `crossMarginSummary`, `v` to the
   aggregate `crossMaintenanceMarginUsed` and `E` to the summed `unrealizedPnl`:

       perps                  = g?.accountValue ?? 0
       balance                = (g?.accountValue ?? 0) - E
       cross.margin.ratio     = v / ((_?.accountValue ?? 0) + 1e-8)
       maintenance.margin     = v
       cross.account.leverage = (_?.totalNtlPos ?? 0) / ((_?.accountValue ?? 0) + 1e-8)

   The unified rows come from `Hd()` (leverage) and `Vd()` (ratio) in the same
   bundle, and from the venue's own i18n bundle, which states the definition
   outright: \"Unified Account Leverage = Total Cross Positions Value / Total
   Collateral Balance.\""
  (:require [clojure.string :as str]))

(defn ->num
  "The venue's `parseFloat`, which is what its wire figures arrive as. Its own
   three lines rather than an import, for the reason given in the namespace
   docstring."
  [value]
  (cond
    (number? value) value
    (string? value) (let [parsed (js/parseFloat (str/trim value))]
                      (when-not (js/isNaN parsed) parsed))
    :else nil))

(defn- n0
  "JavaScript's `?? 0` over a wire field: absent and unparseable both read 0.
   The venue has no nil discipline here -- every row it prints falls back to a
   zero -- which is exactly the behaviour a parity check needs to see."
  [value]
  (or (->num value) 0))

(def ^:private zero-summary
  {:accountValue 0 :totalNtlPos 0 :totalRawUsd 0 :totalMarginUsed 0})

(def ^:private summary-fields
  [:accountValue :totalNtlPos :totalRawUsd :totalMarginUsed])

(defn- add-summary
  "`S8`: accumulate one dex's margin summary, scaled into USD by that dex's
   collateral price."
  [acc summary price]
  (reduce (fn [m field]
            (update m field + (* price (n0 (get summary field)))))
          acc
          summary-fields))

(defn aggregate
  "`HM`: one synthetic clearinghouse state in USD across every dex the wallet
   has a snapshot for.

   `per-dex` is a sequence of `{:state _ :collateral-price _}`. Each state's
   figures are denominated in that dex's collateral token, so each is scaled by
   that token's USD price before being summed."
  [per-dex]
  (reduce (fn [acc {:keys [state collateral-price]}]
            (let [price (n0 collateral-price)]
              (-> acc
                  (update :marginSummary add-summary (:marginSummary state) price)
                  (update :crossMarginSummary add-summary (:crossMarginSummary state) price)
                  (update :crossMaintenanceMarginUsed
                          + (* price (n0 (:crossMaintenanceMarginUsed state))))
                  (update :assetPositions into (or (:assetPositions state) [])))))
          {:marginSummary zero-summary
           :crossMarginSummary zero-summary
           :crossMaintenanceMarginUsed 0
           :assetPositions []}
          (or per-dex [])))

(def ^:private divide-by-zero-guard
  "The venue adds 1e-8 to the denominator of both classic quotients rather than
   branching on zero, so a flat account reads 0.00% and 0.00x instead of a
   placeholder."
  1e-8)

(defn unrealized-pnl
  "Summed position-level unrealized PNL, unconverted -- see the note on
   `assetPositions` in the namespace docstring."
  [positions]
  (reduce + 0 (map #(n0 (get-in % [:position :unrealizedPnl])) positions)))

(defn classic-rows
  "Every row the venue's classic panel prints, plus the Account Value row we
   add on top of it (the venue shows Spot and Perps and leaves the reader to
   add them, so its value is defined here as that sum)."
  [agg spot-usd]
  (let [account-value (get-in agg [:marginSummary :accountValue])
        cross-account-value (get-in agg [:crossMarginSummary :accountValue])
        cross-total-ntl-pos (get-in agg [:crossMarginSummary :totalNtlPos])
        maintenance (:crossMaintenanceMarginUsed agg)
        upnl (unrealized-pnl (:assetPositions agg))]
    {:account-value (+ (n0 spot-usd) account-value)
     :spot (n0 spot-usd)
     :perps account-value
     :balance (- account-value upnl)
     :unrealized-pnl upnl
     :cross-margin-ratio (/ maintenance (+ cross-account-value divide-by-zero-guard))
     :maintenance-margin maintenance
     :cross-account-leverage (/ cross-total-ntl-pos
                                (+ cross-account-value divide-by-zero-guard))}))

(defn- isolated?
  [position-row]
  (= "isolated"
     (some-> (get-in position-row [:position :leverage :type]) str str/lower-case)))

(defn- spot-total
  [spot-balances token]
  (->> spot-balances
       (filter #(= token (:coin %)))
       (map #(n0 (:total %)))
       (reduce + 0)))

(defn- isolated-margin-for-token
  "Isolated margin held on the dexes that settle in `token`, in that token."
  [per-dex token]
  (reduce (fn [total {:keys [collateral state]}]
            (if (= collateral token)
              (+ total (reduce + 0 (map #(n0 (get-in % [:position :marginUsed]))
                                        (filter isolated? (:assetPositions state)))))
              total))
          0
          per-dex))

(defn- cross-maintenance-for-token
  [per-dex token]
  (reduce (fn [total {:keys [collateral state]}]
            (if (= collateral token)
              (+ total (n0 (:crossMaintenanceMarginUsed state)))
              total))
          0
          per-dex))

(defn- unified-ratio
  "`Vd`: portfolio-liquidation risk, evaluated per collateral token and reported
   as the worst of them. Isolated margin is held back from the collateral that
   cross positions can draw on, and the reading is capped at 1."
  [per-dex spot-balances collateral-tokens]
  (let [ratios (keep (fn [token]
                       (let [maintenance (cross-maintenance-for-token per-dex token)
                             available (- (spot-total spot-balances token)
                                          (isolated-margin-for-token per-dex token))]
                         (when (pos? available)
                           (min 1 (/ maintenance available)))))
                     collateral-tokens)]
    (if (seq ratios)
      (reduce max ratios)
      0)))

(defn unified-rows
  "Every row the venue's unified panel prints.

   `collateral-tokens` is the set of tokens that collateralise a perp dex
   anywhere on the venue, which is what the venue's denominator spans -- not
   only the dexes this wallet trades on. `price-by-token` maps a token symbol
   to its USD price."
  [agg per-dex spot-balances collateral-tokens price-by-token]
  (let [price (fn [token] (n0 (get price-by-token token)))
        collateral-usd (reduce + 0 (map #(* (spot-total spot-balances %) (price %))
                                        collateral-tokens))
        cross-ntl-usd (get-in agg [:crossMarginSummary :totalNtlPos])
        isolated-usd (reduce (fn [total {:keys [collateral state]}]
                               (+ total (reduce + 0
                                                (map #(* (js/Math.abs
                                                          (n0 (get-in % [:position :positionValue])))
                                                         (price collateral))
                                                     (filter isolated? (:assetPositions state))))))
                             0
                             per-dex)]
    {:portfolio-value (reduce + 0 (map #(* (n0 (:total %)) (price (:coin %))) spot-balances))
     :unrealized-pnl (unrealized-pnl (:assetPositions agg))
     :maintenance-margin (:crossMaintenanceMarginUsed agg)
     :unified-account-leverage (if (zero? collateral-usd)
                                 0
                                 (/ cross-ntl-usd collateral-usd))
     :unified-account-ratio (unified-ratio per-dex spot-balances collateral-tokens)
     :isolated-notional isolated-usd}))
