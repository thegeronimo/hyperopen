(ns hyperopen.views.portfolio.optimize.target-exposure-table
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn- format-delta-pct
  [value]
  (opt-format/format-pct-delta value
                               {:minimum-fraction-digits 1
                                :maximum-fraction-digits 1
                                :suffix ""}))

(defn- format-compact-usdc
  [value]
  (if (opt-format/finite-number? value)
    (let [abs-value (js/Math.abs value)
          sign (cond
                 (pos? value) "+"
                 (neg? value) "-"
                 :else "")]
      (cond
        (>= abs-value 1000000)
        (str sign "$"
             (.toLocaleString (/ abs-value 1000000)
                               "en-US"
                               #js {:maximumFractionDigits 1})
             "m")

        (>= abs-value 1000)
        (str sign "$"
             (.toLocaleString (/ abs-value 1000)
                               "en-US"
                               #js {:maximumFractionDigits 0})
             "k")

        :else
        (str sign "$"
             (.toLocaleString abs-value
                               "en-US"
                               #js {:maximumFractionDigits 0}))))
    "N/A"))

(defn- signed-label
  [value]
  (cond
    (and (opt-format/finite-number? value) (neg? value)) "short"
    (and (opt-format/finite-number? value) (pos? value)) "long"
    :else "flat"))

(defn- instrument-group-key
  [instrument-id]
  (let [value (str instrument-id)
        unprefixed (last (str/split value #":"))
        base (first (str/split unprefixed #"[/-]"))]
    (if (seq base) base value)))

(defn- leg-label
  [instrument-id current-weight target-weight]
  (let [value (str instrument-id)
        market-type (first (str/split value #":"))]
    (case market-type
      "spot" "spot"
      "perp" (cond
               (neg? (or target-weight 0)) "perp short"
               (pos? (or target-weight 0)) "perp long"
               (neg? (or current-weight 0)) "perp short"
               :else "perp long")
      value)))

(defn- data-role-token
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn- exposure-row
  [idx binding-instrument-ids hidden? instrument-id capital-usd current-weight target-weight]
  (let [current-notional (* (or capital-usd 0) (or current-weight 0))
        target-notional (* (or capital-usd 0) (or target-weight 0))
        delta (- (or target-weight 0) (or current-weight 0))
        binding? (contains? binding-instrument-ids instrument-id)]
    [:tr {:class (cond-> []
                  binding? (conj "bg-warning/10")
                  hidden? (conj "hidden"))
          :data-role (str "portfolio-optimizer-target-exposure-row-" idx)
          :data-binding (when binding? "true")
          :data-current-sign (signed-label current-weight)
          :data-target-sign (signed-label target-weight)}
     [:td {:class ["text-trading-muted"]} ""]
     [:td {:class ["pl-8" "text-trading-muted"]} (leg-label instrument-id current-weight target-weight)]
     [:td {:class ["font-mono" "text-right" "tabular-nums"]} (opt-format/format-pct current-weight {:minimum-fraction-digits 1 :maximum-fraction-digits 1})]
     [:td {:class ["font-mono" "text-right" "tabular-nums"]} (opt-format/format-pct target-weight {:minimum-fraction-digits 1 :maximum-fraction-digits 1})]
     [:td {:class [(if (neg? delta) "text-trading-red" "text-trading-green")
                   "font-mono" "text-right" "tabular-nums"]}
      (format-delta-pct delta)]
     [:td {:class [(if (neg? delta) "text-trading-red" "text-trading-green")
                   "font-mono" "text-right" "tabular-nums"]}
      (format-compact-usdc (- target-notional current-notional))]]))

(defn- exposure-group-row
  [asset capital-usd binding-instrument-ids rows]
  (let [current-weight (reduce + 0 (map :current-weight rows))
        target-weight (reduce + 0 (map :target-weight rows))
        delta (- target-weight current-weight)
        binding? (some #(contains? binding-instrument-ids (:instrument-id %)) rows)
        expandable? (> (count rows) 1)]
    [:tr {:class ["cursor-pointer"]
          :data-role (str "portfolio-optimizer-target-exposure-asset-"
                          (data-role-token asset))
          :data-target-sign (signed-label target-weight)}
     [:td {:class ["w-5" "font-mono" "text-trading-muted/70"]}
      (when expandable? "▾")]
     [:td {:class ["font-mono" "font-semibold" "text-trading-text"]}
      [:span {:data-role (str "portfolio-optimizer-target-exposure-group-"
                              (data-role-token asset))}
       asset]
      (when binding?
        [:span {:class ["ml-2" "border" "border-warning/50" "px-1.5" "py-0.5"
                        "font-mono" "text-[0.5rem]" "font-semibold" "uppercase"
                        "tracking-[0.08em]" "text-warning"]}
         "capped"])]
     [:td {:class ["font-mono" "text-right" "font-semibold" "tabular-nums"]} (opt-format/format-pct current-weight {:minimum-fraction-digits 1 :maximum-fraction-digits 1})]
     [:td {:class ["font-mono" "text-right" "font-semibold" "tabular-nums"]} (opt-format/format-pct target-weight {:minimum-fraction-digits 1 :maximum-fraction-digits 1})]
     [:td {:class [(if (neg? delta) "text-trading-red" "text-trading-green")
                   "font-mono" "text-right" "font-semibold" "tabular-nums"]}
      (format-delta-pct delta)]
     [:td {:class [(if (neg? delta) "text-trading-red" "text-trading-green")
                   "font-mono" "text-right" "font-semibold" "tabular-nums"]}
      (format-compact-usdc (* (or capital-usd 0) delta))]]))

(defn target-exposure-table
  [result]
  (let [capital-usd (get-in result [:rebalance-preview :capital-usd])
        ids (:instrument-ids result)
        current (:current-weights result)
        target (:target-weights result)
        binding-instrument-ids (set (keep :instrument-id
                                          (get-in result [:diagnostics :binding-constraints])))
        rows (map-indexed (fn [idx [instrument-id current-weight target-weight]]
                            {:idx idx
                             :asset (instrument-group-key instrument-id)
                             :instrument-id instrument-id
                             :current-weight (or current-weight 0)
                             :target-weight (or target-weight 0)})
                          (map vector ids current target))
        groups (group-by :asset rows)]
    [:section {:class ["min-h-0" "border-r" "border-base-300" "bg-base-100/95" "leading-4"]
               :data-role "portfolio-optimizer-target-exposure-table"}
     [:div {:class ["flex" "items-center" "justify-between" "border-b" "border-base-300" "px-4" "py-3"]}
      [:div
       [:p {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
        "Allocation"]
       [:p {:class ["mt-1" "text-xs" "text-trading-text"]}
        "By asset · click to expand legs"]]
      [:div {:class ["flex" "border" "border-base-300" "text-[0.62rem]" "font-semibold" "uppercase" "tracking-[0.06em]"]}
       [:button {:type "button"
                 :class ["border-r" "border-base-300" "bg-base-200/60" "px-3" "py-1" "text-trading-text"]}
        "By Asset"]
       [:button {:type "button"
                 :class ["px-3" "py-1" "text-trading-muted"]}
        "By Leg"]]]
     [:div {:class ["overflow-auto"]}
      [:table {:class ["w-full" "border-collapse" "text-[0.6875rem]"]}
       [:thead
        [:tr
         [:th {:class ["sticky" "top-0" "w-5" "border-b" "border-base-300" "bg-base-100" "px-2" "py-1.5" "text-left" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} ""]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-left" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Asset"]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Current"]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Target"]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Δ"]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Δ $"]]]
       (into
        [:tbody]
        (mapcat
         (fn [[asset asset-rows]]
           (concat
            [(exposure-group-row asset capital-usd binding-instrument-ids asset-rows)]
            (map (fn [{:keys [idx instrument-id current-weight target-weight]}]
                   (exposure-row idx
                                 binding-instrument-ids
                                 (= 1 (count asset-rows))
                                 instrument-id
                                 capital-usd
                                 current-weight
                                 target-weight))
                 asset-rows)))
         groups))]]]))
