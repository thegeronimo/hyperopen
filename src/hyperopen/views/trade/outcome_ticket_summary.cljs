(ns hyperopen.views.trade.outcome-ticket-summary
  "The numbers under the outcome ticket's amount: what a buy costs and keeps,
   what a cash out receives, what a merge returns, and the button that says
   so. Fees come from `outcomes.domain.fees`; unknown venues print no number."
  (:require [hyperopen.outcomes.domain.fees :as fees]
            [hyperopen.outcomes.domain.quotes :as quotes]))

(defn- parse
  [value]
  (let [number (cond
                 (number? value) value
                 (string? value) (js/parseFloat value)
                 :else js/NaN)]
    (when-not (js/isNaN number)
      number)))

(defn- within-cap
  "Levels an IOC order can actually take: asks at or under a buy's price cap,
   bids at or over a sale's."
  [levels cap side]
  (if (number? cap)
    (filterv (fn [[px]] (if (= :buy side) (<= px cap) (>= px cap))) levels)
    levels))

(defn- partial-fill-row
  [fill shares side-label]
  (when (and fill (< (:shares fill) shares))
    ["Fills at your price cap" (str (:shares fill) " of " shares " " side-label)]))

(def ^:private fee-unconfirmed "Fee not confirmed for this venue")

(defn- fee-label
  [prefix rate]
  (str prefix " (" (fees/rate-text rate) ")"))

(defn- negative-usd
  [amount]
  (quotes/usd-text (- amount)))

(defn- profit-text
  [profit cost]
  (str (quotes/usd-text profit {:signed? true})
       (when (and (number? cost) (pos? cost))
         (let [pct (* 100 (/ profit cost))]
           (str " (" (if (neg? pct) "−" "+") (.toFixed (js/Math.abs pct) 1) "%)")))))

(defn buy-summary
  "Buying is free; the fee comes at settlement, so the ticket shows what you
   keep if right after it."
  [{:keys [market-order? shares limit-price asks side-label cap-price venue rates]}]
  (let [fill (when market-order? (quotes/fill-shares (within-cap asks cap-price :buy) shares))
        filled (if market-order? (:shares fill) shares)
        cost (if market-order?
               (:proceeds fill)
               (when (number? limit-price) (* shares limit-price)))
        avg (if market-order? (:avg-price fill) limit-price)
        {:keys [pays]} (quotes/payout filled cost)
        buy-fee (fees/buy {:shares filled :price avg :venue venue})
        settle (when (and (:fee-known? buy-fee) (number? cost))
                 (fees/settlement {:shares filled :venue venue :rates rates :cost cost}))]
    {:shares shares
     :rows (when (pos? shares)
             (cond-> [["Shares" (str filled " " side-label)]
                      ["Average price" (quotes/cents-text avg {:precise? true})]
                      ["Cost" (quotes/usd-text cost)]
                      (if (:fee-known? buy-fee)
                        ["Fee to buy" "None"]
                        ["Fees" fee-unconfirmed])
                      [(str "If " side-label " wins") (quotes/usd-text pays)]]
               (:fee-known? settle)
               (into [[(fee-label "Settlement fee" (:rate settle)) (negative-usd (:fee settle))]
                      ["You keep if right" (quotes/usd-text (:net settle))]
                      ["Profit" (profit-text (:profit settle) cost)]])
               (partial-fill-row fill shares side-label) (conj (partial-fill-row fill shares side-label))))
     :cost cost
     :avg avg}))

(defn cash-out-summary
  [{:keys [market-order? shares limit-price bids held entry-notional side-label cap-price
           venue rates best-bid other-label]}]
  (let [fill (when market-order? (quotes/fill-shares (within-cap bids cap-price :sell) shares))
        price (if market-order? (:avg-price fill) limit-price)
        gross (if market-order?
                (:proceeds fill)
                (when (number? limit-price) (* shares limit-price)))
        ;; A limit that does not reach the best bid rests, and rests at maker.
        resting? (and (not market-order?)
                      (number? limit-price)
                      (not (and (number? best-bid) (<= limit-price best-bid))))
        cost-basis (when (pos? held) (* entry-notional (/ shares held)))
        sale (when (number? gross)
               (fees/sell {:shares shares :price price :venue venue :rates rates
                           :resting? resting? :cost cost-basis}))
        ;; Walked levels: the proceeds, not shares x average, are exact.
        sale (if (and sale (:fee-known? sale))
               (let [fee (* gross (:rate sale))
                     net (- gross fee)]
                 (cond-> (assoc sale :gross gross :fee fee :net net)
                   (number? cost-basis) (assoc :result (- net cost-basis))))
               sale)
        hold (when (pos? shares)
               (fees/settlement {:shares shares :venue venue :rates rates}))]
    {:shares shares
     :rows (when (pos? shares)
             (cond-> [["Selling" (str shares " " side-label)]
                      [(if market-order? "Sell at best bid" "Sell at") (quotes/cents-text price {:precise? true})]
                      ["Sale" (quotes/usd-text gross)]]
               (:fee-known? sale)
               (into [[(fee-label "Fee" (:rate sale)) (negative-usd (:fee sale))]
                      ["You receive" (quotes/usd-text (:net sale))]])
               (and sale (not (:fee-known? sale)))
               (conj ["Fee" fee-unconfirmed])
               (number? (:result sale))
               (conj ["Result vs cost" (quotes/usd-text (:result sale) {:signed? true})])
               (partial-fill-row fill shares side-label) (conj (partial-fill-row fill shares side-label))))
     :notes (when (and (pos? shares) (:fee-known? sale))
              (cond-> []
                (not resting?)
                (conj (str "Resting at your own price instead pays "
                           (fees/rate-text (fees/exit-rate rates {:resting? true})) "."))
                (:fee-known? hold)
                (conj (str "Or hold to the result: " (quotes/usd-text (:net hold)) " after the fee if "
                           side-label " wins, $0 if " other-label " wins."))))
     :proceeds gross
     :net (when (:fee-known? sale) (:net sale))
     :price price}))

