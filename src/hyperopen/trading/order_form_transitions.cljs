(ns hyperopen.trading.order-form-transitions
  (:require [clojure.string :as str]
            [hyperopen.state.trading.order-form-key-policy :as order-form-key-policy]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-ownership :as ownership]
            [hyperopen.utils.parse :as parse-utils]
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

(declare current-ui-leverage
         current-leverage-draft)

(def enforce-field-ownership ownership/enforce-field-ownership)

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

(def ^:private localized-numeric-order-form-paths
  #{[:price]
    [:trigger-px]
    [:scale :start]
    [:scale :end]
    [:scale :count]
    [:scale :skew]
    [:twap :hours]
    [:twap :minutes]
    [:tp :trigger]
    [:tp :limit]
    [:sl :trigger]
    [:sl :limit]})

(def ^:private tpsl-offset-input-path->leg
  {[:tp :offset-input] :tp
   [:sl :offset-input] :sl})

(defn- normalize-localized-numeric-input
  [state value]
  (if (string? value)
    (if (str/blank? value)
      value
      (or (parse-utils/normalize-localized-decimal-input value
                                                         (get-in state [:ui :locale]))
          value))
    value))

(defn- parse-localized-numeric-value
  [state value]
  (if (string? value)
    (or (parse-utils/parse-localized-decimal value (get-in state [:ui :locale]))
        value)
    value))

(defn- sync-size-percent-preserving-manual-display
  [state form]
  (let [raw-size-display (str (or (:size-display form) ""))]
    (-> (trading/sync-size-percent-from-size state form)
        (assoc :size-display raw-size-display
               :size-input-source :manual))))

(defn- apply-size-display-input
  [state form raw-value]
  (let [raw* (str (or raw-value ""))
        locale (get-in state [:ui :locale])
        normalized-form (trading/normalize-order-form state form)
        mode (size-input-mode normalized-form)
        reference-price (trading/reference-price state normalized-form)
        parsed-display-size (parse-utils/parse-localized-decimal raw* locale)
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
  (let [canonical-raw-input (normalize-localized-numeric-input state raw-value)
        normalized-form (trading/normalize-order-form state form)
        ui-state (trading/order-form-ui-state state)
        pricing-policy (trading/order-price-policy state normalized-form ui-state)
        limit-like? (trading/limit-like-type? (:type normalized-form))
        baseline (tpsl-policy/baseline-price normalized-form pricing-policy limit-like?)
        unit (tpsl-policy/normalize-unit (get-in normalized-form [:tpsl :unit]))
        size (trading/parse-num (:size normalized-form))
        leverage (trading/parse-num (:ui-leverage normalized-form))
        inverse (tpsl-policy/inverse-for-leg (:side normalized-form) leg)]
    (tpsl-policy/trigger-from-offset-input {:raw-input canonical-raw-input
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
                        :margin-mode-dropdown-open? false
                        :leverage-popover-open? false
                        :leverage-draft (:ui-leverage next-form)
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
                        :margin-mode-dropdown-open? false
                        :leverage-popover-open? false
                        :leverage-draft (:ui-leverage next-form)
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

(defn toggle-margin-mode-dropdown [state]
  (let [ui-state (trading/order-form-ui-state state)
        open? (boolean (:margin-mode-dropdown-open? ui-state))]
    (enforce-field-ownership
     state
     {:order-form-ui (assoc ui-state
                            :margin-mode-dropdown-open? (not open?)
                            :leverage-popover-open? false
                            :leverage-draft (current-ui-leverage state))})))

(defn close-margin-mode-dropdown [state]
  (enforce-field-ownership
   state
   {:order-form-ui (assoc (trading/order-form-ui-state state)
                          :margin-mode-dropdown-open? false)}))

(defn handle-margin-mode-dropdown-keydown [state key]
  (when (= key "Escape")
    (close-margin-mode-dropdown state)))

(defn- current-ui-leverage
  [state]
  (let [form (trading/order-form-draft state)]
    (trading/normalize-ui-leverage state
                                   (parse-localized-numeric-value state (:ui-leverage form)))))

(defn- current-leverage-draft
  [state]
  (let [ui-state (:order-form-ui state)
        current-leverage (current-ui-leverage state)]
    (trading/normalize-ui-leverage state
                                   (parse-localized-numeric-value
                                    state
                                    (or (when (map? ui-state)
                                          (:leverage-draft ui-state))
                                       current-leverage)))))

(defn toggle-leverage-popover [state]
  (let [ui-state (trading/order-form-ui-state state)
        open? (boolean (:leverage-popover-open? ui-state))
        next-open? (not open?)
        current-leverage (current-ui-leverage state)
        draft-leverage (current-leverage-draft state)
        next-ui (assoc ui-state
                       :margin-mode-dropdown-open? false
                       :leverage-popover-open? next-open?
                       :leverage-draft (if next-open?
                                         draft-leverage
                                         current-leverage))]
    (enforce-field-ownership
     state
     {:order-form-ui next-ui})))

(defn close-leverage-popover [state]
  (let [ui-state (trading/order-form-ui-state state)
        current-leverage (current-ui-leverage state)]
    (enforce-field-ownership
     state
     {:order-form-ui (assoc ui-state
                            :leverage-popover-open? false
                            :leverage-draft current-leverage)})))

(defn handle-leverage-popover-keydown [state key]
  (when (= key "Escape")
    (close-leverage-popover state)))

(defn set-order-ui-leverage-draft [state leverage]
  (let [ui-state (trading/order-form-ui-state state)
        next-draft (trading/normalize-ui-leverage state
                                                  (parse-localized-numeric-value state leverage))]
    (enforce-field-ownership
     state
     {:order-form-ui (assoc ui-state
                            :leverage-popover-open? true
                            :leverage-draft next-draft)})))

(defn confirm-order-ui-leverage [state]
  (let [form (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        next-leverage (current-leverage-draft state)
        updated (assoc form :ui-leverage next-leverage)
        next-form (->> (reconcile-size-after-context-change state updated)
                       (backfill-tpsl-triggers-from-offset-inputs state))
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state
                        :leverage-popover-open? false
                        :leverage-draft next-leverage))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-ui next-ui
      :order-form-runtime (cleared-runtime-state state)})))

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
        ui-state (trading/order-form-ui-state state)
        normalized (trading/normalize-ui-leverage state
                                                  (parse-localized-numeric-value state leverage))
        updated (assoc form :ui-leverage normalized)
        next-form (->> (reconcile-size-after-context-change state updated)
                       (backfill-tpsl-triggers-from-offset-inputs state))
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state
                        :leverage-popover-open? false
                        :leverage-draft normalized))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-ui next-ui
      :order-form-runtime (cleared-runtime-state state)})))

