(ns hyperopen.schema.order-form-command-catalog)

(def ^:private command-catalog-entries
  [{:command-id :order-form/select-entry-mode
    :action-id :actions/select-order-entry-mode
    :handler-key :select-order-entry-mode}
   {:command-id :order-form/toggle-pro-order-type-dropdown
    :action-id :actions/toggle-pro-order-type-dropdown
    :handler-key :toggle-pro-order-type-dropdown}
   {:command-id :order-form/close-pro-order-type-dropdown
    :action-id :actions/close-pro-order-type-dropdown
    :handler-key :close-pro-order-type-dropdown}
   {:command-id :order-form/handle-pro-order-type-dropdown-keydown
    :action-id :actions/handle-pro-order-type-dropdown-keydown
    :handler-key :handle-pro-order-type-dropdown-keydown}
   {:command-id :order-form/toggle-size-unit-dropdown
    :action-id :actions/toggle-size-unit-dropdown
    :handler-key :toggle-size-unit-dropdown}
   {:command-id :order-form/close-size-unit-dropdown
    :action-id :actions/close-size-unit-dropdown
    :handler-key :close-size-unit-dropdown}
   {:command-id :order-form/handle-size-unit-dropdown-keydown
    :action-id :actions/handle-size-unit-dropdown-keydown
    :handler-key :handle-size-unit-dropdown-keydown}
   {:command-id :order-form/toggle-tpsl-unit-dropdown
    :action-id :actions/toggle-tpsl-unit-dropdown
    :handler-key :toggle-tpsl-unit-dropdown}
   {:command-id :order-form/close-tpsl-unit-dropdown
    :action-id :actions/close-tpsl-unit-dropdown
    :handler-key :close-tpsl-unit-dropdown}
   {:command-id :order-form/handle-tpsl-unit-dropdown-keydown
    :action-id :actions/handle-tpsl-unit-dropdown-keydown
    :handler-key :handle-tpsl-unit-dropdown-keydown}
   {:command-id :order-form/toggle-tif-dropdown
    :action-id :actions/toggle-tif-dropdown
    :handler-key :toggle-tif-dropdown}
   {:command-id :order-form/close-tif-dropdown
    :action-id :actions/close-tif-dropdown
    :handler-key :close-tif-dropdown}
   {:command-id :order-form/handle-tif-dropdown-keydown
    :action-id :actions/handle-tif-dropdown-keydown
    :handler-key :handle-tif-dropdown-keydown}
   {:command-id :order-form/select-pro-order-type
    :action-id :actions/select-pro-order-type
    :handler-key :select-pro-order-type}
   {:command-id :order-form/set-order-ui-leverage
    :action-id :actions/set-order-ui-leverage
    :handler-key :set-order-ui-leverage}
   {:command-id :order-form/update-order-form
    :action-id :actions/update-order-form
    :handler-key :update-order-form}
   {:command-id :order-form/set-order-size-display
    :action-id :actions/set-order-size-display
    :handler-key :set-order-size-display}
   {:command-id :order-form/set-order-size-input-mode
    :action-id :actions/set-order-size-input-mode
    :handler-key :set-order-size-input-mode}
   {:command-id :order-form/set-order-size-percent
    :action-id :actions/set-order-size-percent
    :handler-key :set-order-size-percent}
   {:command-id :order-form/focus-order-price-input
    :action-id :actions/focus-order-price-input
    :handler-key :focus-order-price-input}
   {:command-id :order-form/blur-order-price-input
    :action-id :actions/blur-order-price-input
    :handler-key :blur-order-price-input}
   {:command-id :order-form/set-order-price-to-mid
    :action-id :actions/set-order-price-to-mid
    :handler-key :set-order-price-to-mid}
   {:command-id :order-form/toggle-order-tpsl-panel
    :action-id :actions/toggle-order-tpsl-panel
    :handler-key :toggle-order-tpsl-panel}
   {:command-id :order-form/submit-order
    :action-id :actions/submit-order
    :handler-key :submit-order}])

(defn- duplicate-values
  [entries entry-key]
  (->> entries
       (map entry-key)
       frequencies
       (keep (fn [[value freq]]
               (when (> freq 1)
                 value)))
       sort
       vec))

(defn- assert-unique-entry-values!
  [entries entry-key]
  (when-let [duplicates (seq (duplicate-values entries entry-key))]
    (throw (js/Error.
            (str "order-form command catalog has duplicate "
                 (name entry-key)
                 " values: "
                 (pr-str duplicates)))))
  entries)

(def ^:private validated-command-catalog-entries
  (-> command-catalog-entries
      (assert-unique-entry-values! :command-id)
      (assert-unique-entry-values! :action-id)))

(def ^:private action-id-by-command-id
  (into {}
        (map (juxt :command-id :action-id))
        validated-command-catalog-entries))

(def ^:private supported-command-id-set
  (set (keys action-id-by-command-id)))

(def ^:private action-id-set
  (set (map :action-id validated-command-catalog-entries)))

(def ^:private runtime-action-binding-rows
  (mapv (juxt :action-id :handler-key)
        validated-command-catalog-entries))

(defn catalog-entries
  []
  validated-command-catalog-entries)

(defn supported-command-ids
  []
  supported-command-id-set)

(defn action-id-for-command
  [command-id]
  (get action-id-by-command-id command-id))

(defn catalog-action-ids
  []
  action-id-set)

(defn runtime-action-bindings
  []
  runtime-action-binding-rows)
