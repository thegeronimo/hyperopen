(ns hyperopen.views.portfolio.montecarlo.distributions
  "Distribution-histogram cards on the Monte Carlo tab. The data-role prefix
  arrives via the model's `:chrome` so the portfolio and vault surfaces share
  these cards."
  (:require [hyperopen.views.portfolio.montecarlo.chart :as chart]
            [hyperopen.views.portfolio.montecarlo.format :as fmt]))

(defn- dist-card
  [{:keys [title dist fmt-kind sign-by-value? threshold range-fmt update-key data-role-prefix]}]
  [:div {:class ["mc-card" "mc-dist-card"]
         :data-role (str data-role-prefix "-dist-" (name (first update-key)))}
   [:div {:class ["mc-dist-title"]} title]
   [:div {:class ["mc-dist-range"]}
    "P5 " [:b (range-fmt (:p5 dist))] " · P95 " [:b (range-fmt (:p95 dist))]]
   [:div {:class ["mc-dist-canvas"]
          :replicant/on-render (chart/histogram-on-render
                                {:dist dist
                                 :fmt fmt-kind
                                 :sign-by-value? sign-by-value?
                                 :threshold threshold
                                 :median (:median dist)
                                 :height 92
                                 :update-key update-key})}]
   [:div {:class ["mc-dist-foot"]}
    [:span (str "median " (range-fmt (:median dist)))]
    [:span (str "μ " (range-fmt (:mean dist)))]]])

(defn distributions
  [{:keys [result controls run-key method chrome]}]
  (let [{:keys [terminal maxdd sharpe vol]} result
        prefix (:data-role-prefix chrome)
        bust-fraction (/ (:bust controls) 100)
        pct0 (fn [v] (fmt/signed-pct v 0))
        pctp0 (fn [v] (fmt/unsigned-pct v 0))
        r2 (fn [v] (fmt/ratio v 2))]
    (if (= method :shuffle)
      [:div {:class ["mc-dist-grid" "mc-dist-grid-2"]
             :data-role (str prefix "-distributions")}
       (dist-card {:title "Sharpe ratio"
                   :dist sharpe
                   :sign-by-value? true
                   :range-fmt r2
                   :update-key [:sharpe run-key]
                   :data-role-prefix prefix})
       (dist-card {:title "Max drawdown"
                   :dist maxdd
                   :fmt-kind :pct
                   :threshold bust-fraction
                   :range-fmt pct0
                   :update-key [:maxdd run-key]
                   :data-role-prefix prefix})]
      [:div {:class ["mc-dist-grid"]
             :data-role (str prefix "-distributions")}
       (dist-card {:title "Total return"
                   :dist terminal
                   :fmt-kind :pct
                   :sign-by-value? true
                   :range-fmt pct0
                   :update-key [:total run-key]
                   :data-role-prefix prefix})
       (dist-card {:title "Max drawdown"
                   :dist maxdd
                   :fmt-kind :pct
                   :threshold bust-fraction
                   :range-fmt pct0
                   :update-key [:maxdd run-key]
                   :data-role-prefix prefix})
       (dist-card {:title "Sharpe ratio"
                   :dist sharpe
                   :sign-by-value? true
                   :range-fmt r2
                   :update-key [:sharpe run-key]
                   :data-role-prefix prefix})
       (dist-card {:title "Annualized vol"
                   :dist vol
                   :fmt-kind :pct
                   :range-fmt pctp0
                   :update-key [:vol run-key]
                   :data-role-prefix prefix})])))
