(ns hyperopen.views.portfolio.optimize.setup-universe
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.portfolio.optimizer.application.view-model.universe :as universe-vm]
            [hyperopen.portfolio.optimizer.application.view-model.universe-search :as universe-search]
            [hyperopen.views.portfolio.optimize.setup-universe-search :as universe-search-view]))

(def ^:private section-title-class
  ["text-[0.875rem]" "font-semibold" "text-trading-text"])

(defn- tag
  ([label tone]
   (tag label tone nil))
  ([label tone extra-class]
   [:span {:class (cond-> ["optimizer-chip" "border" "px-1.5" "py-[1px]" "font-mono"
                           "text-[0.625rem]" "font-semibold" "uppercase"
                           "tracking-[0.12em]"]
                    (= tone :accent) (conj "border-warning/40" "text-warning")
                    (= tone :info) (conj "border-info/40" "text-info")
                    (= tone :long) (conj "border-success/40" "text-success")
                    (= tone :warn) (conj "border-warning/40" "text-warning")
                    (= tone :muted) (conj "border-base-300" "text-trading-muted")
                    extra-class (conj extra-class))
           :data-optimizer-chip "true"
           :data-tone (name tone)}
    label]))

(defn- market-type-tags
  [market-type]
  [:span {:class ["flex" "items-center" "gap-1"]}
   (when (= :spot market-type)
     (tag "spot" :info))
   (when (= :perp market-type)
     (tag "perp" :accent))
   (when (= :vault market-type)
     (tag "vault" :info))])

