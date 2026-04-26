(ns hyperopen.runtime.effect-order-contract)

(declare effect-phase
         assert-action-effect-order!)

(def ^:private projection-effect-ids
  #{:effects/save
    :effects/save-many})

(def ^:private persistence-effect-ids
  #{:effects/local-storage-set
    :effects/local-storage-set-json
    :effects/persist-leaderboard-preferences
    :effects/replace-shareable-route-query})

(def ^:private effect-order-policy-by-action-id
  {:actions/select-asset
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/unsubscribe-active-asset
                        :effects/unsubscribe-orderbook
                        :effects/unsubscribe-trades
                        :effects/subscribe-active-asset
                        :effects/subscribe-orderbook
                        :effects/subscribe-trades
                        :effects/sync-active-asset-funding-predictability}}

   :actions/select-chart-timeframe
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/sync-active-candle-subscription
                        :effects/fetch-candle-snapshot}}

   :actions/select-portfolio-summary-time-range
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? true
    :heavy-effect-ids #{:effects/fetch-candle-snapshot}}

   :actions/select-portfolio-chart-tab
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? true
    :heavy-effect-ids #{:effects/fetch-candle-snapshot}}

   :actions/select-portfolio-returns-benchmark
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/fetch-candle-snapshot
                        :effects/api-fetch-vault-benchmark-details}}

   :actions/set-portfolio-returns-benchmark-suggestions-open
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-vault-index
                        :effects/api-fetch-vault-summaries}}

   :actions/set-vaults-snapshot-range
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? true
    :heavy-effect-ids #{:effects/fetch-candle-snapshot}}

   :actions/set-vault-detail-chart-series
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? true
    :heavy-effect-ids #{:effects/fetch-candle-snapshot}}

   :actions/select-vault-detail-returns-benchmark
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/fetch-candle-snapshot
                        :effects/api-fetch-vault-benchmark-details}}

   :actions/set-vault-detail-returns-benchmark-suggestions-open
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-vault-index
                        :effects/api-fetch-vault-index-with-cache
                        :effects/api-fetch-vault-summaries}}

   :actions/select-account-info-tab
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-user-funding-history
                        :effects/api-fetch-historical-orders}}

   :actions/apply-funding-history-filters
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-user-funding-history}}

   :actions/view-all-funding-history
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-user-funding-history}}

   :actions/submit-position-margin-update
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-position-margin}}

   :actions/submit-vault-transfer
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-vault-transfer}}

   :actions/submit-funding-transfer
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-funding-transfer}}

   :actions/submit-funding-send
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-funding-send}}

   :actions/submit-funding-withdraw
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-funding-withdraw}}

   :actions/submit-funding-deposit
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-funding-deposit}}

   :actions/enable-agent-trading
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/enable-agent-trading}}

   :actions/unlock-agent-trading
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/unlock-agent-trading}}

   :actions/submit-order
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-order}}

   :actions/confirm-order-submission
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-order}}

   :actions/submit-cancel-visible-open-orders-confirmation
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-cancel-order}}

   :actions/cancel-order
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-cancel-order}}

   :actions/cancel-twap
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-cancel-order}}

   :actions/cancel-visible-open-orders
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-cancel-order}}

   :actions/ws-diagnostics-reconnect-now
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/reconnect-websocket}}

   :actions/select-orderbook-price-aggregation
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/subscribe-orderbook}}

   :actions/load-vaults
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-vault-index
                        :effects/api-fetch-vault-index-with-cache
                        :effects/api-fetch-vault-summaries
                        :effects/api-fetch-user-vault-equities}}

   :actions/load-vault-detail
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? true
    :heavy-effect-ids #{:effects/api-fetch-vault-details
                        :effects/api-fetch-vault-webdata2
                        :effects/api-fetch-vault-fills
                        :effects/api-fetch-vault-funding-history
                        :effects/api-fetch-vault-order-history
                        :effects/api-fetch-vault-ledger-updates}}

   :actions/load-vault-route
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? true
    :heavy-effect-ids #{:effects/api-fetch-vault-index
                        :effects/api-fetch-vault-index-with-cache
                        :effects/api-fetch-vault-summaries
                        :effects/api-fetch-user-vault-equities
                        :effects/api-fetch-vault-benchmark-details
                        :effects/api-fetch-vault-details
                        :effects/api-fetch-vault-webdata2
                        :effects/api-fetch-vault-fills
                        :effects/api-fetch-vault-funding-history
                        :effects/api-fetch-vault-order-history
                        :effects/api-fetch-vault-ledger-updates}}

   :actions/load-funding-comparison
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-predicted-fundings}}

   :actions/load-leaderboard
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-leaderboard}}

   :actions/load-leaderboard-route
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-leaderboard}}

   :actions/load-funding-comparison-route
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-predicted-fundings
                        :effects/fetch-asset-selector-markets}}

   :actions/load-staking
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-staking-validator-summaries
                        :effects/api-fetch-staking-delegator-summary
                        :effects/api-fetch-staking-delegations
                        :effects/api-fetch-staking-rewards
                        :effects/api-fetch-staking-history
                        :effects/api-fetch-staking-spot-state}}

   :actions/load-staking-route
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-fetch-staking-validator-summaries
                        :effects/api-fetch-staking-delegator-summary
                        :effects/api-fetch-staking-delegations
                        :effects/api-fetch-staking-rewards
                        :effects/api-fetch-staking-history
                        :effects/api-fetch-staking-spot-state}}

   :actions/submit-staking-deposit
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-staking-deposit}}

   :actions/submit-staking-withdraw
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-staking-withdraw}}

   :actions/submit-staking-delegate
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-staking-delegate}}

   :actions/submit-staking-undelegate
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-submit-staking-undelegate}}

   :actions/load-api-wallet-route
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-load-api-wallets}}

   :actions/confirm-api-wallet-modal
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/api-authorize-api-wallet
                        :effects/api-remove-api-wallet}}

   :actions/navigate
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
   :heavy-effect-ids #{:effects/load-route-module
                       :effects/load-trade-chart-module
                       :effects/load-trading-indicators-module
                        :effects/api-fetch-leaderboard
                        :effects/api-fetch-vault-index
                        :effects/api-fetch-vault-index-with-cache
                        :effects/api-fetch-vault-summaries
                        :effects/api-fetch-user-vault-equities
                        :effects/api-fetch-vault-details
                        :effects/api-fetch-vault-webdata2
                        :effects/api-fetch-vault-fills
                        :effects/api-fetch-vault-funding-history
                        :effects/api-fetch-vault-order-history
                        :effects/api-fetch-vault-ledger-updates
                        :effects/api-fetch-predicted-fundings
                        :effects/api-fetch-staking-validator-summaries
                        :effects/api-fetch-staking-delegator-summary
                        :effects/api-fetch-staking-delegations
                        :effects/api-fetch-staking-rewards
                        :effects/api-fetch-staking-history
                        :effects/api-fetch-staking-spot-state
                        :effects/api-load-api-wallets
                        :effects/fetch-asset-selector-markets}}

   :actions/navigate-mobile-header-menu
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/load-route-module
                        :effects/load-trade-chart-module
                        :effects/load-trading-indicators-module
                        :effects/api-fetch-leaderboard
                        :effects/api-fetch-vault-index
                        :effects/api-fetch-vault-index-with-cache
                        :effects/api-fetch-vault-summaries
                        :effects/api-fetch-user-vault-equities
                        :effects/api-fetch-vault-details
                        :effects/api-fetch-vault-webdata2
                        :effects/api-fetch-vault-fills
                        :effects/api-fetch-vault-funding-history
                        :effects/api-fetch-vault-order-history
                        :effects/api-fetch-vault-ledger-updates
                        :effects/api-fetch-predicted-fundings
                        :effects/api-fetch-staking-validator-summaries
                        :effects/api-fetch-staking-delegator-summary
                        :effects/api-fetch-staking-delegations
                        :effects/api-fetch-staking-rewards
                        :effects/api-fetch-staking-history
                        :effects/api-fetch-staking-spot-state
                        :effects/fetch-asset-selector-markets}}})

