(ns hyperopen.views.portfolio.optimize.setup-v4-universe
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.query :as asset-query]))

(def ^:private eyebrow-class
  ["font-mono" "text-[0.625rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-muted"])

(def ^:private section-title-class
  ["text-[0.6875rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-text"])

(def ^:private input-class
  ["w-full" "border" "border-base-300" "bg-base-100/80" "px-2" "py-1.5"
   "font-mono" "text-[0.6875rem]" "font-medium" "outline-none" "focus:border-warning/70"])

(defn- normalized-text
  [value]
  (some-> value str str/trim))

(defn- selected-instrument-ids
  [universe]
  (into #{} (keep :instrument-id) universe))

(defn- market-label
  [market]
  (or (normalized-text (:symbol market))
      (normalized-text (:coin market))
      (normalized-text (:key market))
      "Unknown Market"))

(defn- display-name
  [value]
  (case (some-> value str str/upper-case)
    "BTC" "Bitcoin"
    "ETH" "Ether"
    "SOL" "Solana"
    "HYPE" "Hyperliquid"
    "ARB" "Arbitrum"
    "LINK" "Chainlink"
    "USDC" "USD Coin"
    "PURR/USDC" "Purr"
    (or (normalized-text value) "--")))

(defn- market-display-name
  [market]
  (or (normalized-text (:name market))
      (normalized-text (:full-name market))
      (display-name (:coin market))))

(defn- candidate-markets
  [state universe query]
  (let [selected-ids (selected-instrument-ids universe)
        query* (or (normalized-text query) "")
        query-upper (str/upper-case query*)]
    (->> (asset-query/filter-and-sort-assets
          (get-in state [:asset-selector :markets])
          query*
          :volume
          :desc
          #{}
          false
          false
          :all)
         (filter #(and (normalized-text (:key %))
                       (normalized-text (:coin %))
                       (:market-type %)
                       (not (contains? selected-ids (:key %)))))
         (sort-by (fn [market]
                    (let [symbol-upper (some-> (market-label market) str/upper-case)
                          coin-upper (some-> (:coin market) str str/upper-case)]
                      [(if (or (= query-upper symbol-upper)
                               (= query-upper coin-upper))
                         0
                         1)
                       (if (= :spot (:market-type market)) 0 1)])))
         (take 6)
         vec)))

(defn- finite-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value) value)

    (string? value)
    (let [parsed (js/Number value)]
      (when (and (number? parsed)
                 (js/isFinite parsed))
        parsed))

    :else nil))

(defn- compact-usd
  [value]
  (if-let [n (finite-number value)]
    (cond
      (>= n 1000000000) (str "$" (.toFixed (/ n 1000000000) 1) "B")
      (>= n 1000000) (str "$" (.toFixed (/ n 1000000) 0) "M")
      (>= n 1000) (str "$" (.toFixed (/ n 1000) 0) "K")
      :else (str "$" (.toFixed n 0)))
    "--"))

(defn- adv-label
  [market]
  (compact-usd (or (:volume24h market)
                   (:volume market)
                   (:openInterest market))))

(defn- liquidity-label
  [market-or-instrument]
  (let [value (or (:liquidity market-or-instrument)
                  (:liquidity-label market-or-instrument)
                  (:depth market-or-instrument))]
    (or (normalized-text value)
        (if-let [volume (finite-number (or (:volume24h market-or-instrument)
                                           (:volume market-or-instrument)))]
          (if (>= volume 50000000) "deep" "medium")
          "medium"))))

(defn- history-label
  [state coin]
  (if (seq (get-in state [:portfolio :optimizer :history-data :candle-history-by-coin coin]))
    "sufficient"
    "missing"))

(defn- tag
  ([label tone]
   (tag label tone nil))
  ([label tone extra-class]
   [:span {:class (cond-> ["border" "px-1.5" "py-[1px]" "font-mono"
                           "text-[0.53125rem]" "font-semibold" "uppercase"
                           "tracking-[0.12em]"]
                    (= tone :accent) (conj "border-warning/40" "text-warning")
                    (= tone :info) (conj "border-info/40" "text-info")
                    (= tone :long) (conj "border-success/40" "text-success")
                    (= tone :warn) (conj "border-warning/40" "text-warning")
                    (= tone :muted) (conj "border-base-300" "text-trading-muted")
                    extra-class (conj extra-class))
           :data-v4-chip "true"
           :data-tone (name tone)}
    label]))

(defn- market-type-tags
  [market-type]
  [:span {:class ["flex" "items-center" "gap-1"]}
   (when (= :spot market-type)
     (tag "spot" :info))
   (when (= :perp market-type)
     (tag "perp" :accent))])

