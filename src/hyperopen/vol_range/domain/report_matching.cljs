(ns hyperopen.vol-range.domain.report-matching
  "Gross spread on matched fills, the secondary Vol Range diagnostic
  (HO-SPEC-014 v2.1 section 12.5, 13.5 row 5).

  Over the campaign's tracked fills with `a <= time < b`, sorted by (time,
  tid, event-id), buy and sell queues start empty and hold GROSS (pre-fee)
  quantities. Each fill matches the oldest opposite-side unmatched quantity,
  splitting partials; every split counts as one matched pair, in either
  order (buy then sell or sell then buy). No exchange-defined cycle is
  implied.

    gross matched spread  = Σ q × (sell_px − buy_px)
    unmatched buys        = Σ q × (P_end − buy_px)
    sold inventory not
      reaccumulated       = Σ q × (sell_px − P_end)
    fee and rebate impact = − USDC fees − net base fees × P_end

  Their sum equals the window's Ladder contribution versus hold whenever
  every fee is in USDC or the base token (a negative fee is a rebate and
  reverses the sign). A fee in a third token leaves the reconciliation
  unavailable; attribution already breaks there. The matching is never
  added to the headline and is not a cost basis. Exact decimals; pure."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.domain.report-ledger :as ledger]))

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str str/trim precision/parse-decimal) zero))
(defn- ds [x] (precision/decimal->string x))
(defn- sum [xs] (reduce precision/add zero xs))

(defn- window-fills
  "Tracked fill events of `campaign-id` (or of none named) with `a <= time < b`."
  [events campaign-id [a b]]
  (->> (ledger/in-window (ledger/events events) [a b])
       (filter :tracked?)
       (filter #(contains? #{:buy :sell} (:side %)))
       (filter #(or (nil? campaign-id) (nil? (:campaign-id %)) (= campaign-id (:campaign-id %))))
       vec))

(defn- match-one
  "Match one fill `e` against the opposite queue in `acc`."
  [{:keys [buys sells] :as acc} e]
  (let [buy? (= :buy (:side e))
        px (d (:px e))]
    (loop [opposite (if buy? sells buys)
           remaining (d (:sz e))
           acc acc]
      (let [head (first opposite)]
        (if (and head (precision/pos-dec? remaining))
          (let [q (precision/dec-min remaining (:remaining head))
                spread (if buy?
                         (precision/mul q (precision/sub (:px head) px))
                         (precision/mul q (precision/sub px (:px head))))
                left (precision/sub (:remaining head) q)
                opposite' (if (precision/pos-dec? left)
                            (assoc-in opposite [0 :remaining] left)
                            (subvec opposite 1))]
            (recur opposite'
                   (precision/sub remaining q)
                   (-> acc
                       (update :gross precision/add spread)
                       (update :matched-qty precision/add q)
                       (update :pairs inc))))
          (let [acc (assoc acc (if buy? :sells :buys) opposite)]
            (if (precision/pos-dec? remaining)
              (update acc (if buy? :buys :sells) conj {:px px :remaining remaining})
              acc)))))))

(defn- unmatched [queue p-end side]
  {:qty (sum (map :remaining queue))
   :value (sum (for [{:keys [px remaining]} queue]
                 (precision/mul remaining (if (= :buy side)
                                            (precision/sub p-end px)
                                            (precision/sub px p-end)))))})

(defn- fees-by-token [events]
  (->> events
       (filter :fee-token)
       (reduce (fn [acc e] (update acc (:fee-token e) #(precision/add (or % zero) (d (:fee e))))) {})
       (into {} (map (fn [[k v]] [k (ds v)])))))

(defn match-window
  "The matched-fill diagnostic of `campaign`'s tracked `events` over `[a-ms,
  b-ms)` at the end mark `p-end` (a decimal string): {:fills :pairs
  :matched-qty :gross :unmatched-buy-qty :unmatched-buys :unmatched-sell-qty
  :unmatched-sells :usdc-fees :base-fees :fee-impact :total :p-end
  :fees-by-token :reconciles?}, decimal strings. `:total` (gross + unmatched
  + fee impact) is nil when a fee is in a third token (`:reconciles?`
  false). Nil when `p-end` is not a decimal."
  [{:keys [campaign events a-ms b-ms p-end]}]
  (when-let [pe (some-> p-end str str/trim precision/parse-decimal)]
    (let [fills (window-fills events (:campaign-id campaign) [a-ms b-ms])
          {:keys [buys sells gross matched-qty pairs]}
          (reduce match-one {:buys [] :sells [] :gross zero :matched-qty zero :pairs 0} fills)
          ub (unmatched buys pe :buy)
          us (unmatched sells pe :sell)
          fee-of (fn [kind] (sum (keep #(when (= kind (:fee-kind %)) (d (:fee %))) fills)))
          usdc-fees (fee-of :quote)
          base-fees (fee-of :base)
          unsupported? (some #(and (= :unsupported (:fee-kind %)) (not (precision/zero-dec? (d (:fee %))))) fills)
          fee-impact (precision/neg (precision/add usdc-fees (precision/mul base-fees pe)))
          total (sum [gross (:value ub) (:value us) fee-impact])]
      {:fills (count fills)
       :pairs pairs
       :matched-qty (ds matched-qty)
       :gross (ds gross)
       :unmatched-buy-qty (ds (:qty ub))
       :unmatched-buys (ds (:value ub))
       :unmatched-sell-qty (ds (:qty us))
       :unmatched-sells (ds (:value us))
       :usdc-fees (ds usdc-fees)
       :base-fees (ds base-fees)
       :fee-impact (ds fee-impact)
       :total (when-not unsupported? (ds total))
       :p-end (ds pe)
       :fees-by-token (fees-by-token fills)
       :reconciles? (not unsupported?)})))

(defn reconciles-with?
  "True when the diagnostic's total equals `contribution` (a decimal string)
  exactly."
  [{:keys [total]} contribution]
  (boolean (and total contribution
                (zero? (precision/cmp (d total) (d contribution))))))
