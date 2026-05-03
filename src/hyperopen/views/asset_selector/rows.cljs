(ns hyperopen.views.asset-selector.rows
  (:require [hyperopen.asset-selector.list-metrics :as list-metrics]
            [hyperopen.system :as app-system]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.asset-selector.controls :as controls]
            [hyperopen.views.asset-selector.icons :as icons]
            [nexus.registry :as nxr]))

(defn format-or-dash [value formatter]
  (or (formatter value) "—"))

(defn asset-selector-row-state
  [selected? highlighted?]
  (cond
    selected? "selected"
    highlighted? "highlighted"
    :else "idle"))

(defn select-asset-click-handler
  [{:keys [key] :as asset}]
  (fn [_event]
    (nxr/dispatch app-system/store
                  nil
                  [(if (string? key)
                     [:actions/select-asset-by-market-key key]
                     [:actions/select-asset asset])])))

(defn- desktop-grid-classes
  [outcome?]
  (cond-> ["grid" "gap-2" "items-center" "px-2" "h-6" "box-border" "cursor-pointer" "asset-selector-row-surface"]
    outcome? (conj "grid-cols-[minmax(0,1fr)_4.75rem_10.5rem_7rem_7rem]")
    (not outcome?) (conj "grid-cols-12")))

(defn- desktop-outcome-cell-classes
  [outcome? fallback-classes]
  (if outcome?
    ["min-w-0" "text-left"]
    fallback-classes))

(defn asset-list-item [asset selected? highlighted? favorites _missing-icons _loaded-icons]
  (let [{:keys [key coin symbol mark markRaw volume24h change24h change24hPct openInterest fundingRate
                market-type dex maxLeverage]} asset
        safe-change (when (some? change24h) (fmt/safe-number change24h))
        safe-change-pct (when (some? change24hPct) (fmt/safe-number change24hPct))
        safe-funding-rate (when (some? fundingRate) (fmt/safe-number fundingRate))
        change-available? (and (number? safe-change)
                               (number? safe-change-pct)
                               (not (js/isNaN safe-change))
                               (not (js/isNaN safe-change-pct)))
        is-positive (and change-available?
                         (>= safe-change 0))
        change-color (if is-positive "text-success" "text-error")
        funding-available? (and (number? safe-funding-rate)
                                (not (js/isNaN safe-funding-rate)))
        funding-positive (and funding-available?
                              (>= safe-funding-rate 0))
        funding-color (if funding-positive "text-success" "text-error")
        is-spot (= market-type :spot)
        is-outcome (= market-type :outcome)
        favorite? (contains? favorites key)]
    [:div
     {:class (desktop-grid-classes is-outcome)
      :data-row-state (asset-selector-row-state selected? highlighted?)
      :data-role "asset-selector-row"
      :style {:contain "layout paint style"
              :content-visibility "auto"
              :contain-intrinsic-size (str list-metrics/row-height-px "px")}
      :on {:click (select-asset-click-handler asset)}}
     [:div {:class (cond-> ["flex" "items-center" "space-x-1.5" "min-w-0"]
                     (not is-outcome) (conj "col-span-3"))}
      (icons/favorite-button favorite? key)
      [:div.flex.items-center.space-x-1.5.min-w-0.overflow-hidden
       [:div.text-sm.truncate.whitespace-nowrap symbol]
       (when is-spot
         (controls/chip "SPOT" ["bg-gray-500/20" "text-gray-200" "border-gray-500/30" "shrink-0"]))
       (when dex
         (controls/chip dex ["bg-emerald-500/20" "text-emerald-300" "border-emerald-500/30" "shrink-0"]))
       (when (and maxLeverage (> maxLeverage 0))
         (controls/chip (str maxLeverage "x") ["bg-primary/20" "text-primary" "border-primary/30" "shrink-0"]))]]
     [:div {:class (desktop-outcome-cell-classes is-outcome
                                                  ["col-span-2" "text-left"])}
      (if is-outcome
        [:div.text-sm.text-gray-400.num
         (if (number? mark)
           (str (js/Math.round (* mark 100)) "%")
           "—")]
        [:div.text-sm.text-gray-400.num
         (or (fmt/format-trade-price mark markRaw) "—")])]
     [:div {:class (desktop-outcome-cell-classes is-outcome
                                                  ["col-span-2" "text-left"])}
      (if change-available?
        [:div {:class [change-color "text-sm" "num"]}
         (str (if is-positive "+" "") (or (fmt/format-trade-price-delta safe-change) "0.00")
              " (" (fmt/safe-to-fixed safe-change-pct 2) "%)")]
        [:div.text-sm.text-gray-400.num "—"])]
     (when-not is-outcome
       [:div {:class (desktop-outcome-cell-classes is-outcome
                                                    ["col-span-1" "text-left"])}
        (if is-spot
          [:div.text-sm.text-gray-400.num "—"]
          (if funding-available?
            (controls/tooltip
              [[:div {:class [funding-color "text-sm" "cursor-help" "num" "text-left"]
                      :style {:min-width "max-content"}}
                (str (if funding-positive "+" "") (fmt/safe-to-fixed (* safe-funding-rate 100) 4) "%")]
               (str "Annualized: " (fmt/format-percentage (fmt/annualized-funding-rate (* safe-funding-rate 100)) 2))]
              "bottom")
            [:div.text-sm.text-gray-400.num "—"]))])
     [:div {:class (if is-outcome
                     ["min-w-0" "text-left" "text-sm" "num"]
                     ["col-span-2" "text-left" "text-sm" "num"])}
      (format-or-dash volume24h fmt/format-large-currency)]
     [:div {:class (if is-outcome
                     ["min-w-0" "text-left" "text-sm" "num"]
                     ["col-span-2" "text-left" "text-sm" "num"])}
      (if is-spot
        "—"
        (format-or-dash openInterest fmt/format-large-currency))]]))

