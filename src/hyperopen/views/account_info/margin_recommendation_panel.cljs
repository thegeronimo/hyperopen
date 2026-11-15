(ns hyperopen.views.account-info.margin-recommendation-panel
  "Anchored popover showing the modeled isolated-margin recommendation for one
  position, laid out to the designer's card: current-state stats, the green
  recommendation block, the modeled probability-of-liquidation-vs-collateral
  curve with Current/Recommended markers, how-we-estimated + buffers columns,
  the target-risk selector, and the apply / custom-margin actions.

  This is a page-local, recoverable control, so per docs/FRONTEND.md it is an
  anchored popover (like the sibling Reduce/Margin popovers in this table) —
  not a full-screen modal. It floats from the risk chip that opens it, sized
  and clamped to the viewport by hyperopen.views.ui.anchored-popover."
  (:require [clojure.string :as str]
            [hyperopen.margin-rec.state :as margin-rec-state]
            [hyperopen.views.account-info.margin-rec-copy :as copy]
            [hyperopen.views.account-info.margin-rec-curve :as margin-rec-curve]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.ui.anchored-popover :as anchored-popover]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]
            [hyperopen.views.ui.hint-tooltip :as hint]))

(defn- fmt-usd
  [value]
  (if (number? value)
    (str "$" (shared/format-currency value))
    "--"))

(defn- fmt-usdc
  [value]
  (if (number? value)
    (str "$" (shared/format-currency value) " USDC")
    "--"))

(defn- fmt-price
  "New-liquidation-price is a computed estimate, not an exchange tick-size
  price string, so it is always shown to 2 decimal places — unlike
  `shared/format-trade-price`, which (lacking a raw reference) preserves
  however many fractional digits the float's own noise happens to produce."
  [value]
  (if (number? value)
    (str "$" (shared/format-currency value))
    "--"))

(defn- fmt-probability
  [p]
  (cond
    (not (number? p)) "--"
    (< p 0.0005) "<0.1%"
    :else (str (.toFixed (* 100 p) 1) "%")))

(defn- fmt-percent
  [frac digits]
  (if (number? frac)
    (str (.toFixed (* 100 frac) digits) "%")
    "--"))

(defn horizon-copy
  [{:keys [hours source samples]}]
  (let [duration (cond
                   (not (number? hours)) "--"
                   (< hours 24) (str (js/Math.round hours) " hours")
                   (< hours 42) "1 day"
                   :else (str (js/Math.round (/ hours 24)) " days"))
        basis (case source
                :per-coin (str "inferred from " samples
                               " similar position episodes")
                :account (str "typical hold across " samples
                              " markets you trade")
                :override "manual override"
                "default horizon — limited trade history")]
    {:duration duration :basis basis}))

