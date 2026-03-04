(ns hyperopen.order.actions
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-transitions :as transitions]))

(defn- next-order-form-ui-state
  [state order-form order-form-ui]
  (let [working-form (or order-form (trading/order-form-draft state))
        base-ui (trading/order-form-ui-state state)
        merged-ui (merge base-ui
                         (or order-form-ui {})
                         (trading/order-form-ui-overrides-from-form order-form))]
    (trading/effective-order-form-ui working-form merged-ui)))

(defn- transition-save-many
  [state {:keys [order-form order-form-ui order-form-runtime]}]
  (let [persisted-form (when (map? order-form)
                         (trading/persist-order-form order-form))
        persisted-ui (when (or (map? order-form-ui)
                               (map? order-form))
                       (next-order-form-ui-state state order-form order-form-ui))
        path-values (cond-> []
                      (map? persisted-form) (conj [[:order-form] persisted-form])
                      (map? persisted-ui) (conj [[:order-form-ui] persisted-ui])
                      (map? order-form-runtime) (conj [[:order-form-runtime] order-form-runtime]))]
    (if (seq path-values)
      [[:effects/save-many path-values]]
      [])))

(defn select-order-entry-mode [state mode]
  (transition-save-many state (transitions/select-entry-mode state mode)))

(defn select-pro-order-type [state order-type]
  (transition-save-many state (transitions/select-pro-order-type state order-type)))

(defn toggle-pro-order-type-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/toggle-pro-order-type-dropdown state))
        next-open? (boolean (:pro-order-type-dropdown-open? ui-state))]
    [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] next-open?]]]]))

(defn close-pro-order-type-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/close-pro-order-type-dropdown state))]
    [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?]
                           (boolean (:pro-order-type-dropdown-open? ui-state))]]]]))

(defn handle-pro-order-type-dropdown-keydown [state key]
  (if-let [transition (transitions/handle-pro-order-type-dropdown-keydown state key)]
    (let [ui-state (:order-form-ui transition)]
      [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?]
                             (boolean (:pro-order-type-dropdown-open? ui-state))]]]])
    []))

(defn toggle-margin-mode-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/toggle-margin-mode-dropdown state))
        next-open? (boolean (:margin-mode-dropdown-open? ui-state))]
    [[:effects/save-many [[[:order-form-ui :margin-mode-dropdown-open?] next-open?]
                          [[:order-form-ui :leverage-popover-open?]
                           (boolean (:leverage-popover-open? ui-state))]
                          [[:order-form-ui :leverage-draft]
                           (:leverage-draft ui-state)]]]]))

(defn close-margin-mode-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/close-margin-mode-dropdown state))]
    [[:effects/save-many [[[:order-form-ui :margin-mode-dropdown-open?]
                           (boolean (:margin-mode-dropdown-open? ui-state))]]]]))

(defn handle-margin-mode-dropdown-keydown [state key]
  (if-let [transition (transitions/handle-margin-mode-dropdown-keydown state key)]
    (let [ui-state (:order-form-ui transition)]
      [[:effects/save-many [[[:order-form-ui :margin-mode-dropdown-open?]
                             (boolean (:margin-mode-dropdown-open? ui-state))]]]])
    []))

(defn toggle-leverage-popover [state]
  (let [ui-state (:order-form-ui (transitions/toggle-leverage-popover state))]
    [[:effects/save-many [[[:order-form-ui :margin-mode-dropdown-open?]
                           (boolean (:margin-mode-dropdown-open? ui-state))]
                          [[:order-form-ui :leverage-popover-open?]
                           (boolean (:leverage-popover-open? ui-state))]
                          [[:order-form-ui :leverage-draft]
                           (:leverage-draft ui-state)]]]]))

(defn close-leverage-popover [state]
  (let [ui-state (:order-form-ui (transitions/close-leverage-popover state))]
    [[:effects/save-many [[[:order-form-ui :leverage-popover-open?]
                           (boolean (:leverage-popover-open? ui-state))]
                          [[:order-form-ui :leverage-draft]
                           (:leverage-draft ui-state)]]]]))

(defn handle-leverage-popover-keydown [state key]
  (if-let [transition (transitions/handle-leverage-popover-keydown state key)]
    (let [ui-state (:order-form-ui transition)]
      [[:effects/save-many [[[:order-form-ui :leverage-popover-open?]
                             (boolean (:leverage-popover-open? ui-state))]
                            [[:order-form-ui :leverage-draft]
                             (:leverage-draft ui-state)]]]])
    []))

(defn set-order-ui-leverage-draft [state leverage]
  (let [ui-state (:order-form-ui (transitions/set-order-ui-leverage-draft state leverage))]
    [[:effects/save-many [[[:order-form-ui :leverage-popover-open?]
                           (boolean (:leverage-popover-open? ui-state))]
                          [[:order-form-ui :leverage-draft]
                           (:leverage-draft ui-state)]]]]))

(defn confirm-order-ui-leverage [state]
  (transition-save-many state (transitions/confirm-order-ui-leverage state)))

(defn toggle-size-unit-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/toggle-size-unit-dropdown state))
        next-open? (boolean (:size-unit-dropdown-open? ui-state))]
    [[:effects/save-many [[[:order-form-ui :size-unit-dropdown-open?] next-open?]]]]))

