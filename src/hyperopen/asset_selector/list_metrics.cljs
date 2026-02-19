(ns hyperopen.asset-selector.list-metrics)

;; Canonical selector list geometry/settings used by both view virtualization
;; and action-side render-limit growth math. Keep these values in one place to
;; prevent drift-induced scroll regressions.
(def default-render-limit
  120)

(def row-height-px
  24)

(def viewport-height-px
  256)