(defn- recommendation-strip
  "Horizontal summary bar above the (now full-width) chart: the recommended
  margin headline, the before/after liquidation probability, and the resulting
  liquidation price. Stacks vertically on narrow viewports."
  [{:keys [as-of p-now p-after recommended]}]
  (let [{:keys [equity additional new-liquidation-px new-liq-change-frac]} recommended
        current-equity (:equity as-of)
        vs-current (when (and (number? additional)
                              (number? current-equity)
                              (pos? current-equity)
                              (>= additional 0.01))
                     (/ additional current-equity))]
    [:div {:class ["rounded-lg" "border" "border-trading-green/30" "bg-base-200"]
           :data-role "margin-rec-summary"}
     [:div {:class ["grid" "divide-x" "divide-base-300" "sm:grid-cols-3"]}
      [:div {:class ["px-3" "py-2.5"]
             :data-role "margin-rec-recommended"}
       (hint/attach (copy/tip :recommended-margin)
                    [:div {:class ["inline-block" "text-xs" "text-trading-text-secondary"]}
                     "Recommended isolated margin"]
                    {:placement :bottom-start})
       [:div {:class ["mt-0.5" "flex" "flex-wrap" "items-baseline" "gap-x-2" "gap-y-1"]}
        [:span {:class ["text-xl" "font-semibold" "num" "text-trading-green"]}
         (fmt-usdc equity)]
        (when vs-current
          (hint/attach (copy/tip :vs-current)
                       [:span {:class ["rounded" "bg-trading-green/15" "px-1.5"
                                       "py-0.5" "text-xs" "font-medium"
                                       "text-trading-green"]}
                        (str "+" (fmt-percent vs-current 1) " vs current")]
                       {:placement :bottom-start}))]
       (when (and (number? additional) (>= additional 0.01))
         (hint/attach (copy/tip :additional)
                      [:div {:class ["mt-0.5" "inline-block" "text-xs"
                                     "text-trading-text-secondary"]
                             :data-role "margin-rec-additional"}
                       (str "Add " (fmt-usdc additional) " to your current "
                            (fmt-usd current-equity))]
                      {:placement :bottom-start}))]
      [:div {:class ["px-3" "py-2.5"]
             :data-role "margin-rec-risk-delta"}
       (hint/attach (copy/tip :liq-probability)
                    [:div {:class ["inline-block" "text-xs" "text-trading-text-secondary"]}
                     "Modeled liq. probability"]
                    {:placement :bottom-start})
       [:div {:class ["mt-0.5" "flex" "items-center" "gap-2"]}
        [:span {:class ["text-lg" "font-semibold" "num" "text-amber-300"]}
         (fmt-probability p-now)]
        [:span {:class ["text-sm" "text-trading-text-secondary"]} "→"]
        [:span {:class ["text-lg" "font-semibold" "num" "text-trading-green"]}
         (fmt-probability p-after)]]
       (hint/attach (copy/tip :horizon-scope)
                    [:div {:class ["inline-block" "text-xs" "text-trading-text-secondary"]}
                     "before next intervention"]
                    {:placement :bottom-start})]
      [:div {:class ["px-3" "py-2.5"]
             :data-role "margin-rec-new-liq"}
       (hint/attach (copy/tip :new-liq)
                    [:div {:class ["inline-block" "text-xs" "text-trading-text-secondary"]}
                     "New liquidation price (est.)"]
                    {:placement :bottom-end})
       [:div {:class ["mt-0.5" "text-lg" "font-semibold" "num" "text-trading-text"]}
        (fmt-price new-liquidation-px)]
       (when (number? new-liq-change-frac)
         [:div {:class ["text-xs" "text-trading-text-secondary"]}
          (str "≈ " (fmt-percent (js/Math.abs new-liq-change-frac) 1)
               (if (pos? new-liq-change-frac) " below" " above")
               " current liq. price")])]]]))

(defn- method-row
  ([label value] (method-row label value nil))
  ([label value hint-text]
   (hint/attach hint-text
                [:div {:class ["flex" "items-baseline" "justify-between" "gap-2"
                               "text-xs"]}
                 [:span {:class ["flex" "min-w-0" "items-baseline" "gap-1.5"
                                 "text-trading-text-secondary"]}
                  [:span {:class ["shrink-0" "text-trading-green"]} "✓"]
                  [:span {:class ["min-w-0"]} label]]
                 [:span {:class ["shrink-0" "num" "text-trading-text"]} value]]
                {:placement :top-start})))

(defn- methods-column
  [{:keys [sigma horizon paths-count]} coin-label]
  (let [horizon* (horizon-copy horizon)]
    [:div {:class ["min-w-0" "space-y-1.5"]
           :data-role "margin-rec-methods"}
     [:div {:class ["text-xs" "font-semibold" "text-trading-text"]}
      "How we estimated this"]
     (method-row "365-day crypto volatility convention" "Applied"
                 (copy/tip :vol-convention))
     (method-row (str "Recent realized volatility (" coin-label ")")
                 (fmt-percent (:annualized sigma) 0)
                 (copy/tip :realized-vol))
     (method-row (if (number? paths-count)
                   (str "Scenario simulation ("
                        (.toLocaleString paths-count "en-US") " paths)")
                   "Scenario simulation")
                 "Monte Carlo"
                 (copy/simulation-tip paths-count))
     (method-row "Trade-history-derived horizon"
                 (:duration horizon*)
                 (copy/horizon-tip (:basis horizon*)))]))

