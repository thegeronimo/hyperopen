(ns hyperopen.views.degen.order-form
  "Degen-voice chrome for the trade order form: escalating leverage
   taunts (live in the popover, banner for the committed value) and the
   massive BUY MOON / SELL PANIC side selectors from the HyperDegen
   prototype. Everything renders nil (or the compact default) under
   other themes."
  (:require [hyperopen.ui.sfx :as sfx]
            [hyperopen.ui.voice :as voice]))

(defn leverage-tier
  "Escalating taunt for a leverage value (prototype's LEVERAGE_WARNINGS).
   nil for non-numeric input."
  [leverage]
  (let [lev (js/parseFloat leverage)]
    (when-not (js/isNaN lev)
      (cond
        (>= lev 200)
        {:level 5
         :text "MAXIMUM DEGEN. Tell your family you love them. 💀"
         :classes ["border-ho-sell" "text-ho-sell"]}

        (>= lev 100)
        {:level 4
         :text "A 0.1% wick ends you. Good luck. 💀"
         :classes ["border-ho-sell" "text-ho-sell"]}

        (>= lev 50)
        {:level 3
         :text "Your liquidation price is basically the current price."
         :classes ["border-ho-sell" "text-ho-sell"]}

        (>= lev 20)
        {:level 2
         :text "WARNING: HIGH LEVERAGE = BIG FUN (or big sadness)"
         :classes ["border-ho-warn" "text-ho-warn"]}

        (>= lev 10)
        {:level 1
         :text "Getting spicy. 🌶️"
         :classes ["border-ho-warn" "text-ho-warn"]}

        :else
        {:level 0
         :text "Sensible. Are you lost?"
         :classes ["border-ho-border" "text-ho-text-dim"]}))))

(defn leverage-warning-banner
  "Escalating parody warning under the leverage row for the committed
   leverage; quiet below 20x so the form isn't permanently noisy."
  [state leverage]
  (when (voice/degen? state)
    (when-some [tier (leverage-tier leverage)]
      (when (>= (:level tier) 2)
        [:div {:class (into ["rounded-md" "border" "border-dashed" "px-2.5"
                             "py-1.5" "text-xs" "font-bold"]
                            (:classes tier))
               :data-role "degen-leverage-warning"}
         (:text tier)]))))

(defn- tier-slider-color
  [tier]
  (cond
    (>= (:level tier) 3) "rgb(var(--ho-sell) / 0.6)"
    (>= (:level tier) 1) "rgb(var(--ho-warn) / 0.55)"
    :else "rgb(var(--ho-accent) / 0.5)"))

(defn- leverage-scale-marks
  "Evenly spaced Nx labels for the popover slider scale (the prototype's
   1x..1000x ruler, adapted to the market's real max leverage)."
  [max-leverage]
  (let [m (js/Math.max 1 (js/Math.round (js/parseFloat max-leverage)))]
    (->> [1 (* m 0.25) (* m 0.5) (* m 0.75) m]
         (map #(js/Math.max 1 (js/Math.round %)))
         distinct
         vec)))

(defn leverage-popover-risk
  "Degen chrome for the leverage popover, driven live by the slider
   draft: taunt message, slider fill color, value text class, and a
   scale-marks row. Ticks on tier crossings. nil under other voices."
  [state draft-leverage max-leverage]
  (when (voice/degen? state)
    (when-some [tier (leverage-tier draft-leverage)]
      (sfx/leverage-tick-on-change! :leverage (:level tier) true)
      {:slider-color (tier-slider-color tier)
       :value-class (second (:classes tier))
       :marks-row [:div {:class ["flex" "items-center" "justify-between"
                                 "px-0.5" "text-xs" "text-ho-text-dim"]
                         :data-role "degen-leverage-scale-marks"}
                   (for [mark (leverage-scale-marks max-leverage)]
                     ^{:key (str "degen-lev-mark-" mark)}
                     [:span (str mark "x")])]
       :message [:div {:class (into ["rounded-md" "border" "border-dashed"
                                     "px-2.5" "py-1.5" "text-xs" "font-bold"
                                     "leading-snug"]
                                    (:classes tier))
                       :data-role "degen-leverage-popover-message"}
                 (str "⚠ " (:text tier))]})))

(defn effective-leverage
  "Account leverage implied by the order size slider: using P% of the
   available balance at Lx margin leverage levers the account ~P/100*L.
   nil when either input is non-numeric."
  [size-percent leverage]
  (let [pct (js/parseFloat size-percent)
        lev (js/parseFloat leverage)]
    (when-not (or (js/isNaN pct) (js/isNaN lev))
      (* (/ pct 100) lev))))

(defn size-risk
  "Degen risk feedback for the order-size slider, driven by the imputed
   account leverage: a taunt line for under the slider and a fill color
   for the slider track (token var forms, never raw colors). Ticks on
   tier crossings. nil unless the degen voice is active and the size is
   positive."
  [state size-percent ui-leverage]
  (when (voice/degen? state)
    (when-some [eff (effective-leverage size-percent ui-leverage)]
      (when (pos? eff)
        (let [tier (leverage-tier eff)
              all-in? (>= (js/parseFloat size-percent) 100)]
          (sfx/leverage-tick-on-change! :order-size (:level tier) true)
          {:slider-color (tier-slider-color tier)
           :message [:div {:class (into ["rounded-md" "border" "border-dashed"
                                         "px-2.5" "py-1.5" "text-xs" "font-bold"
                                         "leading-snug"]
                                        (:classes tier))
                           :data-role "degen-size-risk-message"}
                     (str "⚠ ≈" (.toFixed eff 1) "x account leverage. "
                          (when all-in? "ALL IN. ")
                          (:text tier))]})))))

(defn- massive-side-button
  "Big stacked side selector with a parenthetical sublabel, per the
   prototype's BUY MOON / SELL PANIC."
  [label sublabel side active? on-click]
  (let [buy? (= side :buy)]
    [:button {:type "button"
              :data-role (str "degen-massive-side-" (name side))
              :aria-pressed (boolean active?)
              :class (into ["flex" "w-full" "flex-col" "items-center"
                            "justify-center" "gap-0.5" "rounded-xl" "py-2.5"
                            "border-2" "font-black" "tracking-wide"
                            "transition-colors"]
                           (cond
                             (and active? buy?)
                             ["bg-ho-buy" "border-ho-buy" "text-ho-bg"]

                             buy?
                             ["bg-ho-accent-soft" "border-ho-border-accent"
                              "text-ho-buy" "hover:bg-ho-accent-soft-hi"]

                             active?
                             ["bg-ho-sell" "border-ho-sell" "text-ho-bg"]

                             :else
                             ["bg-ho-sell-soft" "border-ho-border-sell"
                              "text-ho-sell" "hover:bg-ho-sell-soft-deep"]))
              :on {:click on-click}}
     [:span {:class ["text-base" "leading-tight"]} label]
     (when sublabel
       [:span {:class ["text-xs" "font-semibold" "opacity-80"]} sublabel])]))

(defn massive-side-row
  [side side-handlers {:keys [buy-label sell-label buy-sublabel sell-sublabel]}]
  [:div {:class ["flex" "flex-col" "gap-2"]
         :data-role "degen-massive-side-row"}
   (massive-side-button buy-label buy-sublabel :buy (= side :buy)
                        ((:on-select-side side-handlers) :buy))
   (massive-side-button sell-label sell-sublabel :sell (= side :sell)
                        ((:on-select-side side-handlers) :sell))])
