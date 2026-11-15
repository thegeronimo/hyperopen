(ns hyperopen.views.portfolio.optimize.target-exposure-table
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]
            [hyperopen.portfolio.optimizer.application.view-model.rebalance :as rebalance-view-model]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(def ^:private add-asset-input-class
  ["w-full" "border" "border-base-300" "bg-base-100/80" "px-2" "py-1.5"
   "font-mono" "text-[0.6875rem]" "font-medium" "outline-none"
   "transition-shadow" "focus:border-warning/70"])

(defn- search-input-mount-focus!
  [{:keys [:replicant/life-cycle :replicant/node]}]
  (when (= :replicant.life-cycle/mount life-cycle)
    (platform/queue-microtask!
     (fn []
       (when (and node
                  (.-isConnected node)
                  (fn? (.-focus node)))
         (.focus node)
         (when (fn? (.-select node))
           (.select node)))))))

(defn- format-delta-pct
  [value]
  (opt-format/format-pct-delta value
                               {:minimum-fraction-digits 1
                                :maximum-fraction-digits 1
                                :suffix "%"}))

(defn- delta-tone-class
  [delta]
  (if (neg? (or delta 0))
    "text-trading-red"
    "text-trading-green"))

(defn- delta-direction
  [delta]
  (cond
    (neg? (or delta 0)) "negative"
    (pos? (or delta 0)) "positive"
    :else "neutral"))

(defn- visible-delta-rows
  [groups]
  (mapcat (fn [{:keys [rows] :as group}]
            (cons group (remove :hidden? rows)))
          groups))

(defn- max-abs-delta
  [groups]
  (reduce max
          0
          (keep (fn [{:keys [delta]}]
                  (when (opt-format/finite-number? delta)
                    (js/Math.abs delta)))
                (visible-delta-rows groups))))

(defn- percent-size
  [value]
  (let [rounded (/ (js/Math.round (* value 10)) 10)]
    (if (= rounded (js/Math.round rounded))
      (str (js/Math.round rounded) "%")
      (str rounded "%"))))

(defn- delta-bar-percent
  [delta max-delta scale]
  (if (and (opt-format/finite-number? delta)
           (pos? max-delta))
    (percent-size (min scale (* scale (/ (js/Math.abs delta) max-delta))))
    "0%"))

(defn- delta-bar-size
  [delta max-delta]
  (delta-bar-percent delta max-delta 100))

(defn- delta-bar-fill-size
  [delta max-delta]
  (delta-bar-percent delta max-delta 50))

(defn- data-role-token
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn- allocation-asset-icon
  [asset {:keys [icon-kind market]}]
  (let [vault? (= :vault icon-kind)
        icon-url (when-not vault?
                   (asset-icon/market-icon-url market))]
    [:span {:class ["inline-flex" "h-4" "w-4" "shrink-0" "items-center" "justify-center"]
            :data-role (str "portfolio-optimizer-target-exposure-icon-"
                            (data-role-token asset))
            :aria-hidden true}
     (if vault?
       [:span {:class ["block" "h-2.5" "w-2.5" "rotate-45" "border" "border-cyan-300/70" "bg-cyan-300/15" "shadow-[0_0_6px_rgba(34,211,238,0.24)]"]
               :data-role (str "portfolio-optimizer-target-exposure-vault-diamond-"
                               (data-role-token asset))}]
       (if (seq icon-url)
         [:img {:class ["block" "h-4" "w-4" "rounded-full" "object-contain"]
                :src icon-url
                :alt ""
                :data-role (str "portfolio-optimizer-target-exposure-asset-icon-img-"
                                (data-role-token asset))}]
         [:span {:class ["block" "h-3" "w-3" "rounded-full" "border" "border-base-300" "bg-base-200"]}]))]))