(defn- selected-row
  [state instrument]
  (let [instrument-id (:instrument-id instrument)
        coin (:coin instrument)
        market-type (:market-type instrument)
        history (history-label state coin)]
    [:div {:class ["grid" "grid-cols-[18px_minmax(0,1fr)_42px_72px_48px_20px]"
                   "items-center" "gap-2" "border-b" "border-base-300"
                   "px-2" "py-1.5" "last:border-b-0" "hover:bg-base-200/30"]
           :data-role (str "portfolio-optimizer-universe-selected-row-" instrument-id)}
     [:span {:class ["text-warning"]} "☑"]
     [:span {:class ["min-w-0"]}
      [:span {:class ["block" "truncate" "font-mono" "text-[0.6875rem]" "font-semibold"]}
       (or coin instrument-id)]
      [:span {:class ["block" "truncate" "text-[0.65625rem]" "text-trading-muted"]}
       (display-name coin)]]
     [:span {:class ["min-w-0"]} (market-type-tags market-type)]
     [:span {:class ["min-w-0"]} (tag history (if (= "sufficient" history) :long :warn))]
     [:span {:class ["truncate" "text-[0.65625rem]" "text-trading-muted"]}
      (liquidity-label instrument)]
     [:span {:class ["text-right"]}
      [:button {:type "button"
                :class ["font-mono" "text-[0.6875rem]" "text-trading-muted" "hover:text-warning"]
                :aria-label (str "Remove " instrument-id)
                :data-role (str "portfolio-optimizer-universe-remove-" instrument-id)
                :on {:click [[:actions/remove-portfolio-optimizer-universe-instrument
                               instrument-id]]}}
       "x"]]]))

(defn- market-row
  [market idx]
  (let [market-key (:key market)
        market-type (:market-type market)
        history (:history-label market "sufficient")]
    [:div {:class ["grid" "grid-cols-[66px_minmax(0,1fr)_58px_72px_42px_44px]"
                   "items-center" "gap-2" "border-b" "border-base-300" "px-2"
                   "py-1.5" "last:border-b-0" "hover:bg-base-200/30"]
           :data-role (str "portfolio-optimizer-universe-candidate-row-" market-key)
           :data-active (when (zero? idx) "true")}
     [:span {:class ["truncate" "font-mono" "text-[0.6875rem]" "font-semibold"]}
      (market-label market)]
     [:span {:class ["truncate" "text-[0.6875rem]" "text-trading-muted"]}
      (market-display-name market)]
     (market-type-tags market-type)
     (tag history (if (= "sufficient" history) :long :warn))
     [:span {:class ["font-mono" "text-[0.6rem]" "text-trading-muted" "text-right"]}
      (adv-label market)]
     [:button {:type "button"
               :class ["text-right" "font-mono" "text-[0.65625rem]" "font-semibold"
                       "text-warning" "hover:text-warning"]
               :data-role (str "portfolio-optimizer-universe-add-" market-key)
               :on {:click [[:actions/add-portfolio-optimizer-universe-instrument market-key]]}}
      "+ add"]]))

(defn- quick-chip
  [state selected-ids symbol]
  (let [market (some #(when (= symbol (some-> (:coin %) str str/upper-case)) %)
                     (get-in state [:asset-selector :markets]))
        market-key (:key market)
        disabled? (or (not market-key)
                      (contains? selected-ids market-key))]
    [:button {:type "button"
              :class ["border" "border-base-300" "px-1.5" "py-[1px]"
                      "font-mono" "text-[0.59375rem]" "uppercase"
                      "tracking-[0.08em]" "text-trading-muted"
                      "disabled:cursor-not-allowed" "disabled:opacity-40"
                      "enabled:hover:border-warning/40" "enabled:hover:text-warning"]
              :disabled disabled?
              :data-v4-chip "true"
              :data-role (when market-key
                           (str "portfolio-optimizer-universe-quick-add-" market-key))
              :on (when market-key
                    {:click [[:actions/add-portfolio-optimizer-universe-instrument market-key]]})}
     (str "+ " symbol)]))

(defn- selected-table
  [state universe]
  [:div {:class ["mt-2" "border" "border-base-300" "bg-base-100/50"]}
   [:div {:class ["flex" "items-center" "border-b" "border-base-300" "px-2" "py-1.5"]}
    [:span {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.12em]"
                    "text-trading-muted"]}
     (str (count universe) " included")]
    [:span {:class ["ml-auto" "font-mono" "text-[0.6rem]" "text-trading-muted/70"]}
     "cap: 25 assets"]]
   (if (seq universe)
     (into [:div {:class ["text-xs"]}]
           (map #(selected-row state %) universe))
     [:p {:class ["px-2" "py-3" "text-xs" "text-trading-muted"]}
      "No instruments selected yet."])])

