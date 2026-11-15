(ns hyperopen.views.l2-orderbook.styles)

(def orderbook-columns-class "grid-cols-[1fr_2fr_2fr]")
(def mobile-split-columns-class "grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)_minmax(0,0.8fr)_minmax(0,1.2fr)]")
(def header-neutral-text-class "text-trading-text-secondary")
(def body-neutral-text-class "ob-body-text")
(def ask-depth-bar-class "ob-ask-bar")
(def bid-depth-bar-class "ob-bid-bar")
(def ask-price-text-class "text-ho-sell-hi")
(def bid-price-text-class "ob-bid-px")
(def orderbook-tab-indicator-class "bg-ho-accent")
(def depth-bar-transition-classes
  ["transition-transform"
   "duration-200"
   "ease-out"])

(defn level-flash-class
  "The flash class for a level that changed in the update numbered `flash-version`,
   or nil when the level did not change."
  [side flash-version]
  (when (number? flash-version)
    (case [(= side :ask) (even? flash-version)]
      [true true] "ob-flash-ask-a"
      [true false] "ob-flash-ask-b"
      [false true] "ob-flash-bid-a"
      [false false] "ob-flash-bid-b")))