(defn market-key-present?
  [market-key assets]
  (some (fn [asset]
          (= market-key (:key asset)))
        assets))

(defn effective-highlighted-market-key
  [assets selected-market-key highlighted-market-key]
  (cond
    (and (string? highlighted-market-key)
         (market-key-present? highlighted-market-key assets))
    highlighted-market-key

    (and (string? selected-market-key)
         (market-key-present? selected-market-key assets))
    selected-market-key

    :else
    (:key (first assets))))

(defn navigate-shortcut-icon []
  [:svg {:width 16
         :height 9.454545454545455
         :fill "none"
         :stroke "currentColor"
         :focusable "false"
         :aria-hidden "true"
         :viewBox "0 0 22 13"}
   [:path {:d "M3.81809 12.6989L-8.88109e-05 8.88068L0.656161 8.22443L3.34934 10.9261V1.77273L0.656161 4.47443L-8.88109e-05 3.81818L3.81809 -1.43051e-06L7.63628 3.81818L6.98855 4.47443L4.28684 1.77273V10.9261L6.98855 8.22443L7.63628 8.88068L3.81809 12.6989ZM18.432 6.76691H17.748C18.132 5.91491 18.528 5.26691 18.9 4.84691H10.152V3.99491H18.9C18.516 3.57491 18.132 2.92691 17.748 2.08691H18.432C19.272 3.07091 20.16 3.79091 21.072 4.25891V4.59491C20.16 5.06291 19.272 5.78291 18.432 6.76691ZM12.792 11.4469C11.952 10.4629 11.064 9.74291 10.152 9.27491V8.93891C11.064 8.47091 11.952 7.75091 12.792 6.76691H13.476C13.092 7.60691 12.708 8.25491 12.324 8.67491H21.072V9.52691H12.324C12.696 9.94691 13.092 10.5949 13.476 11.4469H12.792Z"
           :fill "currentColor"}]])

(defn shortcut-keycap [content]
  [:div {:class ["rounded"
                 "text-xs"
                 "leading-none"
                 "text-white"
                 "whitespace-nowrap"]
         :style {:background "rgb(39, 48, 53)"
                 :border-radius "5px"
                 :padding "2px 4px"}}
   content])

(defn shortcut-item [key-content label]
  [:div {:class ["flex" "items-center" "gap-2" "whitespace-nowrap"]}
   (shortcut-keycap key-content)
   [:div {:class ["text-xs" "text-gray-400"]}
    label]])

