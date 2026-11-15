(ns hyperopen.portfolio.optimizer.infrastructure.draft-autosave
  "Store watchers for the optimizer's opinionated default path.

  Autosave: every draft change persists (debounced) to the per-wallet
  `draft::<address>` IndexedDB record, so edits — including a deliberately cleared
  universe — survive reloads and wallet switches. The write is skipped for
  untouched/default drafts, when the effective address changed between the edit
  and the flush (a wallet switch must never cross-key a draft), and when the draft
  equals the value already persisted (a fresh restore does not re-write itself).

  Holdings arrival: on a cold page-load the /optimize/new route event fires before
  the account feed delivers any holdings, so the route-time preseed no-ops. This
  watcher observes the no-holdings -> holdings transition and re-dispatches the
  restore-or-preseed funnel (IndexedDB still wins over the machine preseed)."
  (:require [nexus.registry :as nxr]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.actions.draft-persistence :as draft-persistence]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.history-cache :as history-cache]
            [hyperopen.portfolio.optimizer.application.view-library :as view-library]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.infrastructure.persistence :as persistence]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(def ^:private autosave-watch-key ::draft-autosave)
(def ^:private history-cache-watch-key ::history-cache-autosave)
(def ^:private preseed-watch-key ::holdings-preseed)
(def ^:private identity-restore-watch-key ::identity-restore)
(def ^:private assumption-hydrate-watch-key ::assumption-library-hydrate)
(def ^:private view-library-hydrate-watch-key ::view-library-hydrate)

(def default-debounce-ms
  "One draft edit rarely comes alone (drags, typed digits); a sub-second debounce
  batches a burst into one IndexedDB write without risking a visible loss window."
  800)

(defonce ^:private last-persisted-draft
  (atom nil))

(defn note-persisted!
  "Record `draft` as the value already sitting in IndexedDB so the autosave watcher
  does not immediately re-write it. Called by the flush itself and by the restore
  effect right before it hydrates a loaded record into the store."
  [draft]
  (reset! last-persisted-draft draft))

(defn- complete-draft
  "Persist a spec-complete draft even when the in-store value is a legacy partial
  one (sessions seeded before the holdings seed materialized the full default):
  layer it over a fresh default so the restore-side ::draft validation accepts it."
  [draft]
  (let [default (optimizer-defaults/default-draft)]
    (-> (merge default draft)
        (assoc :metadata (merge (:metadata default) (:metadata draft))))))

(defn install-draft-autosave-watcher!
  [{:keys [store save-draft! now-ms-fn set-timeout-fn clear-timeout-fn debounce-ms]
    :or {save-draft! persistence/save-draft!
         now-ms-fn platform/now-ms
         set-timeout-fn platform/set-timeout!
         clear-timeout-fn platform/clear-timeout!
         debounce-ms default-debounce-ms}}]
  (let [pending (atom nil)
        flush! (fn [address]
                 (let [state @store
                       draft (get-in state contracts/draft-path)]
                   (when (and (seq (or address ""))
                              (= address (account-context/effective-account-address state))
                              (not (optimizer-defaults/untouched-draft? draft))
                              (not= draft @last-persisted-draft))
                     (let [saved-at-ms (now-ms-fn)]
                       (-> (save-draft! address
                                        {:version draft-persistence/draft-record-version
                                         :address address
                                         :draft (complete-draft draft)
                                         :saved-at-ms saved-at-ms})
                           (.then (fn [persisted?]
                                    (when persisted?
                                      (note-persisted! draft)
                                      (swap! store
                                             assoc-in
                                             contracts/draft-persist-path
                                             {:status :saved
                                              :at-ms saved-at-ms}))))
                           (.catch (fn [_err] nil)))))))
        schedule! (fn [address]
                    (when-let [{:keys [timer]} @pending]
                      (clear-timeout-fn timer))
                    (reset! pending
                            {:address address
                             :timer (set-timeout-fn
                                     (fn []
                                       (let [{:keys [address]} @pending]
                                         (reset! pending nil)
                                         (flush! address)))
                                     debounce-ms)}))]
    (remove-watch store autosave-watch-key)
    (add-watch store autosave-watch-key
               (fn [_ _ old-state new-state]
                 (let [old-draft (get-in old-state contracts/draft-path)
                       new-draft (get-in new-state contracts/draft-path)]
                   (when (and (not= old-draft new-draft)
                              (not (optimizer-defaults/untouched-draft? new-draft))
                              (not= new-draft @last-persisted-draft))
                     (when-let [address (account-context/effective-account-address new-state)]
                       (schedule! address))))))
    store))

