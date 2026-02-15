(ns hyperopen.views.trade.order-form-type-extensions
  (:require [hyperopen.trading.order-type-registry :as order-types]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]))

(def ^:private section-renderers
  {:trigger
   (fn [form {:keys [on-set-trigger-price]}]
     [:div
      (primitives/section-label "Trigger")
      (primitives/input (:trigger-px form)
                        on-set-trigger-price
                        :placeholder "Trigger price")])

   :scale
   (fn [form {:keys [on-set-scale-start
                     on-set-scale-end
                     on-set-scale-count
                     on-set-scale-skew]}]
     [:div {:class ["space-y-2"]}
      (primitives/section-label "Scale")
      (primitives/input (get-in form [:scale :start])
                        on-set-scale-start
                        :placeholder "Start price")
      (primitives/input (get-in form [:scale :end])
                        on-set-scale-end
                        :placeholder "End price")
      [:div {:class ["grid" "grid-cols-2" "gap-2"]}
       (primitives/inline-labeled-scale-input "Total Orders"
                                              (get-in form [:scale :count])
                                              on-set-scale-count)
       (primitives/inline-labeled-scale-input "Size Skew"
                                              (get-in form [:scale :skew])
                                              on-set-scale-skew)]])

   :twap
   (fn [form {:keys [on-set-twap-minutes
                     on-toggle-twap-randomize]}]
     [:div {:class ["space-y-2"]}
      (primitives/section-label "TWAP")
      (primitives/input (get-in form [:twap :minutes])
                        on-set-twap-minutes
                        :placeholder "Minutes")
      (primitives/row-toggle "Randomize"
                             (get-in form [:twap :randomize])
                             on-toggle-twap-randomize
                             "trade-toggle-twap-randomize")])})

(defn supported-order-type-sections []
  (set (keys section-renderers)))

(defn order-type-extension [order-type]
  (let [entry (order-types/order-type-entry order-type)
        section-ids (->> (:sections entry)
                         (filter #(contains? section-renderers %))
                         vec)]
    (assoc entry
           :id order-type
           :label (order-types/order-type-label order-type)
           :sections section-ids)))

(defn render-order-type-sections [order-type form callbacks]
  (let [extension (order-type-extension order-type)]
    (for [section-id (:sections extension)]
      (when-let [renderer (get section-renderers section-id)]
        ^{:key (str "order-type-section-" (name section-id))}
        (renderer form callbacks)))))
