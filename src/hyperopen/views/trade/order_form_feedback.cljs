(ns hyperopen.views.trade.order-form-feedback
  (:require [clojure.string]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-tpsl-policy :as tpsl-policy]))

(defn- twap-runtime-label [total-minutes]
  (if (number? total-minutes)
    (let [days (quot total-minutes 1440)
          hours (quot (mod total-minutes 1440) 60)
          minutes (mod total-minutes 60)
          parts (cond-> []
                  (pos? days) (conj (str days "d"))
                  (pos? hours) (conj (str hours "h"))
                  (or (pos? minutes) (and (zero? days) (zero? hours)))
                  (conj (str minutes "m")))]
      (clojure.string/join " " parts))
    "--"))

(defn- twap-interval-label
  "Human label for the gap between clips. Sub-minute gaps read in seconds; longer gaps
   read in minutes or hours, prefixed with ~ because the venue owns the real schedule."
  [interval-seconds]
  (if (and (number? interval-seconds) (pos? interval-seconds))
    (let [seconds (js/Math.round interval-seconds)]
      (cond
        (< seconds 90) (str seconds "s")
        (< seconds 3600) (str "~" (js/Math.round (/ seconds 60)) "m")
        :else (let [total-minutes (js/Math.round (/ seconds 60))
                    hours (quot total-minutes 60)
                    minutes (mod total-minutes 60)]
                (if (pos? minutes)
                  (str "~" hours "h " minutes "m")
                  (str "~" hours "h")))))
    "--"))

(defn twap-preview
  "Estimated slice schedule for the TWAP ticket.

   Since 2026-08-01 the venue derives the gap between clips from BOTH the runtime and the
   order's notional -- it clips as often as every 30 seconds, but spaces clips further
   apart rather than let one fall under $10. So the estimate needs a reference price; with
   no price available it falls back to the notional-blind 30-second bound, which is the
   upper end of the range."
  [state form base-symbol]
  (let [total-minutes (trading/twap-total-minutes (get-in form [:twap]))
        reference-price (trading/reference-price state form)
        notional (trading/twap-order-notional (:size form) reference-price)
        notional-known? (number? notional)
        order-count (if notional-known?
                      (trading/twap-venue-suborder-count total-minutes notional)
                      (trading/twap-suborder-count total-minutes))
        interval-seconds (trading/twap-interval-seconds-for-count total-minutes order-count)
        suborder-size (trading/twap-suborder-size (:size form) total-minutes reference-price)]
    {:runtime (twap-runtime-label total-minutes)
     ;; With no size (or no price) the count is only the ceiling the 30-second floor
     ;; allows, so it is shown as a bound rather than a figure. Presenting "2881 slices"
     ;; for an empty ticket would assert a schedule the venue will not use.
     :frequency (if notional-known?
                  (twap-interval-label interval-seconds)
                  (str (twap-interval-label interval-seconds) "+"))
     :order-count (cond
                    (not (number? order-count)) "--"
                    notional-known? (str order-count)
                    :else (str "up to " order-count))
     :size-per-suborder (if (number? suborder-size)
                          (str (trading/base-size-string state suborder-size)
                               " "
                               base-symbol)
                          "--")}))

(defn tpsl-panel-model
  [state form side ui-leverage controls]
  (let [ui-state (trading/order-form-ui-state state)
        pricing-policy (trading/order-price-policy state form ui-state)
        limit-like? (boolean (:show-limit-like-controls? controls))
        unit (tpsl-policy/normalize-unit (get-in form [:tpsl :unit]))
        baseline (tpsl-policy/baseline-price form pricing-policy limit-like?)
        size (trading/parse-num (:size form))
        leverage (trading/parse-num ui-leverage)
        tp-inverse (tpsl-policy/inverse-for-leg side :tp)
        sl-inverse (tpsl-policy/inverse-for-leg side :sl)]
    {:form form
     :unit unit
     :unit-dropdown-open? (boolean (:tpsl-unit-dropdown-open? ui-state))
     :tp-offset (tpsl-policy/offset-display {:offset-input (get-in form [:tp :offset-input])
                                             :trigger (get-in form [:tp :trigger])
                                             :baseline baseline
                                             :size size
                                             :leverage leverage
                                             :inverse tp-inverse
                                             :unit unit})
     :sl-offset (tpsl-policy/offset-display {:offset-input (get-in form [:sl :offset-input])
                                             :trigger (get-in form [:sl :trigger])
                                             :baseline baseline
                                             :size size
                                             :leverage leverage
                                             :inverse sl-inverse
                                             :unit unit})}))

(defn- spectate-mode-icon [size-classes]
  [:svg {:viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.9"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :class size-classes
         :aria-hidden "true"}
   [:path {:d "M9 10h.01"}]
   [:path {:d "M15 10h.01"}]
   [:path {:d "M12 2a7 7 0 0 0-7 7v10l2-2 2 2 2-2 2 2 2-2 2 2V9a7 7 0 0 0-7-7z"}]])

(defn spectate-mode-stop-affordance []
  [:div {:data-role "order-form-spectate-mode-stop"}
   [:button {:type "button"
             :class ["flex"
                     "h-9"
                     "w-full"
                     "items-center"
                     "justify-between"
                     "gap-2"
                     "rounded-lg"
                     "border"
                     "border-[#2f7067]"
                     "bg-[#0f433d]/25"
                     "px-3"
                     "text-sm"
                     "font-medium"
                     "text-[#d6f1ed]"
                     "transition-colors"
                     "hover:bg-[#0f433d]/45"
                     "focus:outline-none"
                     "focus:ring-1"
                     "focus:ring-[#87c8c0]/40"
                     "focus:ring-offset-0"]
             :on {:click [[:actions/stop-spectate-mode]]}
             :data-role "order-form-spectate-mode-stop-button"}
    [:span {:class ["inline-flex" "min-w-0" "items-center" "gap-2"]}
     (spectate-mode-icon ["h-5" "w-5" "shrink-0"])
     [:span {:class ["truncate"]} "Stop Spectate Mode"]]
    [:span {:class ["shrink-0"
                    "rounded-[4px]"
                    "border"
                    "border-[#2f7067]"
                    "bg-[#0f433d]"
                    "px-1.5"
                    "py-0.5"
                    "text-xs"
                    "font-semibold"
                    "uppercase"
                    "leading-none"
                    "tracking-[0.04em]"
                    "text-[#c2e5e0]"]}
     "⌘⇧X"]]])
