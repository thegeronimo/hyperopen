(ns hyperopen.order.actions
  (:require [hyperopen.api.trading :as trading-api]
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

(defn set-order-ui-leverage [state leverage]
  (transition-save-many state (transitions/set-order-ui-leverage state leverage)))

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
  (let [raw-form (trading/order-form-draft state)
        agent-ready? (= :ready (get-in state [:wallet :agent :status]))
        submit-policy (trading/submit-policy state raw-form {:mode :submit
                                                             :agent-ready? agent-ready?})
        form (:form submit-policy)
        request (:request submit-policy)
        reason (:reason submit-policy)
        error-message (:error-message submit-policy)]
    (cond
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
  (let [agent-ready? (= :ready (get-in state [:wallet :agent :status]))
        request (trading-api/build-cancel-order-request state order)]
    (cond
      (not agent-ready?)
      [[:effects/save [:orders :cancel-error] "Enable trading before cancelling orders."]]

      (map? request)
      [[:effects/api-cancel-order request]]

      :else
      [[:effects/save [:orders :cancel-error] "Missing asset or order id."]])))
