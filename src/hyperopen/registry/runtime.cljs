(ns hyperopen.registry.runtime
  (:require [nexus.registry :as nxr]
            [hyperopen.runtime.validation :as runtime-validation]))

(def ^:private effect-bindings
  [[:effects/save :save]
   [:effects/save-many :save-many]
   [:effects/local-storage-set :local-storage-set]
   [:effects/local-storage-set-json :local-storage-set-json]
   [:effects/queue-asset-icon-status :queue-asset-icon-status]
   [:effects/push-state :push-state]
   [:effects/replace-state :replace-state]
   [:effects/init-websocket :init-websocket]
   [:effects/subscribe-active-asset :subscribe-active-asset]
   [:effects/subscribe-orderbook :subscribe-orderbook]
   [:effects/subscribe-trades :subscribe-trades]
   [:effects/subscribe-webdata2 :subscribe-webdata2]
   [:effects/fetch-candle-snapshot :fetch-candle-snapshot]
   [:effects/unsubscribe-active-asset :unsubscribe-active-asset]
   [:effects/unsubscribe-orderbook :unsubscribe-orderbook]
   [:effects/unsubscribe-trades :unsubscribe-trades]
   [:effects/unsubscribe-webdata2 :unsubscribe-webdata2]
   [:effects/connect-wallet :connect-wallet]
   [:effects/disconnect-wallet :disconnect-wallet]
   [:effects/enable-agent-trading :enable-agent-trading]
   [:effects/set-agent-storage-mode :set-agent-storage-mode]
   [:effects/copy-wallet-address :copy-wallet-address]
   [:effects/reconnect-websocket :reconnect-websocket]
   [:effects/refresh-websocket-health :refresh-websocket-health]
   [:effects/confirm-ws-diagnostics-reveal :confirm-ws-diagnostics-reveal]
   [:effects/copy-websocket-diagnostics :copy-websocket-diagnostics]
   [:effects/ws-reset-subscriptions :ws-reset-subscriptions]
   [:effects/fetch-asset-selector-markets :fetch-asset-selector-markets]
   [:effects/api-fetch-user-funding-history :api-fetch-user-funding-history]
   [:effects/api-fetch-historical-orders :api-fetch-historical-orders]
   [:effects/export-funding-history-csv :export-funding-history-csv]
   [:effects/api-submit-order :api-submit-order]
   [:effects/api-cancel-order :api-cancel-order]
   [:effects/api-load-user-data :api-load-user-data]])

(defn registered-effect-ids
  []
  (->> effect-bindings
       (map first)
       set))

