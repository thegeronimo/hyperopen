(ns hyperopen.views.portfolio.volume-history-popover
  (:require [hyperopen.views.portfolio.format :as portfolio-format]
            [hyperopen.views.ui.anchored-popover :as anchored-popover]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]))

(def ^:private preferred-width-px
  520)

(def ^:private estimated-height-px
  520)

(def ^:private trigger-selector
  "[data-role='portfolio-volume-history-trigger']")

(def ^:private focus-on-render
  (dialog-focus/dialog-focus-on-render
   {:restore-selector "[data-role=\"portfolio-volume-history-trigger\"]"}))

(defn- close-icon []
  [:svg {:class ["h-4" "w-4"]
         :fill "none"
         :stroke "currentColor"
         :viewBox "0 0 24 24"
         :aria-hidden true}
   [:path {:stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width 2
           :d "M6 18 18 6M6 6l12 12"}]])

(defn- compact-volume [value decimals]
  (let [n (if (number? value) value 0)
        magnitude (js/Math.abs n)
        sign (if (neg? n) "-" "")]
    (cond
      (>= magnitude 1000000000)
      (str sign "$" (.toFixed (/ magnitude 1000000000) decimals) "b")

      (>= magnitude 1000000)
      (str sign "$" (.toFixed (/ magnitude 1000000) decimals) "m")

      (>= magnitude 1000)
      (str sign "$" (.toFixed (/ magnitude 1000) decimals) "k")

      :else
      (portfolio-format/format-currency n))))

(defn- formatted-volume [value total?]
  (compact-volume value 2))

(defn- volume-cell [role value total?]
  [:td {:class (cond-> ["px-1.5" "py-1" "text-right" "align-middle" "sm:px-2.5"]
                 total? (conj "sticky" "bottom-0" "z-10" "bg-base-200"))
        :data-role role}
   [:span {:class (cond-> ["num" "text-xs" "font-medium" "tabular-nums" "whitespace-nowrap" "sm:text-sm"]
                    total? (conj "text-trading-green")
                    (not total?) (conj "text-trading-text")
                    true (conj "leading-tight"))}
    (formatted-volume value total?)]])

(defn- history-row [{:keys [date-label
                            exchange-volume
                            weighted-maker-volume
                            weighted-taker-volume
                            total?]}]
  [:tr {:class (cond-> ["border-t" "border-base-300/70" "leading-4"]
                 total? (conj "bg-base-200/70"))
        :data-role (if total?
                     "portfolio-volume-history-total-row"
                     "portfolio-volume-history-day-row")}
   [:td {:class (cond-> ["px-1.5" "py-1" "text-left" "align-middle" "text-xs" "font-medium" "leading-tight" "sm:px-2.5" "sm:text-sm"]
                  total? (conj "text-trading-green")
                  total? (conj "sticky" "bottom-0" "z-10" "bg-base-200")
                  (not total?) (conj "text-trading-text"))
         :style {:overflow-wrap "break-word"}}
    (or date-label "Total")]
   (volume-cell "portfolio-volume-history-cell-exchange" exchange-volume total?)
   (volume-cell "portfolio-volume-history-cell-maker" weighted-maker-volume total?)
   (volume-cell "portfolio-volume-history-cell-taker" weighted-taker-volume total?)])

(defn- header-cell [role align-class label]
  [:th {:class ["px-1.5" "py-1.5" "font-normal" "leading-snug" "sm:px-2.5" align-class]
        :data-role role
        :style {:overflow-wrap "break-word"}}
   label])

(defn- status-message [{:keys [loading? error]}]
  (cond
    loading?
    [:div {:class ["rounded-md"
                   "border"
                   "border-base-300"
                   "bg-base-200/70"
                   "px-3"
                   "py-2"
                   "text-xs"
                   "text-trading-text-secondary"]
           :role "status"
           :data-role "portfolio-volume-history-loading"}
     "Loading volume history..."]

    (seq error)
    [:div {:class ["rounded-md"
                   "border"
                   "border-error/35"
                   "bg-error/10"
                   "px-3"
                   "py-2"
                   "text-xs"
                   "text-error"]
           :data-role "portfolio-volume-history-error"}
     error]

    :else
    nil))

(defn- trigger-anchor-bounds
  []
  (let [document* (some-> js/globalThis .-document)
        target (some-> document* (.querySelector trigger-selector))]
    (when (and target (fn? (.-getBoundingClientRect target)))
      (let [rect (.getBoundingClientRect target)]
        {:left (.-left rect)
         :right (.-right rect)
         :top (.-top rect)
         :bottom (.-bottom rect)
         :width (.-width rect)
         :height (.-height rect)
         :viewport-width (some-> js/globalThis .-innerWidth)
         :viewport-height (some-> js/globalThis .-innerHeight)}))))

