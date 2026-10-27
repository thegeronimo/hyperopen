(ns hyperopen.views.trade.vol-range.model-section
  "Model section of the Vol Range ticket (spec section 4): daily volatility,
  anchor and inventory tilt. Consumes `:model` of the ticket projection."
  (:require [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.trade.vol-range.primitives :as p]))

(defn- mode-toggle
  [{:keys [mode]}]
  [:div {:role "radiogroup" :aria-label "Daily volatility source" :data-role "vol-range-volatility-mode"
         :class ["inline-flex" "rounded-md" "border" "border-ho-border" "overflow-hidden"]}
   (for [[id label] [[:auto "Auto"] [:manual "Manual"]]]
     ^{:key id}
     [:button {:type "button"
               :role "radio"
               :aria-checked (= mode id)
               :class (into ["px-2" "py-0.5" "text-xs"]
                            (if (= mode id) ["bg-base-200" "text-ho-text"] ["text-ho-text-secondary"]))
               :on {:click (p/actions (commands/update-draft [:volatility :mode] (name id)))}}
      label])])

(defn- volatility-block
  [{:keys [mode display source-label manual-input caption window-label unavailable-message] :as vol} fe]
  [:div {:class ["flex" "flex-col" "gap-1.5"] :data-role "vol-range-volatility"}
   [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
    [:div {:class ["text-sm" "text-ho-text"]}
     "Daily volatility "
     [:span {:class ["font-semibold" "num"] :data-role "vol-range-volatility-value"}
      (or display "—")]
     (when source-label
       [:span {:class ["ml-1" "text-xs" "text-ho-text-muted"]} (str "(" source-label ")")])]
    (mode-toggle vol)]
   (when (= mode :manual)
     (p/text-field {:id "vol-range-manual-volatility"
                    :label "Manual daily volatility"
                    :value manual-input
                    :suffix "%"
                    :path [:volatility :manual-daily-pct]
                    :field-errors fe}))
   (when caption
     [:div {:class ["text-xs" "text-ho-text-muted"]} caption
      (when window-label [:span " · " window-label])])
   (when unavailable-message
     [:div {:class ["flex" "flex-wrap" "items-center" "gap-2" "text-xs" "text-ho-warn"]
            :role "status" :data-role "vol-range-volatility-unavailable"}
      [:span unavailable-message]
      [:button {:type "button"
                :class ["underline" "text-ho-text-secondary" "hover:text-ho-text"]
                :data-role "vol-range-refresh-daily-candles"
                :on {:click (p/actions (commands/refresh-daily-candles))}}
       "Refresh daily candles"]])])

(defn- anchor-block
  [{:keys [mode options price caption custom-input excursion-text]} fe]
  [:div {:class ["flex" "flex-col" "gap-1.5"] :data-role "vol-range-anchor"}
   [:label {:for "vol-range-anchor-select" :class ["text-xs" "text-ho-text-secondary"]} "Anchor"]
   [:select {:id "vol-range-anchor-select"
             :class ["w-full" "h-9" "px-2" "bg-base-200" "border" "border-base-300" "rounded-lg"
                     "text-sm" "text-ho-text"]
             :on {:change (p/actions (commands/update-draft-from-input [:anchor :mode]))}}
    (for [{:keys [id label available? selected? price]} options]
      ^{:key id}
      [:option {:value (name id)
                :selected (boolean selected?)
                :disabled (not available?)}
       (str label (when (and price available?) (str " · " price)))])]
   (when (= mode :custom)
     (p/text-field {:id "vol-range-custom-anchor"
                    :label "Custom anchor price"
                    :value custom-input
                    :path [:anchor :custom-price]
                    :field-errors fe}))
   [:div {:class ["flex" "flex-col" "gap-0.5" "text-xs" "text-ho-text-muted"]}
    (when (or price caption)
      [:span {:data-role "vol-range-anchor-source"}
       (str (when price (str "Anchor " price)) (when (and price caption) " · ") caption)])
    (when excursion-text
      [:span {:class ["num"] :data-role "vol-range-anchor-excursion"} (str excursion-text)])]])

(defn- tilt-block
  [{:keys [enabled? strength-input caption]} advanced-open? fe]
  [:div {:class ["flex" "flex-col" "gap-1"] :data-role "vol-range-tilt"}
   (p/switch {:id "vol-range-tilt-enabled"
              :label "Inventory tilt"
              :checked? enabled?
              :path [:inventory :tilt-enabled?]})
   [:div {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-tilt-caption"} caption]
   [:button {:type "button"
             :class ["self-start" "text-xs" "text-ho-text-secondary" "underline"]
             :aria-expanded (boolean advanced-open?)
             :data-role "vol-range-advanced-toggle"
             :on {:click (p/actions (commands/set-ui-flag :advanced-open? (not (boolean advanced-open?))))}}
    "Advanced"]
   (when advanced-open?
     (p/text-field {:id "vol-range-tilt-strength"
                    :label "Tilt strength"
                    :value strength-input
                    :path [:inventory :tilt-strength]
                    :field-errors fe}))])

(defn model-section
  ([model ui] (model-section model ui {}))
  ([{:keys [volatility anchor tilt]} {:keys [advanced-open?]} fe]
   (p/section {:title "Model" :data-role "vol-range-model"}
              (volatility-block volatility fe)
              (anchor-block anchor fe)
              (tilt-block tilt advanced-open? fe))))
