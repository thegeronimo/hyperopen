(ns hyperopen.portfolio.optimizer.actions.default-assumptions
  "Actions applying the backend's recommended history assumptions (the
  discovery rows' `default-assumption` blocks) to the proxy workflow — one
  card, or every pending card at once. Decisions live in the pure
  `application.default-assumptions`; these actions assemble state and emit
  effects.

  The apply is the same BULK funnel as the agent-file import (one draft save,
  one assumption-library sync, one reference-instrument reconcile, one
  prefetch batch), because a single recommendation can add several
  out-of-universe basket members at once."
  (:require [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.assumption-library :as assumption-library]
            [hyperopen.portfolio.optimizer.application.default-assumptions :as default-assumptions]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as history-api-v2]
            [hyperopen.portfolio.optimizer.application.history-prefetch :as history-prefetch]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.view-model.universe :as universe-vm]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]))

(defn- note-effect
  [kind message]
  [:effects/save contracts/ui-history-assumptions-io-note-path
   {:kind kind :message message}])

(defn- discovery-state
  [state]
  (get-in state contracts/history-discovery-path))

(defn- assumptions-map
  [state]
  (or (get-in state contracts/draft-history-assumptions-path) {}))

(defn- reference-instruments
  [state]
  (vec (or (get-in state contracts/draft-proxy-reference-instruments-path) [])))

(defn- resolve-catalog-instrument
  ;; Same resolution the per-chip proxy add and the file import perform:
  ;; shared catalog resolver + backend history-identity decoration, else
  ;; alignment rejects the stored instrument as identity-ambiguous.
  [state proxy-id]
  (when-let [market (universe-candidates/resolve-market state proxy-id)]
    (common/market->universe-instrument
     (history-api-v2/with-discovery-metadata market (discovery-state state)))))

(defn- referenced-proxy-ids
  [assumptions]
  (into #{}
        (mapcat (fn [[_ entry]]
                  (when (history-assumptions/proxy? entry)
                    (history-assumptions/proxy-instrument-ids entry))))
        assumptions))

(def ^:private workflow-adequacies
  ;; Same enrollment signal as the assumption cards and the file export: a
  ;; recommendation reaches only assets whose history adequacy warrants a
  ;; card. It must never touch an asset with real usable history.
  #{:none :short})

(defn- recommendation-targets
  "Entry-less workflow assets ({:instrument-id local :backend-id backend})
  whose id passes `pred`, in universe order."
  [state pred]
  (let [discovery (discovery-state state)
        assumptions (assumptions-map state)
        readiness (setup-readiness/build-readiness state)
        load-state (get-in state contracts/history-load-state-path)
        history-status-by-id (setup-readiness/history-status-by-instrument readiness)]
    (into []
          (keep (fn [instrument]
                  (let [id (:instrument-id instrument)
                        backend-id (:optimizer-history/instrument-id
                                    (history-api-v2/with-discovery-metadata
                                     instrument discovery))
                        status (universe-vm/selected-history-status
                                state readiness load-state
                                history-status-by-id instrument)
                        adequacy (universe-vm/history-adequacy
                                  status state readiness instrument)]
                    (when (and id
                               backend-id
                               (pred id)
                               (not (contains? assumptions id))
                               (contains? workflow-adequacies adequacy))
                      {:instrument-id id :backend-id backend-id}))))
          (get-in state contracts/draft-universe-path))))

(defn- plan-effects
  "The import channel's bulk apply, driven by a recommendation plan: one draft
  save (assumptions + reconciled references + prefetch state), one library
  sync carrying every upsert, and the outcome note."
  [state plan]
  (let [assumptions* (:assumptions plan)
        applied-ids (mapv :instrument-id (:applied plan))
        universe-ids (into #{}
                           (keep :instrument-id)
                           (get-in state contracts/draft-universe-path))
        refs (reference-instruments state)
        new-instruments (:new-reference-instruments plan)
        known (reduce (fn [acc instrument]
                        (assoc acc (:instrument-id instrument) instrument))
                      {}
                      (concat refs new-instruments))
        refs* (->> (referenced-proxy-ids assumptions*)
                   (remove universe-ids)
                   sort
                   (keep known)
                   vec)
        prefetch-plan (when (seq new-instruments)
                        (history-prefetch/enqueue-missing-instruments
                         state new-instruments))
        path-values (cond-> [[contracts/draft-history-assumptions-path assumptions*]]
                      (not= refs* refs)
                      (conj [contracts/draft-proxy-reference-instruments-path refs*])

                      (:changed? prefetch-plan)
                      (conj [contracts/history-prefetch-path (:state prefetch-plan)]))
        upserts (mapv (fn [instrument-id]
                        (let [entry (get assumptions* instrument-id)]
                          {:instrument-id instrument-id
                           :assumption entry
                           :reference-instruments
                           (assumption-library/entry-reference-instruments refs* entry)}))
                      applied-ids)]
    (cond-> (conj (common/save-draft-path-values path-values)
                  [:effects/sync-portfolio-optimizer-assumption-library
                   {:upserts upserts}])
      (:start? prefetch-plan) (conj history-prefetch/selection-prefetch-effect)
      true (conj (note-effect :success
                              (default-assumptions/apply-note-message plan))))))

(defn- recommended-apply-plan
  [state pred]
  (let [discovery (discovery-state state)
        refs (reference-instruments state)
        known-refs (reduce (fn [acc instrument]
                             (assoc acc (:instrument-id instrument) instrument))
                           {}
                           refs)
        resolve-member (fn [{:keys [instrument-id backend-id]}]
                         (or (get known-refs instrument-id)
                             (resolve-catalog-instrument state instrument-id)
                             (default-assumptions/external-reference-instrument
                              (default-assumptions/discovery-instrument
                               discovery backend-id))))]
    (default-assumptions/recommendation-plan
     {:discovery discovery
      :assumptions (assumptions-map state)
      :universe-ids (into #{}
                          (keep :instrument-id)
                          (get-in state contracts/draft-universe-path))
      :resolve-member resolve-member
      :targets (recommendation-targets state pred)})))

(defn- apply-recommended
  [state pred]
  (let [plan (recommended-apply-plan state pred)]
    (if (empty? (:applied plan))
      [(note-effect :error (default-assumptions/apply-note-message plan))]
      (plan-effects state plan))))

(defn pending-recommended-apply-effects
  "Bulk-apply effects for every pending applicable backend recommendation, or
  nil when nothing would apply. The Run click's auto-apply keys off nil to fall
  through to a plain pipeline run; the explicit apply-all action keeps its own
  error-note path for the empty case."
  [state]
  (let [plan (recommended-apply-plan state (constantly true))]
    (when (seq (:applied plan))
      (plan-effects state plan))))

(defn apply-portfolio-optimizer-recommended-history-assumption
  "One-click apply of the backend's recommendation for ONE workflow asset:
  seeds the recommended mode, basket, relationship strength, and rationale as
  an acknowledged assumption. Members whose history the backend cannot serve
  yet are held back and disclosed in the note."
  [state instrument-id]
  (if-let [instrument-id* (common/non-blank-text instrument-id)]
    (apply-recommended state #(= instrument-id* %))
    []))

(defn apply-portfolio-optimizer-recommended-history-assumptions
  "Applies every pending backend recommendation across the proxy workflow.
  Assets the user already configured are never touched."
  [state]
  (apply-recommended state (constantly true)))
