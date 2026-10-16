(ns hyperopen.orderbook.actions
  (:require [hyperopen.orderbook.settings :as orderbook-settings]))

(defn toggle-orderbook-size-unit-dropdown
  [state]
  (let [visible? (get-in state [:orderbook-ui :size-unit-dropdown-visible?] false)]
    [[:effects/save [:orderbook-ui :size-unit-dropdown-visible?] (not visible?)]]))

(def select-orderbook-size-unit
  orderbook-settings/select-orderbook-size-unit)

(defn toggle-orderbook-price-aggregation-dropdown
  [state]
  (let [visible? (get-in state [:orderbook-ui :price-aggregation-dropdown-visible?] false)]
    [[:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] (not visible?)]]))

(def select-orderbook-price-aggregation
  orderbook-settings/select-orderbook-price-aggregation)

(def select-orderbook-tab
  orderbook-settings/select-orderbook-tab)

;; Hovered (or keyboard-focused) row for the depth readout card: its side and its
;; depth from the spread (0 = best level). Keyed by position rather than price so that
;; when the book ticks under a still cursor, the card stays on the row under the cursor
;; and its figures update, instead of chasing the old price or vanishing. Transient,
;; never persisted, and a no-op when nothing changes.
(defn set-orderbook-hover-level
  [state side depth]
  (let [level {:side (keyword side) :depth depth}]
    (if (= level (get-in state [:orderbook-ui :hover-level]))
      []
      [[:effects/save [:orderbook-ui :hover-level] level]])))

(defn clear-orderbook-hover-level
  [state]
  (if (get-in state [:orderbook-ui :hover-level])
    [[:effects/save [:orderbook-ui :hover-level] nil]]
    []))
