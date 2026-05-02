(ns hyperopen.views.l2-orderbook.styles)

(def orderbook-columns-class "grid-cols-[1fr_2fr_2fr]")
(def mobile-split-columns-class "grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)_minmax(0,0.8fr)_minmax(0,1.2fr)]")
(def header-neutral-text-class "text-[rgb(148,158,156)]")
(def body-neutral-text-class "text-[rgb(210,218,215)]")
(def ask-depth-bar-class "bg-[rgba(237,112,136,0.15)]")
(def bid-depth-bar-class "bg-[rgba(31,166,125,0.15)]")
(def ask-price-text-class "text-[rgb(237,112,136)]")
(def bid-price-text-class "text-[rgb(31,166,125)]")
(def orderbook-tab-indicator-class "bg-[rgb(80,210,193)]")
(def desktop-breakpoint-px 1024)
(def depth-bar-transition-classes
  ["transition-all"
   "duration-300"
   "ease-[cubic-bezier(0.68,-0.6,0.32,1.6)]"])
