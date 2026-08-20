(ns hyperopen.views.trade.order-form-feedback
  (:require [clojure.string]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-tpsl-policy :as tpsl-policy]))

(defn- pluralized
  [value unit]
  (str value " " unit (when-not (= 1 value) "s")))

(defn- duration-phrase
  "Reads a runtime the way a person would say it. Minutes below an hour and for anything
   that is not a whole number of hours, so ninety minutes stays ninety minutes. Hours up to
   two days, so a one-day run says twenty-four hours. Days beyond that."
  [total-minutes]
  (when (and (number? total-minutes) (pos? total-minutes))
    (cond
      (< total-minutes 60) (pluralized total-minutes "minute")

      (and (< total-minutes 2880) (zero? (mod total-minutes 60)))
      (pluralized (quot total-minutes 60) "hour")

      (< total-minutes 2880) (pluralized total-minutes "minute")

      (zero? (mod total-minutes 1440)) (pluralized (quot total-minutes 1440) "day")

      :else (pluralized (js/Math.round (/ total-minutes 60)) "hour"))))

(defn- interval-phrase
  "The gap between slices, rounded for prose. The ticket says 'about every' in front of it,
   so precision past the unit would be false confidence."
  [interval-seconds]
  (when (and (number? interval-seconds) (pos? interval-seconds))
    (let [seconds (js/Math.round interval-seconds)]
      (cond
        (< seconds 90) (pluralized seconds "second")
        (< seconds 5400) (pluralized (js/Math.round (/ seconds 60)) "minute")
        :else (pluralized (js/Math.round (/ seconds 3600)) "hour")))))

(defn twap-schedule
  "Everything the TWAP section needs to state its schedule.

   The venue derives slice spacing from the order's notional as well as its runtime, so the
   real schedule is only knowable once a size is entered. Until then :known? is false and
   the section says so in words rather than showing a number it cannot stand behind."
  [state form base-symbol]
  (let [twap (get-in form [:twap])
        total-minutes (trading/twap-total-minutes twap)
        reference-price (trading/reference-price state form)
        notional (trading/twap-order-notional (:size form) reference-price)
        known? (number? notional)
        preset-key (trading/twap-preset-key-for-runtime total-minutes)
        order-count (when known? (trading/twap-venue-suborder-count total-minutes notional))
        interval-seconds (when order-count
                           (trading/twap-interval-seconds-for-count total-minutes order-count))
        suborder-size (trading/twap-suborder-size (:size form) total-minutes reference-price)
        slice-notional (when (and known? order-count (pos? order-count))
                         (/ notional order-count))]
    {:preset-key (or preset-key :custom)
     :custom? (or (boolean (:custom-runtime? twap))
                  (nil? preset-key))
     :presets trading/twap-runtime-presets
     :runtime-window "5m - 7d"
     :known? known?
     :verb (if (= :sell (:side form)) "Sells" "Buys")
     :base-symbol base-symbol
     :runtime-phrase (duration-phrase total-minutes)
     :interval-phrase (interval-phrase interval-seconds)
     :order-count order-count
     :order-notional (when known? (fmt/format-large-currency notional))
     :slice-notional (when slice-notional (fmt/format-currency slice-notional))
     :slice-size (when (number? suborder-size)
                   (str (trading/base-size-string state suborder-size) " " base-symbol))}))

(defn twap-submit-copy
  "Submit-button copy for a TWAP, or nil for every other order type.

   A TWAP is not a placed order -- it is a run the venue works over time -- so the button
   says what starting it does, and the recap beneath restates any guard the label has no
   room for. Returning nil leaves the other order types on the voice catalog untouched."
  [{:keys [order-type schedule guards]}]
  (when (= :twap order-type)
    (let [{:keys [verb runtime-phrase]} schedule
          direction (if (= "Sells" verb) "sell" "buy")]
      {:label (if runtime-phrase
                (str "Start TWAP - " direction " over " runtime-phrase)
                "Start TWAP")
       :recap (:submit-recap guards)})))

(defn- level-text
  "A guard level as the user would write it: thousands grouped, and no cents forced onto a
   round number. The shared price formatter pins two decimals for anything over a cent,
   which turns a 65,000 trigger into 65,000.00 -- accurate, but noise on a band label."
  [value]
  (when-let [formatted (fmt/format-trade-price-plain value)]
    (if (clojure.string/includes? formatted ".")
      (-> formatted
          (clojure.string/replace #"0+$" "")
          (clojure.string/replace #"\.$" ""))
      formatted)))

(defn- band-position
  "Where a level sits on the guard band, as a percentage. The band reserves 11% of its width
   at each end so an extreme marker still reads as a marker rather than a cut edge."
  [value lo hi]
  (if (or (nil? value) (nil? lo) (nil? hi) (= lo hi))
    50
    (+ 11 (* 78 (/ (- value lo) (- hi lo))))))

(defn twap-guards
  "The optional price guards, and the band that places them either side of the mark.

   The band only appears once there is a mark and at least one level to plot against it --
   drawing an empty axis would be decoration, not information."
  [state form]
  (let [twap (get-in form [:twap])
        sell? (= :sell (:side form))
        trigger (trading/parse-num (:trigger-px twap))
        stop (trading/parse-num (:stop-px twap))
        mark (trading/parse-num (trading/reference-price state form))
        levels (remove nil? [trigger stop mark])
        lo (when (seq levels) (apply min levels))
        hi (when (seq levels) (apply max levels))
        fmt-level (fn [v] (when v (level-text v)))]
    {:stop-price-label (if sell? "Min Price" "Max Price")
     :sell? sell?
     :summary (cond
                (and trigger stop) (str (fmt-level trigger) " \u2192 " (fmt-level stop))
                trigger (str "starts " (fmt-level trigger))
                stop (str "stops " (fmt-level stop))
                :else "none set")
     :any-set? (boolean (or trigger stop))
     :trigger-note (when trigger
                     (str "Nothing sends until mark hits " (fmt-level trigger) "."))
     :stop-note (when stop
                  (str "At " (fmt-level stop) " the rest of the run is cancelled."))
     :band (when (and mark (or trigger stop))
             {:mark-label (str "MARK " (fmt-level mark))
              :mark-pct (band-position mark lo hi)
              :trigger-label (when trigger (str "STARTS " (fmt-level trigger)))
              :trigger-pct (when trigger (band-position trigger lo hi))
              :stop-label (when stop (str "STOPS " (fmt-level stop)))
              :stop-pct (when stop (band-position stop lo hi))
              :range-from (band-position (or (when (and trigger stop) (min trigger stop))
                                             trigger stop) lo hi)
              :range-to (band-position (or (when (and trigger stop) (max trigger stop))
                                           trigger stop) lo hi)})
     :submit-recap (cond
                     (and trigger stop) (str "Starts at " (fmt-level trigger)
                                             " \u00b7 stops at " (fmt-level stop))
                     trigger (str "Starts at " (fmt-level trigger))
                     stop (str "Stops at " (fmt-level stop))
                     :else nil)}))

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
