(ns hyperopen.views.portfolio.optimize.tear-sheet-parts
  "Pieces of the hypothetical tear sheet page: option pills, the method
  controls, data coverage and holdings."
  (:require [hyperopen.views.portfolio.optimize.tear-sheet-card :as card]
            [hyperopen.views.vaults.detail.activity.performance-metrics :as metrics-view]))

(defn set-option
  [option value]
  [[:actions/set-portfolio-optimizer-tear-sheet-option option value]])

(defn pill
  [{:keys [label active? on data-role]}]
  [:button {:type "button"
            :aria-pressed (boolean active?)
            :class (into ["min-h-8" "min-w-10" "rounded-md" "px-2.5" "text-xs" "font-medium" "num"]
                         (if active?
                           ["bg-[#a493ff]/20" "text-trading-text"]
                           ["text-trading-muted" "hover:text-trading-text"]))
            :data-role data-role
            :on {:click on}}
   label])

(defn format-date
  [time-ms]
  (when (number? time-ms)
    (.toLocaleDateString (js/Date. time-ms) "en-US"
                         #js {:month "short" :day "numeric" :year "numeric" :timeZone "UTC"})))

(defn spacing-label
  [gap-days]
  (cond
    (not (number? gap-days)) "--"
    (< gap-days 0.75) (str "~" (js/Math.max 1 (js/Math.round (* 24 gap-days))) "h")
    :else (str "~" (js/Math.round gap-days) "d")))

(defn- radio-card
  [{:keys [label sub checked? on data-role]}]
  [:button {:type "button"
            :role "radio"
            :aria-checked (boolean checked?)
            :class (into ["w-full" "rounded-lg" "border" "px-3" "py-2.5" "text-left"]
                         (if checked?
                           ["border-[#a493ff]/70" "bg-[#a493ff]/10"]
                           ["border-base-300" "hover:bg-base-200/40"]))
            :data-role data-role
            :on {:click on}}
   [:span {:class ["flex" "items-center" "gap-2.5" "text-sm" "text-trading-text"]}
    [:span {:class (into ["h-3.5" "w-3.5" "shrink-0" "rounded-full" "border"]
                         (if checked?
                           ["border-[4px]" "border-[#a493ff]"]
                           ["border-trading-muted"]))}]
    label]
   [:span {:class ["mt-1" "block" "pl-6" "text-xs" "leading-5" "text-trading-muted"]} sub]])

(def ^:private rebalance-choices
  [{:value :daily :label "Daily, back to target"
    :sub "Holds the optimizer's weights exactly, matching what it measured."}
   {:value :monthly :label "Monthly"
    :sub "Weights drift between month-ends."}
   {:value :hold :label "Deposit once and hold"
    :sub "No rebalancing; winners grow their share."}])

(def ^:private missing-choices
  [{:value :spread :label "Spread its weight over live assets"
    :sub "Uses the whole range; early periods hold fewer assets."}
   {:value :cash :label "Hold that weight as cash"
    :sub "Weights never change, at the cost of idle cash early on."}
   {:value :common :label "Start when every asset exists"
    :sub "Cleanest comparison; the range starts at the youngest asset's first sample."}])

(defn method-panel
  [opts]
  [:div {:class ["grid" "gap-5" "p-4" "lg:grid-cols-2"]
         :data-role "portfolio-optimizer-tear-sheet-method"}
   [:fieldset {:class ["flex" "flex-col" "gap-2"]}
    [:legend {:class ["mb-2" "text-sm" "font-medium" "text-trading-text"]} "Rebalancing"]
    (for [{:keys [value label sub]} rebalance-choices]
      ^{:key (str "rebalance-" (name value))}
      (radio-card {:label label :sub sub
                   :checked? (= value (:rebalance opts))
                   :on (set-option :rebalance value)
                   :data-role (str "portfolio-optimizer-tear-sheet-rebalance-" (name value))}))]
   [:fieldset {:class ["flex" "flex-col" "gap-2"]}
    [:legend {:class ["mb-2" "text-sm" "font-medium" "text-trading-text"]} "Before an asset existed"]
    (for [{:keys [value label sub]} missing-choices]
      ^{:key (str "missing-" (name value))}
      (radio-card {:label label :sub sub
                   :checked? (= value (:missing opts))
                   :on (set-option :missing value)
                   :data-role (str "portfolio-optimizer-tear-sheet-missing-" (name value))}))]
   [:label {:class ["flex" "min-h-11" "cursor-pointer" "items-center" "gap-3" "text-sm" "text-trading-text" "lg:col-span-2"]}
    [:input {:type "checkbox"
             :checked (boolean (:profit-share? opts))
             :class ["h-4" "w-4" "rounded" "border-base-300" "bg-base-100"]
             :data-role "portfolio-optimizer-tear-sheet-profit-share"
             :on {:change (set-option :profit-share? (not (:profit-share? opts)))}}]
    [:span "Deduct each vault leader's profit share "
     [:span {:class ["text-trading-muted"]} "(what a depositor would actually keep)"]]]])

(defn coverage-panel
  [{:keys [legs run]}]
  [:div {:class ["p-4"] :data-role "portfolio-optimizer-tear-sheet-coverage"}
   [:p {:class ["mb-3" "max-w-3xl" "text-xs" "leading-5" "text-trading-muted"]}
    "Hyperliquid publishes a fixed budget of points per vault, so the further back the range goes the coarser each vault gets; the last 30 days are dense. Vault points are converted to a share-price return (deposits and withdrawals removed), as on each vault's page."]
   [:div {:class ["overflow-x-auto"]}
    [:div {:class ["min-w-[560px]"]}
     [:div {:class ["grid" "grid-cols-[minmax(0,2fr)_repeat(3,minmax(90px,1fr))]" "gap-3" "border-b" "border-base-300" "pb-2"
                    "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
      [:span "Asset"] [:span {:class ["text-right"]} "First sample"]
      [:span {:class ["text-right"]} "Spacing"] [:span {:class ["text-right"]} "In range from"]]
     (for [{:keys [id label first-ms gap-days joins-in-window? has-history?]} legs]
       ^{:key (str "coverage-" id)}
       [:div {:class ["grid" "min-h-9" "grid-cols-[minmax(0,2fr)_repeat(3,minmax(90px,1fr))]" "items-center" "gap-3"
                      "border-b" "border-base-300/50" "text-sm"]}
        [:span {:class ["truncate" "text-trading-text"]} label]
        [:span {:class ["num" "text-right" "text-trading-muted"]} (or (format-date first-ms) "--")]
        [:span {:class (into ["num" "text-right"]
                             (if (and (number? gap-days) (> gap-days 1.5)) ["text-[#e2b545]"] ["text-trading-muted"]))}
         (if has-history? (spacing-label gap-days) "no history")]
        [:span {:class ["num" "text-right" "text-trading-muted"]}
         (if joins-in-window? (format-date first-ms) (format-date (:start-ms run)))]])]]])

(defn holdings-panel
  [{:keys [legs]}]
  [:div {:class ["overflow-x-auto" "p-4"] :data-role "portfolio-optimizer-tear-sheet-holdings"}
   [:div {:class ["min-w-[640px]"]}
    [:div {:class ["grid" "grid-cols-[minmax(0,2fr)_repeat(4,minmax(90px,1fr))]" "gap-3" "border-b" "border-base-300" "pb-2"
                   "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
     [:span "Asset"] [:span {:class ["text-right"]} "Target"] [:span {:class ["text-right"]} "Its return"]
     [:span {:class ["text-right"]} "Added"] [:span {:class ["text-right"]} "Spacing"]]
    (for [{:keys [id label weight own-return contribution gap-days has-history?]} legs]
      ^{:key (str "holding-" id)}
      [:div {:class ["grid" "min-h-10" "grid-cols-[minmax(0,2fr)_repeat(4,minmax(90px,1fr))]" "items-center" "gap-3"
                     "border-b" "border-base-300/50" "text-sm"]
             :data-role "portfolio-optimizer-tear-sheet-holding-row"}
       [:span {:class ["truncate" "text-trading-text"]} label]
       [:span {:class ["num" "text-right" "text-trading-text"]} (str (.toFixed (* 100 weight) 1) "%")]
       [:span {:class ["num" "text-right" (if (and (number? own-return) (neg? own-return)) "text-trading-red" "text-trading-green")]}
        (metrics-view/format-metric-value :percent own-return)]
       [:span {:class ["num" "text-right" (if (and (number? contribution) (neg? contribution)) "text-trading-red" "text-trading-green")]}
        (metrics-view/format-metric-value :percent contribution)]
       [:span {:class ["num" "text-right" "text-trading-muted"]} (if has-history? (spacing-label gap-days) "no history")]])]])

(defn live-strip
  "Step area of how many assets have history over the window, with join
  dates listed underneath."
  [{:keys [run legs]}]
  (let [counts (:live-counts run)
        total (count legs)
        t0 (first (first counts))
        t1 (first (peek counts))
        span (max 1 (- t1 t0))
        x #(.toFixed (* 1000 (/ (- % t0) span)) 1)
        y #(.toFixed (- 28 (* 26 (/ % (max 1 total)))) 1)
        path (str "M0 28"
                  (apply str (map (fn [[t n]] (str " L" (x t) " " (y n))) counts))
                  " L1000 28 Z")
        by-id (into {} (map (juxt :id :label)) legs)
        joins (:joins run)]
    [:div {:class ["mt-3"] :data-role "portfolio-optimizer-tear-sheet-live-strip"}
     [:div {:class ["flex" "justify-between" "text-xs" "text-trading-muted"]}
      [:span "Assets with history"]
      [:span {:class ["num"]} (str (second (first counts)) " → " (second (peek counts)) " of " total)]]
     [:svg {:viewBox "0 0 1000 28" :preserveAspectRatio "none" :class ["mt-1" "block" "h-7" "w-full"]
            :role "img" :aria-label "Number of assets with history over the range"}
      [:path {:d path :fill "rgba(164,147,255,0.22)" :stroke "#a493ff" :stroke-width "1"
              :vector-effect "non-scaling-stroke"}]]
     (when (seq joins)
       [:div {:class ["mt-1.5" "flex" "flex-wrap" "gap-x-4" "gap-y-1" "text-xs" "text-[#d6cfff]"]}
        (for [{:keys [id time-ms]} joins]
          ^{:key (str "join-" id)}
          [:span (str (get by-id id id) " joins " (format-date time-ms))])])]))

(defn hero-stat
  [label value sub tone data-role]
  [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-200/30" "px-4" "py-3"]}
   [:div {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.14em]" "text-trading-muted"]} label]
   [:div {:class (into ["num" "mt-1.5" "text-2xl"] tone) :data-role data-role} value]
   [:div {:class ["mt-1" "text-xs" "text-trading-muted"]} sub]])

(defn return-label
  "\"1Y return\", or \"Return since <date>\" when the method starts the
  backtest later than the range (start when every asset exists)."
  [{:keys [run window]} range-label]
  (if (> (:start-ms run) (+ (:start-ms window) (* 2 24 60 60 1000)))
    (str "Return since " (format-date (:start-ms run)))
    (str range-label " return")))

(defn hero-stats
  [model stats range-label sparse?]
  (let [ret (:cumulative-return stats)]
    [:div {:class ["mt-5" "grid" "grid-cols-2" "gap-3" "lg:grid-cols-4"]}
     (hero-stat (return-label model range-label) (metrics-view/format-metric-value :percent ret)
                "cumulative, after the method below"
                [(if (and (number? ret) (neg? ret)) "text-trading-red" "text-trading-green")]
                "portfolio-optimizer-tear-sheet-return")
     (hero-stat "Volatility" (card/format-volatility (:volatility stats))
                (if sparse? "annualized · estimated" "annualized")
                ["text-trading-text"] "portfolio-optimizer-tear-sheet-volatility")
     (hero-stat "Sharpe" (metrics-view/format-metric-value :ratio (:sharpe stats))
                (str "Sortino " (metrics-view/format-metric-value :ratio (:sortino stats)))
                ["text-trading-text"] "portfolio-optimizer-tear-sheet-sharpe")
     (hero-stat "Max drawdown" (metrics-view/format-metric-value :percent (:max-drawdown stats))
                (str "longest " (metrics-view/format-metric-value :integer (:longest-dd-days stats)) " days under water")
                ["text-trading-red"] "portfolio-optimizer-tear-sheet-drawdown")]))

(defn- benchmark-chip
  [{:keys [value label stroke builtin? available?]}]
  [:span {:class ["inline-flex" "min-h-8" "items-center" "gap-1.5" "rounded-full" "border" "border-base-content/30"
                  "pl-3" "pr-1" "text-xs" "text-trading-text"]
          :data-role (str "portfolio-optimizer-tear-sheet-benchmark-chip-" value)}
   [:span {:class ["h-0.5" "w-3"] :style {:background-color stroke}}]
   (if available? label (str label " · loading"))
   [:button {:type "button"
             :aria-label (str "Remove " label)
             :class ["inline-flex" "h-6" "w-6" "items-center" "justify-center" "rounded-full" "text-trading-muted"
                     "hover:bg-base-200" "hover:text-trading-text"]
             :on {:click (if builtin?
                           (set-option :equal-weight? false)
                           (set-option :remove-benchmark value))}}
    [:svg {:viewBox "0 0 16 16" :class ["h-3" "w-3"] :fill "none" :stroke "currentColor"
           :stroke-width "1.5" :aria-hidden true}
     [:path {:d "M4 4l8 8M12 4l-8 8"}]]]])

(defn compare-row
  "Benchmark chips plus a search over every market and vault the vault page
  offers as a benchmark."
  [{:keys [benchmarks]} opts {:keys [query open? full? candidates]}]
  [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"] :data-role "portfolio-optimizer-tear-sheet-compare"}
   [:span {:class ["mr-1" "text-xs" "text-trading-muted"]} "Compare"]
   [:span {:class ["inline-flex" "min-h-8" "items-center" "gap-1.5" "rounded-full" "border" "border-[#a493ff]/70"
                   "px-3" "text-xs" "text-trading-text"]}
    [:span {:class ["h-0.5" "w-3" "bg-[#a493ff]"]}]
    "This target"]
   (for [b benchmarks]
     ^{:key (str "benchmark-chip-" (:value b))}
     (benchmark-chip b))
   ;; Always a node (hidden when unused): a nil hole next to keyed siblings
   ;; freezes Replicant's renderer.
   (if (:equal-weight? opts)
     [:span {:class ["hidden"]}]
     [:button {:type "button"
               :class ["min-h-8" "rounded-full" "border" "border-dashed" "border-base-300" "px-3" "text-xs" "text-trading-muted"
                       "hover:text-trading-text"]
               :data-role "portfolio-optimizer-tear-sheet-add-equal-weight"
               :on {:click (set-option :equal-weight? true)}}
      "+ Equal weight"])
   [:div {:class ["relative" "min-w-[220px]" "flex-[1_1_260px]"]}
    [:input {:type "search"
             :value (or query "")
             :disabled (boolean full?)
             :placeholder (if full? "Remove a benchmark to add another" "Add benchmark: any perp, spot market or vault")
             :aria-label "Search benchmarks"
             :autocomplete "off"
             :spellcheck false
             :class ["h-8" "w-full" "rounded-full" "border" "border-base-300" "bg-base-100" "px-3" "text-xs" "text-trading-text"]
             :data-role "portfolio-optimizer-tear-sheet-benchmark-search"
             :on {:input [[:actions/set-portfolio-optimizer-tear-sheet-option :benchmark-search [:event.target/value]]]
                  :focus (set-option :benchmark-open? true)
                  :blur (set-option :benchmark-open? false)
                  :keydown [[:actions/set-portfolio-optimizer-tear-sheet-option :benchmark-key [:event/key]]]}}]
    (when (and open? (not full?))
      [:div {:class ["absolute" "left-0" "right-0" "top-full" "z-40" "mt-1" "max-h-60" "overflow-y-auto" "rounded-lg"
                     "border" "border-base-300" "bg-base-100" "p-1" "shadow-xl"]
             :role "listbox"
             :data-role "portfolio-optimizer-tear-sheet-benchmark-suggestions"}
       (if (seq candidates)
         (for [{:keys [value label]} candidates]
           ^{:key (str "benchmark-suggestion-" value)}
           [:button {:type "button"
                     :role "option"
                     :class ["flex" "min-h-8" "w-full" "items-center" "rounded" "px-2" "text-left" "text-xs" "text-trading-text"
                             "hover:bg-base-200"]
                     :data-role (str "portfolio-optimizer-tear-sheet-benchmark-suggestion-" value)
                     ;; mousedown: fires before the input's blur closes the list.
                     :on {:mousedown (set-option :add-benchmark value)}}
            label])
         [:div {:class ["px-2" "py-1.5" "text-xs" "text-trading-muted"]} "No matching markets or vaults."])])]])
