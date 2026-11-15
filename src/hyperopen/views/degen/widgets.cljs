(ns hyperopen.views.degen.widgets
  "Theme-gated parody decor for the HyperDegen experience (see
   docs/exec-plans/active/2026-06-10-hyperdegen-voice-layer.md, Phase C).

   Every component renders nil unless the degen voice is active, projects
   *real* account/market state into joke chrome where data is available,
   and uses semantic tokens only — no raw colors."
  (:require [hyperopen.ui.voice :as voice]
            [hyperopen.views.account-equity.format :as equity-format]
            [hyperopen.views.account-equity.metrics :as equity-metrics]
            [hyperopen.views.degen.illustrations :as illustrations]
            [hyperopen.wallet.core :as wallet]))

(def tips
  ["If it's red, buy. If it's green, buy more. If it nukes, screenshot."
   "If it's green, you're a genius. If it's red, it's the algorithms."
   "Zoom out until the chart looks good. Then trade on that timeframe."
   "A stop loss is just admitting defeat in advance."
   "Never sell. That way you never realize a loss. Genius."])

(def quotes
  [{:q "Risk it for the biscuit." :a "Degen Sun Tzu"}
   {:q "Fortune favors the degen." :a "Ancient CT Proverb"}
   {:q "It's only a loss if you check the app." :a "Anonymous Whale"}
   {:q "Buy high, sell never." :a "The Tao of HODL"}])

(defn- day-rotation
  [coll now-ms offset]
  (nth coll (mod (+ (js/Math.floor (/ now-ms 86400000)) offset)
                 (count coll))))

(defn daily-tip
  "Deterministic tip-of-the-day; `now-ms` injectable for tests. `offset`
   (the RESET LIFE counter) re-rolls to the next tip."
  ([] (daily-tip (.now js/Date) 0))
  ([now-ms] (daily-tip now-ms 0))
  ([now-ms offset]
   (day-rotation tips now-ms offset)))

(defn daily-quote
  "Deterministic quote-of-the-day; rotates with RESET LIFE like the tip."
  ([] (daily-quote (.now js/Date) 0))
  ([now-ms offset]
   (day-rotation quotes now-ms offset)))

(defn liq-risk
  "Margin-ratio (0..1) into a parody risk readout."
  [ratio]
  (cond
    (and (number? ratio) (>= ratio 0.4))
    {:text "VERY HIGH 😬" :hint "(good luck)" :class "text-ho-sell"}

    (and (number? ratio) (>= ratio 0.15))
    {:text "SPICY 🌶️" :hint "(watch it)" :class "text-ho-warn"}

    (and (number? ratio) (pos? ratio))
    {:text "MEH 😴" :hint "(do more)" :class "text-ho-text"}

    :else
    {:text "NONE" :hint "(boring)" :class "text-ho-text-dim"}))

(defn market-vibes
  "24h change percent into market vibes."
  [pct]
  (cond
    (and (number? pct) (>= pct 2))
    {:text "BULLISH AF 🚀🚀" :class "text-ho-buy"}

    (and (number? pct) (<= pct -2))
    {:text "BEARISH AF 💀" :class "text-ho-sell"}

    (number? pct)
    {:text "CRAB MARKET 🦀" :class "text-ho-warn"}

    :else
    {:text "NO VIBES" :class "text-ho-text-dim"}))

(defn feeling
  "Unrealized PNL into the feeling gauge."
  [unrealized-pnl]
  (cond
    (and (number? unrealized-pnl) (pos? unrealized-pnl))
    {:status "AMAZING 🤑" :meter "▰▰▰▰▰" :class "text-ho-buy"}

    (and (number? unrealized-pnl) (neg? unrealized-pnl))
    {:status "TERRIBLE 💩" :meter "▰▱▱▱▱" :class "text-ho-sell"}

    (number? unrealized-pnl)
    {:status "NUMB 😶" :meter "▰▰▱▱▱" :class "text-ho-text-dim"}

    :else
    {:status "NUMB 😶" :meter "▱▱▱▱▱" :class "text-ho-text-dim"}))

(defn- change-24h-pct
  [state]
  (let [raw (get-in state [:active-assets :contexts (:active-asset state) :change24hPct])
        n (js/parseFloat raw)]
    (when-not (js/isNaN n) n)))

