(ns hyperopen.views.portfolio.optimize.setup-controls
  (:require [clojure.string :as str]))

(def eyebrow-class
  ["font-mono" "text-[0.6875rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"])

(def section-title-class
  ["text-[0.875rem]" "font-semibold" "text-trading-text"])

(def input-class
  ["w-full" "border" "border-base-300" "bg-base-100/80" "px-2" "py-1.5"
   "font-mono" "text-[0.8125rem]" "font-medium" "outline-none"
   "transition-shadow" "focus:border-warning/70"
   "focus:shadow-[0_0_0_1px_rgba(212,181,88,0.75)]"])

(defn labelize
  [value]
  (-> (name (or value :unknown))
      (str/replace "-" " ")
      (str/capitalize)))

(defn percent-label
  [value]
  (if (number? value)
    (str (.toFixed (* value 100) 0) "%")
    "--"))

(defn disclosure-panel
  [role & children]
  (into [:details {:class ["optimizer-setup-panel" "border" "border-base-300" "bg-base-100/90" "p-3"]
                   :data-role role
                   :id role}]
        children))

(defn disclosure-panel-open
  "Disclosure panel that starts expanded. The attribute only sets the initial
  state — a user toggle survives re-renders because the attribute value never
  changes between renders."
  [role & children]
  (into [:details {:class ["optimizer-setup-panel" "border" "border-base-300" "bg-base-100/90" "p-3"]
                   :data-role role
                   :id role
                   :open true}]
        children))

(defn section-heading
  [title trailing]
  [:div {:class ["flex" "items-center" "justify-between" "gap-3" "border-b" "border-base-300" "pb-2"]}
   [:p {:class section-title-class} title]
   (when trailing
     [:span {:class ["optimizer-section-trailing" "font-mono" "text-[0.75rem]" "text-trading-muted/70"]}
      trailing])])

(defn disclosure-heading
  [title trailing]
  [:summary {:class ["cursor-pointer" "select-none" "focus:outline-none" "focus:text-warning"]}
   (section-heading title trailing)])

(defn segmented-button
  ([label selected? role action]
   (segmented-button label nil nil :center selected? role action))
  ([label hidden-label selected? role action]
   (segmented-button label hidden-label nil :center selected? role action))
  ([label hidden-label help-copy tooltip-position selected? role action]
   (let [tooltip-id (str role "-tooltip")
         tooltip-position-classes (case tooltip-position
                                    :start ["left-0"]
                                    :end ["right-0"]
                                    ["left-1/2" "-translate-x-1/2"])]
     [:button {:type "button"
               :class (cond-> ["optimizer-segment-button"
                               "group" "relative" "border-r" "border-base-300"
                               "bg-transparent" "px-2" "py-1.5" "text-center"
                               "text-[0.75rem]" "font-medium"
                               "tracking-[0.04em]" "text-trading-muted"
                               "transition-colors" "last:border-r-0"
                               "hover:text-warning" "focus:outline-none"
                               "focus:text-warning"
                               "focus:shadow-[inset_0_0_0_1px_rgba(212,181,88,0.75)]"]
                        selected? (conj "bg-base-200/40" "text-trading-text"))
               :aria-pressed (str selected?)
               :aria-describedby (when help-copy tooltip-id)
               :data-role role
               :on {:click [action]}}
      label
      (when hidden-label
        [:span {:class ["sr-only"]} hidden-label])
      (when help-copy
        [:span {:class (into ["pointer-events-none" "absolute" "top-[calc(100%+6px)]"
                              "z-30" "w-72" "max-w-[calc(100vw-2rem)]"
                              "border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
                              "font-sans" "text-[0.75rem]" "font-normal"
                              "normal-case" "leading-[1.45]" "tracking-normal"
                              "text-trading-muted" "opacity-0"
                              "shadow-[0_12px_32px_rgba(0,0,0,0.45)]"
                              "transition-opacity" "duration-150"
                              "group-hover:opacity-100" "group-focus:opacity-100"]
                             tooltip-position-classes)
                :id tooltip-id
                :role "tooltip"
                :data-role tooltip-id}
         help-copy])])))

(defn decimal->percent-text
  "Render a stored fraction as a clean percent string for a percent-entry input
  (0.15 -> \"15\", 0.155 -> \"15.5\"), so the field echoes the interpreted value the
  user is meant to type rather than the raw fraction."
  [value]
  (if (number? value)
    (let [pct (/ (js/Math.round (* value 10000)) 100)]
      (if (== pct (js/Math.round pct))
        (str (js/Math.round pct))
        (str pct)))
    ""))

(defn- lucide-node->hiccup
  [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn lucide-icon
  "Render a lucide icon-node array (from a direct
  [\"lucide/dist/esm/icons/<name>.js\" :default node] require at the call
  site) as an inline 14px stroke icon."
  [node extra-class]
  (into [:svg {:viewBox "0 0 24 24"
               :width 14
               :height 14
               :fill "none"
               :stroke "currentColor"
               :stroke-width 1.75
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden "true"
               :class (into ["shrink-0"] (or extra-class []))}]
        (map lucide-node->hiccup (array-seq node))))

(defn percent-input
  "Percent-entry numeric input: the user types a percent (15 = 15%); it commits on
  blur/Enter (:change, never per keystroke — an eager parse rewrites a controlled input
  mid-typing), shows a literal % suffix, and echoes the interpreted value via `value-text`.
  `action` must dispatch a percent-aware handler that divides the typed number by 100."
  [label value-text role action highlighted? hint]
  [:label {:class (cond-> ["block" "border" "border-base-300" "bg-base-200/20" "p-2"]
                    highlighted? (conj "border-warning/70" "bg-warning/10"))}
   [:span {:class eyebrow-class} label]
   [:span {:class ["mt-2" "flex" "items-center" "gap-1"]}
    [:input {:type "text"
             :inputmode "decimal"
             :class input-class
             :data-role role
             :data-infeasible (when highlighted? "true")
             :aria-invalid (when highlighted? "true")
             :value (str value-text)
             :on {:change [action]}}]
    [:span {:class ["font-mono" "text-[0.8125rem]" "text-trading-muted"]} "%"]]
   (when hint
     [:span {:class ["mt-1" "block" "font-mono" "text-[0.625rem]" "text-trading-muted/70"]} hint])])