(defn action-policy
  [action-id]
  (get effect-order-policy-by-action-id action-id))

(defn covered-action-ids
  []
  (set (keys effect-order-policy-by-action-id)))

(defn covered-action?
  [action-id]
  (contains? effect-order-policy-by-action-id action-id))

(defn effect-order-summary
  [action-id effects]
  (let [policy (action-policy action-id)
        heavy-effect-ids (:heavy-effect-ids policy #{})
        effect-ids (mapv first (or effects []))
        phases (mapv #(effect-phase (or policy {:heavy-effect-ids heavy-effect-ids}) %) effect-ids)
        projection-indices (keep-indexed (fn [index phase]
                                           (when (= phase :projection)
                                             index))
                                         phases)
        heavy-indices (keep-indexed (fn [index phase]
                                      (when (= phase :heavy-io)
                                        index))
                                    phases)
        first-projection-index (first projection-indices)
        first-heavy-index (first heavy-indices)
        duplicate-heavy-effect-ids
        (loop [remaining effect-ids
               seen #{}
               duplicates []]
          (if-let [effect-id (first remaining)]
            (if (contains? heavy-effect-ids effect-id)
              (if (contains? seen effect-id)
                (recur (next remaining) seen (conj duplicates effect-id))
                (recur (next remaining) (conj seen effect-id) duplicates))
              (recur (next remaining) seen duplicates))
            duplicates))
        projection-before-heavy
        (cond
          (empty? heavy-indices) true
          (nil? first-projection-index) false
          :else (< first-projection-index first-heavy-index))
        phase-order-valid?
        (try
          (assert-action-effect-order! action-id effects {:phase :debug-summary})
          true
          (catch :default _
            false))]
    {:action-id action-id
     :covered? (covered-action? action-id)
     :effect-ids effect-ids
     :phases phases
     :projection-effect-count (count projection-indices)
     :heavy-effect-count (count heavy-indices)
     :projection-before-heavy projection-before-heavy
     :duplicate-heavy-effect-ids duplicate-heavy-effect-ids
     :phase-order-valid phase-order-valid?}))

(defn- tracked-phase?
  [phase-rank phase]
  (contains? phase-rank phase))

(defn- effect-phase
  [{:keys [heavy-effect-ids]} effect-id]
  (cond
    (contains? projection-effect-ids effect-id) :projection
    (contains? persistence-effect-ids effect-id) :persistence
    (contains? heavy-effect-ids effect-id) :heavy-io
    :else :other))

(defn- contract-error
  [action-id context rule effect-index effect-id details]
  (js/Error.
   (str "effect-order contract failed for " action-id ". "
        "rule=" rule
        " effect-index=" effect-index
        " effect-id=" effect-id
        " context=" (pr-str context)
        (when (seq details)
          (str " details=" details)))))

(defn- assert-heavy-before-projection!
  [action-id context projection-seen? effect-index effect-id]
  (when-not projection-seen?
    (throw (contract-error
            action-id
            context
            "heavy-before-projection-phase"
            effect-index
            effect-id
            "heavy I/O emitted before projection phase"))))

(defn- assert-no-duplicate-heavy-effects!
  [action-id context effect-id heavy-seen-at-index effect-index]
  (when-some [first-index (get heavy-seen-at-index effect-id)]
    (throw (contract-error
            action-id
            context
            "duplicate-heavy-effect"
            effect-index
            effect-id
            (str "duplicate heavy effect emitted; first-index=" first-index)))))

(defn- assert-phase-order!
  [action-id context phase-rank previous-phase previous-rank effect-phase effect-index effect-id]
  (let [current-rank (get phase-rank effect-phase)]
    (when (< current-rank previous-rank)
      (throw (contract-error
              action-id
              context
              "phase-order-regression"
              effect-index
              effect-id
              (str "phase order must be nondecreasing. "
                   "previous-phase=" previous-phase
                   " current-phase=" effect-phase))))))

(defn assert-action-effect-order!
  [action-id effects context]
  (if-let [{:keys [required-phase-order
                   require-projection-before-heavy?
                   allow-duplicate-heavy-effects?
                   heavy-effect-ids] :as policy}
           (action-policy action-id)]
    (let [phase-rank (zipmap required-phase-order (range))]
      (loop [remaining-effects (seq effects)
             effect-index 0
             projection-seen? false
             previous-phase nil
             previous-rank -1
             heavy-seen-at-index {}]
        (if-let [effect (first remaining-effects)]
          (let [effect-id (first effect)
                phase (effect-phase policy effect-id)
                projection-seen?* (or projection-seen?
                                      (= phase :projection))
                heavy-seen-at-index*
                (if (contains? heavy-effect-ids effect-id)
                  (do
                    (when require-projection-before-heavy?
                      (assert-heavy-before-projection!
                       action-id
                       context
                       projection-seen?
                       effect-index
                       effect-id))
                    (when-not allow-duplicate-heavy-effects?
                      (assert-no-duplicate-heavy-effects!
                       action-id
                       context
                       effect-id
                       heavy-seen-at-index
                       effect-index))
                    (assoc heavy-seen-at-index effect-id effect-index))
                  heavy-seen-at-index)
                tracked-phase?* (tracked-phase? phase-rank phase)
                previous-phase* (if tracked-phase?* phase previous-phase)
                previous-rank* (if tracked-phase?*
                                 (do
                                   (assert-phase-order!
                                    action-id
                                    context
                                    phase-rank
                                    previous-phase
                                    previous-rank
                                    phase
                                    effect-index
                                    effect-id)
                                   (get phase-rank phase))
                                 previous-rank)]
            (recur (next remaining-effects)
                   (inc effect-index)
                   projection-seen?*
                   previous-phase*
                   previous-rank*
                   heavy-seen-at-index*))
          effects)))
    effects))