(defn- popover-style
  [anchor]
  (let [anchor* (if (anchored-popover/complete-anchor? anchor)
                  anchor
                  (trigger-anchor-bounds))]
    (if (anchored-popover/complete-anchor? anchor*)
      (anchored-popover/anchored-popover-layout-style
       {:anchor anchor*
        :preferred-width-px preferred-width-px
        :estimated-height-px estimated-height-px})
      {:left "12px"
       :top "12px"
       :width "min(calc(100vw - 24px), 520px)"})))

(defn- maker-share-footer
  [maker-volume-share-pct]
  [:p {:class ["mt-3" "text-xs" "text-trading-text-secondary" "sm:text-sm"]
       :data-role "portfolio-volume-history-maker-share"}
   (str "Your 14 day maker volume share is "
        (portfolio-format/format-percent maker-volume-share-pct))])

(defn volume-history-popover [{:keys [anchor open? rows maker-volume-share-pct] :as model}]
  (when open?
    (let [row-models (if (seq rows)
                       rows
                       [{:id :total
                         :date-label "Total"
                         :total? true
                         :exchange-volume 0
                         :weighted-maker-volume 0
                         :weighted-taker-volume 0}])]
      [:div {:class ["fixed" "inset-0" "z-[90]" "pointer-events-none"]
             :data-role "portfolio-volume-history-layer"}
       [:button {:type "button"
                 :class ["absolute" "inset-0" "pointer-events-auto" "bg-transparent"]
                 :aria-label "Close volume history"
                 :data-role "portfolio-volume-history-backdrop"
                 :on {:click [[:actions/close-portfolio-volume-history]]}}]
       [:section {:class ["absolute"
                          "z-[91]"
                          "pointer-events-auto"
                          "rounded-lg"
                          "border"
                          "border-base-300"
                          "bg-base-100"
                          "shadow-2xl"
                          "outline-none"
                          "p-4"
                          "sm:p-5"]
                  :style (merge (popover-style anchor)
                                {:max-height "calc(100vh - 72px)"
                                 :overflow-y "auto"})
                  :role "dialog"
                  :aria-labelledby "portfolio-volume-history-title"
                  :tabindex 0
                  :data-role "portfolio-volume-history-popover"
                  :replicant/on-render focus-on-render
                  :on {:keydown [[:actions/handle-portfolio-volume-history-keydown [:event/key]]]}}
        [:div {:class ["mb-4" "flex" "items-start" "justify-between" "gap-3"]}
         [:h2 {:id "portfolio-volume-history-title"
               :class ["text-lg" "font-semibold" "text-trading-text"]}
          "Your Volume History"]
         [:button {:type "button"
                   :class ["inline-flex"
                           "h-8"
                           "w-8"
                           "items-center"
                           "justify-center"
                           "rounded-md"
                           "text-trading-text-secondary"
                           "transition-colors"
                           "hover:bg-base-200"
                           "hover:text-trading-text"
                           "focus:outline-none"
                           "focus:ring-1"
                           "focus:ring-trading-green/40"]
                   :aria-label "Close volume history"
                   :data-role "portfolio-volume-history-close"
                   :on {:click [[:actions/close-portfolio-volume-history]]}}
          (close-icon)]]
        (status-message model)
        [:div {:class ["overflow-auto"
                       "rounded-md"
                       "border"
                       "border-base-300"
                       "bg-base-200/45"
                       "max-h-[390px]"
                       "md:max-h-[540px]"]
               :data-role "portfolio-volume-history-table-frame"}
         [:table {:class ["w-full" "border-collapse"]
                  :data-role "portfolio-volume-history-table"}
          [:colgroup
           [:col {:style {:width "30%"}}]
           [:col {:style {:width "22%"}}]
           [:col {:style {:width "24%"}}]
           [:col {:style {:width "24%"}}]]
          [:thead
           [:tr {:class ["text-left" "text-xs" "font-normal" "text-trading-text-secondary"]}
            (header-cell "portfolio-volume-history-header-date" "text-left" "Date (UTC)")
            (header-cell "portfolio-volume-history-header-exchange" "text-right" "Exchange Volume")
            (header-cell "portfolio-volume-history-header-maker" "text-right" "Your Weighted Maker Volume")
            (header-cell "portfolio-volume-history-header-taker" "text-right" "Your Weighted Taker Volume")]]
          (into [:tbody]
                (for [{:keys [id] :as row} row-models]
                  ^{:key (name (or id :total))}
                  (history-row row)))]]
        (maker-share-footer maker-volume-share-pct)]])))