(defn universe-section
  [state draft]
  (let [universe (vec (or (:universe draft) []))
        selected-ids (selected-instrument-ids universe)
        search-query (or (get-in state [:portfolio-ui :optimizer :universe-search-query]) "")
        markets (candidate-markets state universe search-query)
        searching? (seq (normalized-text search-query))]
    [:section {:class ["border" "border-base-300" "bg-base-100/90" "p-3"]
               :data-role "portfolio-optimizer-universe-panel"}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3" "border-b"
                    "border-base-300" "pb-2"]}
      [:p {:class section-title-class}
       [:span {:class ["mr-2" "font-mono" "text-trading-muted/70"]} "01"]
       "Universe"]
      [:span {:class ["font-mono" "text-[0.65625rem]" "uppercase" "tracking-[0.08em]"
                      "text-trading-muted"]}
       (str (count universe) " included")]]
     [:div {:class ["mt-3" "grid" "grid-cols-3" "border" "border-base-300" "text-center"
                    "text-[0.65625rem]" "font-medium" "uppercase"
                    "tracking-[0.04em]" "text-trading-muted"]}
      [:button {:type "button"
                :class ["border-r" "border-base-300" "px-2" "py-2" "uppercase" "hover:text-warning"]
                :data-role "portfolio-optimizer-universe-use-current"
                :on {:click [[:actions/set-portfolio-optimizer-universe-from-current]]}}
       "From holdings"
       [:span {:class ["sr-only"]} "Use Current Holdings"]]
      [:span {:class ["border-r" "border-warning/60" "bg-warning/10" "px-2" "py-2" "text-warning"]}
       "Custom"]
      [:span {:class ["px-2" "py-2" "text-trading-muted/60"]} "Index"]]
     [:div {:class ["sr-only"]} "Manual Add"]
     [:div {:class ["mt-3" "relative"]}
      [:div {:class ["flex" "items-center" "gap-1.5" "border" "px-2"
                     (if searching?
                       "border-warning/70"
                       "border-base-300")
                     "bg-base-100/80"]}
       [:span {:class ["font-mono" "text-[0.65rem]" "text-trading-muted"]} "⌕"]
       [:input {:type "search"
                :class (into input-class ["border-0" "bg-transparent" "px-0" "focus:border-0"])
                :placeholder "Search ticker or name (e.g. TIA, AVAX, Solana...)"
                :data-role "portfolio-optimizer-universe-search-input"
                :value search-query
                :on {:input [[:actions/set-portfolio-optimizer-universe-search-query
                              [:event.target/value]]]}}]
       (when searching?
         [:button {:type "button"
                   :class ["font-mono" "text-xs" "text-trading-muted" "hover:text-warning"]
                   :aria-label "Clear universe search"
                   :on {:click [[:actions/set-portfolio-optimizer-universe-search-query ""]]}}
          "x"])
       [:span {:class ["border" "border-base-300" "px-1.5" "py-[1px]"
                       "font-mono" "text-[0.55rem]" "uppercase"
                       "tracking-[0.1em]" "text-trading-muted"]}
        "↵ add"]]
      (when searching?
        (if (seq markets)
          (into [:div {:class ["mt-1" "border" "border-base-300" "bg-base-200/80"
                               "shadow-[0_12px_32px_rgba(0,0,0,0.45)]"]
                       :data-role "portfolio-optimizer-universe-search-results"}]
                (map-indexed (fn [idx market] (market-row market idx)) markets))
          [:p {:class ["mt-1" "border" "border-base-300" "bg-base-200/70" "p-2"
                       "text-xs" "text-trading-muted"]
               :data-role "portfolio-optimizer-universe-search-results-empty"}
           "No matching unused markets found."]))]
     (when-not searching?
       [:div {:class ["mt-2" "flex" "flex-wrap" "items-center" "gap-1.5"]}
        [:span {:class ["font-mono" "text-[0.59375rem]" "uppercase" "tracking-[0.1em]"
                        "text-trading-muted/70"]}
         "quick add"]
        (for [symbol ["TON" "NEAR" "INJ" "JUP" "AVAX" "DOT"]]
          (quick-chip state selected-ids symbol))])
     (selected-table state universe)
     [:div {:class ["mt-2" "font-mono" "text-[0.58rem]" "leading-5"
                    "text-trading-muted/70"]}
      "Search adds tradeable spot or perp legs. Symbols with limited history use stabilized covariance with a longer pull toward the market reference."
      [:span {:class ["sr-only"]}
       "Requires history reload after adding new assets."]]]))
