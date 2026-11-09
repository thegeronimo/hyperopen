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
   no book has arrived yet, so callers can fall back to the context quote."
  [book mark]
  (when (or (seq (:bids book)) (seq (:asks book)))
    (quotes/book-quote (best-bid book) (best-ask book) mark)))