(def history-cache-debounce-ms
  "A load completion can be followed by a prefetch-merge moments later (the
  delta path); a short debounce folds the burst into one multi-MB IndexedDB
  write."
  1500)

(defn install-history-cache-watcher!
  "Persist the last successful history bundle per wallet
  (`history-bundle::<address>`) so the restore funnel can hydrate it on the
  next visit (stale-while-revalidate — see application.history-cache). Watches
  only the bundle's :loaded-at-ms transition, so the comparison stays O(1) on
  every store swap; the record itself is built at flush time from current
  state."
  [{:keys [store save-history-cache! now-ms-fn set-timeout-fn clear-timeout-fn
           history-cache-debounce-ms*]
    :or {save-history-cache! persistence/save-history-cache!
         now-ms-fn platform/now-ms
         set-timeout-fn platform/set-timeout!
         clear-timeout-fn platform/clear-timeout!
         history-cache-debounce-ms* history-cache-debounce-ms}}]
  (let [pending (atom nil)
        last-persisted-at (atom nil)
        flush! (fn []
                 (let [state @store
                       address (account-context/effective-account-address state)
                       history-data (get-in state contracts/history-data-path)
                       loaded-at-ms (:loaded-at-ms history-data)]
                   (when (and address
                              loaded-at-ms
                              (not (:restored-from-cache? history-data))
                              (not= loaded-at-ms @last-persisted-at))
                     (when-let [record (history-cache/history-cache-record
                                        state address (now-ms-fn))]
                       (reset! last-persisted-at loaded-at-ms)
                       (-> (save-history-cache! address record)
                           (.catch (fn [_err] nil)))))))
        schedule! (fn []
                    (when-let [timer @pending]
                      (clear-timeout-fn timer))
                    (reset! pending
                            (set-timeout-fn
                             (fn []
                               (reset! pending nil)
                               (flush!))
                             history-cache-debounce-ms*)))]
    (remove-watch store history-cache-watch-key)
    (add-watch store history-cache-watch-key
               (fn [_ _ old-state new-state]
                 (let [path (conj contracts/history-data-path :loaded-at-ms)
                       old-loaded (get-in old-state path)
                       new-loaded (get-in new-state path)]
                   (when (and new-loaded (not= old-loaded new-loaded))
                     (schedule!)))))
    store))

(defn install-holdings-preseed-watcher!
  [{:keys [store dispatch!]
    :or {dispatch! nxr/dispatch}}]
  (remove-watch store preseed-watch-key)
  (add-watch store preseed-watch-key
             (fn [_ _ old-state new-state]
               (when (and (current-portfolio/holdings-source-arrived? old-state new-state)
                          (optimizer-defaults/untouched-draft?
                           (get-in new-state contracts/draft-path))
                          (= :optimize-new
                             (:kind (portfolio-routes/parse-portfolio-route
                                     (get-in new-state [:router :path])))))
                 (dispatch! store
                            nil
                            [[:actions/restore-or-preseed-portfolio-optimizer-draft
                              (get-in new-state [:router :path])]]))))
  store)

(defn install-identity-restore-watcher!
  "Re-dispatch the restore-or-preseed funnel when the EFFECTIVE ACCOUNT IDENTITY
  resolves or changes while the user sits on /optimize/new with an untouched
  draft. The restore effect silently no-ops when the effective address is still
  nil (e.g. a full page reload under spectate mode resolves identity AFTER the
  optimizer route loads), and the holdings watcher only fires on holdings-arrival
  transitions — so without this watcher a reload loses a draft the UI claimed was
  saved, and a late-connecting account never auto-seeds. The untouched-draft gate
  keeps repeat dispatches idempotent, exactly like the holdings watcher."
  [{:keys [store dispatch!]
    :or {dispatch! nxr/dispatch}}]
  (remove-watch store identity-restore-watch-key)
  (add-watch store identity-restore-watch-key
             (fn [_ _ old-state new-state]
               (let [old-address (account-context/effective-account-address old-state)
                     new-address (account-context/effective-account-address new-state)]
                 (when (and (some? new-address)
                            (not= old-address new-address)
                            (optimizer-defaults/untouched-draft?
                             (get-in new-state contracts/draft-path))
                            (= :optimize-new
                               (:kind (portfolio-routes/parse-portfolio-route
                                       (get-in new-state [:router :path])))))
                   (dispatch! store
                              nil
                              [[:actions/restore-or-preseed-portfolio-optimizer-draft
                                (get-in new-state [:router :path])]])))))
  store)

