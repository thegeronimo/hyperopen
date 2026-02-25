(ns hyperopen.views.trade.order-form-component-primitives
  (:require [clojure.string :as str]))

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

(def compact-container-focus-classes
  ["transition-[border-color,box-shadow]"
   "duration-150"
   "hover:border-[#6f7a88]"
   "hover:ring-1"
   "hover:ring-[#6f7a88]/30"
   "hover:ring-offset-0"
   "focus-within:ring-1"
   "focus-within:ring-[#8a96a6]/40"
   "focus-within:ring-offset-0"
   "focus-within:shadow-none"
   "focus-within:border-[#8a96a6]"])

(defn- dom-event-attr [event-id]
  (keyword (str "on-" (name event-id))))

(defn- bind-event
  "Bind either runtime DSL event payloads or plain callback functions.
   - vector/keyword payloads use {:on {event-id payload}}
   - functions use the native DOM attribute (:on-click, :on-input, etc)
   - maps are merged into :on directly"
  [attrs event-id handler]
  (cond
    (nil? handler)
    attrs

    (map? handler)
    (update attrs :on (fnil merge {}) handler)

    (fn? handler)
    (assoc attrs (dom-event-attr event-id) handler)

    :else
    (update attrs :on (fnil merge {}) {event-id handler})))

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
      [:input (bind-event
               {:id checkbox-id
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
                :checked (boolean checked?)}
               :change
               on-change)]
      [:label {:for checkbox-id
               :class ["cursor-pointer" "select-none"]}
       label-text]])))

