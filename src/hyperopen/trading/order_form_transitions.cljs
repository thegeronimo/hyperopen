(ns hyperopen.trading.order-form-transitions
  (:require [clojure.string :as str]
            [hyperopen.state.trading.order-form-key-policy :as order-form-key-policy]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-tpsl-policy :as tpsl-policy]))

(defn- normalize-order-entry-mode [mode]
  (let [candidate (cond
                    (keyword? mode) mode
                    (string? mode) (keyword mode)
                    :else :market)]
    (if (contains? #{:market :limit :pro} candidate)
      candidate
      :market)))

(defn- cleared-runtime-state [state]
  (assoc (trading/order-form-runtime-state state) :error nil))

(defn- ui-only-form-path? [path]
  (order-form-key-policy/canonical-write-blocked-order-form-path? path))

(defn- enforce-field-ownership
  [state transition]
  (if-not (map? transition)
    transition
    (let [order-form (:order-form transition)
          order-form-ui (:order-form-ui transition)
          order-form-runtime (:order-form-runtime transition)
          persisted-form (when (map? order-form)
                           (trading/persist-order-form order-form))
          persisted-ui (when (or (map? order-form-ui)
                                 (map? order-form))
                         (let [working-form (or order-form (trading/order-form-draft state))
                               merged-ui (merge (trading/order-form-ui-state state)
                                                (or order-form-ui {})
                                                (trading/order-form-ui-overrides-from-form order-form))]
                           (trading/effective-order-form-ui working-form merged-ui)))]
      (cond-> {}
        (map? persisted-form) (assoc :order-form persisted-form)
        (map? persisted-ui) (assoc :order-form-ui persisted-ui)
        (map? order-form-runtime) (assoc :order-form-runtime order-form-runtime)))))

(defn- size-input-mode [form]
  (trading/normalize-size-input-mode (:size-input-mode form)))

(defn- size-input-source [form]
  (trading/normalize-size-input-source (:size-input-source form)))

(defn- percent-sized? [form]
  (= :percent (size-input-source form)))

(defn- manual-sized? [form]
  (= :manual (size-input-source form)))

(defn- positive-size-percent? [form]
  (pos? (or (trading/parse-num (:size-percent form)) 0)))

(defn- sync-size-percent-preserving-manual-display
  [state form]
  (let [raw-size-display (str (or (:size-display form) ""))]
    (-> (trading/sync-size-percent-from-size state form)
        (assoc :size-display raw-size-display
               :size-input-source :manual))))

(defn- apply-size-display-input
  [state form raw-value]
  (let [raw* (str (or raw-value ""))
        normalized-form (trading/normalize-order-form state form)
        mode (size-input-mode normalized-form)
        reference-price (trading/reference-price state normalized-form)
        parsed-display-size (trading/parse-num raw*)
        canonical-size (case mode
                         :quote
                         (when (and (number? parsed-display-size)
                                    (pos? parsed-display-size)
                                    (number? reference-price)
                                    (pos? reference-price))
                           (trading/base-size-string state (/ parsed-display-size reference-price)))

                         :base
                         (when (and (number? parsed-display-size)
                                    (pos? parsed-display-size))
                           (trading/base-size-string state parsed-display-size))

                         nil)
        updated (assoc form
                       :size-input-source :manual
                       :size-display raw*
                       :size (or canonical-size ""))]
    (cond
      (str/blank? raw*)
      (assoc updated :size "" :size-percent 0)

      (seq canonical-size)
      (sync-size-percent-preserving-manual-display state updated)

      :else
      (assoc updated :size-percent 0))))

(defn- reconcile-size-after-context-change
  [state form]
  (cond
    (and (percent-sized? form)
         (positive-size-percent? form))
    (-> (trading/sync-size-from-percent state form)
        (assoc :size-input-source :percent))

    (and (manual-sized? form)
         (= :quote (size-input-mode form))
         (not (str/blank? (str (or (:size-display form) "")))))
    (apply-size-display-input state form (:size-display form))

    (manual-sized? form)
    (sync-size-percent-preserving-manual-display state form)

    :else
    form))

(defn- disable-tpsl-legs
  [form]
  (-> form
      (assoc-in [:tp :enabled?] false)
      (assoc-in [:sl :enabled?] false)))

(defn- sync-tpsl-leg-enablement
  [form]
  (-> form
      (assoc-in [:tp :enabled?] (tpsl-policy/trigger-present? (get-in form [:tp :trigger])))
      (assoc-in [:sl :enabled?] (tpsl-policy/trigger-present? (get-in form [:sl :trigger])))
      (assoc-in [:tp :is-market] true)
      (assoc-in [:sl :is-market] true)
      (assoc-in [:tp :limit] "")
      (assoc-in [:sl :limit] "")))

(defn- apply-tpsl-trigger
  [form leg trigger]
  (-> form
      (assoc-in [leg :trigger] (or trigger ""))
      (assoc-in [leg :enabled?] (tpsl-policy/trigger-present? trigger))
      (assoc-in [leg :is-market] true)
      (assoc-in [leg :limit] "")))

(defn- resolve-tpsl-trigger-from-offset-input
  [state form leg raw-value]
  (let [normalized-form (trading/normalize-order-form state form)
        ui-state (trading/order-form-ui-state state)
        pricing-policy (trading/order-price-policy state normalized-form ui-state)
        limit-like? (trading/limit-like-type? (:type normalized-form))
        baseline (tpsl-policy/baseline-price normalized-form pricing-policy limit-like?)
        unit (tpsl-policy/normalize-unit (get-in normalized-form [:tpsl :unit]))
        size (trading/parse-num (:size normalized-form))
        leverage (trading/parse-num (:ui-leverage normalized-form))
        inverse (tpsl-policy/inverse-for-leg (:side normalized-form) leg)]
    (tpsl-policy/trigger-from-offset-input {:raw-input raw-value
                                            :baseline baseline
                                            :size size
                                            :leverage leverage
                                            :inverse inverse
                                            :unit unit})))

(defn- backfill-tpsl-triggers-from-offset-inputs
  [state form]
  (let [panel-open? (boolean (:tpsl-panel-open? (trading/order-form-ui-state state)))]
    (if (and panel-open?
             (not (true? (:reduce-only form))))
      (reduce (fn [next-form leg]
                (let [trigger (get-in next-form [leg :trigger])
                      raw-offset (get-in next-form [leg :offset-input])]
                  (if (and (not (tpsl-policy/trigger-present? trigger))
                           (tpsl-policy/trigger-present? raw-offset))
                    (let [resolved-trigger (resolve-tpsl-trigger-from-offset-input state
                                                                                   next-form
                                                                                   leg
                                                                                   raw-offset)]
                      (if (tpsl-policy/trigger-present? resolved-trigger)
                        (apply-tpsl-trigger next-form leg resolved-trigger)
                        next-form))
                    next-form)))
              form
              [:tp :sl])
      form)))

(defn select-entry-mode [state mode]
  (let [mode* (normalize-order-entry-mode mode)
        form (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        close-pro-dropdown? (contains? #{:market :limit} mode*)
        next-type (case mode*
                    :market :market
                    :limit :limit
                    (trading/normalize-pro-order-type (:type form)))
        normalized (trading/normalize-order-form state
                                                 (assoc form
                                                        :entry-mode mode*
                                                        :type next-type))
        next-form (reconcile-size-after-context-change state normalized)
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state
                        :pro-order-type-dropdown-open?
                        (if close-pro-dropdown?
                          false
                          (boolean (:pro-order-type-dropdown-open? ui-state)))
                        :size-unit-dropdown-open? false
                        :tpsl-unit-dropdown-open? false
                        :tif-dropdown-open? false))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-ui next-ui
      :order-form-runtime (cleared-runtime-state state)})))

