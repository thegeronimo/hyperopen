(ns hyperopen.domain.trading.book-sweep
  "What a market order would take from one side of the order book.

   `market-impact` sweeps a requested size, as the order form's market slippage
   estimate does (`domain.trading.market`): the same levels best-first, the same
   top-of-book midpoint, so the two never disagree. `sweep-to-price` sweeps every level
   up to a price, for the depth readout on a hovered level."
  (:require [hyperopen.domain.trading.core :as core]))

(defn- normalize-level [level]
  (let [price (core/parse-num (:px level))
        size (core/parse-num (:sz level))]
    (when (and (number? price) (pos? price)
               (number? size) (pos? size))
      {:px (:px level) :price price :size size})))

(defn- side-levels
  "Levels a market order on `side` would take, best first: asks for a buy, bids for a sell."
  [book side]
  (case side
    :buy (->> (:asks book) (keep normalize-level) (sort-by :price <) vec)
    :sell (->> (:bids book) (keep normalize-level) (sort-by :price >) vec)
    nil))

(defn- best-price [levels better?]
  (reduce (fn [best {:keys [price]}]
            (if (or (nil? best) (better? price best)) price best))
          nil
          levels))

(defn midpoint [book]
  (let [best-bid (best-price (keep normalize-level (:bids book)) >)
        best-ask (best-price (keep normalize-level (:asks book)) <)]
    (when (and best-bid best-ask)
      (/ (+ best-bid best-ask) 2))))

(defn- summarize
  "Totals for a list of `{:px :price :take :size}` fills, best first."
  [fills mid]
  (when (seq fills)
    (let [size (reduce + (map :take fills))
          notional (reduce + (map #(* (:take %) (:price %)) fills))
          avg (/ notional size)
          worst (peek fills)]
      {:levels (count fills)
       :size size
       :notional notional
       :avg-price avg
       :worst-px (:px worst)
       :worst-price (:price worst)
       :last-level-fraction (/ (:take worst) (:size worst))
       :slippage-pct (when (and (number? mid) (pos? mid))
                       (* 100 (/ (js/Math.abs (- avg mid)) mid)))
       :fill-fraction-by-px (into {} (map (juxt :px #(/ (:take %) (:size %)))) fills)})))

(def ^:private size-epsilon 1e-12)

(defn market-impact
  "The fill of a market order for `size` (base units) on `side` (:buy or :sell), or nil
   when the size is not positive or that side of the book is empty.

   `:complete?` is false when the visible book cannot fill the whole size; the totals
   then describe the part it can. `:depth-used-pct` is the share of the side's visible
   size the order takes."
  [book side size]
  (let [levels (side-levels book side)]
    (when (and (number? size) (pos? size) (seq levels))
      (let [fills (loop [remaining size
                         fills []
                         levels levels]
                    (if (or (<= remaining size-epsilon) (empty? levels))
                      fills
                      (let [level (first levels)
                            taken (min remaining (:size level))]
                        (recur (- remaining taken)
                               (conj fills (assoc level :take taken))
                               (rest levels)))))
            depth (reduce + (map :size levels))
            summary (summarize fills (midpoint book))]
        (assoc summary
               :requested-size size
               :complete? (>= (+ (:size summary) size-epsilon) size)
               :depth-used-pct (* 100 (/ (min size depth) depth)))))))

(defn sweep-to-price
  "Every level on `side` from the best price through `px` inclusive, taken in full: what
   a market order limited at `px` would fill against the visible book. Nil when no level
   is at or better than `px`."
  [book side px]
  (let [limit (core/parse-num px)
        within? (case side
                  :buy #(<= (:price %) limit)
                  :sell #(>= (:price %) limit)
                  (constantly false))]
    (when (number? limit)
      (summarize (->> (side-levels book side)
                      (take-while within?)
                      (mapv #(assoc % :take (:size %))))
                 (midpoint book)))))