(defn- side-button
  [scope role-id instrument-id selected-side short-selectable? side]
  (let [selected? (= selected-side side)
        enabled? (and instrument-id
                      (or (= :long side)
                          short-selectable?))
        token (data-role-token role-id)
        label-id (or instrument-id role-id "allocation group")]
    [:button {:type "button"
              :class (cond-> ["h-6" "w-6" "border" "border-base-300"
                              "font-mono" "text-[0.6rem]" "font-semibold"
                              "uppercase" "leading-none" "transition-colors"]
                       (and selected? (= :long side))
                       (conj "bg-success/70" "text-base-100")
                       (and selected? (= :short side))
                       (conj "bg-error/70" "text-base-100")
                       (and (not selected?) enabled?)
                       (conj "text-trading-muted" "hover:border-warning/60"
                             "hover:text-warning")
                       (not enabled?)
                       (conj "cursor-not-allowed" "text-trading-muted/30"
                             "opacity-50"))
              :aria-label (str "Set " label-id " "
                               (name side))
              :aria-pressed (if selected? "true" "false")
              :data-role (str scope
                              "-side-"
                              (name side)
                              "-"
                              token)
              :data-selected (when selected? "true")
              :disabled (when-not enabled? true)
              :on (when enabled?
                    {:click [[:actions/set-portfolio-optimizer-universe-instrument-side-and-run
                              instrument-id
                              side]]})}
     (case side
       :short "S"
       "L")]))

(defn- side-control
  [scope {:keys [asset instrument-id position-side short-selectable?]}]
  (let [role-id (or instrument-id asset "allocation-group")]
    [:span {:class ["inline-flex" "items-center" "justify-end"]
            :data-role (str scope
                            "-side-control-"
                            (data-role-token role-id))}
     (side-button scope role-id instrument-id position-side short-selectable? :long)
     (side-button scope role-id instrument-id position-side short-selectable? :short)]))

(defn- pnl-corr-line
  [result instrument-id]
  (when-let [{:keys [correlation effect title]}
             (some-> (structure-model/pnl-portfolio-correlation result instrument-id)
                     structure-model/pnl-correlation-presentation)]
    [:span {:class ["optimizer-target-exposure-pnl-corr" "block"]
            :data-role (str "portfolio-optimizer-target-exposure-pnl-corr-"
                            (data-role-token instrument-id))
            :data-effect effect :title title}
     "P&L corr. to portfolio "
     [:span {:class ["font-mono" "tabular-nums"] :data-effect effect} correlation]
     " · " effect]))
(defn- selection-attrs
  "Attrs merged onto a row's <tr>: click-to-select + the highlight ring on the
  EFFECTIVE selection (explicit or view-model fallback), so the ring always
  agrees with the breakdown panel."
  [{:keys [structure? selected-id]} instrument-id excluded?]
  (let [selectable? (boolean (and structure? instrument-id (not excluded?)))]
    (cond-> {}
      selectable?
      (assoc :data-selectable "true"
             :title "Show this position's contribution breakdown"
             :on {:click [[:actions/set-portfolio-optimizer-selected-risk-instrument
                           instrument-id]]})
      (and selectable? (= instrument-id selected-id))
      (assoc :data-selected "true"))))

(defn- change-cell
  [role-token delta emphasis?]
  [:td {:class (cond-> [(delta-tone-class delta)
                        "optimizer-target-exposure-change-cell"
                        "font-mono" "text-right" "tabular-nums"]
                 emphasis? (conj "font-semibold"))
        :data-role (str "portfolio-optimizer-target-exposure-change-"
                        role-token)}
   (format-delta-pct delta)])

(defn- delta-bar-cell
  [role-token delta max-delta]
  [:td {:class [(delta-tone-class delta)
                "optimizer-target-exposure-delta-bar-cell"]
        :data-role (str "portfolio-optimizer-target-exposure-delta-bar-cell-"
                        role-token)}
   [:span {:class ["optimizer-target-exposure-delta-bar"]
           :data-role (str "portfolio-optimizer-target-exposure-delta-bar-"
                           role-token)
           :data-direction (delta-direction delta)
           :style {:--optimizer-delta-bar-size (delta-bar-size delta max-delta)
                   :--optimizer-delta-bar-fill-size (delta-bar-fill-size delta max-delta)}
           :aria-hidden true}
    [:span {:class ["optimizer-target-exposure-delta-bar-fill"]}]]])

