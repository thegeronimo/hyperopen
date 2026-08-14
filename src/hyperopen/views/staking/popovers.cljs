(ns hyperopen.views.staking.popovers
  "Geometry, anchoring and dialog chrome for the staking action popovers. The
  panel bodies live in hyperopen.views.staking.popover-content."
  (:require [hyperopen.views.staking.popover-content :as popover-content]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]))
(def ^:private popover-margin-px
  12)
(def ^:private popover-gap-px
  10)
(def ^:private minimum-popover-anchor-height-px
  36)
(def ^:private popover-fallback-viewport-width
  1280)
(def ^:private popover-fallback-viewport-height
  800)
(def ^:private action-popover-trigger-data-role-by-kind
  {:transfer "staking-action-transfer-button"
   :unstake "staking-action-unstake-button"
   :stake "staking-action-stake-button"})
(def ^:private action-popover-focus-on-render (dialog-focus/dialog-focus-on-render))
(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))
(defn- anchor-number
  [anchor k default]
  (let [value (get anchor k)]
    (if (number? value)
      value
      default)))
(defn- query-element-anchor
  [data-role]
  (when (and (string? data-role)
             (some? js/document))
    (let [selector (str "[data-role=\"" data-role "\"]")
          element (.querySelector js/document selector)]
      (when (and element
                 (fn? (.-getBoundingClientRect element)))
        (let [rect (.getBoundingClientRect element)]
          {:left (.-left rect)
           :right (.-right rect)
           :top (.-top rect)
           :bottom (.-bottom rect)
           :width (.-width rect)
           :height (.-height rect)
           :viewport-width (some-> js/globalThis .-innerWidth)
           :viewport-height (some-> js/globalThis .-innerHeight)})))))
(defn- action-popover-anchor
  [kind stored-anchor]
  (let [data-role (get action-popover-trigger-data-role-by-kind kind)]
    (or (query-element-anchor data-role)
        stored-anchor)))
(defn- action-popover-layout-style
  [anchor kind]
  (let [anchor* (if (map? anchor) anchor {})
        ;; Measured with the fullest content each panel can render: the transfer
        ;; panel carries the queue block, and the unstake panel carries both the
        ;; two-step note and a lock notice. Under-estimating here lets the panel
        ;; run past the bottom of a short viewport.
        ;; Measured in a real browser at 1440x900 with the fullest content each
        ;; panel can carry: transfer in the staking->spot direction with a queued
        ;; row = 427px; unstake with both the delegation-lock notice and a form
        ;; error = 460px; stake = 324px. The values below add ~30px of headroom.
        ;; Underestimating pushes the CTA off the bottom of a short viewport,
        ;; where it becomes unreachable, so re-measure if the panels grow.
        estimated-height-px (if (= kind :transfer) 460 490)
        viewport-width (max 320
                            (anchor-number anchor* :viewport-width popover-fallback-viewport-width)
                            (+ (anchor-number anchor* :right 0) popover-margin-px))
        viewport-height (max 320
                             (anchor-number anchor* :viewport-height popover-fallback-viewport-height))
        available-width (max 0 (- viewport-width (* 2 popover-margin-px)))
        panel-width (min 560 available-width)
        anchor-right (anchor-number anchor*
                                    :right
                                    (- viewport-width popover-margin-px))
        anchor-top (anchor-number anchor* :top popover-margin-px)
        anchor-height (max minimum-popover-anchor-height-px
                           (anchor-number anchor* :height 0))
        anchor-bottom* (anchor-number anchor*
                                      :bottom
                                      (+ anchor-top anchor-height))
        anchor-bottom (if (>= (- anchor-bottom* anchor-top) 8)
                        anchor-bottom*
                        (+ anchor-top anchor-height))
        preferred-left (- anchor-right panel-width)
        left (clamp preferred-left
                    popover-margin-px
                    (- viewport-width panel-width popover-margin-px))
        preferred-top (+ anchor-bottom popover-gap-px)
        max-top (- viewport-height estimated-height-px popover-margin-px)
        top (if (> max-top popover-margin-px)
              (clamp preferred-top popover-margin-px max-top)
              popover-margin-px)]
    {:left (str left "px")
     :top (str top "px")
     :width (str panel-width "px")}))
(defn- popover-close-button []
  [:button {:type "button"
            :class ["absolute"
                    "right-5"
                    "top-4"
                    "inline-flex"
                    "h-8"
                    "w-8"
                    "items-center"
                    "justify-center"
                    "rounded-lg"
                    "text-ho-text"
                    "transition-colors"
                    "hover:bg-[#16313b]"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"]
            :aria-label "Close staking action popover"
            :on {:click [[:actions/close-staking-action-popover]]}}
   "x"])
(defn action-popover-layer
  [{:keys [action-popover
           form
           submitting
           balances
           error
           selected-validator
           selected-validator-lock
           unstaking
           projected-transfer-arrival-ms
           validator-search-query
           validator-dropdown-open?
           validators]}]
  (when (:open? action-popover)
    (let [kind (:kind action-popover)
          anchor (action-popover-anchor kind (:anchor action-popover))
          panel-style (action-popover-layout-style anchor kind)
          title (case kind
                  :transfer "Transfer HYPE"
                  :unstake "Unstake"
                  "Stake")]
      [:div {:class ["fixed" "inset-0" "z-[230]" "pointer-events-none"]
             :data-role "staking-action-popover-layer"}
       [:button {:type "button"
                 :class ["absolute" "inset-0" "pointer-events-auto" "bg-transparent"]
                 :aria-label "Close staking action popover"
                 :on {:click [[:actions/close-staking-action-popover]]}}]
       [:div {:class ["absolute"
                      "pointer-events-auto"
                      "staking-action-popover-surface"
                      "rounded-[22px]"
                      "border"
                      "border-[#1d3540]"
                      "p-4"
                      "pt-5"
                      "shadow-[0_24px_58px_rgba(0,0,0,0.55)]"
                      "space-y-3"]
              :style panel-style
              :tab-index 0
              :role "dialog"
              :aria-modal true :data-role "staking-action-popover"
              :replicant/on-render action-popover-focus-on-render
              :on {:keydown [[:actions/handle-staking-action-popover-keydown [:event/key]]]}}
        (popover-close-button)
        [:h2 {:class ["text-[42px]" "font-normal" "leading-none" "text-ho-text" "text-center"]}
         title]
        (case kind
          :transfer
          (popover-content/transfer-popover-content
           {:form form
            :submitting submitting
            :balances balances
            :unstaking unstaking
            :projected-transfer-arrival-ms projected-transfer-arrival-ms
            :transfer-direction (:transfer-direction action-popover)})
          :unstake
          (popover-content/unstake-popover-content
           {:form form
            :submitting submitting
            :error error
            :balances balances
            :selected-validator selected-validator
            :selected-validator-lock selected-validator-lock
            :validator-search-query validator-search-query
            :validator-dropdown-open? validator-dropdown-open?
            :validators validators})
          (popover-content/stake-popover-content
           {:form form
            :submitting submitting
            :balances balances
            :selected-validator selected-validator
            :validator-search-query validator-search-query
            :validator-dropdown-open? validator-dropdown-open?
            :validators validators}))]])))
