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
                              #js {:maximumFractionDigits 2}))
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

(defn- panel-shell
  [data-role title subtitle & children]
  [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
             :data-role data-role}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
    title]
   [:p {:class ["mt-2" "text-sm" "text-trading-muted"]} subtitle]
   (into [:div {:class ["mt-4" "space-y-2"]}]
         children)])

(defn- row-shell
  [& children]
  (into [:div {:class ["grid"
                       "grid-cols-[minmax(8rem,1.1fr)_repeat(4,minmax(5rem,0.8fr))]"
                       "gap-3"
                       "rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-200/40"
                       "p-3"
                       "text-xs"
                       "tabular-nums"]}]
        children))

(defn- row-shell-with-attrs
  [attrs & children]
  (let [base-class ["grid"
                    "grid-cols-[minmax(8rem,1.1fr)_repeat(4,minmax(5rem,0.8fr))]"
                    "gap-3"
                    "rounded-lg"
                    "border"
                    "border-base-300"
                    "bg-base-200/40"
                    "p-3"
                    "text-xs"
                    "tabular-nums"]
        attrs* (-> attrs
                   (dissoc :extra-class)
                   (assoc :class (into base-class (:extra-class attrs))))]
    (into [:div attrs*] children)))

(defn- signed-weight-cell
  [value]
  (let [sign (signed-label value)
        width (if (finite-number? value)
                (min 100 (* 100 (js/Math.abs value)))
                0)]
    [:span {:class ["space-y-1"]
            :data-sign sign}
     [:span {:class ["block"]} (format-pct value)]
     [:span {:class ["block" "h-1.5" "overflow-hidden" "rounded-full" "bg-base-300/50"]}
      [:span {:class (cond-> ["block" "h-full" "rounded-full"]
                       (= "long" sign) (conj "bg-primary/70")
                       (= "short" sign) (conj "bg-error/70")
                       (= "flat" sign) (conj "bg-trading-muted/40"))
              :style {:width (str width "%")}}]]]))

(defn- exposure-row
  [idx binding-instrument-ids instrument-id capital-usd current-weight target-weight]
  (let [current-notional (* (or capital-usd 0) (or current-weight 0))
        target-notional (* (or capital-usd 0) (or target-weight 0))
        delta (- (or target-weight 0) (or current-weight 0))
        binding? (contains? binding-instrument-ids instrument-id)]
    (row-shell-with-attrs
     {:data-role (str "portfolio-optimizer-target-exposure-row-" idx)
      :data-binding (when binding? "true")
      :data-current-sign (signed-label current-weight)
      :data-target-sign (signed-label target-weight)
      :extra-class (when binding?
                     ["border-warning/60" "bg-warning/10"])}
     [:span {:class ["font-semibold" "text-trading-text"]} instrument-id]
     (signed-weight-cell current-weight)
     (signed-weight-cell target-weight)
     [:span (format-pct delta)]
     [:span (format-usdc (- target-notional current-notional))])))

(defn- exposure-group-row
  [asset capital-usd rows]
  (let [current-weight (reduce + 0 (map :current-weight rows))
        target-weight (reduce + 0 (map :target-weight rows))
        delta (- target-weight current-weight)]
    (row-shell-with-attrs
     {:data-role (str "portfolio-optimizer-target-exposure-group-"
                      (data-role-token asset))
      :data-target-sign (signed-label target-weight)
      :extra-class ["border-base-300" "bg-base-200/70"]}
     [:span {:class ["font-semibold" "text-trading-text"]}
      (str asset " group")]
     (signed-weight-cell current-weight)
     (signed-weight-cell target-weight)
     [:span (format-pct delta)]
     [:span (format-usdc (* (or capital-usd 0) delta))])))

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
    (panel-shell
     "portfolio-optimizer-target-exposure-table"
     "Target Exposure"
     "Signed current-vs-target weights are grouped by asset with instrument legs visible for long-only and signed portfolios."
     (row-shell
      [:span {:class ["font-semibold" "text-trading-muted"]} "Instrument"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Current"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Target"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Delta"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Notional"])
     (map (fn [[asset asset-rows]]
            [:details {:class ["space-y-2"]
                       :data-role (str "portfolio-optimizer-target-exposure-asset-"
                                       (data-role-token asset))
                       :open true}
             [:summary {:class ["cursor-pointer" "list-none"]}
              (exposure-group-row asset capital-usd asset-rows)]
             (map (fn [{:keys [idx instrument-id current-weight target-weight]}]
                    (exposure-row idx
                                  binding-instrument-ids
                                  instrument-id
                                  capital-usd
                                  current-weight
                                  target-weight))
                  asset-rows)])
          groups))))