(defn- exposure-row
  [{:keys [max-delta result] :as ctx}
   {:keys [idx binding? binding-kind hidden? current-sign target-sign leg-label current-weight
           target-weight delta excluded? status-label instrument-id]
    :as row}]
  (let [role-token (data-role-token (or instrument-id (str "row-" idx)))]
    [:tr (merge
          {:class (cond-> ["optimizer-target-exposure-row" "optimizer-exposure-row"]
                    binding? (conj "bg-warning/10")
                    excluded? (conj "optimizer-target-exposure-row--excluded")
                    hidden? (conj "hidden"))
           :data-role (str "portfolio-optimizer-target-exposure-row-" idx)
           :data-binding (when binding? "true")
           :data-binding-kind (when binding? (name (or binding-kind :capped)))
           :data-excluded (when excluded? "true")
           :data-current-sign current-sign
           :data-target-sign target-sign}
          (selection-attrs ctx instrument-id excluded?))
     [:td {:class ["text-trading-muted"]} ""]
     [:td {:class ["pl-8" "text-trading-muted"]}
      [:span {:class (cond-> ["inline-flex" "items-center" "gap-2"]
                       excluded? (conj "line-through" "decoration-trading-muted/70"))}
       leg-label]
      (when excluded?
        [:span {:class ["optimizer-target-exposure-status" "ml-2"]}
         status-label])
      (when-not hidden?
        (pnl-corr-line result instrument-id))]
     [:td {:class ["px-2" "text-right"]}
      (side-control "portfolio-optimizer-target-exposure-row" row)]
     [:td {:class (cond-> ["font-mono" "text-right" "tabular-nums"]
                    excluded? (conj "line-through" "decoration-trading-muted/70"))}
      (opt-format/format-pct current-weight {:minimum-fraction-digits 1 :maximum-fraction-digits 1})]
     [:td {:class (cond-> ["font-mono" "text-right" "tabular-nums"]
                    excluded? (conj "line-through" "decoration-trading-muted/70"))}
      (opt-format/format-pct target-weight (if excluded?
                                             {:minimum-fraction-digits 2
                                              :maximum-fraction-digits 2}
                                             {:minimum-fraction-digits 1
                                              :maximum-fraction-digits 1}))]
     (change-cell role-token delta false)
     (delta-bar-cell role-token delta max-delta)]))

(defn- exposure-group-row
  [{:keys [max-delta result] :as ctx}
   {:keys [asset current-weight target-weight delta binding? binding-kind
           expandable? target-sign excluded? status-label instrument-id]
    :as group}]
  (let [asset-token (data-role-token asset)
        token (data-role-token (or instrument-id asset))]
    [:tr (merge
          {:class (cond-> ["optimizer-target-exposure-asset" "optimizer-exposure-row" "cursor-pointer"]
                    excluded? (conj "optimizer-target-exposure-row--excluded"))
           :data-role (str "portfolio-optimizer-target-exposure-asset-"
                           (data-role-token asset))
           :data-excluded (when excluded? "true")
           :data-target-sign target-sign}
          (selection-attrs ctx instrument-id excluded?))
   [:td {:class ["w-8" "font-mono" "text-trading-muted/70"]}
    (if instrument-id
      [:button {:type "button"
                :class ["optimizer-target-exposure-exclude-button"]
                :aria-label (str (if excluded? "Include " "Exclude ")
                                 asset
                                 " and rerun")
                :title (str (if excluded? "Include " "Exclude ") asset)
                :data-role (str "portfolio-optimizer-target-exposure-exclude-"
                                token)
                :data-excluded (when excluded? "true")
                :on {:click [[:actions/toggle-portfolio-optimizer-universe-instrument-exclusion-and-run
                              instrument-id]]}}
       "⌀"]
      (when expandable? "▾"))]
   [:td {:class ["font-mono" "font-semibold" "text-trading-text"]}
    [:span {:class ["inline-flex" "min-w-0" "items-center" "gap-2"]}
     (allocation-asset-icon asset group)
     [:span {:class (cond-> []
                      excluded? (conj "line-through" "decoration-trading-muted/70"))
             :data-role (str "portfolio-optimizer-target-exposure-group-"
                             (data-role-token asset))}
      asset]
     (when excluded?
       [:span {:class ["optimizer-target-exposure-excluded-tag"]}
        "excluded"])]
    (when binding?
      (let [floored? (= :floored binding-kind)]
        [:span {:class ["ml-2" "border" "border-warning/50" "px-1.5" "py-0.5"
                        "font-mono" "text-[0.5rem]" "font-semibold" "uppercase"
                        "tracking-[0.08em]" "text-warning"]
                :title (if floored?
                         "Held at 0% — the optimizer would have taken this asset past zero, but the side setting blocks that direction. To keep every asset with a risk-based size, try Risk-weighted sizing in Optimization goal."
                         "The target sits on this asset's weight cap.")
                :data-binding-kind (name (or binding-kind :capped))}
         (if floored? "floored" "capped")]))
    (when excluded?
      [:span {:class ["optimizer-target-exposure-status" "ml-2"]}
       status-label])
    (pnl-corr-line result instrument-id)]
   [:td {:class ["px-2" "text-right"]}
    (side-control "portfolio-optimizer-target-exposure" group)]
   [:td {:class (cond-> ["font-mono" "text-right" "font-semibold" "tabular-nums"]
                  excluded? (conj "line-through" "decoration-trading-muted/70"))}
    (opt-format/format-pct current-weight {:minimum-fraction-digits 1 :maximum-fraction-digits 1})]
   [:td {:class (cond-> ["font-mono" "text-right" "font-semibold" "tabular-nums"]
                  excluded? (conj "line-through" "decoration-trading-muted/70"))}
    (opt-format/format-pct target-weight (if excluded?
                                           {:minimum-fraction-digits 2
                                            :maximum-fraction-digits 2}
                                           {:minimum-fraction-digits 1
                                            :maximum-fraction-digits 1}))]
   (change-cell asset-token delta true)
   (delta-bar-cell asset-token delta max-delta)]))