(defn close-size-unit-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/close-size-unit-dropdown state))]
    [[:effects/save-many [[[:order-form-ui :size-unit-dropdown-open?]
                           (boolean (:size-unit-dropdown-open? ui-state))]]]]))

(defn handle-size-unit-dropdown-keydown [state key]
  (if-let [transition (transitions/handle-size-unit-dropdown-keydown state key)]
    (let [ui-state (:order-form-ui transition)]
      [[:effects/save-many [[[:order-form-ui :size-unit-dropdown-open?]
                             (boolean (:size-unit-dropdown-open? ui-state))]]]])
    []))

(defn toggle-tpsl-unit-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/toggle-tpsl-unit-dropdown state))
        next-open? (boolean (:tpsl-unit-dropdown-open? ui-state))]
    [[:effects/save-many [[[:order-form-ui :tpsl-unit-dropdown-open?] next-open?]]]]))

(defn close-tpsl-unit-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/close-tpsl-unit-dropdown state))]
    [[:effects/save-many [[[:order-form-ui :tpsl-unit-dropdown-open?]
                           (boolean (:tpsl-unit-dropdown-open? ui-state))]]]]))

(defn handle-tpsl-unit-dropdown-keydown [state key]
  (if-let [transition (transitions/handle-tpsl-unit-dropdown-keydown state key)]
    (let [ui-state (:order-form-ui transition)]
      [[:effects/save-many [[[:order-form-ui :tpsl-unit-dropdown-open?]
                             (boolean (:tpsl-unit-dropdown-open? ui-state))]]]])
    []))

(defn toggle-tif-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/toggle-tif-dropdown state))
        next-open? (boolean (:tif-dropdown-open? ui-state))]
    [[:effects/save-many [[[:order-form-ui :tif-dropdown-open?] next-open?]]]]))

(defn close-tif-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/close-tif-dropdown state))]
    [[:effects/save-many [[[:order-form-ui :tif-dropdown-open?]
                           (boolean (:tif-dropdown-open? ui-state))]]]]))

(defn handle-tif-dropdown-keydown [state key]
  (if-let [transition (transitions/handle-tif-dropdown-keydown state key)]
    (let [ui-state (:order-form-ui transition)]
      [[:effects/save-many [[[:order-form-ui :tif-dropdown-open?]
                             (boolean (:tif-dropdown-open? ui-state))]]]])
    []))

(defn set-order-ui-leverage [state leverage]
  (transition-save-many state (transitions/set-order-ui-leverage state leverage)))

(defn set-order-margin-mode [state mode]
  (transition-save-many state (transitions/set-order-margin-mode state mode)))

(defn set-order-size-percent [state percent]
  (transition-save-many state (transitions/set-order-size-percent state percent)))

(defn set-order-size-display [state value]
  (transition-save-many state (transitions/set-order-size-display state value)))

(defn set-order-size-input-mode [state mode]
  (transition-save-many state (transitions/set-order-size-input-mode state mode)))

(defn focus-order-price-input [state]
  (transition-save-many state (transitions/focus-order-price-input state)))

(defn blur-order-price-input [state]
  (transition-save-many state (transitions/blur-order-price-input state)))

(defn set-order-price-to-mid [state]
  (transition-save-many state (transitions/set-order-price-to-mid state)))

(defn toggle-order-tpsl-panel [state]
  (if-let [transition (transitions/toggle-order-tpsl-panel state)]
    (transition-save-many state transition)
    []))

(defn update-order-form [state path value]
  (transition-save-many state (transitions/update-order-form state path value)))

(defn submit-order [state]
  (let [ghost-mode-message (account-context/mutations-blocked-message state)
        raw-form (trading/order-form-draft state)
        agent-ready? (= :ready (get-in state [:wallet :agent :status]))
        submit-policy (trading/submit-policy state raw-form {:mode :submit
                                                             :agent-ready? agent-ready?})
        form (:form submit-policy)
        request (:request submit-policy)
        reason (:reason submit-policy)
        error-message (:error-message submit-policy)]
    (cond
      (seq ghost-mode-message)
      [[:effects/save [:order-form-runtime :error] ghost-mode-message]]

      reason
      [[:effects/save [:order-form-runtime :error] error-message]]

      :else
      (let [persisted-form (trading/persist-order-form form)
            persisted-ui (next-order-form-ui-state state form nil)]
      [[:effects/save [:order-form-runtime :error] nil]
       [:effects/save [:order-form] persisted-form]
       [:effects/save [:order-form-ui] persisted-ui]
       [:effects/api-submit-order request]]))))

(defn prune-canceled-open-orders
  [state request]
  (order-effects/prune-canceled-open-orders state request))

(defn cancel-order [state order]
  (let [ghost-mode-message (account-context/mutations-blocked-message state)
        agent-ready? (= :ready (get-in state [:wallet :agent :status]))
        request (trading-api/build-cancel-order-request state order)]
    (cond
      (seq ghost-mode-message)
      [[:effects/save [:orders :cancel-error] ghost-mode-message]]

      (not agent-ready?)
      [[:effects/save [:orders :cancel-error] "Enable trading before cancelling orders."]]

      (map? request)
      [[:effects/api-cancel-order request]]

      :else
      [[:effects/save [:orders :cancel-error] "Missing asset or order id."]])))
