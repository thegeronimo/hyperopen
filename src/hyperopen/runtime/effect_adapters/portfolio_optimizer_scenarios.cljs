(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-scenarios
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.application.history-cache :as history-cache]
            [hyperopen.portfolio.optimizer.application.scenario-operations :as scenario-operations]
            [hyperopen.portfolio.optimizer.application.scenario-workflow :as scenario-workflow]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(defn- env-fn
  [env key]
  (get env key))

(defn- load-tracking-record
  [load-tracking! scenario-id]
  (if load-tracking!
    (-> (load-tracking! scenario-id)
        (.catch (fn [_err] nil)))
    (js/Promise.resolve nil)))

(def ^:private reserved-unsaved-scenario-ids
  #{"draft"})

(defn- saved-scenario-id
  [scenario-id]
  (let [scenario-id* (some-> scenario-id str str/trim)]
    (when (and (seq scenario-id*)
               (not (contains? reserved-unsaved-scenario-ids scenario-id*))
               (not (str/starts-with? scenario-id* "draft-")))
      scenario-id*)))

(defn- scenario-name
  [opts]
  (let [scenario-name* (some-> (:name opts) str str/trim)]
    (when (seq scenario-name*)
      scenario-name*)))

(defn- active-scenario-address
  [state]
  (account-context/normalize-address
   (get-in state (conj contracts/active-scenario-path :address))))

(defn- current-scenario-id
  [env state opts now-ms]
  (or (saved-scenario-id (:scenario-id opts))
      (saved-scenario-id
       (get-in state contracts/active-scenario-loaded-id-path))
      (saved-scenario-id
       (get-in state contracts/draft-id-path))
      ((env-fn env :next-scenario-id) now-ms)))

(defn- apply-result!
  [store result]
  (reset! store (:state result))
  result)

(declare interpret-result!)

(defn- operation-result-value
  [store operation]
  (scenario-operations/result-value operation @store))

(defn- require-persisted!
  [label persisted?]
  (when-not persisted?
    (throw (js/Error. (str "Failed to persist " label "."))))
  persisted?)

(defn- fail-operation-result
  [operation state error completed-at-ms]
  (scenario-operations/fail operation state error completed-at-ms))

(defn- fail-operation!
  [env store operation error]
  (apply-result!
   store
   (fail-operation-result operation @store error ((env-fn env :now-ms))))
  (js/Promise.resolve nil))

(defn- complete-operation-result
  [operation state completed-at-ms]
  (scenario-operations/complete operation state completed-at-ms))

(defn- dispatch-saved-scenario-route!
  [env store scenario-record]
  (when-let [scenario-id (saved-scenario-id (:id scenario-record))]
    (let [path (portfolio-routes/portfolio-optimize-scenario-path scenario-id)
          current-path (get-in @store [:router :path])
          route-kind (:kind (portfolio-routes/parse-portfolio-route
                             (or current-path "")))
          dispatch! (env-fn env :dispatch!)]
      (when (and path
                 current-path
                 dispatch!
                 (= :optimize-scenario route-kind)
                 (not= current-path path))
        (dispatch! store nil [[:actions/navigate path {:replace? true}]])))))

(defn- continue-after-scenario-record
  [operation state command scenario-record completed-at-ms]
  (scenario-operations/continue-after-scenario-record
   operation
   state
   command
   scenario-record
   completed-at-ms))

(defn- continue-after-scenario-index
  [operation state command loaded-index completed-at-ms]
  (scenario-operations/continue-after-scenario-index
   operation
   state
   command
   loaded-index
   completed-at-ms))

(defn- merge-result-context
  [operation result]
  (merge operation
         (select-keys result [:scenario-record :scenario-index])))

(defn- interpret-command!
  [env store operation result command]
  (let [now-ms-fn (env-fn env :now-ms)]
    (case (:command/type command)
      :optimizer.workflow/load-scenario
      (-> ((env-fn env :load-scenario!) (:scenario-id command))
          (.then (fn [scenario-record]
                   (let [completed-at-ms (now-ms-fn)
                         operation* (assoc operation
                                           :loaded-scenario-record scenario-record)
                         result* (continue-after-scenario-record
                                  operation*
                                  @store
                                  command
                                  scenario-record
                                  completed-at-ms)]
                     (interpret-result! env store operation* result*))))
          (.catch (fn [err]
                    (fail-operation! env store operation err))))

      :optimizer.workflow/load-tracking
      (-> (load-tracking-record (env-fn env :load-tracking!)
                                (:scenario-id command))
          (.then (fn [tracking-record]
                   (interpret-result!
                    env
                    store
                    operation
                    (scenario-workflow/complete-load-after-tracking
                     {:state @store
                      :scenario-id (:scenario-id command)
                      :scenario-record (:loaded-scenario-record operation)
                      :tracking-record tracking-record
                      :started-at-ms (:started-at-ms operation)
                      :completed-at-ms (now-ms-fn)})))))

      :optimizer.workflow/load-scenario-index
      (-> ((env-fn env :load-scenario-index!) (:address command))
          (.then (fn [loaded-index]
                   (let [completed-at-ms (now-ms-fn)
                         result* (continue-after-scenario-index
                                  operation
                                  @store
                                  command
                                  loaded-index
                                  completed-at-ms)]
                     (interpret-result!
                      env
                      store
                      (merge-result-context operation result*)
                      result*))))
          (.catch (fn [err]
                    (fail-operation! env store operation err))))

      :optimizer.workflow/save-scenario
      (-> ((env-fn env :save-scenario!)
           (:scenario-id command)
           (:scenario-record command))
          (.then (fn [persisted?]
                   (require-persisted! "optimizer scenario" persisted?)
                   (interpret-result!
                    env
                    store
                    operation
                    (scenario-workflow/advance-command-result result))))
          (.catch (fn [err]
                    (fail-operation! env store operation err))))

      :optimizer.workflow/save-scenario-index
      (let [operation* (assoc operation
                              :scenario-index (:scenario-index command))]
        (-> ((env-fn env :save-scenario-index!)
             (:address command)
             (:scenario-index command))
            (.then (fn [persisted?]
                     (require-persisted! "optimizer scenario index" persisted?)
                     (-> (interpret-result!
                          env
                          store
                          operation*
                          (complete-operation-result operation*
                                                     @store
                                                     (now-ms-fn)))
                         (.then (fn [value]
                                  (when (= :save (:operation/type operation*))
                                    (dispatch-saved-scenario-route!
                                     env
                                     store
                                     (:scenario-record operation*)))
                                  value)))))
            (.catch (fn [err]
                      (fail-operation! env store operation* err)))))

      (js/Promise.resolve nil))))

(defn- interpret-result!
  [env store operation result]
  (let [result* (apply-result! store result)]
    (if-let [command (first (:commands result*))]
      (interpret-command! env store operation result* command)
      (js/Promise.resolve (operation-result-value store operation)))))

(defn load-portfolio-optimizer-scenario-index-effect
  [env store _opts]
  (let [state @store
        address (account-context/effective-account-address state)
        started-at-ms ((env-fn env :now-ms))]
    (interpret-result!
     env
     store
     {:operation/type :scenario-index
      :address address
      :started-at-ms started-at-ms}
     (scenario-workflow/begin-index-load
      {:state state
       :address address
       :started-at-ms started-at-ms}))))

(defn load-portfolio-optimizer-scenario-effect
  [env store scenario-id _opts]
  (let [started-at-ms ((env-fn env :now-ms))]
    (interpret-result!
     env
     store
     {:operation/type :load
      :scenario-id scenario-id
      :started-at-ms started-at-ms}
     (scenario-workflow/begin-load
      {:state @store
       :scenario-id scenario-id
       :started-at-ms started-at-ms}))))

(defn archive-portfolio-optimizer-scenario-effect
  [env store scenario-id _opts]
  (let [state @store
        address (account-context/effective-account-address state)
        started-at-ms ((env-fn env :now-ms))]
    (interpret-result!
     env
     store
     {:operation/type :archive
      :address address
      :scenario-id scenario-id
      :started-at-ms started-at-ms}
     (scenario-workflow/begin-archive
      {:state state
       :address address
       :scenario-id scenario-id
       :started-at-ms started-at-ms}))))

(defn duplicate-portfolio-optimizer-scenario-effect
  [env store scenario-id _opts]
  (let [state @store
        address (account-context/effective-account-address state)
        now-ms-fn (env-fn env :now-ms)
        started-at-ms (now-ms-fn)
        duplicated-scenario-id ((env-fn env :next-scenario-id) started-at-ms)]
    (interpret-result!
     env
     store
     {:operation/type :duplicate
      :address address
      :scenario-id scenario-id
      :duplicated-scenario-id duplicated-scenario-id
      :started-at-ms started-at-ms}
     (scenario-workflow/begin-duplicate
      {:state state
       :address address
       :scenario-id scenario-id
       :duplicated-scenario-id duplicated-scenario-id
       :started-at-ms started-at-ms}))))

(defn enable-portfolio-optimizer-manual-tracking-effect
  [env store]
  (let [state @store
        address (account-context/effective-account-address state)
        scenario-id (or (get-in state contracts/active-scenario-loaded-id-path)
                        (get-in state contracts/draft-id-path))
        started-at-ms ((env-fn env :now-ms))]
    (interpret-result!
     env
     store
     {:operation/type :manual-tracking
      :address address
      :scenario-id scenario-id
      :started-at-ms started-at-ms}
     (scenario-workflow/begin-manual-tracking
      {:state state
       :address address
       :scenario-id scenario-id
       :started-at-ms started-at-ms}))))

(defn save-portfolio-optimizer-scenario-effect
  [env store opts]
  (let [opts* (or opts {})
        state @store
        address (or (account-context/effective-account-address state)
                    (active-scenario-address state))
        started-at-ms ((env-fn env :now-ms))
        scenario-id (current-scenario-id env state opts* started-at-ms)
        scenario-name* (scenario-name opts*)]
    (interpret-result!
     env
     store
     {:operation/type :save
      :address address
      :scenario-id scenario-id
      :scenario-name scenario-name*
      :started-at-ms started-at-ms}
     (scenario-workflow/begin-save
      {:state state
       :address address
       :scenario-id scenario-id
       :started-at-ms started-at-ms}))))

(defn reset-portfolio-optimizer-draft-effect
  "The explicit New-scenario fresh start. Deletes the per-wallet autosaved
  draft record FIRST — a reset draft is untouched again, so a surviving
  `draft::<addr>` record would be restored right back by the arrival watchers —
  then resets the workspace state and re-runs the holdings preseed so the page
  behaves like a first visit."
  [env store _opts]
  (let [address (account-context/effective-account-address @store)
        delete-draft! (env-fn env :delete-draft!)
        dispatch! (env-fn env :dispatch!)
        note-reset! (or (env-fn env :note-reset!) (fn []))
        reset! (fn []
                 (swap! store scenario-workflow/reset-workspace-state)
                 (note-reset!)
                 (when dispatch!
                   (dispatch! store
                              nil
                              [[:actions/set-portfolio-optimizer-universe-from-current]]))
                 nil)]
    (if (and address delete-draft!)
      (-> (delete-draft! address)
          (.then (fn [_persisted?] (reset!)))
          (.catch (fn [_err] (reset!))))
      (js/Promise.resolve (reset!)))))

(defn- hydrate-history-cache!
  "Read the wallet's persisted history bundle in PARALLEL with the draft
  restore and hydrate it while the in-memory history data is still empty —
  the stale-while-revalidate half that lets assumption cards and readiness
  settle from the last session's data seconds before the background reload
  (the load effect the draft/preseed hydration already appends) lands and
  replaces it. All the guards (address, version, age cap, never-clobber) live
  in the pure history-cache policy."
  [env store address]
  (let [load-history-cache! (env-fn env :load-history-cache!)
        now-ms-fn (or (env-fn env :now-ms) platform/now-ms)]
    (if (and address load-history-cache!)
      (-> (load-history-cache! address)
          (.then (fn [record]
                   (when record
                     (swap! store
                            (fn [state]
                              (or (history-cache/hydrate-history-cache
                                   state record address (now-ms-fn))
                                  state))))
                   nil))
          (.catch (fn [_err] nil)))
      (js/Promise.resolve nil))))

(defn restore-portfolio-optimizer-draft-effect
  "Restore the per-wallet persisted draft into an untouched /optimize/new draft,
  or fall back to the holdings preseed when nothing was persisted. The persisted
  draft always wins over the machine preseed, so this effect is the single funnel
  both the route loader and the holdings-arrival watcher dispatch through. The
  wallet's cached history bundle hydrates in parallel (either branch benefits)."
  [env store _path]
  (let [address (account-context/effective-account-address @store)
        load-draft! (env-fn env :load-draft!)
        dispatch! (env-fn env :dispatch!)
        note-restored! (or (env-fn env :note-restored!) (fn [_draft]))
        untouched? (fn []
                     (optimizer-defaults/untouched-draft?
                      (get-in @store contracts/draft-path)))
        preseed! (fn []
                   (when (untouched?)
                     (dispatch! store
                                nil
                                [[:actions/set-portfolio-optimizer-universe-from-current]]))
                   nil)
        history-hydration (hydrate-history-cache! env store address)
        draft-restore
        (if (and address load-draft! dispatch!)
          (-> (load-draft! address)
              (.then (fn [record]
                       (if (and (map? record)
                                (map? (:draft record))
                                (untouched?))
                         (do (note-restored! (:draft record))
                             (dispatch! store
                                        nil
                                        [[:actions/hydrate-portfolio-optimizer-draft record]]))
                         (preseed!))
                       nil))
              (.catch (fn [_err]
                        (preseed!))))
          (js/Promise.resolve (preseed!)))]
    (-> (js/Promise.all #js [history-hydration draft-restore])
        (.then (fn [_] nil)))))
