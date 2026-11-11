(ns hyperopen.views.trading-chart.toolbar
  (:require [hyperopen.views.trading-chart.chart-type-dropdown :refer [chart-type-dropdown]]
            [hyperopen.views.trading-chart.indicators-dropdown :refer [indicators-dropdown]]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.views.trading-chart.timeframe-dropdown :refer [timeframe-dropdown]]
            [hyperopen.views.trading-chart.answers-vm :as answers-vm]
            [hyperopen.views.trading-chart.vm :as vm]
            [hyperopen.views.websocket-freshness :as ws-freshness]))

;; Main timeframes for quick access buttons
(def main-timeframes [:5m :1h :1d])

(defn- chart-trades-toggle
  [state]
  (let [on? (trading-settings/show-chart-trades? state)]
    [:button
     {:type "button"
      :data-role "chart-toolbar-trades-toggle"
      :aria-pressed (str on?)
      :title (if on? "Hide your trades on the chart" "Show your trades on the chart")
      :class (cond-> ["flex" "items-center" "gap-1.5" "h-8" "px-3" "text-base" "font-medium"
                      "rounded-none" "transition-colors" "bg-gray-900/40"
                      "hover:text-white" "hover:bg-gray-800/70"
                      "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-slate-500/70"
                      "focus-visible:ring-offset-1" "focus-visible:ring-offset-base-100"]
               on? (conj "text-gray-100")
               (not on?) (conj "text-gray-400"))
      :on {:click [[:actions/set-chart-trades-enabled (not on?)]]}}
     [:span {:class (cond-> ["flex" "items-center" "gap-0.5"]
                      (not on?) (conj "opacity-40"))
             :aria-hidden "true"}
      [:span {:class ["inline-block" "w-2" "h-2" "rounded-full" "bg-trading-green"]}]
      [:span {:class ["inline-block" "w-2" "h-2" "rounded-full" "bg-trading-red"]}]]
     [:span "My trades"]]))

;; Outcome markets only: label prices in percent (a chance) or cents (a share
;; price). One preference for every outcome chart; it only relabels.
(def ^:private axis-unit-options
  [{:unit :percent :text "%" :aria-label "Percent"}
   {:unit :cents :text "¢" :aria-label "Cents"}])

(def ^:private segment-button-classes
  ["flex" "items-center" "justify-center" "h-8" "min-w-8" "px-2.5" "text-base" "font-medium"
   "whitespace-nowrap" "rounded-none" "transition-colors"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-slate-500/70"
   "focus-visible:ring-offset-1" "focus-visible:ring-offset-base-100"])

(defn- segment-classes
  [on? disabled?]
  (cond-> segment-button-classes
    on? (into ["text-gray-100" "bg-gray-800"])
    (and (not on?) (not disabled?)) (into ["text-gray-400" "bg-gray-900/40" "hover:text-white" "hover:bg-gray-800/70"])
    (and (not on?) disabled?) (into ["text-gray-600" "bg-gray-900/40" "cursor-not-allowed"])))

(defn- axis-unit-control
  "`locked?` (All answers): the chances compare on one percent scale, so %
   shows pressed and does nothing, and ¢ is disabled. The stored preference
   is left alone."
  [selected-unit locked?]
  [:div {:class ["flex" "items-center" "gap-1.5"]
         :role "group"
         :aria-label "Chart price axis unit"
         :data-role "chart-toolbar-axis-unit"}
   [:span {:class ["text-xs" "text-gray-400" "pl-1"]
           :aria-hidden "true"}
    "Axis"]
   [:div {:class ["flex" "items-center"]}
    (for [{:keys [unit text aria-label]} axis-unit-options]
      (let [on? (if locked? (= unit :percent) (= unit selected-unit))
            disabled? (and locked? (= unit :cents))]
        [:button
         (cond-> {:key (name unit)
                  :type "button"
                  :data-role "chart-toolbar-axis-unit-option"
                  :data-unit (name unit)
                  :aria-pressed (str on?)
                  :aria-label (if disabled?
                                "Cents, not available when all answers are shown"
                                aria-label)
                  :title (cond
                           disabled? "Cents are not available when all answers are shown"
                           locked? "All answers compare on one percent scale"
                           :else (str "Show prices in " (if (= unit :cents) "cents" "percent")))
                  :class (segment-classes on? disabled?)}
           disabled? (assoc :disabled true)
           ;; % stays pressed but does nothing while all answers are shown
           (and locked? (not disabled?)) (assoc :aria-disabled "true")
           (not locked?) (assoc :on {:click [[:actions/set-outcome-chart-unit unit]]}))
         text]))]])

(defn- chart-shows-control
  "Multi-answer events: the selected answer's candles or every answer as a
   line."
  [{:keys [mode selected-label]}]
  [:div {:class ["flex" "items-center"]
         :role "group"
         :aria-label "Chart shows"
         :data-role "chart-toolbar-chart-shows"}
   (for [[value text] [[:single (str selected-label " only")] [:all "All answers"]]]
     (let [on? (= value mode)]
       [:button {:key (name value)
                 :type "button"
                 :data-role "chart-toolbar-chart-shows-option"
                 :data-mode (name value)
                 :aria-pressed (str on?)
                 :class (segment-classes on? false)
                 :on {:click [[:actions/set-outcome-chart-mode value]]}}
        text]))])

