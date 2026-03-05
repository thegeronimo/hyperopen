(ns hyperopen.views.ghost-mode-modal
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.wallet.core :as wallet]))

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
  "[data-role='ghost-mode-open-button']")

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

(defn- watchlist-action-icon-button
  [{:keys [aria-label title on-click data-role disabled? class]} icon]
  [:button {:type "button"
            :class (cond-> (into ["inline-flex"
                                  "h-7"
                                  "w-7"
                                  "items-center"
                                  "justify-center"
                                  "rounded-md"
                                  "border"
                                  "border-base-300"
                                  "bg-base-200/40"
                                  "text-gray-400"
                                  "transition-colors"
                                  "focus:outline-none"
                                  "focus:ring-0"
                                  "focus:ring-offset-0"
                                  "hover:bg-base-200"
                                  "hover:text-gray-100"]
                                 (or class []))
                     disabled? (into ["cursor-not-allowed"
                                      "opacity-45"
                                      "hover:bg-base-200/40"
                                      "hover:text-gray-400"]))
            :on (when-not disabled? {:click on-click})
            :aria-label aria-label
            :title title
            :disabled disabled?
            :data-role data-role}
   icon])

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
           :data-role "ghost-mode-copy-feedback"}
     [:svg {:viewBox "0 0 20 20"
            :class ["h-3.5" "w-3.5" "shrink-0"]
            :fill "none"
            :stroke "currentColor"
            :stroke-width "1.9"
            :stroke-linecap "round"
            :stroke-linejoin "round"
            :aria-hidden "true"}
      [:path {:d icon-path}]]
     [:span {:data-role "ghost-mode-copy-feedback-message"}
      message]]))

(defn- spectate-icon
  []
  [:svg {:viewBox "0 0 24 24"
         :class ["h-3.5" "w-3.5"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M3 12s3.5-5 9-5 9 5 9 5-3.5 5-9 5-9-5-9-5z"}]
   [:circle {:cx "12" :cy "12" :r "2.25"}]])

(defn- copy-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3.5" "w-3.5"]
         :fill "currentColor"
         :aria-hidden "true"}
   [:path {:d "M4 4a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v1h-2V4H6v8h1v2H6a2 2 0 0 1-2-2V4z"}]
   [:path {:d "M8 7a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V7zm2 0h4v7h-4V7z"}]])

(defn- link-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3.5" "w-3.5"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.7"
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M8 12l4-4"}]
   [:path {:d "M6.4 14.6 4.9 16.1a2.8 2.8 0 0 1-4 0 2.8 2.8 0 0 1 0-4l1.5-1.5"}]
   [:path {:d "M13.6 5.4 15.1 3.9a2.8 2.8 0 0 1 4 0 2.8 2.8 0 0 1 0 4l-1.5 1.5"}]])

(defn- edit-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3.5" "w-3.5"]
         :fill "currentColor"
         :aria-hidden "true"}
   [:path {:d "M4 13.5V16h2.5L14 8.5 11.5 6 4 13.5Z"}]
   [:path {:d "M10.5 7 13 9.5"}]])