(defn- stat-card
  [{:keys [data-role label value value-class hint icon]}]
  [:div {:class ["flex" "items-center" "gap-2.5" "rounded-lg"
                 "border" "border-ho-border" "bg-ho-surface" "px-3.5" "py-2"
                 "min-w-[8.5rem]" "shrink-0"]
         :data-role data-role}
   icon
   [:div {:class ["flex" "flex-col" "justify-center" "gap-0.5"]}
    [:span {:class ["text-xs" "uppercase" "tracking-[0.08em]"
                    "text-ho-text-muted" "whitespace-nowrap"]}
     label]
    [:span {:class (conj ["text-sm" "font-bold" "whitespace-nowrap"]
                         (or value-class "text-ho-text"))}
     value]
    (when hint
      [:span {:class ["text-xs" "text-ho-text-dim" "whitespace-nowrap"]}
       hint])]])

(defn- congrats-card
  [state]
  (let [who (or (wallet/short-addr (get-in state [:wallet :address]))
                "DEGEN")]
    [:div {:class ["flex" "flex-col" "justify-center" "gap-0.5" "rounded-lg"
                   "border" "border-dashed" "border-ho-warn" "bg-ho-surface"
                   "px-3.5" "py-2" "min-w-[16rem]" "flex-1" "shrink-0"]
           :data-role "degen-congrats-card"}
     [:span {:class ["text-xs" "font-bold" "text-ho-warn" "whitespace-nowrap"]}
      (str "🎉 CONGRATS " who "! 🎉")]
     [:span {:class ["text-xs" "text-ho-warn/80" "whitespace-nowrap"]}
      "You are in the TOP 100% of degens!"]]))

(defn stats-strip
  "Parody stat strip over the trade surface; real numbers where available."
  [state]
  (when (voice/degen? state)
    (let [metrics (equity-metrics/account-equity-metrics state)
          ratio (or (:unified-account-ratio metrics)
                    (:cross-margin-ratio metrics))
          pnl (:pnl-info metrics)
          risk (liq-risk ratio)
          vibes (market-vibes (change-24h-pct state))]
      [:div {:class ["flex" "items-stretch" "gap-2" "px-2" "pt-2"
                     "overflow-x-auto" "scrollbar-hide"]
             :data-role "degen-stats-strip"}
       (stat-card {:data-role "degen-stat-total-value"
                   :label "Total Value (LOL)"
                   :value (equity-format/display-currency
                           (:account-value-display metrics))
                   :hint "(number, allegedly)"
                   :icon [:span {:class ["shrink-0"]}
                          (illustrations/pepe "w-9")]})
       (stat-card {:data-role "degen-stat-pnl"
                   :label "Unrealized P&L (pray)"
                   :value (:text pnl)
                   :value-class (:class pnl)
                   :hint "(probably fake)"})
       (stat-card {:data-role "degen-stat-liq-risk"
                   :label "Liquidation Risk"
                   :value (:text risk)
                   :value-class (:class risk)
                   :hint (:hint risk)})
       (stat-card {:data-role "degen-stat-number-go-up"
                   :label "Number Go Up?"
                   :value "¯\\_(ツ)_/¯"
                   :value-class "text-ho-text-secondary"})
       (stat-card {:data-role "degen-stat-vibes"
                   :label "Market Vibes"
                   :value (:text vibes)
                   :value-class (:class vibes)})
       (stat-card {:data-role "degen-stat-server"
                   :label "Server Status"
                   :value "WHO KNOWS"
                   :value-class "text-ho-text-secondary"
                   :hint "(vibes only)"})
       [:div {:class ["flex" "items-center" "rounded-lg" "border" "border-dashed"
                      "border-ho-warn" "bg-ho-surface" "px-3.5" "py-2" "shrink-0"]
              :data-role "degen-nfa-card"}
        [:span {:class ["animate-pulse" "text-xs" "font-bold" "text-ho-warn"
                        "whitespace-nowrap"]}
         "NOT FINANCIAL ADVICE! 🤡"]]
       (congrats-card state)])))

(def ^:private marker-style
  {:font-family "var(--font-marker)"})

(defn- doodle-label
  [classes text]
  [:span {:class (into ["text-base" "font-bold" "leading-none" "whitespace-nowrap"]
                       classes)
          :style marker-style}
   text])

