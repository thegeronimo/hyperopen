(ns hyperopen.outcomes.domain.quotes
  "Chance, price and payout rules for outcome sides.

   A side's price is what one share costs; a winning share pays $1.00, so the
   price is also the market's chance. The feed has no last-trade price
   (`markPx` is not one) and never-traded contracts carry placeholder quotes
   whose midpoint sits near 0.50, so neither the mark nor a bare midpoint may
   be shown as a chance. ONE rule, read from the best bid and ask, decides what
   every surface (list, header, ticket) shows: `chance-quote`. `side-quote`
   and `book-quote` only gather its inputs."
  (:require [clojure.string :as str]))

(def wide-spread
  "Up to this bid/ask spread (5¢) the midpoint is the chance."
  0.05)

(def range-spread
  "Up to this spread (20¢) the bid–ask range is shown, muted, never one
   number; wider than this there is no market."
  0.20)

(def ^:private epsilon 1e-9)

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

(defn never-traded?
  "True only on positive evidence: a previous-day price of exactly 0 and no
   volume today. A side whose context has not arrived (no previous-day price)
   is not known to be untraded. Supply is not a test."
  [{:keys [prevDayRaw volume24h baseVolume24h]}]
  (let [prev (num prevDayRaw)]
    (boolean (and (number? prev)
                  (zero? prev)
                  (not (positive? volume24h))
                  (not (positive? baseVolume24h))))))

(defn chance-quote
  "The one chance rule. Input: {:bid :ask :book-loaded? :two-sided?
   :never-traded?}. `:book-loaded?` says bid/ask are authoritative (a book
   arrived, so a missing side is really empty); `:two-sided?` is the
   context's hint (a null `midPx` means the book lost a side). First match
   wins:

     :live        bid and ask ≤ 5¢ apart → the midpoint is the chance
     :not-traded  never traded and not tight → \"Not traded\", limit only
     :no-quotes   nothing known about the book yet → a dash
     :range       both sides, > 5¢ and ≤ 20¢ apart → bid–ask range, muted
     :no-market   > 20¢ apart, one-sided or empty → \"No market\", limit only

   Returns {:state :chance :price :low :high :bid :ask :spread :never-traded?
   :market-order? :rankable? :muted?}. `:chance`/`:price` only for :live;
   `:low`/`:high` only for :range; `:bid`/`:ask` whenever they stand.
   `:market-order?` is true for :live and :range; `:rankable?` (may be ranked
   or summed as a chance) only for :live."
  [{:keys [bid ask book-loaded? two-sided? never-traded?]}]
  (let [bid* (price bid)
        ask* (price ask)
        both? (and (number? bid*) (number? ask*))
        spread (when both? (- ask* bid*))
        no-info? (and (nil? bid*) (nil? ask*) (not book-loaded?))
        base (cond-> {:never-traded? (boolean never-traded?)
                      :market-order? false
                      :rankable? false
                      :muted? true}
               (number? bid*) (assoc :bid bid*)
               (number? ask*) (assoc :ask ask*)
               (number? spread) (assoc :spread spread))]
    (cond
      (and both? (<= spread (+ wide-spread epsilon)))
      (let [mid (/ (+ bid* ask*) 2)]
        (assoc base :state :live :chance mid :price mid
               :market-order? true :rankable? true :muted? false))

      never-traded?
      (assoc base :state :not-traded)

      (and no-info? (false? two-sided?))
      (assoc base :state :no-market)

      no-info?
      (assoc base :state :no-quotes)

      (and both? (<= spread (+ range-spread epsilon)))
      (assoc base :state :range :low bid* :high ask* :market-order? true)

      :else
      (assoc base :state :no-market))))

(defn- context-two-sided
  "The context's hint about the book: a null `midPx` on a context that has
   arrived means one side or both are empty. Nil when unknown."
  [side]
  (when (and (some? (:prevDayRaw side)) (contains? side :midRaw))
    (some? (:midRaw side))))

(defn side-quote
  "Quote for one side from what the side map carries: the best bid/ask when a
   book feed has written `:bid`/`:ask` (either key present means the book is
   known), otherwise only the context (never-traded evidence and the
   two-sided hint). Never reads `:mark`."
  [side]
  (let [book-loaded? (or (contains? side :bid) (contains? side :ask))]
    (chance-quote {:bid (:bid side)
                   :ask (:ask side)
                   :book-loaded? book-loaded?
                   :two-sided? (context-two-sided side)
                   :never-traded? (never-traded? side)})))

