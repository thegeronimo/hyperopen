(ns hyperopen.views.portfolio.optimize.instrument-overrides-panel
  (:require [hyperopen.portfolio.optimizer.domain.constraints :as domain-constraints]
            [hyperopen.views.portfolio.optimize.instrument-display :as instrument-display]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]))

(def ^:private row-grid-class
  ["grid" "grid-cols-[minmax(0,1fr)_34px_34px_34px_60px_60px]" "items-center"
   "gap-2" "px-2" "py-1.5"])

(def ^:private override-help
  {:allow "Checking Allow restricts the run: only allowed assets stay eligible. Leave the whole column clear to keep every universe asset eligible."
   :block "Blocked assets are excluded from this run. Block wins when an asset is also allowed."
   :lock "Pins the asset at its currently held weight, so the optimizer rebalances around the position instead of trading it."
   :max-weight "Per-asset ceiling on the target weight, as a fraction of equity. Blank inherits the global per-asset cap."
   :perp-cap "Tighter ceiling for this perp leg, as a fraction of equity. Blank inherits the asset's cap. Spot and vault legs have no perp cap."})

(def ^:private column-tooltip-ids
  {:allow "portfolio-optimizer-override-allow-tooltip"
   :block "portfolio-optimizer-override-block-tooltip"
   :lock "portfolio-optimizer-override-lock-tooltip"
   :max-weight "portfolio-optimizer-override-max-weight-tooltip"
   :perp-cap "portfolio-optimizer-override-perp-cap-tooltip"})

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

(defn- column-tooltip
  [column]
  [:span {:class ["pointer-events-none" "absolute" "left-0" "right-0"
                  "top-[calc(100%+4px)]" "z-30" "border" "border-base-300"
                  "bg-base-100" "px-2" "py-1.5" "font-sans" "text-[0.75rem]"
                  "font-normal" "normal-case" "leading-[1.45]" "tracking-normal"
                  "text-trading-muted" "opacity-0"
                  "shadow-[0_12px_32px_rgba(0,0,0,0.45)]" "transition-opacity"
                  "duration-150" "group-hover:opacity-100"]
          :id (column-tooltip-ids column)
          :role "tooltip"
          :data-role (column-tooltip-ids column)}
   (get override-help column)])

(defn- header-cell
  [label column center?]
  [:span {:class (cond-> ["group" "min-w-0" "cursor-help"]
                   center? (conj "text-center"))}
   label
   (column-tooltip column)])

(defn- header-row
  []
  [:div {:class (into ["relative" "border-b" "border-base-300" "bg-base-200/40"
                       "font-mono" "text-[0.625rem]" "font-semibold" "uppercase"
                       "tracking-[0.12em]" "text-trading-muted/70"]
                      row-grid-class)
         :data-role "portfolio-optimizer-instrument-overrides-header"}
   [:span {:class ["min-w-0" "truncate"]} "Asset"]
   (header-cell "Alw" :allow true)
   (header-cell "Blk" :block true)
   (header-cell "Lck" :lock true)
   (header-cell "Max Wt" :max-weight false)
   (header-cell "Perp" :perp-cap false)])

(defn- override-checkbox
  [{:keys [tone column checked? data-role aria-label action]}]
  [:input {:type "checkbox"
           :class ["optimizer-override-check" "justify-self-center"]
           :data-tone tone
           :data-role data-role
           :checked checked?
           :aria-label aria-label
           :aria-describedby (column-tooltip-ids column)
           :on {:change [action]}}])

(defn- override-number-input
  [{:keys [value placeholder column data-role aria-label action]}]
  [:input {:type "text"
           :inputmode "decimal"
           :class controls/input-class
           :data-role data-role
           :value (text-value value)
           :placeholder placeholder
           :aria-label aria-label
           :aria-describedby (column-tooltip-ids column)
           :on {:input [action]}}])

(defn- row-active?
  [constraints id perp?]
  (or (checked? (:allowlist constraints) id)
      (checked? (:blocklist constraints) id)
      (checked? (:held-locks constraints) id)
      (some? (get-in constraints [:asset-overrides id :max-weight]))
      (and perp?
           (some? (get-in constraints [:perp-leverage id :max-weight])))))

