(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-scenarios
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer-scenario-state :as state]))

(defn- env-fn
  [env key]
  (get env key))

(defn- load-tracking-record
  [load-tracking! scenario-id]
  (if load-tracking!
    (-> (load-tracking! scenario-id)
        (.catch (fn [_err] nil)))
    (js/Promise.resolve nil)))

(defn- solved-run?
  [last-successful-run]
  (= :solved (get-in last-successful-run [:result :status])))

(defn- current-scenario-id
  [env state opts now-ms]
  (or (:scenario-id opts)
      (get-in state [:portfolio :optimizer :active-scenario :loaded-id])
      (get-in state [:portfolio :optimizer :draft :id])
      ((env-fn env :next-scenario-id) now-ms)))

(def default-scenario-index state/default-scenario-index)
(def begin-scenario-save-state state/begin-scenario-save-state)
(def failed-scenario-save-state state/failed-scenario-save-state)
(def begin-scenario-index-load-state state/begin-scenario-index-load-state)
(def apply-scenario-save-success state/apply-scenario-save-success)
(def apply-scenario-save-error state/apply-scenario-save-error)
(def apply-scenario-index-load-success state/apply-scenario-index-load-success)
(def apply-scenario-index-load-error state/apply-scenario-index-load-error)
(def begin-scenario-load-state state/begin-scenario-load-state)
(def apply-scenario-load-success state/apply-scenario-load-success)
(def apply-scenario-load-not-found state/apply-scenario-load-not-found)
(def apply-scenario-load-error state/apply-scenario-load-error)
(def begin-scenario-archive-state state/begin-scenario-archive-state)
(def apply-scenario-archive-success state/apply-scenario-archive-success)
(def apply-scenario-archive-error state/apply-scenario-archive-error)
(def begin-scenario-duplicate-state state/begin-scenario-duplicate-state)
(def apply-scenario-duplicate-success state/apply-scenario-duplicate-success)
(def apply-scenario-duplicate-error state/apply-scenario-duplicate-error)

(defn load-portfolio-optimizer-scenario-index-effect
  [env store _opts]
  (let [state @store
        address (account-context/effective-account-address state)
        now-ms-fn (env-fn env :now-ms)
        load-scenario-index! (env-fn env :load-scenario-index!)
        started-at-ms (now-ms-fn)]
    (if address
      (do
        (swap! store assoc-in
               [:portfolio :optimizer :scenario-index-load-state]
               (begin-scenario-index-load-state started-at-ms))
        (-> (load-scenario-index! address)
            (.then (fn [loaded-index]
                     (let [completed-at-ms (now-ms-fn)
                           scenario-index (or loaded-index
                                              (default-scenario-index))]
                       (swap! store
                              apply-scenario-index-load-success
                              scenario-index
                              started-at-ms
                              completed-at-ms)
                       scenario-index)))
            (.catch (fn [err]
                      (let [completed-at-ms (now-ms-fn)]
                        (swap! store
                               apply-scenario-index-load-error
                               started-at-ms
                               completed-at-ms
                               err))
                      nil))))
      (do
        (swap! store
               apply-scenario-index-load-error
               started-at-ms
               started-at-ms
               {:message "Cannot load scenario index without an address."})
        (js/Promise.resolve nil)))))

(defn load-portfolio-optimizer-scenario-effect
  [env store scenario-id _opts]
  (let [now-ms-fn (env-fn env :now-ms)
        load-scenario! (env-fn env :load-scenario!)
        load-tracking! (env-fn env :load-tracking!)
        started-at-ms (now-ms-fn)]
    (if scenario-id
      (do
        (swap! store assoc-in
               [:portfolio :optimizer :scenario-load-state]
               (begin-scenario-load-state scenario-id started-at-ms))
        (-> (load-scenario! scenario-id)
            (.then (fn [scenario-record]
                     (if (map? scenario-record)
                       (-> (load-tracking-record load-tracking! scenario-id)
                           (.then (fn [tracking-record]
                                    (let [completed-at-ms (now-ms-fn)]
                                      (swap! store
                                             apply-scenario-load-success
                                             scenario-id
                                             scenario-record
                                             tracking-record
                                             started-at-ms
                                             completed-at-ms)
                                      scenario-record))))
                       (let [completed-at-ms (now-ms-fn)]
                         (swap! store
                                apply-scenario-load-not-found
                                scenario-id
                                started-at-ms
                                completed-at-ms)
                         nil))))
            (.catch (fn [err]
                      (let [completed-at-ms (now-ms-fn)]
                        (swap! store
                               apply-scenario-load-error
                               scenario-id
                               started-at-ms
                               completed-at-ms
                               err))
                      nil))))
      (do
        (swap! store
               apply-scenario-load-error
               scenario-id
               started-at-ms
               started-at-ms
               {:message "Cannot load scenario without a scenario id."})
        (js/Promise.resolve nil)))))

