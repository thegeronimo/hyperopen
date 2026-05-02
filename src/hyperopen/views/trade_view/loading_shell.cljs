(ns hyperopen.views.trade-view.loading-shell
  (:require [hyperopen.trade-modules :as trade-modules]))

(defn trade-chart-loading-shell
  [state]
  (let [error-message (trade-modules/trade-chart-error state)
        route (get-in state [:router :path] "/trade")
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)]
    [:div {:class ["w-full" "h-full" "min-h-0" "min-w-0" "overflow-hidden"]
           :data-parity-id "trade-chart-module-shell"}
     [:div {:class ["w-full" "h-full" "flex" "flex-col" "min-h-0" "min-w-0" "overflow-hidden"]}
      [:div {:class ["flex"
                     "items-center"
                     "border-b"
                     "border-gray-700"
                     "px-4"
                     "pt-2"
                     "pb-1"
                     "w-full"
                     "min-w-0"
                     "space-x-4"
                     "bg-base-100"]
             :data-role "trade-chart-shell-toolbar"}
       [:div {:class ["flex" "items-center" "space-x-1"]}
        (for [timeframe [:5m :1h :1d]]
          ^{:key (str "trade-chart-shell-timeframe-" (name timeframe))}
          [:div {:class (if (= timeframe selected-timeframe)
                          ["px-3"
                           "py-1"
                           "text-sm"
                           "font-medium"
                           "rounded"
                           "text-trading-green"]
                          ["px-3"
                           "py-1"
                           "text-sm"
                           "font-medium"
                           "rounded"
                           "text-gray-300"
                           "bg-base-200/70"])
                 :data-role (str "trade-chart-shell-timeframe-" (name timeframe))}
           (name timeframe)])
        [:div {:class ["flex"
                       "items-center"
                       "px-3"
                       "py-1"
                       "text-sm"
                       "font-medium"
                       "rounded"
                       "text-gray-300"]
               :data-role "trade-chart-shell-timeframe-dropdown"}
         [:span "▼"]]]
       [:div {:class ["w-px" "h-6" "bg-gray-700"]
              :data-role "trade-chart-shell-divider"}]
       [:div {:class ["flex" "items-center" "gap-1"]
              :data-role "trade-chart-shell-controls"}
        [:div {:class ["h-6" "w-6" "rounded" "bg-base-200/60"]
               :data-role "trade-chart-shell-chart-type"}]
        [:div {:class ["w-px" "h-6" "bg-gray-700"]
               :data-role "trade-chart-shell-divider"}]
        [:div {:class ["flex"
                       "items-center"
                       "gap-1.5"
                       "h-8"
                       "px-3"
                       "text-base"
                       "font-medium"
                       "rounded-none"
                       "text-gray-300"
                       "bg-gray-900/40"]
               :data-role "trade-chart-shell-indicators"}
         [:span "Indicators"]]]]
      [:div {:class ["w-full"
                     "relative"
                     "flex-1"
                     "min-h-[360px]"
                     "min-w-0"
                     "bg-base-100"
                     "trading-chart-host"]}
       [:div {:class ["absolute"
                      "inset-0"
                      "flex"
                      "items-center"
                      "justify-center"
                      "px-6"
                      "py-10"]}
        [:div {:class ["flex"
                       "max-w-md"
                       "flex-col"
                       "items-center"
                       "gap-3"
                       "text-center"]}
         [:div {:class ["text-sm"
                        "font-semibold"
                        "uppercase"
                        "tracking-[0.12em]"
                        "text-trading-text-secondary"]}
          (if error-message
            "Chart Load Failed"
            "Loading Chart")]
         [:p {:class ["text-sm" "text-trading-text-secondary"]}
          (or error-message
              "Loading the trade chart on demand to keep the initial trade bundle smaller.")]
         (when error-message
           [:button {:type "button"
                     :class ["rounded-lg"
                             "border"
                             "border-base-300"
                             "px-3"
                             "py-2"
                             "text-sm"
                             "font-medium"
                             "text-trading-text"
                             "transition-colors"
                             "hover:border-primary"
                             "hover:text-primary"]
                     :on {:click [[:actions/navigate route {:replace? true}]]}}
            "Retry"])]]]]]))