(defn- buffers-column
  [{:keys [breakdown recommended]}]
  (let [total (:equity recommended)]
    (into [:div {:class ["min-w-0" "space-y-1.5"]
                 :data-role "margin-rec-buffers"}
           (hint/attach (copy/tip :buffers-section)
                        [:div {:class ["inline-block" "text-xs" "font-semibold"
                                       "text-trading-text"]}
                         "Recommended margin components"]
                        {:placement :top-end})]
          (map (fn [{:keys [key label amount]}]
                 (hint/attach (get copy/buffer-tips key)
                              [:div {:class ["flex" "items-baseline" "justify-between"
                                             "gap-2" "text-xs"]
                                     :replicant/key (str "margin-rec-buffer-" (name key))
                                     :data-role (str "margin-rec-buffer-" (name key))}
                               [:span {:class ["min-w-0" "text-trading-text-secondary"]} label]
                               [:span {:class ["shrink-0" "num" "text-trading-text"]}
                                (str (fmt-usd amount)
                                     (when (and (number? amount) (number? total) (pos? total))
                                       (str " (" (js/Math.round (* 100 (/ amount total))) "%)")))]]
                              {:placement :top-end}))
               breakdown))))

(def risk-mode-options
  [[:conservative "Conservative" "~1% risk" :risk-conservative :top-start]
   [:balanced "Balanced" "~2% risk" :risk-balanced :top]
   [:capital-efficient "Capital efficient" "~5% risk" :risk-capital-efficient :top-end]])

(defn- risk-mode-control
  [active-mode]
  [:div {:data-role "margin-rec-risk-mode"}
   (hint/attach (copy/tip :risk-target)
                [:div {:class ["mb-1.5" "inline-block" "text-xs"
                               "text-trading-text-secondary"]}
                 "Target liquidation risk"]
                {:placement :top-start})
   (into [:div {:class ["grid" "grid-cols-3" "gap-1.5"]}]
         (map (fn [[mode label sub tip-key placement]]
                (let [active? (= mode active-mode)]
                  (hint/attach
                   (copy/tip tip-key)
                   [:button {:type "button"
                             :aria-pressed (if active? "true" "false")
                             :aria-label (str label " (" sub " target)")
                             :data-role (str "margin-rec-risk-mode-" (name mode))
                             :class (into ["rounded-md" "border" "px-2" "py-2"
                                           "text-center" "text-xs" "font-medium"
                                           "transition-colors" "focus:outline-none"
                                           "focus:ring-1" "focus:ring-ho-text-muted/40"]
                                          (if active?
                                            ["border-trading-green" "bg-trading-green/10"
                                             "text-trading-text"]
                                            ["border-base-300" "bg-transparent"
                                             "text-trading-text-secondary"
                                             "hover:text-trading-text"]))
                             :on {:click [[:actions/set-margin-rec-risk-mode mode]]}}
                    [:span {:class ["block"]} label]
                    [:span {:class ["block" "text-xs" "text-trading-text-secondary"]} sub]]
                   {:placement placement :cursor? false :delay? true})))
              risk-mode-options))
   (hint/attach (copy/tip :settings-note)
                [:div {:class ["mt-1.5" "text-center" "text-xs"
                               "text-trading-text-secondary"]}
                 "You can adjust this anytime in Settings."]
                {:placement :top})])

(defn- advanced-details
  "Collapsible disclosure holding the supplementary methodology and buffer
  breakdown, so the chart above can take the full width. Native <details> keeps
  the content in the DOM and needs no app state; a stable :replicant/key stops
  a re-render from recreating the element and closing it."
  [rec-result coin-label]
  [:details {:class ["group" "rounded-lg" "border" "border-base-300" "bg-base-300/20"]
             :replicant/key "margin-rec-advanced"
             :data-role "margin-rec-advanced"}
   [:summary {:class ["flex" "cursor-pointer" "list-none" "items-center" "justify-between"
                      "gap-2" "rounded-lg" "px-3" "py-2" "text-xs" "font-medium"
                      "text-trading-text" "transition-colors" "hover:bg-base-300/50"
                      "group-open:rounded-b-none"
                      "focus:outline-none" "focus-visible:ring-1"
                      "focus-visible:ring-ho-text-muted/40"]}
    [:span "How we estimated this & the margin breakdown"]
    [:svg {:class ["h-4" "w-4" "shrink-0" "text-trading-text-secondary"
                   "transition-transform" "duration-150" "group-open:rotate-180"]
           :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden true}
     [:path {:fill-rule "evenodd" :clip-rule "evenodd"
             :d "M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.168l3.71-3.938a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z"}]]]
   [:div {:class ["grid" "gap-4" "border-t" "border-base-300" "px-3" "py-3"
                  "sm:grid-cols-2"]}
    (methods-column rec-result coin-label)
    (buffers-column rec-result)]])

