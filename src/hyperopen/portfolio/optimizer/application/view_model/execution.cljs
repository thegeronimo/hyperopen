(ns hyperopen.portfolio.optimizer.application.view-model.execution
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.application.execution-amend :as execution-amend]
            [hyperopen.portfolio.optimizer.application.execution-carryover :as carryover]
            [hyperopen.portfolio.optimizer.application.execution-cloid :as cloid]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.spot-token-labels :as spot-token-labels]
            [hyperopen.portfolio.optimizer.application.view-model.execution-reconcile :as reconcile]
            [hyperopen.portfolio.optimizer.application.view-model.workspace :as workspace]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.trading-settings :as trading-settings]))

(def ^:private stale-recommendation-message
  "Inputs changed since this recommendation was computed — re-run the optimizer before executing.")

(def ^:private simulation-allowed-note
  "You can still model execution strategies and per-order types here to compare costs — arming and sending stay disabled.")

(defn- labels-by-instrument
  [state]
  (or (get-in state (conj contracts/last-successful-run-result-path
                          :labels-by-instrument))
      {}))

(defn- display-label
  "Human display symbol for an instrument id, mirroring the other optimizer surfaces:
  prefer the engine label, fall back to the trailing id segment (e.g.
  \"perp:xyz:xyz:SILVER\" -> \"SILVER\"), and resolve spot \"@N\" dust references
  through spotMeta (the asset-selector pattern) so users never see a bare pair index."
  [spot-resolver labels instrument-id]
  (let [value (str instrument-id)
        from-labels (get labels instrument-id)
        tail (last (str/split value #":"))
        base (some-> tail (str/split #"[/-]") first)
        named (cond
                (and from-labels (not (spot-token-labels/at-reference? from-labels)))
                from-labels

                (and base (not= base value) (not (spot-token-labels/at-reference? base)))
                base

                :else nil)]
    (or named
        (when (str/starts-with? value "spot:")
          (:base (spot-token-labels/display-fields spot-resolver tail tail)))
        from-labels
        base
        value)))

(defn- label-resolver
  "Closure resolving an instrument id to its display label from app state (engine
  labels + spotMeta)."
  [state]
  (let [labels (labels-by-instrument state)
        spot-resolver (spot-token-labels/resolver (get-in state [:spot :meta]))]
    (partial display-label spot-resolver labels)))

(defn- enrich-instrument-rows
  [resolve-label rows]
  (mapv (fn [row]
          (assoc row :instrument-label (resolve-label (:instrument-id row))))
        (or rows [])))

(defn- order-numbered-and-sorted
  "Stamp each display row with its 1-based ledger order number (the submission order) then
  sort the list by absolute notional, largest first — so the riskiest trade always leads the
  pre-commit order table while the displayed `#` stays tied to ledger order (the reference
  used by Resume / audit). Ties keep ledger order."
  [rows]
  (->> rows
       (map-indexed (fn [i row] (assoc row :order-no (inc i))))
       (sort-by (fn [row] [(- (js/Math.abs (or (:delta-notional-usd row) 0))) (:order-no row)]))
       vec))

(defn- latest-record
  [records]
  (last (vec records)))

(defn- enrich-execution-attempt
  [resolve-label attempt]
  (when attempt
    (assoc attempt :rows (enrich-instrument-rows resolve-label (:rows attempt)))))

(defn execution-modal-model
  [state]
  (let [modal (or (get-in state contracts/execution-modal-path) {})
        plan (or (:plan modal) {})
        summary (:summary plan)
        labels-by-instrument* (labels-by-instrument state)
        resolve-label (label-resolver state)
        latest-attempt (enrich-execution-attempt
                        resolve-label
                        (latest-record (get-in state contracts/execution-history-path)))
        submitting? (boolean (:submitting? modal))
        ready? (pos? (or (:ready-count summary) 0))
        confirm-disabled? (or submitting?
                              (:execution-disabled? plan)
                              (not ready?))
        plan* (assoc plan
                     :rows (enrich-instrument-rows resolve-label
                                                   (:rows plan)))]
    {:modal modal
     :open? (boolean (:open? modal))
     :plan plan*
     :summary summary
     :latest-attempt latest-attempt
     :labels-by-instrument labels-by-instrument*
     :submitting? submitting?
     :ready? ready?
     :confirm-disabled? (boolean confirm-disabled?)
     :disabled-message (or (:disabled-message plan)
                           "Order submission wiring is not enabled in this slice.")}))

(defn- stamp-amend-affordances
  "Stamps each still-working (reconciled :resting) row with its live amend context —
  {:amendable? true :oid :remaining-size :limit-px :live-mark :order-type :limit-bps} —
  so the order table can render the amend editor without touching state. A row whose
  oid can no longer be seen live on the book gets no :amend (there is nothing safe to
  cancel-and-replace); the selection keys come from the same `amend-selections` the
  amend action resolves, so what the editor shows is what a commit would submit."
  [state modal writable? rows]
  (if-not writable?
    rows
    (let [market-by-key (get-in state [:asset-selector :market-by-key])
          selections {:overrides (:overrides modal)
                      :params (:params modal)}]
      (mapv (fn [row]
              (if-let [target (when (= :resting (:status row))
                                (execution-amend/amend-target state market-by-key row))]
                (assoc row :amend
                       (merge (select-keys target
                                           [:oid :remaining-size :limit-px :live-mark])
                              {:amendable? true}
                              (execution-amend/amend-selections selections row)))
                row))
            rows))))

(def ^:private terminal-run-statuses
  #{:executed :resting :partially-executed :failed :blocked})

(defn- run-status
  [state]
  (get-in state (conj contracts/execution-path :status)))

(defn- derive-phase
  "Folds the thin engine state (submitting? + run :status) and the cosmetic pre-submit
  UI phase into the six v4 display phases."
  [{:keys [submitting? ui-phase status live-resting?]}]
  (cond
    submitting? :running
    (= :executed status) :done
    (= :resting status) :resting
    (and (terminal-run-statuses status) live-resting?) :partial
    (terminal-run-statuses status) :halted
    (= :armed ui-phase) :armed
    :else :staged))

(defn execution-tab-model
  "View-model for the v4 Execution tab. Reuses the modal plan/summary/latest-attempt
  derivation and layers the staging interaction state + derived display phase on top.
  Pre-submit the table shows the plan rows; after a run it shows the latest ledger
  attempt rows (which carry :submitted/:failed/:blocked + :error)."
  [state]
  (let [modal (or (get-in state contracts/execution-modal-path) {})
        plan (or (:plan modal) {})
        summary (:summary plan)
        labels (labels-by-instrument state)
        resolve-label (label-resolver state)
        latest-attempt (when-let [attempt (enrich-execution-attempt
                                           resolve-label
                                           (latest-record
                                            (get-in state contracts/execution-history-path)))]
                         (let [rrows (reconcile/reconcile-rows state (:rows attempt))]
                           (assoc attempt
                                  :rows rrows
                                  :status (execution/final-ledger-status rrows))))
        run-attempt (enrich-execution-attempt
                     resolve-label
                     (get-in state contracts/execution-run-attempt-path))
        submitting? (boolean (:submitting? modal))
        status (run-status state)
        terminal? (boolean (terminal-run-statuses status))
        ui-phase (or (:phase modal) :staged)
        plan-rows (enrich-instrument-rows resolve-label (:rows plan))
        reconciled-rows (reconcile/reconcile-rows
                         state
                         (cond
                           (and submitting? (seq (:rows run-attempt)))
                           (:rows run-attempt)

                           (and terminal? (seq (:rows latest-attempt)))
                           (:rows latest-attempt)

                           :else plan-rows))
        exit-ids (or (:exit-instrument-ids modal) #{})
        pre-run? (and (not submitting?) (not terminal?))
        stamp-exit (fn [row]
                     (cond-> row
                       (and pre-run? (contains? exit-ids (:instrument-id row)))
                       (assoc :exit? true)

                       (and pre-run?
                            (= :skipped (:status row))
                            (= :excluded-from-optimization (:reason row)))
                       (assoc :exitable? true)))
        display-rows (stamp-amend-affordances
                      state modal
                      (and (not (:execution-disabled? plan))
                           (not submitting?))
                      (mapv stamp-exit
                            (order-numbered-and-sorted reconciled-rows)))
        reconciled-status (if (= :resting status)
                            (execution/final-ledger-status reconciled-rows)
                            status)
        live-resting? (and terminal?
                           (boolean (some #(= :resting (:status %)) reconciled-rows)))
        phase (derive-phase {:submitting? submitting? :ui-phase ui-phase
                             :status reconciled-status :live-resting? live-resting?})
        ready? (pos? (or (:ready-count summary) 0))
        execution-disabled? (boolean (:execution-disabled? plan))
        scenario-stale? (boolean (workspace/scenario-stale?
                                  state (setup-readiness/build-readiness state)))
        stale-notice? (and scenario-stale? (not execution-disabled?))
        disabled-message (when execution-disabled?
                           (or (:disabled-message plan)
                               "Execution is disabled for this scenario."))
        commit-blocked-reason (when execution-disabled? (:disabled-reason plan))
        commit-blocked-message (when execution-disabled?
                                 (if (= :read-only commit-blocked-reason)
                                   (str disabled-message " " simulation-allowed-note)
                                   disabled-message))
        confirm-disabled? (or submitting?
                              execution-disabled?
                              scenario-stale?
                              (not ready?))
        ready-rows (filter #(= :ready (:status %)) plan-rows)
        side-notional (fn [side]
                        (->> ready-rows
                             (filter #(= side (:side %)))
                             (map #(js/Math.abs (or (:delta-notional-usd %) 0)))
                             (reduce + 0)))
        {owned-open :optimizer-owned untagged-open :untagged-overlap}
        (cloid/classify-overlap (cloid/live-open-orders state) ready-rows)
        session-carryover (carryover/live-resting-carryover
                           state
                           (get-in state contracts/execution-resting-carryover-path))
        auto-cancel-oids (into #{}
                               (map #(str (:oid %)))
                               (concat session-carryover owned-open))
        overlap-selections (or (get-in state (conj contracts/execution-modal-path
                                                   :overlap-cancels)) {})
        overlap-orders (mapv (fn [o]
                               (assoc o :cancel? (boolean
                                                  (get overlap-selections (str (:oid o))))))
                             untagged-open)]
    {:plan (assoc plan :rows plan-rows)
     :summary summary
     :carryover-count (count auto-cancel-oids)
     :overlap-orders overlap-orders
     :rows display-rows
     :phase phase
     :abort-requested? (boolean (get-in state (conj contracts/execution-path :abort-requested?)))
     :ready-buys-usd (side-notional :buy)
     :ready-sells-usd (side-notional :sell)
     :run-status status
     :terminal? terminal?
     :latest-attempt latest-attempt
     :labels-by-instrument labels
     :default-order-type (or (:default-order-type modal) :recommended)
     :overrides (or (:overrides modal) {})
     :params (or (:params modal) {})
     :open-row (:open-row modal)
     :order-filter (or (:order-filter modal) :all)
     :auto-exit-excluded? (boolean (trading-settings/optimizer-auto-exit-excluded? state))
     :submitting? submitting?
     :error (:error modal)
     :ready? ready?
     :commit-blocked? execution-disabled?
     :commit-blocked-reason commit-blocked-reason
     :commit-blocked-message commit-blocked-message
     :stale? stale-notice?
     :stale-message (when stale-notice? stale-recommendation-message)
     :arm-disabled? (or execution-disabled? scenario-stale?)
     :arm-disabled-message (cond
                             execution-disabled? disabled-message
                             scenario-stale? stale-recommendation-message
                             :else nil)
     :confirm-disabled? (boolean confirm-disabled?)
     :disabled-message disabled-message
     :has-plan? (boolean (seq (:rows plan)))}))