(defn archive-portfolio-optimizer-scenario-effect
  [env store scenario-id _opts]
  (let [state @store
        address (account-context/effective-account-address state)
        now-ms-fn (env-fn env :now-ms)
        load-scenario! (env-fn env :load-scenario!)
        load-scenario-index! (env-fn env :load-scenario-index!)
        save-scenario! (env-fn env :save-scenario!)
        save-scenario-index! (env-fn env :save-scenario-index!)
        started-at-ms (now-ms-fn)]
    (if (and address scenario-id)
      (do
        (swap! store assoc-in
               [:portfolio :optimizer :scenario-archive-state]
               (begin-scenario-archive-state scenario-id started-at-ms))
        (-> (load-scenario! scenario-id)
            (.then (fn [scenario-record]
                     (if-not (map? scenario-record)
                       (throw (js/Error. (str "Scenario " scenario-id " was not found.")))
                       (-> (load-scenario-index! address)
                           (.then (fn [loaded-index]
                                    (let [archived-record
                                          (scenario-records/archive-scenario-record
                                           scenario-record
                                           started-at-ms)
                                          scenario-index
                                          (scenario-records/refresh-scenario-index-summary
                                           (or loaded-index
                                               (get-in @store
                                                       [:portfolio :optimizer :scenario-index])
                                               (default-scenario-index))
                                           (scenario-records/scenario-summary archived-record))]
                                      (-> (save-scenario! scenario-id archived-record)
                                          (.then (fn [_]
                                                   (save-scenario-index! address scenario-index)))
                                          (.then (fn [_]
                                                   (let [completed-at-ms (now-ms-fn)]
                                                     (swap! store
                                                            apply-scenario-archive-success
                                                            scenario-index
                                                            archived-record
                                                            started-at-ms
                                                            completed-at-ms)
                                                     archived-record)))))))))))
            (.catch (fn [err]
                      (let [completed-at-ms (now-ms-fn)]
                        (swap! store
                               apply-scenario-archive-error
                               scenario-id
                               started-at-ms
                               completed-at-ms
                               err))
                      nil))))
      (do
        (swap! store
               apply-scenario-archive-error
               scenario-id
               started-at-ms
               started-at-ms
               {:message "Cannot archive scenario without an address and scenario id."})
        (js/Promise.resolve nil)))))

