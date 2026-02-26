(ns hyperopen.runtime.effect-order-contract)

(def ^:private projection-effect-ids
  #{:effects/save
    :effects/save-many})

(def ^:private persistence-effect-ids
  #{:effects/local-storage-set
    :effects/local-storage-set-json})

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
                        :effects/subscribe-trades}}

   :actions/select-chart-timeframe
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/fetch-candle-snapshot}}

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
    :heavy-effect-ids #{:effects/fetch-candle-snapshot}}

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

   :actions/enable-agent-trading
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/enable-agent-trading}}

   :actions/ws-diagnostics-reconnect-now
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/reconnect-websocket}}

   :actions/select-orderbook-price-aggregation
   {:required-phase-order [:projection :persistence :heavy-io]
    :require-projection-before-heavy? true
    :allow-duplicate-heavy-effects? false
    :heavy-effect-ids #{:effects/subscribe-orderbook}}})

(defn action-policy
  [action-id]
  (get effect-order-policy-by-action-id action-id))

(defn covered-action-ids
  []
  (set (keys effect-order-policy-by-action-id)))

(defn covered-action?
  [action-id]
  (contains? effect-order-policy-by-action-id action-id))

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