(def ^:private action-bindings
  [[:actions/init-websockets :init-websockets]
   [:actions/subscribe-to-asset :subscribe-to-asset]
   [:actions/subscribe-to-webdata2 :subscribe-to-webdata2]
   [:actions/connect-wallet :connect-wallet-action]
   [:actions/disconnect-wallet :disconnect-wallet-action]
   [:actions/enable-agent-trading :enable-agent-trading-action]
   [:actions/set-agent-storage-mode :set-agent-storage-mode-action]
   [:actions/copy-wallet-address :copy-wallet-address-action]
   [:actions/reconnect-websocket :reconnect-websocket-action]
   [:actions/toggle-ws-diagnostics :toggle-ws-diagnostics]
   [:actions/close-ws-diagnostics :close-ws-diagnostics]
   [:actions/toggle-ws-diagnostics-sensitive :toggle-ws-diagnostics-sensitive]
   [:actions/ws-diagnostics-reconnect-now :ws-diagnostics-reconnect-now]
   [:actions/ws-diagnostics-copy :ws-diagnostics-copy]
   [:actions/set-show-surface-freshness-cues :set-show-surface-freshness-cues]
   [:actions/toggle-show-surface-freshness-cues :toggle-show-surface-freshness-cues]
   [:actions/ws-diagnostics-reset-market-subscriptions :ws-diagnostics-reset-market-subscriptions]
   [:actions/ws-diagnostics-reset-orders-subscriptions :ws-diagnostics-reset-orders-subscriptions]
   [:actions/ws-diagnostics-reset-all-subscriptions :ws-diagnostics-reset-all-subscriptions]
   [:actions/toggle-asset-dropdown :toggle-asset-dropdown]
   [:actions/close-asset-dropdown :close-asset-dropdown]
   [:actions/select-asset :select-asset]
   [:actions/update-asset-search :update-asset-search]
   [:actions/update-asset-selector-sort :update-asset-selector-sort]
   [:actions/toggle-asset-selector-strict :toggle-asset-selector-strict]
   [:actions/toggle-asset-favorite :toggle-asset-favorite]
   [:actions/set-asset-selector-favorites-only :set-asset-selector-favorites-only]
   [:actions/set-asset-selector-tab :set-asset-selector-tab]
   [:actions/handle-asset-selector-shortcut :handle-asset-selector-shortcut]
   [:actions/set-asset-selector-scroll-top :set-asset-selector-scroll-top]
   [:actions/increase-asset-selector-render-limit :increase-asset-selector-render-limit]
   [:actions/show-all-asset-selector-markets :show-all-asset-selector-markets]
   [:actions/maybe-increase-asset-selector-render-limit :maybe-increase-asset-selector-render-limit]
   [:actions/refresh-asset-markets :refresh-asset-markets]
   [:actions/mark-loaded-asset-icon :mark-loaded-asset-icon]
   [:actions/mark-missing-asset-icon :mark-missing-asset-icon]
   [:actions/toggle-timeframes-dropdown :toggle-timeframes-dropdown]
   [:actions/select-chart-timeframe :select-chart-timeframe]
   [:actions/toggle-chart-type-dropdown :toggle-chart-type-dropdown]
   [:actions/select-chart-type :select-chart-type]
   [:actions/toggle-indicators-dropdown :toggle-indicators-dropdown]
   [:actions/update-indicators-search :update-indicators-search]
   [:actions/toggle-orderbook-size-unit-dropdown :toggle-orderbook-size-unit-dropdown]
   [:actions/select-orderbook-size-unit :select-orderbook-size-unit]
   [:actions/toggle-orderbook-price-aggregation-dropdown :toggle-orderbook-price-aggregation-dropdown]
   [:actions/select-orderbook-price-aggregation :select-orderbook-price-aggregation]
   [:actions/select-orderbook-tab :select-orderbook-tab]
   [:actions/add-indicator :add-indicator]
   [:actions/remove-indicator :remove-indicator]
   [:actions/update-indicator-period :update-indicator-period]
   [:actions/show-volume-indicator :show-volume-indicator]
   [:actions/hide-volume-indicator :hide-volume-indicator]
   [:actions/select-account-info-tab :select-account-info-tab]
   [:actions/set-funding-history-filters :set-funding-history-filters]
   [:actions/toggle-funding-history-filter-open :toggle-funding-history-filter-open]
   [:actions/toggle-funding-history-filter-coin :toggle-funding-history-filter-coin]
   [:actions/reset-funding-history-filter-draft :reset-funding-history-filter-draft]
   [:actions/apply-funding-history-filters :apply-funding-history-filters]
   [:actions/view-all-funding-history :view-all-funding-history]
   [:actions/export-funding-history-csv :export-funding-history-csv]
   [:actions/set-funding-history-page-size :set-funding-history-page-size]
   [:actions/set-funding-history-page :set-funding-history-page]
   [:actions/next-funding-history-page :next-funding-history-page]
   [:actions/prev-funding-history-page :prev-funding-history-page]
   [:actions/set-funding-history-page-input :set-funding-history-page-input]
   [:actions/apply-funding-history-page-input :apply-funding-history-page-input]
   [:actions/handle-funding-history-page-input-keydown :handle-funding-history-page-input-keydown]
   [:actions/set-trade-history-page-size :set-trade-history-page-size]
   [:actions/set-trade-history-page :set-trade-history-page]
   [:actions/next-trade-history-page :next-trade-history-page]
   [:actions/prev-trade-history-page :prev-trade-history-page]
   [:actions/set-trade-history-page-input :set-trade-history-page-input]
   [:actions/apply-trade-history-page-input :apply-trade-history-page-input]
   [:actions/handle-trade-history-page-input-keydown :handle-trade-history-page-input-keydown]
   [:actions/sort-trade-history :sort-trade-history]
   [:actions/sort-positions :sort-positions]
   [:actions/sort-balances :sort-balances]
   [:actions/sort-open-orders :sort-open-orders]
   [:actions/sort-funding-history :sort-funding-history]
   [:actions/sort-order-history :sort-order-history]
   [:actions/toggle-order-history-filter-open :toggle-order-history-filter-open]
   [:actions/set-order-history-status-filter :set-order-history-status-filter]
   [:actions/set-order-history-page-size :set-order-history-page-size]
   [:actions/set-order-history-page :set-order-history-page]
   [:actions/next-order-history-page :next-order-history-page]
   [:actions/prev-order-history-page :prev-order-history-page]
   [:actions/set-order-history-page-input :set-order-history-page-input]
   [:actions/apply-order-history-page-input :apply-order-history-page-input]
   [:actions/handle-order-history-page-input-keydown :handle-order-history-page-input-keydown]
   [:actions/refresh-order-history :refresh-order-history]
   [:actions/set-hide-small-balances :set-hide-small-balances]
   [:actions/select-order-entry-mode :select-order-entry-mode]
   [:actions/select-pro-order-type :select-pro-order-type]
   [:actions/toggle-pro-order-type-dropdown :toggle-pro-order-type-dropdown]
   [:actions/close-pro-order-type-dropdown :close-pro-order-type-dropdown]
   [:actions/handle-pro-order-type-dropdown-keydown :handle-pro-order-type-dropdown-keydown]
   [:actions/toggle-size-unit-dropdown :toggle-size-unit-dropdown]
   [:actions/close-size-unit-dropdown :close-size-unit-dropdown]
   [:actions/handle-size-unit-dropdown-keydown :handle-size-unit-dropdown-keydown]
   [:actions/set-order-ui-leverage :set-order-ui-leverage]
   [:actions/set-order-size-percent :set-order-size-percent]
   [:actions/set-order-size-display :set-order-size-display]
   [:actions/set-order-size-input-mode :set-order-size-input-mode]
   [:actions/focus-order-price-input :focus-order-price-input]
   [:actions/blur-order-price-input :blur-order-price-input]
   [:actions/set-order-price-to-mid :set-order-price-to-mid]
   [:actions/toggle-order-tpsl-panel :toggle-order-tpsl-panel]
   [:actions/update-order-form :update-order-form]
   [:actions/submit-order :submit-order]
   [:actions/cancel-order :cancel-order]
   [:actions/load-user-data :load-user-data]
   [:actions/set-funding-modal :set-funding-modal]
   [:actions/navigate :navigate]])