(defn input [value on-change & {:keys [type placeholder]}]
  [:input (bind-event
           {:class (into ["w-full"
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
            :value (or value "")}
           :input
           on-change)])

(defn row-input [value placeholder on-change accessory & {:keys [input-padding-right
                                                                 input-padding-left
                                                                 label-max-width
                                                                 on-focus
                                                                 on-blur
                                                                 disabled?
                                                                 inputmode]
                                                          :or {input-padding-right "pr-20"
                                                               input-padding-left "pl-24"
                                                               label-max-width "max-w-[52%]"}}]
  (let [base-classes (into ["w-full"
                            "h-11"
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
                           (concat [input-padding-left]
                                   neutral-input-focus-classes
                                   (if accessory [input-padding-right] ["pr-3"]))
                           )
        input-classes (cond-> base-classes
                        disabled? (into ["cursor-not-allowed"
                                         "opacity-60"]))
        input-attrs (-> (cond-> {:class input-classes
                                 :type "text"
                                 :aria-label placeholder
                                 :placeholder placeholder
                                 :value (or value "")
                                 :disabled (boolean disabled?)}
                          inputmode (assoc :inputmode inputmode))
                        (bind-event :input on-change)
                        (bind-event :focus on-focus)
                        (bind-event :blur on-blur))]
    [:div {:class ["relative" "w-full"]}
     [:span {:class ["order-row-input-label"
                     "pointer-events-none"
                     "absolute"
                     "left-3"
                     "top-1/2"
                     "-translate-y-1/2"
                     label-max-width
                     "truncate"
                     "text-sm"
                     "text-gray-500"]}
      placeholder]
     [:input input-attrs]
     (when accessory
       [:div {:class ["absolute"
                      "right-3"
                      "inset-y-0"
                      "flex"
                      "items-center"
                      "shrink-0"]}
       accessory])]))

(defn compact-row-input [value label on-change accessory & {:keys [on-focus
                                                                   on-blur
                                                                   disabled?
                                                                   inputmode
                                                                   short-label
                                                                   overflow-visible?]}]
  (let [display-label (if (and short-label
                               (seq (str/trim (str (or value "")))))
                        short-label
                        label)
        container-classes (cond-> (into ["flex"
                                         "h-[33px]"
                                         "w-full"
                                         "items-center"
                                         "gap-1.5"
                                         "rounded-lg"
                                         "border"
                                         "border-base-300"
                                         "bg-transparent"
                                         (if overflow-visible?
                                           "overflow-visible"
                                           "overflow-hidden")
                                         "py-[5px]"
                                         "pr-3"
                                         "pl-2.5"]
                                        compact-container-focus-classes)
                           disabled? (into ["opacity-60"]))
        input-attrs (-> (cond-> {:class (into ["min-w-0"
                                               "w-full"
                                               "flex-1"
                                               "h-[31px]"
                                               "leading-[31px]"
                                               "m-0"
                                               "p-0"
                                               "border-0"
                                               "bg-transparent"
                                               "text-right"
                                               "text-xs"
                                               "font-normal"
                                               "text-gray-100"
                                               "num"
                                               "appearance-none"
                                               "outline-none"
                                               "focus:outline-none"
                                               "focus:border-0"
                                               "focus:ring-0"
                                               "focus:ring-offset-0"
                                               "focus:shadow-none"]
                                              (if disabled?
                                                ["cursor-not-allowed"]
                                                ["cursor-default"]))
                                 :type "text"
                                 :aria-label label
                                 :placeholder ""
                                 :value (or value "")
                                 :disabled (boolean disabled?)}
                          inputmode (assoc :inputmode inputmode))
                        (bind-event :input on-change)
                        (bind-event :focus on-focus)
                        (bind-event :blur on-blur))]
    [:div {:class container-classes}
     [:span {:class ["shrink-0"
                     "whitespace-nowrap"
                     "text-xs"
                     "text-gray-500"]}
      display-label]
     [:input input-attrs]
     (when accessory
       [:div {:class ["flex" "shrink-0" "items-center"]}
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
   [:input (bind-event
            {:class (into ["w-full"
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
             :value (or value "")}
            :input
            on-change)]])

(defn chip-button [label active? & {:keys [on-click disabled?]}]
  [:button (cond-> {:type "button"
                    :disabled (boolean disabled?)
             :class (into ["flex-1"
                                  "h-10"
                                  "rounded-lg"
                                  "text-sm"
                                  "font-semibold"
                                  "transition-colors"]
                                 (if active?
                                   ["bg-base-200" "text-gray-100" "border" "border-base-300"]
                                   ["bg-base-200/60" "text-gray-300" "border" "border-base-300/80"]))}
             (and on-click (not disabled?))
             (bind-event :click on-click))
   label])

(defn mode-button [label active? on-click]
  [:button (bind-event
            {:type "button"
             :class (into ["flex-1"
                           "h-10"
                           "text-sm"
                           "font-medium"
                           "border-b-2"
                           "transition-colors"]
                          (if active?
                            ["text-gray-100" "border-primary"]
                            ["text-gray-400" "border-transparent" "hover:text-gray-200"]))}
            :click
            on-click)
   label])

(defn side-button [label side active? on-click]
  (let [active-classes (case side
                         :buy ["bg-[#50D2C1]" "text-[#0F1A1F]"]
                         :sell ["bg-[#ED7088]" "text-[#F6FEFD]"]
                         ["bg-primary" "text-primary-content"])]
    [:button (bind-event
              {:type "button"
               :class (into ["flex-1"
                             "h-10"
                             "text-sm"
                             "font-semibold"
                             "rounded-md"
                             "transition-colors"]
                            (if active?
                              active-classes
                              ["bg-[#273035]" "text-[#F6FEFD]"]))}
              :click
              on-click)
     label]))

(defn- metric-label [title]
  [:span {:class ["text-sm" "text-gray-400"]} title])

(defn- metric-label-with-tooltip [title tooltip]
  [:div {:class ["group" "relative" "inline-flex" "items-center"]
         :tabindex 0}
   [:span {:class ["text-sm"
                   "text-gray-400"
                   "underline"
                   "decoration-dashed"
                   "underline-offset-2"]}
    title]
   [:div {:class ["pointer-events-none"
                  "absolute"
                  "right-0"
                  "bottom-full"
                  "mb-1"
                  "z-[100]"
                  "w-[400px]"
                  "max-w-[calc(100vw-20px)]"
                  "opacity-0"
                  "transition-opacity"
                  "duration-150"
                  "group-hover:opacity-100"
                  "group-focus:opacity-100"]}
    [:div {:class ["relative"
                   "rounded-[5px]"
                   "bg-[rgb(39,48,53)]"
                   "px-[10px]"
                   "py-[6px]"
                   "text-left"
                   "font-normal"
                   "leading-[1.35]"
                   "text-white"]
           :style {:font-size "11px"}}
     tooltip
     [:div {:class ["absolute"
                    "left-1/2"
                    "-translate-x-1/2"
                    "top-full"
                    "h-0"
                    "w-0"
                    "border-x-4"
                    "border-x-transparent"
                    "border-t-4"
                    "border-t-[rgb(39,48,53)]"]}]]]])

(defn metric-row
  ([title value]
   (metric-row title value nil nil))
  ([title value value-class]
   (metric-row title value value-class nil))
  ([title value value-class label-tooltip]
   [:div {:class ["flex" "items-center" "justify-between"]}
    (if (seq label-tooltip)
      (metric-label-with-tooltip title label-tooltip)
      (metric-label title))
    [:span {:class (into ["text-sm" "font-semibold" "num"]
                         (if (seq value-class)
                           [value-class]
                           ["text-gray-100"]))}
     value]]))

(defn quote-accessory [quote-symbol]
  [:div {:class ["flex" "items-center" "gap-1.5" "text-sm" "font-semibold" "text-gray-100"]}
   [:span quote-symbol]
   [:svg {:class ["w-3.5" "h-3.5" "text-gray-400"]
          :viewBox "0 0 20 20"
          :fill "currentColor"}
    [:path {:fill-rule "evenodd"
            :clip-rule "evenodd"
            :d "M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z"}]]])