(defn duplicate-portfolio-optimizer-scenario-effect
  [env store scenario-id _opts]
  (let [state @store
        address (account-context/effective-account-address state)
        now-ms-fn (env-fn env :now-ms)
        load-scenario! (env-fn env :load-scenario!)
        load-scenario-index! (env-fn env :load-scenario-index!)
        save-scenario! (env-fn env :save-scenario!)
        save-scenario-index! (env-fn env :save-scenario-index!)
        started-at-ms (now-ms-fn)
        duplicated-scenario-id ((env-fn env :next-scenario-id) started-at-ms)]
    (if (and address scenario-id duplicated-scenario-id)
      (do
        (swap! store assoc-in
               [:portfolio :optimizer :scenario-duplicate-state]
               (begin-scenario-duplicate-state scenario-id started-at-ms))
        (-> (load-scenario! scenario-id)
            (.then (fn [scenario-record]
                     (if-not (map? scenario-record)
                       (throw (js/Error. (str "Scenario " scenario-id " was not found.")))
                       (-> (load-scenario-index! address)
                           (.then (fn [loaded-index]
                                    (let [duplicated-record
                                          (scenario-records/duplicate-scenario-record
                                           {:source-record scenario-record
                                            :scenario-id duplicated-scenario-id
                                            :duplicated-at-ms started-at-ms})
                                          scenario-index
                                          (scenario-records/upsert-scenario-index
                                           (or loaded-index
                                               (get-in @store
                                                       [:portfolio :optimizer :scenario-index])
                                               (default-scenario-index))
                                           (scenario-records/scenario-summary duplicated-record))]
                                      (-> (save-scenario! duplicated-scenario-id duplicated-record)
                                          (.then (fn [_]
                                                   (save-scenario-index! address scenario-index)))
                                          (.then (fn [_]
                                                   (let [completed-at-ms (now-ms-fn)]
                                                     (swap! store
                                                            apply-scenario-duplicate-success
                                                            scenario-index
                                                            duplicated-record
                                                            scenario-id
                                                            started-at-ms
                                                            completed-at-ms)
                                                     duplicated-record)))))))))))
            (.catch (fn [err]
                      (let [completed-at-ms (now-ms-fn)]
                        (swap! store
                               apply-scenario-duplicate-error
                               scenario-id
                               started-at-ms
                               completed-at-ms
                               err))
                      nil))))
      (do
        (swap! store
               apply-scenario-duplicate-error
               scenario-id
               started-at-ms
               started-at-ms
               {:message "Cannot duplicate scenario without an address and scenario id."})
        (js/Promise.resolve nil)))))

(defn save-portfolio-optimizer-scenario-effect
  [env store opts]
  (let [opts* (or opts {})
        state @store
        address (account-context/effective-account-address state)
        draft (or (get-in state [:portfolio :optimizer :draft])
                  (optimizer-defaults/default-draft))
        last-successful-run (get-in state [:portfolio :optimizer :last-successful-run])
        now-ms-fn (env-fn env :now-ms)
        load-scenario-index! (env-fn env :load-scenario-index!)
        save-scenario! (env-fn env :save-scenario!)
        save-scenario-index! (env-fn env :save-scenario-index!)
        started-at-ms (now-ms-fn)
        scenario-id (current-scenario-id env state opts* started-at-ms)
        existing-index (or (get-in state [:portfolio :optimizer :scenario-index])
                           (default-scenario-index))]
    (if (and address
             scenario-id
             (solved-run? last-successful-run))
      (do
        (swap! store assoc-in
               [:portfolio :optimizer :scenario-save-state]
               (begin-scenario-save-state scenario-id started-at-ms))
        (-> (load-scenario-index! address)
            (.then (fn [loaded-index]
                     (let [scenario-record (scenario-records/build-saved-scenario-record
                                            {:address address
                                             :scenario-id scenario-id
                                             :draft draft
                                             :last-successful-run last-successful-run
                                             :saved-at-ms started-at-ms})
                           scenario-index (scenario-records/upsert-scenario-index
                                           (or loaded-index existing-index (default-scenario-index))
                                           (scenario-records/scenario-summary scenario-record))]
                       (-> (save-scenario! scenario-id scenario-record)
                           (.then (fn [_]
                                    (save-scenario-index! address scenario-index)))
                           (.then (fn [_]
                                    (let [completed-at-ms (now-ms-fn)]
                                      (swap! store
                                             apply-scenario-save-success
                                             scenario-index
                                             scenario-record
                                             started-at-ms
                                             completed-at-ms)
                                      scenario-record)))))))
            (.catch (fn [err]
                      (let [completed-at-ms (now-ms-fn)]
                        (swap! store
                               apply-scenario-save-error
                               scenario-id
                               started-at-ms
                               completed-at-ms
                               err))
                      nil))))
      (do
        (swap! store assoc-in
               [:portfolio :optimizer :scenario-save-state]
               (failed-scenario-save-state scenario-id
                                           started-at-ms
                                           started-at-ms
                                           {:message "Cannot save scenario without an address and solved run."}))
        (js/Promise.resolve nil)))))
