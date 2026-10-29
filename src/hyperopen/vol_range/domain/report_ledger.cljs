(ns hyperopen.vol-range.domain.report-ledger
  "Signed fill events for Vol Range reporting (HO-SPEC-014 v2.1 sections
  12.2, 12.4, 13.5 rows 3, 4 and 6).

  For gross filled quantity x at price p, a buy changes base by +x and USDC
  cash by -p*x; a sell by -x and +p*x. The venue's signed fee (which already
  includes any builder fee) is then subtracted from its actual token: a base
  fee from `net-base-delta`, a quote fee from `net-cash-delta`; a negative
  fee credits. A fee in any third token is `:unsupported`: it is kept as its
  actual token amount and never converted (attribution breaks elsewhere).

  Events sort by (time, tid, event-id). Boundary state uses the half-open
  convention: `state-before` at t counts events with `T0 <= time < t`, so a
  fill at exactly t belongs to the next interval. Exact decimals throughout;
  pure."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]))

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str str/trim precision/parse-decimal) zero))
(defn- ds [x] (precision/decimal->string x))

(defn token-name
  "A venue token label without any \":0x…\" token-id suffix."
  [x]
  (some-> x str str/trim (str/split #":" 2) first not-empty))

(defn- fee-kind [fee-token fee base-symbol quote-symbol]
  (let [t (token-name fee-token)]
    (cond (and t (= t (token-name base-symbol))) :base
          (and t (= t (token-name quote-symbol))) :quote
          (precision/zero-dec? fee) :quote
          :else :unsupported)))

(defn fill-event
  "The signed event for a canonical fill record (`fill-records/normalize-fill`)
  in a market whose base and quote token names are given, or nil when the
  price or size is not a decimal. `ctx` carries the attribution:
  {:base-symbol :quote-symbol :tracked? :campaign-id :grid-id :operation-id
   :logical-id}."
  [fill {:keys [base-symbol quote-symbol tracked? campaign-id grid-id operation-id logical-id]}]
  (let [px (some-> (:px fill) str precision/parse-decimal)
        sz (some-> (:sz fill) str precision/parse-decimal)
        side (:side fill)]
    (when (and px sz (contains? #{:buy :sell} side))
      (let [fee (d (:fee fill))
            kind (fee-kind (:fee-token fill) fee base-symbol quote-symbol)
            notional (precision/mul px sz)
            base-delta (if (= :buy side) sz (precision/neg sz))
            cash-delta (if (= :buy side) (precision/neg notional) notional)]
        {:event-id (:fill-key fill)
         :kind :fill
         :time (:time fill)
         :tid (:tid fill)
         :coin (:coin fill)
         :tracked? (boolean tracked?)
         :campaign-id campaign-id
         :grid-id grid-id
         :operation-id operation-id
         :logical-id logical-id
         :side side
         :px (ds px)
         :sz (ds sz)
         :crossed (:crossed fill)
         :base-delta (ds base-delta)
         :cash-delta (ds cash-delta)
         :fee (ds fee)
         :fee-token (or (token-name (:fee-token fill)) (when (= :quote kind) (token-name quote-symbol)))
         :fee-kind kind
         :net-base-delta (ds (if (= :base kind) (precision/sub base-delta fee) base-delta))
         :net-cash-delta (ds (if (= :quote kind) (precision/sub cash-delta fee) cash-delta))}))))

(defn- tid-key [tid]
  (let [t (str tid)] [(count t) t]))

(defn events
  "Distinct events (first per `:event-id`), sorted by (time, tid, event-id)."
  [xs]
  (->> (remove nil? xs)
       (reduce (fn [[seen out] e]
                 (if (contains? seen (:event-id e))
                   [seen out]
                   [(conj seen (:event-id e)) (conj out e)]))
               [#{} []])
       second
       (sort-by (fn [e] [(:time e) (tid-key (:tid e)) (str (:event-id e))]))
       vec))

(defn in-window
  "Events with `a <= time < b`."
  [events [a b]]
  (filterv #(and (number? (:time %)) (<= a (:time %)) (< (:time %) b)) events))

(defn state-before
  "{:q :c} at `t`: q0 plus the net base deltas and the net cash deltas of the
  campaign's tracked events with `T0 <= time < t`. `campaign` is
  {:campaign-id :t0-ms :baseline {:q0}}; events of another campaign are
  ignored when they name one."
  [campaign events t]
  (let [cid (:campaign-id campaign)
        mine (->> (in-window events [(:t0-ms campaign) t])
                  (filter :tracked?)
                  (filter #(or (nil? cid) (nil? (:campaign-id %)) (= cid (:campaign-id %)))))]
    {:q (ds (reduce precision/add (d (get-in campaign [:baseline :q0])) (map (comp d :net-base-delta) mine)))
     :c (ds (reduce precision/add zero (map (comp d :net-cash-delta) mine)))}))

(defn- vwap [quote base]
  (when (precision/pos-dec? base)
    (ds (precision/divide quote base 12 :half-up))))

(defn activity
  "Execution activity over `events`: fill count, gross bought and sold base
  and quote, VWAPs, maker and taker counts (`:crossed`), and signed fees by
  their actual token."
  [events]
  (let [side-sum (fn [side k] (reduce precision/add zero
                                      (keep #(when (= side (:side %)) (precision/abs-dec (d (get % k)))) events)))
        bought-base (side-sum :buy :base-delta)
        bought-quote (side-sum :buy :cash-delta)
        sold-base (side-sum :sell :base-delta)
        sold-quote (side-sum :sell :cash-delta)]
    {:fills (count events)
     :bought-base (ds bought-base)
     :bought-quote (ds bought-quote)
     :buy-vwap (vwap bought-quote bought-base)
     :sold-base (ds sold-base)
     :sold-quote (ds sold-quote)
     :sell-vwap (vwap sold-quote sold-base)
     :maker (count (filter #(false? (:crossed %)) events))
     :taker (count (filter #(true? (:crossed %)) events))
     :fees-by-token (->> events
                         (filter :fee-token)
                         (reduce (fn [acc e] (update acc (:fee-token e) #(precision/add (or % zero) (d (:fee e)))))
                                 {})
                         (into {} (map (fn [[k v]] [k (ds v)]))))}))
