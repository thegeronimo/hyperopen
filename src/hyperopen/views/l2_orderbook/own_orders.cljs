(ns hyperopen.views.l2-orderbook.own-orders
  "The user's own resting orders, placed onto the order book levels they rest in."
  (:require [hyperopen.views.account-info.projections :as account-projections]
            [hyperopen.websocket.orderbook-policy :as orderbook-policy]))

(defn- memoize-last
  [f]
  (let [cache (atom nil)]
    (fn [& args]
      (let [cached @cache]
        (if (and (map? cached)
                 (= args (:args cached)))
          (:result cached)
          (let [result (apply f args)]
            (reset! cache {:args args
                           :result result})
            result))))))

(defn- preferred-orders-value
  [state k]
  (if (contains? (or (:orders state) {}) k)
    (get-in state [:orders k])
    (get-in state [:webdata2 k])))

(def ^:private memoized-active-asset-open-orders
  (memoize-last account-projections/normalized-open-orders-for-active-asset))

(defn active-asset-open-orders
  "Normalized open orders on the active asset, from the same sources and with the same
   pending-cancel filtering as the Open Orders tab and the chart's order lines."
  [state]
  (memoized-active-asset-open-orders
   (preferred-orders-value state :open-orders)
   (preferred-orders-value state :open-orders-snapshot)
   (preferred-orders-value state :open-orders-snapshot-by-dex)
   (:active-asset state)
   (get-in state [:orders :pending-cancel-oids])))

(defn- order-book-side [order]
  (case (some-> (:side order) str)
    "B" :bids
    "A" :asks
    nil))

(defn- containing-level
  "The displayed level an order rests in, or nil when it is not on the displayed book.

   At full precision only the level at the order's exact price counts: if that level is
   momentarily absent, a neighbouring level must not claim the order. Aggregated books
   bucket bids down and asks up, so there a bid belongs to the highest bid level at or
   below it and an ask to the lowest ask level at or above it."
  [side order-px levels bucketed?]
  (let [prices (keep orderbook-policy/level-price levels)
        candidates (cond
                     (not bucketed?) (filter #(= % order-px) prices)
                     (= side :bids) (filter #(<= % order-px) prices)
                     :else (filter #(>= % order-px) prices))]
    (when (seq candidates)
      (let [target (if (= side :bids) (apply max candidates) (apply min candidates))]
        (some #(when (= target (orderbook-policy/level-price %)) (:px %)) levels)))))

(defn own-order-sizes
  "Total resting size per displayed level, `{:bids {px size} :asks {px size}}`, keyed by
   the level's raw price. Trigger orders are not on the book until they fire, so they
   are left out. `bucketed?` is true when the book is shown at a coarser precision."
  [orders bid-levels ask-levels bucketed?]
  (reduce (fn [acc order]
            (let [side (order-book-side order)
                  order-px (orderbook-policy/parse-number (:px order))
                  size (or (orderbook-policy/parse-number (:sz order)) 0)
                  level-px (when (and side order-px (not (:is-trigger order)))
                             (containing-level side
                                               order-px
                                               (if (= side :bids) bid-levels ask-levels)
                                               bucketed?))]
              (if level-px
                (update-in acc [side level-px] (fnil + 0) size)
                acc)))
          {:bids {} :asks {}}
          orders))
