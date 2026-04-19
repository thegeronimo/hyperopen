(ns hyperopen.views.trading-chart.toolbar
  (:require [hyperopen.views.trading-chart.chart-type-dropdown :refer [chart-type-dropdown]]
            [hyperopen.views.trading-chart.indicators-dropdown :refer [indicators-dropdown]]
            [hyperopen.views.trading-chart.timeframe-dropdown :refer [timeframe-dropdown]]
            [hyperopen.views.websocket-freshness :as ws-freshness]))

;; Main timeframes for quick access buttons
(def main-timeframes [:5m :1h :1d])

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
        freshness-cue (when show-surface-freshness-cues?
                        (ws-freshness/surface-cue websocket-health
                                                  {:topic "trades"
                                                   :selector {:coin (:active-asset state)}
                                                   :live-prefix "Last tick"}))]
    [:div.flex.items-center.border-b.border-gray-700.px-4.pt-2.pb-1.w-full.min-w-0.space-x-4.bg-base-100
     {:data-parity-id "chart-toolbar"}
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

     [:div.flex.items-center.gap-1
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
                             :search-term indicators-search-term})]]

     (when freshness-cue
       ^{:replicant/key "chart-freshness-cue"}
       [:div {:class ["ml-auto" "flex" "items-center"]
              :data-role "chart-freshness-cue"}
        [:span {:class (case (:tone freshness-cue)
                         :success ["text-xs" "font-medium" "text-success" "tracking-wide"]
                         :warning ["text-xs" "font-medium" "text-warning" "tracking-wide"]
                         ["text-xs" "font-medium" "text-base-content/70" "tracking-wide"])}
         (:text freshness-cue)]])]))
