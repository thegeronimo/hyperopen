(ns hyperopen.schema.contracts
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
            [hyperopen.state.trading.order-form-key-policy :as order-form-key-policy]))

(defn validation-enabled?
  []
  ^boolean goog.DEBUG)

(defn- non-empty-string?
  [value]
  (and (string? value)
       (seq (str/trim value))))

(defn- keyword-path?
  [path]
  (and (vector? path)
       (every? keyword? path)))

(defn- parse-int-value
  [value]
  (cond
    (integer? value) value

    (and (number? value)
         (not (js/isNaN value))
         (= value (js/Math.floor value)))
    value

    (string? value)
    (let [text (str/trim value)]
      (when (re-matches #"[+-]?\\d+" text)
        (js/parseInt text 10)))

    :else nil))

(defn- parse-number-value
  [value]
  (cond
    (number? value)
    (when-not (js/isNaN value)
      value)

    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        (let [parsed (js/Number text)]
          (when (and (number? parsed)
                     (not (js/isNaN parsed)))
            parsed))))

    :else nil))

(defn- parseable-int?
  [value]
  (some? (parse-int-value value)))

(defn- parseable-number?
  [value]
  (some? (parse-number-value value)))

(defn- non-negative-int?
  [value]
  (when-let [parsed (parse-int-value value)]
    (>= parsed 0)))

(defn- positive-int?
  [value]
  (when-let [parsed (parse-int-value value)]
    (> parsed 0)))

