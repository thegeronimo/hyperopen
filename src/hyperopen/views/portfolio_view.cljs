(ns hyperopen.views.portfolio-view
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.portfolio.vm :as portfolio-vm]))

(def ^:private compact-currency-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:notation "compact"
        :maximumFractionDigits 1}))

(def ^:private integer-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:maximumFractionDigits 0}))

(def ^:private action-items
  [{:label "Link Staking"
    :action [:actions/navigate "/staking"]}
   {:label "Swap Stablecoins"
    :action [:actions/navigate "/trade"]}
   {:label "Perps ↔ Spot"
    :action [:actions/navigate "/trade"]}
   {:label "EVM ↔ Core"
    :action [:actions/navigate "/trade"]}
   {:label "Portfolio Margin"
    :action [:actions/navigate "/portfolio"]}
   {:label "Send"
    :action [:actions/set-funding-modal :send]}
   {:label "Withdraw"
    :action [:actions/set-funding-modal :withdraw]}
   {:label "Deposit"
    :primary? true
    :action [:actions/set-funding-modal :deposit]}])

(defn- format-currency [value]
  (or (fmt/format-currency value)
      "$0.00"))

(defn- format-compact-currency [value]
  (let [n (if (number? value) value 0)]
    (str "$" (.format compact-currency-formatter n))))

(defn- format-fee-pct [pct]
  (let [n (if (number? pct) pct 0)]
    (str (.toFixed n 3) "%")))

(defn- format-percent [pct]
  (let [n (if (number? pct) pct 0)]
    (str (.toFixed n 2) "%")))

(defn- format-drawdown [ratio]
  (if (number? ratio)
    (format-percent (* ratio 100))
    "N/A"))

(defn- format-hype [value]
  (let [n (if (number? value) value 0)]
    (str (.format integer-formatter n) " HYPE")))

(defn- format-axis-number [value]
  (let [n (if (number? value) value 0)]
    (.format integer-formatter (js/Math.round n))))

(defn- format-axis-percent [value]
  (let [n (if (number? value) value 0)
        rounded (/ (js/Math.round (* n 100)) 100)
        rounded* (if (== rounded -0) 0 rounded)
        magnitude (.toFixed (js/Math.abs rounded*) 2)
        sign (cond
               (pos? rounded*) "+"
               (neg? rounded*) "-"
               :else "")]
    (str sign magnitude "%")))

(defn- format-axis-label [axis-kind value]
  (if (= axis-kind :percent)
    (format-axis-percent value)
    (format-axis-number value)))

(def ^:private axis-label-fallback-char-width-px
  7.5)

(def ^:private axis-label-horizontal-padding-px
  30)

(def ^:private axis-label-min-gutter-width-px
  56)

(def ^:private axis-label-measure-context
  (delay
    (when (and (exists? js/document)
               (some? js/document))
      (let [canvas (.createElement js/document "canvas")]
        (.getContext canvas "2d")))))

(defn- axis-label-width-px [text]
  (let [context @axis-label-measure-context]
    (if context
      (do
        ;; Keep axis width in sync with actual rendered 12px label metrics.
        (set! (.-font context) "12px \"Inter Variable\", system-ui, -apple-system, \"Segoe UI\", sans-serif")
        (-> context
            (.measureText text)
            .-width))
      (* axis-label-fallback-char-width-px (count text)))))

(defn- y-axis-gutter-width [axis-kind y-ticks]
  (let [widest-label-px (->> y-ticks
                             (map (fn [{:keys [value]}]
                                    (axis-label-width-px (format-axis-label axis-kind value))))
                             (reduce max 0))
        gutter-width (+ widest-label-px axis-label-horizontal-padding-px)]
    (js/Math.ceil (max axis-label-min-gutter-width-px gutter-width))))

(defn- action-button [{:keys [label action primary?]}]
  [:button {:type "button"
            :class (into ["btn" "btn-sm" "rounded-lg" "border" "border-base-300" "bg-base-100" "text-trading-text-secondary" "hover:text-trading-text" "hover:bg-base-200"]
                         (when primary?
                           ["bg-[#1f5b55]" "text-trading-text" "hover:bg-[#267067]"]))
            :on {:click [action]}}
   label])

(defn- summary-row [label value & [value-class]]
  [:div {:class ["grid" "grid-cols-[1fr_auto]" "items-center" "gap-3"]}
   [:span {:class ["text-sm" "text-trading-text-secondary"]}
    label]
   [:span {:class (into ["num" "text-sm" "text-trading-text"] (or value-class []))}
    value]])

(defn- pnl-summary [pnl]
  (let [n (if (number? pnl) pnl 0)
        color-class (cond
                      (pos? n) "text-success"
                      (neg? n) "text-error"
                      :else "text-trading-text")]
    {:value (str (cond
                   (pos? n) "+"
                   (neg? n) "-"
                   :else "")
                (format-currency (js/Math.abs n)))
     :class [color-class]}))