(defn book-quote
  "Quote from a live book's best bid and ask (see `chance-quote`). `mark` is
   accepted for compatibility and ignored: it is not a trade. The optional
   `side` supplies never-traded evidence (previous-day price, volume)."
  ([best-bid best-ask mark] (book-quote best-bid best-ask mark nil))
  ([best-bid best-ask _mark side]
   (chance-quote {:bid best-bid
                  :ask best-ask
                  :book-loaded? true
                  :never-traded? (never-traded? side)})))

(defn mirror-quote
  "The other side of the same contract: prices flip to 1 − p, bid/ask swap and
   a range turns over. State and flags carry across."
  [{:keys [chance price bid ask low high] :as quote}]
  (cond-> (select-keys quote [:state :spread :never-traded? :market-order? :rankable? :muted?])
    (number? chance) (assoc :chance (- 1 chance))
    (number? price) (assoc :price (- 1 price))
    (number? ask) (assoc :bid (- 1 ask))
    (number? bid) (assoc :ask (- 1 bid))
    (number? high) (assoc :low (- 1 high))
    (number? low) (assoc :high (- 1 low))))

(defn- pct-text
  [chance]
  (let [pct (js/Math.round (* 100 chance))]
    (cond
      (< pct 1) "<1"
      (> pct 99) ">99"
      :else (str pct))))

(defn chance-text
  "\"82%\", clamped to \"<1%\" and \">99%\"; nil chance is an em dash."
  [chance]
  (if-let [chance* (num chance)]
    (str (pct-text chance*) "%")
    "—"))

(defn range-text
  "A bid–ask range as chances: \"30–40%\"."
  [low high]
  (let [low* (num low)
        high* (num high)]
    (if (and (number? low*) (number? high*))
      (str (pct-text low*) "–" (pct-text high*) "%")
      "—")))

(defn quote-text
  "What a surface shows for a quote: the chance, the muted range, or why there
   is none. Anything else (no quotes yet) is a dash."
  [{:keys [state chance low high]}]
  (case state
    :live (chance-text chance)
    :range (range-text low high)
    :not-traded "Not traded"
    :no-market "No market"
    "—"))

(declare cents-text)

(defn standing-quotes-text
  "The quotes standing behind a Not traded / No market / range quote:
   \"bid 1.3¢ · ask 98.7¢\". Nil when neither side stands."
  [{:keys [bid ask]}]
  (let [parts (cond-> []
                (number? bid) (conj (str "bid " (cents-text bid)))
                (number? ask) (conj (str "ask " (cents-text ask))))]
    (when (seq parts)
      (str/join " · " parts))))

(def stale-sum-threshold
  "Answers that exclude each other adding up past this read as stale prices."
  1.05)

(defn exclusive-sum
  "Sum check for answers that exclude each other (pick-one, field). Only
   midpoint chances (:live) count. {:sum :counted :over? :text}; `:text` is
   \"Adds up to 109%\" when the sum is past 105%, else nil."
  [quotes]
  (let [chances (keep (fn [quote] (when (= :live (:state quote)) (:chance quote))) quotes)
        sum (reduce + 0 chances)
        over? (> sum (+ stale-sum-threshold epsilon))]
    {:sum sum
     :counted (count chances)
     :over? over?
     :text (when over? (str "Adds up to " (js/Math.round (* 100 sum)) "%"))}))

(defn compact-shares-text
  "Shares for volume columns: 943K shares, 1.2M shares, 950 shares."
  [value]
  (when-let [value* (num value)]
    (str (cond
           (>= value* 1e9) (str (.toFixed (/ value* 1e9) 1) "B")
           (>= value* 1e6) (str (.toFixed (/ value* 1e6) 1) "M")
           (>= value* 1e4) (str (js/Math.round (/ value* 1e3)) "K")
           (>= value* 1e3) (str (.toFixed (/ value* 1e3) 1) "K")
           :else (str (js/Math.round value*)))
         " shares")))

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