(defn select-pro-order-type [state order-type]
  (let [form (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        next-type (trading/normalize-pro-order-type order-type)
        normalized (trading/normalize-order-form state
                                                 (assoc form
                                                        :entry-mode :pro
                                                        :type next-type))
        next-form (reconcile-size-after-context-change state normalized)
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state
                        :pro-order-type-dropdown-open? false
                        :size-unit-dropdown-open? false
                        :tpsl-unit-dropdown-open? false
                        :tif-dropdown-open? false))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-ui next-ui
      :order-form-runtime (cleared-runtime-state state)})))

(defn toggle-pro-order-type-dropdown [state]
  (let [ui-state (trading/order-form-ui-state state)
        open? (boolean (:pro-order-type-dropdown-open? ui-state))]
    (enforce-field-ownership
     state
     {:order-form-ui (assoc ui-state :pro-order-type-dropdown-open? (not open?))})))

(defn close-pro-order-type-dropdown [state]
  (enforce-field-ownership
   state
   {:order-form-ui (assoc (trading/order-form-ui-state state)
                          :pro-order-type-dropdown-open? false)}))

(defn handle-pro-order-type-dropdown-keydown [state key]
  (when (= key "Escape")
    (close-pro-order-type-dropdown state)))

(defn toggle-size-unit-dropdown [state]
  (let [ui-state (trading/order-form-ui-state state)
        open? (boolean (:size-unit-dropdown-open? ui-state))]
    (enforce-field-ownership
     state
     {:order-form-ui (assoc ui-state :size-unit-dropdown-open? (not open?))})))

