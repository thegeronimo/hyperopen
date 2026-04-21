(ns hyperopen.views.spectate-mode-modal
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.views.spectate-mode-modal.watchlist :as watchlist]))

(def ^:private panel-gap-px
  10)

(def ^:private panel-margin-px
  12)

(def ^:private preferred-panel-width-px
  520)

(def ^:private estimated-panel-height-px
  560)

(def ^:private fallback-viewport-width
  1280)

(def ^:private fallback-viewport-height
  800)

(def ^:private fallback-anchor-top
  84)

(def ^:private fallback-anchor-selector
  "[data-role='spectate-mode-open-button']")

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- anchor-number
  [anchor k default]
  (let [value (get anchor k)]
    (if (number? value)
      value
      default)))

(defn- complete-anchor?
  [anchor]
  (and (map? anchor)
       (number? (:left anchor))
       (number? (:right anchor))
       (number? (:top anchor))
       (number? (:bottom anchor))))

(defn- element-anchor-bounds
  [selector]
  (when (seq selector)
    (let [document* (some-> js/globalThis .-document)
          target (some-> document* (.querySelector selector))]
      (when (and target (fn? (.-getBoundingClientRect target)))
        (let [rect (.getBoundingClientRect target)]
          {:left (.-left rect)
           :right (.-right rect)
           :top (.-top rect)
           :bottom (.-bottom rect)
           :width (.-width rect)
           :height (.-height rect)
           :viewport-width (some-> js/globalThis .-innerWidth)
           :viewport-height (some-> js/globalThis .-innerHeight)})))))

(defn- anchored-panel-layout-style
  [anchor]
  (let [anchor* (if (map? anchor) anchor {})
        viewport-width (max 320
                            (anchor-number anchor* :viewport-width fallback-viewport-width)
                            (+ (anchor-number anchor* :right 0) panel-margin-px))
        viewport-height (max 320
                             (anchor-number anchor* :viewport-height fallback-viewport-height))
        anchor-right (anchor-number anchor* :right (- viewport-width panel-margin-px))
        anchor-top (anchor-number anchor* :top fallback-anchor-top)
        anchor-bottom (anchor-number anchor* :bottom (+ anchor-top 40))
        panel-width (clamp (- viewport-width (* 2 panel-margin-px))
                           320
                           preferred-panel-width-px)
        left (clamp (- anchor-right panel-width)
                    panel-margin-px
                    (- viewport-width panel-width panel-margin-px))
        below-top (+ anchor-bottom panel-gap-px)
        above-top (- anchor-top panel-gap-px estimated-panel-height-px)
        fits-below? (<= (+ below-top estimated-panel-height-px panel-margin-px)
                        viewport-height)
        top (if fits-below?
              (clamp below-top
                     panel-margin-px
                     (- viewport-height estimated-panel-height-px panel-margin-px))
              (clamp above-top
                     panel-margin-px
                     (- viewport-height estimated-panel-height-px panel-margin-px)))
        max-height (max 300
                        (- viewport-height top panel-margin-px))]
    {:left (str left "px")
     :top (str top "px")
     :width (str panel-width "px")
     :max-height (str max-height "px")}))

(defn- modal-button-classes
  [primary? disabled?]
  (cond
    disabled?
    ["rounded-lg"
     "border"
     "border-base-300"
     "bg-base-200"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-gray-500"
     "cursor-not-allowed"]

    primary?
    ["rounded-lg"
     "border"
     "border-primary/40"
     "bg-primary"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-primary-content"
     "hover:bg-primary/90"]

    :else
    ["rounded-lg"
     "border"
     "border-base-300"
     "bg-base-200"
     "px-3.5"
     "py-2"
     "text-sm"
     "text-gray-200"
     "hover:bg-base-300"
     "hover:text-gray-100"]))

(def ^:private input-row-action-button-classes
  ["h-8"
   "w-full"
   "justify-center"
   "px-2"
   "py-1"
   "text-xs"
   "font-medium"
   "leading-none"
   "whitespace-nowrap"])

