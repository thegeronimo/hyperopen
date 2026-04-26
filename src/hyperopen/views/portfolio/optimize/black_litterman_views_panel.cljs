(ns hyperopen.views.portfolio.optimize.black-litterman-views-panel)

(def ^:private field-label-class
  ["block" "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"])

(def ^:private input-class
  ["mt-2" "w-full" "rounded-md" "border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
   "text-sm" "font-semibold" "tabular-nums" "outline-none" "focus:border-primary/70"])

(defn- format-pct
  [value]
  (if (number? value)
    (str (.toLocaleString (* 100 value)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         "%")
    "N/A"))

(defn- keyword-label
  [value]
  (cond
    (keyword? value) (name value)
    (some? value) (str value)
    :else "N/A"))

(defn- instrument-options
  [universe]
  (map (fn [instrument]
         [:option {:value (:instrument-id instrument)}
          (or (:symbol instrument)
              (:instrument-id instrument))])
       universe))

(defn- select-field
  [label value data-role action universe]
  [:label {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]}
   [:span {:class field-label-class} label]
   (into [:select {:class input-class
                   :data-role data-role
                   :value (or value "")
                   :on {:change [action]}}]
         (instrument-options universe))])

(defn- number-field
  [label value data-role action]
  [:label {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]}
   [:span {:class field-label-class} label]
   [:input {:type "text"
            :inputmode "decimal"
            :class input-class
            :data-role data-role
            :value (str (or value ""))
            :on {:input [action]}}]])

(defn- view-kind-button
  [view idx kind label selected?]
  [:button {:type "button"
            :class (cond-> ["rounded-md" "border" "px-2" "py-1.5" "text-left" "text-xs" "font-semibold"]
                     selected? (conj "border-primary/60" "bg-primary/10" "text-primary")
                     (not selected?) (conj "border-base-300" "bg-base-200/40" "text-trading-muted"))
            :aria-pressed (str selected?)
            :data-role (str "portfolio-optimizer-black-litterman-view-" idx "-" (name kind) "-kind")
            :on {:click [[:actions/set-portfolio-optimizer-black-litterman-view-parameter
                          (:id view)
                          :kind
                          kind]]}}
   label])

(defn- view-row
  [universe idx view]
  (let [kind (or (:kind view) :absolute)
        relative? (= :relative kind)]
    [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-200/40" "p-3"]
           :data-role (str "portfolio-optimizer-black-litterman-view-row-" idx)}
     [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2"]}
      [:div
       [:p {:class ["text-sm" "font-semibold"]}
        (if relative? "Relative View" "Absolute View")]
       [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
        (if relative?
          "Express expected spread between two instruments."
          "Express expected return for one instrument.")]]
      [:button {:type "button"
                :class ["rounded-md" "border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
                        "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]"
                        "text-trading-muted" "hover:text-trading-text"]
                :data-role (str "portfolio-optimizer-black-litterman-view-" idx "-remove")
                :on {:click [[:actions/remove-portfolio-optimizer-black-litterman-view
                              (:id view)]]}}
       "Remove"]]
     [:div {:class ["mt-3" "grid" "grid-cols-2" "gap-2"]}
      (view-kind-button view idx :absolute "Absolute" (= :absolute kind))
      (view-kind-button view idx :relative "Relative" relative?)]
     (if relative?
       [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2" "md:grid-cols-2"]}
        (select-field "Long Leg"
                      (:long-instrument-id view)
                      (str "portfolio-optimizer-black-litterman-view-" idx "-long-instrument-input")
                      [:actions/set-portfolio-optimizer-black-litterman-view-parameter
                       (:id view)
                       :long-instrument-id
                       [:event.target/value]]
                      universe)
        (select-field "Short Leg"
                      (:short-instrument-id view)
                      (str "portfolio-optimizer-black-litterman-view-" idx "-short-instrument-input")
                      [:actions/set-portfolio-optimizer-black-litterman-view-parameter
                       (:id view)
                       :short-instrument-id
                       [:event.target/value]]
                      universe)]
       [:div {:class ["mt-3"]}
        (select-field "Instrument"
                      (:instrument-id view)
                      (str "portfolio-optimizer-black-litterman-view-" idx "-instrument-input")
                      [:actions/set-portfolio-optimizer-black-litterman-view-parameter
                       (:id view)
                       :instrument-id
                       [:event.target/value]]
                      universe)])
     [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2" "md:grid-cols-2"]}
      (number-field (if relative? "Spread Return" "Expected Return")
                    (:return view)
                    (str "portfolio-optimizer-black-litterman-view-" idx "-return-input")
                    [:actions/set-portfolio-optimizer-black-litterman-view-parameter
                     (:id view)
                     :return
                     [:event.target/value]])
      (number-field "Confidence"
                    (:confidence view)
                    (str "portfolio-optimizer-black-litterman-view-" idx "-confidence-input")
                    [:actions/set-portfolio-optimizer-black-litterman-view-parameter
                     (:id view)
                     :confidence
                     [:event.target/value]])]]))

