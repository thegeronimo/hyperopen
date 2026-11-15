(ns hyperopen.views.portfolio.optimize.risk-asset-breakdown-panel
  "BREAKDOWN tab of the Equal Risk risk-contribution card (designer spec
  2026-07-11, per-asset breakdown): the tab now leads with ONE asset's
  decomposition — standalone risk, diversification effect, and their exact sum
  the net contribution, drawn as labeled full-height bars over a shared axis —
  followed by the framed Net = Standalone + Diversification equation with the
  actual numbers and four summary tiles (equal-risk fit, this asset's
  diversification benefit/cost, net vs target, allocation freedom, all worded
  by the structure view-model).

  Which asset the panel explains is the SAME app state the Allocation table's
  row clicks set (:actions/set-portfolio-optimizer-selected-risk-instrument);
  the panel adds a Change-asset native select dispatching that action with the
  nexus [:event.target/value] placeholder, so the dropdown, row clicks, and
  the table's highlight ring can never disagree. A small DOM-state toggle
  (sr-only radios + scoped :has() CSS, the card's usual pattern) switches to
  the pre-rendered all-assets chart (risk-breakdown-panel) — 'Selected asset'
  is the unchecked default. This namespace owns the whole tab body; the card
  calls breakdown-tab-panel and nothing else."
  (:require ["lucide/dist/esm/icons/arrow-right-left.js" :default lucide-arrows]
            ["lucide/dist/esm/icons/lock-open.js" :default lucide-lock-open]
            ["lucide/dist/esm/icons/lock.js" :default lucide-lock]
            ["lucide/dist/esm/icons/scale.js" :default lucide-scale]
            ["lucide/dist/esm/icons/shield.js" :default lucide-shield]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]
            [hyperopen.views.portfolio.optimize.risk-breakdown-panel
             :as breakdown-panel]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]))

(def ^:private row-class "optimizer-risk-asset-row")

(defn- change-asset-picker
  "Native select over every held asset (the decomposition maps are never
  capped, so a wide book can inspect assets the all-assets chart hides).
  Dispatches the shared selected-risk-instrument action. Selection rides the
  OPTIONS as :selected (Replicant writes the .selected property), never
  :value on the select — element attributes are set before children mount,
  so a select-level value silently falls back to the first option on first
  render. The EFFECTIVE selection is marked, so a stale saved id still shows
  the rendered fallback."
  [result selected-id]
  (let [options (structure-model/breakdown-asset-options result)]
    (when (seq options)
      [:label {:class ["optimizer-risk-asset-picker"]}
       [:span {:class ["optimizer-risk-asset-picker-label"]} "Change asset"]
       (into [:select {:class ["optimizer-risk-asset-picker-select" "border"
                               "border-base-300" "bg-base-100/80" "py-0" "pl-2"
                               "pr-7" "font-mono" "text-[0.75rem]"
                               "leading-none"]
                       :data-role "portfolio-optimizer-risk-asset-select"
                       :on {:change
                            [[:actions/set-portfolio-optimizer-selected-risk-instrument
                              [:event.target/value]]]}}]
             (map (fn [{:keys [instrument-id label]}]
                    [:option {:value instrument-id
                              :selected (= instrument-id selected-id)}
                     label]))
             options)])))

(defn- sign-token
  [value]
  (when (and (number? value) (js/isFinite value))
    (if (neg? value) "negative" "positive")))