(def ^:private input-row-layout-classes
  ["grid"
   "grid-cols-[minmax(0,1fr)_7.75rem]"
   "items-center"
   "gap-2"])

(defn- copy-feedback-row
  [copy-feedback]
  (let [kind (:kind copy-feedback)
        message (:message copy-feedback)
        success? (= :success kind)
        tone-classes (if success?
                       ["border-[#1f5247]"
                        "bg-[#0e2d2f]"
                        "text-[#aefde8]"]
                       ["border-[#6d2d3a]"
                        "bg-[#361b24]"
                        "text-[#f5b8c6]"])
        icon-path (if success?
                    "M16 5.5 7.5 14 4 10.5"
                    "M6 6 14 14 M14 6 6 14")]
    [:div {:class (into ["flex"
                         "items-center"
                         "gap-2"
                         "rounded-md"
                         "border"
                         "px-2.5"
                         "py-2"
                         "text-xs"
                         "font-medium"]
                        tone-classes)
           :data-role "spectate-mode-copy-feedback"}
     [:svg {:viewBox "0 0 20 20"
            :class ["h-3.5" "w-3.5" "shrink-0"]
            :fill "none"
            :stroke "currentColor"
            :stroke-width "1.9"
            :stroke-linecap "round"
            :stroke-linejoin "round"
            :aria-hidden "true"}
      [:path {:d icon-path}]]
     [:span {:data-role "spectate-mode-copy-feedback-message"}
      message]]))

(defn- modal-model
  [state]
  (let [ui-state (get-in state [:account-context :spectate-ui] {})
        search (or (:search ui-state) "")
        label (or (:label ui-state) "")
        editing-address (account-context/normalize-address
                         (:editing-watchlist-address ui-state))
        copy-feedback (get-in state [:wallet :copy-feedback])
        watchlist (account-context/normalize-watchlist
                   (get-in state [:account-context :watchlist]))
        active? (account-context/spectate-mode-active? state)
        active-address (account-context/spectate-address state)
        valid-search? (some? (account-context/normalize-address search))
        edit-mode? (some? editing-address)]
    {:open? (true? (:modal-open? ui-state))
     :anchor (:anchor ui-state)
     :search search
     :label label
     :editing-address editing-address
     :search-error (:search-error ui-state)
     :copy-feedback copy-feedback
     :show-copy-feedback? (and (map? copy-feedback)
                               (seq (:message copy-feedback)))
     :watchlist watchlist
     :active? active?
     :active-address active-address
     :start-disabled? (not valid-search?)
     :add-disabled? (not valid-search?)
     :edit-mode? edit-mode?
     :show-label-row? (or edit-mode?
                          valid-search?
                          (seq label))
     :add-watchlist-label (if edit-mode?
                            "Save Label"
                            "Add To Watchlist")}))

(defn- resolved-panel-style
  [anchor]
  (let [stored-anchor* (if (map? anchor) anchor {})
        fallback-anchor* (when-not (complete-anchor? stored-anchor*)
                           (element-anchor-bounds fallback-anchor-selector))]
    (anchored-panel-layout-style (or fallback-anchor* stored-anchor*))))

