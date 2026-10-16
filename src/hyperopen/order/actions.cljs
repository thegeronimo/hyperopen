(ns hyperopen.order.actions
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.margin-rec.state :as margin-rec-state]
            [hyperopen.order.cancel-visible-confirmation :as cancel-visible-confirmation]
            [hyperopen.order.submit-confirmation :as submit-confirmation]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.order.outcome-side-selection :as outcome-side-selection]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.trading.order-form-transitions :as transitions]
            [hyperopen.trading.orderbook-price-pick :as orderbook-price-pick]))

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

(defn toggle-outcome-option-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/toggle-outcome-option-dropdown state))]
    [[:effects/save-many [[[:order-form-ui :outcome-option-dropdown-open?]
                           (boolean (:outcome-option-dropdown-open? ui-state))]
                          [[:order-form-ui :outcome-option-query]
                           (:outcome-option-query ui-state)]]]]))

(defn close-outcome-option-dropdown [state]
  (let [ui-state (:order-form-ui (transitions/close-outcome-option-dropdown state))]
    [[:effects/save-many [[[:order-form-ui :outcome-option-dropdown-open?]
                           (boolean (:outcome-option-dropdown-open? ui-state))]
                          [[:order-form-ui :outcome-option-query]
                           (:outcome-option-query ui-state)]]]]))

(defn set-outcome-option-query [state query]
  (let [ui-state (:order-form-ui (transitions/set-outcome-option-query state query))]
    [[:effects/save-many [[[:order-form-ui :outcome-option-dropdown-open?]
                           (boolean (:outcome-option-dropdown-open? ui-state))]
                          [[:order-form-ui :outcome-option-query]
                           (:outcome-option-query ui-state)]]]]))

