(ns hyperopen.portfolio.optimizer.actions.vault-handoff
  "Seed a fresh optimizer draft from a set of vaults (a saved vault view or
  screener results). The draft replaces the working draft — saved scenarios are
  untouched — with:

  - a universe of exactly those vaults (resolved from the vault index, largest
    first, capped at `max-vaults`),
  - `{:kind :vault-view}` as the universe source, so the setup panel can say
    where the list came from,
  - optionally the Vault allocation rules: long-only, fully invested (1.00x
    gross and net) and a per-vault cap. Vault deposits cannot be shorted or
    levered, so the perp-oriented 2.00x defaults are unreachable for them.

  Pure: returns effect vectors. The caller navigates to the optimizer route
  afterwards; the route's draft restore only fills an UNTOUCHED draft, so it
  never overwrites this one."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.history-prefetch :as history-prefetch]
            [hyperopen.portfolio.optimizer.application.scenario-workflow :as scenario-workflow]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]))

(def max-vaults
  40)

(def vault-allocation-constraints
  {:long-only? true
   :gross-max 1.0
   :net-min 1.0
   :net-max 1.0
   :max-asset-weight 0.25})

(def objectives
  #{:minimum-variance :equal-risk :max-sharpe})

(def ^:private reset-paths
  "Workspace paths the New-scenario reset owns; a hand-off is a fresh start."
  [contracts/active-scenario-path
   contracts/last-successful-run-path
   contracts/run-state-path
   contracts/optimization-progress-path
   contracts/scenario-save-state-path
   contracts/scenario-save-modal-path
   contracts/scenario-load-state-path
   contracts/tracking-path
   contracts/draft-persist-path])

(defn- normalize-address
  [value]
  (some-> value str str/trim str/lower-case not-empty))

(defn vault-instruments
  "Universe instruments for `addresses`, in the given order, resolved through
  the vault index. Unknown, child or duplicate vaults are skipped."
  [state addresses]
  (let [rows (get-in state [:vaults :merged-index-rows])
        row-by-address (into {}
                             (keep (fn [row]
                                     (when-let [address (normalize-address (:vault-address row))]
                                       [address row])))
                             (if (sequential? rows) rows []))]
    (->> addresses
         (keep normalize-address)
         distinct
         (keep (fn [address]
                 (some-> (get row-by-address address)
                         universe-candidates/vault-row->candidate
                         common/market->universe-instrument)))
         (take max-vaults)
         vec)))

(defn handoff-draft
  [universe {:keys [name source-label view-id objective vault-allocation?]}]
  (let [objective* (if (contains? objectives objective) objective :equal-risk)
        draft (optimizer-defaults/default-draft)]
    (-> draft
        (assoc :name (or (some-> name str str/trim not-empty) "Vaults")
               :universe universe
               :objective {:kind objective*})
        (cond-> vault-allocation?
          (update :constraints merge vault-allocation-constraints))
        (assoc-in [:metadata :universe-source]
                  (cond-> {:kind :vault-view
                           :label (or source-label "vault selection")
                           :count (count universe)}
                    view-id (assoc :view-id view-id))))))

(defn seed-portfolio-optimizer-from-vaults
  "Effects that replace the working optimizer draft with a vault universe.
  Empty when none of the addresses resolve."
  [state handoff]
  (let [universe (vault-instruments state (:addresses handoff))]
    (if (empty? universe)
      []
      (let [draft (handoff-draft universe handoff)
            reset-state (scenario-workflow/reset-workspace-state state)
            seeded-state (assoc-in reset-state contracts/draft-path draft)
            prefetch-state (history-prefetch/cleanup-to-instrument-ids
                            (history-prefetch/prefetch-state seeded-state)
                            (keep :instrument-id universe))
            prefetch-plan (history-prefetch/enqueue-missing-instruments
                           (assoc-in seeded-state contracts/history-prefetch-path prefetch-state)
                           universe)
            path-values (-> (mapv (fn [path] [path (get-in reset-state path)]) reset-paths)
                            (conj [contracts/draft-path draft])
                            (conj [contracts/history-prefetch-path (:state prefetch-plan)]))]
        (cond-> (common/save-draft-path-values path-values)
          (:start? prefetch-plan)
          (conj history-prefetch/selection-prefetch-effect))))))
