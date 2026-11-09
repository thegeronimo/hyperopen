(ns hyperopen.outcomes.domain.quotes
  "Chance, price and payout rules for outcome sides.

   A side's price is what one share costs; a winning share pays $1.00, so the
   price is also the market's chance. Contracts that never traded report a
   placeholder mark of 0.50 and a book quoted 1.2¢ / 98.8¢ reports a 0.50 mid,
   so neither number may be shown as a chance. These rules decide what is.")

(def wide-spread
  "Above this bid/ask spread (5¢) the mid stops being a usable chance."
  0.05)

(defn- num
  [value]
  (let [number (cond
                 (number? value) value
                 (string? value) (js/parseFloat value)
                 :else js/NaN)]
    (when (and (number? number) (not (js/isNaN number)))
      number)))

(defn- positive?
  [value]
  (let [number (num value)]
    (and (number? number) (pos? number))))

(defn- price
  "A side price is strictly between 0 and 1; a 0 means the field was missing."
  [value]
  (let [number (num value)]
    (when (and (number? number) (pos? number))
      number)))

(defn side-quote
  "Quote for one side from its context alone (lists have no order book):
   {:state :live|:last|:not-traded|:unknown :chance :price}. A mark far from
   the mid can only happen when the spread is wider than twice the gap, so the
   mark (the last trade) is shown and labelled. \"Not traded\" needs positive
   evidence — a zero previous-day price and no volume — so a side whose context
   has not arrived yet reads as unknown, not untraded."
  [{:keys [mark mid prevDayRaw volume24h]}]
  (let [mark* (price mark)
        mid* (price mid)
        prev (num prevDayRaw)
        untraded? (and (number? prev) (zero? prev) (not (positive? volume24h)))]
    (cond
      (and (nil? mid*) untraded?) {:state :not-traded}
      (nil? mid*) (if (number? mark*)
                    {:state :last :chance mark* :price mark*}
                    {:state :unknown})
      (and (number? mark*) (> (js/Math.abs (- mid* mark*)) wide-spread))
      {:state :last :chance mark* :price mark*}
      :else {:state :live :chance mid* :price mid*})))

(defn book-quote
  "Quote from the live book: mid while the spread is at most 5¢, otherwise the
   last trade; no two-sided book is :no-market."
  [best-bid best-ask mark]
  (let [bid (num best-bid)
        ask (num best-ask)
        mark* (num mark)]
    (if (and (number? bid) (number? ask))
      (let [spread (- ask bid)]
        (if (<= spread (+ wide-spread 1e-9))
          {:state :live :chance (/ (+ bid ask) 2) :price (/ (+ bid ask) 2) :bid bid :ask ask :spread spread}
          {:state :last :chance mark* :price mark* :bid bid :ask ask :spread spread}))
      (cond-> {:state :no-market}
        (number? bid) (assoc :bid bid)
        (number? ask) (assoc :ask ask)
        (number? mark*) (assoc :last mark*)))))

(defn mirror-quote
  "The other side of the same contract: prices flip to 1 − p and bid/ask swap."
  [{:keys [chance price bid ask last] :as quote}]
  (cond-> (select-keys quote [:state :spread])
    (number? chance) (assoc :chance (- 1 chance))
    (number? price) (assoc :price (- 1 price))
    (number? ask) (assoc :bid (- 1 ask))
    (number? bid) (assoc :ask (- 1 bid))
    (number? last) (assoc :last (- 1 last))))

(defn chance-text
  "\"82%\", clamped to \"<1%\" and \">99%\"; nil chance is an em dash."
  [chance]
  (if-let [chance* (num chance)]
    (let [pct (js/Math.round (* 100 chance*))]
      (cond
        (< pct 1) "<1%"
        (> pct 99) ">99%"
        :else (str pct "%")))
    "—"))

(defn quote-text
  "What a list shows for a quote: the chance, or why there is none."
  [{:keys [state chance]}]
  (case state
    :not-traded "Not traded"
    :no-market "No market"
    (chance-text chance)))

(defn cents-text
  "Price in cents: \"81.9¢\"; `{:precise? true}` keeps the book's ticks (81.902¢)."
  ([price] (cents-text price nil))
  ([price {:keys [precise?]}]
   (if-let [price* (num price)]
     (str (.toFixed (* 100 price*) (if precise? 3 1)) "¢")
     "—")))

(def ^:private minus "−")

(defn points-text
  "A chance change in percentage points: \"+0.2 pts\", \"−1.0 pts\"."
  [delta]
  (when-let [delta* (num delta)]
    (let [pts (* 100 delta*)
          rounded (/ (js/Math.round (* 10 pts)) 10)]
      (str (cond (pos? rounded) "+" (neg? rounded) minus :else "")
           (.toFixed (js/Math.abs rounded) 1)
           " pts"))))

(defn change-24h
  "Chance change since the previous day's price, nil without a reference."
  [{:keys [mark prevDayRaw]}]
  (let [mark* (num mark)
        prev (num prevDayRaw)]
    (when (and (number? mark*) (number? prev) (pos? prev))
      (- mark* prev))))

(defn usd-text
  "Dollars with cents: $1,234.50; `{:signed? true}` adds + or −."
  ([value] (usd-text value nil))
  ([value {:keys [signed?]}]
   (when-let [value* (num value)]
     (let [abs-text (.toLocaleString (js/Math.abs value*) "en-US"
                                     #js {:minimumFractionDigits 2 :maximumFractionDigits 2})]
       (str (cond
              (and signed? (pos? value*)) "+"
              (neg? value*) minus
              :else "")
            "$" abs-text)))))

(defn compact-usd-text
  "Dollars for volume columns: $227K, $1.2M, $950."
  [value]
  (when-let [value* (num value)]
    (cond
      (>= value* 1e9) (str "$" (.toFixed (/ value* 1e9) 1) "B")
      (>= value* 1e6) (str "$" (.toFixed (/ value* 1e6) 1) "M")
      (>= value* 1e4) (str "$" (js/Math.round (/ value* 1e3)) "K")
      (>= value* 1e3) (str "$" (.toFixed (/ value* 1e3) 1) "K")
      :else (str "$" (js/Math.round value*)))))

(defn walk-asks
  "Spend `usdc` against ask levels ([[price shares] …], best first) in whole
   shares: {:shares :cost :avg-price :exhausted?}."
  [levels usdc]
  (let [budget (or (num usdc) 0)]
    (loop [[[px sz] & more] (seq levels)
           shares 0
           cost 0]
      (let [px* (num px)
            sz* (num sz)]
        (if (or (nil? px*) (nil? sz*) (<= px* 0))
          {:shares shares
           :cost cost
           :avg-price (when (pos? shares) (/ cost shares))
           :exhausted? (nil? px)}
          (let [affordable (js/Math.floor (+ 1e-9 (/ (- budget cost) px*)))
                fill (min sz* (max 0 affordable))
                shares* (+ shares fill)
                cost* (+ cost (* fill px*))]
            (if (< fill sz*)
              {:shares shares*
               :cost cost*
               :avg-price (when (pos? shares*) (/ cost* shares*))
               :exhausted? false}
              (recur more shares* cost*))))))))

(declare sell-into-bids)

(defn fill-shares
  "Take `shares` from levels ([[price shares] …], best first): asks for a buy,
   bids for a sell. {:shares :proceeds :avg-price}; proceeds is the cost of a buy."
  [levels shares]
  (sell-into-bids levels shares))

(defn sell-into-bids
  "Sell `shares` into bid levels ([[price shares] …], best first):
   {:shares :proceeds :avg-price}."
  [levels shares]
  (let [target (max 0 (or (num shares) 0))]
    (loop [[[px sz] & more] (seq levels)
           sold 0
           proceeds 0]
      (let [px* (num px)
            sz* (num sz)]
        (if (or (nil? px*) (nil? sz*) (>= sold target))
          {:shares sold
           :proceeds proceeds
           :avg-price (when (pos? sold) (/ proceeds sold))}
          (let [fill (min sz* (- target sold))]
            (recur more (+ sold fill) (+ proceeds (* fill px*)))))))))

(defn payout
  "What `shares` bought for `cost` pay if right: {:pays :profit :profit-pct}."
  [shares cost]
  (let [shares* (or (num shares) 0)
        cost* (or (num cost) 0)
        pays shares*]
    {:pays pays
     :profit (- pays cost*)
     :profit-pct (when (pos? cost*) (* 100 (/ (- pays cost*) cost*)))}))
