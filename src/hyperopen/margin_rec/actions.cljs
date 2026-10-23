(ns hyperopen.margin-rec.actions
  "Pure actions for the isolated-margin recommendation feature.

  Effect-order discipline: every action that emits heavy IO emits at least one
  projection (:effects/save) first — see the policies registered for
  :actions/margin-rec-sync and :actions/margin-rec-process-intents in
  hyperopen.runtime.effect-order-contract."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.margin-rec.state :as margin-rec-state]
            [hyperopen.trading-settings :as trading-settings]))

(defn- effective-address
  [state]
  (account-context/effective-account-address state))

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(defn- anchor-number
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else js/NaN)]
    (when (and (number? n) (js/isFinite n))
      n)))

(defn- normalize-anchor
  "Keep only the numeric geometry keys from a trigger's bounds, so the popover
  can be positioned against it. Returns nil when no usable geometry is present."
  [anchor]
  (let [anchor* (cond
                  (map? anchor) anchor
                  (some? anchor) (js->clj anchor :keywordize-keys true)
                  :else nil)]
    (when (map? anchor*)
      (let [normalized (reduce (fn [acc k]
                                 (if-let [num (anchor-number (get anchor* k))]
                                   (assoc acc k num)
                                   acc))
                               {}
                               anchor-keys)]
        (when (seq normalized)
          normalized)))))

(defn margin-rec-sync
  "Diff desired background work against state and emit the (idle/low-priority)
  fetch and compute effects. Dispatched by the store watcher, debounced."
  [state now-ms]
  (let [address (effective-address state)
        {:keys [prune-keys candle-coins fills-address job]}
        (margin-rec-state/plan-work state address now-ms)
        prune-saves (when (seq prune-keys)
                      [[:effects/save
                        margin-rec-state/recs-path
                        (apply dissoc
                               (get-in state margin-rec-state/recs-path)
                               prune-keys)]])
        ;; One save of the whole map: :effects/save paths must be keyword-only
        ;; segments (::common/state-path), so the coin strings live in the
        ;; value, never in the path.
        candle-stamp-saves (when (seq candle-coins)
                             [[:effects/save
                               margin-rec-state/candle-requests-path
                               (reduce (fn [stamps coin] (assoc stamps coin now-ms))
                                       (get-in state margin-rec-state/candle-requests-path)
                                       candle-coins)]])
        fills-saves (when fills-address
                      [[:effects/save
                        margin-rec-state/fills-path
                        (assoc (get-in state margin-rec-state/fills-path)
                               :status :loading
                               :address fills-address)]])
        job-saves (when job
                    [[:effects/save
                      margin-rec-state/computing-path
                      {:key (:key job) :started-at now-ms}]])
        candle-fetches (map (fn [coin]
                              [:effects/fetch-candle-snapshot
                               :coin coin
                               :interval margin-rec-state/candle-interval
                               :bars margin-rec-state/candle-fetch-bars
                               ;; ~46 days of hourly bars per isolated position:
                               ;; background work, behind the page's own requests
                               :priority :low])
                            candle-coins)
        fills-fetches (when fills-address
                        [[:effects/margin-rec-fetch-fills fills-address]])
        job-effects (when job
                      [[:effects/margin-rec-compute job]])]
    (vec (concat prune-saves
                 candle-stamp-saves
                 fills-saves
                 job-saves
                 candle-fetches
                 fills-fetches
                 job-effects))))

(defn toggle-margin-rec-panel
  ([state position-key]
   (toggle-margin-rec-panel state position-key nil))
  ([state position-key anchor]
   (let [current (get-in state margin-rec-state/panel-path)
         opening? (and (some? position-key)
                       (not= current position-key))
         next-key (when opening? position-key)]
     [[:effects/save margin-rec-state/panel-path next-key]
      [:effects/save margin-rec-state/panel-anchor-path
       (when opening? (normalize-anchor anchor))]])))

(defn close-margin-rec-panel
  [_state]
  [[:effects/save margin-rec-state/panel-path nil]
   [:effects/save margin-rec-state/panel-anchor-path nil]])

(defn handle-margin-rec-panel-keydown
  [state key]
  (if (= key "Escape")
    (close-margin-rec-panel state)
    []))

;; --- batch top-up panel ------------------------------------------------------