(defn- summary-selector
  [{:keys [label open? options value]}
   toggle-action
   select-action
   data-role]
  [:div {:class ["relative"]
         :data-role data-role}
   [:button {:type "button"
             :class ["flex"
                     "items-center"
                     "gap-1.5"
                     "rounded-md"
                     "px-2"
                     "py-1"
                     "text-xs"
                     "font-normal"
                     "text-trading-text"
                     "hover:bg-base-200"]
             :aria-expanded (boolean open?)
             :on {:click [[toggle-action]]}}
    [:span label]
    [:svg {:class (into ["h-4" "w-4" "text-trading-text-secondary" "transition-transform"]
                        (when open?
                          ["rotate-180"]))
           :fill "none"
           :stroke "currentColor"
           :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round"
             :stroke-linejoin "round"
             :stroke-width 2
             :d "M19 9l-7 7-7-7"}]]]
   [:div {:class (into ["absolute"
                        "right-0"
                        "top-full"
                        "mt-1"
                        "min-w-[160px]"
                        "overflow-hidden"
                        "rounded-md"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "shadow-lg"
                        "z-30"]
                       (if open?
                         ["opacity-100" "scale-y-100" "translate-y-0"]
                         ["opacity-0" "scale-y-95" "-translate-y-1" "pointer-events-none"]))
          :style {:transition "all 80ms ease-in-out"}}
    (for [{option-value :value option-label :label} options]
      ^{:key (str data-role "-" (name option-value))}
      [:button {:type "button"
                :class (into ["block"
                              "w-full"
                              "px-3"
                              "py-2"
                              "text-left"
                              "text-xs"
                              "hover:bg-base-200"]
                             (if (= option-value value)
                               ["text-trading-text" "bg-base-200"]
                               ["text-trading-text-secondary"]))
                :on {:click [[select-action option-value]]}}
       option-label])]])

(defn- section-card [data-role & children]
  (into [:div {:class ["rounded-xl"
                       "border"
                       "border-base-300"
                       "bg-base-100/95"
                       "overflow-hidden"]
               :data-role data-role}]
        children))

(defn- summary-card [{:keys [summary selectors]}]
  (let [pnl-info (pnl-summary (:pnl summary))
        summary-scope (:summary-scope selectors)
        summary-time-range (:summary-time-range selectors)]
    (section-card
     "portfolio-account-summary-card"
     [:div {:class ["flex" "items-center" "justify-between" "border-b" "border-base-300" "px-4" "py-3"]}
      (summary-selector summary-scope
                        :actions/toggle-portfolio-summary-scope-dropdown
                        :actions/select-portfolio-summary-scope
                        "portfolio-summary-scope-selector")
      (summary-selector summary-time-range
                        :actions/toggle-portfolio-summary-time-range-dropdown
                        :actions/select-portfolio-summary-time-range
                        "portfolio-summary-time-range-selector")]
     [:div {:class ["space-y-2.5" "px-4" "py-3"]}
      (summary-row "PNL" (:value pnl-info) (:class pnl-info))
      (summary-row "Volume" (format-currency (:volume summary)))
      (summary-row "Max Drawdown" (format-drawdown (:max-drawdown-pct summary)))
      (summary-row "Total Equity" (format-currency (:total-equity summary)))
      (when (:show-perps-account-equity? summary)
        (summary-row "Perps Account Equity" (format-currency (:perps-account-equity summary))))
      (summary-row (:spot-equity-label summary) (format-currency (:spot-account-equity summary)))
      (when (:show-vault-equity? summary)
        (summary-row "Vault Equity" (format-currency (:vault-equity summary))))
      (when (:show-earn-balance? summary)
        (summary-row "Earn Balance" (format-currency (:earn-balance summary))))
      (when (:show-staking-account? summary)
        (summary-row "Staking Account" (format-hype (:staking-account-hype summary))))])))