(defn- status-note
  [message role]
  [:div {:class ["rounded-md" "bg-base-300/40" "px-3" "py-2" "text-xs"
                 "text-trading-text-secondary"]
         :data-role role}
   message])

(def ^:private dialog-focus-on-render
  (dialog-focus/dialog-focus-on-render
   {:restore-selector "[data-role='margin-rec-risk-chip']"}))

(defn- panel-shell
  [coin-label leverage-label anchor children]
  (let [layout-style (anchored-popover/centered-overlay-layout-style
                      {:anchor anchor
                       :preferred-width-px 780
                       :preferred-height-px 680})]
    [:div {:class ["fixed" "z-[240]" "flex" "max-h-[calc(100vh-1.5rem)]" "flex-col"
                   "overflow-hidden" "rounded-xl" "border" "border-base-300" "bg-base-200"
                   "shadow-[0_24px_70px_rgba(0,0,0,0.55)]"]
           :style layout-style
           :role "dialog"
           :aria-label "Margin recommendation"
           :tabindex "-1"
           :replicant/on-render dialog-focus-on-render
           :on {:keydown [[:actions/handle-margin-rec-panel-keydown [:event/key]]]}
           :data-role "margin-rec-panel"}
     [:div {:class ["flex" "shrink-0" "items-center" "gap-3" "border-b"
                    "border-base-300" "px-4" "py-3"]}
      [:div {:class ["flex" "min-w-0" "flex-wrap" "items-center" "gap-2"]}
       (hint/attach (copy/tip :header)
                    [:span {:class ["text-base" "font-semibold" "text-trading-text"]}
                     "Margin recommendation"]
                    {:placement :bottom-start})
       (hint/attach (copy/tip :coin)
                    [:span {:class ["rounded" "bg-trading-green/15" "px-1.5" "py-0.5"
                                    "text-xs" "font-semibold" "text-trading-green"]
                            :data-role "margin-rec-coin"}
                     coin-label]
                    {:placement :bottom-start})
       (when leverage-label
         (hint/attach (copy/tip :leverage)
                      [:span {:class ["text-xs" "text-trading-text-secondary"]
                              :data-role "margin-rec-leverage"}
                       leverage-label]
                      {:placement :bottom-start}))]
      (hint/attach
       (copy/tip :close)
       [:button {:type "button"
                 :data-role "margin-rec-panel-close"
                 :class ["ml-auto" "inline-flex" "h-7" "w-7" "shrink-0" "items-center"
                         "justify-center" "rounded-md" "text-sm"
                         "text-trading-text-secondary" "transition-colors"
                         "hover:bg-base-300" "hover:text-trading-text"
                         "focus:outline-none" "focus:ring-1"
                         "focus:ring-ho-text-muted/40"]
                 :aria-label (copy/tip :close)
                 :on {:click [[:actions/close-margin-rec-panel]]}}
        "✕"]
       {:placement :bottom-end :cursor? false :delay? true})]
     (into [:div {:class ["min-h-0" "space-y-3" "overflow-y-auto" "px-4" "py-3"]}]
           children)]))