(def ^:private closed-batch
  {:open? false :anchor nil :deselected #{}})

(defn toggle-margin-rec-batch-panel
  ([state]
   (toggle-margin-rec-batch-panel state nil))
  ([state anchor]
   (let [open? (boolean (get-in state (conj margin-rec-state/batch-path :open?)))]
     [[:effects/save margin-rec-state/batch-path
       (if open?
         closed-batch
         {:open? true
          :anchor (normalize-anchor anchor)
          :deselected #{}})]])))

(defn close-margin-rec-batch-panel
  [_state]
  [[:effects/save margin-rec-state/batch-path closed-batch]])

(defn handle-margin-rec-batch-keydown
  [state key]
  (if (= key "Escape")
    (close-margin-rec-batch-panel state)
    []))

(defn toggle-margin-rec-batch-selection
  [state position-key]
  (let [batch (margin-rec-state/batch-ui state)
        deselected (set (:deselected batch))
        next-deselected (if (contains? deselected position-key)
                          (disj deselected position-key)
                          (conj deselected position-key))]
    [[:effects/save margin-rec-state/batch-path
      (assoc batch :deselected next-deselected)]]))

(declare submit-for-intent)

(defn margin-rec-batch-plan
  "Plan the one-action batch top-up over the current at-risk candidates:
  selected candidates drain their dex's available-collateral pool
  most-severe-first. Returns {:submits [{:candidate :amount :request}]
                              :skipped [{:candidate :skip-reason}]}."
  [state]
  (let [mode (margin-rec-state/risk-mode state)
        deselected (set (:deselected (margin-rec-state/batch-ui state)))
        candidates (->> (margin-rec-state/batch-candidates state mode)
                        (remove (comp deselected :position-key)))
        pools (margin-rec-state/batch-available-pools state candidates)]
    (loop [candidates candidates
           pools pools
           submits []
           skipped []]
      (if-let [{:keys [dex additional position-data] :as candidate} (first candidates)]
        (let [pool-key (or dex "")
              available (get pools pool-key 0)
              amount (min additional available)]
          (if (< amount margin-rec-state/intent-min-topup-usd)
            (recur (rest candidates) pools submits
                   (conj skipped {:candidate candidate
                                  :skip-reason :insufficient-available}))
            (if-let [request (submit-for-intent state
                                                {:position-data position-data}
                                                amount)]
              (recur (rest candidates)
                     (assoc pools pool-key (- available amount))
                     (conj submits {:candidate candidate
                                    :amount amount
                                    :request request})
                     skipped)
              (recur (rest candidates) pools submits
                     (conj skipped {:candidate candidate
                                    :skip-reason :request-invalid})))))
        {:submits submits :skipped skipped}))))

(defn apply-margin-rec-batch
  "Submit the recommended top-up for every selected at-risk position in one
  action.

  Trading must be ready before any margin request is sent — mirror manual
  order entry (order/actions.cljs submit-order) and optimizer execution
  (confirm-portfolio-optimizer-execution), which route every non-ready agent
  status instead of submitting. Without this the per-position
  `:effects/api-submit-position-margin` effects each dead-end on a
  \"Unlock trading…\" rejection with an error toast per position — the
  anti-pattern this branch replaces.

  A locked agent prompts the passkey unlock and replays THIS action on
  success. An action can only EMIT effects (`effects/*`) — it cannot return
  `[:actions/...]` (that fails the effect-id schema) — so we inline what
  `:actions/unlock-agent-trading` itself emits: flip the status to
  `:unlocking` (so a second click can't double-prompt the passkey) and run
  the unlock effect with this action queued as the replay. The panel is left
  open on the locked pass so the replay re-plans from intact selections; it
  closes only on the ready pass that actually submits."
  [state]
  (let [{:keys [submits]} (margin-rec-batch-plan state)
        agent-status (get-in state [:wallet :agent :status])]
    (cond
      ;; Nothing fundable — just close the panel.
      (empty? submits)
      (close-margin-rec-batch-panel state)

      ;; Trading ready: close the panel, then one updateIsolatedMargin per
      ;; fundable position.
      (= :ready agent-status)
      (vec (concat (close-margin-rec-batch-panel state)
                   (map (fn [{:keys [request]}]
                          [:effects/api-submit-position-margin request])
                        submits)))

      ;; Locked: surface the passkey unlock and replay this action on success.
      (= :locked agent-status)
      [[:effects/save-many [[[:wallet :agent :status] :unlocking]
                            [[:wallet :agent :error] nil]]]
       [:effects/unlock-agent-trading
        {:after-success-actions [[:actions/apply-margin-rec-batch]]}]]

      ;; Unlock already in flight (awaiting passkey): hold without submitting,
      ;; so a second click can't double-prompt.
      (= :unlocking agent-status)
      []

      ;; Trading not enabled yet (default :not-ready, plus :approving/:error):
      ;; open the enable-trading recovery modal — the same prompt manual order
      ;; entry shows — instead of submitting requests that would each be
      ;; rejected with "Enable trading first".
      :else
      [[:effects/save [:wallet :agent :recovery-modal-open?] true]])))

(defn- persist-trading-settings
  [state updates]
  (let [next-state (trading-settings/normalize-state
                    (merge (or (:trading-settings state)
                               trading-settings/default-state)
                           updates))]
    [[:effects/save [:trading-settings] next-state]
     [:effects/local-storage-set-json trading-settings/storage-key next-state]]))

(defn set-margin-rec-risk-mode
  [state mode]
  (persist-trading-settings
   state
   {:margin-rec-risk-mode (trading-settings/normalize-margin-rec-risk-mode mode)}))

(defn set-margin-rec-auto-topup
  [state enabled?]
  (persist-trading-settings state {:margin-rec-auto-topup? (boolean enabled?)}))

(defn- submit-for-intent
  "Build the updateIsolatedMargin request for a matured intent via the
  existing position-margin machinery (validation + asset-id resolution)."
  [state entry amount]
  (let [modal (position-margin/from-position-row
               state
               (assoc (:position-data entry)
                      :prefill-margin-mode :add
                      :prefill-margin-amount amount))
        result (position-margin/prepare-submit state modal)]
    (when (:ok? result)
      (:request result))))

(defn- intent-outcome
  "Decide what a pending intent should do against current positions.
  Returns {:intent updated-intent :request request-or-nil}."
  [state entries-by-key now-ms intent]
  (let [entry (get entries-by-key (:position-key intent))
        expired? (>= now-ms (:expires-at intent))
        target (or (:target-equity intent)
                   (when entry
                     (margin-rec-state/ready-recommended-equity
                      state
                      (:position-key intent))))]
    (cond
      expired?
      {:intent (assoc intent :status :expired :resolved-at now-ms)}

      (nil? entry)
      ;; Position not visible yet (fill still propagating) — keep waiting.
      {:intent intent}

      (not (margin-rec-state/intent-matches-position? intent entry))
      ;; Size does not match the expected post-fill size (order cancelled,
      ;; partial fill, or user already changed the position): keep waiting
      ;; until expiry rather than topping up a position we did not create.
      {:intent intent}

      (nil? target)
      ;; Recommendation still computing — wait for it (or expiry).
      {:intent intent}

      :else
      (let [needed (- target (:equity entry))
            capped (cond-> needed
                     (number? (:max-add intent)) (min (:max-add intent)))
            available (or (:available-to-add
                           (position-margin/from-position-row state (:position-data entry)))
                          0)
            amount (min capped available)]
        (if (< amount margin-rec-state/intent-min-topup-usd)
          {:intent (assoc intent
                          :status :skipped
                          :skip-reason (if (< needed margin-rec-state/intent-min-topup-usd)
                                         :already-funded
                                         :insufficient-available)
                          :resolved-at now-ms)}
          (if-let [request (submit-for-intent state entry amount)]
            {:intent (assoc intent
                            :status :submitted
                            :submitted-amount amount
                            :resolved-at now-ms)
             :request request}
            {:intent (assoc intent
                            :status :skipped
                            :skip-reason :request-invalid
                            :resolved-at now-ms)}))))))

(defn margin-rec-process-intents
  "One-shot post-fill top-ups. Each pending intent fires at most once, only
  while fresh, only when the observed position matches the expected post-fill
  size, and only up to the available collateral."
  [state now-ms]
  (let [intents (get-in state margin-rec-state/intents-path)
        pending (filter (comp #{:pending} :status val) intents)]
    (if (empty? pending)
      []
      (let [entries-by-key (into {}
                                 (map (juxt :position-key identity))
                                 (margin-rec-state/isolated-positions state))
            outcomes (map (fn [[k intent]]
                            [k (intent-outcome state
                                               entries-by-key
                                               now-ms
                                               (margin-rec-state/stamp-intent intent now-ms))])
                          pending)
            changed (filter (fn [[k {:keys [intent]}]]
                              (not= intent (get intents k)))
                            outcomes)
            next-intents (reduce (fn [acc [k {:keys [intent]}]]
                                   (assoc acc k intent))
                                 intents
                                 changed)
            requests (keep (comp :request second) outcomes)]
        (if (empty? changed)
          []
          (vec (concat [[:effects/save margin-rec-state/intents-path next-intents]]
                       (map (fn [request]
                              [:effects/api-submit-position-margin request])
                            requests))))))))
