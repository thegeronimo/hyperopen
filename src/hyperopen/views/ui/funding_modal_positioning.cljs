(ns hyperopen.views.ui.funding-modal-positioning
  (:require [hyperopen.views.ui.anchored-popover :as anchored-popover]))

(def deposit-action-data-role
  "funding-action-deposit")

(def transfer-action-data-role
  "funding-action-transfer")

(def withdraw-action-data-role
  "funding-action-withdraw")

(def trade-order-entry-panel-parity-id
  "trade-order-entry-panel")

(def ^:private preferred-panel-width-px
  448)

(def ^:private estimated-panel-height-px
  560)

;; The Transfer form (places, asset list, amount, blocked card, summary) runs
;; taller than the other funding forms. Placing it as if it were 700px tall
;; keeps an anchored popover opened from a low row high enough that its fix
;; and submit buttons stay in view at 1280x800; the panel scrolls inside past
;; that (see the funding modal shell's height cap).
(def ^:private estimated-panel-height-px-by-mode
  {:transfer 700})

(def ^:private popover-divider-gap-px
  10)

(def ^:private trade-order-entry-panel-max-width-px
  320)

(def ^:private fallback-viewport-width
  1280)

(def ^:private fallback-viewport-height
  800)

(def ^:private mobile-sheet-breakpoint-px
  640)

(def ^:private mobile-sheet-top-offset-px
  20)

(def ^:private mobile-sheet-modes
  #{:send :deposit :transfer :withdraw})

(def ^:private fallback-anchor-data-role-by-mode
  {:deposit deposit-action-data-role
   :transfer transfer-action-data-role
   :withdraw withdraw-action-data-role})

(defn- data-role-selector
  [data-role]
  (when (and (string? data-role) (seq data-role))
    (str "[data-role='" data-role "']")))

(defn- parity-id-selector
  [parity-id]
  (str "[data-parity-id='" parity-id "']"))

(def ^:private trade-order-entry-panel-selector
  (parity-id-selector trade-order-entry-panel-parity-id))

(defn- fallback-anchor-selector
  [mode]
  (some-> (get fallback-anchor-data-role-by-mode mode)
          data-role-selector))

(defn- opener-anchor-selector
  [modal]
  (data-role-selector (:opener-data-role modal)))

(defn- modal-viewport-width
  [anchor]
  (max 320
       (or (when (number? (:viewport-width anchor))
             (:viewport-width anchor))
           (some-> js/globalThis .-innerWidth)
           (when (number? (:right anchor))
             (+ (:right anchor) 16))
           fallback-viewport-width)))

(defn- modal-viewport-height
  [anchor]
  (max 320
       (or (when (number? (:viewport-height anchor))
             (:viewport-height anchor))
           (some-> js/globalThis .-innerHeight)
           fallback-viewport-height)))

(defn- mobile-sheet-layout?
  [mode anchor]
  (and (contains? mobile-sheet-modes mode)
       (<= (modal-viewport-width anchor)
           mobile-sheet-breakpoint-px)))

(def ^:private mobile-sheet-padding-bottom
  "max(env(safe-area-inset-bottom), 1rem)")

(defn- mobile-sheet-style
  "The sheet's height cap and bottom padding. The padding is also published
   as `--funding-panel-pad-bottom`, so a sticky footer inside can reach over
   exactly that much (it is more than 1rem on phones with a home
   indicator)."
  [anchor]
  (let [max-height (max 320
                        (- (modal-viewport-height anchor)
                           mobile-sheet-top-offset-px))]
    {:max-height (str max-height "px")
     :padding-bottom mobile-sheet-padding-bottom
     :--funding-panel-pad-bottom mobile-sheet-padding-bottom}))

(defn- element-anchor-bounds
  [selector]
  (when (seq selector)
    (let [document* (some-> js/globalThis .-document)
          target (some-> document* (.querySelector selector))]
      (when (and target (fn? (.-getBoundingClientRect target)))
        (let [rect (.getBoundingClientRect target)]
          {:left (.-left rect)
           :right (.-right rect)
           :top (.-top rect)
           :bottom (.-bottom rect)
           :width (.-width rect)
           :height (.-height rect)
           :viewport-width (some-> js/globalThis .-innerWidth)
           :viewport-height (some-> js/globalThis .-innerHeight)})))))

(defn- trade-order-entry-divider-left
  []
  (let [panel-bounds (element-anchor-bounds trade-order-entry-panel-selector)]
    (when (and (number? (:left panel-bounds))
               (number? (:width panel-bounds))
               (<= (:width panel-bounds) trade-order-entry-panel-max-width-px))
      (:left panel-bounds))))

(defn- align-anchor-to-trade-order-entry-divider
  [anchor]
  (if-let [divider-left (and (anchored-popover/complete-anchor? anchor)
                             (trade-order-entry-divider-left))]
    (let [divider-anchor (+ divider-left popover-divider-gap-px)]
      (assoc anchor
             :left divider-anchor
             :right divider-anchor))
    anchor))

(defn resolve-modal-layout
  [modal]
  (let [stored-anchor (if (map? (:anchor modal)) (:anchor modal) {})
        fallback-anchor (when-not (anchored-popover/complete-anchor? stored-anchor)
                          (or (element-anchor-bounds
                               (opener-anchor-selector modal))
                              (element-anchor-bounds
                               (fallback-anchor-selector (:mode modal)))))
        anchor (-> (or fallback-anchor stored-anchor)
                   align-anchor-to-trade-order-entry-divider)
        mobile-sheet? (mobile-sheet-layout? (:mode modal) anchor)
        anchored-popover? (and (not mobile-sheet?)
                               (anchored-popover/complete-anchor? anchor))]
    {:anchor anchor
     :mobile-sheet? mobile-sheet?
     :anchored-popover? anchored-popover?
     :popover-style (when anchored-popover?
                      (anchored-popover/anchored-popover-layout-style
                       {:anchor anchor
                        :preferred-width-px preferred-panel-width-px
                        :estimated-height-px (get estimated-panel-height-px-by-mode
                                                  (:mode modal)
                                                  estimated-panel-height-px)}))
     :sheet-style (when mobile-sheet?
                    (mobile-sheet-style anchor))}))
