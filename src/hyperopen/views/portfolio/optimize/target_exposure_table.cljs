(ns hyperopen.views.portfolio.optimize.target-exposure-table
  (:require [clojure.string :as str]))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- format-pct
  [value]
  (if (finite-number? value)
    (str (.toLocaleString (* 100 value)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         "%")
    "N/A"))

(defn- format-usdc
  [value]
  (if (finite-number? value)
    (str "$" (.toLocaleString value
                              "en-US"
                              #js {:maximumFractionDigits 0}))
    "N/A"))

(defn- signed-label
  [value]
  (cond
    (and (finite-number? value) (neg? value)) "short"
    (and (finite-number? value) (pos? value)) "long"
    :else "flat"))

(defn- instrument-group-key
  [instrument-id]
  (let [value (str instrument-id)
        unprefixed (last (str/split value #":"))
        base (first (str/split unprefixed #"[/-]"))]
    (if (seq base) base value)))

(defn- data-role-token
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn- exposure-row
  [idx binding-instrument-ids instrument-id capital-usd current-weight target-weight]
  (let [current-notional (* (or capital-usd 0) (or current-weight 0))
        target-notional (* (or capital-usd 0) (or target-weight 0))
        delta (- (or target-weight 0) (or current-weight 0))
        binding? (contains? binding-instrument-ids instrument-id)]
    [:tr {:class (when binding? ["bg-warning/10"])
          :data-role (str "portfolio-optimizer-target-exposure-row-" idx)
          :data-binding (when binding? "true")
          :data-current-sign (signed-label current-weight)
          :data-target-sign (signed-label target-weight)}
     [:td {:class ["pl-8" "text-trading-muted"]} instrument-id]
     [:td {:class ["font-mono" "text-right" "tabular-nums"]} (format-pct current-weight)]
     [:td {:class ["font-mono" "text-right" "tabular-nums"]} (format-pct target-weight)]
     [:td {:class [(if (neg? delta) "text-trading-red" "text-trading-green")
                   "font-mono" "text-right" "tabular-nums"]}
      (format-pct delta)]
     [:td {:class [(if (neg? delta) "text-trading-red" "text-trading-green")
                   "font-mono" "text-right" "tabular-nums"]}
      (format-usdc (- target-notional current-notional))]]))

(defn- exposure-group-row
  [asset capital-usd rows]
  (let [current-weight (reduce + 0 (map :current-weight rows))
        target-weight (reduce + 0 (map :target-weight rows))
        delta (- target-weight current-weight)]
    [:tr {:class ["cursor-pointer"]
          :data-role (str "portfolio-optimizer-target-exposure-asset-"
                          (data-role-token asset))
          :data-target-sign (signed-label target-weight)}
     [:td {:class ["font-mono" "font-semibold" "text-trading-text"]}
      [:span {:data-role (str "portfolio-optimizer-target-exposure-group-"
                              (data-role-token asset))}
       asset]]
     [:td {:class ["font-mono" "text-right" "font-semibold" "tabular-nums"]} (format-pct current-weight)]
     [:td {:class ["font-mono" "text-right" "font-semibold" "tabular-nums"]} (format-pct target-weight)]
     [:td {:class [(if (neg? delta) "text-trading-red" "text-trading-green")
                   "font-mono" "text-right" "font-semibold" "tabular-nums"]}
      (format-pct delta)]
     [:td {:class [(if (neg? delta) "text-trading-red" "text-trading-green")
                   "font-mono" "text-right" "font-semibold" "tabular-nums"]}
      (format-usdc (* (or capital-usd 0) delta))]]))

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
    [:section {:class ["min-h-0" "border-r" "border-base-300" "bg-base-100/95"]
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
      [:table {:class ["w-full" "border-collapse" "text-[0.7rem]"]}
       [:thead
        [:tr
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
            [(exposure-group-row asset capital-usd asset-rows)]
            (map (fn [{:keys [idx instrument-id current-weight target-weight]}]
                   (exposure-row idx
                                 binding-instrument-ids
                                 instrument-id
                                 capital-usd
                                 current-weight
                                 target-weight))
                 asset-rows)))
         groups))]]]))
