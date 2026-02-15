(ns hyperopen.views.trade.order-form-components
  (:require [clojure.string :as str]
            [hyperopen.views.trade.order-form-commands :as cmd]))

(def neutral-input-focus-classes
  ["outline-none"
   "transition-[border-color,box-shadow]"
   "duration-150"
   "hover:border-[#6f7a88]"
   "hover:ring-1"
   "hover:ring-[#6f7a88]/30"
   "hover:ring-offset-0"
   "focus:outline-none"
   "focus:ring-1"
   "focus:ring-[#8a96a6]/40"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus:border-[#8a96a6]"])

(defn section-label [text]
  [:div {:class ["text-xs" "text-gray-400" "mb-1"]} text])

(defn- label->stable-id [label-text]
  (let [safe-label (or label-text "toggle")
        slug (-> safe-label
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (seq slug) slug "toggle")))

(defn row-toggle
  ([label-text checked? on-change]
   (row-toggle label-text checked? on-change nil))
  ([label-text checked? on-change toggle-id]
   (let [checkbox-id (or toggle-id
                         (str "trade-toggle-" (label->stable-id label-text)))]
     [:div {:class ["inline-flex" "items-center" "gap-2" "text-sm" "text-gray-100"]}
      [:input {:id checkbox-id
               :class ["h-4"
                       "w-4"
                       "rounded-[3px]"
                       "border"
                       "border-base-300"
                       "bg-transparent"
                       "trade-toggle-checkbox"
                       "transition-colors"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"
                       "focus:shadow-none"]
               :type "checkbox"
               :checked (boolean checked?)
               :on {:change on-change}}]
      [:label {:for checkbox-id
               :class ["cursor-pointer" "select-none"]}
       label-text]])))

(defn input [value on-change & {:keys [type placeholder]}]
  [:input {:class (into ["w-full"
                         "h-10"
                         "px-3"
                         "bg-base-200"
                         "border"
                         "border-base-300"
                         "rounded-lg"
                         "text-sm"
                         "text-right"
                         "text-gray-100"
                         "num"
                         "placeholder:text-gray-500"]
                        neutral-input-focus-classes)
           :type (or type "text")
           :placeholder (or placeholder "")
           :value (or value "")
           :on {:input on-change}}])

(defn row-input [value placeholder on-change accessory & {:keys [input-padding-right on-focus on-blur]
                                                           :or {input-padding-right "pr-20"}}]
  (let [input-events (cond-> {:input on-change}
                       on-focus (assoc :focus on-focus)
                       on-blur (assoc :blur on-blur))]
    [:div {:class ["relative" "w-full"]}
     [:span {:class ["order-row-input-label"
                     "pointer-events-none"
                     "absolute"
                     "left-3"
                     "top-1/2"
                     "-translate-y-1/2"
                     "max-w-[52%]"
                     "truncate"
                     "text-sm"
                     "text-gray-500"]}
      placeholder]
     [:input {:class (into ["w-full"
                            "h-11"
                            "pl-24"
                            "bg-base-200"
                            "border"
                            "border-base-300"
                            "rounded-lg"
                            "text-sm"
                            "text-right"
                            "text-gray-100"
                            "num"
                            "placeholder:text-transparent"
                            "appearance-none"]
                           (concat neutral-input-focus-classes
                                   (if accessory [input-padding-right] ["pr-3"])))
              :type "text"
              :aria-label placeholder
              :placeholder placeholder
              :value (or value "")
              :on input-events}]
     (when accessory
       [:div {:class ["absolute"
                      "right-3"
                      "top-1/2"
                      "-translate-y-1/2"
                      "shrink-0"]}
        accessory])]))

(defn inline-labeled-scale-input [label value on-change]
  [:div {:class ["relative" "w-full"]}
   [:span {:class ["pointer-events-none"
                   "absolute"
                   "left-3"
                   "top-1/2"
                   "-translate-y-1/2"
                   "text-sm"
                   "text-gray-400"
                   "truncate"
                   "max-w-[55%]"]}
    label]
   [:input {:class (into ["w-full"
                          "h-10"
                          "bg-base-200"
                          "border"
                          "border-base-300"
                          "rounded-lg"
                          "text-right"
                          "text-sm"
                          "font-semibold"
                          "text-gray-100"
                          "num"
                          "appearance-none"
                          "pl-24"
                          "pr-3"]
                         neutral-input-focus-classes)
            :type "text"
            :aria-label label
            :value (or value "")
            :on {:input on-change}}]])

(defn chip-button [label active? & {:keys [on-click disabled?]}]
  [:button {:type "button"
            :disabled (boolean disabled?)
            :class (into ["flex-1"
                          "h-10"
                          "rounded-lg"
                          "text-sm"
                          "font-semibold"
                          "transition-colors"]
                         (if active?
                           ["bg-base-200" "text-gray-100" "border" "border-base-300"]
                           ["bg-base-200/60" "text-gray-300" "border" "border-base-300/80"]))
            :on (when (and on-click (not disabled?))
                  {:click on-click})}
   label])

(defn mode-button [label active? on-click]
  [:button {:type "button"
            :class (into ["flex-1"
                          "h-10"
                          "text-sm"
                          "font-medium"
                          "border-b-2"
                          "transition-colors"]
                         (if active?
                           ["text-gray-100" "border-primary"]
                           ["text-gray-400" "border-transparent" "hover:text-gray-200"]))
            :on {:click on-click}}
   label])

(defn side-button [label side active? on-click]
  (let [active-classes (case side
                         :buy ["bg-[#50D2C1]" "text-[#0F1A1F]"]
                         :sell ["bg-[#ED7088]" "text-[#F6FEFD]"]
                         ["bg-primary" "text-primary-content"])]
    [:button {:type "button"
              :class (into ["flex-1"
                            "h-10"
                            "text-sm"
                            "font-semibold"
                            "rounded-md"
                            "transition-colors"]
                           (if active?
                             active-classes
                             ["bg-[#273035]" "text-[#F6FEFD]"]))
              :on {:click on-click}}
     label]))

(defn entry-mode-tabs
  [{:keys [entry-mode
           type
           pro-dropdown-open?
           pro-tab-label
           pro-dropdown-options
           order-type-label]}]
  [:div {:class ["relative"]}
   (when pro-dropdown-open?
     [:div {:class ["fixed" "inset-0" "z-[180]"]
            :on {:click (cmd/close-pro-order-type-dropdown)}}])
   [:div {:class ["relative" "z-[190]" "flex" "items-center" "border-b" "border-base-300"]}
    (mode-button "Market"
                 (= entry-mode :market)
                 (cmd/select-entry-mode :market))
    (mode-button "Limit"
                 (= entry-mode :limit)
                 (cmd/select-entry-mode :limit))
    [:div {:class ["relative" "flex-1"]}
     [:button {:type "button"
               :class (into ["w-full"
                             "h-10"
                             "text-sm"
                             "font-medium"
                             "border-b-2"
                             "transition-colors"
                             "inline-flex"
                             "items-center"
                             "justify-center"
                             "gap-1.5"]
                            (if (= entry-mode :pro)
                              ["text-gray-100" "border-primary"]
                              ["text-gray-400" "border-transparent" "hover:text-gray-200"]))
               :on {:click (cmd/toggle-pro-order-type-dropdown)
                    :keydown (cmd/handle-pro-order-type-dropdown-keydown [:event/key])}}
      [:span pro-tab-label]
      [:svg {:class (into ["h-3.5" "w-3.5" "transition-transform"]
                          (if pro-dropdown-open?
                            ["rotate-180"]
                            ["rotate-0"]))
             :viewBox "0 0 20 20"
             :fill "currentColor"}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]]
     (when pro-dropdown-open?
       [:div {:class ["absolute"
                      "right-0"
                      "top-full"
                      "mt-1"
                      "w-36"
                      "overflow-hidden"
                      "rounded-lg"
                      "border"
                      "border-base-300"
                      "bg-base-100"
                      "shadow-lg"
                      "z-[210]"]}
        (for [pro-order-type pro-dropdown-options]
          ^{:key (name pro-order-type)}
          [:button {:type "button"
                    :class (into ["block"
                                  "w-full"
                                  "px-3"
                                  "py-2"
                                  "text-left"
                                  "text-sm"
                                  "transition-colors"]
                                 (if (= type pro-order-type)
                                   ["bg-base-200" "text-gray-100"]
                                   ["text-gray-300" "hover:bg-base-200" "hover:text-gray-100"]))
                    :on {:click (cmd/select-pro-order-type pro-order-type)}}
           (order-type-label pro-order-type)])])]]])

(defn metric-row
  ([title value]
   (metric-row title value nil))
  ([title value value-class]
   [:div {:class ["flex" "items-center" "justify-between"]}
    [:span {:class ["text-sm" "text-gray-400"]} title]
    [:span {:class (into ["text-sm" "font-semibold" "num"]
                         (if (seq value-class)
                           [value-class]
                           ["text-gray-100"]))}
     value]]))

(defn tp-sl-panel [form]
  [:div {:class ["space-y-2"]}
   (row-toggle "Enable TP"
               (get-in form [:tp :enabled?])
               (cmd/update-order-form [:tp :enabled?] [:event.target/checked]))
   (when (get-in form [:tp :enabled?])
     [:div {:class ["space-y-2"]}
      (input (get-in form [:tp :trigger])
             (cmd/update-order-form [:tp :trigger] [:event.target/value])
             :placeholder "TP trigger")
      (row-toggle "TP Market"
                  (get-in form [:tp :is-market])
                  (cmd/update-order-form [:tp :is-market] [:event.target/checked]))
      (when (not (get-in form [:tp :is-market]))
        (input (get-in form [:tp :limit])
               (cmd/update-order-form [:tp :limit] [:event.target/value])
               :placeholder "TP limit price"))])
   (row-toggle "Enable SL"
               (get-in form [:sl :enabled?])
               (cmd/update-order-form [:sl :enabled?] [:event.target/checked]))
   (when (get-in form [:sl :enabled?])
     [:div {:class ["space-y-2"]}
      (input (get-in form [:sl :trigger])
             (cmd/update-order-form [:sl :trigger] [:event.target/value])
             :placeholder "SL trigger")
      (row-toggle "SL Market"
                  (get-in form [:sl :is-market])
                  (cmd/update-order-form [:sl :is-market] [:event.target/checked]))
      (when (not (get-in form [:sl :is-market]))
        (input (get-in form [:sl :limit])
               (cmd/update-order-form [:sl :limit] [:event.target/value])
               :placeholder "SL limit price"))])])

(defn quote-accessory [quote-symbol]
  [:div {:class ["flex" "items-center" "gap-1.5" "text-sm" "font-semibold" "text-gray-100"]}
   [:span quote-symbol]
   [:svg {:class ["w-3.5" "h-3.5" "text-gray-400"]
          :viewBox "0 0 20 20"
          :fill "currentColor"}
    [:path {:fill-rule "evenodd"
            :clip-rule "evenodd"
            :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]])

(defn tif-inline-control [form]
  [:div {:class ["relative" "flex" "items-center" "gap-2"]}
   [:span {:class ["text-xs" "uppercase" "tracking-wide" "text-gray-400"]} "TIF"]
   [:select {:class ["appearance-none"
                     "bg-transparent"
                     "text-sm"
                     "font-semibold"
                     "text-gray-100"
                     "outline-none"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:ring-offset-0"
                     "focus:shadow-none"
                     "pr-4"]
             :value (name (:tif form))
             :on {:change (cmd/update-order-form [:tif] [:event.target/value])}}
    [:option {:value "gtc"} "GTC"]
    [:option {:value "ioc"} "IOC"]
    [:option {:value "alo"} "ALO"]]
   [:svg {:class ["pointer-events-none"
                  "absolute"
                  "right-0"
                  "top-1/2"
                  "-translate-y-1/2"
                  "w-3.5"
                  "h-3.5"
                  "text-gray-400"]
          :viewBox "0 0 20 20"
          :fill "currentColor"}
    [:path {:fill-rule "evenodd"
            :clip-rule "evenodd"
            :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]])

(defn render-order-type-section [section form]
  (case section
    :trigger
    [:div
     (section-label "Trigger")
     (input (:trigger-px form)
            (cmd/update-order-form [:trigger-px] [:event.target/value])
            :placeholder "Trigger price")]

    :scale
    [:div {:class ["space-y-2"]}
     (section-label "Scale")
     (input (get-in form [:scale :start])
            (cmd/update-order-form [:scale :start] [:event.target/value])
            :placeholder "Start price")
     (input (get-in form [:scale :end])
            (cmd/update-order-form [:scale :end] [:event.target/value])
            :placeholder "End price")
     [:div {:class ["grid" "grid-cols-2" "gap-2"]}
      (inline-labeled-scale-input "Total Orders"
                                  (get-in form [:scale :count])
                                  (cmd/update-order-form [:scale :count] [:event.target/value]))
      (inline-labeled-scale-input "Size Skew"
                                  (get-in form [:scale :skew])
                                  (cmd/update-order-form [:scale :skew] [:event.target/value]))]]

    :twap
    [:div {:class ["space-y-2"]}
     (section-label "TWAP")
     (input (get-in form [:twap :minutes])
            (cmd/update-order-form [:twap :minutes] [:event.target/value])
            :placeholder "Minutes")
     (row-toggle "Randomize"
                 (get-in form [:twap :randomize])
                 (cmd/update-order-form [:twap :randomize] [:event.target/checked])
                 "trade-toggle-twap-randomize")]

    nil))