(defn- candidate-row
  [{:keys [market-key label name]} idx active-index]
  [:div {:class ["optimizer-draft-add-asset-candidate-row"
                 "grid" "grid-cols-[58px_minmax(0,1fr)_44px]"
                 "items-center" "gap-2" "border-b" "border-base-300" "px-2"
                 "py-1.5" "last:border-b-0" "hover:bg-base-200/40"]
         :data-role (str "portfolio-optimizer-draft-add-asset-candidate-row-"
                         market-key)
         :id (str "portfolio-optimizer-draft-add-asset-candidate-" idx)
         :role "option"
         :aria-selected (if (= idx active-index) "true" "false")
         :data-active (when (= idx active-index) "true")
         :on {:click [[:actions/add-portfolio-optimizer-universe-instrument-and-run
                       market-key]]}}
   [:span {:class ["truncate" "font-mono" "text-[0.6875rem]" "font-semibold"]}
    label]
   [:span {:class ["truncate" "text-[0.6875rem]" "text-trading-muted"]}
    name]
   [:button {:type "button"
             :class ["text-right" "font-mono" "text-[0.65625rem]" "font-semibold"
                     "text-warning" "hover:text-warning"]
             :data-role (str "portfolio-optimizer-draft-add-asset-add-"
                             market-key)
             :on {:click [[:actions/add-portfolio-optimizer-universe-instrument-and-run
                           market-key]]}}
    "+ add"]])

(defn- candidate-table-header
  []
  [:div {:class ["grid" "grid-cols-[58px_minmax(0,1fr)_44px]"
                 "items-center" "gap-2" "border-b" "border-base-300"
                 "bg-base-200/40" "px-2" "py-1.5" "font-mono"
                 "text-[0.55rem]" "font-semibold" "uppercase"
                 "tracking-[0.12em]" "text-trading-muted/70"]
         :data-role "portfolio-optimizer-draft-add-asset-candidate-header"
         :role "presentation"}
   [:span "Asset"]
   [:span "Name"]
   [:span {:class ["sr-only"]} "Add"]])

