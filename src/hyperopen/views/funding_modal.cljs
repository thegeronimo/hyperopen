(ns hyperopen.views.funding-modal
  (:require [clojure.string :as str]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.ui.anchored-popover :as anchored-popover]))

(def ^:private preferred-panel-width-px
  448)

(def ^:private estimated-panel-height-px
  560)

(def ^:private popover-divider-gap-px
  10)

(def ^:private trade-order-entry-panel-max-width-px
  320)

(def ^:private trade-order-entry-panel-selector
  "[data-parity-id='trade-order-entry-panel']")

(def ^:private fallback-viewport-width
  1280)

(def ^:private fallback-viewport-height
  800)

(def ^:private mobile-sheet-breakpoint-px
  640)

(def ^:private mobile-sheet-top-offset-px
  20)

(defn- base-button-classes
  [primary?]
  (if primary?
    ["rounded-lg"
     "border"
     "border-[#2f625a]"
     "bg-[#0d3a35]"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-[#daf3ef]"
     "hover:border-[#3f7f75]"
     "hover:bg-[#115046]"]
    ["rounded-lg"
     "border"
     "border-[#2c4b50]"
     "bg-transparent"
     "px-3.5"
     "py-2"
     "text-sm"
     "text-[#b7c8cc]"
     "hover:border-[#3d666b]"
     "hover:text-[#e5eef1]"]))

(defn- submit-button-classes
  [disabled?]
  (if disabled?
    ["rounded-lg"
     "border"
     "border-[#2a4b4b]"
     "bg-[#08202a]/55"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-[#6c8e93]"
     "cursor-not-allowed"]
    ["rounded-lg"
     "border"
     "border-primary/40"
     "bg-primary"
     "px-3.5"
     "py-2"
     "text-sm"
     "font-medium"
     "text-primary-content"
     "hover:bg-primary/90"]))

(defn- funding-asset-icon
  [symbol icon-src]
  [:div {:class ["h-8" "w-8" "shrink-0" "overflow-hidden" "rounded-full"]}
   (if (seq icon-src)
     [:img {:class ["block" "h-8" "w-8" "rounded-full" "object-cover"]
            :src icon-src
            :alt (str symbol " icon")}]
     [:div {:class ["flex"
                    "h-8"
                    "w-8"
                    "items-center"
                    "justify-center"
                    "rounded-full"
                    "bg-[#1e5a93]"
                    "text-xs"
                    "font-semibold"
                    "text-white"]}
      symbol])])

(defn- asset-icon-src
  [asset]
  (asset-icon/market-icon-url {:coin (:symbol asset)
                               :symbol (:symbol asset)}))

(defn- format-grouped-amount-input
  [value]
  (let [raw (-> (or value "")
                str
                (str/replace #"," "")
                (str/replace #"\s+" ""))]
    (if-not (re-matches #"^\d*(?:\.\d*)?$" raw)
      raw
      (let [[whole fraction] (str/split raw #"\." 2)
            has-decimal? (str/includes? raw ".")
            grouped-whole (if (seq whole)
                            (str/replace whole #"\B(?=(\d{3})+(?!\d))" ",")
                            "")]
        (cond
          (empty? raw)
          ""

          (and (empty? whole) has-decimal?)
          (str "0." (or fraction ""))

          has-decimal?
          (str (if (seq grouped-whole) grouped-whole "0")
               "."
               (or fraction ""))

          :else
          grouped-whole)))))

(defn- mode->fallback-anchor-selector
  [mode]
  (case mode
    :deposit "[data-role='funding-action-deposit']"
    :transfer "[data-role='funding-action-transfer']"
    :withdraw "[data-role='funding-action-withdraw']"
    nil))

(defn- anchor-number
  [anchor k default]
  (let [value (get anchor k)]
    (if (number? value)
      value
      default)))

(defn- modal-viewport-width
  [anchor]
  (max 320
       (or (when (number? (:viewport-width anchor))
             (:viewport-width anchor))
           (some-> js/globalThis .-innerWidth)
           (when (number? (:right anchor))
             (+ (:right anchor) 16))
           fallback-viewport-width)))

(defn- modal-viewport-height
  [anchor]
  (max 320
       (or (when (number? (:viewport-height anchor))
             (:viewport-height anchor))
           (some-> js/globalThis .-innerHeight)
           fallback-viewport-height)))

(defn- mobile-sheet?
  [modal]
  (and (= :send (:mode modal))
       (<= (modal-viewport-width (or (:anchor modal) {}))
           mobile-sheet-breakpoint-px)))

(defn- mobile-sheet-style
  [modal]
  (let [max-height (max 320
                        (- (modal-viewport-height (or (:anchor modal) {}))
                           mobile-sheet-top-offset-px))]
    {:max-height (str max-height "px")
     :padding-bottom "max(env(safe-area-inset-bottom), 1rem)"}))

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

(defn- trade-order-entry-divider-left
  []
  (let [panel-bounds (element-anchor-bounds trade-order-entry-panel-selector)]
    (when (and (number? (:left panel-bounds))
               (number? (:width panel-bounds))
               (<= (:width panel-bounds) trade-order-entry-panel-max-width-px))
      (:left panel-bounds))))

(defn- align-anchor-to-trade-order-entry-divider
  [anchor]
  (if-let [divider-left (and (anchored-popover/complete-anchor? anchor)
                             (trade-order-entry-divider-left))]
    (let [divider-anchor (+ divider-left popover-divider-gap-px)]
      (assoc anchor
             :left divider-anchor
             :right divider-anchor))
    anchor))

(defn- lifecycle-tx-hash-content
  [{:keys [hash explorer-url]}]
  (if (seq explorer-url)
    [:a {:href explorer-url
         :target "_blank"
         :rel "noreferrer noopener"
         :class ["font-mono"
                 "text-xs"
                 "break-all"
                 "text-[#70e9e1]"
                 "underline"
                 "decoration-[#3d8f8a]"
                 "hover:text-[#9df5ef]"]}
     hash]
    [:p {:class ["break-all" "font-mono" "text-xs" "text-[#dce9ee]"]}
     hash]))

(defn- summary-row
  [{:keys [label value]}]
  [:div {:class ["flex" "items-center" "justify-between"]}
   [:span {:class ["text-[#7d94a0]"]} label]
   [:span {:class ["text-[#dce9ee]"]} value]])

(defn- lifecycle-stage-row
  [panel]
  [:div {:class ["flex" "items-center" "justify-between"]}
   [:span {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-[#7d94a0]"]}
    "Lifecycle Stage"]
   [:span {:class ["text-xs" "font-medium" "text-[#dce9ee]"]}
    (:stage-label panel)]])

(defn- outcome-tone-class
  [tone]
  (if (= tone :failure)
    "text-[#f2b8c5]"
    "text-[#7af2d7]"))

(defn- lifecycle-outcome-row
  [panel]
  (when-let [{:keys [label tone]} (:outcome panel)]
    [:div {:class ["flex" "items-center" "justify-between"]}
     [:span {:class ["text-[#7d94a0]"]} "Outcome"]
     [:span {:class ["text-xs"
                     "font-medium"
                     (outcome-tone-class tone)]}
      label]]))

(defn- lifecycle-detail-rows
  [panel]
  (remove nil?
          [(when (number? (:source-confirmations panel))
             (summary-row {:label "Source confirmations"
                           :value (str (:source-confirmations panel))}))
           (when (number? (:destination-confirmations panel))
             (summary-row {:label "Destination confirmations"
                           :value (str (:destination-confirmations panel))}))
           (when (number? (:queue-position panel))
             (summary-row {:label "Queue position"
                           :value (str (:queue-position panel))}))]))

(defn- lifecycle-destination-tx-block
  [panel]
  (when-let [destination-tx (:destination-tx panel)]
    [:div {:class ["space-y-1"]}
     [:p {:class ["text-[#7d94a0]"]} "Destination tx hash"]
     (lifecycle-tx-hash-content destination-tx)]))

(defn- lifecycle-next-check-row
  [panel]
  (when (seq (:next-check-label panel))
    (summary-row {:label "Next check"
                  :value (:next-check-label panel)})))

(defn- notice-block
  [classes message]
  [:div {:class classes}
   message])

(defn- lifecycle-notice-blocks
  [panel]
  (remove nil?
          [(when (seq (:error panel))
             (notice-block ["rounded-md"
                            "border"
                            "border-[#7b3340]"
                            "bg-[#3a1b22]/55"
                            "px-2.5"
                            "py-1.5"
                            "text-xs"
                            "text-[#f2b8c5]"]
                           (:error panel)))
           (when (seq (:recovery-hint panel))
             (notice-block ["rounded-md"
                            "border"
                            "border-[#775331]"
                            "bg-[#322515]/55"
                            "px-2.5"
                            "py-1.5"
                            "text-xs"
                            "text-[#f7d8af]"]
                           (:recovery-hint panel)))]))

(defn- lifecycle-panel
  [panel data-role]
  (when (map? panel)
    (into
     [:div {:class ["rounded-lg"
                    "border"
                    "border-[#24485b]"
                    "bg-[#0c1f2c]"
                    "px-3"
                    "py-2.5"
                    "space-y-1.5"]
            :data-role data-role}
      (lifecycle-stage-row panel)
      (summary-row {:label "Status"
                    :value (:status-label panel)})]
     (concat
      (when-let [row (lifecycle-outcome-row panel)]
        [row])
      (lifecycle-detail-rows panel)
      (when-let [block (lifecycle-destination-tx-block panel)]
        [block])
      (when-let [row (lifecycle-next-check-row panel)]
        [row])
      (lifecycle-notice-blocks panel)))))

(defn- action-row
  [{:keys [back-action
           cancel-action
           cancel-label
           submit-action
           submit-label
           submit-disabled?]}]
  (cond
    back-action
    [:div {:class ["grid" "grid-cols-[56px_1fr]" "gap-2" "pt-1"]}
     [:button {:type "button"
               :class ["h-[38px]"
                       "rounded-lg"
                       "border"
                       "border-[#355061]"
                       "bg-[#152633]"
                       "text-lg"
                       "text-[#cfe0e7]"
                       "hover:border-[#4b6c82]"
                       "hover:bg-[#1b3242]"]
               :on {:click [[back-action]]}}
      "←"]
     [:button {:type "button"
               :disabled submit-disabled?
               :class (submit-button-classes submit-disabled?)
               :on {:click [[submit-action]]}}
      submit-label]]

    :else
    [:div {:class ["flex" "justify-end" "gap-2"]}
     [:button {:type "button"
               :class (base-button-classes false)
               :on {:click [[cancel-action]]}}
      cancel-label]
     [:button {:type "button"
               :disabled submit-disabled?
               :class (submit-button-classes submit-disabled?)
               :on {:click [[submit-action]]}}
      submit-label]]))

(defn- amount-input-field
  [{:keys [label
           value
           placeholder
           disabled?
           input-action
           input-args
           max-action
           max-label
           suffix
           data-role]}]
  [:div {:class ["space-y-2"]}
   [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
    [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
     label]
    (when (and max-action (seq max-label))
      [:button {:type "button"
                :disabled disabled?
                :class (if disabled?
                         ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#6f868c]" "cursor-not-allowed"]
                         ["text-xs" "font-medium" "tracking-[0.03em]" "text-[#5de6da]" "hover:text-[#8bf3ea]"])
                :on {:click [[max-action]]}}
       max-label])]
   [:div {:class ["flex"
                  "items-center"
                  "rounded-lg"
                  "border"
                  "border-[#28474b]"
                  "bg-[#0c2028]"
                  "px-3"
                  "py-2"
                  "gap-2"]}
    [:input {:type "text"
             :input-mode "decimal"
             :placeholder placeholder
             :disabled disabled?
             :value (format-grouped-amount-input value)
             :class ["flex-1"
                     "bg-transparent"
                     "border-0"
                     "ring-0"
                     "text-sm"
                     "text-[#e6eff2]"
                     "outline-none"
                     "focus:border-0"
                     "focus:ring-0"
                     "disabled:cursor-not-allowed"
                     "disabled:opacity-70"]
             :data-role data-role
             :on {:input [(vec (concat [input-action]
                                       (or input-args [])
                                       [[:event.target/value]]))]}}]
    (when (seq suffix)
      [:span {:class ["text-sm" "text-[#7e95a0]"]} suffix])]])

(defn- withdraw-amount-input-field
  [{:keys [value
           placeholder
           disabled?
           input-action
           max-action
           suffix
           data-role]}]
  [:div {:class ["space-y-2"]}
   [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
    "Amount"]
   [:div {:class ["flex"
                  "items-center"
                  "rounded-lg"
                  "border"
                  "border-[#28474b]"
                  "bg-[#0c2028]"
                  "px-2.5"
                  "py-1.5"
                  "gap-2"]}
    [:button {:type "button"
              :disabled disabled?
              :class (if disabled?
                       ["rounded-md"
                        "border"
                        "border-[#445565]"
                        "bg-[#1d2933]"
                        "px-2"
                        "py-0.5"
                        "text-xs"
                        "font-semibold"
                        "text-[#6f868c]"
                        "cursor-not-allowed"]
                       ["rounded-md"
                        "border"
                        "border-[#445565]"
                        "bg-[#26313d]"
                        "px-2"
                        "py-0.5"
                        "text-xs"
                        "font-semibold"
                        "text-[#e6eef2]"
                        "hover:border-[#607487]"])
              :on {:click [[max-action]]}}
     "MAX"]
    [:input {:type "text"
             :input-mode "decimal"
             :placeholder placeholder
             :disabled disabled?
             :value (format-grouped-amount-input value)
             :class ["flex-1"
                     "bg-transparent"
                     "border-0"
                     "ring-0"
                     "text-right"
                     "text-sm"
                     "text-[#e6eff2]"
                     "outline-none"
                     "focus:border-0"
                     "focus:ring-0"
                     "disabled:cursor-not-allowed"
                     "disabled:opacity-70"]
             :data-role data-role
             :on {:input [[input-action [:event.target/value]]]}}]
    [:span {:class ["text-sm" "text-[#7e95a0]"]} suffix]]])

(defn- deposit-asset-button
  [asset selected?]
  [:button {:type "button"
            :class (into ["w-full"
                          "rounded-lg"
                          "border"
                          "px-3"
                          "py-2.5"
                          "text-left"
                          "transition-colors"
                          "duration-150"]
                         (if selected?
                           ["border-[#3a5b6a]" "bg-[#1a2a37]"]
                           ["border-transparent" "bg-transparent" "hover:bg-[#10222f]"]))
            :on {:click [[:actions/select-funding-deposit-asset
                          (:key asset)]]}}
   [:div {:class ["flex" "items-center" "gap-2.5"]}
    (funding-asset-icon (:symbol asset) (asset-icon-src asset))
    [:div {:class ["flex" "min-w-0" "flex-col"]}
     [:span {:class ["text-sm" "font-semibold" "text-[#e6eef2]"]} (:symbol asset)]
       [:span {:class ["text-xs" "text-[#7e95a0]"]} (:network asset)]]]])

(defn- withdraw-asset-button
  [asset selected?]
  [:button {:type "button"
            :class (into ["w-full"
                          "rounded-lg"
                          "border"
                          "px-3"
                          "py-2.5"
                          "text-left"
                          "transition-colors"
                          "duration-150"]
                         (if selected?
                           ["border-[#3a5b6a]" "bg-[#1a2a37]"]
                           ["border-transparent" "bg-transparent" "hover:bg-[#10222f]"]))
            :on {:click [[:actions/select-funding-withdraw-asset
                          (:key asset)]]}}
   [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
    [:div {:class ["flex" "items-center" "gap-2.5"]}
     (funding-asset-icon (:symbol asset) (asset-icon-src asset))
     [:div {:class ["flex" "min-w-0" "flex-col"]}
      [:span {:class ["text-sm" "font-semibold" "text-[#e6eef2]"]} (:symbol asset)]
      [:span {:class ["text-xs" "text-[#7e95a0]"]} (:network asset)]]]
    [:span {:class ["shrink-0" "text-sm" "font-medium" "text-[#dce9ee]"]}
     (:available-display asset)]]])

(defn- deposit-asset-card
  [asset]
  [:div {:class ["rounded-lg"
                 "border"
                 "border-[#1f3f4f]"
                 "bg-[#1a2633]"
                 "px-3"
                 "py-3"]}
   [:div {:class ["flex" "items-center" "gap-2.5"]}
    (funding-asset-icon (:symbol asset) (asset-icon-src asset))
    [:div {:class ["flex" "flex-col"]}
     [:span {:class ["text-sm" "font-semibold" "text-[#e6eef2]"]} (:symbol asset)]
     [:span {:class ["text-xs" "text-[#7e95a0]"]} (:network asset)]]]])

(defn- deposit-select-content
  [{:keys [search assets selected-asset]}]
  [:div {:class ["space-y-3"] :data-role "funding-deposit-select-step"}
   [:p {:class ["text-sm" "leading-6" "text-[#8fa7ae]"]}
    "Deposit funds to start trading immediately. You can withdraw at any time."]
   [:input {:type "text"
            :placeholder (:placeholder search)
            :value (:value search)
            :class ["w-full"
                    "rounded-lg"
                    "border"
                    "border-[#1f3f4f]"
                    "bg-[#0c202a]"
                    "px-3"
                    "py-2.5"
                    "text-sm"
                    "text-[#dce9ee]"
                    "outline-none"
                    "focus:border-[#30607a]"]
            :on {:input [[:actions/search-funding-deposit-assets
                          [:event.target/value]]]}}]
   (if (seq assets)
     (into
      [:div {:class ["max-h-[264px]"
                     "overflow-y-auto"
                     "rounded-xl"
                     "border"
                     "border-[#1c3948]"
                     "bg-[#0c1e29]"
                     "p-2"]
             :data-role "funding-deposit-asset-list"}]
      (for [asset assets]
        ^{:key (name (:key asset))}
        (deposit-asset-button asset
                              (= (:key asset)
                                 (:key selected-asset)))))
     [:div {:class ["rounded-xl"
                    "border"
                    "border-[#1c3948]"
                    "bg-[#0c1e29]"
                    "px-3"
                    "py-8"
                    "text-center"
                    "text-sm"
                    "text-[#7b919b]"]}
      "No assets match your search."])])

(defn- deposit-address-content
  [{:keys [selected-asset flow summary lifecycle actions]}]
  [:div {:class ["space-y-3"] :data-role "funding-deposit-amount-step"}
   [:p {:class ["text-sm" "text-[#8ea4ab]"]}
    (str "From the " (:network selected-asset) " network")]
   (deposit-asset-card selected-asset)
   [:div {:class ["space-y-3"]}
    [:div {:class ["rounded-lg"
                   "border"
                   "border-[#24485b]"
                   "bg-[#0f2433]"
                   "px-3"
                   "py-3"
                   "space-y-1.5"]}
     [:p {:class ["text-sm" "text-[#d9e7ed]"]}
      "Generate a deposit address and send funds on the selected network."]
     [:p {:class ["text-xs" "text-[#7f97a0]"]}
      "Only send "
      [:span {:class ["font-semibold" "text-[#c9dce4]"]} (:symbol selected-asset)]
      " from "
      [:span {:class ["font-semibold" "text-[#c9dce4]"]} (:network selected-asset)]
      "."]
     (when (seq (:generated-address flow))
       [:div {:class ["space-y-1.5" "pt-1"]}
        [:p {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-[#7c93a0]"]}
         "Deposit Address"]
        [:div {:class ["rounded-md"
                       "border"
                       "border-[#2c5468]"
                       "bg-[#0a1d29]"
                       "px-2.5"
                       "py-2"
                       "text-xs"
                       "font-mono"
                       "break-all"
                       "text-[#d6e8ee]"]
               :data-role "funding-deposit-generated-address"}
         (:generated-address flow)]
        (when (pos? (:generated-signature-count flow))
          [:p {:class ["text-xs" "text-[#7f97a0]"]}
           (str "Authorization signatures: " (:generated-signature-count flow))])])]
    [:div {:class ["space-y-1.5" "pt-1" "text-sm"]}
     (for [row (:rows summary)]
       ^{:key (:label row)}
       (summary-row row))
     (when (and (= :error (get-in flow [:fee-estimate :state]))
                (seq (get-in flow [:fee-estimate :message])))
       [:p {:class ["text-xs" "text-[#9db2ba]"]}
        (str "Live HyperUnit estimates unavailable: "
             (get-in flow [:fee-estimate :message]))])]
    (lifecycle-panel lifecycle "funding-deposit-lifecycle")
    (action-row {:back-action :actions/return-to-funding-deposit-asset-select
                 :submit-action :actions/submit-funding-deposit
                 :submit-label (get-in actions [:submit-label])
                 :submit-disabled? (get-in actions [:submit-disabled?])})]])

(defn- deposit-amount-content
  [{:keys [selected-asset amount summary actions]}]
  [:div {:class ["space-y-3"] :data-role "funding-deposit-amount-step"}
   [:p {:class ["text-sm" "text-[#8ea4ab]"]}
    (str "From the " (:network selected-asset) " network")]
   (deposit-asset-card selected-asset)
   [:div {:class ["space-y-1.5"]}
    [:label {:class ["block" "text-xs" "text-[#7e94a0]"]}
     "Amount"]
    [:div {:class ["flex"
                   "items-center"
                   "rounded-lg"
                   "border"
                   "border-[#2a4658]"
                   "bg-[#0f2230]"
                   "px-2"
                   "py-1.5"
                   "gap-2"]}
     [:button {:type "button"
               :class ["rounded-md"
                       "border"
                       "border-[#445565]"
                       "bg-[#26313d]"
                       "px-2"
                       "py-0.5"
                       "text-xs"
                       "font-semibold"
                       "text-[#e6eef2]"
                       "hover:border-[#607487]"]
               :on {:click [[:actions/set-funding-deposit-amount-to-minimum]]}}
      "MIN"]
     [:input {:type "text"
              :input-mode "decimal"
              :placeholder (str (:minimum-value amount))
              :disabled (get-in actions [:submitting?])
              :value (format-grouped-amount-input (:value amount))
              :class ["flex-1"
                      "bg-transparent"
                      "border-0"
                      "ring-0"
                      "text-sm"
                      "text-[#e6eef2]"
                      "text-right"
                      "outline-none"
                      "focus:border-0"
                      "focus:ring-0"
                      "disabled:opacity-70"]
              :on {:input [[:actions/enter-funding-deposit-amount
                            [:event.target/value]]]}}]
     [:span {:class ["text-sm" "text-[#7e95a0]"]} (:symbol selected-asset)]]]
   [:div {:class ["flex" "gap-2"]}
    (for [quick-amount (:quick-amounts amount)]
      ^{:key (str "deposit-quick-" quick-amount)}
      [:button {:type "button"
                :class ["rounded-md"
                        "border"
                        "border-[#3a4d5d]"
                        "bg-[#111f29]"
                        "px-3"
                        "py-1.5"
                        "text-xs"
                        "text-[#e0ebef]"
                        "hover:border-[#537089]"
                        "hover:bg-[#162b37]"]
                :on {:click [[:actions/enter-funding-deposit-amount
                              (str quick-amount)]]}}
       (if (>= quick-amount 1000)
         (str (/ quick-amount 1000) "k")
         (str quick-amount))])]
   [:div {:class ["space-y-1.5" "pt-2" "text-sm"]}
    (for [row (:rows summary)]
      ^{:key (:label row)}
      (summary-row row))]
   (action-row {:back-action :actions/return-to-funding-deposit-asset-select
                :submit-action :actions/submit-funding-deposit
                :submit-label (get-in actions [:submit-label])
                :submit-disabled? (get-in actions [:submit-disabled?])})])

(defn- deposit-unavailable-content
  [{:keys [flow actions]}]
  [:div {:class ["space-y-3"]}
   [:div {:class ["rounded-lg"
                  "border"
                  "border-[#264759]"
                  "bg-[#102535]"
                  "px-3"
                  "py-3"
                  "text-sm"
                  "text-[#9bb1b9]"
                  "space-y-1.5"]}
    [:p "This asset's deposit flow is not implemented in Hyperopen yet."]
    [:p {:class ["text-xs" "text-[#7f98a0]"]}
     (:unsupported-detail flow)]]
   (action-row {:back-action :actions/return-to-funding-deposit-asset-select
                :submit-action :actions/submit-funding-deposit
                :submit-label (get-in actions [:submit-label])
                :submit-disabled? true})])

(defn- deposit-missing-asset-content
  [{:keys [actions]}]
  [:div {:class ["space-y-3"]}
   [:div {:class ["rounded-lg"
                  "border"
                  "border-[#7b3340]"
                  "bg-[#3a1b22]/55"
                  "px-3"
                  "py-3"
                  "text-sm"
                  "text-[#f2b8c5]"]}
    "Select an asset before continuing with the deposit flow."]
   (action-row {:back-action :actions/return-to-funding-deposit-asset-select
                :submit-action :actions/submit-funding-deposit
                :submit-label (get-in actions [:submit-label])
                :submit-disabled? true})])

(defn- transfer-content
  [{:keys [to-perp? amount actions]}]
  [:div {:class ["space-y-3"]}
   [:div {:class ["grid" "grid-cols-2" "gap-2"]}
    [:button {:type "button"
              :class (if to-perp?
                       (base-button-classes true)
                       (base-button-classes false))
              :disabled (get-in actions [:submitting?])
              :on {:click [[:actions/set-funding-transfer-direction true]]}}
     "Spot -> Perps"]
    [:button {:type "button"
              :class (if to-perp?
                       (base-button-classes false)
                       (base-button-classes true))
              :disabled (get-in actions [:submitting?])
              :on {:click [[:actions/set-funding-transfer-direction false]]}}
     "Perps -> Spot"]]
   (amount-input-field {:label "Amount (USDC)"
                        :value (:value amount)
                        :placeholder "Enter amount"
                        :disabled? (get-in actions [:submitting?])
                        :input-action :actions/enter-funding-transfer-amount
                        :max-action :actions/set-funding-amount-to-max
                        :max-label (str "MAX: " (:max-display amount) " USDC")
                        :data-role "funding-transfer-amount-input"})
   (action-row {:cancel-action :actions/close-funding-modal
                :cancel-label "Cancel"
                :submit-action :actions/submit-funding-transfer
                :submit-label (get-in actions [:submit-label])
                :submit-disabled? (get-in actions [:submit-disabled?])})])

(defn- send-asset-field
  [{:keys [symbol prefix-label]}]
  [:div {:class ["flex"
                 "items-center"
                 "rounded-lg"
                 "border"
                 "border-[#28474b]"
                 "bg-[#0c2028]"
                 "px-3"
                 "py-3"
                 "gap-2"
                 "min-w-0"]}
   [:span {:class ["truncate" "text-sm" "font-semibold" "text-[#e6eff2]"]}
    (or symbol "Asset")]
   [:div {:class ["flex" "items-center" "gap-2" "min-w-0"]}
    (when (seq prefix-label)
      [:span {:class ["inline-flex"
                      "items-center"
                      "rounded-lg"
                      "bg-[#242924]"
                      "px-3"
                      "py-[1px]"
                      "text-xs"
                      "font-medium"
                      "leading-none"
                      "text-emerald-300"]}
       prefix-label])]])

(defn- send-content
  [{:keys [asset destination amount actions]}]
  [:div {:class ["space-y-4"] :data-role "funding-send-step"}
   [:p {:class ["text-sm" "leading-6" "text-[#8fa7ae]"]}
    "Send tokens to another account on the Hyperliquid L1."]
   [:div {:class ["space-y-2"]}
    [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
     "Destination"]
    [:input {:type "text"
             :placeholder "0x..."
             :disabled (get-in actions [:submitting?])
             :value (:value destination)
             :class ["w-full"
                     "rounded-lg"
                     "border"
                     "border-[#28474b]"
                     "bg-[#0c2028]"
                     "px-3"
                     "py-2.5"
                     "text-sm"
                     "text-[#e6eff2]"
                     "outline-none"
                     "focus:border-[#4f8f87]"
                     "disabled:cursor-not-allowed"
                     "disabled:opacity-70"]
             :data-role "funding-send-destination-input"
             :on {:input [[:actions/set-funding-modal-field
                           [:destination-input]
                           [:event.target/value]]]}}]]
   [:div {:class ["grid" "grid-cols-2" "gap-3"]}
    [:div {:class ["space-y-2"]}
     [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
      "Account"]
     [:div {:class ["flex"
                    "items-center"
                    "justify-between"
                    "rounded-lg"
                    "border"
                    "border-[#28474b]"
                    "bg-[#0c2028]"
                    "px-3"
                    "py-3"]}
      [:span {:class ["text-sm" "font-semibold" "text-[#e6eff2]"]} "Trading Account"]]]
    [:div {:class ["space-y-2"]}
     [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
      "Asset"]
     (send-asset-field asset)]]
   (amount-input-field {:label "Amount"
                        :value (:value amount)
                        :placeholder "Enter amount"
                        :disabled? (get-in actions [:submitting?])
                        :input-action :actions/set-funding-modal-field
                        :input-args [[:amount-input]]
                        :max-action :actions/set-funding-amount-to-max
                        :max-label (when (seq (:max-display amount))
                                     (str "MAX: " (:max-display amount)
                                          (when (seq (:symbol amount))
                                            (str " " (:symbol amount)))))
                        :suffix (:symbol amount)
                        :data-role "funding-send-amount-input"})
   [:button {:type "button"
             :disabled (get-in actions [:submit-disabled?])
             :class (into ["w-full"] (submit-button-classes (get-in actions [:submit-disabled?])))
             :on {:click [[:actions/submit-funding-send]]}}
    (get-in actions [:submit-label])]])

(defn- withdraw-select-content
  [{:keys [search assets selected-asset]}]
  [:div {:class ["space-y-3"] :data-role "funding-withdraw-select-step"}
   [:p {:class ["text-sm" "leading-6" "text-[#8fa7ae]"]}
    "Withdraw funds from your Hyperliquid account. You can deposit at any time."]
   [:input {:type "text"
            :placeholder (:placeholder search)
            :value (:value search)
            :class ["w-full"
                    "rounded-lg"
                    "border"
                    "border-[#1f3f4f]"
                    "bg-[#0c202a]"
                    "px-3"
                    "py-2.5"
                    "text-sm"
                    "text-[#dce9ee]"
                    "outline-none"
                    "focus:border-[#30607a]"]
            :on {:input [[:actions/search-funding-withdraw-assets
                          [:event.target/value]]]}}]
   (if (seq assets)
     (into
      [:div {:class ["max-h-[264px]"
                     "overflow-y-auto"
                     "rounded-xl"
                     "border"
                     "border-[#1c3948]"
                     "bg-[#0c1e29]"
                     "p-2"]
             :data-role "funding-withdraw-asset-list"}]
      (for [asset assets]
        ^{:key (name (:key asset))}
        (withdraw-asset-button asset
                               (= (:key asset)
                                  (:key selected-asset)))))
     [:div {:class ["rounded-xl"
                    "border"
                    "border-[#1c3948]"
                    "bg-[#0c1e29]"
                    "px-3"
                    "py-8"
                    "text-center"
                    "text-sm"
                    "text-[#7b919b]"]}
      "No assets match your search."])])

(defn- withdrawal-queue-copy
  [{:keys [state length]}]
  (case state
    :loading "Loading..."
    :ready (str length)
    :error "Unavailable"
    "N/A"))

(defn- hyperunit-address-flow?
  [flow]
  (= (:kind flow) :hyperunit-address))

(defn- withdraw-asset-selector
  [selected-asset]
  [:div {:class ["space-y-2"]}
   [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
    "Asset"]
   (deposit-asset-card selected-asset)])

(defn- withdraw-destination-label
  [selected-asset flow]
  (if (hyperunit-address-flow? flow)
    (str "Destination Address (" (:network selected-asset) ")")
    "Destination Address"))

(defn- withdraw-destination-placeholder
  [selected-asset flow]
  (if (hyperunit-address-flow? flow)
    (str "Enter " (:symbol selected-asset) " destination")
    "0x..."))

(defn- withdraw-destination-field
  [selected-asset flow destination submitting?]
  [:div {:class ["space-y-2"]}
   [:label {:class ["block" "text-xs" "uppercase" "tracking-[0.08em]" "text-[#8ea4ab]"]}
    (withdraw-destination-label selected-asset flow)]
   [:input {:type "text"
            :placeholder (withdraw-destination-placeholder selected-asset flow)
            :disabled submitting?
            :value (:value destination)
            :class ["w-full"
                    "rounded-lg"
                    "border"
                    "border-[#28474b]"
                    "bg-[#0c2028]"
                    "px-3"
                    "py-2"
                    "text-sm"
                    "text-[#e6eff2]"
                    "outline-none"
                    "focus:border-[#4f8f87]"
                    "disabled:cursor-not-allowed"
                    "disabled:opacity-70"]
            :on {:input [[:actions/enter-funding-withdraw-destination
                          [:event.target/value]]]}}]])

(defn- queue-operation-link-or-text
  [{:keys [tx-id explorer-url]}]
  (if (seq explorer-url)
    [:a {:href explorer-url
         :target "_blank"
         :rel "noreferrer noopener"
         :class ["max-w-[220px]"
                 "truncate"
                 "font-mono"
                 "text-xs"
                 "text-[#70e9e1]"
                 "underline"
                 "decoration-[#3d8f8a]"
                 "hover:text-[#9df5ef]"]}
     tx-id]
    [:span {:class ["max-w-[220px]"
                    "truncate"
                    "font-mono"
                    "text-xs"
                    "text-[#dce9ee]"]}
     tx-id]))

(defn- withdraw-queue-row
  [flow]
  (when (hyperunit-address-flow? flow)
    [:div {:class ["flex" "items-center" "justify-between"]}
     [:span {:class ["text-[#7d94a0]"]} "Withdrawal queue"]
     [:span {:class ["text-[#dce9ee]"]}
      (withdrawal-queue-copy (get-in flow [:withdrawal-queue]))]]))

(defn- withdraw-last-queue-tx-row
  [flow]
  (when-let [tx-id (when (hyperunit-address-flow? flow)
                     (get-in flow [:withdrawal-queue :last-operation :tx-id]))]
    [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
     [:span {:class ["text-[#7d94a0]"]} "Last queue tx"]
     (queue-operation-link-or-text (assoc (get-in flow [:withdrawal-queue :last-operation])
                                          :tx-id tx-id))]))

(defn- withdraw-queue-error-message
  [flow]
  (when (and (hyperunit-address-flow? flow)
             (= :error (get-in flow [:withdrawal-queue :state]))
             (seq (get-in flow [:withdrawal-queue :message])))
    [:p {:class ["text-xs" "text-[#9db2ba]"]}
     (str "Live queue status unavailable: "
          (get-in flow [:withdrawal-queue :message]))]))

(defn- withdraw-fee-estimate-error-message
  [flow]
  (when (and (hyperunit-address-flow? flow)
             (= :error (get-in flow [:fee-estimate :state]))
             (seq (get-in flow [:fee-estimate :message])))
    [:p {:class ["text-xs" "text-[#9db2ba]"]}
     (str "Live HyperUnit estimates unavailable: "
          (get-in flow [:fee-estimate :message]))]))

(defn- withdraw-summary-section
  [summary flow]
  (into
   [:div {:class ["space-y-1.5" "pt-1" "text-sm"]}]
   (concat
    (for [row (:rows summary)]
      ^{:key (:label row)}
      (summary-row row))
    (remove nil?
            [(withdraw-queue-row flow)
             (withdraw-last-queue-tx-row flow)
             (withdraw-queue-error-message flow)
             (withdraw-fee-estimate-error-message flow)]))))

(defn- withdraw-protocol-address-panel
  [flow]
  (when (and (hyperunit-address-flow? flow)
             (seq (:protocol-address flow)))
    [:div {:class ["rounded-lg"
                   "border"
                   "border-[#24485b]"
                   "bg-[#0f2433]"
                   "px-3"
                   "py-2.5"
                   "space-y-1.5"]}
     [:p {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-[#7c93a0]"]}
      "HyperUnit Protocol Address"]
     [:p {:class ["break-all" "font-mono" "text-xs" "text-[#d6e8ee]"]}
      (:protocol-address flow)]]))

(defn- withdraw-detail-content
  [{:keys [selected-asset destination amount flow summary lifecycle actions]}]
  (let [submitting? (get-in actions [:submitting?])]
    [:div {:class ["space-y-3"] :data-role "funding-withdraw-detail-step"}
     [:p {:class ["text-sm" "text-[#8ea4ab]"]}
      (str "To the " (:network selected-asset) " network")]
     (withdraw-asset-selector selected-asset)
     (withdraw-destination-field selected-asset flow destination submitting?)
     (withdraw-amount-input-field {:value (:value amount)
                                   :placeholder "Enter amount"
                                   :disabled? submitting?
                                   :input-action :actions/enter-funding-withdraw-amount
                                   :max-action :actions/set-funding-amount-to-max
                                   :suffix (:symbol amount)
                                   :data-role "funding-withdraw-amount-input"})
     [:div {:class ["flex" "justify-end"]}
      [:p {:class ["text-xs" "text-[#7f97a0]"]}
       (:available-label amount)]]
     (withdraw-summary-section summary flow)
     (withdraw-protocol-address-panel flow)
     (lifecycle-panel lifecycle "funding-withdraw-lifecycle")
     (action-row {:back-action :actions/return-to-funding-withdraw-asset-select
                  :submit-action :actions/submit-funding-withdraw
                  :submit-label (get-in actions [:submit-label])
                  :submit-disabled? (get-in actions [:submit-disabled?])})]))

(defn- legacy-content
  [{:keys [message]}]
  [:div {:class ["space-y-3"]}
   [:p {:class ["text-sm" "text-[#b9cbd0]"]}
    message]
   [:div {:class ["flex" "justify-end"]}
    [:button {:type "button"
              :class (base-button-classes true)
              :on {:click [[:actions/close-funding-modal]]}}
     "Close"]]])

(defn- render-content
  [{:keys [content deposit send transfer withdraw legacy]}]
  (case (:kind content)
    :deposit/select (deposit-select-content deposit)
    :deposit/address (deposit-address-content deposit)
    :deposit/amount (deposit-amount-content deposit)
    :deposit/unavailable (deposit-unavailable-content deposit)
    :deposit/missing-asset (deposit-missing-asset-content deposit)
    :send/form (send-content send)
    :transfer/form (transfer-content transfer)
    :withdraw/select (withdraw-select-content withdraw)
    :withdraw/detail (withdraw-detail-content withdraw)
    :unsupported/workflow (legacy-content legacy)
    nil))

(defn funding-modal-view
  [state]
  (let [{:keys [modal feedback] :as view-model} (funding-actions/funding-modal-view-model state)
        open? (:open? modal)
        stored-anchor* (if (map? (:anchor modal)) (:anchor modal) {})
        mobile-sheet? (mobile-sheet? modal)
        fallback-anchor* (when-not (anchored-popover/complete-anchor? stored-anchor*)
                           (element-anchor-bounds
                            (mode->fallback-anchor-selector (:mode modal))))
        anchor (-> (or fallback-anchor* stored-anchor*)
                   align-anchor-to-trade-order-entry-divider)
        anchored-popover? (and (not mobile-sheet?)
                               (anchored-popover/complete-anchor? anchor))
        popover-style (when anchored-popover?
                        (anchored-popover/anchored-popover-layout-style
                         {:anchor anchor
                          :preferred-width-px preferred-panel-width-px
                          :estimated-height-px estimated-panel-height-px}))
        sheet-style (when mobile-sheet?
                      (mobile-sheet-style (assoc modal :anchor anchor)))
        panel-children
        [[:div {:class ["flex" "items-center" "justify-between"]}
          [:h2 {:class ["text-lg" "font-semibold" "text-[#e5eef1]"]}
           (:title modal)]
          [:button {:type "button"
                    :class (into ["h-8"
                                  "w-8"
                                  "leading-none"
                                  "text-xl"
                                  "transition-colors"
                                  "focus:outline-none"
                                  "focus:ring-1"
                                  "focus:ring-[#66e3c5]/40"
                                  "focus:ring-offset-0"
                                  "focus:shadow-none"]
                                 (if mobile-sheet?
                                   ["inline-flex"
                                    "items-center"
                                    "justify-center"
                                    "rounded-lg"
                                    "border"
                                    "border-[#17313d]"
                                    "bg-[#0b181d]"
                                    "text-gray-300"
                                    "hover:bg-[#102229]"
                                    "hover:text-gray-100"]
                                   ["rounded-md"
                                    "text-[#7f98a0]"
                                    "hover:bg-[#0f2834]"
                                    "hover:text-[#dce9ee]"]))
                    :on {:click [[:actions/close-funding-modal]]}}
           "×"]]
         (render-content view-model)
         (when (:visible? feedback)
           [:div {:class ["rounded-md"
                          "border"
                          "border-[#7b3340]"
                          "bg-[#3a1b22]/55"
                          "px-3"
                          "py-2"
                          "text-sm"
                          "text-[#f2b8c5]"]
                  :data-role "funding-status"}
            (:message feedback)])]]
    (when open?
      (if mobile-sheet?
        [:div {:class ["fixed" "inset-0" "z-[80]"]
               :data-role "funding-mobile-sheet-layer"}
         [:button {:type "button"
                   :class ["absolute" "inset-0" "bg-black/55" "backdrop-blur-[1px]"]
                   :style {:transition "opacity 0.14s ease-out"
                           :opacity 1}
                   :replicant/mounting {:style {:opacity 0}}
                   :replicant/unmounting {:style {:opacity 0}}
                   :aria-label "Close funding dialog"
                   :data-role "funding-mobile-sheet-backdrop"
                   :on {:click [[:actions/close-funding-modal]]}}]
         (into [:div {:class ["absolute"
                              "inset-x-0"
                              "bottom-0"
                              "w-full"
                              "overflow-y-auto"
                              "rounded-t-[22px]"
                              "border"
                              "border-[#17313d]"
                              "bg-[#06131a]"
                              "px-4"
                              "pt-4"
                              "text-sm"
                              "shadow-[0_-24px_60px_rgba(0,0,0,0.45)]"
                              "space-y-3"]
                      :style sheet-style
                      :replicant/mounting {:style {:transform "translateY(18px)"
                                                   :opacity 0}}
                      :replicant/unmounting {:style {:transform "translateY(18px)"
                                                     :opacity 0}}
                      :role "dialog"
                      :aria-modal true
                      :aria-label (:title modal)
                      :tab-index 0
                      :data-role "funding-modal"
                      :data-funding-mobile-sheet-surface "true"
                      :on {:keydown [[:actions/handle-funding-modal-keydown
                                      [:event/key]]]}}]
               (keep identity panel-children))]
        [:div {:class (into ["fixed" "inset-0" "z-[80]"]
                            (if anchored-popover?
                              ["pointer-events-none"]
                              ["flex" "items-center" "justify-center" "p-4"]))}
         [:button {:type "button"
                   :class (into ["absolute" "inset-0"]
                                (if anchored-popover?
                                  ["pointer-events-auto" "bg-transparent"]
                                  ["bg-black/65"]))
                   :aria-label "Close funding dialog"
                   :on {:click [[:actions/close-funding-modal]]}}]
         (into [:div {:class (into ["relative"
                                    "z-[81]"
                                    "space-y-3"
                                    "border"
                                    "border-[#1f3b3c]"
                                    "bg-[#081b24]"
                                    "shadow-2xl"
                                    "pointer-events-auto"]
                                   (cond-> ["rounded-2xl" "p-4"]
                                     (not anchored-popover?)
                                     (conj "w-full" "max-w-md")))
                        :style (or popover-style)
                        :role "dialog"
                        :aria-modal true
                        :aria-label (:title modal)
                        :tab-index 0
                        :data-role "funding-modal"
                        :on {:keydown [[:actions/handle-funding-modal-keydown
                                        [:event/key]]]}}]
               (keep identity panel-children))]))))