(defn selector-shortcut-footer []
  [:div {:class ["flex"
                 "items-center"
                 "gap-4"
                 "mt-4"
                 "overflow-x-auto"
                 "bg-base-100"
                 "px-4"
                 "py-0.5"
                 "scrollbar-hide"]}
   (shortcut-item "⌘K" "Open")
   (shortcut-item (navigate-shortcut-icon) "Navigate")
   (shortcut-item "Enter" "Select")
   (shortcut-item "⌘S" "Favorite")
   (shortcut-item "Esc" "Close")])

(defn mobile-asset-list-item [asset selected? highlighted? favorites]
  (let [{:keys [key coin symbol mark markRaw volume24h change24h change24hPct openInterest market-type dex maxLeverage]} asset
        safe-change (when (some? change24h) (fmt/safe-number change24h))
        safe-change-pct (when (some? change24hPct) (fmt/safe-number change24hPct))
        change-available? (and (number? safe-change)
                               (number? safe-change-pct)
                               (not (js/isNaN safe-change))
                               (not (js/isNaN safe-change-pct)))
        positive-change? (and change-available? (>= safe-change 0))
        change-color (if positive-change? "text-success" "text-error")
        is-spot (= market-type :spot)
        is-outcome (= market-type :outcome)
        favorite? (contains? favorites key)]
    [:div {:class ["grid"
                   "grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,0.95fr)]"
                   "gap-3"
                   "items-center"
                   "border-b"
                   "border-base-300/70"
                   "px-4"
                   "py-3"
                   "cursor-pointer"
                   "asset-selector-row-surface"]
           :data-row-state (asset-selector-row-state selected? highlighted?)
           :on {:click (select-asset-click-handler asset)}
           :data-role "mobile-asset-selector-row"}
     [:div {:class ["flex" "items-start" "gap-2.5" "min-w-0"]}
      (icons/mobile-favorite-button favorite? key)
      [:div {:class ["min-w-0" "space-y-1"]}
       [:div {:class ["truncate" "text-base" "font-medium" "leading-none" "text-trading-text"]}
        symbol]
       [:div {:class ["flex" "items-center" "gap-1.5" "overflow-hidden"]}
        (when is-outcome
          (controls/chip "OUTCOME" ["bg-sky-500/20" "text-sky-200" "border-sky-500/30" "shrink-0"]))
        (when is-spot
          (controls/chip "SPOT" ["bg-gray-500/20" "text-gray-200" "border-gray-500/30" "shrink-0"]))
        (when dex
          (controls/chip dex ["bg-emerald-500/20" "text-emerald-300" "border-emerald-500/30" "shrink-0"]))
        (when (and maxLeverage (> maxLeverage 0))
          (controls/chip (str maxLeverage "x") ["bg-primary/20" "text-primary" "border-primary/30" "shrink-0"]))]]]
     [:div {:class ["min-w-0" "space-y-1" "text-left"]}
      [:div {:class ["num" "text-base" "font-semibold" "leading-none" "text-trading-text"]}
       (format-or-dash volume24h fmt/format-large-currency)]
      [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
       (if is-spot
         "--"
         (format-or-dash openInterest fmt/format-large-currency))]]
     [:div {:class ["min-w-0" "space-y-1" "text-right"]}
      [:div {:class ["num" "text-base" "font-semibold" "leading-none" "text-trading-text"]}
       (if is-outcome
         (if (number? mark)
           (str (js/Math.round (* mark 100)) "%")
           "—")
         (or (fmt/format-trade-price mark markRaw) "—"))]
      [:div
       (if change-available?
         {:class [change-color "num" "text-xs"]}
         {:class ["num" "text-xs" "text-trading-text-secondary"]})
       (if change-available?
         (fmt/format-percentage safe-change-pct)
         "—")]]]))

(defn mobile-asset-list [assets selected-market-key highlighted-market-key favorites suppress-empty-state?]
  (if (empty? assets)
    (if suppress-empty-state?
      [:div {:class ["flex-1" "px-4" "py-10"]}]
      [:div {:class ["flex-1" "px-4" "py-10" "text-center" "text-gray-400"]}
       [:div "No assets found"]
       [:div {:class ["mt-1" "text-xs"]} "Try adjusting your search"]])
    [:div {:class ["flex-1" "overflow-y-auto" "scrollbar-hide"]}
     (for [asset assets]
       ^{:key (:key asset)}
       (mobile-asset-list-item asset
                               (= selected-market-key (:key asset))
                               (= highlighted-market-key (:key asset))
                               favorites))]))
