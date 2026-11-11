(ns hyperopen.views.trade-view.shell
  (:require [hyperopen.platform :as platform]
            [hyperopen.system :as app-system]
            [hyperopen.views.ui.dialog-focus :as dialog-focus]
            [hyperopen.views.ui.funding-modal-positioning :as funding-modal-positioning]
            [nexus.registry :as nxr]))

(defn- dispatch!
  [actions]
  (when app-system/store
    (nxr/dispatch app-system/store nil actions)))

(def ^:private outcome-buy-selector "[data-role='outcome-mobile-buy']")

(defn- sit-above-bottom-nav!
  "Pin the bar to the top edge of the phone's bottom navigation."
  [{:replicant/keys [node]}]
  (when node
    (let [nav (.querySelector js/document "[data-role='mobile-bottom-nav']")
          nav-top (when nav (.-top (.getBoundingClientRect nav)))
          offset (if (and (number? nav-top) (pos? nav-top))
                   (max 0 (- (.-innerHeight js/window) nav-top))
                   0)]
      (set! (.. node -style -bottom) (str offset "px")))))

(defn render-outcome-mobile-bar
  "Both chances and one Buy button, pinned above the bottom navigation on a
   phone. Buy opens the ticket as a sheet (ONE action)."
  [{:keys [layout]} {:keys [entries title]}]
  (when (and (:outcome-bar? layout) (seq entries))
    [:div {:class ["fixed" "inset-x-0" "z-[180]" "flex" "items-center" "gap-3" "border-t" "border-base-300"
                   "bg-base-200" "px-3" "py-2" "lg:hidden"]
           :style {:bottom "0px"}
           :replicant/on-render sit-above-bottom-nav!
           :data-role "outcome-mobile-bar"}
     (into [:div {:class ["flex" "min-w-0" "flex-1" "items-baseline" "gap-2" "text-sm" "font-medium"]
                  :title title
                  :data-role "outcome-mobile-bar-chances"}]
           (comp (map (fn [{:keys [label text muted?]}]
                        [:span {:class ["flex" "min-w-0" "items-baseline" "gap-1"]}
                         [:span {:class ["truncate" "text-trading-text-secondary"]} label]
                         [:span {:class (into ["num" "shrink-0"]
                                              (if muted? ["text-ho-text-muted"] ["text-trading-text"]))}
                          text]]))
                 (interpose [:span {:class ["text-ho-text-muted"] :aria-hidden true} "·"]))
           entries)
     [:button {:type "button"
               :class ["h-10" "shrink-0" "rounded-md" "bg-primary" "px-6" "text-sm" "font-semibold"
                       "text-primary-content" "focus:outline-none"]
               :data-role "outcome-mobile-buy"
               ;; ONE action: open the sheet as a Buy in dollars, whatever side
               ;; the form last carried.
               :on {:click [[:actions/set-outcome-ticket-entry {:side :buy
                                                                :size-input-mode :quote
                                                                :open-ticket-sheet? true}]]}}
      "Buy"]]))

(defn- close-sheet!
  []
  (dispatch! [[:actions/select-trade-mobile-surface :chart]]))

(defn- sheet-keydown
  [event]
  (when (= "Escape" (.-key event))
    (.preventDefault event)
    (.stopPropagation event)
    (close-sheet!)))

(def ^:private dialog-focus-hook
  (dialog-focus/dialog-focus-on-render {:restore-selector outcome-buy-selector}))

(defn- focus-buy-button!
  "The Buy bar re-mounts as the sheet leaves; give focus back to it as soon as
   it is there (the dialog helper cannot, because nothing was focused when the
   sheet opened: the bar had already left the page)."
  [attempts-left]
  (platform/set-timeout!
   (fn []
     (if-let [buy (.querySelector js/document outcome-buy-selector)]
       (.focus buy)
       (when (pos? attempts-left)
         (focus-buy-button! (dec attempts-left)))))
   32))

(defn- sheet-focus
  [{:replicant/keys [life-cycle] :as context}]
  (dialog-focus-hook context)
  (when (= :replicant.life-cycle/unmount life-cycle)
    (focus-buy-button! 12)))

