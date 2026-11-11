(ns hyperopen.views.l2-orderbook.outcome-cents
  "Outcome books read their prices in cents (a share pays $1, so 0.225 is
   22.5¢). Only labels change: every level keeps its dollar `:px`, which is
   what a click sends to the order form."
  (:require [hyperopen.outcomes.domain.price-unit :as price-unit]))

(defn context
  "Cents settings for the book of `coin`, or nil when it is not an outcome
   market. Digits follow the active price grouping: 0.00001 -> 22.500."
  [coin market selected-option]
  (when (price-unit/outcome-market? coin market)
    {:decimals (price-unit/cents-decimals (price-unit/step-decimals (:step selected-option)))}))

(defn price-text
  [ctx px]
  (price-unit/book-cents-text px (:decimals ctx)))

(defn- relabel-row
  [ctx row]
  (if (and row (some? (:px row)))
    (-> row
        (assoc-in [:display :price] (price-text ctx (:px row)))
        (assoc-in [:display :price-unit] "¢"))
    row))

(defn relabel-rows
  [ctx rows]
  (if (and ctx rows) (mapv #(relabel-row ctx %) rows) rows))

(defn relabel-pairs
  [ctx pairs]
  (if (and ctx pairs)
    (mapv (fn [pair]
            (cond-> pair
              (:bid pair) (update :bid #(relabel-row ctx %))
              (:ask pair) (update :ask #(relabel-row ctx %))))
          pairs)
    pairs))

(defn relabel-spread
  [ctx spread]
  (if (and ctx spread (some? (:absolute spread)))
    (assoc spread :absolute-label (str (price-text ctx (:absolute spread)) "¢"))
    spread))

(defn relabel-option
  [option]
  (if-let [label (price-unit/step-cents-label (:step option))]
    (assoc option :label label)
    option))

(defn relabel-options
  [ctx options]
  (if ctx (mapv relabel-option options) options))

(defn avg-price-text
  "The readout's average fill in cents, one digit finer than the book."
  [ctx price]
  (some-> (price-unit/book-cents-text price (inc (:decimals ctx))) (str "¢")))
