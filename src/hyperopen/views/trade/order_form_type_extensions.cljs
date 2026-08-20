(ns hyperopen.views.trade.order-form-type-extensions
  (:require [hyperopen.trading.order-type-registry :as order-types]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]
            [hyperopen.views.trade.order-form-twap-section :as twap-section]))

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
   (fn [form callbacks]
     (twap-section/twap-section form callbacks))})

(declare ensure-valid-extension-registry!)

(defn supported-order-type-sections []
  (set (keys section-renderers)))

(defn extension-registry-errors
  []
  (let [supported-sections (supported-order-type-sections)
        order-type-entries order-types/order-type-config
        missing-required (remove #(contains? order-type-entries %)
                                 [:market :limit])
        invalid-pro-types (remove #(contains? order-type-entries %)
                                  (order-types/pro-order-types))
        entry-errors (mapcat (fn [order-type]
                               (let [entry (order-types/order-type-entry order-type)
                                     sections (:sections entry)
                                     unknown-sections (remove supported-sections sections)
                                     duplicate-sections (->> sections
                                                             frequencies
                                                             (filter (fn [[_ c]] (> c 1)))
                                                             (map first))
                                     capability-keys [:limit-like?
                                                      :supports-tpsl?
                                                      :supports-post-only?
                                                      :show-scale-preview?
                                                      :show-liquidation-row?
                                                      :show-slippage-row?]
                                     non-boolean-capabilities (->> capability-keys
                                                                   (filter #(contains? entry %))
                                                                   (remove #(boolean? (get entry %))))]
                                 (remove nil?
                                         [(when-not (and (string? (:label entry))
                                                         (seq (:label entry)))
                                            {:type :missing-label
                                             :order-type order-type})
                                          (when-not (vector? sections)
                                            {:type :invalid-sections
                                             :order-type order-type
                                             :value sections})
                                          (when (seq unknown-sections)
                                            {:type :unknown-sections
                                             :order-type order-type
                                             :sections (vec unknown-sections)})
                                          (when (seq duplicate-sections)
                                            {:type :duplicate-sections
                                             :order-type order-type
                                             :sections (vec duplicate-sections)})
                                          (when (seq non-boolean-capabilities)
                                            {:type :non-boolean-capabilities
                                             :order-type order-type
                                             :keys (vec non-boolean-capabilities)})])))
                             (keys order-type-entries))]
    (vec (concat
          (when (seq missing-required)
            [{:type :missing-required-order-types
              :order-types (vec missing-required)}])
          (when (seq invalid-pro-types)
            [{:type :invalid-pro-order-types
              :order-types (vec invalid-pro-types)}])
          entry-errors))))

(defn assert-valid-extension-registry!
  []
  (let [errors (extension-registry-errors)]
    (when (seq errors)
      (throw (js/Error.
              (str "Invalid order-form type extension registry. errors="
                   (pr-str errors)))))
    true))

(defn- ensure-valid-extension-registry!
  []
  (when ^boolean goog.DEBUG
    (assert-valid-extension-registry!)))

(defn order-type-extension [order-type]
  (ensure-valid-extension-registry!)
  (let [entry (order-types/order-type-entry order-type)
        section-ids (->> (:sections entry)
                         (filter #(contains? section-renderers %))
                         vec)]
    (assoc entry
           :id order-type
           :label (order-types/order-type-label order-type)
           :sections section-ids)))

(defn render-order-type-sections [order-type form callbacks]
  (ensure-valid-extension-registry!)
  (let [extension (order-type-extension order-type)]
    (for [section-id (:sections extension)]
      (when-let [renderer (get section-renderers section-id)]
        ^{:key (str "order-type-section-" (name section-id))}
        (renderer form callbacks)))))