(defn- side-button
  [instrument-id selected-side short-selectable? side]
  (let [selected? (= selected-side side)
        enabled? (or (= :long side)
                     short-selectable?)]
    [:button {:type "button"
              :class (cond-> ["h-6" "w-6" "border" "border-base-300"
                              "font-mono" "text-[0.6875rem]" "font-semibold"
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
              :aria-label (str "Set " instrument-id " "
                               (name side))
              :aria-pressed (if selected? "true" "false")
              :data-role (str "portfolio-optimizer-universe-side-"
                              (name side)
                              "-"
                              instrument-id)
              :data-selected (when selected? "true")
              :disabled (when-not enabled? true)
              :on (when enabled?
                    {:click [[:actions/set-portfolio-optimizer-universe-instrument-side
                              instrument-id
                              side]]})}
     (case side
       :short "S"
       "L")]))

(defn- side-control
  [instrument-id position-side short-selectable?]
  [:span {:class ["flex" "items-center" "justify-center"]
          :data-role (str "portfolio-optimizer-universe-side-control-"
                          instrument-id)}
   (side-button instrument-id position-side short-selectable? :long)
   (side-button instrument-id position-side short-selectable? :short)])

(defn- remove-aria-label
  [primary-label market-type instrument-id]
  (str "Remove "
       (or primary-label instrument-id "instrument")
       (when-let [market-type-label (some-> market-type name)]
         (str " " market-type-label))))

(def ^:private assumption-chip-tooltip-class
  ["pointer-events-none" "absolute" "left-0" "top-[calc(100%+6px)]"
   "z-30" "w-[min(22rem,calc(100vw-2rem))]" "border"
   "border-base-300" "bg-base-100" "px-2" "py-1.5"
   "font-sans" "text-[0.75rem]" "font-normal"
   "normal-case" "leading-[1.45]" "tracking-normal"
   "text-trading-muted" "opacity-0" "shadow-[0_12px_32px_rgba(0,0,0,0.45)]"
   "group-hover:opacity-100" "group-focus-within:opacity-100"])

(defn- assumption-workflow-chip
  [{:keys [instrument-id assumption-badge assumption-badge-label assumption-badge-tooltip]}]
  (when (contains? universe-vm/workflow-assumption-badges assumption-badge)
    (let [tone (cond
                 (contains? #{:conservative :proxy-behavior} assumption-badge)
                 ["border-success/50" "text-success" "bg-success/10"]

                 (= :short-history assumption-badge)
                 ["border-base-300" "text-trading-muted" "bg-base-200/40"]

                 :else
                 ["border-warning/50" "text-warning" "bg-warning/10"])
          chip-class (into ["inline-block" "border" "px-1" "py-px" "font-mono"
                            "text-[0.5625rem]" "font-semibold" "uppercase"
                            "tracking-[0.08em]"]
                           tone)
          badge-role (str "portfolio-optimizer-universe-assumption-badge-" instrument-id)]
      (if-not assumption-badge-tooltip
        [:span {:class (into ["mt-0.5"] chip-class)
                :data-role badge-role
                :data-badge (name assumption-badge)}
         assumption-badge-label]
        (let [tooltip-id (str "portfolio-optimizer-universe-assumption-tooltip-" instrument-id)]
          [:span {:class ["group" "relative" "mt-0.5" "inline-block"]}
           [:span {:class (conj chip-class "cursor-help")
                   :data-role badge-role
                   :data-badge (name assumption-badge)
                   :tabindex 0
                   :aria-describedby tooltip-id}
            assumption-badge-label]
           [:span {:class assumption-chip-tooltip-class
                   :id tooltip-id
                   :role "tooltip"
                   :data-role tooltip-id}
            assumption-badge-tooltip]])))))

(defn- duplicate-secondary?
  "A sublabel that just repeats the ticker (DOT/DOT, S/S, GRAM/GRAM) is a
  wasted line on most rows of a holdings-seeded universe — render it only when
  it adds information."
  [primary-label secondary-label]
  (and (some? primary-label)
       (some? secondary-label)
       (= (str/lower-case (str primary-label))
          (str/lower-case (str secondary-label)))))

(defn- selected-row
  [{:keys [instrument-id
           market-type
           primary-label
           secondary-label
           history-status
           position-side
           short-selectable?
           twin-underlying
           twin-last?]
    :as row}
   show-type?
   search-match?]
    [:div {:class (cond-> ["optimizer-universe-row"
                           (when-not show-type? "optimizer-universe-cols-4")
                           "grid" "items-center" "gap-2" "border-b" "border-base-300"
                           "px-2" "py-1.5" "last:border-b-0" "hover:bg-base-200/30"]
                    twin-underlying (conj "optimizer-universe-twin-row" "bg-warning/5")
                    (and twin-underlying (not twin-last?)) (conj "border-b-0")
                    search-match? (conj "bg-info/10" "outline" "outline-1"
                                        "-outline-offset-1" "outline-info/60"))
           :data-role (str "portfolio-optimizer-universe-selected-row-" instrument-id)
           :data-history-status (some-> history-status name)
           :data-twin-of (when twin-underlying twin-underlying)
           :data-search-match (when search-match? "true")}
     [:span {:class (if twin-underlying
                      ["border-l-2" "border-warning/70" "pl-1" "text-warning"]
                      ["text-warning"])}
      "☑"]
     [:span {:class ["min-w-0"]}
      [:span {:class ["block" "truncate" "font-mono" "text-[0.8125rem]" "font-semibold"]}
       primary-label]
      (cond
        search-match?
        [:span {:class ["block" "truncate" "text-[0.6875rem]" "text-info"]
                :data-role (str "portfolio-optimizer-universe-search-match-" instrument-id)}
         "In your mix · matches your search"]

        (not (duplicate-secondary? primary-label secondary-label))
        [:span {:class ["block" "truncate" "text-[0.6875rem]" "text-trading-muted/80"]}
         secondary-label])
      (assumption-workflow-chip row)]
     (when show-type?
       [:span {:class ["flex" "justify-center"]} (market-type-tags market-type)])
     (side-control instrument-id position-side short-selectable?)
     [:span {:class ["text-right"]}
      [:button {:type "button"
                :class ["font-mono" "text-[0.8125rem]" "text-trading-muted" "hover:text-warning"]
                :aria-label (remove-aria-label primary-label market-type instrument-id)
                :data-role (str "portfolio-optimizer-universe-remove-" instrument-id)
                :on {:click [[:actions/remove-portfolio-optimizer-universe-instrument
                               instrument-id]]}}
       "x"]]])

(defn- twin-group-header
  [underlying twin-count]
  [:div {:class ["optimizer-universe-twin-header" "flex" "items-center" "gap-2"
                 "bg-warning/5" "px-2" "pt-1.5" "pb-0.5" "font-mono"
                 "text-[0.625rem]" "font-semibold" "uppercase" "tracking-[0.1em]"
                 "text-warning"]
         :data-role (str "portfolio-optimizer-universe-twin-header-" underlying)}
   [:svg {:width "12" :height "12" :viewBox "0 0 24 24" :fill "none"
          :stroke "currentColor" :stroke-width "2.5" :stroke-linecap "round"
          :stroke-linejoin "round" :aria-hidden "true"}
    [:path {:d "M9 17H7A5 5 0 0 1 7 7h2"}]
    [:path {:d "M15 7h2a5 5 0 1 1 0 10h-2"}]
    [:line {:x1 "8" :y1 "12" :x2 "16" :y2 "12"}]]
   [:span {:class ["whitespace-nowrap"]} (str underlying " × " twin-count " · same asset")]])

(defn- selected-table-rows
  [selected-rows show-type? search-match-ids]
  (mapcat (fn [{:keys [twin-underlying twin-first? twin-count instrument-id] :as row}]
            (cond-> []
              (and twin-underlying twin-first?)
              (conj (twin-group-header twin-underlying twin-count))
              :always
              (conj (selected-row row
                                  show-type?
                                  (contains? search-match-ids instrument-id)))))
          selected-rows))

(defn- selected-table-header
  [show-type?]
  [:div {:class ["optimizer-universe-selected-header"
                 (when-not show-type? "optimizer-universe-cols-4")
                 "grid" "items-center" "gap-2" "border-b" "border-base-300"
                 "bg-base-200/40" "px-2" "py-1.5" "font-mono"
                 "text-[0.625rem]" "font-semibold" "uppercase"
                 "tracking-[0.12em]" "text-trading-muted/70"]
         :data-role "portfolio-optimizer-universe-selected-header"}
   [:span ""]
   [:span "Asset"]
   (when show-type?
     [:span {:class ["text-center"]} "Type"])
   [:span {:class ["text-center"]} "Side"]
   [:span {:class ["sr-only"]} "Remove"]])

(defn- holdings-loading-block
  "Modeless inline loading state for the auto-seed window: the machine is
  fetching the portfolio on the user's behalf, so the panel says so instead of
  showing the empty-universe copy that asks the user to add assets."
  []
  [:div {:class ["px-2" "py-3"]
         :data-role "portfolio-optimizer-universe-holdings-loading"}
   [:p {:class ["text-[0.8125rem]" "font-medium" "text-trading-text"]}
    "Loading holdings…"]
   [:p {:class ["mt-1" "text-[0.75rem]" "text-trading-muted"]}
    "Fetching the current portfolio for this account."]
   [:div {:class ["mt-3" "space-y-2"]
          :aria-hidden "true"}
    [:div {:class ["optimizer-universe-skeleton-row"]}]
    [:div {:class ["optimizer-universe-skeleton-row"]}]
    [:div {:class ["optimizer-universe-skeleton-row"]}]]])

(defn- selected-table
  [selected-rows universe holdings-loading? no-importable-holdings? search-match-ids]
  (let [show-type? (< 1 (count (distinct (keep :market-type selected-rows))))]
    [:div {:class ["mt-2" "border" "border-base-300" "bg-base-100/50"]}
     (when (seq universe)
       [:div {:class ["flex" "items-center" "justify-end" "gap-2" "border-b"
                      "border-base-300" "px-2" "py-1.5"]}
        [:button {:type "button"
                  :class ["font-mono" "text-[0.6875rem]" "uppercase" "tracking-[0.12em]"
                          "text-trading-muted" "hover:text-warning"]
                  :data-role "portfolio-optimizer-universe-clear"
                  :on {:click [[:actions/clear-portfolio-optimizer-universe]]}}
         "Clear universe"]])
     (cond
       (seq universe)
       (into [:div {:class ["text-xs"]}]
             (cons (selected-table-header show-type?)
                   (selected-table-rows selected-rows show-type? search-match-ids)))

       holdings-loading?
       (holdings-loading-block)

       no-importable-holdings?
       [:div {:class ["flex" "flex-col" "items-start" "gap-1" "px-2" "py-3"]
              :data-role "portfolio-optimizer-universe-holdings-empty"}
        [:p {:class ["text-xs" "text-trading-muted"]}
         "No open positions to import for this account."]
        [:p {:class ["text-[0.75rem]" "text-trading-muted/80"]}
         "Search above to add perps, spot, or vault return legs and build a custom set."]]

       :else
       [:div {:class ["flex" "flex-col" "items-start" "gap-1" "px-2" "py-3"]}
        [:p {:class ["text-xs" "text-trading-muted"]}
         "No assets selected yet."]
        [:p {:class ["text-[0.75rem]" "text-trading-muted/80"]}
         "Holdings load automatically when your account is connected — use “My holdings” above to re-import them, or search to build a custom set."]])]))

(defn- omission-reason-copy
  [reason]
  (case reason
    :spot-excluded "spot balance — excluded while spot assets are off in constraints"
    :unusable-history "no usable optimizer history"
    "excluded"))

(defn- universe-source-line
  "Visible-automation line under the source strip: says where the universe came
  from and accounts for every asset a holdings load left out (nothing is dropped
  silently). The included-count is NOT repeated here — the panel header owns
  the one visible count."
  [universe-source]
  (let [holdings? (= :holdings (:kind universe-source))
        omitted (vec (or (:omitted universe-source) []))]
    (when universe-source
      [:div {:class ["mt-1.5" "font-mono" "text-[0.6875rem]" "leading-4" "text-trading-muted"]
             :data-role "portfolio-optimizer-universe-source-line"
             :data-universe-source (name (or (:kind universe-source) :custom))}
       (case (:kind universe-source)
         :holdings [:p (str "From holdings"
                            (when (pos? (count omitted))
                              (str " · " (count omitted) " omitted")))]
         :vault-view [:p (str "From vault view \u201c" (:label universe-source) "\u201d · "
                              (:count universe-source) " vaults")]
         [:p "Custom universe"])
       (when (and holdings? (seq omitted))
         [:details {:class ["mt-0.5"]}
          [:summary {:class ["cursor-pointer" "text-trading-muted/80" "hover:text-warning"]
                     :data-role "portfolio-optimizer-universe-omitted-summary"}
           (str "Show " (count omitted) " omitted")]
          (into [:ul {:class ["mt-1" "space-y-0.5" "pl-3" "text-trading-muted/80"]
                      :data-role "portfolio-optimizer-universe-omitted-list"}]
                (map (fn [{:keys [instrument-id label reason]}]
                       [:li {:data-omitted-instrument instrument-id}
                        (str (or label instrument-id) " — "
                             (omission-reason-copy reason))])
                     omitted))])])))

(defn universe-section
  ([state draft]
   (universe-section state draft nil))
  ([state draft opts]
   (let [model (optimizer-view-model/universe-section-model
                state
                draft
                (merge {:group-results? true}
                       opts
                       {:candidate-options
                        (merge {:limit universe-search/panel-candidate-limit}
                               (:candidate-options opts))}))
         {:keys [universe
                 readiness
                 selected-rows
                 universe-source
                 no-importable-holdings?
                 twin-underlyings
                 search-duplicate-summary]} model
         holdings-source? (= :holdings (:kind universe-source))
         vault-view-source? (= :vault-view (:kind universe-source))
         holdings-loading? (= :holdings-loading (:reason readiness))
         holdings-active? (or holdings-source? holdings-loading?)]
    [:section {:class ["optimizer-universe-panel" "optimizer-setup-panel"
                       "border" "border-base-300" "bg-base-100/90" "p-3" "leading-4"]
               :data-role "portfolio-optimizer-universe-panel"
               :data-holdings-loading (when holdings-loading? "true")}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3" "border-b"
                    "border-base-300" "pb-2"]}
      [:p {:class section-title-class} "Universe"]
      [:span {:class ["font-mono" "text-[0.75rem]" "uppercase" "tracking-[0.08em]"
                      "text-trading-muted/70"]}
       (if holdings-loading?
         "loading…"
         (str (count universe) " included"))
       (when (and (not holdings-loading?) (seq twin-underlyings))
         [:span {:class ["text-warning"]
                 :data-role "portfolio-optimizer-universe-twin-count"}
          (str " · " (count twin-underlyings)
               (if (= 1 (count twin-underlyings)) " duplicate" " duplicates"))])]]
     [:div {:class ["mt-3" "grid" (if vault-view-source? "grid-cols-3" "grid-cols-2")
                    "border" "border-base-300" "text-center"
                    "text-[0.75rem]" "font-medium"
                    "tracking-[0.04em]" "text-trading-muted"]
            :data-role "portfolio-optimizer-universe-source-strip"}
      [:button {:type "button"
                :class (cond-> ["border-r" "border-base-300" "px-2" "py-2"
                                "hover:text-warning"]
                         holdings-active?
                         (conj "border-warning/60" "bg-warning/10" "text-warning"))
                :data-role "portfolio-optimizer-universe-use-current"
                :data-active (when holdings-active? "true")
                :on {:click [[:actions/set-portfolio-optimizer-universe-from-current]]}}
       (if holdings-active? "My holdings" "Load my holdings")
       [:span {:class ["sr-only"]}
        (if holdings-active?
          "Refresh the universe from your current holdings"
          "Load my current holdings into the universe")]]
      (when vault-view-source?
        [:span {:class ["border-r" "border-base-300" "px-2" "py-2"
                        "border-warning/60" "bg-warning/10" "text-warning"]
                :data-role "portfolio-optimizer-universe-source-vault-view"
                :data-active "true"}
         "Vault view"])
      [:span {:class (cond-> ["px-2" "py-2"]
                       (and universe-source (not holdings-source?) (not vault-view-source?))
                       (conj "bg-warning/10" "text-warning"))
              :data-role "portfolio-optimizer-universe-source-custom"
              :data-active (when (and universe-source (not holdings-source?) (not vault-view-source?)) "true")}
       "Custom"]]
     (universe-source-line universe-source)
     [:div {:class ["sr-only"]} "Manual Add"]
     (universe-search-view/search-block
      model
      {:count-label universe-search/group-count-label})
     (selected-table selected-rows universe holdings-loading? no-importable-holdings?
                     (or (:existing-ids search-duplicate-summary) #{}))
     [:div {:class ["mt-2" "text-[0.6875rem]" "leading-[1.5]"
                    "text-trading-muted/70"]}
      "Search adds tradeable spot, perp, or vault return legs. Symbols with limited history use stabilized covariance with a longer pull toward the market reference."
      [:span {:class ["sr-only"]}
       "History starts loading after assets are included."]]])))
