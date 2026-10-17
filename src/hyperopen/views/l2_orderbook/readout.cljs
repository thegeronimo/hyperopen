(ns hyperopen.views.l2-orderbook.readout
  "The depth readout card shown beside a hovered or focused order book level: what a
   market order swept through that level would fill."
  (:require [hyperopen.domain.trading.book-sweep :as book-sweep]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.websocket.orderbook-policy :as orderbook-policy]))

(def card-id "orderbook-depth-readout")

(defn readout
  "Sweep figures for a market order through `row`'s price, or nil without a row."
  [orderbook-data row]
  (when-let [side (#{:ask :bid} (:side row))]
    (book-sweep/sweep-to-price orderbook-data (if (= side :ask) :buy :sell) (:px row))))

(defn- figure [label value value-classes]
  (list
   [:span {:class ["text-trading-text-secondary"]} label]
   [:span {:class (into ["font-mono" "text-right" "text-ho-text"] value-classes)} value]))

(defn- format-bps [slippage-pct]
  (str (fmt/format-intl-number (* 100 slippage-pct) {:minimumFractionDigits 2
                                                     :maximumFractionDigits 2})
       " bps"))

(defn readout-card
  "Positioned `fixed` with no offsets, so it sits at its static position beside the row
   while escaping the panel's `overflow: hidden`; the transform moves it to the row's
   left. It never takes pointer events, so it cannot steal the hover.

   Styled as the raised surface (lighter than the book) so it reads clearly over the
   chart, with an arrow pointing at the row it describes."
  [{:keys [levels size notional avg-price slippage-pct]} side price-label price-decimals base-symbol]
  [:div {:id card-id
         :role "tooltip"
         :class ["pointer-events-none" "fixed" "z-50" "w-[268px]" "-translate-x-[calc(100%_+_10px)]"
                 "box-border" "flex" "flex-col" "gap-2.5" "rounded-md" "border" "border-ho-border"
                 "bg-ho-surface-raised" "px-3.5" "py-3" "text-xs" "font-normal"
                 "shadow-[0_8px_24px_rgb(var(--ho-bg-deep)/0.7)]"]
         :data-role "orderbook-depth-readout"}
   [:div {:class ["text-trading-text-secondary"]}
    (if (= side :ask) "Market buy up to " "Market sell down to ")
    [:span {:class ["font-mono" "text-ho-text"]} price-label]]
   [:div {:class ["grid" "grid-cols-[minmax(0,1fr)_auto]" "gap-y-1.5"]}
    (figure "Levels swept" (str levels) [])
    (figure "Total size"
            (str (orderbook-policy/format-total size :decimals 8) " " base-symbol)
            [])
    (figure "Notional" (str "$" (orderbook-policy/format-total notional :decimals 0)) [])
    (figure "Avg fill price" (orderbook-policy/format-level-price avg-price (inc price-decimals)) [])
    (when (number? slippage-pct)
      (figure "Slippage vs mid" (format-bps slippage-pct) ["text-ho-warn"]))]
   [:div {:class ["border-t" "border-ho-border" "pt-2" "text-trading-text-secondary"]}
    [:span {:class ["text-ho-text"]} "Click"]
    " to set limit price"]
   ;; Arrow pointing at the row.
   [:div {:class ["absolute" "-right-[6px]" "top-1/2" "h-2.5" "w-2.5" "-translate-y-1/2" "rotate-45"
                  "border-r" "border-t" "border-ho-border" "bg-ho-surface-raised"]
          :aria-hidden "true"}]])
