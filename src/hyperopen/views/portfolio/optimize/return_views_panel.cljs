(ns hyperopen.views.portfolio.optimize.return-views-panel
  "The Return views panel: per-asset return inputs with honest provenance. Every
  row states whether the optimizer will use YOUR view (authored, aged, resettable)
  or the IMPLIED baseline estimate, so a machine-seeded number can never pass as
  a user forecast. Rows edit in place; edits materialize views immediately and
  autosave to the wallet's view library."
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.application.black-litterman-editor-model :as bl-model]
            [hyperopen.portfolio.optimizer.application.return-inputs :as return-inputs]
            [hyperopen.portfolio.optimizer.application.return-views :as return-views]
            [hyperopen.portfolio.optimizer.application.view-model.setup-summary :as setup-summary]
            [hyperopen.portfolio.optimizer.contracts :as optimizer-contracts]
            [hyperopen.system :as app-system]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.portfolio.optimize.instrument-display :as instrument-display]
            [nexus.registry :as nxr]))

(defn- instrument-label
  [universe instrument-id]
  (or (some (fn [instrument]
              (when (= instrument-id (:instrument-id instrument))
                (or (when (instrument-display/vault-instrument? instrument)
                      (instrument-display/primary-label instrument))
                    (:coin instrument)
                    (:symbol instrument)
                    (:name instrument))))
            universe)
      (some-> instrument-id
              (str/split #":")
              last)
      instrument-id
      "Asset"))

(defn- instrument-by-id
  [universe instrument-id]
  (some (fn [instrument]
          (when (= instrument-id (:instrument-id instrument))
            instrument))
        universe))

(defn- row-icon
  [instrument asset-label instrument-id]
  (let [icon-url (asset-icon/market-icon-url instrument)]
    [:span {:class ["optimizer-objective-view-token"
                    "inline-flex" "h-5" "w-5" "shrink-0" "items-center"
                    "justify-center" "overflow-hidden" "rounded-full"
                    "font-mono" "text-[0.625rem]" "font-semibold"]
            :data-role (str "portfolio-optimizer-objective-menu-view-"
                            instrument-id
                            "-icon")
            :aria-hidden "true"}
     (if (seq icon-url)
       [:img {:class ["block" "h-5" "w-5" "rounded-full" "object-contain"]
              :src icon-url
              :alt ""}]
       (subs asset-label 0 (min 1 (count asset-label))))]))

(def ^:private return-step-keys #{"ArrowUp" "ArrowDown"})

(defn- return-step-keydown-handler
  [instrument-id]
  (fn [event]
    (let [key (some-> event .-key)]
      (when (contains? return-step-keys key)
        (when (fn? (.-preventDefault event))
          (.preventDefault event))
        (when (fn? (.-stopPropagation event))
          (.stopPropagation event))
        (when app-system/store
          (nxr/dispatch app-system/store
                        nil
                        [[:actions/step-portfolio-optimizer-objective-menu-view-return
                          instrument-id
                          key]]))))))

(defn- row-return-text
  [state row]
  (let [ui-text (get-in state (conj optimizer-contracts/ui-objective-menu-view-drafts-path
                                    (keyword (:instrument-id row))
                                    :return-text))]
    (cond
      (some? ui-text) (str ui-text)
      (some? (:return row)) (bl-model/decimal->percent-text (:return row))
      :else "")))

(defn- source-chip
  [{:keys [instrument-id source age-label stale?]}]
  (let [user? (= :user source)]
    [:span {:class (cond-> ["optimizer-return-view-chip" "inline-flex" "items-baseline"
                            "gap-1" "border" "px-1.5" "py-px" "font-mono"
                            "text-[0.575rem]" "font-semibold" "uppercase"
                            "tracking-[0.08em]"]
                     (and user? (not stale?)) (conj "optimizer-return-view-chip--user")
                     (and user? stale?) (conj "optimizer-return-view-chip--stale")
                     (not user?) (conj "optimizer-return-view-chip--implied"))
            :data-role (str "portfolio-optimizer-objective-menu-view-"
                            instrument-id
                            "-source")
            :data-source (name source)
            :data-stale (str (boolean stale?))
            :title (if user?
                     "You set this return. The optimizer uses it as your view."
                     "Baseline estimate from stabilized history — used because you have no view here. Edit to set yours.")}
     (if user? "Your view" "Implied")
     (when user?
       [:span {:class ["normal-case" "tracking-normal" "font-normal"
                       "text-trading-muted"]}
        (cond
          (and age-label stale?) (str "· " age-label " · stale")
          age-label (str "· " age-label)
          :else nil)])]))

(def ^:private confidence-word
  {:low "Low"
   :medium "Medium"
   :high "High"})

(defn- confidence-button
  [instrument-id selected-level user? [level _]]
  (let [selected? (and user? (= level selected-level))
        word (get confidence-word level)]
    [:button {:type "button"
              :class ["optimizer-objective-view-confidence-button"
                      "inline-flex" "items-center" "justify-center"
                      "border" "border-base-300" "font-mono" "text-[0.75rem]"
                      "font-semibold"
                      "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
              :data-role (str "portfolio-optimizer-objective-menu-view-"
                              instrument-id
                              "-confidence-"
                              (name level))
              :data-selected (str selected?)
              :aria-pressed (str selected?)
              :data-tooltip (if user?
                              (str (name level) " trust in this return")
                              (str "save as your view · " (name level) " confidence"))
              :aria-label (if user?
                            (str "Set " (name level) " confidence")
                            (str "Save implied return as your view at "
                                 (name level) " confidence"))
              :on {:click [[:actions/set-portfolio-optimizer-objective-menu-view-confidence
                            instrument-id
                            level]]}}
     word]))

(defn- return-input
  [asset-label instrument-id value-text implied?]
  [:span {:class ["optimizer-objective-view-return-input-shell"
                  "relative" "inline-flex" "items-center"]}
   [:input {:type "text"
            :inputmode "decimal"
            :aria-label (str asset-label " return")
            :class (cond-> ["optimizer-objective-view-return-input"
                            "border" "border-base-300" "py-1" "pl-2" "pr-9"
                            "text-right" "font-mono" "text-[0.6875rem]"
                            "text-trading-text"
                            "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
                     implied? (conj "optimizer-objective-view-return-input--implied"))
            :data-role (str "portfolio-optimizer-objective-menu-view-"
                            instrument-id
                            "-return")
            :placeholder "—"
            :value value-text
            :on {:input [[:actions/set-portfolio-optimizer-objective-menu-view-return
                          instrument-id
                          [:event.target/value]]]
                 :keydown (return-step-keydown-handler instrument-id)}}]
   [:span {:class ["optimizer-objective-view-return-suffix"
                   "pointer-events-none" "absolute" "font-mono"
                   "text-[0.625rem]" "text-trading-muted"]}
    "%"]
   [:span {:class ["optimizer-objective-view-stepper"
                   "absolute" "inline-flex" "flex-col" "overflow-hidden"]
           :aria-hidden "false"}
    [:button {:type "button"
              :class ["optimizer-objective-view-stepper-button" "border-0" "p-0"
                      "font-mono"
                      "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
              :aria-label (str "Increase " asset-label " return")
              :data-role (str "portfolio-optimizer-objective-menu-view-"
                              instrument-id
                              "-step-up")
              :on {:click [[:actions/step-portfolio-optimizer-objective-menu-view-return
                            instrument-id
                            :up]]}}
     "▲"]
    [:button {:type "button"
              :class ["optimizer-objective-view-stepper-button" "border-0" "p-0"
                      "font-mono"
                      "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
              :aria-label (str "Decrease " asset-label " return")
              :data-role (str "portfolio-optimizer-objective-menu-view-"
                              instrument-id
                              "-step-down")
              :on {:click [[:actions/step-portfolio-optimizer-objective-menu-view-return
                            instrument-id
                            :down]]}}
     "▼"]]])

(defn- reset-button
  [asset-label instrument-id]
  [:button {:type "button"
            :class ["optimizer-return-view-reset" "border-0" "bg-transparent"
                    "px-1" "py-0" "font-mono" "text-[0.6875rem]"
                    "text-trading-muted"
                    "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
            :data-role (str "portfolio-optimizer-objective-menu-view-"
                            instrument-id
                            "-reset")
            :title "Reset to implied — clears your view"
            :aria-label (str "Reset " asset-label " to the implied return")
            :on {:click [[:actions/remove-portfolio-optimizer-objective-menu-view
                          instrument-id]]}}
   "×"])

(defn- provenance-row
  [state universe row]
  (let [instrument-id (:instrument-id row)
        asset-label (instrument-label universe instrument-id)
        instrument (instrument-by-id universe instrument-id)
        user? (= :user (:source row))]
    [:div {:class ["optimizer-objective-view-row" "optimizer-return-view-row"
                   "border" "border-base-300" "px-3" "py-2"]
           :data-role (str "portfolio-optimizer-objective-menu-view-row-"
                           instrument-id)
           :data-source (name (:source row))}
     [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
      [:div {:class ["flex" "min-w-0" "items-center" "gap-2"]}
       (row-icon instrument asset-label instrument-id)
       [:span {:class ["truncate" "text-[0.75rem]" "font-semibold" "text-trading-text"]}
        asset-label]]
      [:div {:class ["flex" "items-center" "gap-1" "font-mono" "text-[0.625rem]"
                     "text-trading-muted"]}
       [:span "return"]
       (return-input asset-label instrument-id (row-return-text state row) (not user?))]]
     [:div {:class ["mt-1.5" "flex" "min-w-0" "items-center" "gap-1"]}
      (source-chip row)
      (when user? (reset-button asset-label instrument-id))]
     [:div {:class ["optimizer-objective-view-confidence-line" "mt-2" "flex"
                    "items-center" "gap-2" "flex-wrap"]}
      [:span {:class ["optimizer-objective-view-confidence-label" "font-mono"
                      "text-[0.6875rem]" "uppercase" "tracking-[0.08em]"
                      "text-trading-muted"]}
       (if user? "Confidence" "Save as my view")]
      (into
       [:div {:class ["optimizer-objective-view-confidence" "flex"]
              :role "group"
              :aria-label (str asset-label
                               (if user?
                                 " confidence"
                                 " — save implied return as your view at confidence"))}]
       (map #(confidence-button instrument-id (:confidence-level row) user? %)
            bl-model/confidence-options))]]))

(defn- filter-chip
  [container-role active-filter summary [filter-key label]]
  (let [selected? (= filter-key active-filter)
        count* (case filter-key
                 :yours (:yours summary)
                 :implied (:implied summary)
                 :stale (:stale summary)
                 nil)]
    [:button {:type "button"
              :class ["optimizer-return-view-filter" "border" "border-base-300"
                      "px-1.5" "py-0.5" "font-mono" "text-[0.6rem]" "font-semibold"
                      "uppercase" "tracking-[0.08em]"
                      "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
              :data-role (str container-role "-filter-" (name filter-key))
              :data-selected (str selected?)
              :aria-pressed (str selected?)
              :on {:click [[:actions/set-portfolio-optimizer-return-views-filter
                            filter-key]]}}
     (if count* (str label " " count*) label)]))

(defn- io-toolbar
  [container-role summary]
  (let [empty-universe? (zero? (or (:total summary) 0))]
    [:div {:class ["mt-1.5" "flex" "flex-wrap" "items-center" "gap-1"]
           :data-role (str container-role "-io")}
     [:span {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.08em]"
                     "text-trading-muted"]}
      "File"]
     [:button {:type "button"
               :class ["optimizer-return-view-filter" "border" "border-base-300"
                       "px-1.5" "py-0.5" "font-mono" "text-[0.6rem]" "font-semibold"
                       "uppercase" "tracking-[0.08em]"
                       "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
               :data-role (str container-role "-export")
               :disabled empty-universe?
               :title "Download every asset's return view as JSON — fill `return-percent` offline (e.g. with an AI agent running your own model) and import the file here"
               :on (when-not empty-universe?
                     {:click [[:actions/export-portfolio-optimizer-return-views]]})}
      "Export JSON"]
     [:button {:type "button"
               :class ["optimizer-return-view-filter" "border" "border-base-300"
                       "px-1.5" "py-0.5" "font-mono" "text-[0.6rem]" "font-semibold"
                       "uppercase" "tracking-[0.08em]"
                       "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
               :data-role (str container-role "-import")
               :title "Import a filled return-views JSON file — each filled return becomes your view"
               :on {:click [[:actions/import-portfolio-optimizer-return-views]]}}
      "Import JSON"]]))

(defn- io-note
  [container-role {:keys [kind message]}]
  (when (seq (str (or message "")))
    [:div {:class (cond-> ["mb-2" "flex" "items-start" "justify-between" "gap-2"
                           "border" "px-2" "py-1.5" "text-[0.6875rem]"
                           "leading-[1.4]"]
                    (= :error kind) (into ["border-error/50" "bg-error/10"
                                           "text-error"])
                    (not= :error kind) (into ["border-base-300" "bg-base-200/20"
                                              "text-trading-muted"]))
           :data-role (str container-role "-io-note")
           :data-kind (name (or kind :success))}
     [:span message]
     [:button {:type "button"
               :class ["border-0" "bg-transparent" "px-1" "py-0" "font-mono"
                       "text-[0.6875rem]" "text-trading-muted"
                       "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
               :data-role (str container-role "-io-note-dismiss")
               :aria-label "Dismiss file import/export message"
               :on {:click [[:actions/dismiss-portfolio-optimizer-return-views-io-note]]}}
      "×"]]))

(defn- views-off-note
  [container-role]
  [:div {:class ["border" "border-base-300" "bg-base-200/20" "p-3"
                 "text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]
         :data-role (str container-role "-views-off")}
   [:p "Return views are off for this return model — it uses the historical estimate directly."]
   [:button {:type "button"
             :class ["mt-2" "border" "border-warning/50" "bg-warning/10" "px-2"
                     "py-1" "text-[0.75rem]" "font-semibold" "text-warning"]
             :data-role (str container-role "-views-on")
             :on {:click [[:actions/set-portfolio-optimizer-return-model-kind
                           :black-litterman]]}}
    "Use views where I have them"]])

(defn- return-free-objective-label
  [objective-kind]
  (case objective-kind
    :equal-risk "Equal Risk"
    :inverse-volatility "Risk-weighted sizing"
    "Minimum risk"))

(defn- min-variance-note
  [container-role objective-kind]
  [:p {:class ["border" "border-base-300" "bg-base-200/20" "p-2"
               "text-[0.6875rem]" "leading-[1.4]" "text-trading-muted"]
       :data-role (str container-role "-objective-note")}
   (str (return-free-objective-label objective-kind) " ignores expected returns — views change the displayed return, not this recommendation. Switch to Maximum Sharpe to optimize with them.")])

(defn- conservative-note
  [container-role objective-kind]
  [:p {:class ["border" "border-base-300" "bg-base-200/20" "p-3"
               "text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]
       :data-role (str container-role "-conservative-note")}
   (str "Return views are not used by " (return-free-objective-label objective-kind) ". Switch to the Maximum Sharpe goal to optimize with your expected-return views.")])

(defn return-views-panel
  "The provenance-aware Return views panel. Options:
  :container-role/:title/:description — shell identity and copy;
  :include-apply?/:apply-role — the results-page re-run affordance;
  :collapsible? — render as a <details> whose summary carries the title + live
  counts (the results rail uses this closed: view editing is an input task,
  by-exception on a review page);
  :open? — initial expansion for the collapsible form. The setup rail passes
  true under Maximum Sharpe: the forecast inputs DRIVE that objective, so they
  must be visible by default, not discoverable behind a disclosure. The
  attribute only sets initial state — a user toggle survives re-renders
  because the attribute value never changes between renders;
  :now-ms — clock for age labels (tests pass a fixed value)."
  [{:keys [draft state readiness now-ms container-role title description
           extra-class include-apply? apply-role collapsible? open?]
    :or {container-role "portfolio-optimizer-objective-menu-use-my-views-editor"
         title "Return views"
         apply-role "portfolio-optimizer-objective-menu-apply"}}]
  (let [universe (vec (:universe draft))
        black-litterman? (= :black-litterman (get-in draft [:return-model :kind]))
        objective-kind (get-in draft [:objective :kind])
        return-free? (contains? setup-summary/return-free-objective-kinds
                                objective-kind)
        rows (return-views/rows
              {:universe universe
               :views (get-in draft [:return-model :views])
               :library-entries (get-in state optimizer-contracts/view-library-path)
               :implied-returns-by-instrument
               (return-inputs/readiness-inputs-by-instrument readiness)
               :now-ms now-ms})
        summary (return-views/summary rows)
        active-filter (return-views/normalize-filter
                       (get-in state optimizer-contracts/ui-return-views-filter-path))
        visible-rows (filterv #(return-views/matches-filter? % active-filter) rows)
        description* (when black-litterman?
                       (or description
                           "Your views tilt the forecast where you have them; implied rows fall back to the baseline estimate. Edits save automatically."))
        title-el [:p {:class ["font-mono" "text-[0.58rem]" "font-semibold" "uppercase"
                              "tracking-[0.18em]" "text-warning"]}
                  title]
        note-els [(when description*
                    [:p {:class ["mt-1" "text-[0.6875rem]" "leading-[1.35]" "text-trading-muted"]}
                     description*])
                  (when black-litterman?
                    [:p {:class ["mt-1" "text-[0.6875rem]" "leading-[1.35]" "text-trading-muted/80"]
                         :data-role (str container-role "-confidence-help")}
                     "Confidence sets how strongly the optimizer trusts each return — a low setting tilts gently, a high setting pulls hard."])]
        body (cond
               (and (not black-litterman?) return-free?)
               (conservative-note container-role objective-kind)

               (not black-litterman?)
               (views-off-note container-role)

               :else
               [:div {:class ["flex" "min-h-0" "flex-col"]}
                (when return-free?
                  [:div {:class ["mb-2"]} (min-variance-note container-role objective-kind)])
                [:p {:class ["font-mono" "text-[0.6875rem]" "font-semibold" "text-trading-text"]
                     :data-role (str container-role "-summary")}
                 (return-views/summary-line summary)]
                (into
                 [:div {:class ["mt-1.5" "flex" "flex-wrap" "gap-1"]
                        :data-role (str container-role "-filters")}]
                 (map #(filter-chip container-role active-filter summary %)
                      return-views/filters))
                (io-toolbar container-role summary)
                [:div {:class ["mt-1.5" "mb-2"]}
                 (io-note container-role
                          (get-in state optimizer-contracts/ui-return-views-io-note-path))]
                (into
                 [:div {:class ["optimizer-objective-view-rows" "min-h-0" "space-y-1.5"
                                "overflow-x-hidden" "overflow-y-auto"]
                        :data-role (str container-role "-rows")}]
                 (if (seq visible-rows)
                   (map #(provenance-row state universe %) visible-rows)
                   [[:p {:class ["p-2" "text-[0.6875rem]" "text-trading-muted"]
                         :data-role (str container-role "-filter-empty")}
                     (case active-filter
                       :yours "No views of yours yet — edit any row to add one."
                       :stale "No stale views."
                       :implied "Every asset has your view — nothing is implied."
                       "Add assets to the universe to edit return views.")]]))
                (when include-apply?
                  [:button {:type "button"
                            :class ["optimizer-primary-action" "mt-2" "w-full" "border"
                                    "border-base-300" "px-3" "py-2" "text-left"
                                    "text-[0.6875rem]" "font-semibold"
                                    "focus:outline-none" "focus:ring-0" "focus:ring-offset-0"]
                            :data-role apply-role
                            :on {:click [[:actions/apply-portfolio-optimizer-objective-menu-selection-and-run]]}}
                   "Apply & re-run"])])]
    (if collapsible?
      [:details (cond-> {:class (cond-> ["optimizer-objective-views-section"
                                         "optimizer-return-views-disclosure"
                                         "border-t" "border-base-300" "px-3" "py-3"]
                                  extra-class (conj extra-class))
                         :data-role container-role}
                  open? (assoc :open true))
       [:summary {:class ["optimizer-return-views-disclosure-summary" "cursor-pointer"]
                  :data-role (str container-role "-toggle")}
        title-el
        [:span {:class ["optimizer-return-views-disclosure-count" "font-mono"
                        "text-[0.6875rem]" "text-trading-muted"]}
         (return-views/summary-line summary)]]
       (into [:div {:class ["mt-2" "mb-2"]}] note-els)
       body]
      [:section {:class (cond-> ["optimizer-objective-views-section"
                                 "flex" "min-h-0" "shrink-0" "flex-col"
                                 "border-t" "border-base-300" "px-3" "py-3"]
                          extra-class (conj extra-class))
                 :data-role container-role}
       (into [:div {:class ["mb-2"]} title-el] note-els)
       body])))

(defn panel-now-ms
  "Render-time clock for age labels. Isolated so view tests can stay deterministic
  by passing :now-ms explicitly instead."
  []
  (platform/now-ms))
