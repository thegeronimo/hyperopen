(ns hyperopen.views.trade.book-impact
  "The order form's market order, swept against the visible book: the card under the
   order form and the levels it marks on the order book."
  (:require [hyperopen.domain.trading.book-sweep :as book-sweep]
            [hyperopen.domain.trading.core :as core]
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

(def ^:private memoized-impact
  (memoize-last
   (fn [form book]
     (when (= :market (some-> (:type form) keyword))
       (when-let [side (#{:buy :sell} (some-> (:side form) keyword))]
         (some-> (book-sweep/market-impact book side (core/parse-num (:size form)))
                 (assoc :side side)))))))

(defn market-impact
  "The sweep for the order form's market order on the active asset's book, or nil when
   the form is not a sized market order. Uses the same book, size and side as the
   footer's slippage estimate. Reads the stored form, which carries the synced base
   size, so the memo key stays cheap."
  [state]
  (memoized-impact (:order-form state)
                   (get-in state [:orderbooks (:active-asset state)])))

(defn- figure [label value]
  [:div {:class ["flex" "items-baseline" "justify-between" "gap-3"]}
   [:span {:class ["text-trading-text-secondary"]} label]
   [:span {:class ["num" "text-trading-text"]} value]])

(defn book-impact-card
  [{:keys [side levels size requested-size worst-px avg-price last-level-fraction
           depth-used-pct complete?]}
   book
   base-symbol]
  (let [decimals (orderbook-policy/book-price-decimals (concat (:bids book) (:asks book)))
        side-label (if (= side :buy) "asks" "bids")
        partial? (< last-level-fraction 0.999)]
    [:div {:class ["rounded-md" "border" "border-base-300" "bg-base-200/60" "px-3" "py-2.5" "space-y-1.5" "text-xs"]
           :data-role "order-form-book-impact"}
     [:div {:class ["flex" "items-center" "gap-1.5" "text-trading-text"]}
      [:span {:class ["h-2.5" "w-[2px]" "bg-ho-accent"] :aria-hidden "true"}]
      "Book impact"
      [:span {:class ["text-trading-text-secondary"]} "· marked on the order book"]]
     (figure "Levels swept"
             (str levels
                  (when partial?
                    (str " (last " (js/Math.round (* 100 last-level-fraction)) "% filled)"))))
     (figure "Worst price" (orderbook-policy/format-level-price worst-px decimals))
     (figure "Avg fill price" (orderbook-policy/format-level-price avg-price (inc decimals)))
     (figure (str "Visible " side-label " used")
             (str (js/Math.round depth-used-pct) "%"))
     (when-not complete?
       [:div {:class ["text-ho-warn"] :role "status"}
        (str "The visible book fills only "
             (orderbook-policy/format-total size :decimals 8) " of "
             (orderbook-policy/format-total requested-size :decimals 8) " " base-symbol
             ". The rest would fill deeper, at worse prices.")])]))