(defn- add-asset-popover
  [state draft]
  (let [{:keys [search-query markets candidate-rows active-index market-keys]}
        (optimizer-view-model/universe-panel-model state draft)
        searching? (seq search-query)]
    [:div {:class ["optimizer-draft-add-asset-popover"
                   "optimizer-draft-add-asset-popover--dark"
                   "fixed" "inset-x-2" "top-16" "z-30" "max-h-[calc(100vh-5rem)]"
                   "overflow-hidden" "border" "border-base-300" "p-3"
                   "shadow-[0_12px_32px_rgba(0,0,0,0.45)]"
                   "md:absolute" "md:inset-auto" "md:right-0" "md:top-[calc(100%+4px)]"
                   "md:w-[380px]" "md:max-h-[224px]"]
           :data-role "portfolio-optimizer-draft-add-asset-popover"}
     [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
      [:div
       [:p {:class ["font-mono" "text-[0.625rem]" "font-semibold" "uppercase" "tracking-[0.08em]"
                    "text-trading-muted/70"]}
        "Add to universe"]
       [:p {:class ["mt-1" "text-[0.6875rem]" "font-semibold" "uppercase" "tracking-[0.08em]"
                    "text-trading-text"]}
        "Search a tradable asset"]
       [:p {:class ["mt-1" "text-[0.6875rem]" "leading-4" "text-trading-muted"]}
        "Adds to the scenario's universe and re-solves. Targets and frontier will refresh."]]
      [:button {:type "button"
                :class ["font-mono" "text-xs" "text-trading-muted" "hover:text-warning"]
                :aria-label "Close add asset"
                :data-role "portfolio-optimizer-draft-add-asset-close"
                :on {:click [[:actions/set-portfolio-optimizer-draft-add-asset-open false]]}}
       "x"]]
     [:div {:class ["optimizer-universe-search-shell"
                    "mt-3" "flex" "items-center" "gap-1.5" "border" "px-2"
                    "portfolio-optimizer-universe-search-shell"
                    (if searching?
                      "border-warning/70"
                      "border-base-300")
                    "bg-transparent"]
            :data-role "portfolio-optimizer-draft-add-asset-search-shell"
            :data-searching (when searching? "true")}
      [:span {:class ["optimizer-universe-search-affordance"
                      "portfolio-optimizer-universe-search-affordance"
                      "font-mono" "text-[0.65rem]" "text-trading-muted"]
              :data-role "portfolio-optimizer-draft-add-asset-search-icon"}
       "⌕"]
      [:input {:type "search"
               :class (into add-asset-input-class ["optimizer-universe-search-field"
                                                   "portfolio-optimizer-universe-search-field"
                                                   "border-0" "bg-transparent" "px-0"
                                                   "focus:border-0"])
               :placeholder "Ticker or name (e.g. TIA, Injective)..."
               :data-role "portfolio-optimizer-draft-add-asset-search-input"
               :aria-controls "portfolio-optimizer-draft-add-asset-search-results"
               :aria-activedescendant (when (seq markets)
                                        (str "portfolio-optimizer-draft-add-asset-candidate-"
                                             active-index))
               :value search-query
               :replicant/on-render search-input-mount-focus!
               :on {:input [[:actions/set-portfolio-optimizer-universe-search-query
                             [:event.target/value]]]
                    :keydown [[:actions/handle-portfolio-optimizer-draft-add-asset-keydown
                               [:event/key]
                               market-keys]]}}]]
     (if (seq candidate-rows)
       (into [:div {:class ["optimizer-draft-add-asset-results"
                            "mt-2" "max-h-[220px]" "overflow-auto" "border"
                            "border-base-300" "bg-base-200/80"]
                    :id "portfolio-optimizer-draft-add-asset-search-results"
                    :role "listbox"
                    :data-role "portfolio-optimizer-draft-add-asset-search-results"}]
             (cons (candidate-table-header)
                   (map-indexed (fn [idx row]
                                  (candidate-row row idx active-index))
                                candidate-rows)))
       [:p {:class ["mt-2" "border" "border-base-300" "bg-base-200/70" "p-2"
                    "text-xs" "text-trading-muted"]
            :data-role "portfolio-optimizer-draft-add-asset-search-results-empty"}
        "No matching unused instruments found."])]))

