(ns hyperopen.views.portfolio.optimize.black-litterman-views-panel
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.return-inputs :as return-inputs]
            [hyperopen.views.portfolio.optimize.instrument-display :as instrument-display]))

(def ^:private max-active-views 10)

(def ^:private confidence-options
  [[:low "LOW"]
   [:medium "MEDIUM"]
   [:high "HIGH"]])

(def ^:private horizon-options
  [[:1m "1M"]
   [:3m "3M"]
   [:6m "6M"]
   [:1y "1Y"]])

(def ^:private direction-options
  [[:outperform "Outperform"]
   [:underperform "Underperform"]])

(def ^:private eyebrow-class
  ["font-mono" "text-[0.625rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"])

(def ^:private input-class
  ["w-full" "border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
   "font-mono" "text-[0.71875rem]" "text-trading-text" "outline-none"
   "focus:border-warning/70"])

(defn- normalize-kind
  [value fallback]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else fallback))

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
      "Select"))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-percent-text
  [value]
  (let [text (some-> value
                     str
                     str/trim
                     (str/replace #"," "")
                     (str/replace #"%" "")
                     str/trim)]
    (when (seq text)
      (let [parsed (js/Number text)]
        (when (finite-number? parsed)
          (/ parsed 100))))))

(defn- pct-label
  ([value]
   (pct-label value false))
  ([value signed?]
   (if (finite-number? value)
     (let [pct (* value 100)
           abs-pct (js/Math.abs pct)
           fixed (.toFixed abs-pct 2)
           trimmed (str/replace fixed #"\.?0+$" "")
           sign (cond
                  (not signed?) ""
                  (pos? pct) "+"
                  (neg? pct) "-"
                  :else "")]
       (str sign trimmed "%"))
     "--")))

(defn- display-confidence
  [value]
  (name (normalize-kind value :medium)))

(defn- display-horizon
  [value]
  (-> (name (normalize-kind value :3m))
      str/upper-case))

(defn- view-primary-id
  [view]
  (or (:instrument-id view)
      (:long-instrument-id view)))

(defn- view-comparator-id
  [view]
  (or (:comparator-instrument-id view)
      (:short-instrument-id view)))

(defn- view-direction
  [view]
  (normalize-kind (:direction view) :outperform))

(defn- view-summary
  [universe view]
  (let [kind (:kind view)
        primary (instrument-label universe (view-primary-id view))
        comparator (instrument-label universe (view-comparator-id view))
        direction (view-direction view)
        return-label (pct-label (:return view) (= :absolute kind))]
    (case kind
      :relative
      (str primary " "
           (if (= :underperform direction) "<" ">")
           " " comparator " by " (pct-label (:return view)) " annualized")
      (str primary " expected return " return-label " annualized"))))

(defn- draft-defaults
  [universe kind]
  (let [ids (mapv :instrument-id universe)
        [first-id second-id] ids]
    (case kind
      :relative {:instrument-id first-id
                 :comparator-instrument-id (or second-id first-id)
                 :direction :outperform
                 :return-text ""
                 :return-text-touched? false
                 :confidence :medium
                 :horizon :3m
                 :notes ""}
      {:instrument-id first-id
       :return-text ""
       :return-text-touched? false
       :confidence :medium
       :horizon :3m
       :notes ""})))

(defn- selected-kind
  [editor-state]
  (let [kind (normalize-kind (:selected-kind editor-state) :absolute)]
    (if (contains? #{:absolute :relative} kind)
      kind
      :absolute)))

(defn- drop-nil-values
  [m]
  (into {}
        (remove (fn [[_ value]]
                  (nil? value)))
        m))

(defn- with-automatic-absolute-return-text
  [draft kind return-inputs editing?]
  (let [instrument-id (:instrument-id draft)]
    (if (and (= :absolute kind)
             (not editing?)
             instrument-id
             (not (:return-text-touched? draft))
             (nil? (parse-percent-text (:return-text draft))))
      (if-let [return-input (get return-inputs instrument-id)]
        (assoc draft :return-text (return-inputs/decimal->percent-text return-input))
        draft)
      draft)))

(defn- selected-draft
  [universe editor-state kind return-inputs editing?]
  (-> (merge (draft-defaults universe kind)
             (drop-nil-values (get-in editor-state [:drafts kind])))
      (with-automatic-absolute-return-text kind return-inputs editing?)))

(defn- instrument-grid-class
  [kind]
  (cond-> ["grid" "grid-cols-1" "gap-3"]
    (= :relative kind) (into ["sm:grid-cols-2" "xl:grid-cols-1" "2xl:grid-cols-2"])))

(defn- draft-valid?
  [universe kind draft active-count editing?]
  (let [ids (set (keep :instrument-id universe))
        instrument-id (:instrument-id draft)
        comparator-id (:comparator-instrument-id draft)
        return-value (parse-percent-text (:return-text draft))]
    (and (or editing? (< active-count max-active-views))
         (contains? ids instrument-id)
         (some? return-value)
         (if (= :relative kind)
           (and (contains? ids comparator-id)
                (not= instrument-id comparator-id)
                (not (neg? return-value)))
           true))))

(defn- preview-text
  [universe kind draft]
  (let [value (parse-percent-text (:return-text draft))
        asset (instrument-label universe (:instrument-id draft))]
    (if (and (:instrument-id draft) value)
      (case kind
        :relative
        (let [comparator-id (:comparator-instrument-id draft)]
          (if (and comparator-id (not= comparator-id (:instrument-id draft)))
            (str asset " "
                 (if (= :underperform (:direction draft)) "<" ">")
                 " " (instrument-label universe comparator-id)
                 " by " (pct-label value) " annualized")
            "Select a comparator asset to preview this view."))
        (str asset " expected return " (pct-label value true) " annualized"))
      "Select an asset and enter a value to preview this view.")))

(defn- segmented-button
  [label selected? role action]
  [:button {:type "button"
            :class (cond-> ["border" "border-base-300" "bg-base-200/20" "px-2" "py-1.5"
                            "text-center" "text-[0.65625rem]" "font-semibold" "uppercase"
                            "tracking-[0.08em]" "text-trading-muted" "hover:text-warning"]
                     selected? (conj "border-warning/70" "bg-warning/10" "text-warning"))
            :aria-pressed (str selected?)
            :data-role role
            :on {:click [action]}}
   label])

(defn- option-group
  [{:keys [label options selected field role-prefix]}]
  [:div
   [:p {:class eyebrow-class} label]
   (into [:div {:class ["mt-2" "grid" "grid-cols-2" "gap-1"]
                :role "listbox"
                :aria-label label
                :data-role (str role-prefix "-options")}]
         (map (fn [[value option-label]]
                [:button {:type "button"
                          :role "option"
                          :aria-selected (str (= value selected))
                          :class (cond-> ["border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
                                          "text-left" "text-[0.6875rem]" "font-medium"
                                          "text-trading-muted" "hover:text-warning"]
                                   (= value selected)
                                   (conj "border-warning/70" "bg-warning/10" "text-warning"))
                          :data-role (str role-prefix "-option-" (name value))
                          :on {:click [[:actions/set-portfolio-optimizer-black-litterman-editor-field
                                        field
                                        value]]}}
                 option-label])
              options))])

(defn- instrument-option-group
  [{:keys [label universe selected field role-prefix exclude-id]}]
  [:div
   [:p {:class eyebrow-class} label]
   (into [:div {:class ["mt-2" "max-h-32" "overflow-y-auto" "border" "border-base-300"]
                :role "listbox"
                :aria-label label
                :data-role (str role-prefix "-options")}]
         (map (fn [instrument]
                (let [instrument-id (:instrument-id instrument)
                      selected? (= instrument-id selected)
                      disabled? (= instrument-id exclude-id)]
                  [:button {:type "button"
                            :role "option"
                            :aria-selected (str selected?)
                            :disabled disabled?
                            :class (cond-> ["block" "w-full" "border-b" "border-base-300"
                                            "bg-base-100" "px-2" "py-1.5" "text-left"
                                            "text-[0.6875rem]" "font-medium"
                                            "last:border-b-0" "hover:text-warning"
                                            "disabled:cursor-not-allowed" "disabled:text-trading-muted/40"]
                                     selected? (conj "bg-warning/10" "text-warning"))
                            :data-role (str role-prefix "-option-" instrument-id)
                            :on {:click [[:actions/set-portfolio-optimizer-black-litterman-editor-field
                                          field
                                          instrument-id]]}}
                   [:span {:class ["font-mono"]} (instrument-label universe instrument-id)]
                   (when (:symbol instrument)
                     [:span {:class ["ml-2" "text-trading-muted"]} (:symbol instrument)])]))
              universe))])

(defn- text-input
  [{:keys [label value field role inputmode error]}]
  [:label {:class ["block"]}
   [:span {:class eyebrow-class} label]
   [:input {:type "text"
            :inputmode (or inputmode "text")
            :class (conj input-class "mt-2")
            :data-role role
            :aria-invalid (when error "true")
            :value (str (or value ""))
            :on {:input [[:actions/set-portfolio-optimizer-black-litterman-editor-field
                          field
                          [:event.target/value]]]}}]
   (when error
     [:p {:class ["mt-1" "text-[0.65625rem]" "text-warning"]} error])])

(defn- notes-input
  [draft]
  [:label {:class ["block"]}
   [:span {:class eyebrow-class} "NOTES (optional)"]
   [:textarea {:class ["mt-2" "min-h-[44px]" "w-full" "resize-y" "border" "border-base-300"
                       "bg-base-100" "px-2" "py-1.5" "text-[0.6875rem]" "outline-none"
                       "focus:border-warning/70"]
               :maxlength 280
               :data-role "portfolio-optimizer-black-litterman-editor-notes"
               :value (str (or (:notes draft) ""))
               :on {:input [[:actions/set-portfolio-optimizer-black-litterman-editor-field
                             :notes
                             [:event.target/value]]]}}]])

(defn- active-view-card
  [universe view]
  (let [summary (view-summary universe view)
        confidence (display-confidence (or (:confidence-level view)
                                           (cond
                                             (<= (or (:confidence view) 0.5) 0.25) :low
                                             (<= (or (:confidence view) 0.5) 0.5) :medium
                                             :else :high)))
        horizon (display-horizon (:horizon view))]
    [:div {:class ["border" "border-base-300" "bg-base-200/50" "p-2.5" "text-left"]
           :role "button"
           :tabindex 0
           :data-role (str "portfolio-optimizer-black-litterman-active-view-" (:id view))
           :on {:click [[:actions/edit-portfolio-optimizer-black-litterman-view (:id view)]]}}
     [:div {:class ["flex" "items-start" "justify-between" "gap-2"]}
      [:div {:class ["min-w-0"]}
       [:p {:class ["truncate" "text-[0.75rem]" "font-semibold" "text-trading-text"]}
        summary]
       [:p {:class ["mt-1" "font-mono" "text-[0.625rem]" "text-trading-muted"]}
        (str "Confidence: " confidence " · Horizon: " horizon)]]
      [:button {:type "button"
                :class ["shrink-0" "border" "border-transparent" "bg-transparent" "px-1.5"
                        "text-[0.875rem]" "text-trading-muted" "hover:border-base-300"
                        "hover:text-warning"]
                :aria-label (str "Remove " summary " view")
                :data-role (str "portfolio-optimizer-black-litterman-active-view-" (:id view) "-remove")
                :on {:click [[:actions/remove-portfolio-optimizer-black-litterman-view
                              (:id view)]]}}
       "x"]]]))

(defn black-litterman-views-panel
  ([draft readiness]
   (black-litterman-views-panel draft readiness {}))
  ([draft readiness editor-state]
   (when (= :black-litterman (get-in draft [:return-model :kind]))
     (let [universe (vec (:universe draft))
           views (vec (get-in draft [:return-model :views]))
           kind (selected-kind editor-state)
           errors (or (:errors editor-state) {})
           editing-view-id (:editing-view-id editor-state)
           editing? (boolean editing-view-id)
           return-inputs (return-inputs/readiness-inputs-by-instrument readiness)
           draft* (selected-draft universe editor-state kind return-inputs editing?)
           valid? (draft-valid? universe kind draft* (count views) editing?)
           clear-open? (true? (:clear-confirmation-open? editor-state))]
       [:section {:class ["portfolio-optimizer-bl-panel" "space-y-4"]
                  :data-role "portfolio-optimizer-black-litterman-panel"}
        [:div
         [:p {:class eyebrow-class} "EDIT VIEWS"]
         [:span {:class ["sr-only"]} "Black-Litterman Views"]
         [:h3 {:class ["mt-1" "text-[0.8125rem]" "font-semibold" "text-trading-text"]}
          "Tell the model what you believe"]]

        [:div {:class ["grid" "grid-cols-2" "border" "border-base-300"]}
         (segmented-button "ABSOLUTE" (= :absolute kind)
                           "portfolio-optimizer-black-litterman-editor-type-absolute"
                           [:actions/set-portfolio-optimizer-black-litterman-editor-type
                            :absolute])
         (segmented-button "RELATIVE" (= :relative kind)
                           "portfolio-optimizer-black-litterman-editor-type-relative"
                           [:actions/set-portfolio-optimizer-black-litterman-editor-type
                            :relative])]

        [:div {:class (instrument-grid-class kind)
               :data-role "portfolio-optimizer-black-litterman-editor-instrument-grid"}
         (instrument-option-group {:label "ASSET"
                                   :universe universe
                                   :selected (:instrument-id draft*)
                                   :field :instrument-id
                                   :role-prefix "portfolio-optimizer-black-litterman-editor-asset"})
         (when (= :relative kind)
           (instrument-option-group {:label "COMPARATOR"
                                     :universe universe
                                     :selected (:comparator-instrument-id draft*)
                                     :field :comparator-instrument-id
                                     :exclude-id (:instrument-id draft*)
                                     :role-prefix "portfolio-optimizer-black-litterman-editor-comparator"}))]

        (when (= :relative kind)
          (option-group {:label "DIRECTION"
                         :options direction-options
                         :selected (:direction draft*)
                         :field :direction
                         :role-prefix "portfolio-optimizer-black-litterman-editor-direction"}))

        (text-input {:label (if (= :relative kind)
                              "EXPECTED RETURN / SPREAD (annualized)"
                              "EXPECTED RETURN (annualized)")
                     :value (:return-text draft*)
                     :field :return-text
                     :inputmode "decimal"
                     :role "portfolio-optimizer-black-litterman-editor-return"
                     :error (:return-text errors)})

        [:div {:class ["grid" "grid-cols-1" "gap-3" "sm:grid-cols-2" "xl:grid-cols-1" "2xl:grid-cols-2"]}
         (option-group {:label "CONFIDENCE"
                        :options confidence-options
                        :selected (:confidence draft*)
                        :field :confidence
                        :role-prefix "portfolio-optimizer-black-litterman-editor-confidence"})
         (option-group {:label "HORIZON"
                        :options horizon-options
                        :selected (:horizon draft*)
                        :field :horizon
                        :role-prefix "portfolio-optimizer-black-litterman-editor-horizon"})]

        (notes-input draft*)

        [:div {:class ["border" "border-base-300" "bg-base-200/30" "p-3"]
               :data-role "portfolio-optimizer-black-litterman-preview"}
         [:p {:class eyebrow-class} "VIEW PREVIEW"]
         [:p {:class ["mt-2" "text-[0.75rem]" "font-semibold" "text-trading-text"]
              :data-role "portfolio-optimizer-black-litterman-preview-text"}
          (preview-text universe kind draft*)]
         [:p {:class ["mt-1" "font-mono" "text-[0.625rem]" "text-trading-muted"]}
          (str "Confidence: " (display-confidence (:confidence draft*))
               " · Horizon: " (display-horizon (:horizon draft*)))]]

        (when (:comparator-instrument-id errors)
          [:p {:class ["text-[0.65625rem]" "text-warning"]} (:comparator-instrument-id errors)])
        (when (:max errors)
          [:p {:class ["text-[0.65625rem]" "text-warning"]} (:max errors)])

        [:div {:class ["flex" "gap-2"]}
         [:button {:type "button"
                   :class ["flex-1" "border" "border-warning/70" "bg-warning/80" "px-3"
                           "py-2" "text-[0.71875rem]" "font-semibold" "text-base-100"
                           "disabled:cursor-not-allowed" "disabled:border-base-300"
                           "disabled:bg-base-200/30" "disabled:text-trading-muted"]
                   :disabled (not valid?)
                   :data-role "portfolio-optimizer-black-litterman-save-view"
                   :on {:click [[:actions/save-portfolio-optimizer-black-litterman-editor-view]]}}
          (if editing? "Save changes" "+ Add view")]
         (when editing?
           [:button {:type "button"
                     :class ["border" "border-base-300" "bg-base-100" "px-3" "py-2"
                             "text-[0.6875rem]" "font-semibold" "text-trading-muted"
                             "hover:text-trading-text"]
                     :data-role "portfolio-optimizer-black-litterman-cancel-edit"
                     :on {:click [[:actions/cancel-portfolio-optimizer-black-litterman-edit]]}}
            "Cancel"])]

        [:div {:class ["border-t" "border-base-300" "pt-3"]}
         [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
          [:p {:class eyebrow-class}
           (str "ACTIVE VIEWS (" (count views) "/" max-active-views ")")]
          [:button {:type "button"
                    :class ["bg-transparent" "text-[0.65625rem]" "font-semibold"
                            "text-trading-muted" "hover:text-warning"
                            "disabled:cursor-not-allowed" "disabled:text-trading-muted/40"]
                    :disabled (empty? views)
                    :data-role "portfolio-optimizer-black-litterman-clear-all"
                    :on {:click [[:actions/request-clear-portfolio-optimizer-black-litterman-views]]}}
           "Clear all"]]
         (if (seq views)
           (into [:div {:class ["mt-2" "space-y-2"]}]
                 (map (partial active-view-card universe) views))
           [:p {:class ["mt-2" "border" "border-base-300" "bg-base-200/30" "p-3"
                        "text-[0.6875rem]" "text-trading-muted"]}
            "No views yet. Add an absolute or relative belief to blend with the market reference."])
         (when clear-open?
           [:div {:class ["mt-3" "border" "border-warning/50" "bg-warning/10" "p-3"]
                  :data-role "portfolio-optimizer-black-litterman-clear-confirmation"}
            [:p {:class ["text-[0.71875rem]" "font-semibold" "text-trading-text"]}
             "Clear all views?"]
            [:p {:class ["mt-1" "text-[0.65625rem]" "text-trading-muted"]}
             (str "This removes " (count views) " views from the scenario.")]
            [:div {:class ["mt-3" "flex" "gap-2"]}
             [:button {:type "button"
                       :class ["border" "border-base-300" "bg-base-100" "px-3" "py-1.5"
                               "text-[0.6875rem]" "font-semibold" "text-trading-muted"]
                       :data-role "portfolio-optimizer-black-litterman-clear-cancel"
                       :on {:click [[:actions/cancel-clear-portfolio-optimizer-black-litterman-views]]}}
              "Cancel"]
             [:button {:type "button"
                       :class ["border" "border-warning/70" "bg-warning/80" "px-3" "py-1.5"
                               "text-[0.6875rem]" "font-semibold" "text-base-100"]
                       :data-role "portfolio-optimizer-black-litterman-clear-confirm"
                       :on {:click [[:actions/confirm-clear-portfolio-optimizer-black-litterman-views]]}}
              "Clear views"]]])]

        [:p {:class ["font-mono" "text-[0.625rem]" "leading-[1.45]" "text-trading-muted"]
             :data-role "portfolio-optimizer-black-litterman-helper"}
         "Views adjust expected returns only. Risk (covariance) is unchanged."]]))))