(defn handle-outcome-option-dropdown-keydown [state key]
  (if-let [transition (transitions/handle-outcome-option-dropdown-keydown state key)]
    (let [ui-state (:order-form-ui transition)]
      [[:effects/save-many [[[:order-form-ui :outcome-option-dropdown-open?]
                             (boolean (:outcome-option-dropdown-open? ui-state))]
                            [[:order-form-ui :outcome-option-query]
                             (:outcome-option-query ui-state)]]]])
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

(defn set-order-price-from-orderbook [state price]
  (if-let [t (orderbook-price-pick/set-order-price-from-orderbook state price)]
    (transition-save-many state t) []))

(defn toggle-order-tpsl-panel [state]
  (if-let [transition (transitions/toggle-order-tpsl-panel state)]
    (transition-save-many state transition)
    []))

(defn update-order-form [state path value]
  (let [transition (transitions/update-order-form state path value)]
    (or (outcome-side-selection/maybe-switch-side-market state path value transition) (transition-save-many state transition))))
(defn- current-order-feedback-toasts
  [state]
  (let [toasts (->> (or (get-in state [:ui :toasts]) [])
                    (filter map?)
                    vec)
        legacy-toast (get-in state [:ui :toast])]
    (if (seq toasts)
      toasts
      (if (map? legacy-toast)
        [legacy-toast]
        []))))

(defn dismiss-order-feedback-toast
  [state toast-id]
  (let [current-toasts (current-order-feedback-toasts state)
        remaining-toasts (if (some? toast-id)
                           (->> current-toasts
                                (remove #(= toast-id (:id %)))
                                vec)
                           [])
        latest-toast (some-> (peek remaining-toasts)
                             (dissoc :id))]
    [[:effects/save-many [[[:ui :toasts] remaining-toasts]
                          [[:ui :toast] latest-toast]]]]))

(defn- set-order-feedback-toast-expanded
  [state toast-id expanded?]
  (let [current-toasts (current-order-feedback-toasts state)
        next-toasts (mapv (fn [toast]
                            (if (= toast-id (:id toast))
                              (if expanded?
                                (assoc toast
                                       :expanded? true
                                       :auto-timeout? false)
                                (-> toast
                                    (assoc :expanded? false)
                                    (dissoc :auto-timeout?)))
                              toast))
                          current-toasts)
        latest-toast (some-> (peek next-toasts)
                             (dissoc :id))]
    (cond-> [[:effects/save-many [[[:ui :toasts] next-toasts]
                                   [[:ui :toast] latest-toast]]]]
      expanded? (conj [:effects/clear-order-feedback-toast-timeout toast-id]))))

(defn expand-order-feedback-toast
  [state toast-id]
  (set-order-feedback-toast-expanded state toast-id true))

(defn collapse-order-feedback-toast
  [state toast-id]
  (set-order-feedback-toast-expanded state toast-id false))

(defn- open-enable-trading-recovery-effects
  [error-message]
  [[:effects/save-many [[[:order-form-runtime :error] nil]
                        [[:wallet :agent :error] error-message]
                        [[:wallet :agent :recovery-modal-open?] true]]]])

(defn- agent-status-message
  [agent-status submit-message]
  (case agent-status
    :locked "Unlock trading before submitting orders."
    :unlocking "Awaiting passkey before submitting orders."
    submit-message))

(defn submit-unlocked-order-request
  ([_state request]
   (submit-unlocked-order-request _state request nil))
  ([_state request path-values]
   (cond-> []
     (seq path-values) (conj [:effects/save-many path-values])
     (map? request) (conj [:effects/api-submit-order request]))))

(declare cancel-order-projection-effects)

(defn submit-unlocked-cancel-request
  ([_state request]
   (submit-unlocked-cancel-request _state request nil))
  ([state request path-values]
   (cond-> []
     (seq path-values) (conj [:effects/save-many path-values])
     (map? request) (into (cancel-order-projection-effects state request))
     (map? request) (conj [:effects/api-cancel-order request]))))

(defn- unlock-after-success-payload
  [actions]
  (when (seq actions)
    {:after-success-actions (vec actions)}))

(defn- unlock-agent-trading-effect
  [after-success-actions]
  (let [payload (unlock-after-success-payload after-success-actions)]
    (cond-> [:effects/unlock-agent-trading]
      payload (conj payload))))

(defn- locked-submit-effects
  [request path-values]
  [[:effects/save-many [[[:order-form-runtime :error] nil]
                        [[:wallet :agent :status] :unlocking]
                        [[:wallet :agent :error] nil]]]
   (unlock-agent-trading-effect
    [[:actions/submit-unlocked-order-request request path-values]])])

(defn- locked-cancel-effects
  [request]
  [[:effects/save-many [[[:orders :cancel-error] nil]
                        [[:wallet :agent :status] :unlocking]
                        [[:wallet :agent :error] nil]]]
   (unlock-agent-trading-effect
    [[:actions/submit-unlocked-cancel-request request]])])

(def ^:private confirm-open-order-message
  "Submit this order?\n\nDisable open-order confirmation in Trading settings if you prefer one-click submits.")

(def ^:private order-submission-confirmation-path
  [:order-submit-confirmation])

(defn dismiss-order-submission-confirmation
  [_state]
  [[:effects/save order-submission-confirmation-path
    (submit-confirmation/default-state)]])

(defn handle-order-submission-confirmation-keydown
  [state key]
  (if (= key "Escape")
    (dismiss-order-submission-confirmation state)
    []))

(defn confirm-order-submission
  [state]
  (let [confirmation (or (:order-submit-confirmation state)
                         (submit-confirmation/default-state))
        request (:request confirmation)
        path-values (vec (:path-values confirmation))
        projection-values (conj path-values
                                [order-submission-confirmation-path
                                 (submit-confirmation/default-state)])]
    (if (and (submit-confirmation/open? confirmation)
             (map? request))
      [[:effects/save-many projection-values]
       [:effects/api-submit-order request]]
      (dismiss-order-submission-confirmation state))))

(defn- parse-order-num
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else js/NaN)]
    (when (and (number? n) (js/isFinite n) (pos? n))
      n)))