(defn- chart-card [{:keys [chart]}]
  (let [{:keys [tabs selected-tab axis-kind y-ticks path]} chart
        y-axis-width (y-axis-gutter-width axis-kind y-ticks)
        plot-left (+ y-axis-width 10)]
     (section-card
     "portfolio-chart-card"
     [:div {:class ["flex" "items-center" "border-b" "border-base-300"]}
      (for [{tab-value :value
             tab-label :label} tabs]
        ^{:key (str "portfolio-chart-tab-" (name tab-value))}
        [:button {:type "button"
                  :class (into ["-mb-px" "px-4" "py-3" "text-sm" "transition-colors" "border-b-2" "border-transparent"]
                               (if (= tab-value selected-tab)
                                 ["border-primary" "text-trading-text"]
                                 ["text-trading-text-secondary" "hover:text-trading-text"]))
                  :data-role (str "portfolio-chart-tab-" (name tab-value))
                  :aria-pressed (= tab-value selected-tab)
                  :on {:click [[:actions/select-portfolio-chart-tab tab-value]]}}
         tab-label])]
     [:div {:class ["h-[182px]" "px-4" "py-3" "relative"]
            :data-role "portfolio-chart-shell"}
      [:div {:class ["absolute" "left-0" "top-3" "bottom-3"]
             :data-role "portfolio-chart-y-axis"
             :style {:width (str y-axis-width "px")}}
       (for [{:keys [value y-ratio]} y-ticks]
         ^{:key (str "portfolio-chart-tick-" y-ratio "-" value)}
         [:span {:class ["absolute"
                         "right-2"
                         "-translate-y-1/2"
                         "num"
                         "text-right"
                         "text-xs"
                         "text-trading-text-secondary"]
                 :style {:top (str (* 100 y-ratio) "%")}}
          (format-axis-label axis-kind value)])
       [:div {:class ["absolute"
                      "right-0"
                      "top-0"
                      "bottom-0"
                      "border-l"
                      "border-base-300"]}]
       (for [{:keys [y-ratio]} y-ticks]
         ^{:key (str "portfolio-chart-axis-tick-" y-ratio)}
         [:div {:class ["absolute"
                        "right-0"
                        "w-1.5"
                        "border-t"
                        "border-base-300"]
                :style {:top (str (* 100 y-ratio) "%")}}])]
      [:div {:class ["absolute" "right-2" "top-3" "bottom-3"]
             :style {:left (str plot-left "px")}}
       [:svg {:viewBox "0 0 100 100"
              :preserveAspectRatio "none"
              :class ["h-full" "w-full"]}
        [:line {:x1 0
                :y1 100
                :x2 100
                :y2 100
                :stroke "#28414a"
                :stroke-width 0.8
                :vector-effect "non-scaling-stroke"}]
        (when (seq path)
          [:path {:d path
                  :fill "none"
                  :stroke "#f5f7f8"
                  :stroke-width 1.4
                  :vector-effect "non-scaling-stroke"
                  :stroke-linecap "square"
                  :stroke-linejoin "miter"
                  :data-role "portfolio-chart-path"}])]]])))

(defn- metric-cards [{:keys [volume-14d-usd fees]}]
  [:div {:class ["grid" "grid-cols-1" "gap-3" "md:grid-cols-2" "xl:grid-cols-1"]}
   (section-card
    "portfolio-14d-volume-card"
    [:div {:class ["space-y-3" "px-4" "py-3"]}
     [:div {:class ["text-sm" "text-trading-text-secondary"]}
      "14 Day Volume"]
     [:div {:class ["num" "text-4xl" "font-medium" "text-trading-text"]}
      (format-compact-currency volume-14d-usd)]
     [:button {:class ["btn" "btn-xs" "btn-ghost" "justify-start" "px-0" "text-trading-green" "hover:bg-transparent"]}
      "View Volume"]])
   (section-card
    "portfolio-fees-card"
    [:div {:class ["space-y-3" "px-4" "py-3"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
      [:span {:class ["text-sm" "text-trading-text-secondary"]}
       "Fees (Taker / Maker)"]
      [:button {:class ["btn" "btn-ghost" "btn-xs" "text-trading-text"]}
       "Perps"]]
     [:div {:class ["num" "text-4xl" "font-medium" "leading-tight" "text-trading-text"]}
      (str (format-fee-pct (:taker fees)) " / " (format-fee-pct (:maker fees)))]
     [:button {:class ["btn" "btn-xs" "btn-ghost" "justify-start" "px-0" "text-trading-green" "hover:bg-transparent"]}
      "View Fee Schedule"]])])

(defn- header-actions []
  [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
   [:h1 {:class ["text-5xl" "font-medium" "tracking-tight" "text-trading-text"]}
    "Portfolio"]
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]
          :data-role "portfolio-actions-row"}
    (for [{:keys [label] :as item} action-items]
      ^{:key label}
      (action-button item))]])

(defn portfolio-view [state]
  (let [view-model (portfolio-vm/portfolio-vm state)]
    [:div {:class ["flex-1"
                   "min-h-0"
                   "overflow-y-auto"
                   "app-shell-gutter"
                   "py-4"
                   "space-y-4"
                   "md:py-5"]
           :style {:background-image "radial-gradient(circle at 15% 0%, rgba(0, 212, 170, 0.10), transparent 35%), radial-gradient(circle at 85% 100%, rgba(0, 212, 170, 0.08), transparent 40%)"}
           :data-parity-id "portfolio-root"}
     (header-actions)
     [:div {:class ["grid"
                    "grid-cols-1"
                    "gap-3"
                    "xl:grid-cols-[320px_minmax(340px,1fr)_minmax(420px,1.35fr)]"]}
      (metric-cards view-model)
      (summary-card view-model)
      (chart-card view-model)]
     [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "overflow-hidden"]
            :data-role "portfolio-account-table"}
      (account-info-view/account-info-view state)]]))