(defn- assumption-library-gap?
  "True when some universe instrument has NO draft assumption entry but the
  wallet's assumption library remembers one — the state the hydrate action
  gap-fills."
  [state]
  (let [entries (get-in state contracts/assumption-library-path)]
    (and (map? entries)
         (seq entries)
         (let [assumptions (or (get-in state contracts/draft-history-assumptions-path)
                               {})]
           (boolean (some (fn [instrument]
                            (let [id (:instrument-id instrument)]
                              (and id
                                   (not (contains? assumptions id))
                                   (contains? entries id))))
                          (get-in state contracts/draft-universe-path)))))))

(defn- assumption-hydrate-inputs
  [state]
  [(get-in state contracts/draft-universe-path)
   (get-in state contracts/draft-history-assumptions-path)
   (get-in state contracts/assumption-library-path)])

(defn install-assumption-library-hydrate-watcher!
  "Gap-fill remembered history assumptions whenever a universe instrument
  lacks a draft entry the library remembers. One watcher covers every path
  that can open a gap — universe add, draft restore, holdings preseed, the
  library mirror arriving from IndexedDB — regardless of ordering. The
  hydrate action is a pure gap-fill (existing draft entries always win), so
  repeat dispatches are idempotent; each fill closes the gap, so the watcher
  quiesces. Clear never resurrects: the library remove hits the state mirror
  before the draft write (see the actions-side ordering note)."
  [{:keys [store dispatch!]
    :or {dispatch! nxr/dispatch}}]
  (remove-watch store assumption-hydrate-watch-key)
  (add-watch store assumption-hydrate-watch-key
             (fn [_ _ old-state new-state]
               (when (and (not= (assumption-hydrate-inputs old-state)
                                (assumption-hydrate-inputs new-state))
                          (assumption-library-gap? new-state))
                 (dispatch! store
                            nil
                            [[:actions/hydrate-portfolio-optimizer-history-assumption-library]]))))
  store)

(defn- view-library-gap?
  "True when some universe instrument has NO authored absolute view but the
  wallet's view library remembers one — the state the hydrate action
  gap-fills. Only under the views-aware return model: other estimators ignore
  views, and the preset/model-switch actions hydrate on entry."
  [state]
  (let [entries (get-in state contracts/view-library-path)]
    (and (map? entries)
         (seq entries)
         (= :black-litterman
            (get-in state (conj contracts/draft-return-model-path :kind)))
         (view-library/hydration-gap?
          (get-in state contracts/draft-return-model-views-path)
          entries
          (get-in state contracts/draft-universe-path)))))

(defn- view-library-hydrate-inputs
  [state]
  [(get-in state contracts/draft-universe-path)
   (get-in state contracts/draft-return-model-path)
   (get-in state contracts/view-library-path)])

(defn install-view-library-hydrate-watcher!
  "Gap-fill remembered return views whenever a universe instrument lacks an
  authored view the library remembers — universe add, draft restore, the
  library mirror arriving from IndexedDB — regardless of ordering. The hydrate
  action is a pure gap-fill (existing draft views always win), so repeat
  dispatches are idempotent; each fill closes the gap, so the watcher
  quiesces. Clearing a view never resurrects it: the library remove hits the
  state mirror BEFORE the draft write (see the view-row actions' ordering
  note), so by the time this watcher sees the view disappear the library
  entry is already gone."
  [{:keys [store dispatch!]
    :or {dispatch! nxr/dispatch}}]
  (remove-watch store view-library-hydrate-watch-key)
  (add-watch store view-library-hydrate-watch-key
             (fn [_ _ old-state new-state]
               (when (and (not= (view-library-hydrate-inputs old-state)
                                (view-library-hydrate-inputs new-state))
                          (view-library-gap? new-state))
                 (dispatch! store
                            nil
                            [[:actions/hydrate-portfolio-optimizer-view-library]]))))
  store)

(defn install-optimizer-draft-watchers!
  [deps]
  (install-draft-autosave-watcher! deps)
  (install-history-cache-watcher! deps)
  (install-holdings-preseed-watcher! deps)
  (install-identity-restore-watcher! deps)
  (install-assumption-library-hydrate-watcher! deps)
  (install-view-library-hydrate-watcher! deps))
