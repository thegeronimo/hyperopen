(ns hyperopen.views.portfolio.optimize.frontier-chart
  (:require [hyperopen.views.portfolio.optimize.frontier-chart-layers :as frontier-layers]
            [hyperopen.views.portfolio.optimize.frontier-chart-model :as chart-model]
            [hyperopen.views.portfolio.optimize.frontier-chart-toolbar :as frontier-toolbar]))

(defn- build-frontier-chart
  [draft result overlay-mode constrain-frontier?]
  (let [{:keys [points subtitle reading-text target] :as model}
        (chart-model/chart-model draft result overlay-mode constrain-frontier?)]
    (when (seq points)
      [:section {:class ["optimizer-frontier-panel"
                         "min-w-0" "overflow-hidden" "bg-transparent" "leading-4"]
                 :data-role "portfolio-optimizer-frontier-panel"}
       (frontier-toolbar/toolbar
        {:overlay-mode (:overlay-mode model)
         :constrain-frontier? constrain-frontier?
         :subtitle subtitle
         :point-count (count points)})
       [:div {:class ["optimizer-frontier-chart-box"
                      "relative" "mt-4" "overflow-hidden" "border" "border-base-300" "bg-base-100" "p-4"]
              :data-role "portfolio-optimizer-frontier-chart-box"}
        (frontier-layers/chart-svg draft result model)]
       [:div {:class ["mt-4" "grid" "grid-cols-[auto_auto_minmax(0,1fr)]"
                      "items-start" "gap-3" "text-[0.7rem]" "text-trading-muted"]
              :data-role "portfolio-optimizer-frontier-reading"}
        [:span {:class ["whitespace-nowrap" "font-mono" "text-[0.62rem]"
                        "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]
                :data-role "portfolio-optimizer-frontier-reading-label"}
         "Reading this"]
        [:span {:aria-hidden "true"} "·"]
        [:span {:class ["min-w-0"]
                :data-role "portfolio-optimizer-frontier-reading-copy"}
         (str reading-text
              " Click or drag a point to set "
              (:label target)
              " and rerun.")]]])))

(def ^:private chart-memo (volatile! nil))

(defn frontier-chart
  ([draft result]
   (frontier-chart draft result :standalone))
  ([draft result overlay-mode]
   (frontier-chart draft result overlay-mode false))
  ([draft result overlay-mode constrain-frontier?]
   (let [inputs [draft result overlay-mode constrain-frontier?]
         cached @chart-memo]
     (if (and cached (= inputs (:inputs cached)))
       (:hiccup cached)
       (let [hiccup (build-frontier-chart draft result overlay-mode constrain-frontier?)]
         (vreset! chart-memo {:inputs inputs :hiccup hiccup})
         hiccup)))))