(defn registered-action-ids
  []
  (->> action-bindings
       (map first)
       set))

(defn- require-handler
  [handlers handler-key kind id]
  (let [handler (get handlers handler-key)]
    (if (fn? handler)
      handler
      (throw (js/Error.
              (str "Missing " (name kind) " handler " handler-key " for " id))))))

(defn register-effects!
  [handlers]
  (doseq [[effect-id handler-key] effect-bindings]
    (nxr/register-effect!
     effect-id
     (runtime-validation/wrap-effect-handler
      effect-id
      (require-handler handlers handler-key :effect effect-id)))))

(defn register-actions!
  [handlers]
  (doseq [[action-id handler-key] action-bindings]
    (nxr/register-action!
     action-id
     (runtime-validation/wrap-action-handler
      action-id
      (require-handler handlers handler-key :action action-id)))))

(defn register-system-state!
  []
  (nxr/register-system->state! deref))

(defn register-placeholders!
  []
  (nxr/register-placeholder! :event.target/value
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-target .-value)))

  (nxr/register-placeholder! :event.target/checked
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-target .-checked)))

  (nxr/register-placeholder! :event/key
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-key)))

  (nxr/register-placeholder! :event/metaKey
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-metaKey)))

  (nxr/register-placeholder! :event/ctrlKey
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-ctrlKey)))

  (nxr/register-placeholder! :event.target/scrollTop
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-target .-scrollTop)))

  (nxr/register-placeholder! :event/timeStamp
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-timeStamp))))
