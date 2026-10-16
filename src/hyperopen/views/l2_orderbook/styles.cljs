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
;; Transform-only and non-overshooting. Bars are full-width fills scaled along x, so
;; the animation runs on the compositor instead of relaying out the row each frame.
;; The previous 300ms ease-[cubic-bezier(0.68,-0.6,0.32,1.6)] curve was an
;; easeInOutBack whose control points sit below 0 and above 1, so every bar
;; undershot and then overshot its target -- a wobble at the incremental `l2` feed's
;; ~550ms cadence. 200ms ease-out keeps the bars visibly gliding for well over a third
;; of each update interval, which reads as live movement rather than a snap.
(def depth-bar-transition-classes
  ["transition-transform"
   "duration-200"
   "ease-out"])

;; Two identical keyframe sets per side (styled in trading-controls.css). A level that
;; changes in consecutive updates switches between them, and switching the animation
;; name is what restarts a CSS animation without an extra unflashed render in between.
(defn level-flash-class
  "The flash class for a level that changed in the update numbered `flash-version`,
   or nil when the level did not change."
  [side flash-version]
  (when (number? flash-version)
    ;; Spelled out in full: the stylesheet layer only keeps class names it finds
    ;; literally in source.
    (case [(= side :ask) (even? flash-version)]
      [true true] "ob-flash-ask-a"
      [true false] "ob-flash-ask-b"
      [false true] "ob-flash-bid-a"
      [false false] "ob-flash-bid-b")))