(defn chart-doodles
  "Decorative hand-drawn annotations over the price chart. Absolutely
   positioned, pointer-events-none, never intercepts chart interaction."
  [state]
  (when (voice/degen? state)
    [:div {:class ["pointer-events-none" "absolute" "inset-0" "z-[5]"
                   "hidden" "lg:block" "opacity-80"]
           :data-role "degen-chart-doodles"}
     [:div {:class ["absolute" "left-[8%]" "top-[40%]" "w-[26%]" "text-ho-buy"]}
      [:svg {:viewBox "0 0 120 60"
             :class ["w-full"]
             :fill "none"}
       [:path {:d "M6 52 C 36 50, 64 38, 102 12"
               :stroke "currentColor"
               :stroke-width "3"
               :stroke-linecap "round"
               :vector-effect "non-scaling-stroke"}]
       [:path {:d "M88 10 L 104 11 L 96 24"
               :stroke "currentColor"
               :stroke-width "3"
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :vector-effect "non-scaling-stroke"}]]
      (doodle-label ["text-ho-buy"] "seems good")]
     [:div {:class ["absolute" "left-[52%]" "top-[10%]" "w-[9%]" "text-ho-buy"]}
      [:svg {:viewBox "0 0 100 60"
             :class ["w-full"]
             :fill "none"}
       [:ellipse {:cx "50" :cy "30" :rx "44" :ry "24"
                  :stroke "currentColor"
                  :stroke-width "3"
                  :vector-effect "non-scaling-stroke"}]]]
     [:div {:class ["absolute" "right-[6%]" "top-[8%]" "w-[12%]" "text-ho-sell"
                    "flex" "flex-col" "items-end" "gap-1"]}
      (doodle-label ["text-ho-sell" "text-lg"] "uh oh")
      [:svg {:viewBox "0 0 80 70"
             :class ["w-2/3"]
             :fill "none"}
       [:path {:d "M70 8 C 50 18, 36 34, 26 56"
               :stroke "currentColor"
               :stroke-width "3"
               :stroke-linecap "round"
               :vector-effect "non-scaling-stroke"}]
       [:path {:d "M20 42 L 25 58 L 40 52"
               :stroke "currentColor"
               :stroke-width "3"
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :vector-effect "non-scaling-stroke"}]]]
     [:div {:class ["absolute" "left-[10%]" "top-[64%]" "w-[58%]" "text-ho-warn"]}
      [:div {:class ["flex" "items-baseline" "gap-2"]}
       (doodle-label ["text-ho-warn"] "MAGIC LINE ✨")]
      [:svg {:viewBox "0 0 400 8"
             :preserveAspectRatio "none"
             :class ["w-full" "h-2"]
             :fill "none"}
       [:path {:d "M2 4 L 398 4"
               :stroke "currentColor"
               :stroke-width "3"
               :stroke-dasharray "14 10"
               :stroke-linecap "round"
               :vector-effect "non-scaling-stroke"}]]]
     [:div {:class ["absolute" "right-[8%]" "bottom-[24%]" "w-[8%]" "text-ho-info"
                    "flex" "flex-col" "items-center" "gap-1"]}
      [:svg {:viewBox "0 0 100 90"
             :class ["w-3/4"]
             :fill "none"}
       [:path {:d "M22 30 L 14 8 L 38 18"
               :stroke "currentColor"
               :stroke-width "3"
               :stroke-linejoin "round"
               :vector-effect "non-scaling-stroke"}]
       [:path {:d "M78 30 L 86 8 L 62 18"
               :stroke "currentColor"
               :stroke-width "3"
               :stroke-linejoin "round"
               :vector-effect "non-scaling-stroke"}]
       [:circle {:cx "50" :cy "50" :r "34"
                 :stroke "currentColor"
                 :stroke-width "3"
                 :vector-effect "non-scaling-stroke"}]
       [:circle {:cx "38" :cy "44" :r "3" :fill "currentColor"}]
       [:circle {:cx "62" :cy "44" :r "3" :fill "currentColor"}]
       [:path {:d "M44 60 Q 50 66, 56 60"
               :stroke "currentColor"
               :stroke-width "3"
               :stroke-linecap "round"
               :vector-effect "non-scaling-stroke"}]
       [:path {:d "M10 50 L 30 52 M10 62 L 30 58 M90 50 L 70 52 M90 62 L 70 58"
               :stroke "currentColor"
               :stroke-width "2"
               :stroke-linecap "round"
               :vector-effect "non-scaling-stroke"}]]
      (doodle-label ["text-ho-info"] "trust me bro")]]))

(defn top-gainer
  "Best 24h performer from real market data; nil when no markets carry a
   numeric change."
  [state]
  (let [markets (vals (get-in state [:asset-selector :market-by-key] {}))
        gainers (keep (fn [market]
                        (let [pct (js/parseFloat (:change24hPct market))]
                          (when-not (js/isNaN pct)
                            (assoc market :degen/pct pct))))
                      markets)]
    (when (seq gainers)
      (apply max-key :degen/pct gainers))))

(defn- widget-card
  [{:keys [data-role border-class title]} & body]
  [:div {:class (conj ["flex" "flex-col" "gap-1.5" "rounded-lg" "border"
                       "bg-ho-surface" "px-3.5" "py-3"]
                      (or border-class "border-ho-border"))
         :data-role data-role}
   [:span {:class ["text-xs" "font-bold" "uppercase" "tracking-[0.08em]"
                   "text-ho-text"]}
    title]
   (into [:div {:class ["flex" "flex-col" "gap-1"]}] body)])

