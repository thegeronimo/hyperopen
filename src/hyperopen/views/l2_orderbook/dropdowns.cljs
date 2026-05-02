(ns hyperopen.views.l2-orderbook.dropdowns)

(defn precision-dropdown [selected-option price-options dropdown-visible?]
  (let [selected-label (or (:label selected-option) "0.000001")
        selected-mode (:mode selected-option)
        interactive? (> (count price-options) 1)]
    [:div.relative
     [:button.flex.items-center.space-x-2.rounded.px-2.py-1.transition-colors
      (cond-> {:type "button"
               :class (if interactive?
                        ["hover:bg-gray-800" "cursor-pointer"]
                        ["cursor-default"])
               :disabled (not interactive?)}
        interactive?
        (assoc :on {:click [[:actions/toggle-orderbook-price-aggregation-dropdown]]}))
      [:span.text-white.text-sm selected-label]
      [:svg.w-4.h-4.text-gray-400.transition-transform {:fill "none"
                                                        :stroke "currentColor"
                                                        :viewBox "0 0 24 24"
                                                        :class (when dropdown-visible? "rotate-180")}
       [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]
     (when interactive?
       [:div.absolute.top-full.left-0.mt-1.bg-base-100.border.border-base-300.rounded.spectate-lg.z-30.min-w-24.overflow-hidden
        {:class (if dropdown-visible?
                  ["opacity-100" "scale-y-100" "translate-y-0"]
                  ["opacity-0" "scale-y-95" "-translate-y-2" "pointer-events-none"])
         :style {:transition "all 80ms ease-in-out"}}
        (for [option price-options]
          ^{:key (str "precision-option-" (name (:mode option)))}
          [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-800
           {:class (if (= selected-mode (:mode option))
                     ["text-white" "bg-gray-800"]
                     ["text-gray-300"])
            :on {:click [[:actions/select-orderbook-price-aggregation (:mode option)]]}}
           (:label option)])])]))

(defn size-unit-dropdown [base-symbol quote-symbol size-unit dropdown-visible?]
  (let [selected-symbol (if (= size-unit :quote) quote-symbol base-symbol)]
    [:div.relative
     [:button.flex.items-center.space-x-2.hover:bg-gray-800.rounded.px-2.py-1.transition-colors
      {:type "button"
       :on {:click [[:actions/toggle-orderbook-size-unit-dropdown]]}}
      [:span.text-white.text-sm selected-symbol]
      [:svg.w-4.h-4.text-gray-400.transition-transform {:fill "none"
                                                        :stroke "currentColor"
                                                        :viewBox "0 0 24 24"
                                                        :class (when dropdown-visible? "rotate-180")}
       [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]
     [:div.absolute.top-full.right-0.mt-1.bg-base-100.border.border-base-300.rounded.spectate-lg.z-20.min-w-20.overflow-hidden
      {:class (if dropdown-visible?
                ["opacity-100" "scale-y-100" "translate-y-0"]
                ["opacity-0" "scale-y-95" "-translate-y-2" "pointer-events-none"])
       :style {:transition "all 80ms ease-in-out"}}
      [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-800
       {:class (if (= size-unit :quote) ["text-white" "bg-gray-800"] ["text-gray-300"])
        :on {:click [[:actions/select-orderbook-size-unit :quote]]}}
       quote-symbol]
      [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-800
       {:class (if (= size-unit :base) ["text-white" "bg-gray-800"] ["text-gray-300"])
        :on {:click [[:actions/select-orderbook-size-unit :base]]}}
	       base-symbol]]]))
