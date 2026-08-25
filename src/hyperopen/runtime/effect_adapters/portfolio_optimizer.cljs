(ns hyperopen.runtime.effect-adapters.portfolio-optimizer
  (:require [nexus.registry :as nxr]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.default :as api]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.config :as app-config]
            [hyperopen.portfolio.optimizer.application.assumption-library :as assumption-library]
            [hyperopen.portfolio.optimizer.application.constraint-profiles :as constraint-profiles]
            [hyperopen.portfolio.optimizer.application.view-library :as view-library]
            [hyperopen.portfolio.optimizer.application.rebalance-snapshot :as rebalance-snapshot]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as history-api-v2]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.infrastructure.draft-autosave :as draft-autosave]
            [hyperopen.portfolio.optimizer.infrastructure.history-api-v2-client :as history-api-v2-client]
            [hyperopen.portfolio.optimizer.infrastructure.history-client :as history-client]
            [hyperopen.portfolio.optimizer.infrastructure.persistence :as persistence]
            [hyperopen.portfolio.optimizer.infrastructure.run-bridge :as run-bridge]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer.execution :as execution]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer.history :as history]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer.tracking :as tracking]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-pipeline :as pipeline]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-scenarios :as scenario-effects]))

(def ^:dynamic *request-run!* run-bridge/request-run!)
(def ^:dynamic *request-history-bundle!* history-client/request-history-bundle!)
(def ^:dynamic *request-candle-snapshot!* api/request-candle-snapshot!)
(def ^:dynamic *request-l2-book-snapshot!* api/request-l2-book-snapshot!)
(def ^:dynamic *request-market-funding-history!* api/request-market-funding-history!)
(def ^:dynamic *request-vault-details!* api/request-vault-details!)
(def ^:dynamic *optimizer-history-api-config*
  (:optimizer-history-api app-config/config))
(def ^:dynamic *optimizer-history-api-fetch* js/fetch)
(def ^:dynamic *optimizer-history-api-request-id*
  (fn []
    (str "optimizer-history-"
         (.now js/Date)
         "-"
         (js/Math.floor (* 1000000000 (js/Math.random))))))
