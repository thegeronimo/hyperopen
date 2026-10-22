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
  {:command-id :order-form/toggle-margin-mode-dropdown
    :action-id :actions/toggle-margin-mode-dropdown
    :handler-key :toggle-margin-mode-dropdown}
   {:command-id :order-form/close-margin-mode-dropdown
    :action-id :actions/close-margin-mode-dropdown
    :handler-key :close-margin-mode-dropdown}
   {:command-id :order-form/handle-margin-mode-dropdown-keydown
    :action-id :actions/handle-margin-mode-dropdown-keydown
    :handler-key :handle-margin-mode-dropdown-keydown}
   {:command-id :order-form/toggle-leverage-popover
    :action-id :actions/toggle-leverage-popover
    :handler-key :toggle-leverage-popover}
   {:command-id :order-form/close-leverage-popover
    :action-id :actions/close-leverage-popover
    :handler-key :close-leverage-popover}
   {:command-id :order-form/handle-leverage-popover-keydown
    :action-id :actions/handle-leverage-popover-keydown
    :handler-key :handle-leverage-popover-keydown}
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
   {:command-id :order-form/toggle-outcome-option-dropdown
    :action-id :actions/toggle-outcome-option-dropdown
    :handler-key :toggle-outcome-option-dropdown}
   {:command-id :order-form/close-outcome-option-dropdown
    :action-id :actions/close-outcome-option-dropdown
    :handler-key :close-outcome-option-dropdown}
   {:command-id :order-form/set-outcome-option-query
    :action-id :actions/set-outcome-option-query
    :handler-key :set-outcome-option-query}
   {:command-id :order-form/set-outcome-option-sort
    :action-id :actions/set-outcome-option-sort
    :handler-key :set-outcome-option-sort}
   {:command-id :order-form/handle-outcome-option-dropdown-keydown
    :action-id :actions/handle-outcome-option-dropdown-keydown
    :handler-key :handle-outcome-option-dropdown-keydown}
   {:command-id :order-form/select-outcome-option
    :action-id :actions/select-outcome-option
    :handler-key :select-outcome-option}
   {:command-id :order-form/select-pro-order-type
    :action-id :actions/select-pro-order-type
    :handler-key :select-pro-order-type}
   {:command-id :order-form/set-order-ui-leverage-draft
    :action-id :actions/set-order-ui-leverage-draft
    :handler-key :set-order-ui-leverage-draft}
   {:command-id :order-form/confirm-order-ui-leverage
    :action-id :actions/confirm-order-ui-leverage
    :handler-key :confirm-order-ui-leverage}
   {:command-id :order-form/set-order-ui-leverage
    :action-id :actions/set-order-ui-leverage
    :handler-key :set-order-ui-leverage}
   {:command-id :order-form/set-order-margin-mode
    :action-id :actions/set-order-margin-mode
    :handler-key :set-order-margin-mode}
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
    :handler-key :submit-order}
   {:command-id :order-form/select-scale-mode
    :action-id :actions/select-scale-mode
    :handler-key :select-scale-mode}
   {:command-id :order-form/update-vol-range-draft
    :action-id :actions/update-vol-range-draft
    :handler-key :update-vol-range-draft}
   {:command-id :order-form/set-vol-range-sell-quantity-percent
    :action-id :actions/set-vol-range-sell-quantity-percent
    :handler-key :set-vol-range-sell-quantity-percent}
   {:command-id :order-form/apply-vol-range-remediation
    :action-id :actions/apply-vol-range-remediation
    :handler-key :apply-vol-range-remediation}
   {:command-id :order-form/open-vol-range-review
    :action-id :actions/open-vol-range-review
    :handler-key :open-vol-range-review}
   {:command-id :order-form/close-vol-range-review
    :action-id :actions/close-vol-range-review
    :handler-key :close-vol-range-review}
   {:command-id :order-form/handle-vol-range-review-keydown
    :action-id :actions/handle-vol-range-review-keydown
    :handler-key :handle-vol-range-review-keydown}
   {:command-id :order-form/set-vol-range-ui-flag
    :action-id :actions/set-vol-range-ui-flag
    :handler-key :set-vol-range-ui-flag}
   {:command-id :order-form/refresh-vol-range-daily-candles
    :action-id :actions/refresh-vol-range-daily-candles
    :handler-key :refresh-vol-range-daily-candles}])

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