(defn- outcome-ticket-sheet
  "The ticket as a bottom sheet: dimmed backdrop, close button, Escape; focus
   moves in and returns to the Buy bar on close."
  [title ticket]
  [:div {:class ["relative" "flex" "max-h-[88vh]" "flex-col" "overflow-hidden" "rounded-t-xl" "border-t"
                 "border-base-300" "bg-base-100" "shadow-2xl"]
         :role "dialog"
         :aria-modal "true"
         :aria-label (str "Buy " (or title "outcome market"))
         :tabindex -1
         :replicant/on-render sheet-focus
         :on {:keydown sheet-keydown}
         :style {:padding-bottom "env(safe-area-inset-bottom)"}
         :data-role "outcome-ticket-sheet"}
   [:div {:class ["flex" "shrink-0" "items-center" "justify-between" "gap-3" "border-b" "border-base-300" "px-3" "py-2"]}
    [:div {:class ["min-w-0" "truncate" "text-sm" "font-semibold" "text-trading-text"]} (or title "Buy")]
    [:button {:type "button"
              :class ["inline-flex" "h-9" "w-9" "items-center" "justify-center" "rounded-md"
                      "text-trading-text-secondary" "hover:text-trading-text" "focus:outline-none"]
              :aria-label "Close ticket"
              :data-role "outcome-ticket-sheet-close"
              :on {:click [[:actions/select-trade-mobile-surface :chart]]}}
     [:svg {:class ["h-5" "w-5"] :fill "none" :stroke "currentColor" :viewBox "0 0 24 24" :aria-hidden true}
      [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M6 18L18 6M6 6l12 12"}]]]]
   [:div {:class ["min-h-0" "flex-1" "overflow-y-auto"]} ticket]])

(def trade-mobile-surfaces
  [[:chart "Chart"]
   [:orderbook "Order Book"]
   [:trades "Trades"]])

(defn- mobile-surface-button
  [selected-surface [surface-id label]]
  [:button {:type "button"
            :data-role (str "trade-mobile-surface-button-" (name surface-id))
            :class (into ["flex-1"
                          "border-b-2"
                          "px-2"
                          "py-2"
                          "text-sm"
                          "font-medium"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                         (if (= selected-surface surface-id)
                           ["border-primary" "text-trading-text"]
                           ["border-transparent" "text-trading-text-secondary" "hover:text-trading-text"]))
            :on {:click [[:actions/select-trade-mobile-surface surface-id]]}}
   label])

(defn desktop-secondary-panel-placeholder
  [title data-role & {:keys [fill-height?]
                      :or {fill-height? false}}]
  [:div {:class (into ["w-full"
                       "min-h-0"
                       "overflow-hidden"
                       "bg-base-100"
                       "p-3"
                       "space-y-3"]
                      (if fill-height?
                        ["h-full"]
                        ["min-h-[9rem]"]))
         :data-role data-role}
   [:div {:class ["text-sm" "font-semibold" "text-trading-text-secondary"]}
    title]
   [:div {:class ["space-y-2"]}
    [:div {:class ["h-3" "w-28" "rounded" "bg-base-300/60"]}]
    [:div {:class ["h-3" "w-full" "rounded" "bg-base-300/50"]}]
    [:div {:class ["h-3" "w-5/6" "rounded" "bg-base-300/40"]}]
    [:div {:class ["h-3" "w-2/3" "rounded" "bg-base-300/30"]}]]])

(defn render-mobile-active-asset-strip
  [state {:keys [layout]} {:keys [render-active-asset-panel]}]
  [:div {:class (:mobile-active-asset-strip-classes layout)
         :data-parity-id "trade-mobile-active-asset-strip"}
   (when (:show-mobile-active-asset? layout)
     (render-active-asset-panel state))])

(defn render-mobile-surface-tabs
  ([mobile-surface layout-context]
   (render-mobile-surface-tabs mobile-surface layout-context {}))
  ([mobile-surface {:keys [layout]} surface-labels]
   [:div {:class (:mobile-surface-tabs-classes layout)
          :data-parity-id "trade-mobile-surface-tabs"}
    [:div {:class ["flex" "items-center" "gap-0"]}
     (for [[surface-id default-label] trade-mobile-surfaces]
       ^{:key (str "trade-mobile-surface-" (name surface-id))}
       (mobile-surface-button mobile-surface
                              [surface-id (or (get surface-labels surface-id)
                                              default-label)]))]]))

(defn render-trade-chart-panel
  [desktop-layout?
   {:keys [layout]}
   {:keys [active-asset-panel-state trade-chart-panel-state]}
   {:keys [render-active-asset-panel-state trade-chart-panel-content-state chart-doodles]}]
  [:div {:class (:chart-panel-classes layout)
         :data-parity-id "trade-chart-panel"}
   [:div {:class (:desktop-active-asset-shell-classes layout)}
    (when desktop-layout?
      (render-active-asset-panel-state active-asset-panel-state))]
   (when (:chart-panel-visible? layout)
     [:div {:class ["relative" "overflow-hidden" "flex-1" "min-h-0" "min-w-0"]}
      (trade-chart-panel-content-state trade-chart-panel-state)
      chart-doodles])])

(defn render-orderbook-panel-shell
  [desktop-layout?
   {:keys [layout]}
   {:keys [orderbook-panel-state mobile-orderbook-panel-state]}
   {:keys [render-orderbook-panel]}]
  [:div {:class (:orderbook-panel-classes layout)
         :data-parity-id "trade-orderbook-panel"}
   [:div {:class ["h-full" "min-h-0" "lg:hidden"]}
    (when (and (:orderbook-panel-visible? layout)
               (not desktop-layout?))
      (render-orderbook-panel mobile-orderbook-panel-state))]
   [:div {:class ["hidden" "h-full" "min-h-0" "lg:block"]}
    (when (and (:orderbook-panel-visible? layout)
               desktop-layout?)
      (render-orderbook-panel orderbook-panel-state))]])

(defn render-order-entry-panel-shell
  [desktop-layout?
   {:keys [layout]}
   {:keys [account-equity-panel-state
           desktop-secondary-panels-ready?
           equity-metrics
           hide-account-equity?
           hyperevm-line-model
           order-form-panel-state
           outcome-bar-model]}
   {:keys [render-account-equity-panel-state render-order-form-panel]}]
  [:div {:class (:order-entry-panel-classes layout)
         :data-parity-id funding-modal-positioning/trade-order-entry-panel-parity-id}
   (if (:outcome-sheet? layout)
     [:div {:class ["absolute" "inset-0" "bg-black/60"]
            :on {:click [[:actions/select-trade-mobile-surface :chart]]}
            :data-role "outcome-ticket-sheet-backdrop"}]
     nil)
   (when (:order-entry-panel-visible? layout)
     (if (:outcome-sheet? layout)
       (outcome-ticket-sheet (:title outcome-bar-model) (render-order-form-panel order-form-panel-state))
       (render-order-form-panel order-form-panel-state)))
   (when-not hide-account-equity?
     [:div {:class ["hidden" "border-t" "border-base-300" "lg:block"]
            :data-parity-id "trade-desktop-account-equity-panel"}
      (when (and desktop-layout?
                 (:order-entry-panel-visible? layout))
        (if desktop-secondary-panels-ready?
          (or (render-account-equity-panel-state account-equity-panel-state
                                                 equity-metrics
                                                 {:hyperevm-line hyperevm-line-model})
              (desktop-secondary-panel-placeholder "Account Equity"
                                                   "trade-desktop-account-equity-placeholder"))
          (desktop-secondary-panel-placeholder "Account Equity"
                                               "trade-desktop-account-equity-placeholder")))])])

(defn render-account-panel-shell
  [state
   desktop-layout?
   {:keys [layout]}
   {:keys [account-info-panel-state desktop-secondary-panels-ready?]}
   {:keys [render-account-info-panel render-account-info-panel-state]}]
  [:div {:class (:account-panel-classes layout)
         :data-parity-id "trade-account-tables-panel"}
   [:div {:class ["w-full" "lg:hidden"]
          :data-parity-id "trade-mobile-account-panel"}
    (when (and (:account-panel-visible? layout)
               (not desktop-layout?))
      (or (render-account-info-panel state)
          (desktop-secondary-panel-placeholder "Account"
                                               "trade-mobile-account-panel-placeholder"
                                               :fill-height? true)))]
   [:div {:class ["hidden" "w-full" "min-h-0" "lg:flex"]
          :data-parity-id "trade-desktop-account-panel"}
    (when (and (:account-panel-visible? layout)
               desktop-layout?)
      (if desktop-secondary-panels-ready?
        (or (render-account-info-panel-state account-info-panel-state
                                             {:default-panel-classes ["h-full"]})
            (desktop-secondary-panel-placeholder "Account"
                                                 "trade-desktop-account-panel-placeholder"
                                                 :fill-height? true))
        (desktop-secondary-panel-placeholder "Account"
                                             "trade-desktop-account-panel-placeholder"
                                             :fill-height? true)))]])

(defn render-mobile-account-summary
  [state {:keys [layout]} {:keys [equity-metrics hyperevm-line-model]} {:keys [mobile-account-surface]}]
  (when (:mobile-account-summary-visible? layout)
    [:div {:class (:mobile-account-summary-classes layout)
           :data-parity-id "trade-mobile-account-summary-panel"}
     (mobile-account-surface state equity-metrics hyperevm-line-model)]))

(defn render-trade-grid
  [state
   {:keys [desktop-layout? layout] :as layout-context}
   panel-context
   renderers]
  [:div {:class ["relative" "flex-1" "min-h-0"]}
   [:div {:class ["hidden" "xl:block" "absolute" "top-0" "bottom-0" "right-[320px]" "w-px" "bg-base-300" "pointer-events-none" "z-10"]}]
   [:div {:class ["grid"
                  "h-full"
                  "min-h-0"
                  "grid-cols-1"
                  "gap-x-0" "gap-y-0"
                  "bg-base-100"
                  "items-stretch"
                  "lg:h-full"
                  "lg:grid-cols-[minmax(0,1fr)_320px]"
                  "xl:grid-cols-[minmax(0,1fr)_280px_320px]"]
          :style (:grid-style layout)}
    (render-trade-chart-panel desktop-layout? layout-context panel-context renderers)
    (render-orderbook-panel-shell desktop-layout? layout-context panel-context renderers)
    (render-order-entry-panel-shell desktop-layout? layout-context panel-context renderers)
    (render-account-panel-shell state desktop-layout? layout-context panel-context renderers)]
   (render-mobile-account-summary state layout-context panel-context renderers)])
