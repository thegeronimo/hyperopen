(ns hyperopen.views.trade.order-form-view
  (:require [clojure.string :as str]))

(defn- label [text]
  [:div.text-xs.text-gray-400.mb-1 text])

(defn- input [value on-change & {:keys [type placeholder]}]
  [:input.w-full.px-3.py-2.bg-base-200.border.border-base-300.rounded-lg.text-sm
   {:type (or type "text")
    :placeholder (or placeholder "")
    :value (or value "")
    :on {:input on-change}}])

(defn- toggle [label-text checked? on-change]
  [:label.flex.items-center.space-x-2.cursor-pointer
   [:input.checkbox.checkbox-sm.checkbox-primary
    {:type "checkbox"
     :checked (boolean checked?)
     :on {:change on-change}}]
   [:span.text-sm label-text]])

(defn- section [title & children]
  [:div
   [:div.text-sm.font-medium.text-gray-200.mb-2 title]
   (into [:div.space-y-2] children)])

(defn- order-type-options []
  [:market :limit :stop-market :stop-limit :take-market :take-limit :scale :twap])

(defn order-form-view [state]
  (let [form (:order-form state)
        active-market (:active-market state)
        active-asset (:active-asset state)
        inferred-spot? (and (string? active-asset) (str/includes? active-asset "/"))
        inferred-hip3? (and (string? active-asset) (str/includes? active-asset ":") (not inferred-spot?))
        spot? (or (= :spot (:market-type active-market)) inferred-spot?)
        hip3? (or (some? (:dex active-market)) inferred-hip3?)
        read-only? (or spot? hip3?)
        side (:side form)
        type (:type form)
        error (:error form)
        submitting? (:submitting? form)]
    [:div {:class ["bg-base-100" "border" "border-base-300" "rounded-none" "shadow-none" "p-3" "space-y-4" "h-full"]}
     (when spot?
       [:div.px-3.py-2.bg-base-200.border.border-base-300.rounded-lg.text-xs.text-gray-300
        "Spot trading is not supported yet. You can still view spot charts and order books."])
     (when hip3?
       [:div.px-3.py-2.bg-base-200.border.border-base-300.rounded-lg.text-xs.text-gray-300
        "HIP-3 trading is not supported yet. You can still view these markets."])

     [:div {:class (when read-only? ["opacity-60" "pointer-events-none"])}
      [:div.flex.items-center.justify-between
       [:div.text-lg.font-semibold "Order"]
       [:div.flex.items-center.space-x-2
        [:button.btn.btn-xs
         {:class (if (= side :buy) "btn-success" "btn-ghost")
          :on {:click [[:actions/update-order-form [:side] :buy]]}}
         "Buy"]
        [:button.btn.btn-xs
         {:class (if (= side :sell) "btn-error" "btn-ghost")
          :on {:click [[:actions/update-order-form [:side] :sell]]}}
         "Sell"]]]

      (section "Order Type"
               [:div
                [:select.w-full.px-3.py-2.bg-base-200.border.border-base-300.rounded-lg.text-sm
                 {:value (name type)
                  :on {:change [[:actions/update-order-form [:type] [:event.target/value]]]} }
                 (for [opt (order-type-options)]
                   ^{:key (name opt)}
                   [:option {:value (name opt)} (str/upper-case (name opt))])]])

      (section "Size"
               (input (:size form) [[:actions/update-order-form [:size] [:event.target/value]]]
                      :placeholder "Size"))

      (when (#{:limit :stop-limit :take-limit} type)
        (section "Price"
                 (input (:price form) [[:actions/update-order-form [:price] [:event.target/value]]]
                        :placeholder "Limit price")))

      (when (#{:stop-market :stop-limit :take-market :take-limit} type)
        (section "Trigger"
                 (input (:trigger-px form) [[:actions/update-order-form [:trigger-px] [:event.target/value]]]
                        :placeholder "Trigger price")))

      (when (= :market type)
        (section "Slippage"
                 (input (:slippage form) [[:actions/update-order-form [:slippage] [:event.target/value]]]
                        :placeholder "0.5")))

      (when (= :scale type)
        (section "Scale"
                 (input (get-in form [:scale :start]) [[:actions/update-order-form [:scale :start] [:event.target/value]]]
                        :placeholder "Start price")
                 (input (get-in form [:scale :end]) [[:actions/update-order-form [:scale :end] [:event.target/value]]]
                        :placeholder "End price")
                 (input (get-in form [:scale :count]) [[:actions/update-order-form [:scale :count] [:event.target/value]]]
                        :placeholder "Order count")))

      (when (= :twap type)
        (section "TWAP"
                 (input (get-in form [:twap :minutes]) [[:actions/update-order-form [:twap :minutes] [:event.target/value]]]
                        :placeholder "Minutes")
                 (toggle "Randomize" (get-in form [:twap :randomize])
                         [[:actions/update-order-form [:twap :randomize] [:event.target/checked]]])))

      (section "Options"
               (toggle "Reduce Only" (:reduce-only form)
                       [[:actions/update-order-form [:reduce-only] [:event.target/checked]]])
               (toggle "Post Only" (:post-only form)
                       [[:actions/update-order-form [:post-only] [:event.target/checked]]])
               [:div
                (label "Time In Force")
                [:select.w-full.px-3.py-2.bg-base-200.border.border-base-300.rounded-lg.text-sm
                 {:value (name (:tif form))
                  :on {:change [[:actions/update-order-form [:tif] [:event.target/value]]]} }
                 [:option {:value "gtc"} "GTC"]
                 [:option {:value "ioc"} "IOC"]
                 [:option {:value "alo"} "ALO"]]])

      (section "TP/SL"
               (toggle "Enable TP" (get-in form [:tp :enabled?])
                       [[:actions/update-order-form [:tp :enabled?] [:event.target/checked]]])
               (when (get-in form [:tp :enabled?])
                 [:div.space-y-2
                  (input (get-in form [:tp :trigger]) [[:actions/update-order-form [:tp :trigger] [:event.target/value]]]
                         :placeholder "TP trigger")
                  (toggle "TP Market" (get-in form [:tp :is-market])
                          [[:actions/update-order-form [:tp :is-market] [:event.target/checked]]])
                  (when (not (get-in form [:tp :is-market]))
                    (input (get-in form [:tp :limit]) [[:actions/update-order-form [:tp :limit] [:event.target/value]]]
                           :placeholder "TP limit price"))])
               (toggle "Enable SL" (get-in form [:sl :enabled?])
                       [[:actions/update-order-form [:sl :enabled?] [:event.target/checked]]])
               (when (get-in form [:sl :enabled?])
                 [:div.space-y-2
                  (input (get-in form [:sl :trigger]) [[:actions/update-order-form [:sl :trigger] [:event.target/value]]]
                         :placeholder "SL trigger")
                  (toggle "SL Market" (get-in form [:sl :is-market])
                          [[:actions/update-order-form [:sl :is-market] [:event.target/checked]]])
                  (when (not (get-in form [:sl :is-market]))
                    (input (get-in form [:sl :limit]) [[:actions/update-order-form [:sl :limit] [:event.target/value]]]
                           :placeholder "SL limit price"))]) )]

     (when error
       [:div.text-sm.text-red-400 error])

     [:button.btn.btn-primary.w-full
      {:disabled (or submitting? read-only?)
       :on {:click [[:actions/submit-order]]}}
      (if submitting? "Submitting..." "Submit Order")]]))