(defn margin-rec-intent-save
  "When auto top-up is enabled and this is a single risk-increasing isolated
  perp order, record a one-shot intent keyed by the position; the background
  intent pass tops the filled position up to the modeled recommendation.
  Returns a projection effect or nil."
  [state form request]
  (when (and (trading-settings/margin-rec-auto-topup? state)
             (= :isolated (:margin-mode form))
             (= "order" (get-in request [:action :type])))
    (let [orders (get-in request [:action :orders])
          order (first orders)
          coin (get-in state [:active-market :coin])
          size (parse-order-num (:s order))]
      (when (and (= 1 (count orders))
                 (string? coin)
                 (seq coin)
                 size
                 (not (true? (:r order))))
        (let [position-key (margin-rec-state/position-key-for-coin coin)
              current-szi (or (some #(when (= position-key (:position-key %))
                                       (:szi %))
                                    (margin-rec-state/isolated-positions state))
                              0)
              expected (+ current-szi (if (true? (:b order)) size (- size)))
              expected-size (js/Math.abs expected)
              price (parse-order-num (:p order))
              order-notional (when price (* size price))]
          (when (> expected-size (js/Math.abs current-szi))
            (let [intent (margin-rec-state/make-intent-draft
                          {:position-key position-key
                           :coin coin
                           :dex (margin-rec-state/dex-for-coin coin)
                           :expected-size expected-size
                           :target-equity nil
                           :max-add order-notional
                           :source :trade})]
              [:effects/save
               margin-rec-state/intents-path
               (assoc (get-in state margin-rec-state/intents-path)
                      position-key
                      intent)])))))))

(defn submit-order [state]
  (let [spectate-mode-message (account-context/mutations-blocked-message state)
        raw-form (trading/order-form-draft state)
        agent-status (get-in state [:wallet :agent :status])
        agent-ready? (= :ready agent-status)
        submit-policy (trading/submit-policy state raw-form {:mode :submit
                                                             :agent-ready? agent-ready?
                                                             :agent-unavailable-message
                                                             (agent-status-message
                                                              agent-status
                                                              "Enable trading before submitting orders.")})
        form (:form submit-policy)
        request (:request submit-policy)
        reason (:reason submit-policy)
        error-message (:error-message submit-policy)]
    (cond
      (seq spectate-mode-message)
      [[:effects/save [:order-form-runtime :error] spectate-mode-message]]

      (= :agent-not-ready reason)
      (if (= :ready agent-status)
        []
        (case agent-status
          :locked (let [persisted-form (trading/persist-order-form form)
                        persisted-ui (next-order-form-ui-state state form nil)
                        path-values [[[:order-form-runtime :error] nil]
                                     [[:order-form] persisted-form]
                                     [[:order-form-ui] persisted-ui]]
                        intent-save (margin-rec-intent-save state form request)]
                    (into (if intent-save [intent-save] [])
                          (locked-submit-effects request path-values)))
          :unlocking [[:effects/save [:order-form-runtime :error] error-message]]
          (open-enable-trading-recovery-effects error-message)))

      reason
      [[:effects/save [:order-form-runtime :error] error-message]]

      :else
      (let [persisted-form (trading/persist-order-form form)
            persisted-ui (next-order-form-ui-state state form nil)
            path-values [[[:order-form-runtime :error] nil]
                         [[:order-form] persisted-form]
                         [[:order-form-ui] persisted-ui]]
            intent-save (margin-rec-intent-save state form request)]
        (if (trading-settings/confirm-open-orders? state)
          (into (if intent-save [intent-save] [])
                [[:effects/confirm-api-submit-order {:variant :open-order
                                                     :message confirm-open-order-message
                                                     :request request
                                                     :path-values path-values}]])
          (into (if intent-save [intent-save] [])
                [[:effects/save [:order-form-runtime :error] nil]
                 [:effects/save [:order-form] persisted-form]
                 [:effects/save [:order-form-ui] persisted-ui]
                 [:effects/api-submit-order request]]))))))

