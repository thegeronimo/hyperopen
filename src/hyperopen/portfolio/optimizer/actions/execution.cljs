(ns hyperopen.portfolio.optimizer.actions.execution
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.margin-rec.state :as margin-rec-state]
            [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.application.execution-amend :as execution-amend]
            [hyperopen.portfolio.optimizer.application.execution-carryover :as carryover]
            [hyperopen.portfolio.optimizer.application.execution-cloid :as cloid]
            [hyperopen.portfolio.optimizer.application.rebalance-preview :as rebalance-preview]
            [hyperopen.portfolio.optimizer.application.run-identity :as run-identity]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.trading-settings :as trading-settings]))

(def stale-recommendation-message
  "Inputs changed since this recommendation was computed — re-run the optimizer before executing.")

(defn- optimizer-running?
  [state]
  (or (= :running (get-in state contracts/run-state-status-path))
      (= :running (get-in state contracts/optimization-progress-status-path))))

(defn stale-recommendation?
  "True when there IS a solved run but the current draft/readiness no longer matches it
  (universe / constraints / objective / model edited since the solve), so the staged plan
  is computed from inputs the trader has since moved off. Mirrors the Recommendation tab's
  `:stale?` signal but enforced in the execution gates, where a stale plan would otherwise
  release live orders. Short-circuits on `solved-run?` so a hand-built plan with no retained
  run (and the cheap pre-submit toggles) never pays for `build-readiness`."
  [state]
  (let [last-successful-run (get-in state contracts/last-successful-run-path)]
    (and (run-identity/solved-run? last-successful-run)
         (run-identity/stale-run?
          {:draft (get-in state contracts/draft-path)
           :readiness (setup-readiness/build-readiness state)
           :run-state (get-in state contracts/run-state-path)
           :running? (optimizer-running? state)
           :last-successful-run last-successful-run}))))

(def ^:private phase-path (conj contracts/execution-modal-path :phase))
(def ^:private default-order-type-path
  (conj contracts/execution-modal-path :default-order-type))
(def ^:private overrides-path (conj contracts/execution-modal-path :overrides))
(def ^:private params-path (conj contracts/execution-modal-path :params))
(def ^:private open-row-path (conj contracts/execution-modal-path :open-row))
(def ^:private order-filter-path (conj contracts/execution-modal-path :order-filter))

(defn- staged-plan
  "Builds the execution plan from the last successful run, or nil when there is no
  solved run with a rebalance preview to stage.

  The entry gates on currency, not merely `solved?`: a stale recommendation (inputs edited
  since the solve) stages a plan flagged `:execution-disabled? :stale-recommendation`, so the
  trader sees what WOULD trade but can't arm or commit until the optimizer is re-run. A
  read-only (spectate) block takes precedence — it is the harder restriction and its message
  wins.

  `exit-instrument-ids` (a set, usually the modal's) are the trader's per-staging
  \"sell this held-out asset to zero\" marks: when the set is PROVIDED (even empty —
  the toggle-off path must return the plan to the run's own preview) the preview is
  re-derived from the STORED run request (the source the stored preview was built
  from, cost contexts included) with those instruments treated as explicit exits —
  never from the live draft, whose divergence is the stale gate's concern, and never
  by mutating the request, so the input-signature stays untouched."
  ([state]
   (staged-plan state nil))
  ([state exit-instrument-ids]
   (let [readiness (setup-readiness/build-readiness state)
         last-successful-run
         (rebalance-preview/last-successful-run-with-rebalance-preview
          (:request readiness)
          (get-in state contracts/last-successful-run-path))
         last-successful-run
         (if (and (map? last-successful-run) (some? exit-instrument-ids))
           (update last-successful-run
                   :result
                   #(rebalance-preview/result-with-refreshed-rebalance-preview
                     (get-in last-successful-run [:request-signature :request])
                     %
                     {:exit-instrument-ids exit-instrument-ids}))
           last-successful-run)
         result (:result last-successful-run)
         preview (:rebalance-preview result)]
     (when (and (= :solved (:status result))
                (map? preview))
       (let [plan (execution/build-execution-plan
                   {:scenario-id (common/current-scenario-id state)
                    :rebalance-preview preview
                    :execution-assumptions (get-in state
                                                   contracts/draft-execution-assumptions-path)
                    :mutations-blocked-message
                    (account-context/mutations-blocked-message state)})]
         (if (and (not (:execution-disabled? plan))
                  (stale-recommendation? state))
           (assoc plan
                  :execution-disabled? true
                  :disabled-reason :stale-recommendation
                  :disabled-message stale-recommendation-message)
           plan))))))