(defn- fetch-candle-snapshot-args?
  [args]
  (and (vector? args)
       (even? (count args))
       (every? keyword? (take-nth 2 args))
       (let [opts (apply hash-map args)]
         (and (every? #{:coin :interval :bars} (keys opts))
              (or (not (contains? opts :coin))
                  (non-empty-string? (:coin opts)))
              (or (not (contains? opts :interval))
                  (keyword? (:interval opts)))
              (or (not (contains? opts :bars))
                  (positive-int? (:bars opts)))))))

(s/def ::any-args vector?)
(s/def ::non-empty-string non-empty-string?)
(s/def ::state-path keyword-path?)
(s/def ::path-value (s/tuple ::state-path any?))
(s/def ::path-values (s/and vector?
                            (s/coll-of ::path-value :kind vector?)))
(s/def ::intish parseable-int?)
(s/def ::numberish parseable-number?)
(s/def ::non-negative-int non-negative-int?)
(s/def ::positive-int positive-int?)

(s/def ::market-key ::non-empty-string)
(s/def ::icon-status #{:loaded :missing})
(s/def ::asset-icon-status (s/keys :req-un [::market-key ::icon-status]))

(s/def ::action map?)
(s/def ::nonce (s/and integer?
                      #(>= % 0)))
(s/def ::r ::non-empty-string)
(s/def ::s ::non-empty-string)
(s/def ::v integer?)
(s/def ::signature (s/keys :req-un [::r ::s ::v]))
(s/def ::signed-exchange-payload
  (s/keys :req-un [::action ::nonce ::signature]))

(s/def ::exchange-response map?)

(s/def ::channel ::non-empty-string)
(s/def ::provider-message
  (s/keys :req-un [::channel]))

(s/def ::save-args (s/tuple ::state-path any?))
(s/def ::save-many-args (s/tuple ::path-values))
(s/def ::storage-args (s/tuple ::non-empty-string any?))
(s/def ::queue-asset-icon-status-args (s/tuple ::asset-icon-status))
(s/def ::path-args (s/tuple ::non-empty-string))
(s/def ::coin-args (s/tuple ::non-empty-string))
(s/def ::address-args (s/tuple ::non-empty-string))
(s/def ::optional-address-args (s/tuple (s/nilable ::non-empty-string)))
(s/def ::address-and-optional-address-args
  (s/tuple ::non-empty-string (s/nilable ::non-empty-string)))
(s/def ::set-agent-storage-mode-args (s/tuple keyword?))
(s/def ::enable-agent-trading-args (s/tuple map?))
(s/def ::api-submit-request (s/keys :req-un [::action]))
(s/def ::api-submit-order-args (s/tuple ::api-submit-request))
(s/def ::api-cancel-order-args (s/tuple ::api-submit-request))
(s/def ::api-submit-position-tpsl-args (s/tuple ::api-submit-request))
(s/def ::api-submit-position-margin-args (s/tuple ::api-submit-request))

(defn- fetch-asset-selector-markets-args?
  [args]
  (or (empty? args)
      (and (= 1 (count args))
           (map? (first args)))))

(s/def ::fetch-asset-selector-markets-args fetch-asset-selector-markets-args?)
(s/def ::fetch-candle-snapshot-args fetch-candle-snapshot-args?)
(s/def ::request-id ::non-negative-int)
(s/def ::request-id-args (s/tuple ::request-id))
(s/def ::map-vector (s/and vector?
                           (s/coll-of map? :kind vector?)))
(s/def ::export-funding-history-csv-args (s/tuple ::map-vector))

(s/def ::group #{:market_data :orders_oms :all})
(s/def ::source keyword?)
(s/def ::ws-reset-request
  (s/keys :opt-un [::group ::source]))
(s/def ::ws-reset-subscriptions-args (s/tuple ::ws-reset-request))
(s/def ::no-args empty?)

(s/def ::keyword-or-string (s/or :keyword keyword?
                                 :string ::non-empty-string))
(s/def ::tab ::keyword-or-string)
(s/def ::market-or-coin (s/or :coin ::non-empty-string
                              :market map?))
(s/def ::dropdown-target (s/or :keyword keyword?
                               :coin ::non-empty-string))
(s/def ::dropdown-target-args (s/tuple ::dropdown-target))
(s/def ::booleanish #(or (nil? %) (boolean? %)))
(s/def ::boolean-args (s/tuple boolean?))
(s/def ::keyword-args (s/tuple keyword?))
(s/def ::keyword-or-string-args (s/tuple ::keyword-or-string))
(s/def ::optional-string-args (s/tuple (s/nilable string?)))
(s/def ::tab-and-input-args (s/tuple ::keyword-or-string any?))
(s/def ::single-input-args (s/tuple any?))
(s/def ::single-or-double-input-args (s/or :single (s/tuple any?)
                                           :double (s/tuple any? any?)))
(s/def ::tab-args (s/tuple ::tab))
(s/def ::asset-selector-shortcut-market-keys
  (s/and vector?
         (s/coll-of ::market-key :kind vector?)))
(s/def ::asset-selector-shortcut-args
  (s/tuple (s/nilable string?) ::booleanish ::booleanish (s/nilable ::asset-selector-shortcut-market-keys)))
(s/def ::market-or-coin-args (s/tuple ::market-or-coin))
(s/def ::market-key-args (s/tuple ::market-key))
(s/def ::max-page-args (s/tuple (s/nilable ::intish)))
(s/def ::page-and-max-page-args (s/tuple ::intish (s/nilable ::intish)))
(s/def ::sort-column-args (s/tuple ::non-empty-string))
(s/def ::vault-detail-activity-sort-args (s/tuple ::keyword-or-string ::non-empty-string))
(s/def ::key-args (s/tuple ::non-empty-string))
(s/def ::keydown-with-max-page-args (s/tuple ::non-empty-string (s/nilable ::intish)))
(s/def ::keydown-with-optional-coin-args
  (s/tuple ::non-empty-string (s/nilable ::non-empty-string)))
(s/def ::funding-history-filter-path (s/or :path ::state-path
                                          :key keyword?))
(s/def ::funding-history-filter-args (s/tuple ::funding-history-filter-path any?))
(s/def ::add-indicator-args (s/tuple keyword? map?))
(s/def ::update-indicator-period-args (s/tuple keyword? any?))
(s/def ::cancel-order-args (s/tuple map?))
(s/def ::funding-modal-args (s/tuple any?))
(s/def ::left number?)
(s/def ::right number?)
(s/def ::top number?)
(s/def ::bottom number?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::viewport-width number?)
(s/def ::viewport-height number?)
(s/def ::position-tpsl-anchor
  (s/keys :opt-un [::left
                   ::right
                   ::top
                   ::bottom
                   ::width
                   ::height
                   ::viewport-width
                   ::viewport-height]))
(s/def ::portfolio-chart-hover-args
  (s/tuple (s/nilable ::numberish)
           (s/nilable ::position-tpsl-anchor)
           ::intish))
(s/def ::vault-chart-hover-args
  (s/tuple (s/nilable ::numberish)
           (s/nilable ::position-tpsl-anchor)
           ::intish))
(s/def ::position-tpsl-open-args
  (s/or :position-only (s/tuple map?)
        :position-and-anchor (s/tuple map? any?)))
(s/def ::position-tpsl-modal-field-args (s/tuple ::state-path any?))
(s/def ::position-reduce-open-args
  (s/or :position-only (s/tuple map?)
        :position-and-anchor (s/tuple map? any?)))
(s/def ::position-reduce-popover-field-args (s/tuple ::state-path any?))
(s/def ::position-margin-open-args
  (s/or :position-only (s/tuple map?)
        :position-and-anchor (s/tuple map? any?)))
(s/def ::position-margin-modal-field-args (s/tuple ::state-path any?))
(s/def ::ws-reset-source-args (s/or :none ::no-args
                                    :source (s/tuple ::source)))

(s/def ::action-id (s/and keyword?
                          #(= "actions" (namespace %))))
(s/def ::effect-id (s/and keyword?
                          #(= "effects" (namespace %))))

(def ^:private action-args-spec-by-id
  {:actions/init-websockets ::no-args
   :actions/subscribe-to-asset ::coin-args
   :actions/subscribe-to-webdata2 ::address-args
   :actions/connect-wallet ::no-args
   :actions/disconnect-wallet ::no-args
   :actions/enable-agent-trading ::no-args
   :actions/set-agent-storage-mode ::set-agent-storage-mode-args
   :actions/copy-wallet-address ::no-args
   :actions/reconnect-websocket ::no-args
   :actions/toggle-ws-diagnostics ::no-args
   :actions/close-ws-diagnostics ::no-args
   :actions/toggle-ws-diagnostics-sensitive ::no-args
   :actions/ws-diagnostics-reconnect-now ::no-args
   :actions/ws-diagnostics-copy ::no-args
   :actions/set-show-surface-freshness-cues ::boolean-args
   :actions/toggle-show-surface-freshness-cues ::no-args
   :actions/ws-diagnostics-reset-market-subscriptions ::ws-reset-source-args
   :actions/ws-diagnostics-reset-orders-subscriptions ::ws-reset-source-args
   :actions/ws-diagnostics-reset-all-subscriptions ::ws-reset-source-args
   :actions/toggle-asset-dropdown ::dropdown-target-args
   :actions/close-asset-dropdown ::no-args
   :actions/select-asset ::market-or-coin-args
   :actions/update-asset-search ::single-input-args
   :actions/update-asset-selector-sort ::keyword-args
   :actions/toggle-asset-selector-strict ::no-args
   :actions/toggle-asset-favorite ::market-key-args
   :actions/set-asset-selector-favorites-only ::boolean-args
   :actions/set-asset-selector-tab ::tab-args
   :actions/handle-asset-selector-shortcut ::asset-selector-shortcut-args
   :actions/set-asset-selector-scroll-top ::single-input-args
   :actions/increase-asset-selector-render-limit ::no-args
   :actions/show-all-asset-selector-markets ::no-args
   :actions/maybe-increase-asset-selector-render-limit ::single-or-double-input-args
   :actions/refresh-asset-markets ::no-args
   :actions/mark-loaded-asset-icon ::market-key-args
   :actions/mark-missing-asset-icon ::market-key-args
   :actions/toggle-timeframes-dropdown ::no-args
   :actions/select-chart-timeframe ::keyword-args
   :actions/toggle-chart-type-dropdown ::no-args
   :actions/select-chart-type ::keyword-args
   :actions/toggle-indicators-dropdown ::no-args
   :actions/update-indicators-search ::single-input-args
   :actions/toggle-portfolio-summary-scope-dropdown ::no-args
   :actions/select-portfolio-summary-scope ::keyword-or-string-args
   :actions/toggle-portfolio-summary-time-range-dropdown ::no-args
   :actions/toggle-portfolio-performance-metrics-time-range-dropdown ::no-args
   :actions/select-portfolio-summary-time-range ::keyword-or-string-args
   :actions/select-portfolio-chart-tab ::keyword-or-string-args
   :actions/set-portfolio-account-info-tab ::tab-args
   :actions/set-portfolio-chart-hover ::portfolio-chart-hover-args
   :actions/clear-portfolio-chart-hover ::no-args
   :actions/set-portfolio-returns-benchmark-search ::single-input-args
   :actions/set-portfolio-returns-benchmark-suggestions-open ::boolean-args
   :actions/select-portfolio-returns-benchmark ::optional-string-args
   :actions/remove-portfolio-returns-benchmark ::coin-args
   :actions/handle-portfolio-returns-benchmark-search-keydown ::keydown-with-optional-coin-args
   :actions/clear-portfolio-returns-benchmark ::no-args
   :actions/toggle-orderbook-size-unit-dropdown ::no-args
   :actions/select-orderbook-size-unit ::keyword-or-string-args
   :actions/toggle-orderbook-price-aggregation-dropdown ::no-args
   :actions/select-orderbook-price-aggregation ::keyword-or-string-args
   :actions/select-orderbook-tab ::tab-args
   :actions/add-indicator ::add-indicator-args
   :actions/remove-indicator ::keyword-args
   :actions/update-indicator-period ::update-indicator-period-args
   :actions/show-volume-indicator ::no-args
   :actions/hide-volume-indicator ::no-args
   :actions/select-account-info-tab ::tab-args
   :actions/set-funding-history-filters ::funding-history-filter-args
   :actions/toggle-funding-history-filter-open ::no-args
   :actions/toggle-funding-history-filter-coin ::coin-args
   :actions/add-funding-history-filter-coin ::coin-args
   :actions/handle-funding-history-coin-search-keydown ::keydown-with-optional-coin-args
   :actions/reset-funding-history-filter-draft ::no-args
   :actions/apply-funding-history-filters ::no-args
   :actions/view-all-funding-history ::no-args
   :actions/export-funding-history-csv ::no-args
   :actions/set-funding-history-page-size ::single-input-args
   :actions/set-funding-history-page ::page-and-max-page-args
   :actions/next-funding-history-page ::max-page-args
   :actions/prev-funding-history-page ::max-page-args
   :actions/set-funding-history-page-input ::single-input-args
   :actions/apply-funding-history-page-input ::max-page-args
   :actions/handle-funding-history-page-input-keydown ::keydown-with-max-page-args
   :actions/set-trade-history-page-size ::single-input-args
   :actions/set-trade-history-page ::page-and-max-page-args
   :actions/next-trade-history-page ::max-page-args
   :actions/prev-trade-history-page ::max-page-args
   :actions/set-trade-history-page-input ::single-input-args
   :actions/apply-trade-history-page-input ::max-page-args
   :actions/handle-trade-history-page-input-keydown ::keydown-with-max-page-args
   :actions/sort-trade-history ::sort-column-args
   :actions/toggle-trade-history-direction-filter-open ::no-args
   :actions/set-trade-history-direction-filter ::keyword-or-string-args
   :actions/sort-positions ::sort-column-args
   :actions/toggle-positions-direction-filter-open ::no-args
   :actions/set-positions-direction-filter ::keyword-or-string-args
   :actions/sort-balances ::sort-column-args
   :actions/sort-open-orders ::sort-column-args
   :actions/toggle-open-orders-direction-filter-open ::no-args
   :actions/set-open-orders-direction-filter ::keyword-or-string-args
   :actions/sort-funding-history ::sort-column-args
   :actions/sort-order-history ::sort-column-args
   :actions/toggle-order-history-filter-open ::no-args
   :actions/set-order-history-status-filter ::keyword-or-string-args
   :actions/set-account-info-coin-search ::tab-and-input-args
   :actions/set-order-history-page-size ::single-input-args
   :actions/set-order-history-page ::page-and-max-page-args
   :actions/next-order-history-page ::max-page-args
   :actions/prev-order-history-page ::max-page-args
   :actions/set-order-history-page-input ::single-input-args
   :actions/apply-order-history-page-input ::max-page-args
   :actions/handle-order-history-page-input-keydown ::keydown-with-max-page-args
   :actions/refresh-order-history ::no-args
   :actions/set-hide-small-balances ::boolean-args
   :actions/open-position-tpsl-modal ::position-tpsl-open-args
   :actions/close-position-tpsl-modal ::no-args
   :actions/handle-position-tpsl-modal-keydown ::key-args
   :actions/set-position-tpsl-modal-field ::position-tpsl-modal-field-args
   :actions/set-position-tpsl-configure-amount ::boolean-args
   :actions/set-position-tpsl-limit-price ::boolean-args
   :actions/submit-position-tpsl ::no-args
   :actions/trigger-close-all-positions ::no-args
   :actions/open-position-reduce-popover ::position-reduce-open-args
   :actions/close-position-reduce-popover ::no-args
   :actions/handle-position-reduce-popover-keydown ::key-args
   :actions/set-position-reduce-popover-field ::position-reduce-popover-field-args
   :actions/set-position-reduce-size-percent ::single-input-args
   :actions/set-position-reduce-limit-price-to-mid ::no-args
   :actions/submit-position-reduce-close ::no-args
   :actions/open-position-margin-modal ::position-margin-open-args
   :actions/close-position-margin-modal ::no-args
   :actions/handle-position-margin-modal-keydown ::key-args
   :actions/set-position-margin-modal-field ::position-margin-modal-field-args
   :actions/set-position-margin-amount-percent ::single-input-args
   :actions/set-position-margin-amount-to-max ::no-args
   :actions/submit-position-margin-update ::no-args
   :actions/select-order-entry-mode ::keyword-or-string-args
   :actions/select-pro-order-type ::keyword-or-string-args
   :actions/toggle-pro-order-type-dropdown ::no-args
   :actions/close-pro-order-type-dropdown ::no-args
   :actions/handle-pro-order-type-dropdown-keydown ::key-args
   :actions/toggle-margin-mode-dropdown ::no-args
   :actions/close-margin-mode-dropdown ::no-args
   :actions/handle-margin-mode-dropdown-keydown ::key-args
   :actions/toggle-leverage-popover ::no-args
   :actions/close-leverage-popover ::no-args
   :actions/handle-leverage-popover-keydown ::key-args
   :actions/toggle-size-unit-dropdown ::no-args
   :actions/close-size-unit-dropdown ::no-args
   :actions/handle-size-unit-dropdown-keydown ::key-args
   :actions/toggle-tpsl-unit-dropdown ::no-args
   :actions/close-tpsl-unit-dropdown ::no-args
   :actions/handle-tpsl-unit-dropdown-keydown ::key-args
   :actions/toggle-tif-dropdown ::no-args
   :actions/close-tif-dropdown ::no-args
   :actions/handle-tif-dropdown-keydown ::key-args
   :actions/set-order-ui-leverage-draft ::single-input-args
   :actions/confirm-order-ui-leverage ::no-args
   :actions/set-order-ui-leverage ::single-input-args
   :actions/set-order-margin-mode ::keyword-or-string-args
   :actions/set-order-size-percent ::single-input-args
   :actions/set-order-size-display ::single-input-args
   :actions/set-order-size-input-mode ::keyword-or-string-args
   :actions/focus-order-price-input ::no-args
   :actions/blur-order-price-input ::no-args
   :actions/set-order-price-to-mid ::no-args
   :actions/toggle-order-tpsl-panel ::no-args
   :actions/update-order-form (s/tuple ::state-path any?)
   :actions/submit-order ::no-args
   :actions/cancel-order ::cancel-order-args
   :actions/load-user-data ::address-args
   :actions/set-funding-modal ::funding-modal-args
   :actions/load-vault-route ::path-args
   :actions/load-vaults ::no-args
   :actions/load-vault-detail ::address-args
   :actions/set-vaults-search-query ::single-input-args
   :actions/toggle-vaults-filter ::keyword-args
   :actions/set-vaults-snapshot-range ::keyword-or-string-args
   :actions/set-vaults-sort ::keyword-or-string-args
   :actions/set-vaults-user-page-size ::single-input-args
   :actions/toggle-vaults-user-page-size-dropdown ::no-args
   :actions/close-vaults-user-page-size-dropdown ::no-args
   :actions/set-vaults-user-page ::page-and-max-page-args
   :actions/next-vaults-user-page ::max-page-args
   :actions/prev-vaults-user-page ::max-page-args
   :actions/set-vault-detail-tab ::keyword-or-string-args
   :actions/set-vault-detail-activity-tab ::keyword-or-string-args
   :actions/sort-vault-detail-activity ::vault-detail-activity-sort-args
   :actions/toggle-vault-detail-activity-filter-open ::no-args
   :actions/close-vault-detail-activity-filter ::no-args
   :actions/set-vault-detail-activity-direction-filter ::keyword-or-string-args
   :actions/set-vault-detail-chart-series ::keyword-or-string-args
   :actions/set-vault-detail-returns-benchmark-search ::single-input-args
   :actions/set-vault-detail-returns-benchmark-suggestions-open ::boolean-args
   :actions/select-vault-detail-returns-benchmark ::optional-string-args
   :actions/remove-vault-detail-returns-benchmark ::coin-args
   :actions/handle-vault-detail-returns-benchmark-search-keydown ::keydown-with-optional-coin-args
   :actions/clear-vault-detail-returns-benchmark ::no-args
   :actions/set-vault-detail-chart-hover ::vault-chart-hover-args
   :actions/clear-vault-detail-chart-hover ::no-args
   :actions/navigate (s/or :path (s/tuple ::non-empty-string)
                           :path-and-opts (s/tuple ::non-empty-string map?))})

(def ^:private effect-args-spec-by-id
  {:effects/save ::save-args
   :effects/save-many ::save-many-args
   :effects/local-storage-set ::storage-args
   :effects/local-storage-set-json ::storage-args
   :effects/queue-asset-icon-status ::queue-asset-icon-status-args
   :effects/sync-asset-selector-active-ctx-subscriptions ::no-args
   :effects/push-state ::path-args
   :effects/replace-state ::path-args
   :effects/init-websocket ::no-args
   :effects/subscribe-active-asset ::coin-args
   :effects/subscribe-orderbook ::coin-args
   :effects/subscribe-trades ::coin-args
   :effects/subscribe-webdata2 ::address-args
   :effects/fetch-candle-snapshot ::fetch-candle-snapshot-args
   :effects/unsubscribe-active-asset ::coin-args
   :effects/unsubscribe-orderbook ::coin-args
   :effects/unsubscribe-trades ::coin-args
   :effects/unsubscribe-webdata2 ::address-args
   :effects/connect-wallet ::no-args
   :effects/disconnect-wallet ::no-args
   :effects/enable-agent-trading ::enable-agent-trading-args
   :effects/set-agent-storage-mode ::set-agent-storage-mode-args
   :effects/copy-wallet-address ::optional-address-args
   :effects/reconnect-websocket ::no-args
   :effects/refresh-websocket-health ::no-args
   :effects/confirm-ws-diagnostics-reveal ::no-args
   :effects/copy-websocket-diagnostics ::no-args
   :effects/ws-reset-subscriptions ::ws-reset-subscriptions-args
   :effects/fetch-asset-selector-markets ::fetch-asset-selector-markets-args
   :effects/api-fetch-user-funding-history ::request-id-args
   :effects/api-fetch-historical-orders ::request-id-args
   :effects/export-funding-history-csv ::export-funding-history-csv-args
   :effects/api-submit-order ::api-submit-order-args
   :effects/api-cancel-order ::api-cancel-order-args
   :effects/api-submit-position-tpsl ::api-submit-position-tpsl-args
   :effects/api-submit-position-margin ::api-submit-position-margin-args
   :effects/api-load-user-data ::address-args
   :effects/api-fetch-vault-index ::no-args
   :effects/api-fetch-vault-summaries ::no-args
   :effects/api-fetch-user-vault-equities ::optional-address-args
   :effects/api-fetch-vault-details ::address-and-optional-address-args
   :effects/api-fetch-vault-webdata2 ::address-args
   :effects/api-fetch-vault-fills ::address-args
   :effects/api-fetch-vault-funding-history ::address-args
   :effects/api-fetch-vault-order-history ::address-args
   :effects/api-fetch-vault-ledger-updates ::address-args})

(defn contracted-action-ids
  []
  (set (keys action-args-spec-by-id)))

(defn contracted-effect-ids
  []
  (set (keys effect-args-spec-by-id)))

(defn action-ids-using-any-args
  []
  (->> action-args-spec-by-id
       (keep (fn [[action-id spec]]
               (when (= spec ::any-args)
                 action-id)))
       set))

(s/def ::coin ::non-empty-string)
(s/def ::symbol ::non-empty-string)
(s/def ::active-asset (s/nilable ::non-empty-string))
(s/def ::active-market
  (s/nilable
   (s/keys :req-un [::coin ::symbol])))

(s/def ::asset-selector-state
  (s/and map?
         #(vector? (:markets %))
         #(map? (:market-by-key %))
         #(set? (:favorites %))
         #(set? (:loaded-icons %))
         #(set? (:missing-icons %))))

(s/def ::wallet-state
  (s/and map?
         #(map? (:agent %))))

(s/def ::websocket-state map?)
(s/def ::websocket-ui-state map?)
(s/def ::router-state
  (s/and map?
         #(string? (:path %))))

(s/def ::order-form-state
  (s/and map?
         #(not-any? order-form-key-policy/ui-owned-order-form-key? (keys %))
         #(not-any? order-form-key-policy/legacy-order-form-compatibility-key? (keys %))))

(s/def ::order-form-ui-state
  (s/and map?
         #(= order-form-key-policy/order-form-ui-state-keys (set (keys %)))
         #(boolean? (:pro-order-type-dropdown-open? %))
         #(boolean? (:margin-mode-dropdown-open? %))
         #(boolean? (:leverage-popover-open? %))
         #(boolean? (:size-unit-dropdown-open? %))
         #(boolean? (:tpsl-unit-dropdown-open? %))
         #(boolean? (:tif-dropdown-open? %))
         #(boolean? (:price-input-focused? %))
         #(boolean? (:tpsl-panel-open? %))
         #(contains? #{:market :limit :pro} (:entry-mode %))
         #(number? (:ui-leverage %))
         #(number? (:leverage-draft %))
         #(contains? #{:cross :isolated} (:margin-mode %))
         #(contains? #{:quote :base} (:size-input-mode %))
         #(contains? #{:manual :percent} (:size-input-source %))
         #(string? (:size-display %))))

(s/def ::order-form-runtime-state
  (s/and map?
         #(contains? % :submitting?)
         #(contains? % :error)
         #(boolean? (:submitting? %))
         #(or (nil? (:error %))
              (string? (:error %)))))

(s/def ::app-state
  (s/and map?
         #(contains? % :active-asset)
         #(contains? % :active-market)
         #(contains? % :asset-selector)
         #(contains? % :wallet)
         #(contains? % :websocket)
         #(contains? % :websocket-ui)
         #(contains? % :router)
         #(contains? % :order-form)
         #(contains? % :order-form-ui)
         #(contains? % :order-form-runtime)
         #(s/valid? ::active-asset (:active-asset %))
         #(s/valid? ::active-market (:active-market %))
         #(s/valid? ::asset-selector-state (:asset-selector %))
         #(s/valid? ::wallet-state (:wallet %))
         #(s/valid? ::websocket-state (:websocket %))
         #(s/valid? ::websocket-ui-state (:websocket-ui %))
         #(s/valid? ::router-state (:router %))
         #(s/valid? ::order-form-state (:order-form %))
         #(s/valid? ::order-form-ui-state (:order-form-ui %))
         #(s/valid? ::order-form-runtime-state (:order-form-runtime %))))

(defn- assertion-error
  [label spec value context]
  (js/Error.
   (str label " schema validation failed. "
        "context=" (pr-str context)
        " value=" (pr-str value)
        " explain=" (pr-str (s/explain-data spec value)))))

(defn- assert-spec!
  [label spec value context]
  (when-not (s/valid? spec value)
    (throw (assertion-error label spec value context)))
  value)

(defn assert-action-args!
  [action-id args context]
  (assert-spec! "action payload"
                ::action-id
                action-id
                context)
  (assert-spec! "action payload"
                (get action-args-spec-by-id action-id ::any-args)
                args
                (assoc context :action-id action-id)))

(defn assert-effect-args!
  [effect-id args context]
  (assert-spec! "effect request"
                ::effect-id
                effect-id
                context)
  (assert-spec! "effect request"
                (get effect-args-spec-by-id effect-id ::any-args)
                args
                (assoc context :effect-id effect-id)))

(defn assert-effect-call!
  [effect context]
  (when-not (and (vector? effect)
                 (seq effect))
    (throw (js/Error.
            (str "effect request schema validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str effect)))))
  (let [effect-id (first effect)
        args (subvec effect 1)]
    (assert-effect-args! effect-id args context)
    effect))

(defn assert-emitted-effects!
  [effects context]
  (when-not (sequential? effects)
    (throw (js/Error.
            (str "effect request schema validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str effects)))))
  (doseq [[idx effect] (map-indexed vector effects)]
    (assert-effect-call! effect (assoc context :effect-index idx)))
  effects)

(defn assert-app-state!
  [state context]
  (assert-spec! "app state" ::app-state state context))

(defn assert-provider-message!
  [provider-message context]
  (assert-spec! "provider payload" ::provider-message provider-message context))

(defn assert-signed-exchange-payload!
  [payload context]
  (assert-spec! "exchange payload" ::signed-exchange-payload payload context))

(defn assert-exchange-response!
  [payload context]
  (assert-spec! "exchange payload" ::exchange-response payload context))