(defn prune-canceled-open-orders
  [state request]
  (order-effects/prune-canceled-open-orders state request))

(defn- cancel-request-oids
  [request]
  (->> (get-in request [:action :cancels] [])
       (keep trading-api/resolve-cancel-order-oid)
       set))

(defn- next-pending-cancel-oids
  [state cancel-oids]
  (when (seq cancel-oids)
    (into (if-let [pending (get-in state [:orders :pending-cancel-oids])]
            (if (set? pending)
              pending
              (set pending))
            #{})
          cancel-oids)))

(defn- cancel-order-projection-effects
  [state request]
  (let [cancel-oids (cancel-request-oids request)
        path-values (cond-> [[[:orders :cancel-error] nil]]
                      (seq cancel-oids)
                      (conj [[:orders :pending-cancel-oids]
                             (next-pending-cancel-oids state cancel-oids)]))]
    [[:effects/save-many path-values]]))

(defn- emit-cancel-effects
  [state request missing-request-message]
  (let [spectate-mode-message (account-context/mutations-blocked-message state)
        agent-status (get-in state [:wallet :agent :status])
        agent-ready? (= :ready agent-status)]
    (cond
      (seq spectate-mode-message)
      [[:effects/save [:orders :cancel-error] spectate-mode-message]]

      (not agent-ready?)
      (case agent-status
        :locked (if (map? request)
                  (locked-cancel-effects request)
                  [[:effects/save [:orders :cancel-error] missing-request-message]])
        :unlocking [[:effects/save [:orders :cancel-error]
                     "Awaiting passkey before cancelling orders."]]
        [[:effects/save [:orders :cancel-error]
          "Enable trading before cancelling orders."]])

      (not (map? request))
      [[:effects/save [:orders :cancel-error] missing-request-message]]

      :else
      (into (cancel-order-projection-effects state request)
            [[:effects/api-cancel-order request]]))))

(def ^:private cancel-visible-confirmation-path
  [:account-info :open-orders :cancel-visible-confirmation])

(defn confirm-cancel-visible-open-orders
  [_state orders trigger-bounds]
  (let [orders* (->> (or orders [])
                     (filter map?)
                     vec)]
    (if (seq orders*)
      [[:effects/save cancel-visible-confirmation-path
        (cancel-visible-confirmation/open-state orders* trigger-bounds)]]
      [])))

(defn close-cancel-visible-open-orders-confirmation
  [_state]
  [[:effects/save cancel-visible-confirmation-path
    (cancel-visible-confirmation/default-state)]])

(defn handle-cancel-visible-open-orders-confirmation-keydown
  [state key]
  (if (= key "Escape")
    (close-cancel-visible-open-orders-confirmation state)
    []))

(defn submit-cancel-visible-open-orders-confirmation
  [state]
  (let [orders* (->> (get-in state (conj cancel-visible-confirmation-path :orders))
                     (filter map?)
                     vec)]
    (if (seq orders*)
      (into [[:effects/save cancel-visible-confirmation-path
              (cancel-visible-confirmation/default-state)]]
            (emit-cancel-effects state
                                 (trading-api/build-cancel-orders-request state orders*)
                                 "Some visible orders are missing asset or order id."))
      (close-cancel-visible-open-orders-confirmation state))))

(defn cancel-visible-open-orders
  [state orders]
  (let [orders* (->> (or orders [])
                     (filter map?)
                     vec)]
    (if (seq orders*)
      (emit-cancel-effects state
                           (trading-api/build-cancel-orders-request state orders*)
                           "Some visible orders are missing asset or order id.")
      [])))

(defn cancel-order [state order]
  (emit-cancel-effects state
                       (trading-api/build-cancel-order-request state order)
                       "Missing asset or order id."))

(defn cancel-twap [state twap]
  (emit-cancel-effects state
                       (trading-api/build-cancel-twap-request state twap)
                       "Missing TWAP asset or TWAP id."))
