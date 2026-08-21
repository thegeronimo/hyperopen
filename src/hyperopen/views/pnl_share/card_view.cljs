(ns hyperopen.views.pnl-share.card-view
  "Dispatches a card data map to its template.

   Adding one of the design's remaining directions is a namespace and one entry
   here, not a change to anything that already works."
  (:require [hyperopen.views.pnl-share.card-data :as card-data]
            [hyperopen.views.pnl-share.templates.neon-arrow :as neon-arrow]
            [hyperopen.views.pnl-share.templates.number-hero :as number-hero]))

(def card-width 1080)
(def card-height 608)

(def ^:private renderer-by-template
  {:neon-arrow neon-arrow/render
   :number-hero number-hero/render})

(defn card-svg
  "Returns the card as a standalone [:svg ...] tree, 1080x608."
  [card]
  (let [template (card-data/normalize-template (:template card))
        render (get renderer-by-template template neon-arrow/render)]
    (render (assoc card :template template))))
