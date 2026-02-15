(ns hyperopen.views.trade.order-form-commands)

(defn select-entry-mode [mode]
  [[:actions/select-order-entry-mode mode]])

(defn toggle-pro-order-type-dropdown []
  [[:actions/toggle-pro-order-type-dropdown]])

(defn close-pro-order-type-dropdown []
  [[:actions/close-pro-order-type-dropdown]])

(defn handle-pro-order-type-dropdown-keydown [event-key]
  [[:actions/handle-pro-order-type-dropdown-keydown event-key]])

(defn select-pro-order-type [order-type]
  [[:actions/select-pro-order-type order-type]])

(defn set-order-ui-leverage [leverage]
  [[:actions/set-order-ui-leverage leverage]])

(defn update-order-form [path value]
  [[:actions/update-order-form path value]])

(defn set-order-size-display [event-value]
  [[:actions/set-order-size-display event-value]])

(defn set-order-size-percent [event-value]
  [[:actions/set-order-size-percent event-value]])

(defn focus-order-price-input []
  [[:actions/focus-order-price-input]])

(defn blur-order-price-input []
  [[:actions/blur-order-price-input]])

(defn set-order-price-to-mid []
  [[:actions/set-order-price-to-mid]])

(defn toggle-order-tpsl-panel []
  [[:actions/toggle-order-tpsl-panel]])

(defn submit-order []
  [[:actions/submit-order]])