(defn- requested-universe-ids
  [last-successful-run]
  (into #{}
        (keep #(coercion/non-blank-text (:instrument-id %)))
        (get-in last-successful-run [:request-signature :request :requested-universe])))

(defn- auto-exit-candidate-ids
  "Held PERP positions the trader REMOVED from the allocation — the rows the auto-exit
  preference stages closing orders for (a long sells, a short buys back, both via the
  target-0 path). Two excluded groups are never auto-closed and stay held-by-default:
  spot holdings (an excluded asset class), and assets the trader REQUESTED but the
  engine dropped (present in the stored request's :requested-universe with no result
  target — missing history, calendar exclusion, ...): the trader wanted those
  allocated, so closing them on a data failure would trade against intent. Both remain
  manually closable per row."
  [last-successful-run plan]
  (let [requested (requested-universe-ids last-successful-run)]
    (into #{}
          (comp (filter #(= :skipped (:status %)))
                (filter #(= :excluded-from-optimization (:reason %)))
                (filter #(= :perp (:instrument-type %)))
                (keep #(coercion/non-blank-text (:instrument-id %)))
                (remove requested))
          (:rows plan))))

(defn open-portfolio-optimizer-execution
  "Stages the current rebalance into an execution plan and switches the scenario
  surface to the Execution tab. Always switches the tab (so the entry point is
  discoverable even with no solved run, which renders an empty state); the plan is
  rebuilt as a fresh snapshot on every entry.

  When the persisted auto-exit preference is on (the default), held perp positions the
  trader removed from the allocation are pre-staged as closing orders: the modal's
  exit set is seeded with the candidates and the plan is re-derived with them, so the
  realized portfolio matches the allocation the optimizer actually produced. The
  per-row revert and the surface toggle both remain available.

  The plan is ALWAYS re-derived through the exits arity, even for an empty exit set:
  that arity rebuilds the rebalance preview from the stored run request, so a run
  restored from autosave or a saved scenario — whose frozen preview may predate a cost
  fix, e.g. the worker preview that charged no maker fee — is re-costed on entry
  instead of quoting a stale estimate."
  [state]
  (let [base-plan (staged-plan state #{})
        exits (if (and (map? base-plan)
                       (trading-settings/optimizer-auto-exit-excluded? state))
                (auto-exit-candidate-ids
                 (get-in state contracts/last-successful-run-path)
                 base-plan)
                #{})
        plan (if (seq exits)
               (staged-plan state exits)
               base-plan)]
    [[:effects/save
      contracts/execution-modal-path
      (assoc (optimizer-defaults/default-execution-modal-state)
             :open? true
             :exit-instrument-ids exits
             :plan plan)]
     [:effects/save contracts/execution-path (optimizer-defaults/default-execution-state)]
     [:effects/refresh-portfolio-optimizer-open-orders]
     [:effects/refresh-portfolio-optimizer-rebalance-slippage-snapshots]
     [:effects/save contracts/ui-results-tab-path :execution]
     [:effects/replace-shareable-route-query]]))

(defn set-portfolio-optimizer-execution-phase
  "Pre-submit phase toggle: :armed asks for a second confirm, :staged returns to the
  default order-type selector. Clears any stale confirm-time error. Arming a stale
  recommendation is refused (the surface stays :staged with a re-run prompt) so a plan
  computed from since-edited inputs can never reach the live-commit button."
  [state phase]
  (let [arm? (= :armed (keyword phase))]
    (if (and arm? (stale-recommendation? state))
      [[:effects/save phase-path :staged]
       [:effects/save contracts/execution-modal-error-path stale-recommendation-message]]
      [[:effects/save phase-path (if arm? :armed :staged)]
       [:effects/save contracts/execution-modal-error-path nil]])))

(defn set-portfolio-optimizer-execution-default-order-type
  [_state order-type]
  [[:effects/save default-order-type-path (keyword order-type)]])

(defn set-portfolio-optimizer-execution-row-order-type
  "Overrides one order's type. The :recommended sentinel clears the override so the
  row falls back to the algorithm-recommended type."
  [state row-id order-type]
  (let [overrides (or (get-in state overrides-path) {})
        order-type* (keyword order-type)]
    [[:effects/save
      overrides-path
      (if (= :recommended order-type*)
        (dissoc overrides row-id)
        (assoc overrides row-id order-type*))]]))

(defn toggle-portfolio-optimizer-execution-row
  "Expands/collapses one order's inline type editor (single-open accordion)."
  [state row-id]
  (let [open-row (get-in state open-row-path)]
    [[:effects/save open-row-path (when (not= open-row row-id) row-id)]]))

(defn set-portfolio-optimizer-execution-row-param
  [state row-id param-key value]
  (let [params (or (get-in state params-path) {})]
    [[:effects/save
      params-path
      (assoc-in params [row-id (keyword param-key)] value)]]))

(defn set-portfolio-optimizer-execution-order-filter
  "Sets the order-list filter (:all / :working / :filled)."
  [_state order-filter]
  [[:effects/save order-filter-path (keyword order-filter)]])

(def ^:private exit-instrument-ids-path
  (conj contracts/execution-modal-path :exit-instrument-ids))

(defn restage-portfolio-optimizer-execution-plan
  "Rebuilds the staged plan in place from the CURRENT run, keeping every staging choice
  (exit set, order-type default, per-row overrides/params, open row, filter).

  The snapshot-refresh effect dispatches this when fresher order-book data lands: the
  modal holds a plan SNAPSHOT taken at tab entry, so without a restage the surface keeps
  quoting costs estimated before the books arrived — flat fallback bps, which also make
  TWAP project identically to Market. The estimate a trader reads must be the freshest
  one the app has.

  No-ops unless the surface is still pre-commit and idle: an armed, submitting, or
  post-run plan must never change under the trader's hands."
  [state]
  (let [modal (get-in state contracts/execution-modal-path)
        run-status (or (get-in state (conj contracts/execution-path :status)) :idle)]
    (if (or (not (:open? modal))
            (not (map? (:plan modal)))
            (:submitting? modal)
            (not= :idle run-status)
            (not= :staged (:phase modal)))
      []
      (if-let [plan (staged-plan state (or (get-in state exit-instrument-ids-path) #{}))]
        [[:effects/save (conj contracts/execution-modal-path :plan) plan]]
        []))))

(defn set-portfolio-optimizer-execution-exit
  "Marks (exit? true) or unmarks (exit? false) held instruments the allocator excluded
  as explicit sell-to-zero exits for THIS staged execution, then re-stages the plan so
  marked rows become real sell orders — quantity, cost estimate, and margin impact
  folded into the plan summary — and unmarked rows return to skipped holds.

  The marks are modal-scoped staging state (like :overrides / :overlap-cancels): they
  never touch the run request or its input-signature, so the stale-recommendation gate
  is unaffected, and they reset on every tab entry — a staged sell of a held-out asset
  is an explicit per-staging decision. Re-staging drops an :armed surface back to
  :staged: the order set just changed and must be re-reviewed before commit. No-ops
  while submitting or once a run attempt left :idle (the staged plan was already sent;
  a fresh staging starts from tab re-entry)."
  [state instrument-ids exit?]
  (let [modal (get-in state contracts/execution-modal-path)
        run-status (or (get-in state (conj contracts/execution-path :status)) :idle)
        ids (into #{}
                  (keep #(when (and (string? %) (seq %)) %))
                  instrument-ids)]
    (if (or (not (map? (:plan modal)))
            (:submitting? modal)
            (not= :idle run-status)
            (empty? ids))
      []
      (let [exits (or (get-in state exit-instrument-ids-path) #{})
            exits* (if exit?
                     (into exits ids)
                     (reduce disj exits ids))]
        [[:effects/save exit-instrument-ids-path exits*]
         [:effects/save (conj contracts/execution-modal-path :plan)
          (staged-plan state exits*)]
         [:effects/save phase-path :staged]
         [:effects/save contracts/execution-modal-error-path nil]]))))

(defn set-portfolio-optimizer-execution-auto-exit
  "Persists the trader's auto-exit preference — stage closing orders for held perp
  positions removed from the allocation — to the browser-local trading settings
  (localStorage, survives sessions), and when a pre-run plan is staged, immediately
  re-seeds it: ON recomputes the candidate set (which also clears any manual per-row
  reverts — the preference is the source of truth when toggled), OFF returns every
  auto-staged close to a held row. Mirrors the margin-rec settings persistence
  pattern (projection save + local-storage-set-json)."
  [state enabled?]
  (let [enabled?* (boolean enabled?)
        settings (trading-settings/normalize-state
                  (merge (or (:trading-settings state)
                             trading-settings/default-state)
                         {:optimizer-auto-exit-excluded? enabled?*}))
        persist [[:effects/save [:trading-settings] settings]
                 [:effects/local-storage-set-json trading-settings/storage-key settings]]
        modal (get-in state contracts/execution-modal-path)
        run-status (or (get-in state (conj contracts/execution-path :status)) :idle)
        restage? (and (map? (:plan modal))
                      (not (:submitting? modal))
                      (= :idle run-status))]
    (if-not restage?
      persist
      (let [base-plan (staged-plan state #{})
            exits (if (and enabled?* (map? base-plan))
                    (auto-exit-candidate-ids
                     (get-in state contracts/last-successful-run-path)
                     base-plan)
                    #{})
            plan (if (seq exits)
                   (staged-plan state exits)
                   base-plan)]
        (into persist
              [[:effects/save exit-instrument-ids-path exits]
               [:effects/save (conj contracts/execution-modal-path :plan) plan]
               [:effects/save phase-path :staged]
               [:effects/save contracts/execution-modal-error-path nil]])))))

(def ^:private overlap-cancels-path
  (conj contracts/execution-modal-path :overlap-cancels))

(defn- dedup-by-oid
  [entries]
  (:out (reduce (fn [{:keys [seen out]} e]
                  (let [k (str (:oid e))]
                    (if (contains? seen k)
                      {:seen seen :out out}
                      {:seen (conj seen k) :out (conj out e)})))
                {:seen #{} :out []}
                entries)))

(defn- attach-carryover-cancels
  "Stamps every open order this run should cancel BEFORE releasing new orders onto the
  plan as :cancel-orders. Three sources, deduped by oid (first wins):

    1. In-memory carryover — resting orders from previous runs THIS session (they carry
       a frozen wire asset index).
    2. Live-book recognition — orders on the frontendOpenOrders snapshot bearing the
       optimizer cloid tag, so a run AFTER A PAGE RELOAD still cleans up its own resting
       orders automatically (no user action).
    3. User-selected overlaps — untagged orders on a traded instrument the user ticked to
       cancel on the decision surface (manual orders / pre-tag optimizer orders).

  Read fresh at confirm time so anything that filled in between is not needlessly
  cancelled. Without this a stale order fills on top of the new run and over-allocates."
  [state plan]
  (let [carryover (carryover/live-resting-carryover
                   state
                   (get-in state contracts/execution-resting-carryover-path))
        snapshot (cloid/live-open-orders state)
        ready-rows (filter #(= :ready (:status %)) (:rows plan))
        {:keys [optimizer-owned untagged-overlap]} (cloid/classify-overlap snapshot ready-rows)
        selections (get-in state overlap-cancels-path)
        chosen-untagged (filter #(get selections (str (:oid %))) untagged-overlap)
        merged (dedup-by-oid (concat carryover optimizer-owned chosen-untagged))]
    (cond-> plan
      (seq merged) (assoc :cancel-orders merged))))

(defn set-portfolio-optimizer-execution-overlap-cancel
  "Records the user's cancel/keep choice for one untagged overlapping open order (keyed
  by oid string) on the decision surface. Checked oids are merged into :cancel-orders at
  confirm by attach-carryover-cancels."
  [state oid cancel?]
  (let [selections (or (get-in state overlap-cancels-path) {})
        oid-key (str oid)]
    [[:effects/save
      overlap-cancels-path
      (if cancel?
        (assoc selections oid-key true)
        (dissoc selections oid-key))]]))

(defn margin-rec-execution-intent-save
  "One-shot auto top-up intents for the isolated-margin legs of a confirmed
  execution plan (consumed post-fill by :actions/margin-rec-process-intents).
  Returns a projection effect or nil."
  [state plan]
  (when (trading-settings/margin-rec-auto-topup? state)
    (let [legs (margin-rec-state/isolated-execution-legs state (:rows plan))]
      (when (seq legs)
        [:effects/save
         margin-rec-state/intents-path
         (reduce (fn [intents leg]
                   (assoc intents
                          (:position-key leg)
                          (margin-rec-state/make-intent-draft
                           (assoc leg
                                  :target-equity nil
                                  :source :optimizer))))
                 (get-in state margin-rec-state/intents-path)
                 legs)]))))

(defn confirm-portfolio-optimizer-execution
  [state]
  (let [modal (get-in state contracts/execution-modal-path)
        base-plan (:plan modal)
        plan (when (map? base-plan)
               (execution/apply-order-type-selections
                base-plan
                {:default-order-type (:default-order-type modal)
                 :overrides (:overrides modal)
                 :params (:params modal)}))
        ready-count (get-in plan [:summary :ready-count])
        agent-status (get-in state [:wallet :agent :status])]
    (cond
      (not (map? plan))
      []

      (:submitting? modal)
      []

      (:execution-disabled? plan)
      [[:effects/save
        contracts/execution-modal-error-path
        (or (:disabled-message plan)
            "Execution is disabled for this scenario.")]]

      (stale-recommendation? state)
      [[:effects/save
        contracts/execution-modal-error-path
        stale-recommendation-message]]

      (not (pos? (or ready-count 0)))
      [[:effects/save
        contracts/execution-modal-error-path
        "No executable rows are ready."]]

      (= :locked agent-status)
      [[:effects/save contracts/execution-modal-error-path nil]
       [:effects/save-many [[[:wallet :agent :status] :unlocking]
                            [[:wallet :agent :error] nil]]]
       [:effects/unlock-agent-trading
        {:after-success-actions [[:actions/confirm-portfolio-optimizer-execution]]}]]

      (= :unlocking agent-status)
      [[:effects/save
        contracts/execution-modal-error-path
        "Awaiting passkey before executing."]]

      (not= :ready agent-status)
      [[:effects/save [:wallet :agent :recovery-modal-open?] true]
       [:effects/save
        contracts/execution-modal-error-path
        "Enable trading before executing."]]

      :else
      (let [intent-save (margin-rec-execution-intent-save state plan)]
        (into (if intent-save [intent-save] [])
              [[:effects/save contracts/execution-modal-submitting-path true]
               [:effects/save contracts/execution-modal-error-path nil]
               [:effects/execute-portfolio-optimizer-plan
                (attach-carryover-cancels state plan)]])))))

(defn- amend-refusal-message
  [error]
  (case error
    :not-amendable
    "This order is no longer on the book — it filled or was cancelled."
    :market-unavailable
    "Market metadata is unavailable for this order — try again shortly."
    :remaining-below-lot
    "The unfilled remainder is below one lot — manage it from the trade ticket."
    "This order can't be updated right now."))

(defn amend-portfolio-optimizer-execution-order
  "Replaces ONE working (resting/open) order from the latest execution attempt: cancel
  it on the exchange, then submit a replacement priced off the LIVE mark — a new bps
  offset (or post-only at the touch), or a market order that crosses immediately for
  the REMAINING unfilled size. Reuses the execute effect, whose cancel-first /
  halt-on-failure contract guarantees the replacement can never be live alongside the
  original; the amend plan carries ONLY the amended order's cancel entry (attaching the
  session carryover here would cancel the run's OTHER live orders).

  Deliberately NOT gated on stale-recommendation? (mirrors Revert's reasoning): the
  order is already live — repricing or crossing its remaining size never creates
  exposure beyond what was already confirmed, and gating it would strand a working
  order behind a re-run."
  [state row-id]
  (let [modal (get-in state contracts/execution-modal-path)
        plan (:plan modal)
        ledger (last (get-in state contracts/execution-history-path))
        agent-status (get-in state [:wallet :agent :status])]
    (cond
      (not (map? plan))
      []

      (:submitting? modal)
      []

      (not (map? ledger))
      []

      (:execution-disabled? plan)
      [[:effects/save
        contracts/execution-modal-error-path
        (or (:disabled-message plan)
            "Execution is disabled for this scenario.")]]

      :else
      (let [row (some #(when (= row-id (:row-id %)) %) (:rows ledger))
            target (when row
                     (execution-amend/amend-target
                      state
                      (get-in state [:asset-selector :market-by-key])
                      row))
            {amend-plan :plan error :error}
            (execution-amend/build-amend-plan
             {:plan plan
              :ledger ledger
              :row-id row-id
              :selections {:overrides (:overrides modal)
                           :params (:params modal)}
              :target target})]
        (cond
          (some? error)
          [[:effects/save contracts/execution-modal-error-path
            (amend-refusal-message error)]]

          (= :locked agent-status)
          [[:effects/save contracts/execution-modal-error-path nil]
           [:effects/save-many [[[:wallet :agent :status] :unlocking]
                                [[:wallet :agent :error] nil]]]
           [:effects/unlock-agent-trading
            {:after-success-actions
             [[:actions/amend-portfolio-optimizer-execution-order row-id]]}]]

          (= :unlocking agent-status)
          [[:effects/save
            contracts/execution-modal-error-path
            "Awaiting passkey before updating the order."]]

          (not= :ready agent-status)
          [[:effects/save [:wallet :agent :recovery-modal-open?] true]
           [:effects/save
            contracts/execution-modal-error-path
            "Enable trading before updating orders."]]

          :else
          [[:effects/save contracts/execution-modal-submitting-path true]
           [:effects/save contracts/execution-modal-error-path nil]
           [:effects/execute-portfolio-optimizer-plan amend-plan]])))))

(defn resume-portfolio-optimizer-execution
  "Resumes a halted run by retrying ONLY the still-recoverable rows. Unlike a plain
  re-confirm (which replays the stale modal :plan whose rows stay :ready forever and
  would re-submit already-filled orders), this derives the retry set from the latest
  execution ledger: failed rows are re-armed, already-filled rows are demoted to
  :skipped :already-filled, and the corrected plan is executed via the existing effect
  (which appends a fresh ledger so the audit trail is preserved)."
  [state]
  (let [modal (get-in state contracts/execution-modal-path)
        plan (:plan modal)
        ledger (last (get-in state contracts/execution-history-path))]
    (cond
      (not (map? plan))
      []

      (:submitting? modal)
      []

      (not (map? ledger))
      []

      (:execution-disabled? plan)
      [[:effects/save
        contracts/execution-modal-error-path
        (or (:disabled-message plan)
            "Execution is disabled for this scenario.")]]

      :else
      (let [resume-plan (execution/build-resume-plan plan ledger)
            ready-count (get-in resume-plan [:summary :ready-count])]
        (if-not (pos? (or ready-count 0))
          [[:effects/save
            contracts/execution-modal-error-path
            "No orders are eligible to resume."]]
          [[:effects/save contracts/execution-modal-submitting-path true]
           [:effects/save contracts/execution-modal-error-path nil]
           [:effects/execute-portfolio-optimizer-plan
            (attach-carryover-cancels state resume-plan)]])))))

(defn revert-portfolio-optimizer-execution-filled
  "Unwinds the filled orders from the latest execution attempt by submitting reversing
  (reduce-only, opposite-side) market orders. Reuses the execute effect, which appends a
  fresh ledger so the revert is auditable."
  [state]
  (let [modal (get-in state contracts/execution-modal-path)
        plan (:plan modal)
        ledger (last (get-in state contracts/execution-history-path))]
    (cond
      (not (map? plan))
      []

      (:submitting? modal)
      []

      (not (map? ledger))
      []

      (:execution-disabled? plan)
      [[:effects/save
        contracts/execution-modal-error-path
        (or (:disabled-message plan)
            "Execution is disabled for this scenario.")]]

      :else
      (let [revert-plan (execution/build-revert-plan plan ledger)]
        (if-not (pos? (or (get-in revert-plan [:summary :ready-count]) 0))
          [[:effects/save
            contracts/execution-modal-error-path
            "No filled orders to revert."]]
          [[:effects/save contracts/execution-modal-submitting-path true]
           [:effects/save contracts/execution-modal-error-path nil]
           [:effects/execute-portfolio-optimizer-plan revert-plan]])))))

(defn restage-portfolio-optimizer-execution-smaller
  "Re-stages the unfilled rows at half size and returns the surface to :staged for a
  fresh arm/confirm. Pure state rewrite — no orders are sent. The prior ledger history
  is preserved so the halt remains auditable."
  [state]
  (let [modal (get-in state contracts/execution-modal-path)
        plan (:plan modal)
        ledger (last (get-in state contracts/execution-history-path))]
    (cond
      (not (map? plan))
      []

      (:submitting? modal)
      []

      :else
      (let [restaged (execution/build-restaged-plan plan ledger 0.5)]
        [[:effects/save (conj contracts/execution-modal-path :plan) restaged]
         [:effects/save phase-path :staged]
         [:effects/save contracts/execution-modal-error-path nil]
         [:effects/save contracts/execution-path
          (assoc (optimizer-defaults/default-execution-state)
                 :history (vec (get-in state contracts/execution-history-path)))]]))))

(defn pause-portfolio-optimizer-execution
  "Requests a pause/abort of an in-flight run. The submit loop checks this flag before
  releasing each remaining order: in-flight orders still settle, but nothing new is
  sent, and the run lands in the halted state for resume / re-stage / revert."
  [_state]
  [[:effects/save contracts/execution-abort-requested-path true]])

(defn discard-portfolio-optimizer-execution
  "Abort & discard: clears the staged plan and run state and returns to the
  Recommendation tab. No-op while a run is in flight (mirrors confirm's submitting
  guard)."
  [state]
  (if (:submitting? (get-in state contracts/execution-modal-path))
    []
    [[:effects/save contracts/execution-modal-path
      (optimizer-defaults/default-execution-modal-state)]
     [:effects/save contracts/execution-path
      (optimizer-defaults/default-execution-state)]
     [:effects/save contracts/ui-results-tab-path :recommendation]
     [:effects/replace-shareable-route-query]]))

(defn open-portfolio-optimizer-execution-in-ticket
  "Opens the trade ticket seeded with the first ready order's market, so a trader can
  place or adjust that leg manually."
  [state]
  (let [plan (get-in state (conj contracts/execution-modal-path :plan))
        ready (first (filter #(= :ready (:status %)) (:rows plan)))
        coin (or (:coin ready) (:instrument-id ready))]
    (if coin
      [[:actions/navigate (str "/trade?market=" coin)]]
      [])))
