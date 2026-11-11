(ns hyperopen.outcomes.domain.book
  "Read an outcome side's order book. Levels arrive as {:px \"0.81902\" :sz \"758\"}
   maps; outcome tickets work in [price shares] pairs, best price first."
  (:require [hyperopen.outcomes.domain.quotes :as quotes]))

(defn- parse
  [value]
  (let [number (cond
                 (number? value) value
                 (string? value) (js/parseFloat value)
                 :else js/NaN)]
    (when-not (js/isNaN number)
      number)))

(defn- level
  [{:keys [px sz]}]
  (let [price (parse px)
        size (parse sz)]
    (when (and (number? price) (pos? price)
               (number? size) (pos? size))
      [price size])))

(defn asks
  "Ask levels, cheapest first: what a buy pays."
  [book]
  (->> (:asks book) (keep level) (sort-by first <) vec))

(defn bids
  "Bid levels, highest first: what a sale receives."
  [book]
  (->> (:bids book) (keep level) (sort-by first >) vec))

(defn best-bid [book] (ffirst (bids book)))

(defn best-ask [book] (ffirst (asks book)))

(defn two-sided?
  [book]
  (and (some? (best-bid book)) (some? (best-ask book))))

(defn book-quote
  "Quote for the side this book belongs to (see `quotes/book-quote`). Nil when
   no book has arrived yet, so callers can fall back to the context quote.
   `side` (optional) supplies never-traded evidence: prevDayRaw and volume."
  ([book mark] (book-quote book mark nil))
  ([book mark side]
   (when (or (seq (:bids book)) (seq (:asks book)))
     (quotes/book-quote (best-bid book) (best-ask book) mark side))))

(defn top-of-book-known?
  "True when the top-of-book feed (`bbo`) has written the side's best bid and
   ask onto the side map (either key present means the book is known)."
  [side]
  (and (map? side) (or (contains? side :bid) (contains? side :ask))))

(defn quote-for-side
  "The one chance rule for `side`. Sources, first known wins: the side's own
   top of book written by the feed, its sibling's mirrored, then the side's
   full live book in `books` (the active side's l2Book), the sibling's book
   mirrored, else the side's context. Every surface reads this one order, so
   the list, header, ticket rows and side buttons agree. `books` maps coin ->
   order book and may be nil."
  [side sibling books]
  (cond
    (top-of-book-known? side)
    (quotes/side-quote side)

    (top-of-book-known? sibling)
    (quotes/mirror-quote (quotes/side-quote sibling))

    :else
    (or (book-quote (get books (:coin side)) nil side)
        (when sibling
          (some-> (book-quote (get books (:coin sibling)) nil sibling)
                  quotes/mirror-quote))
        (quotes/side-quote side))))

(defn contract-quotes
  "[side-0 quote, side-1 quote] of one contract. The selected side (`index`)
   is quoted from what is known about it; the other side is its mirror, so
   both always agree. Every surface — list, header, ticket — calls this, so
   the same inputs give the same answer everywhere."
  ([sides books] (contract-quotes sides 0 books))
  ([sides index books]
   (let [sides* (vec sides)
         index* (if (= 1 index) 1 0)
         active (get sides* index*)
         other (get sides* (- 1 index*))
         own (quote-for-side active other books)
         active-quote (if (and other (= :no-quotes (:state own)))
                        (let [other-own (quote-for-side other active books)]
                          (if (= :no-quotes (:state other-own))
                            own
                            (quotes/mirror-quote other-own)))
                        own)
         other-quote (quotes/mirror-quote active-quote)]
     (if (= 1 index*)
       [other-quote active-quote]
       [active-quote other-quote]))))
(defn market-sides
  "The contract's sides; a partial market (a cold-start cache entry) gets a
   Yes side built from its own price fields so the header never goes blank."
  [market]
  (let [sides (vec (:outcome-sides market))]
    (if (seq sides)
      sides
      [{:side-index 0 :side-name "Yes" :coin (:coin market) :mark (:mark market)
        :prevDayRaw (:prevDayRaw market)}
       {:side-index 1 :side-name "No"}])))

(defn selected-side-index
  [market]
  (or (:outcome-side-index market)
      (some (fn [side]
              (when (= (:coin market) (:coin side))
                (:side-index side)))
            (:outcome-sides market))
      0))

(defn market-side-quotes
  "[side-0 quote, side-1 quote] of the active contract, `orderbook` being the
   selected side's live book (may be nil). The one helper the header, the
   ticket and the submit policy share (`contract-quotes`)."
  [market orderbook]
  (let [sides (market-sides market)
        index (selected-side-index market)
        active-coin (:coin (get sides index))
        books (when (and active-coin orderbook) {active-coin orderbook})]
    (contract-quotes sides index books)))

(defn market-orders-off?
  "True when a market order may not go on the selected side: its book has
   arrived and is not two-sided, or the chance rule's quote forbids market
   orders (not traded, no market). A book still loading does not block."
  [market orderbook]
  (boolean
   (and (some? orderbook)
        (let [quote (get (market-side-quotes market orderbook) (selected-side-index market))]
          (not (and (two-sided? orderbook) (:market-order? quote)))))))