(defn chart-top-menu
  [state]
  (let [timeframes-dropdown-visible (get-in state [:chart-options :timeframes-dropdown-visible])
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        chart-type-dropdown-visible (get-in state [:chart-options :chart-type-dropdown-visible])
        selected-chart-type (get-in state [:chart-options :selected-chart-type] :candlestick)
        indicators-dropdown-visible (get-in state [:chart-options :indicators-dropdown-visible])
        volume-visible? (boolean (get-in state [:chart-options :volume-visible?] true))
        active-indicators (get-in state [:chart-options :active-indicators] {})
        indicators-search-term (get-in state [:chart-options :indicators-search-term] "")
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        websocket-health (get-in state [:websocket :health])
        mode-control (answers-vm/chart-mode-control state)
        outcome? (some? (vm/chart-price-unit state))
        all-answers? (= :all (:mode mode-control))
        freshness-cue (when show-surface-freshness-cues?
                        (ws-freshness/surface-cue websocket-health
                                                  {:topic "trades"
                                                   :selector {:coin (:active-asset state)}
                                                   :live-prefix "Last tick"}))]
    ;; An outcome chart's toolbar wraps rather than clipping its Axis and
    ;; Chart shows controls past a phone's edge. Other markets keep the
    ;; one-row toolbar (a second row would cost their chart height).
    [:div {:class (if outcome?
                    ["flex" "flex-wrap" "items-center" "gap-x-4" "gap-y-1" "border-b" "border-gray-700"
                     "px-4" "pt-2" "pb-1" "w-full" "min-w-0" "bg-base-100"]
                    ["flex" "items-center" "border-b" "border-gray-700" "px-4" "pt-2" "pb-1"
                     "w-full" "min-w-0" "space-x-4" "bg-base-100"])
           :data-parity-id "chart-toolbar"}
     [:div.flex.items-center.space-x-1
      (for [key main-timeframes]
        [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
         {:key key
          :class (if (= selected-timeframe key)
                   ["text-trading-green"]
                   ["text-gray-300" "hover:text-white" "hover:bg-gray-700"])
          :on {:click [[:actions/select-chart-timeframe key]]}}
         (name key)])
      (when-not (contains? (set main-timeframes) selected-timeframe)
        [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
         {:class ["text-trading-green"]
          :on {:click [[:actions/toggle-timeframes-dropdown]]}}
         (name selected-timeframe)])
      (timeframe-dropdown {:selected-timeframe selected-timeframe
                           :timeframes-dropdown-visible timeframes-dropdown-visible})]

     [:div.w-px.h-6.bg-gray-700]

     (if all-answers?
       ;; All answers draws lines on a fixed percent scale: no candle type,
       ;; indicators, volume or trade markers.
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-1"]
              :data-role "chart-toolbar-answer-controls"}
        (chart-shows-control mode-control)
        [:div.w-px.h-6.bg-gray-700]
        (axis-unit-control (vm/chart-price-unit state) true)]
       [:div {:class (cond-> ["flex" "items-center" "gap-1"] outcome? (conj "flex-wrap"))}
        (chart-type-dropdown {:selected-chart-type selected-chart-type
                              :chart-type-dropdown-visible chart-type-dropdown-visible})
        [:div.w-px.h-6.bg-gray-700]
        [:div.relative
         (let [active-count (count active-indicators)
               has-active-indicators? (pos? active-count)]
           [:button
            {:class (cond-> ["flex" "items-center" "gap-1.5" "h-8" "px-3" "text-base" "font-medium"
                             "rounded-none" "transition-colors"
                             "text-gray-300" "bg-gray-900/40"
                             "hover:text-white" "hover:bg-gray-800/70"
                             "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-slate-500/70"
                             "focus-visible:ring-offset-1" "focus-visible:ring-offset-base-100"]
                      indicators-dropdown-visible (into ["text-white" "bg-gray-800"])
                      has-active-indicators? (conj "text-gray-100"))
             :on {:click [[:actions/toggle-indicators-dropdown]]}
             :aria-label (if has-active-indicators?
                           (str "Indicators (" active-count " active)")
                           "Indicators")}
            [:span "Indicators"]
            (when has-active-indicators?
              [:span {:class ["text-xs" "text-gray-400"]} (str "(" active-count ")")])])
         (indicators-dropdown {:indicators-dropdown-visible indicators-dropdown-visible
                               :volume-visible? volume-visible?
                               :active-indicators active-indicators
                               :search-term indicators-search-term})]
        [:div.w-px.h-6.bg-gray-700]
        (chart-trades-toggle state)
        (when mode-control
          [:div {:class ["flex" "items-center" "gap-1"]}
           [:div.w-px.h-6.bg-gray-700]
           (chart-shows-control mode-control)])
        (when-let [unit (vm/chart-price-unit state)]
          [:div {:class ["flex" "items-center" "gap-1"]}
           [:div.w-px.h-6.bg-gray-700]
           (axis-unit-control unit false)])])

     (when freshness-cue
       ^{:replicant/key "chart-freshness-cue"}
       [:div {:class ["ml-auto" "flex" "items-center"]
              :data-role "chart-freshness-cue"}
        [:span {:class (case (:tone freshness-cue)
                         :success ["text-xs" "font-medium" "text-success" "tracking-wide"]
                         :warning ["text-xs" "font-medium" "text-warning" "tracking-wide"]
                         ["text-xs" "font-medium" "text-base-content/70" "tracking-wide"])}
         (:text freshness-cue)]])]))