(defn- close-button
  []
  [:button {:type "button"
            :class ["inline-flex"
                    "h-8"
                    "w-8"
                    "items-center"
                    "justify-center"
                    "rounded-md"
                    "text-gray-400"
                    "hover:bg-base-200"
                    "hover:text-gray-100"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"]
            :on {:click [[:actions/close-spectate-mode-modal]]}
            :aria-label "Close Spectate Mode"
            :data-role "spectate-mode-close"}
   [:svg {:viewBox "0 0 20 20"
          :class ["h-4" "w-4"]
          :fill "none"
          :stroke "currentColor"
          :stroke-width "1.8"
          :stroke-linecap "round"
          :aria-hidden "true"}
    [:path {:d "M5 5 15 15"}]
    [:path {:d "M15 5 5 15"}]]])

(defn- modal-header
  [{:keys [active?]}]
  [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
   [:div {:class ["flex" "min-w-0" "items-center" "gap-2"]}
    [:h2 {:class ["text-[17px]" "font-semibold" "leading-[25px]" "text-[#e5eef1]"]}
     "Spectate Mode"]]
   [:div {:class ["flex" "items-center" "gap-2"]}
    (when active?
      [:button {:type "button"
                :class (modal-button-classes false false)
                :on {:click [[:actions/stop-spectate-mode]]}
                :data-role "spectate-mode-stop"}
       "Stop"])
    (close-button)]])

(defn- modal-input
  [{:keys [value placeholder py-class on-input data-role]}]
  [:input {:type "text"
           :value value
           :placeholder placeholder
           :spell-check false
           :auto-capitalize "off"
           :auto-complete "off"
           :class ["w-full"
                   "rounded-lg"
                   "border"
                   "border-base-300"
                   "bg-base-200"
                   "px-3"
                   py-class
                   "text-m"
                   "leading-[19px]"
                   "text-gray-100"
                   "placeholder:text-gray-500"
                   "focus:outline-none"
                   "focus-visible:outline-none"
                   "focus:border-base-300"
                   "focus:ring-1"
                   "focus:ring-[#8a96a6]/35"
                   "focus:ring-offset-0"
                   "focus:shadow-none"]
           :on {:input on-input}
           :data-role data-role}])

(defn- search-row
  [{:keys [active? search start-disabled?]}]
  [:div {:class input-row-layout-classes}
   (modal-input
    {:value search
     :placeholder "Search or enter address to spectate..."
     :py-class "py-2.5"
     :on-input [[:actions/set-spectate-mode-search [:event.target/value]]]
     :data-role "spectate-mode-search-input"})
   [:button {:type "button"
             :class (into (modal-button-classes true start-disabled?)
                          input-row-action-button-classes)
             :disabled start-disabled?
             :on {:click [[:actions/start-spectate-mode]]}
             :data-role "spectate-mode-start"}
    (if active? "Switch" "Spectate")]])

(defn- cancel-edit-button
  []
  [:button {:type "button"
            :class ["rounded-lg"
                    "border"
                    "border-base-300"
                    "bg-base-200"
                    "px-2.5"
                    "py-1.5"
                    "text-xs"
                    "text-gray-200"
                    "hover:bg-base-300"
                    "hover:text-gray-100"]
            :on {:click [[:actions/clear-spectate-mode-watchlist-edit]]}
            :data-role "spectate-mode-clear-watchlist-edit"}
   "Cancel"])

(defn- label-row
  [{:keys [label add-disabled? add-watchlist-label edit-mode? show-label-row?]}]
  (when show-label-row?
    [:div {:class input-row-layout-classes}
     (modal-input
      {:value label
       :placeholder "Enter label for this address..."
       :py-class "py-2"
       :on-input [[:actions/set-spectate-mode-label [:event.target/value]]]
       :data-role "spectate-mode-label-input"})
     [:button {:type "button"
               :class (into (modal-button-classes false add-disabled?)
                            input-row-action-button-classes)
               :disabled add-disabled?
               :on {:click [[:actions/add-spectate-mode-watchlist-address]]}
               :data-role "spectate-mode-add-watchlist"}
      add-watchlist-label]
     (when edit-mode?
       (cancel-edit-button))]))

(defn- active-summary
  [{:keys [active? active-address]}]
  (when active?
    [:div {:class ["rounded-lg"
                   "border"
                   "border-[#1f4f4f]"
                   "bg-[#0a2f33]/60"
                   "px-2.5"
                   "py-1.5"
                   "text-xs"
                   "text-[#bdeee8]"]
           :data-role "spectate-mode-active-summary"}
     [:span {:class ["font-medium"]}
      "Currently spectating: "]
     [:span {:class ["num"]} active-address]]))

(defn- search-error-row
  [{:keys [search-error]}]
  (when (seq search-error)
    [:div {:class ["rounded-md"
                   "border"
                   "border-[#7b3340]"
                   "bg-[#3a1b22]/55"
                   "px-2.5"
                   "py-2"
                   "text-m"
                   "leading-[19px]"
                   "text-[#f2b8c5]"]
           :data-role "spectate-mode-search-error"}
     search-error]))

(defn- modal-controls
  [model]
  [:div {:class ["px-4"
                 "pt-3.5"
                 "pb-1.5"]}
   (modal-header model)
   [:div {:class ["mt-2.5" "space-y-2"]}
    (search-row model)
    (label-row model)
    (active-summary model)
    (search-error-row model)]])

(defn- watchlist-header
  []
  [:div {:class ["grid"
                 "grid-cols-[minmax(0,1.15fr)_minmax(0,0.95fr)_auto]"
                 "items-center"
                 "gap-2"
                 "border-y"
                 "border-base-300"
                 "px-3"
                 "py-2"
                 "text-xs"
                 "font-medium"
                 "text-gray-400"]
         :data-role "spectate-mode-watchlist-header"}
   [:span "Label"]
   [:span "Address"]
   [:span {:class ["text-right"]} "Actions"]])

(defn- watchlist-empty-state
  []
  [:div {:class ["flex"
                 "min-h-[160px]"
                 "flex-1"
                 "items-center"
                 "justify-center"
                 "rounded-lg"
                 "border"
                 "border-dashed"
                 "border-[#2a4b4f]"
                 "bg-[#081f28]"
                 "px-3"
                 "text-sm"
                 "text-[#90a6ad]"]
         :data-role "spectate-mode-watchlist-empty"}
   "No spectated addresses saved yet."])

(defn- watchlist-rows
  [{:keys [watchlist active-address editing-address]}]
  (into
   [:ul {:class ["min-h-0"
                 "flex-1"
                 "overflow-y-auto"
                 "border-b"
                 "border-base-300"
                 "divide-y"
                 "divide-base-300"]
         :data-role "spectate-mode-watchlist"}]
   (map (fn [entry]
          (let [address (:address entry)]
            ^{:key address}
            (watchlist/watchlist-row entry
                                     (= address active-address)
                                     (= address editing-address))))
        watchlist)))

(defn- watchlist-section
  [{:keys [watchlist] :as model}]
  [:div {:class ["flex" "min-h-0" "flex-1" "flex-col"]}
   (watchlist-header)
   (if (seq watchlist)
     (watchlist-rows model)
     (watchlist-empty-state))])

(defn- copy-feedback-slot
  [{:keys [show-copy-feedback? copy-feedback]}]
  (when show-copy-feedback?
    [:div {:class ["border-t"
                   "border-[#1e353f]"
                   "bg-[#0a2029]"
                   "px-4"
                   "pt-2"
                   "pb-2.5"]
           :data-role "spectate-mode-copy-feedback-slot"}
     (copy-feedback-row copy-feedback)]))

(defn- modal-dialog
  [{:keys [anchor] :as model}]
  [:div {:class ["absolute"
                 "pointer-events-auto"
                 "flex"
                 "max-h-full"
                 "min-h-0"
                 "flex-col"
                 "overflow-hidden"
                 "rounded-xl"
                 "border"
                 "border-[#1f3b3c]"
                 "bg-base-100"
                 "shadow-2xl"]
         :style (resolved-panel-style anchor)
         :role "dialog"
         :aria-modal false
         :aria-label "Spectate Mode"
         :data-role "spectate-mode-modal"
         :data-spectate-mode-surface "true"}
   (modal-controls model)
   (watchlist-section model)
   (copy-feedback-slot model)])

(defn spectate-mode-modal-view
  [state]
  (let [{:keys [open?] :as model} (modal-model state)]
    (when open?
      [:div {:class ["fixed" "inset-0" "z-[290]" "pointer-events-none"]
             :data-role "spectate-mode-modal-root"}
       (modal-dialog model)])))
