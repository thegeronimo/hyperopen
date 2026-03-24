(ns hyperopen.views.funding-modal.shared
  (:require [clojure.string :as str]
            [hyperopen.views.asset-icon :as asset-icon]))

(defn base-button-classes
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

(defn submit-button-classes
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

(defn funding-asset-icon
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

(defn asset-icon-src
  [asset]
  (asset-icon/market-icon-url {:coin (:symbol asset)
                               :symbol (:symbol asset)}))

(defn format-grouped-amount-input
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

(defn summary-row
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

(defn lifecycle-panel
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

(defn action-row
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

(defn amount-input-field
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
             :inputmode "decimal"
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

(defn withdraw-amount-input-field
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
             :inputmode "decimal"
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

(defn deposit-asset-card
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