(defn- instrument-row
  [constraints global-cap instrument]
  (let [id (instrument-id instrument)
        perp? (= :perp (:market-type instrument))
        primary-label (instrument-display/primary-label instrument)
        secondary-token (or (instrument-display/base-label instrument)
                            (:coin instrument))
        asset-max-weight (get-in constraints [:asset-overrides id :max-weight])]
    [:div {:class (into ["optimizer-override-row" "border-b" "border-base-300"
                         "last:border-b-0" "hover:bg-base-200/30"]
                        row-grid-class)
           :data-role "portfolio-optimizer-instrument-override-row"
           :data-active (when (row-active? constraints id perp?) "true")}
     [:span {:class ["min-w-0"]}
      [:span {:class ["block" "truncate" "font-mono" "text-[0.8125rem]" "font-semibold"]}
       primary-label]
      [:span {:class ["block" "truncate" "font-mono" "text-[0.625rem]" "uppercase"
                      "tracking-[0.08em]" "text-trading-muted/70"]}
       (str secondary-token " · " (market-label instrument))]]
     (override-checkbox
      {:tone "allow"
       :column :allow
       :checked? (checked? (:allowlist constraints) id)
       :data-role "portfolio-optimizer-instrument-allowlist-input"
       :aria-label (str "Allow " primary-label)
       :action [:actions/set-portfolio-optimizer-instrument-filter
                :allowlist
                id
                [:event.target/checked]]})
     (override-checkbox
      {:tone "block"
       :column :block
       :checked? (checked? (:blocklist constraints) id)
       :data-role "portfolio-optimizer-instrument-blocklist-input"
       :aria-label (str "Block " primary-label)
       :action [:actions/set-portfolio-optimizer-instrument-filter
                :blocklist
                id
                [:event.target/checked]]})
     (override-checkbox
      {:tone "lock"
       :column :lock
       :checked? (checked? (:held-locks constraints) id)
       :data-role "portfolio-optimizer-instrument-held-lock-input"
       :aria-label (str "Lock held weight for " primary-label)
       :action [:actions/set-portfolio-optimizer-asset-override
                :held-lock?
                id
                [:event.target/checked]]})
     (override-number-input
      {:value asset-max-weight
       :placeholder (text-value global-cap)
       :column :max-weight
       :data-role "portfolio-optimizer-instrument-max-weight-input"
       :aria-label (str "Max weight for " primary-label)
       :action [:actions/set-portfolio-optimizer-asset-override
                :max-weight
                id
                [:event.target/value]]})
     (if perp?
       (override-number-input
        {:value (get-in constraints [:perp-leverage id :max-weight])
         :placeholder (text-value (or asset-max-weight global-cap))
         :column :perp-cap
         :data-role "portfolio-optimizer-instrument-perp-max-weight-input"
         :aria-label (str "Perp cap for " primary-label)
         :action [:actions/set-portfolio-optimizer-asset-override
                  :perp-max-weight
                  id
                  [:event.target/value]]})
       [:span {:class ["text-center" "font-mono" "text-[0.8125rem]"
                       "text-trading-muted/60"]
               :aria-hidden "true"}
        "—"])]))

(defn- override-count
  [constraints]
  (+ (count (selected-set (:allowlist constraints)))
     (count (selected-set (:blocklist constraints)))
     (count (selected-set (:held-locks constraints)))
     (count (filter (comp some? :max-weight val)
                    (or (:asset-overrides constraints) {})))
     (count (filter (comp some? :max-weight val)
                    (or (:perp-leverage constraints) {})))))

(defn overrides-trailing-label
  [draft]
  (let [n (override-count (or (:constraints draft) {}))]
    (if (pos? n)
      (str n " active")
      "optional")))

(defn- eligibility-constraints
  [constraints]
  (cond-> constraints
    (empty? (selected-set (:allowlist constraints))) (dissoc :allowlist)))

(defn- toolbar-row
  [universe constraints]
  (let [total (count universe)
        eligible (count (domain-constraints/normalize-universe
                         universe
                         (eligibility-constraints constraints)))
        allowlist-on? (boolean (seq (selected-set (:allowlist constraints))))
        locked (count (selected-set (:held-locks constraints)))]
    [:div {:class ["flex" "items-center" "gap-2" "border-b" "border-base-300"
                   "px-2" "py-1.5"]
           :data-role "portfolio-optimizer-instrument-overrides-toolbar"}
     [:span {:class ["font-mono" "text-[0.6875rem]" "uppercase" "tracking-[0.12em]"
                     "text-trading-muted"]}
      (str eligible " of " total " eligible")]
     (when allowlist-on?
       [:span {:class ["optimizer-chip" "border" "px-1.5" "py-[1px]" "font-mono"
                       "text-[0.625rem]" "font-semibold" "uppercase"
                       "tracking-[0.12em]"]
               :data-optimizer-chip "true"
               :data-tone "accent"}
        "allowlist on"])
     (when (pos? locked)
       [:span {:class ["ml-auto" "font-mono" "text-[0.6875rem]" "uppercase"
                       "tracking-[0.12em]" "text-trading-muted/70"]}
        (str locked " locked")])]))

(defn instrument-overrides-panel
  [draft]
  (let [universe (vec (or (:universe draft) []))
        constraints (or (:constraints draft) {})
        global-cap (or (:max-asset-weight constraints)
                       domain-constraints/default-max-asset-weight)]
    [:div {:class ["mt-3"]
           :data-role "portfolio-optimizer-instrument-overrides-panel"}
     [:p {:class ["text-[0.8125rem]" "leading-[1.45]" "text-trading-muted"]}
      "Row-level eligibility and weight caps layered on top of the global constraints. Hover a column header for details."]
     (if (seq universe)
       [:div {:class ["mt-2" "border" "border-base-300" "bg-base-100/50"]}
        (toolbar-row universe constraints)
        (header-row)
        (into [:div {:class ["max-h-[21rem]" "overflow-y-auto"]
                     :data-role "portfolio-optimizer-instrument-overrides-rows"}]
              (map #(instrument-row constraints global-cap %) universe))]
       [:p {:class ["mt-2" "border" "border-base-300" "bg-base-200/20" "px-2"
                    "py-3" "text-xs" "text-trading-muted"]}
        "No instruments selected yet. Add assets in Universe (01) to edit row-level overrides."])
     (when (seq universe)
       [:div {:class ["mt-2" "font-mono" "text-[0.6875rem]" "leading-5"
                      "text-trading-muted/70"]}
        "Caps are weight fractions — 0.25 caps an asset at 25% of equity. Blank inputs inherit the global cap."])]))