(defn- end-label-placement
  "Where the bar's value label rides: at the bar's free end, pointing away
  from zero — unless a negative bar ends so close to the lane's left edge
  that an outside label would spill over the value column (the scale hugs
  the data, so a dominant negative component leaves no left margin). Those
  flip to the zero side of the bar, which is empty for a negative bar.
  Returns {:left <css %> :dir \"positive\"|\"negative\"}."
  [x bar-value]
  (let [xv (x bar-value)]
    (if (and (neg? bar-value) (< xv 15))
      {:left (x 0) :dir "positive"}
      {:left xv :dir (if (neg? bar-value) "negative" "positive")})))

(defn- component-row
  "One decomposition row: label + sub, the value column, and the lane with a
  full-height bar from zero capped by the value label at the bar's free end.
  The net row carries the sum separator."
  [{:keys [x]} {:keys [label sub value end-label bar-value kind role sum?]}]
  [:div {:class (cond-> [row-class "optimizer-risk-asset-row--component"]
                  sum? (conj "optimizer-risk-asset-row--sum"))
         :data-role role}
   [:div {:class ["min-w-0"]}
    [:p {:class ["optimizer-risk-decomp-label"]} label]
    [:p {:class ["optimizer-risk-decomp-sub"]} sub]]
   [:span {:class ["optimizer-risk-decomp-value" "font-mono" "tabular-nums"]
           :data-kind kind
           :data-sign (sign-token bar-value)}
    value]
   [:div {:class ["optimizer-risk-balance-lane" "relative" "min-w-0"]}
    (when (and (number? bar-value) (js/isFinite bar-value))
      (let [x0 (x 0)
            xv (x bar-value)]
        [:div {:class ["optimizer-risk-decomp-bar"
                       "optimizer-risk-decomp-bar--full"]
               :data-kind kind
               :data-sign (sign-token bar-value)
               :style {:left (str (min x0 xv) "%")
                       :width (str (max 0.4 (js/Math.abs (- xv x0))) "%")}}]))
    (when (and (number? bar-value) (js/isFinite bar-value))
      (let [{:keys [left dir]} (end-label-placement x bar-value)]
        [:span {:class ["optimizer-risk-asset-bar-label" "font-mono"
                        "tabular-nums"]
                :data-kind kind
                :data-dir dir
                :data-sign (sign-token bar-value)
                :style {:left (str left "%")}}
         end-label]))]])

(defn- component-rows
  [{:keys [standalone diversification net]}]
  [{:label "Own-variance term"
    :sub "Position weight squared × its own variance, normalized by portfolio variance"
    :value (structure-model/format-pct standalone)
    :end-label (breakdown-panel/format-signed-pct standalone)
    :bar-value standalone
    :kind "standalone"
    :role "portfolio-optimizer-risk-selected-standalone"}
   {:label "Cross-covariance effect"
    :sub (cond
           (< (js/Math.abs diversification)
              structure-model/cross-effect-neutral-threshold)
           "Neutral at displayed precision"
           (neg? diversification) "Offsets risk at final weights"
           :else "Amplifies risk at final weights")
    :value (breakdown-panel/format-signed-pct diversification)
    :end-label (breakdown-panel/format-signed-pct diversification)
    :bar-value diversification
    :kind "diversification"
    :role "portfolio-optimizer-risk-selected-diversification"}
   {:label "Net risk contribution"
    :sub "To total portfolio volatility"
    :value (structure-model/format-pct net)
    :end-label (str "= " (structure-model/format-pct net))
    :bar-value net
    :kind "net"
    :sum? true
    :role "portfolio-optimizer-risk-selected-net"}])

(defn- equation-strip
  [{:keys [standalone diversification net]}]
  [:div {:class ["optimizer-risk-asset-equation"]
         :data-role "portfolio-optimizer-risk-selected-identity"
         :title "The signed Euler contribution splits exactly into the position's own-variance term plus its cross-covariance term."}
   [:p {:class ["optimizer-risk-asset-equation-terms"]}
    [:span "Net risk contribution"]
    [:span {:class ["optimizer-risk-asset-equation-op"]} "="]
    [:span "Own-variance term"]
    [:span {:class ["optimizer-risk-asset-equation-op"]} "+"]
    [:span "Cross-covariance effect"]]
   [:p {:class ["optimizer-risk-asset-equation-values" "font-mono"
                "tabular-nums"]}
    [:span {:data-kind "net"} (structure-model/format-pct net)]
    [:span {:class ["optimizer-risk-asset-equation-op"]} "="]
    [:span {:data-kind "standalone"} (structure-model/format-pct standalone)]
    [:span {:class ["optimizer-risk-asset-equation-op"]} "+"]
    [:span {:data-kind "diversification"
            :data-sign (sign-token diversification)}
     (str "(" (breakdown-panel/format-signed-pct diversification) ")")]]])

(def ^:private tile-icons
  {:shield lucide-shield
   :arrows lucide-arrows
   :scale lucide-scale
   :lock lucide-lock
   :lock-open lucide-lock-open})

(defn- tile
  [{:keys [key icon icon-tone label value sub]}]
  [:div {:class ["optimizer-equal-risk-fact"]
         :data-role (str "portfolio-optimizer-risk-asset-tile-" (name key))}
   [:span {:class ["optimizer-equal-risk-fact-icon"]
           :data-tone icon-tone}
    (controls/lucide-icon (get tile-icons icon lucide-shield) nil)]
   [:div {:class ["min-w-0"]}
    [:p {:class ["optimizer-equal-risk-fact-label"]} label]
    [:p {:class ["optimizer-equal-risk-fact-value" "font-mono" "tabular-nums"]}
     value]
    [:p {:class ["optimizer-equal-risk-fact-sub" "text-trading-muted"]} sub]]])

(defn- tiles-row
  [tiles]
  (when (seq tiles)
    (into [:div {:class ["optimizer-risk-asset-tiles"]
                 :data-role "portfolio-optimizer-risk-asset-tiles"}]
          (map tile)
          tiles)))

(defn- asset-block
  [result {:keys [instrument-id label side] :as selected}]
  (let [scale (structure-model/fit-scale
               [(:standalone selected) (:diversification selected)
                (:net selected)])
        side-word (structure-model/side-label side)]
    [:div {:class ["optimizer-risk-corr-block"]
           :data-role "portfolio-optimizer-risk-selected-breakdown"}
     [:div {:class ["optimizer-risk-corr-head"]}
      [:div {:class ["min-w-0"]}
       [:p {:class ["optimizer-risk-corr-title"]}
        "Contribution breakdown for "
        [:span {:class ["optimizer-risk-decomp-selected-asset" "font-mono"]
                :data-role "portfolio-optimizer-risk-selected-asset"
                :data-side (some-> side name)}
         label]
        (when side-word
          [:span {:class ["optimizer-risk-asset-side-badge"]
                  :data-side (some-> side name)
                  :title (str "Held " side-word)}
           (subs side-word 0 1)])]
       [:p {:class ["optimizer-risk-asset-explainer"]}
        (str "This final-weight attribution shows how the held position's "
             "cross-covariance "
             (cond
               (< (js/Math.abs (:diversification selected))
                  structure-model/cross-effect-neutral-threshold)
               "is neutral at displayed precision"
               (neg? (:diversification selected)) "offsets risk"
               :else "amplifies risk")
             ". It is not removal impact; removing the asset would require "
             "a new optimization.")]]
      (change-asset-picker result instrument-id)]
     [:div {:class ["optimizer-risk-balance-rows" "relative" "mt-3"]}
      (breakdown-panel/plot-backdrop scale nil
                                     {:row-class row-class
                                      :lead-cells 2
                                      :tail-cells 0
                                      :max-labels 6})
      (into [:div {:class ["relative"]}]
            (map (partial component-row scale))
            (component-rows selected))]
     (breakdown-panel/axis-rows scale
                                {:title "Contribution to Total Portfolio Volatility"
                                 :row-class row-class
                                 :lead-cells 2
                                 :tail-cells 0
                                 :max-labels 6})
     (equation-strip selected)]))

(defn- view-tab
  [radio-name view text]
  [:label {:class ["optimizer-risk-breakdown-mode-tab"]
           :data-role (str "portfolio-optimizer-risk-breakdown-view-" view)}
   [:input {:type "radio"
            :name radio-name
            :class ["sr-only"]
            :data-breakdown-view view}]
   text])

(defn breakdown-tab-panel
  "Panel body for the BREAKDOWN tab: KPI strip, the Selected asset / All
  assets toggle, the per-asset block + tiles (default view), and the
  pre-rendered all-assets chart. Nil when `rows` are empty (the card then
  drops the tab); the per-asset half quietly disappears if the result somehow
  has rows but no selectable contributions, leaving the all-assets chart.
  `summary-tiles?` (default true) drops the four summary tiles — they grade
  the equal-risk fit and freedom, which the Risk-weighted sizing card's
  diagnostic reuse must not imply."
  [{:keys [result rows target-share kpi-strip overflow-note
           selected-instrument-id summary-tiles?]
    :or {summary-tiles? true}}]
  (when (seq rows)
    (let [selected (structure-model/selected-breakdown result
                                                       selected-instrument-id)
          all-panel (breakdown-panel/breakdown-panel
                     {:rows rows
                      :target-share target-share
                      :overflow-note overflow-note})]
      (if-not selected
        [:div kpi-strip all-panel]
        (let [radio-name (str (structure-model/risk-view-radio-name result)
                              "-breakdown-view")]
          [:div {:class ["optimizer-risk-breakdown-body"]}
           kpi-strip
           [:div {:class ["optimizer-risk-breakdown-toolbar"]}
            [:div {:class ["optimizer-risk-breakdown-mode-tabs"]
                   :data-role "portfolio-optimizer-risk-breakdown-view-tabs"}
             (view-tab radio-name "asset" "Selected asset")
             (view-tab radio-name "all" "All assets")]]
           [:div {:data-breakdown-only "asset"}
            (asset-block result selected)
            (when summary-tiles?
              (tiles-row (structure-model/asset-breakdown-tiles result
                                                                selected)))]
           [:div {:data-breakdown-only "all"}
            all-panel]])))))