(defn close-size-unit-dropdown [state]
  (enforce-field-ownership
   state
   {:order-form-ui (assoc (trading/order-form-ui-state state)
                          :size-unit-dropdown-open? false)}))

(defn handle-size-unit-dropdown-keydown [state key]
  (when (= key "Escape")
    (close-size-unit-dropdown state)))

(defn toggle-tpsl-unit-dropdown [state]
  (let [ui-state (trading/order-form-ui-state state)
        open? (boolean (:tpsl-unit-dropdown-open? ui-state))
        panel-open? (boolean (:tpsl-panel-open? ui-state))
        next-open? (if panel-open?
                     (not open?)
                     false)]
    (enforce-field-ownership
     state
     {:order-form-ui (assoc ui-state :tpsl-unit-dropdown-open? next-open?)})))

(defn close-tpsl-unit-dropdown [state]
  (enforce-field-ownership
   state
   {:order-form-ui (assoc (trading/order-form-ui-state state)
                          :tpsl-unit-dropdown-open? false)}))

(defn handle-tpsl-unit-dropdown-keydown [state key]
  (when (= key "Escape")
    (close-tpsl-unit-dropdown state)))

(defn toggle-tif-dropdown [state]
  (let [ui-state (trading/order-form-ui-state state)
        open? (boolean (:tif-dropdown-open? ui-state))]
    (enforce-field-ownership
     state
     {:order-form-ui (assoc ui-state :tif-dropdown-open? (not open?))})))

(defn close-tif-dropdown [state]
  (enforce-field-ownership
   state
   {:order-form-ui (assoc (trading/order-form-ui-state state)
                          :tif-dropdown-open? false)}))

(defn handle-tif-dropdown-keydown [state key]
  (when (= key "Escape")
    (close-tif-dropdown state)))

(defn set-order-ui-leverage [state leverage]
  (let [form (trading/order-form-draft state)
        normalized (trading/normalize-ui-leverage state leverage)
        updated (assoc form :ui-leverage normalized)
        next-form (->> (reconcile-size-after-context-change state updated)
                       (backfill-tpsl-triggers-from-offset-inputs state))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-runtime (cleared-runtime-state state)})))