(defn limit-note
  [{:keys [mode limit-price best-bid best-ask side-label]}]
  (when (number? limit-price)
    (let [price-text (quotes/cents-text limit-price)]
      (if (= :cash-out mode)
        (if (and (number? best-bid) (<= limit-price best-bid))
          (str "Crosses the book: sells now at " price-text " or better.")
          (str "Rests above the best bid. Fills only if " side-label " trades up to " price-text "."))
        (if (and (number? best-ask) (>= limit-price best-ask))
          (str "Crosses the book: buys now at " price-text " or better.")
          (str "Rests below the best ask. Fills only if " side-label " trades down to " price-text "."))))))

(defn submit-label
  [{:keys [mode market-order? shares side-label option-label limit-price cost net state price best-ask]}]
  (let [subject (str side-label (when option-label (str " on " option-label)))
        range? (= :range state)]
    (cond
      (zero? shares) (if (= :cash-out mode) (str "Cash out " subject) (str "Buy " subject))
      ;; A limit order names its price on the button; with none there is no order.
      (and (not market-order?) (not (number? limit-price))) "Enter a limit price"
      (and (= :cash-out mode) market-order?)
      (str "Cash out " shares " " subject
           ;; Across a range the price is spelled out on the button.
           (when (and range? (number? price)) (str " at " (quotes/cents-text price)))
           (when (number? net) (str " · " (quotes/usd-text net))))
      (= :cash-out mode) (str "Place limit sell · " shares " " subject " at " (quotes/cents-text limit-price {:precise? true}))
      (and market-order? range?) (str "Buy " subject " at " (quotes/cents-text best-ask))
      market-order? (str "Buy " subject " · " (quotes/usd-text cost))
      :else (str "Place limit · " shares " " subject " at " (quotes/cents-text limit-price {:precise? true})))))

(defn merge-model
  "Merge ticket: a Yes and a No of one contract go back to $1.00 each."
  [{:keys [merge-spec labels sides holdings venue rates outcome-id]}]
  (let [held (mapv #(get-in holdings [(:coin %) :shares] 0) sides)
        max-pairs (js/Math.floor (apply min held))
        typed (parse (:pairs merge-spec))
        pairs (min (if (and (number? typed) (pos? typed)) (js/Math.floor typed) 0) max-pairs)
        result (fees/merge-pairs {:pairs pairs :venue venue :rates rates})
        [label-a label-b] labels
        subject (str pairs (if (= 1 pairs) " pair" " pairs"))
        submitting? (true? (:submitting? merge-spec))]
    {:held-text [(str (js/Math.floor (first held)) " " label-a)
                 (str (js/Math.floor (second held)) " " label-b)]
     :sentence (str "One " label-a " and one " label-b " together pay $1.00 whoever wins.")
     :pairs-text (or (:pairs merge-spec) "")
     :pairs pairs
     :max-pairs max-pairs
     :rows (if (:fee-known? result)
             [["Returns" (quotes/usd-text (:gross result))]
              [(fee-label "Fee" (:rate result)) (negative-usd (:fee result))]
              ["You receive" (quotes/usd-text (:net result))]
              ["Leaves" (let [[n label] (apply max-key first (map (fn [n label] [(- n pairs) label]) held labels))]
                          (str (js/Math.floor n) " " label))]]
             [["Returns" (quotes/usd-text (* 1.0 pairs))]
              ["Fee" fee-unconfirmed]])
     :fee-known? (boolean (:fee-known? result))
     :net (:net result)
     :submit-label (cond
                     (zero? pairs) "Merge pairs"
                     (:fee-known? result) (str "Merge " subject " · " (quotes/usd-text (:net result)))
                     :else (str "Merge " subject))
     :submit-blocked? (or (zero? pairs) submitting?)
     :submitting? submitting?
     :error (:error merge-spec)
     :submit-args {:outcome-id outcome-id :pairs pairs}
     :entry {:coins (mapv :coin sides) :pairs (str max-pairs)}}))