(defn- apply-button
  [position-data additional placement]
  (hint/attach
   (copy/tip :apply)
   [:button {:type "button"
             :data-role "margin-rec-apply"
             :aria-label "Apply recommendation"
             :class ["h-10" "w-full" "rounded-md" "bg-trading-green" "px-3"
                     "text-sm" "font-semibold" "text-base-100"
                     "transition-colors" "hover:bg-trading-green/90"
                     "focus:outline-none" "focus:ring-1"
                     "focus:ring-trading-green/40"]
             :on {:click [[:actions/open-position-margin-modal
                           (assoc position-data
                                  :prefill-margin-mode :add
                                  :prefill-margin-amount additional)
                           :event.currentTarget/bounds]
                          [:actions/close-margin-rec-panel]]}}
    "Apply recommendation"]
   {:placement placement :cursor? false :delay? true}))

(defn- custom-button
  [position-data placement]
  (hint/attach
   (copy/tip :custom)
   [:button {:type "button"
             :data-role "margin-rec-custom"
             :aria-label "Set custom margin"
             :class ["h-10" "w-full" "rounded-md" "border" "border-base-300"
                     "px-3" "text-sm" "font-medium" "text-trading-text"
                     "transition-colors" "hover:bg-base-300"
                     "focus:outline-none" "focus:ring-1"
                     "focus:ring-ho-text-muted/40"]
             :on {:click [[:actions/open-position-margin-modal
                           position-data
                           :event.currentTarget/bounds]
                          [:actions/close-margin-rec-panel]]}}
    "Set custom margin"]
   {:placement placement :cursor? false :delay? true}))

(defn- ready-panel
  [{:keys [rec-result row-vm read-only? risk-mode]}]
  (let [rec-result (margin-rec-state/select-risk-mode rec-result risk-mode)
        {:keys [as-of p-now p-after recommended curve status]} rec-result
        {:keys [additional]} recommended
        position-data (:row-data row-vm)
        actionable? (and (not read-only?)
                         (= :ok status)
                         (number? additional)
                         (>= additional 0.01))]
    [
     (recommendation-strip rec-result)
     (when (= :within-target status)
       (status-note "Current margin already meets the selected risk target."
                    "margin-rec-within-target"))
     (margin-rec-curve/curve-card curve (:equity as-of) p-now
                                  (:equity recommended) p-after)
     (advanced-details rec-result (:coin-label row-vm))
     (risk-mode-control risk-mode)
     (cond
       read-only? nil
       actionable? [:div {:class ["grid" "grid-cols-1" "gap-2" "sm:grid-cols-2"]}
                    (apply-button position-data additional :top-start)
                    (custom-button position-data :top-end)]
       :else (custom-button position-data :top))
     (when-not read-only?
       [:div {:class ["text-xs" "leading-relaxed" "text-trading-text-secondary"]
              :data-role "margin-rec-disclaimer"}
        (copy/tip :disclaimer)])]))

(defn- leverage-copy
  [row-vm]
  (let [value (get-in row-vm [:position :leverage :value])
        mode (some-> (:margin-mode-label row-vm) str/lower-case)]
    (cond
      (and value mode) (str value "x " mode)
      mode mode
      :else nil)))

(defn margin-recommendation-panel
  "options: {:position-key :rec :row-vm :read-only? :risk-mode :anchor}
  `rec` is the [:margin-rec :recs <key>] entry; `anchor` is the trigger's
  bounds the popover is positioned against."
  [{:keys [position-key rec row-vm anchor] :as options}]
  (when (and position-key row-vm)
    (let [{:keys [status result error]} rec
          coin-label (or (:coin-label row-vm) position-key)]
      (panel-shell
       coin-label
       (leverage-copy row-vm)
       anchor
       (cond
         (nil? rec)
         [(status-note "Modeling liquidation risk in the background…"
                       "margin-rec-computing")]

         (= :error status)
         [(status-note (str "Could not model this position: " (or error "unknown error"))
                       "margin-rec-error")]

         (= :insufficient-history status)
         [(status-note
           (str "Not enough hourly price history to model liquidation risk yet"
                " (" (or (:n-bars result) (:n-bars rec) 0) " bars).")
           "margin-rec-insufficient")]

         (= :invalid status)
         [(status-note "This position cannot be modeled." "margin-rec-invalid")]

         (contains? #{:ok :within-target} status)
         (ready-panel (assoc options :rec-result result))

         :else
         [(status-note "Modeling liquidation risk in the background…"
                       "margin-rec-computing")])))))
