(ns hyperopen.views.portfolio.optimize.instrument-overrides-panel)

(def ^:private row-shell-class
  ["grid" "grid-cols-1" "gap-2" "rounded-lg" "border" "border-base-300" "bg-base-200/40"
   "p-3" "lg:grid-cols-[minmax(160px,1.2fr)_repeat(5,minmax(110px,1fr))]"])

(def ^:private label-class
  ["block" "text-[0.6rem]" "font-semibold" "uppercase" "tracking-[0.16em]" "text-trading-muted"])

(def ^:private input-class
  ["mt-1.5" "w-full" "rounded-md" "border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
   "text-sm" "font-semibold" "tabular-nums" "outline-none" "focus:border-primary/70"])

(defn- instrument-id
  [instrument]
  (:instrument-id instrument))

(defn- selected-set
  [values]
  (set (or values [])))

(defn- checked?
  [values id]
  (contains? (selected-set values) id))

(defn- market-label
  [instrument]
  (name (or (:market-type instrument) :unknown)))

(defn- text-value
  [value]
  (if (some? value)
    (str value)
    ""))

(defn- checkbox-control
  [label checked? data-role action]
  [:label {:class ["rounded-md" "border" "border-base-300" "bg-base-100/70" "p-2"]}
   [:span {:class label-class} label]
   [:input {:type "checkbox"
            :class ["mt-2" "h-4" "w-4" "accent-primary"]
            :data-role data-role
            :checked checked?
            :on {:change [action]}}]])

(defn- number-control
  [label value data-role action]
  [:label {:class ["rounded-md" "border" "border-base-300" "bg-base-100/70" "p-2"]}
   [:span {:class label-class} label]
   [:input {:type "text"
            :inputmode "decimal"
            :class input-class
            :data-role data-role
            :value (text-value value)
            :on {:input [action]}}]])

(defn- disabled-number-control
  [label value data-role]
  [:label {:class ["rounded-md" "border" "border-base-300" "bg-base-100/50" "p-2"
                   "opacity-60"]}
   [:span {:class label-class} label]
   [:input {:type "text"
            :class input-class
            :data-role data-role
            :value value
            :disabled true}]])

(defn- instrument-row
  [constraints instrument]
  (let [id (instrument-id instrument)
        perp? (= :perp (:market-type instrument))]
    [:div {:class row-shell-class
           :data-role "portfolio-optimizer-instrument-override-row"}
     [:div
      [:p {:class ["text-sm" "font-semibold"]} id]
      [:p {:class ["mt-1" "text-xs" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
       (str (:coin instrument) " / " (market-label instrument))]]
     (checkbox-control
      "Allow"
      (checked? (:allowlist constraints) id)
      "portfolio-optimizer-instrument-allowlist-input"
      [:actions/set-portfolio-optimizer-instrument-filter
       :allowlist
       id
       :event.target/checked])
     (checkbox-control
      "Block"
      (checked? (:blocklist constraints) id)
      "portfolio-optimizer-instrument-blocklist-input"
      [:actions/set-portfolio-optimizer-instrument-filter
       :blocklist
       id
       :event.target/checked])
     (number-control
      "Max Weight"
      (get-in constraints [:asset-overrides id :max-weight])
      "portfolio-optimizer-instrument-max-weight-input"
      [:actions/set-portfolio-optimizer-asset-override
       :max-weight
       id
       [:event.target/value]])
     (checkbox-control
      "Held Lock"
      (checked? (:held-locks constraints) id)
      "portfolio-optimizer-instrument-held-lock-input"
      [:actions/set-portfolio-optimizer-asset-override
       :held-lock?
       id
       :event.target/checked])
     (if perp?
       (number-control
        "Perp Cap"
        (get-in constraints [:perp-leverage id :max-weight])
        "portfolio-optimizer-instrument-perp-max-weight-input"
        [:actions/set-portfolio-optimizer-asset-override
         :perp-max-weight
         id
         [:event.target/value]])
       (disabled-number-control
        "Perp Cap"
        "Spot"
        "portfolio-optimizer-instrument-perp-max-weight-input"))]))

(defn instrument-overrides-panel
  [draft]
  (let [universe (vec (or (:universe draft) []))
        constraints (or (:constraints draft) {})]
    [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-instrument-overrides-panel"}
     [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
      "Per-Asset Overrides"]
     [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
      "Set allowlist, blocklist, held-position locks, max asset weights, and per-perp caps."]
     (if (seq universe)
       (into [:div {:class ["mt-4" "space-y-2"]}]
             (map #(instrument-row constraints %) universe))
       [:p {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"
                    "text-sm" "text-trading-muted"]}
        "No instruments selected. Seed the universe before editing row-level constraints."])]))