(defn- add-asset-controls
  [state draft]
  (when (and state draft)
    (let [open? (true? (get-in state contracts/ui-draft-add-asset-open-path))]
      [:div {:class ["relative" "flex" "items-center" "gap-2"]}
       [:button {:type "button"
                 :class ["border" "border-base-300" "bg-base-200/40" "px-2.5" "py-1"
                         "font-mono" "text-[0.65625rem]" "font-semibold"
                         "text-trading-text" "hover:border-warning/60"
                         "hover:text-warning"]
                 :data-role "portfolio-optimizer-draft-add-asset"
                 :aria-expanded (if open? "true" "false")
                 :on {:click [[:actions/set-portfolio-optimizer-draft-add-asset-open
                               true]]}}
        "+ Add asset"]
       (when open?
         (add-asset-popover state draft))])))

(defn- exposure-table-body
  [result draft selected-risk-instrument]
  (let [{:keys [groups]} (rebalance-view-model/target-exposure-table-model result
                                                                            {:draft draft})
        max-delta (max-abs-delta groups)
        structure? (some? (:risk-structure result))
        ctx {:max-delta max-delta
             :result result
             :structure? structure?
             :selected-id (when structure?
                            (structure-model/selected-instrument
                             result
                             selected-risk-instrument))}]
    [:div {:class ["overflow-auto"]}
      [:table {:class ["w-full" "border-collapse" "text-[0.6875rem]"]}
       [:thead
        [:tr
         [:th {:class ["sticky" "top-0" "w-5" "border-b" "border-base-300" "bg-base-100" "px-2" "py-1.5" "text-left" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} ""]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-left" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Asset"]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-2" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Side"]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Current"]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]} "Target"]
         [:th {:class ["sticky" "top-0" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]
               :data-role "portfolio-optimizer-target-exposure-change-header"} "Change"]
         [:th {:class ["sticky" "top-0" "w-[76px]" "border-b" "border-base-300" "bg-base-100" "px-3" "py-2" "text-right" "font-mono" "text-[0.58rem]" "font-normal" "uppercase" "tracking-[0.06em]" "text-trading-muted/70"]
               :data-role "portfolio-optimizer-target-exposure-delta-bar-header"
               :aria-label "Rebalance magnitude"} ""]]]
	       (into
	        [:tbody]
	        (mapcat
	         (fn [{:keys [rows] :as group}]
	            (concat
	             [(exposure-group-row ctx group)]
	             (map (partial exposure-row ctx) rows)))
	          groups))]]))

(def ^:private exposure-table-memo (volatile! nil))

(defn- memoized-exposure-table-body
  [result draft selected-risk-instrument]
  (let [inputs [result draft selected-risk-instrument]
        cached @exposure-table-memo]
    (if (and cached (= inputs (:inputs cached)))
      (:hiccup cached)
      (let [hiccup (exposure-table-body result draft selected-risk-instrument)]
        (vreset! exposure-table-memo {:inputs inputs :hiccup hiccup})
        hiccup))))

(defn target-exposure-table
  ([result]
   (target-exposure-table result nil))
  ([result {:keys [state draft selected-risk-instrument]}]
   [:section {:class ["optimizer-target-exposure-table"
                      "min-h-0" "border-r" "border-base-300" "bg-base-100/95" "leading-4"]
              :data-role "portfolio-optimizer-target-exposure-table"}
    [:div {:class ["optimizer-target-exposure-header" "flex" "items-center" "justify-between" "gap-3" "border-b"
                   "border-base-300" "px-4" "py-3"]}
     [:div {:class ["optimizer-target-exposure-header-copy"]}
      [:p {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
       "Allocation"]
      [:p {:class ["mt-1" "text-xs" "text-trading-text"]}
       "By asset · click to expand legs"]]
     [:div {:class ["optimizer-target-exposure-header-controls" "flex" "items-center" "gap-2"]}
      [:div {:class ["flex" "border" "border-base-300" "text-[0.62rem]" "font-semibold"
                     "uppercase" "tracking-[0.06em]"]}
       [:button {:type "button"
                 :class ["border-r" "border-base-300" "bg-base-200/60" "px-3" "py-1" "text-trading-text"]}
        "By Asset"]
       [:button {:type "button"
                 :class ["px-3" "py-1" "text-trading-muted"]}
        "By Leg"]]
      (add-asset-controls state draft)]]
    (memoized-exposure-table-body result draft selected-risk-instrument)]))
