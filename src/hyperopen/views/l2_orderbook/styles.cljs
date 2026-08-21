(ns hyperopen.views.l2-orderbook.styles)

(def orderbook-columns-class "grid-cols-[1fr_2fr_2fr]")
(def mobile-split-columns-class "grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)_minmax(0,0.8fr)_minmax(0,1.2fr)]")
;; ob-* classes are styled in src/styles/surfaces/trading-controls.css with the
;; legacy literal colors (no near token exists) and re-themed by
;; src/styles/surfaces/institutional.css.
(def header-neutral-text-class "text-trading-text-secondary")
(def body-neutral-text-class "ob-body-text")
(def ask-depth-bar-class "ob-ask-bar")
(def bid-depth-bar-class "ob-bid-bar")
(def ask-price-text-class "text-ho-sell-hi")
(def bid-price-text-class "ob-bid-px")
(def orderbook-tab-indicator-class "bg-ho-accent")
(def desktop-breakpoint-px 1024)
;; Width-only, short, and non-overshooting. The previous 300ms
;; ease-[cubic-bezier(0.68,-0.6,0.32,1.6)] curve is an easeInOutBack: its control
;; points sit below 0 and above 1, so every bar deliberately undershoots and then
;; overshoots its target. That was tolerable while the book refreshed once every
;; ~5.4s, but the incremental `l2` feed retargets these bars roughly every 550ms,
;; where a 300ms overshoot reads as a wobble on data that should be crisp.
;; `transition-all` also transitioned every animatable property rather than the one
;; that actually changes.
(def depth-bar-transition-classes
  ["transition-[width]"
   "duration-100"
   "ease-out"])