(defn- prior-row
  [prior instrument]
  (let [instrument-id (:instrument-id instrument)
        weight (get-in prior [:weights-by-instrument instrument-id])]
    [:div {:class ["grid" "grid-cols-[minmax(8rem,1fr)_7rem]" "gap-3" "rounded-lg"
                   "border" "border-base-300" "bg-base-200/40" "p-2" "text-xs"]}
     [:span {:class ["font-semibold"]} instrument-id]
     [:span {:class ["tabular-nums" "text-trading-muted"]} (format-pct weight)]]))

(defn- prior-panel
  [universe prior]
  [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-200/30" "p-3"]
         :data-role "portfolio-optimizer-black-litterman-prior-panel"}
   [:div {:class ["grid" "grid-cols-1" "gap-2" "md:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]"]}
    [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-100" "p-3"]}
     [:p {:class field-label-class} "Prior Source"]
     [:p {:class ["mt-2" "text-sm" "font-semibold"]} (keyword-label (:source prior))]
     [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
      "Market-cap weights are preferred; current portfolio and equal weight are explicit fallbacks."]]
    [:div {:class ["space-y-2"]}
     (cond
       (not (map? prior))
       [:p {:class ["rounded-md" "border" "border-base-300" "bg-base-200/40" "p-2"
                    "text-xs" "text-trading-muted"]}
        "Prior metadata will appear after a universe is selected."]

       (seq (:warnings prior))
       (map (fn [warning]
              [:p {:class ["rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2"
                           "text-xs" "text-warning"]
                   :data-role "portfolio-optimizer-black-litterman-prior-warning"}
               [:span {:class ["font-semibold"]} (keyword-label (:code warning))]
               [:span {:class ["ml-2"]}
                (str "Fallback: " (keyword-label (:fallback warning)))]])
            (:warnings prior))

       :else
       [:p {:class ["rounded-md" "border" "border-success/30" "bg-success/10" "p-2"
                    "text-xs" "text-success"]}
        "Prior coverage is complete for the selected universe."])]]
   [:div {:class ["mt-3" "space-y-2"]}
    [:p {:class field-label-class} "Implied Prior Weights"]
    (if (seq universe)
      (map (partial prior-row prior) universe)
      [:p {:class ["text-xs" "text-trading-muted"]} "Add instruments to see prior weights."])]])

(defn black-litterman-views-panel
  [draft prior]
  (when (= :black-litterman (get-in draft [:return-model :kind]))
    (let [universe (vec (:universe draft))
          views (vec (get-in draft [:return-model :views]))]
      [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
                 :data-role "portfolio-optimizer-black-litterman-panel"}
       [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
        [:div
         [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
          "Black-Litterman Views"]
         [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
          "Author absolute returns and relative spreads as a return-model input. This does not change the objective layer."]]
        [:div {:class ["flex" "flex-wrap" "gap-2"]}
         [:button {:type "button"
                   :class ["rounded-lg" "border" "border-primary/50" "bg-primary/10" "px-3" "py-2"
                           "text-xs" "font-semibold" "uppercase" "tracking-[0.16em]" "text-primary"]
                   :data-role "portfolio-optimizer-black-litterman-add-absolute-view"
                   :on {:click [[:actions/add-portfolio-optimizer-black-litterman-view
                                 :absolute]]}}
          "Add Absolute"]
         [:button {:type "button"
                   :class ["rounded-lg" "border" "border-primary/50" "bg-primary/10" "px-3" "py-2"
                           "text-xs" "font-semibold" "uppercase" "tracking-[0.16em]" "text-primary"
                           "disabled:cursor-not-allowed" "disabled:border-base-300"
                           "disabled:bg-base-200/40" "disabled:text-trading-muted"]
                   :disabled (> 2 (count universe))
                   :data-role "portfolio-optimizer-black-litterman-add-relative-view"
                   :on {:click [[:actions/add-portfolio-optimizer-black-litterman-view
                                 :relative]]}}
          "Add Relative"]]]
       [:div {:class ["mt-4"]}
        (prior-panel universe prior)]
       [:div {:class ["mt-4" "space-y-3"]}
        (if (seq views)
          (map-indexed (partial view-row universe) views)
          [:p {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"
                       "text-sm" "text-trading-muted"]}
           "No Black-Litterman views yet. Add an absolute view or a relative spread to tilt the prior."])]])))
