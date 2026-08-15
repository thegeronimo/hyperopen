(ns hyperopen.portfolio.optimizer.actions.history-assumptions-io
  "Actions for the proxy workflow's file channel: export the assets that need
  history assumptions as a JSON template for a desktop AI agent to fill in, and
  import the completed file back as authored assumptions. Decisions live in the
  pure `application.history-assumptions-io`; these actions assemble state and
  emit effects. Feedback lands in an inline section note, not the global toast.

  The import apply is deliberately a BULK funnel (one draft save, one
  assumption-library sync carrying every upsert, one reference-instrument
  reconcile, one prefetch batch): looping the per-asset funnel in
  actions.draft would derive each save from pre-dispatch state and clobber the
  earlier writes."
  (:require [hyperopen.asset-selector.query :as asset-query]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.application.assumption-library :as assumption-library]
            [hyperopen.portfolio.optimizer.application.history-assumptions-io :as history-assumptions-io]
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

(defn- draft-universe
  [state]
  (vec (or (get-in state contracts/draft-universe-path) [])))

(defn- assumptions-map
  [state]
  (or (get-in state contracts/draft-history-assumptions-path) {}))

(defn- reference-instruments
  [state]
  (vec (or (get-in state contracts/draft-proxy-reference-instruments-path) [])))

(defn- catalog-markets
  "Live perp/spot catalog rows, volume-descending — the same source the proxy
  picker searches (a blank query passes everything through the sort). Vaults
  are excluded from the file channel: address-labeled entries are noise to an
  agent, and the in-app picker still offers them."
  [state]
  (into []
        (filter (fn [market]
                  (and (common/non-blank-text (:key market))
                       (common/non-blank-text (:coin market))
                       (:market-type market))))
        (asset-query/filter-and-sort-assets
         (get-in state [:asset-selector :markets])
         ""
         :volume
         :desc
         #{}
         false
         false
         :all)))

;; --- Export ---------------------------------------------------------------------

(def ^:private workflow-adequacies
  ;; Same enrollment signal as the assumption cards: an asset joins the
  ;; workflow-scoped export when it already carries a draft entry or its
  ;; history adequacy warrants a card (:pending and :ok never do).
  #{:none :short})

(defn- blocklisted?
  ;; Excluded universe rows are "not being considered by the user" — they stay
  ;; out of the universe-scoped export and out of the candidate menu.
  [blocklist instrument]
  (boolean (some #(common/instrument-matches-id? instrument %) blocklist)))

(defn- export-inputs
  "The pure model's inputs, assembled from state exactly the way the cards
  view-model reads the workflow (same adequacy signal, same observation
  source). Both the target rows and the candidate menu are USER-SELECTED
  assets only — the workflow/universe scope picks the rows; the menu is the
  included universe plus previously picked reference proxies, never the
  exchange catalog (which buried the agent in unloaded rows)."
  [state scope]
  (let [universe (draft-universe state)
        assumptions (assumptions-map state)
        refs (reference-instruments state)
        blocklist (vec (or (get-in state (conj contracts/draft-constraints-path :blocklist)) []))
        readiness (setup-readiness/build-readiness state)
        load-state (get-in state contracts/history-load-state-path)
        history-status-by-id (setup-readiness/history-status-by-instrument readiness)
        objective-kind (get-in state (conj contracts/draft-objective-path :kind))
        return-required? (history-assumptions/return-required-for-objective? objective-kind)
        observations #(universe-vm/native-history-observations state readiness %)
        describe (fn [instrument]
                   {:instrument-id (:instrument-id instrument)
                    :symbol (universe-vm/instrument-primary-label instrument)
                    :name (:name (universe-candidates/market-display instrument))
                    :kind (some-> (:market-type instrument) name)})
        verified? (fn [obs]
                    (boolean (and obs
                                  (>= obs history-assumptions/short-history-min-observations))))
        row-for (fn [instrument entry]
                  (let [obs (observations instrument)]
                    (assoc (describe instrument)
                           :native-days obs
                           :verified? (verified? obs)
                           :status (cond
                                     (nil? entry) "needs-assumption"
                                     (history-assumptions/assumption-complete?
                                      entry return-required?) "configured"
                                     :else "in-progress")
                           :entry entry)))
        rows (into []
                   (keep (fn [instrument]
                           (let [id (:instrument-id instrument)
                                 entry (get assumptions id)]
                             (when id
                               (if (= :universe scope)
                                 (when-not (blocklisted? blocklist instrument)
                                   (row-for instrument entry))
                                 (let [status (universe-vm/selected-history-status
                                               state readiness load-state
                                               history-status-by-id instrument)
                                       adequacy (universe-vm/history-adequacy
                                                 status state readiness instrument)]
                                   (when (or (some? entry)
                                             (contains? workflow-adequacies adequacy))
                                     (row-for instrument entry))))))))
                   universe)]
    ;; No proxy-candidates menu in EITHER scope (maintainer decision
    ;; 2026-07-07): "Export proxy assets" carries ONLY the workflow assets —
    ;; the agent proposes proxies from its own market knowledge and import
    ;; validation drops (and reports) anything unknown or short-history. The
    ;; universe export's `assets` list carries a per-asset `history-status`
    ;; for the same job.
    {:rows rows
     :objective-kind objective-kind
     :scope scope
     ;; Cover every id a stored basket may reference: universe/reference
     ;; instruments first, then rows (same values, richer labels).
     :symbol-by-instrument-id (into {}
                                    (concat
                                     (map (juxt :instrument-id
                                                #(universe-vm/instrument-primary-label %))
                                          (concat universe refs))
                                     (map (juxt :instrument-id :symbol) rows)))}))

(defn export-portfolio-optimizer-history-assumptions
  "Scope :proxy-workflow (default) exports just the assets already flagged in
  the workflow; :universe exports every included (non-blocklisted) universe
  asset so the agent can also flag ones it judges untrustworthy."
  ([state]
   (export-portfolio-optimizer-history-assumptions state :proxy-workflow))
  ([state scope]
   (let [scope* (history-assumptions-io/normalize-export-scope
                 (common/normalize-keyword-like scope))
         inputs (export-inputs state scope*)
         payload (history-assumptions-io/export-payload
                  (assoc inputs :now-ms (platform/now-ms)))]
     (if (pos? (:count payload))
       [[:effects/download-portfolio-optimizer-history-assumptions-file payload]
        (note-effect :success
                     (history-assumptions-io/export-success-message (:count payload)))]
       [(note-effect :error (if (= :universe scope*)
                              history-assumptions-io/export-universe-empty-message
                              history-assumptions-io/export-empty-message))]))))

(defn import-portfolio-optimizer-history-assumptions
  [_state]
  [[:effects/pick-portfolio-optimizer-history-assumptions-file]])

;; --- Import apply -----------------------------------------------------------------

(defn- resolve-catalog-instrument
  ;; Same resolution the per-chip proxy add performs: shared catalog resolver +
  ;; backend history-identity decoration, else alignment rejects the stored
  ;; instrument as identity-ambiguous.
  [state proxy-id]
  (when-let [market (universe-candidates/resolve-market state proxy-id)]
    (common/market->universe-instrument
     (history-api-v2/with-discovery-metadata
      market
      (get-in state contracts/history-discovery-path)))))

(defn- referenced-proxy-ids
  [assumptions]
  (into #{}
        (mapcat (fn [[_ entry]]
                  (when (history-assumptions/proxy? entry)
                    (history-assumptions/proxy-instrument-ids entry))))
        assumptions))

(defn apply-imported-portfolio-optimizer-history-assumptions
  [state data]
  (let [universe (draft-universe state)
        refs (reference-instruments state)
        plan (history-assumptions-io/import-plan
              {:assumptions (assumptions-map state)
               :universe universe
               :proxy-pool (concat refs (catalog-markets state))
               :data data})]
    (cond
      (not= :ok (:status plan))
      [(note-effect :error (history-assumptions-io/import-error-message (:reason plan)))]

      (zero? (:applied plan))
      [(note-effect :error (history-assumptions-io/import-success-message plan))]

      :else
      (let [assumptions* (:assumptions plan)
            applied-ids (:applied-instrument-ids plan)
            universe-ids (into #{} (keep :instrument-id) universe)
            referenced (referenced-proxy-ids assumptions*)
            known-ref-ids (into #{} (keep :instrument-id) refs)
            new-instruments (into []
                                  (keep (fn [proxy-id]
                                          (when-not (contains? known-ref-ids proxy-id)
                                            (resolve-catalog-instrument state proxy-id))))
                                  (->> referenced (remove universe-ids) sort))
            known (reduce (fn [acc instrument]
                            (assoc acc (:instrument-id instrument) instrument))
                          {}
                          (concat refs new-instruments))
            refs* (->> referenced
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
                                  (history-assumptions-io/import-success-message plan))))))))

(defn dismiss-portfolio-optimizer-history-assumptions-io-note
  [_state]
  [[:effects/save contracts/ui-history-assumptions-io-note-path nil]])