(defn- remove-icon
  []
  [:svg {:viewBox "0 0 20 20"
         :class ["h-3.5" "w-3.5"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :stroke-linecap "round"
         :aria-hidden "true"}
   [:path {:d "M5 5 15 15"}]
   [:path {:d "M15 5 5 15"}]])

(defn- watchlist-display-label
  [entry]
  (or (:label entry)
      ""))

(defn- watchlist-display-address
  [address]
  (or (wallet/short-addr address)
      address))

(defn- watchlist-row
  [entry active? editing?]
  (let [address (:address entry)
        label (watchlist-display-label entry)]
    [:li {:class (cond-> ["grid"
                          "grid-cols-[minmax(0,1.15fr)_minmax(0,0.95fr)_auto]"
                          "items-center"
                          "gap-1.5"
                          "px-3"
                          "py-2.5"]
                    active? (into ["bg-base-200/80"])
                    (not active?) (into ["bg-base-100"])
                    editing? (into ["ring-1" "ring-[#4f8f87]/70"]))
        :data-role "ghost-mode-watchlist-row"}
     [:div {:class ["min-w-0"]
            :data-role "ghost-mode-watchlist-label"}
      [:span {:class ["text-m" "font-medium" "text-gray-100" "break-words"]}
       label]]
     [:div {:class ["min-w-0" "truncate"]
            :data-role "ghost-mode-watchlist-address"}
      [:span {:class ["num" "truncate" "text-sm" "text-gray-400"]}
       (watchlist-display-address address)]]
     [:div {:class ["flex" "items-center" "justify-end" "gap-1"]
            :data-role "ghost-mode-watchlist-actions"}
      (watchlist-action-icon-button
       {:aria-label (if active? "Currently spectating this address" "Spectate this address")
        :title (if active? "Currently spectating" "Spectate this address")
        :on-click [[:actions/spectate-ghost-mode-watchlist-address address]]
        :class (when active? ["text-[#e8c25f]"
                              "border-[#7f6a39]"
                              "bg-[#2a2418]"
                              "hover:border-[#9f854c]"
                              "hover:bg-[#3a301f]"
                              "hover:text-[#f2d981]"])
        :data-role "ghost-mode-watchlist-spectate"}
       (spectate-icon))
      (watchlist-action-icon-button
       {:aria-label "Copy watchlist address"
        :title "Copy address"
        :on-click [[:actions/copy-ghost-mode-watchlist-address address]]
        :data-role "ghost-mode-watchlist-copy"}
       (copy-icon))
      (watchlist-action-icon-button
       {:aria-label "Link feature coming soon"
        :title "Link address (coming soon)"
        :data-role "ghost-mode-watchlist-link-placeholder"
        :disabled? true}
       (link-icon))
      (watchlist-action-icon-button
       {:aria-label "Edit watchlist label"
        :title "Edit label"
        :on-click [[:actions/edit-ghost-mode-watchlist-address address]]
        :data-role "ghost-mode-watchlist-edit"}
       (edit-icon))
      (watchlist-action-icon-button
       {:aria-label "Remove watchlist address"
        :title "Remove address"
        :on-click [[:actions/remove-ghost-mode-watchlist-address address]]
        :data-role "ghost-mode-watchlist-remove"}
       (remove-icon))]]))

(defn ghost-mode-modal-view
  [state]
  (let [ui-state (get-in state [:account-context :ghost-ui] {})
        open? (true? (:modal-open? ui-state))
        anchor (:anchor ui-state)
        search (or (:search ui-state) "")
        label (or (:label ui-state) "")
        editing-address (account-context/normalize-address
                         (:editing-watchlist-address ui-state))
        search-error (:search-error ui-state)
        copy-feedback (get-in state [:wallet :copy-feedback])
        show-copy-feedback? (and (map? copy-feedback)
                                 (seq (:message copy-feedback)))
        watchlist (account-context/normalize-watchlist
                   (get-in state [:account-context :watchlist]))
        active? (account-context/ghost-mode-active? state)
        active-address (account-context/ghost-address state)
        valid-search? (some? (account-context/normalize-address search))
        start-disabled? (not valid-search?)
        add-disabled? (not valid-search?)
        edit-mode? (some? editing-address)
        show-label-row? (or edit-mode?
                            valid-search?
                            (seq label))
        add-watchlist-label (if edit-mode?
                             "Save Label"
                             "Add To Watchlist")
        stored-anchor* (if (map? anchor) anchor {})
        fallback-anchor* (when-not (complete-anchor? stored-anchor*)
                           (element-anchor-bounds fallback-anchor-selector))
        anchor* (or fallback-anchor* stored-anchor*)
        panel-style (anchored-panel-layout-style anchor*)]
    (when open?
      [:div {:class ["fixed" "inset-0" "z-[290]" "pointer-events-none"]
             :data-role "ghost-mode-modal-root"}
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
              :style panel-style
              :role "dialog"
              :aria-modal false
              :aria-label "Ghost Mode"
              :data-role "ghost-mode-modal"
              :data-ghost-mode-surface "true"}
        [:div {:class ["px-4"
                       "pt-3.5"
                       "pb-1.5"]}
         [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
          [:div {:class ["flex" "min-w-0" "items-center" "gap-2"]}
           [:h2 {:class ["text-[17px]" "font-semibold" "leading-[25px]" "text-[#e5eef1]"]}
            "Ghost Mode"]]
          [:div {:class ["flex" "items-center" "gap-2"]}
           (when active?
             [:button {:type "button"
                       :class (modal-button-classes false false)
                       :on {:click [[:actions/stop-ghost-mode]]}
                       :data-role "ghost-mode-stop"}
              "Stop"])
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
                     :on {:click [[:actions/close-ghost-mode-modal]]}
                     :aria-label "Close Ghost Mode"
                     :data-role "ghost-mode-close"}
            [:svg {:viewBox "0 0 20 20"
                   :class ["h-4" "w-4"]
                   :fill "none"
                   :stroke "currentColor"
                   :stroke-width "1.8"
                   :stroke-linecap "round"
                   :aria-hidden "true"}
             [:path {:d "M5 5 15 15"}]
             [:path {:d "M15 5 5 15"}]]]]]
         [:div {:class ["mt-2.5" "space-y-2"]}
          [:div {:class input-row-layout-classes}
           [:input {:type "text"
                    :value search
                    :placeholder "Search or enter address to spectate..."
                    :spell-check false
                    :auto-capitalize "off"
                    :auto-complete "off"
                    :class ["w-full"
                            "rounded-lg"
                            "border"
                            "border-base-300"
                            "bg-base-200"
                            "px-3"
                            "py-2.5"
                            "text-m"
                            "leading-[19px]"
                            "text-gray-100"
                            "placeholder:text-gray-500"
                            "outline-none"
                            "focus:border-[#8a96a6]"]
                    :on {:input [[:actions/set-ghost-mode-search [:event.target/value]]]}
                    :data-role "ghost-mode-search-input"}]
           [:button {:type "button"
                     :class (into (modal-button-classes true start-disabled?)
                                  input-row-action-button-classes)
                     :disabled start-disabled?
                     :on {:click [[:actions/start-ghost-mode]]}
                     :data-role "ghost-mode-start"}
            (if active? "Switch" "Spectate")]]
          (when show-label-row?
            [:div {:class input-row-layout-classes}
             [:input {:type "text"
                      :value label
                      :placeholder "Enter label for this address..."
                      :spell-check false
                      :auto-capitalize "off"
                      :auto-complete "off"
                      :class ["w-full"
                              "rounded-lg"
                              "border"
                              "border-base-300"
                              "bg-base-200"
                              "px-3"
                              "py-2"
                              "text-m"
                              "leading-[19px]"
                              "text-gray-100"
                              "placeholder:text-gray-500"
                              "outline-none"
                              "focus:border-[#8a96a6]"]
                      :on {:input [[:actions/set-ghost-mode-label [:event.target/value]]]}
                      :data-role "ghost-mode-label-input"}]
             [:button {:type "button"
                       :class (into (modal-button-classes false add-disabled?)
                                    input-row-action-button-classes)
                       :disabled add-disabled?
                       :on {:click [[:actions/add-ghost-mode-watchlist-address]]}
                       :data-role "ghost-mode-add-watchlist"}
              add-watchlist-label]
             (when edit-mode?
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
                         :on {:click [[:actions/clear-ghost-mode-watchlist-edit]]}
                         :data-role "ghost-mode-clear-watchlist-edit"}
                "Cancel"])])
          (when active?
            [:div {:class ["rounded-lg"
                           "border"
                           "border-[#1f4f4f]"
                           "bg-[#0a2f33]/60"
                           "px-2.5"
                           "py-1.5"
                           "text-xs"
                           "text-[#bdeee8]"]
                   :data-role "ghost-mode-active-summary"}
             [:span {:class ["font-medium"]}
              "Currently spectating: "]
             [:span {:class ["num"]} active-address]])
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
                   :data-role "ghost-mode-search-error"}
             search-error])]]
        [:div {:class ["flex" "min-h-0" "flex-1" "flex-col"]}
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
                :data-role "ghost-mode-watchlist-header"}
          [:span "Label"]
          [:span "Address"]
          [:span {:class ["text-right"]} "Actions"]]
         (if (seq watchlist)
           (into
            [:ul {:class ["min-h-0"
                          "flex-1"
                          "overflow-y-auto"
                          "border-b"
                          "border-base-300"
                          "divide-y"
                          "divide-base-300"]
                  :data-role "ghost-mode-watchlist"}]
            (map (fn [entry]
                   (let [address (:address entry)]
                     ^{:key address}
                     (watchlist-row entry
                                    (= address active-address)
                                    (= address editing-address))))
                 watchlist))
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
                  :data-role "ghost-mode-watchlist-empty"}
            "No spectated addresses saved yet."])]
        (when show-copy-feedback?
          [:div {:class ["border-t"
                         "border-[#1e353f]"
                         "bg-[#0a2029]"
                         "px-4"
                         "pt-2"
                         "pb-2.5"]
                 :data-role "ghost-mode-copy-feedback-slot"}
           (copy-feedback-row copy-feedback)])]])))