(defn set-order-size-percent [state percent]
  (let [form (trading/order-form-draft state)
        next-form (-> (trading/apply-size-percent state
                                                  (assoc form :size-input-source :percent)
                                                  percent)
                      (assoc :size-input-source :percent)
                      (#(backfill-tpsl-triggers-from-offset-inputs state %)))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-runtime (cleared-runtime-state state)})))

(defn set-order-size-display [state value]
  (let [raw-value (str (or value ""))
        form (trading/order-form-draft state)
        next-form (-> (apply-size-display-input state form raw-value)
                      (#(backfill-tpsl-triggers-from-offset-inputs state %)))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-runtime (cleared-runtime-state state)})))

(defn set-order-size-input-mode [state mode]
  (let [form (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        mode* (trading/normalize-size-input-mode mode)
        updated (assoc form :size-input-mode mode*)
        next-form (if (str/blank? (str (or (:size updated) "")))
                    (assoc updated :size-display "")
                    (trading/sync-size-display-for-input-mode state updated))
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state :size-unit-dropdown-open? false))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-ui next-ui
      :order-form-runtime (cleared-runtime-state state)})))

(defn focus-order-price-input [state]
  (let [form (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        pricing-policy (trading/order-price-policy state form ui-state)
        fallback-price (:capture-on-focus-price pricing-policy)
        should-capture-fallback? (seq fallback-price)
        updated (cond-> form
                  should-capture-fallback? (assoc :price fallback-price))
        next-form (if should-capture-fallback?
                    (-> (reconcile-size-after-context-change state updated)
                        (#(backfill-tpsl-triggers-from-offset-inputs state %)))
                    updated)
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state :price-input-focused? true))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-ui next-ui
      :order-form-runtime (cleared-runtime-state state)})))

(defn blur-order-price-input [state]
  (let [form (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        next-ui (trading/effective-order-form-ui
                 form
                 (assoc ui-state :price-input-focused? false))]
    (enforce-field-ownership
     state
     {:order-form-ui next-ui
      :order-form-runtime (cleared-runtime-state state)})))

(defn set-order-price-to-mid [state]
  (let [form (trading/order-form-draft state)
        pricing-policy (trading/order-price-policy state form (trading/order-form-ui-state state))
        mid-price-string (:mid-price pricing-policy)
        updated (if (seq mid-price-string)
                  (assoc form :price mid-price-string)
                  form)
        next-form (if (seq mid-price-string)
                    (-> (reconcile-size-after-context-change state updated)
                        (#(backfill-tpsl-triggers-from-offset-inputs state %)))
                    updated)]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-runtime (cleared-runtime-state state)})))

(defn toggle-order-tpsl-panel [state]
  (let [form (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        normalized-form (trading/normalize-order-form state form)]
    (when (not= :scale (:type normalized-form))
      (let [next-open? (not (boolean (:tpsl-panel-open? ui-state)))
            next-form (cond-> form
                        next-open? (assoc :reduce-only false)
                        next-open? sync-tpsl-leg-enablement
                        (not next-open?) disable-tpsl-legs)
            next-ui (trading/effective-order-form-ui
                     next-form
                     (assoc ui-state
                            :tpsl-panel-open? next-open?
                            :tpsl-unit-dropdown-open? false))]
        (enforce-field-ownership
         state
         {:order-form next-form
          :order-form-ui next-ui
          :order-form-runtime (cleared-runtime-state state)})))))

(defn update-order-form [state path value]
  (if (ui-only-form-path? path)
    (enforce-field-ownership
     state
     {:order-form-runtime (cleared-runtime-state state)})
    (let [form (trading/order-form-draft state)
          tp-offset-input? (= path [:tp :offset-input])
          sl-offset-input? (= path [:sl :offset-input])
          raw-input (str (or value ""))
          resolved-path (case path
                          [:tp :offset-input] [:tp :trigger]
                          [:sl :offset-input] [:sl :trigger]
                          path)
          resolved-value (case path
                           [:tp :offset-input] (resolve-tpsl-trigger-from-offset-input state form :tp value)
                           [:sl :offset-input] (resolve-tpsl-trigger-from-offset-input state form :sl value)
                           value)
          normalized-value (cond
                             (= resolved-path [:type]) (:value (trading/order-type-value resolved-value))
                             (= resolved-path [:side]) (:value (trading/side-value resolved-value))
                             (= resolved-path [:tif]) (:value (trading/tif-value resolved-value))
                             (= resolved-path [:tpsl :unit]) (tpsl-policy/normalize-unit resolved-value)
                             :else resolved-value)
          updated (cond-> (assoc-in form resolved-path normalized-value)
                    tp-offset-input? (assoc-in [:tp :offset-input] raw-input)
                    sl-offset-input? (assoc-in [:sl :offset-input] raw-input)
                    (and (= resolved-path [:tp :trigger])
                         (not tp-offset-input?)) (assoc-in [:tp :offset-input] "")
                    (and (= resolved-path [:sl :trigger])
                         (not sl-offset-input?)) (assoc-in [:sl :offset-input] "")
                    (= resolved-path [:tpsl :unit]) (assoc-in [:tp :offset-input] "")
                    (= resolved-path [:tpsl :unit]) (assoc-in [:sl :offset-input] ""))
          next-form (cond
                      (= resolved-path [:type])
                      (let [typed (-> updated
                                      (update :type trading/normalize-order-type)
                                      (assoc :entry-mode (trading/entry-mode-for-type (:type updated))))
                            normalized (trading/normalize-order-form state typed)]
                        (reconcile-size-after-context-change state normalized))

                      (= resolved-path [:size])
                      (-> (trading/sync-size-percent-from-size state updated)
                          (assoc :size-input-source :manual))

                      (or (= resolved-path [:price]) (= resolved-path [:side]))
                      (reconcile-size-after-context-change state updated)

                      (= resolved-path [:reduce-only])
                      (cond-> updated
                        normalized-value disable-tpsl-legs)

                      (= resolved-path [:tp :trigger])
                      (apply-tpsl-trigger updated :tp normalized-value)

                      (= resolved-path [:sl :trigger])
                      (apply-tpsl-trigger updated :sl normalized-value)

                      :else
                      updated)
          backfilled-form (backfill-tpsl-triggers-from-offset-inputs state next-form)
          next-ui (cond
                    (= resolved-path [:tif])
                    (trading/effective-order-form-ui
                     backfilled-form
                     (assoc (trading/order-form-ui-state state)
                            :tif-dropdown-open? false))

                    (= resolved-path [:tpsl :unit])
                    (trading/effective-order-form-ui
                     backfilled-form
                     (assoc (trading/order-form-ui-state state)
                            :tpsl-unit-dropdown-open? false))

                    (and (= resolved-path [:reduce-only])
                         (true? normalized-value))
                    (trading/effective-order-form-ui
                     backfilled-form
                     (assoc (trading/order-form-ui-state state)
                            :tpsl-panel-open? false
                            :tpsl-unit-dropdown-open? false))

                    :else
                    nil)]
      (enforce-field-ownership
       state
       (cond-> {:order-form backfilled-form
                :order-form-runtime (cleared-runtime-state state)}
         (map? next-ui) (assoc :order-form-ui next-ui))))))
