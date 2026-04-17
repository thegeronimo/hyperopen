(ns hyperopen.views.account-info.tabs.positions.layout
  (:require [hyperopen.views.account-info.shared :as shared]))

(def ^:private mobile-position-overlay-breakpoint-px 640)
(def ^:private mobile-position-card-layout-breakpoint-px 1024)
(def ^:private mobile-position-overlay-trigger-size-px 24)
(def ^:private mobile-position-overlay-horizontal-padding-px 16)
(def ^:private mobile-position-overlay-bottom-padding-px 24)
(def ^:private mobile-position-overlay-fallback-width-px 430)
(def ^:private mobile-position-overlay-fallback-height-px 932)

(def ^:private positions-read-only-grid-template-class
  "grid-cols-[minmax(166px,1.95fr)_minmax(136px,1.28fr)_minmax(94px,0.9fr)_minmax(94px,0.9fr)_minmax(94px,0.9fr)_minmax(114px,1.06fr)_minmax(88px,0.82fr)_minmax(150px,1.16fr)_minmax(86px,0.82fr)_minmax(136px,1fr)]")

(def ^:private positions-read-only-grid-min-width-class
  "min-w-[1245px]")

(defn positions-grid-template-class
  [read-only?]
  (if read-only?
    positions-read-only-grid-template-class
    shared/positions-grid-template-class))

(defn positions-grid-min-width-class
  [read-only?]
  (if read-only?
    positions-read-only-grid-min-width-class
    shared/positions-grid-min-width-class))

(defn- current-viewport-number
  [value fallback]
  (if (and (number? value)
           (pos? value))
    value
    fallback))

(defn current-viewport-width []
  (current-viewport-number (some-> js/globalThis .-innerWidth)
                           mobile-position-overlay-fallback-width-px))

(defn current-viewport-height []
  (current-viewport-number (some-> js/globalThis .-innerHeight)
                           mobile-position-overlay-fallback-height-px))

(defn active-card-layout?
  []
  (let [width (some-> js/globalThis .-innerWidth)]
    (and (number? width)
         (pos? width)
         (< width mobile-position-card-layout-breakpoint-px))))

(defn active-desktop-table-layout?
  []
  (not (active-card-layout?)))

(defn phone-overlay-trigger?
  []
  (<= (current-viewport-width)
      mobile-position-overlay-breakpoint-px))

(defn mobile-position-overlay-anchor
  []
  (let [viewport-width (current-viewport-width)
        viewport-height (current-viewport-height)
        trigger-size mobile-position-overlay-trigger-size-px
        right (max trigger-size
                   (- viewport-width mobile-position-overlay-horizontal-padding-px))
        left (max 0 (- right trigger-size))
        bottom (max trigger-size
                    (- viewport-height mobile-position-overlay-bottom-padding-px))
        top (max 0 (- bottom trigger-size))]
    {:left left
     :right right
     :top top
     :bottom bottom
     :width trigger-size
     :height trigger-size
     :viewport-width viewport-width
     :viewport-height viewport-height}))
