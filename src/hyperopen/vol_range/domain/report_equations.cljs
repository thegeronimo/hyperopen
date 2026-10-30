(ns hyperopen.vol-range.domain.report-equations
  "The Vol Range headline equations (HO-SPEC-014 v2.1 section 12.3, 13.5
  rows 1 to 3) for a half-open window `[a,b)`:

    Net tracked asset P&L = (C_b − C_a) + q_b×P_b − q_a×P_a
    Hold P&L              = q_a×(P_b − P_a)
    Ladder contribution   = (C_b − C_a) + (q_b − q_a)×P_b

  so Net = Hold + Contribution exactly. `q` is the campaign's base
  inventory and `C` its cumulative tracked cash flow (no USDC balance ever
  enters), each reconstructed from the events strictly before the
  boundary. These are dollar contributions, not equity or returns. Exact
  decimals (`hyperopen.domain.trading.precision`); pure."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]))

(defn- d [x] (some-> x str str/trim precision/parse-decimal))
(defn- ds [x] (precision/decimal->string x))

(def figure-keys [:net-pnl :hold-pnl :contribution])

(defn window-figures
  "{:net-pnl :hold-pnl :contribution} as canonical decimal strings for
  {:q-a :c-a :p-a :q-b :c-b :p-b} (decimal strings), or nil when any input
  is not a decimal."
  [{:keys [q-a c-a p-a q-b c-b p-b]}]
  (let [[qa ca pa qb cb pb :as xs] (map d [q-a c-a p-a q-b c-b p-b])]
    (when (every? some? xs)
      (let [cash (precision/sub cb ca)]
        {:net-pnl (ds (precision/sub (precision/add cash (precision/mul qb pb)) (precision/mul qa pa)))
         :hold-pnl (ds (precision/mul qa (precision/sub pb pa)))
         :contribution (ds (precision/add cash (precision/mul (precision/sub qb qa) pb)))}))))

(defn sum-figures
  "The figure-by-figure sum of `figures` maps (each from `window-figures`),
  or nil for none or when any is nil."
  [figures]
  (when (and (seq figures) (every? map? figures))
    (into {} (for [k figure-keys]
               [k (ds (reduce precision/add (precision/parse-decimal "0") (map (comp d k) figures)))]))))

(defn identity-holds?
  "True when Net = Hold + Contribution exactly."
  [{:keys [net-pnl hold-pnl contribution]}]
  (zero? (precision/cmp (d net-pnl) (precision/add (d hold-pnl) (d contribution)))))