(defn set-order-margin-mode [state mode]
  (let [form (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        mode* (trading/effective-margin-mode state mode)
        next-form (assoc form :margin-mode mode*)
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state
                        :margin-mode-dropdown-open? false
                        :leverage-popover-open? false
                        :leverage-draft (:ui-leverage next-form)))]
    (enforce-field-ownership
     state
     {:order-form next-form
      :order-form-ui next-ui
      :order-form-runtime (cleared-runtime-state state)})))

(defn set-order-size-percent [state percent]
  (let [form (trading/order-form-draft state)
        percent* (if (string? percent)
                   (or (parse-utils/parse-localized-decimal percent (get-in state [:ui :locale]))
                       percent)
                   percent)
        next-form (-> (trading/apply-size-percent state
                                                  (assoc form :size-input-source :percent)
                                                  percent*)
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

(defn- normalized-order-form-update-value
  [state path value]
  (let [canonical-value (if (contains? localized-numeric-order-form-paths path)
                          (normalize-localized-numeric-input state value)
                          value)]
    (cond
      (= path [:type]) (:value (trading/order-type-value value))
      (= path [:side]) (:value (trading/side-value value))
      (= path [:tif]) (:value (trading/tif-value value))
      (= path [:tpsl :unit]) (tpsl-policy/normalize-unit value)
      :else canonical-value)))
(defn- resolve-order-form-update
  [state form path value]
  (let [offset-leg (get tpsl-offset-input-path->leg path)
        resolved-path (if offset-leg
                        [offset-leg :trigger]
                        path)
        resolved-value (if offset-leg
                         (resolve-tpsl-trigger-from-offset-input state form offset-leg value)
                         value)]
    {:offset-leg offset-leg
     :path resolved-path
     :raw-input (str (or value ""))
     :value (normalized-order-form-update-value state resolved-path resolved-value)}))
(defn- apply-order-form-update-value
  [form {:keys [offset-leg path raw-input value]}]
  (cond-> (assoc-in form path value)
    offset-leg (assoc-in [offset-leg :offset-input] raw-input)
    (and (= path [:tp :trigger])
         (not= offset-leg :tp)) (assoc-in [:tp :offset-input] "")
    (and (= path [:sl :trigger])
         (not= offset-leg :sl)) (assoc-in [:sl :offset-input] "")
    (= path [:tpsl :unit]) (assoc-in [:tp :offset-input] "")
    (= path [:tpsl :unit]) (assoc-in [:sl :offset-input] "")))
(defn- apply-order-form-path-effects
  [state form {:keys [path value]}]
  (cond
    (= path [:type])
    (let [typed (-> form
                    (update :type trading/normalize-order-type)
                    (assoc :entry-mode (trading/entry-mode-for-type (:type form))))
          normalized (trading/normalize-order-form state typed)]
      (reconcile-size-after-context-change state normalized))

    (= path [:size])
    (-> (trading/sync-size-percent-from-size state form)
        (assoc :size-input-source :manual))

    (or (= path [:price]) (= path [:side]))
    (reconcile-size-after-context-change state form)

    (= path [:reduce-only])
    (cond-> form
      value disable-tpsl-legs)

    (= path [:tp :trigger])
    (apply-tpsl-trigger form :tp value)

    (= path [:sl :trigger])
    (apply-tpsl-trigger form :sl value)

    :else
    form))

(defn- update-order-form-ui-state
  [state form {:keys [path value]}]
  (let [ui-state (trading/order-form-ui-state state)]
    (cond
      (= path [:tif])
      (trading/effective-order-form-ui
       form
       (assoc ui-state :tif-dropdown-open? false))

      (= path [:tpsl :unit])
      (trading/effective-order-form-ui
       form
       (assoc ui-state :tpsl-unit-dropdown-open? false))

      (and (= path [:reduce-only])
           (true? value))
      (trading/effective-order-form-ui
       form
       (assoc ui-state
              :tpsl-panel-open? false
              :tpsl-unit-dropdown-open? false))

      :else
      nil)))

(defn update-order-form [state path value]
  (if (ui-only-form-path? path)
    (enforce-field-ownership
     state
     {:order-form-runtime (cleared-runtime-state state)})
    (let [form (trading/order-form-draft state)
          update-spec (resolve-order-form-update state form path value)
          updated (apply-order-form-update-value form update-spec)
          next-form (apply-order-form-path-effects state updated update-spec)
          backfilled-form (backfill-tpsl-triggers-from-offset-inputs state next-form)
          next-ui (update-order-form-ui-state state backfilled-form update-spec)]
      (enforce-field-ownership
       state
       (cond-> {:order-form backfilled-form
                :order-form-runtime (cleared-runtime-state state)}
         (map? next-ui) (assoc :order-form-ui next-ui))))))
