(ns hyperopen.views.trade.vol-range.primitives
  "Small presentational building blocks for the Vol Range ticket and review.
  They render already-formatted strings from the projection models and emit
  command-derived runtime actions; nothing here parses or computes a number."
  (:require [hyperopen.views.trade.order-form-intent-adapter :as intent-adapter]
            [hyperopen.views.trade.vol-range.commands :as commands]))

(defn actions
  "Runtime action vectors for a view command."
  [command]
  (intent-adapter/command->actions command))

(def ^:private input-classes
  ["w-full" "h-9" "px-2" "bg-base-200" "border" "border-base-300" "rounded-lg" "text-sm"
   "text-right" "text-ho-text" "num" "placeholder:text-ho-text-muted"
   "focus:outline-none" "focus:ring-1" "focus:ring-ho-text-muted/40"])

(defn section
  "A titled section; `attrs` may carry :data-role and extra :class."
  [{:keys [title data-role class]} & children]
  [:section {:class (into ["flex" "flex-col" "gap-2" "border-t" "border-ho-border" "pt-2.5"] class)
             :data-role data-role}
   (when title
     [:h3 {:class ["text-xs" "font-semibold" "uppercase" "tracking-wide" "text-ho-text-secondary"]}
      title])
   (into [:div {:class ["flex" "flex-col" "gap-2"]}] children)])

(defn error-id
  "DOM id of the error card at index `idx`, referenced by aria-describedby."
  [idx]
  (str "vol-range-error-" idx))

(defn field-errors
  "{draft-path error-dom-id} for the ticket's error rows; the first error for a
  path wins, so an input points at one message."
  [errors]
  (reduce (fn [acc [idx {:keys [path]}]]
            (if (and (vector? path) (not (contains? acc path))) (assoc acc path (error-id idx)) acc))
          {}
          (map-indexed vector errors)))

(defn text-field
  "Labelled plain text input writing the draft at `path`. Enter does nothing:
  the input is not inside a form and has no key handler. When `field-errors`
  has `path`, the input is marked invalid and described by that error."
  [{:keys [id label value placeholder path suffix disabled? data-role field-errors]}]
  (let [error-dom-id (get field-errors path)]
    [:div {:class ["flex" "flex-col" "gap-1" "min-w-0"]}
     [:label {:for id :class ["text-xs" "text-ho-text-secondary"]} label]
     [:div {:class ["relative"]}
      [:input (cond-> {:id id
                       :type "text"
                       :inputmode "decimal"
                       :autocomplete "off"
                       :class (cond-> input-classes
                                suffix (conj "pr-10")
                                error-dom-id (conj "border-ho-warn"))
                       :value (or value "")
                       :placeholder (or placeholder "")
                       :disabled (boolean disabled?)
                       :data-role data-role
                       :on {:input (actions (commands/update-draft-from-input path))}}
                error-dom-id (assoc :aria-invalid "true" :aria-describedby error-dom-id))]
      (when suffix
        [:span {:class ["pointer-events-none" "absolute" "right-2" "top-1/2" "-translate-y-1/2"
                        "text-xs" "text-ho-text-muted"]}
         suffix])]]))

(defn switch
  "Labelled boolean draft switch (checkbox with role switch)."
  [{:keys [id label checked? path data-role disabled?]}]
  [:div {:class ["inline-flex" "items-center" "gap-2" "text-sm" "text-ho-text"]}
   [:input {:id id
            :type "checkbox"
            :role "switch"
            :class ["h-4" "w-4" "rounded-[3px]" "border" "border-base-300" "bg-transparent"
                    "trade-toggle-checkbox"]
            :checked (boolean checked?)
            :disabled (boolean disabled?)
            :data-role data-role
            :on {:change (actions (commands/update-draft-from-checkbox path))}}]
   [:label {:for id :class ["cursor-pointer" "select-none"]} label]])

(defn action-button
  "Plain button (never a submit) dispatching `command`."
  [{:keys [label command data-role disabled? tone class aria-label]}]
  [:button {:type "button"
            :class (-> ["rounded-md" "border" "px-2" "py-1" "text-xs" "font-medium"
                        "transition-colors" "disabled:opacity-50" "disabled:cursor-not-allowed"]
                       (into (case tone
                               :warn ["border-ho-warn" "text-ho-warn"]
                               :buy ["border-ho-buy" "text-ho-buy"]
                               :sell ["border-ho-sell" "text-ho-sell"]
                               ["border-ho-border" "text-ho-text-secondary" "hover:text-ho-text"]))
                       (into class))
            :disabled (boolean disabled?)
            :aria-label aria-label
            :data-role data-role
            :on {:click (actions command)}}
   label])

(defn tile
  "Label over value tile with an optional caption."
  [{:keys [label value caption data-role]}]
  [:div {:class ["flex" "flex-col" "gap-0.5" "rounded-md" "border" "border-ho-border" "bg-base-200/40"
                 "px-2" "py-1.5" "min-w-0"]
         :data-role data-role}
   [:span {:class ["text-xs" "text-ho-text-secondary"]} label]
   [:span {:class ["text-sm" "font-semibold" "text-ho-text" "num" "truncate"]} (or value "—")]
   (when caption
     [:span {:class ["text-xs" "text-ho-text-muted"]} caption])])

(defn stat-row
  "Label left, value right; skipped when the value is nil."
  [label value & {:keys [data-role value-class]}]
  (when (some? value)
    [:div {:class ["flex" "items-baseline" "justify-between" "gap-2" "text-xs"]
           :data-role data-role}
     [:span {:class ["text-ho-text-secondary"]} label]
     [:span {:class (into ["num" "text-ho-text" "text-right"] value-class)} value]]))

(defn rung-rows
  "Per-rung preview rows: price, size, notional, sigma distance."
  [rows base-symbol tone]
  (when (seq rows)
    [:ul {:class ["flex" "flex-col" "gap-0.5" "text-xs" "num"] :data-role "vol-range-rung-rows"}
     (for [{:keys [leg-id price size notional sigma]} rows]
       ^{:key leg-id}
       [:li {:class ["grid" "grid-cols-4" "gap-1"] :data-leg-id leg-id}
        [:span {:class [(if (= tone :buy) "text-ho-buy" "text-ho-sell")]} price]
        [:span {:class ["text-right" "text-ho-text"]} (str size (when base-symbol (str " " base-symbol)))]
        [:span {:class ["text-right" "text-ho-text-secondary"]} notional]
        [:span {:class ["text-right" "text-ho-text-muted"]} sigma]])]))