(defn widgets-row
  "Bottom row of parody widget cards on the trade route."
  [state]
  (when (voice/degen? state)
    (let [metrics (equity-metrics/account-equity-metrics state)
          mood (feeling (:unrealized-pnl metrics))
          shill (top-gainer state)
          lives (get-in state [:degen :life-resets] 0)]
      [:div {:class ["hidden" "lg:grid" "grid-cols-2" "xl:grid-cols-5" "gap-2"
                     "px-2" "py-2" "relative" "z-10" "bg-ho-bg"]
             :data-role "degen-widgets-row"}
       (widget-card {:data-role "degen-widget-shill"
                     :border-class "border-ho-warn"
                     :title "Shill of the Day 🗣️"}
                    (if shill
                      [:div {:class ["flex" "items-start" "gap-2"]}
                       [:span {:class ["shrink-0" "pt-0.5"]}
                        (illustrations/doge "w-10" {:shades? true})]
                       [:div {:class ["flex" "flex-col" "gap-1"]}
                        [:button {:type "button"
                                  :class ["self-start" "text-sm" "font-bold"
                                          "text-ho-warn" "hover:text-ho-accent-hi"
                                          "transition-colors"]
                                  :data-role "degen-shill-select"
                                  :on {:click [[:actions/select-asset-by-market-key (:key shill)]]}}
                         (str (or (:coin shill) (:key shill))
                              " +" (.toFixed (:degen/pct shill) 2) "%")]
                        [:p {:class ["text-xs" "text-ho-text-secondary" "leading-relaxed"]}
                         "Definitely not a trap. (not financial advice, obviously)"]]]
                      [:p {:class ["text-xs" "text-ho-text-secondary"]}
                       "Nothing pumping. Suspicious."]))
       (widget-card {:data-role "degen-widget-tip"
                     :border-class "border-ho-info"
                     :title "Degen Tip 💡"}
                    [:p {:class ["text-xs" "text-ho-text-secondary" "leading-relaxed"]}
                     (daily-tip (.now js/Date) lives)])
       (widget-card {:data-role "degen-widget-whale"
                     :border-class "border-ho-info"
                     :title "Whale Watch 🐋"}
                    [:div {:class ["flex" "items-start" "gap-2"]}
                     [:span {:class ["shrink-0"]}
                      (illustrations/whale "w-12")]
                     [:div {:class ["flex" "flex-col" "gap-1"]}
                      [:p {:class ["text-xs" "text-ho-text-secondary" "leading-relaxed"]}
                       "They bought. You exit liquidity."]
                      [:p {:class ["text-xs" "font-bold" "text-ho-info"]}
                       "Nice."]]])
       (widget-card {:data-role "degen-widget-motivation"
                     :title "Daily Motivation 🐕"}
                    [:div {:class ["flex" "items-start" "gap-2"]}
                     [:span {:class ["shrink-0"]}
                      (illustrations/doge "w-10")]
                     (let [{:keys [q a]} (daily-quote (.now js/Date) lives)]
                       [:div {:class ["flex" "flex-col" "gap-1"]}
                        [:p {:class ["text-xs" "text-ho-text-secondary" "leading-relaxed"]}
                         "Such leverage. Much risk. Very degen. Wow."]
                        [:p {:class ["text-xs" "text-ho-text-dim" "italic" "leading-snug"]
                             :style marker-style}
                         (str "“" q "” — " a)]])])
       (widget-card {:data-role "degen-widget-feeling"
                     :title "Feeling Gauge"}
                    [:div {:class ["flex" "items-center" "gap-2.5"]}
                     (illustrations/feeling-gauge-dial (:unrealized-pnl metrics))
                     [:span {:class (conj ["text-sm" "font-bold"] (:class mood))}
                      (:status mood)]]
                    [:p {:class ["text-xs" "text-ho-text-dim"]}
                     "How's it going? (derived from real P&L)"]
                    [:div {:class ["flex" "items-center" "justify-between" "gap-2" "pt-0.5"]}
                     [:button {:type "button"
                               :class ["rounded-md" "border" "border-ho-sell"
                                       "px-2" "py-1" "text-xs" "font-bold"
                                       "text-ho-sell" "hover:bg-ho-sell-soft"
                                       "transition-colors"]
                               :data-role "degen-reset-life"
                               :on {:click [[:actions/reset-degen-life]]}}
                      "RESET LIFE"]
                     (when (pos? lives)
                       [:span {:class ["text-xs" "text-ho-text-dim"]}
                        (str "Lives used: " lives)])])])))