(def ^:dynamic *load-scenario-index!* persistence/load-scenario-index!)
(def ^:dynamic *load-scenario!* persistence/load-scenario!)
(def ^:dynamic *load-draft!* persistence/load-draft!)
(def ^:dynamic *load-history-cache!* persistence/load-history-cache!)
(def ^:dynamic *delete-draft!* persistence/delete-draft!)
(def ^:dynamic *save-scenario!* persistence/save-scenario!)
(def ^:dynamic *save-scenario-index!* persistence/save-scenario-index!)
(def ^:dynamic *load-tracking!* persistence/load-tracking!)
(def ^:dynamic *save-tracking!* persistence/save-tracking!)
(def ^:dynamic *load-constraint-profiles!* persistence/load-constraint-profiles!)
(def ^:dynamic *save-constraint-profiles!* persistence/save-constraint-profiles!)
(def ^:dynamic *load-view-library!* persistence/load-view-library!)
(def ^:dynamic *save-view-library!* persistence/save-view-library!)
(def ^:dynamic *load-assumption-library!* persistence/load-assumption-library!)
(def ^:dynamic *save-assumption-library!* persistence/save-assumption-library!)
(def ^:dynamic *next-scenario-id* (fn [now-ms] (str "scn_" now-ms)))
(def ^:dynamic *now-ms* #(.now js/Date))
(def ^:dynamic *submit-order!* trading-api/submit-order!)
(def ^:dynamic *dispatch!* nxr/dispatch)

(def ^:private optimizer-history-api-override-fields
  [{:target :enabled?
    :aliases [:enabled? :enabled]
    :kind :boolean
    :fallback false}
   {:target :base-url
    :aliases [:base-url :baseUrl]
    :kind :value}
   {:target :proxy-policy
    :aliases [:proxy-policy :proxyPolicy]
    :kind :keyword}
   {:target :include-aligned-returns?
    :aliases [:include-aligned-returns? :includeAlignedReturns]
    :kind :boolean
    :fallback true}
   {:target :fallback-to-legacy?
    :aliases [:fallback-to-legacy? :fallbackToLegacy]
    :kind :boolean
    :fallback true}])

(defn- first-present-alias
  [m aliases]
  (some (fn [alias]
          (when (contains? m alias)
            [alias (get m alias)]))
        aliases))

(defn- first-truthy-alias-value
  [m aliases]
  (some #(get m %) aliases))

(defn- boolean-override-entry
  [m target aliases fallback]
  (when-let [[_alias value] (first-present-alias m aliases)]
    [target (if (boolean? value)
              value
              fallback)]))

(defn- boolean-override-field-entry
  [m {:keys [target aliases fallback]}]
  (boolean-override-entry m target aliases fallback))

(defn- keyword-override-field-entry
  [m {:keys [target aliases]}]
  (when-let [value (first-truthy-alias-value m aliases)]
    [target (coercion/normalize-keyword-like value)]))

(defn- value-override-field-entry
  [m {:keys [target aliases]}]
  (when-let [value (first-truthy-alias-value m aliases)]
    [target value]))

(def ^:private override-field-entry-fn-by-kind
  {:boolean boolean-override-field-entry
   :keyword keyword-override-field-entry
   :value value-override-field-entry})

(defn- override-field-entry
  [m {:keys [kind] :as field}]
  (when-let [entry-fn (get override-field-entry-fn-by-kind kind)]
    (entry-fn m field)))

(defn- normalize-optimizer-history-api-browser-override
  [m]
  (reduce (fn [acc field]
            (if-let [[target value] (override-field-entry m field)]
              (assoc acc target value)
              acc))
          {}
          optimizer-history-api-override-fields))

(defn- optimizer-history-api-browser-override
  []
  (when-let [raw (aget js/globalThis "__HYPEROPEN_OPTIMIZER_HISTORY_API__")]
    (normalize-optimizer-history-api-browser-override
     (js->clj raw :keywordize-keys true))))

(defn- optimizer-history-api-config
  []
  (merge *optimizer-history-api-config*
         (optimizer-history-api-browser-override)))

(defn- request-candle-snapshot!
  [coin opts]
  (*request-candle-snapshot!* coin
                             :interval (:interval opts)
                             :bars (:bars opts)
                             :priority (:priority opts)))

(defn- request-l2-book-snapshot!
  [coin opts]
  (*request-l2-book-snapshot!* coin opts))

(defn- history-env
  []
  {:now-ms *now-ms*
   :request-history-bundle! *request-history-bundle!*
   :request-candle-snapshot! request-candle-snapshot!
   :request-market-funding-history! *request-market-funding-history!*
   :request-vault-details! *request-vault-details!*
   :optimizer-history-api (optimizer-history-api-config)
   :fetch-fn *optimizer-history-api-fetch*
   :request-id *optimizer-history-api-request-id*})

(defn- scenario-env
  []
  {:now-ms *now-ms*
   :next-scenario-id *next-scenario-id*
   :load-scenario-index! *load-scenario-index!*
   :load-scenario! *load-scenario!*
   :load-tracking! *load-tracking!*
   :save-scenario! *save-scenario!*
   :save-scenario-index! *save-scenario-index!*
   :dispatch! *dispatch!*})

(declare ^:dynamic *refresh-account-open-orders!*)

(defn- execution-env
  []
  {:now-ms *now-ms*
   :submit-order! *submit-order!*
   :dispatch! *dispatch!*
   ;; Refreshes base + per-dex frontendOpenOrders after a run leaves orders resting:
   ;; the streams never cover named-dex rows, so without this a resting HIP-3 order
   ;; is invisible to the merged book (not amendable, no on-book reconcile signal).
   ;; Captured by value (like :submit-order!) so test rebindings survive the async
   ;; continuation that eventually invokes it.
   :refresh-open-orders! *refresh-account-open-orders!*
   :load-scenario! *load-scenario!*
   :load-scenario-index! *load-scenario-index!*
   :save-scenario! *save-scenario!*
   :save-scenario-index! *save-scenario-index!*})

(defn- tracking-env
  []
  {:now-ms *now-ms*
   :load-tracking! *load-tracking!*
   :save-tracking! *save-tracking!*})

(defn make-portfolio-optimizer-controller-resolver
  [_runtime]
  (run-bridge/make-controller-resolver))

(defn- request-run-with-controller!
  [controller store request request-signature opts]
  (*request-run!*
   (cond-> {:request request
            :request-signature request-signature
            :store store}
     controller
     (assoc :controller controller)
     (contains? opts :computed-at-ms)
     (assoc :computed-at-ms (:computed-at-ms opts))
     (contains? opts :run-id)
     (assoc :run-id (:run-id opts)))))

(defn run-portfolio-optimizer-effect
  ([_ store request request-signature]
   (run-portfolio-optimizer-effect nil store request request-signature nil))
  ([_ store request request-signature opts]
   (request-run-with-controller! nil store request request-signature (or opts {}))))

(defn- snapshot-request-opts
  [opts]
  {:priority :low
   :cache-ttl-ms (or (:snapshot-cache-ttl-ms opts)
                     rebalance-snapshot/default-snapshot-cache-ttl-ms)})

(defn- fetch-snapshot-contexts!
  [{:keys [request-l2-book-snapshot! now-ms]} plan opts]
  (let [opts* (or opts {})
        now-ms* (now-ms)
        request-opts (snapshot-request-opts opts*)]
    (reduce
     (fn [promise request]
       (.then promise
              (fn [contexts-by-id]
                (-> (request-l2-book-snapshot! (:coin request) request-opts)
                    (.then
                     (fn [payload]
                       (if-let [context (rebalance-snapshot/normalize-l2-book-snapshot-context
                                         (:coin request)
                                         payload
                                         {:now-ms now-ms*
                                          :snapshot-stale-after-ms
                                          (or (:snapshot-stale-after-ms opts*)
                                              (:snapshot-stale-after-ms plan))})]
                         (merge contexts-by-id
                                (zipmap (:instrument-ids request)
                                        (repeat context)))
                         contexts-by-id)))
                    (.catch (fn [_err]
                              contexts-by-id))))))
     (js/Promise.resolve {})
     (:requests plan))))

(defn refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect
  ([_ store]
   (refresh-portfolio-optimizer-rebalance-slippage-snapshots-effect nil store {}))
  ([_ store opts]
   (let [last-run (get-in @store contracts/last-successful-run-path)
         plan (rebalance-snapshot/build-snapshot-refresh-plan last-run opts)
         ;; Resolved here, not at the call site: the dispatch happens in a .then, long
         ;; after a with-redefs around the effect call would have been unwound.
         dispatch! (or (:dispatch! opts) *dispatch!*)]
     (if-not (seq (:requests plan))
       (js/Promise.resolve plan)
       (-> (fetch-snapshot-contexts! {:request-l2-book-snapshot!
                                      (or (:request-l2-book-snapshot! opts)
                                          request-l2-book-snapshot!)
                                      :now-ms (or (:now-ms-fn opts)
                                                  *now-ms*)}
                                     plan
                                     opts)
           (.then
            (fn [contexts-by-id]
              (when (seq contexts-by-id)
                (swap! store
                       update-in
                       contracts/last-successful-run-path
                       (fn [current-run]
                         (if (= (:request-signature last-run)
                                (:request-signature current-run))
                           (rebalance-snapshot/last-run-with-snapshot-contexts
                            current-run
                            contexts-by-id)
                           current-run)))
                ;; The Execution tab holds a plan SNAPSHOT, so re-costing the run is only
                ;; half the job — without this the surface keeps showing the estimate it
                ;; staged before the books arrived. The action itself refuses to touch an
                ;; armed, submitting, or post-run plan.
                (dispatch! store nil
                           [[:actions/restage-portfolio-optimizer-execution-plan]]))
              {:plan plan
               :contexts-by-id contexts-by-id})))))))

(defn make-run-portfolio-optimizer
  ([runtime]
   (make-run-portfolio-optimizer
    runtime
    (make-portfolio-optimizer-controller-resolver runtime)))
  ([_runtime controller-resolver]
   (fn [_ store request request-signature & [opts]]
     (request-run-with-controller! (controller-resolver store)
                                   store
                                   request
                                   request-signature
                                   (or opts {})))))

(defn load-portfolio-optimizer-history-effect
  ([_ store]
   (load-portfolio-optimizer-history-effect nil store nil))
  ([_ store opts]
   (history/load-portfolio-optimizer-history-effect
    (history-env)
    nil
    store
    opts)))

(defn- discovery-loading-state
  [request-id started-at-ms]
  (assoc (optimizer-defaults/default-history-discovery-state)
         :status :loading
         :request-id request-id
         :loaded-at-ms started-at-ms))

(defn- discovery-failed-state
  [current err completed-at-ms]
  (assoc (merge (optimizer-defaults/default-history-discovery-state)
                current)
         :status :failed
         :loaded-at-ms completed-at-ms
         :error {:message (or (some-> err .-message)
                              (str err))}))

(defn- apply-discovery!
  [store value]
  (swap! store assoc-in contracts/history-discovery-path value)
  value)

(defn load-portfolio-optimizer-history-discovery-effect
  [_ store]
  (let [config (optimizer-history-api-config)
        fetch-fn *optimizer-history-api-fetch*
        request-id-fn *optimizer-history-api-request-id*
        now-ms-fn *now-ms*]
    (if-not (:enabled? config)
      (js/Promise.resolve nil)
      (let [request-id (request-id-fn)
            started-at-ms (now-ms-fn)]
        (apply-discovery! store (discovery-loading-state request-id started-at-ms))
        (-> (history-api-v2-client/request-instruments!
             {:fetch-fn fetch-fn
              :base-url (:base-url config)
              :request-id request-id
              :request-timeout-ms (:request-timeout-ms config)})
            (.then (fn [body]
                     (let [discovery (assoc (history-api-v2/normalize-discovery body)
                                            :loaded-at-ms (now-ms-fn)
                                            :error nil)]
                       (apply-discovery! store discovery)
                       discovery)))
            (.catch (fn [err]
                      (apply-discovery!
                       store
                        (discovery-failed-state
                         (get-in @store contracts/history-discovery-path)
                         err
                         (now-ms-fn))))))))))

(defn save-portfolio-optimizer-constraint-default-effect
  "Persist the current draft constraints as the remembered default for this wallet + universe.
  A no-op (resolves false) when the account is read-only/spectated or there is no universe yet."
  [_ store]
  (let [state @store
        address (account-context/effective-account-address state)]
    (if-not (and address (account-context/mutations-allowed? state))
      (js/Promise.resolve false)
      (let [universe-key (constraint-profiles/universe-key
                          (get-in state contracts/draft-universe-path))]
        (if-not universe-key
          (js/Promise.resolve false)
          (let [record (constraint-profiles/profile-record
                        (get-in state contracts/draft-constraints-path)
                        universe-key
                        (*now-ms*))
                profile-map (constraint-profiles/put-profile
                             (get-in state contracts/constraint-profiles-path)
                             record)]
            (swap! store assoc-in contracts/constraint-profiles-path profile-map)
            (-> (*save-constraint-profiles!* address profile-map)
                (.catch (fn [_err] false)))))))))

(defn load-portfolio-optimizer-view-library-effect
  "Load the wallet's remembered return views into the state mirror. Read-only with
  respect to the draft: hydration into draft views happens through the preset /
  return-model actions (which read the mirror), so a slow IndexedDB read can never
  clobber edits or race the draft restore."
  [_ store]
  (let [address (account-context/effective-account-address @store)]
    (if-not address
      (js/Promise.resolve nil)
      (-> (*load-view-library!* address)
          (.then (fn [record]
                   (let [entries (view-library/record->entries record)]
                     (swap! store assoc-in contracts/view-library-path entries)
                     entries)))
          (.catch (fn [_err] nil))))))

(defn sync-portfolio-optimizer-view-library-effect
  "Apply row-level upserts/removes from the view-edit actions to the wallet's
  remembered return views. Timestamps are stamped here. State always updates (the
  panel's provenance ages read from it); the IndexedDB write is skipped for
  read-only/spectated accounts, matching the constraint-default effect."
  [_ store sync]
  (let [state @store
        address (account-context/effective-account-address state)
        entries (view-library/apply-sync
                 (get-in state contracts/view-library-path)
                 (or sync {})
                 (*now-ms*))]
    (swap! store assoc-in contracts/view-library-path entries)
    (if-not (and address (account-context/mutations-allowed? state))
      (js/Promise.resolve false)
      (-> (*save-view-library!* address (view-library/library-record address entries))
          (.catch (fn [_err] false))))))

(defn download-portfolio-optimizer-return-views-file-effect
  "Download the return-views export document as a JSON file (Blob + anchor,
  mirroring the spectate watchlist download)."
  [_ _ {:keys [filename] doc :document}]
  (when (and (exists? js/document)
             (exists? js/URL))
    (let [json (js/JSON.stringify (clj->js doc) nil 2)
          blob (js/Blob. #js [json] #js {:type "application/json;charset=utf-8"})
          url (.createObjectURL js/URL blob)
          link (.createElement js/document "a")]
      (set! (.-href link) url)
      (set! (.-download link) filename)
      (.appendChild (.-body js/document) link)
      (.click link)
      (.removeChild (.-body js/document) link)
      (.revokeObjectURL js/URL url))))

(defn- read-return-views-file-as-json!
  [file on-data]
  (let [reader (js/FileReader.)]
    (set! (.-onload reader)
          (fn [_]
            (let [text (.-result reader)
                  data (try
                         (js->clj (js/JSON.parse text))
                         (catch :default _ ::invalid))]
              (on-data (when (not= data ::invalid) data)))))
    (set! (.-onerror reader)
          (fn [_] (on-data nil)))
    (.readAsText reader file)))

(defn pick-portfolio-optimizer-return-views-file-effect
  "Open a file picker for a return-views JSON file and hand the parsed data to
  the apply action (nil data → the action reports an invalid-file note)."
  [_ store]
  (when (exists? js/document)
    (let [input (.createElement js/document "input")]
      (set! (.-type input) "file")
      (set! (.-accept input) ".json,application/json")
      (set! (.-onchange input)
            (fn [_]
              (when-let [file (some-> input .-files (aget 0))]
                (read-return-views-file-as-json!
                 file
                 (fn [data]
                   (nxr/dispatch store nil
                                 [[:actions/apply-imported-portfolio-optimizer-return-views
                                   data]]))))))
      (.click input))))

(defn load-portfolio-optimizer-assumption-library-effect
  "Load the wallet's remembered history assumptions into the state mirror. The
  hydrate watcher (draft-autosave ns) observes the mirror arriving and
  gap-fills the draft, so ordering against the draft restore doesn't matter —
  whichever lands last still triggers the fill."
  [_ store]
  (let [address (account-context/effective-account-address @store)]
    (if-not address
      (js/Promise.resolve nil)
      (-> (*load-assumption-library!* address)
          (.then (fn [record]
                   (let [entries (assumption-library/record->entries record)]
                     (swap! store assoc-in contracts/assumption-library-path entries)
                     entries)))
          (.catch (fn [_err] nil))))))

(defn sync-portfolio-optimizer-assumption-library-effect
  "Apply upserts/removes from the assumption-edit actions to the wallet's
  remembered history assumptions. Timestamps are stamped here. State always
  updates; the IndexedDB write is skipped for read-only/spectated accounts,
  matching the view-library sync."
  [_ store sync]
  (let [state @store
        address (account-context/effective-account-address state)
        entries (assumption-library/apply-sync
                 (get-in state contracts/assumption-library-path)
                 (or sync {})
                 (*now-ms*))]
    (swap! store assoc-in contracts/assumption-library-path entries)
    (if-not (and address (account-context/mutations-allowed? state))
      (js/Promise.resolve false)
      (-> (*save-assumption-library!* address
                                      (assumption-library/library-record address entries))
          (.catch (fn [_err] false))))))

(defn load-portfolio-optimizer-constraint-profiles-effect
  "Load the wallet's remembered profiles into state and, when the draft is still pristine and a
  default exists for the current universe, seed the draft constraints from it. Never clobbers a
  user-edited (dirty) draft."
  [_ store]
  (let [address (account-context/effective-account-address @store)]
    (if-not address
      (js/Promise.resolve nil)
      (-> (*load-constraint-profiles!* address)
          (.then (fn [loaded]
                   (let [profile-map (if (map? loaded) loaded {})]
                     (swap! store assoc-in contracts/constraint-profiles-path profile-map)
                     (when-let [remembered (constraint-profiles/auto-apply-constraints
                                            {:profiles profile-map
                                             :universe-key (constraint-profiles/universe-key
                                                            (get-in @store contracts/draft-universe-path))
                                             :dirty? (boolean (get-in @store contracts/draft-dirty-path))})]
                       (swap! store assoc-in contracts/draft-constraints-path remembered))
                     profile-map)))
          (.catch (fn [_err] nil))))))

(defn- run-portfolio-optimizer-pipeline-effect*
  [controller-resolver _ store]
  (pipeline/run-portfolio-optimizer-pipeline-effect
   {:now-ms *now-ms*
    :next-run-id run-bridge/next-run-id
    :request-run! (fn [payload]
                    (*request-run!*
                     (cond-> payload
                       (and controller-resolver (:store payload))
                       (assoc :controller
                              (controller-resolver (:store payload))))))
    :load-history! (fn [store* opts]
                     (load-portfolio-optimizer-history-effect nil store* opts))}
   nil
   store))

(defn run-portfolio-optimizer-pipeline-effect
  [_ store]
  (run-portfolio-optimizer-pipeline-effect*
   (run-bridge/make-controller-resolver)
   nil
   store))

(defn make-run-portfolio-optimizer-pipeline
  ([runtime]
   (make-run-portfolio-optimizer-pipeline
    runtime
    (make-portfolio-optimizer-controller-resolver runtime)))
  ([_runtime controller-resolver]
   (fn [_ store]
     (run-portfolio-optimizer-pipeline-effect*
      controller-resolver
      nil
      store))))

(def ^:dynamic *refresh-account-open-orders!*
  ;; Rebindable so unit tests of optimizer effects never fire live snapshot fetches.
  ;; Uses the manual-order-entry mutation refresh, which force-refreshes the base book
  ;; AND every named dex's frontendOpenOrders snapshot (the only cloid-bearing source —
  ;; the generic openOrders stream never hydrates named-dex rows). No-op without a
  ;; connected account.
  (fn [store]
    (when-let [address (or (account-context/effective-account-address @store)
                           (get-in @store [:wallet :address]))]
      (order-effects/refresh-account-surfaces-after-order-mutation! store address))))

(defn refresh-portfolio-optimizer-open-orders-effect
  "Force-refreshes every open-order surface (base + per-dex frontendOpenOrders, the
  cloid-bearing source). Dispatched when the Execution tab is staged so that by
  confirm time the optimizer can recognize — and cancel — its own resting orders from
  previous sessions on the live book."
  [_ store]
  (*refresh-account-open-orders!* store))

(defn execute-portfolio-optimizer-plan-effect
  ([_ store plan]
   (execution/execute-portfolio-optimizer-plan-effect
    (execution-env)
    nil
    store
    plan)))

(defn refresh-portfolio-optimizer-tracking-effect
  ([_ store]
   (tracking/refresh-portfolio-optimizer-tracking-effect
    (tracking-env)
    nil
    store)))

(defn load-portfolio-optimizer-scenario-index-effect
  ([_ store]
   (load-portfolio-optimizer-scenario-index-effect nil store nil))
  ([_ store opts]
   (scenario-effects/load-portfolio-optimizer-scenario-index-effect
    (scenario-env)
    store
    opts)))

(defn load-portfolio-optimizer-scenario-effect
  ([_ store scenario-id]
   (load-portfolio-optimizer-scenario-effect nil store scenario-id nil))
  ([_ store scenario-id opts]
   (scenario-effects/load-portfolio-optimizer-scenario-effect
    (scenario-env)
    store
    scenario-id
    opts)))

(defn archive-portfolio-optimizer-scenario-effect
  ([_ store scenario-id]
   (archive-portfolio-optimizer-scenario-effect nil store scenario-id nil))
  ([_ store scenario-id opts]
   (scenario-effects/archive-portfolio-optimizer-scenario-effect
    (scenario-env)
    store
    scenario-id
    opts)))

(defn duplicate-portfolio-optimizer-scenario-effect
  ([_ store scenario-id]
   (duplicate-portfolio-optimizer-scenario-effect nil store scenario-id nil))
  ([_ store scenario-id opts]
   (scenario-effects/duplicate-portfolio-optimizer-scenario-effect
    (scenario-env)
    store
    scenario-id
    opts)))

(defn save-portfolio-optimizer-scenario-effect
  ([_ store]
   (save-portfolio-optimizer-scenario-effect nil store nil))
  ([_ store opts]
   (scenario-effects/save-portfolio-optimizer-scenario-effect
    (scenario-env)
    store
    opts)))

(defn enable-portfolio-optimizer-manual-tracking-effect
  ([_ store]
   (scenario-effects/enable-portfolio-optimizer-manual-tracking-effect
    (scenario-env)
    store)))

(defn restore-portfolio-optimizer-draft-effect
  ([_ store]
   (restore-portfolio-optimizer-draft-effect nil store nil))
  ([_ store path]
   (scenario-effects/restore-portfolio-optimizer-draft-effect
    {:load-draft! *load-draft!*
     :load-history-cache! *load-history-cache!*
     :now-ms *now-ms*
     :dispatch! *dispatch!*
     :note-restored! draft-autosave/note-persisted!}
    store
    path)))

(defn reset-portfolio-optimizer-draft-effect
  ([_ store]
   (scenario-effects/reset-portfolio-optimizer-draft-effect
    {:delete-draft! *delete-draft!*
     :dispatch! *dispatch!*
     ;; Forget the "already persisted" marker: the IndexedDB record is gone, so
     ;; a future draft identical to the old one must still be written.
     :note-reset! #(draft-autosave/note-persisted! nil)}
    store
    nil)))
