(ns hyperopen.portfolio.optimizer.application.scenario-workflow
  (:require [hyperopen.portfolio.optimizer.application.run-identity :as run-identity]
            [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]
            [hyperopen.portfolio.optimizer.application.scenario-state :as state]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]))

(def default-scenario-index state/default-scenario-index)
(def begin-scenario-save-state state/begin-scenario-save-state)
(def failed-scenario-save-state state/failed-scenario-save-state)
(def begin-scenario-index-load-state state/begin-scenario-index-load-state)
(def begin-scenario-load-state state/begin-scenario-load-state)
(def begin-scenario-archive-state state/begin-scenario-archive-state)
(def begin-scenario-duplicate-state state/begin-scenario-duplicate-state)
(def apply-scenario-save-success state/apply-scenario-save-success)
(def apply-scenario-save-error state/apply-scenario-save-error)
(def apply-scenario-index-load-success state/apply-scenario-index-load-success)
(def apply-scenario-index-load-error state/apply-scenario-index-load-error)
(def apply-scenario-load-success state/apply-scenario-load-success)
(def apply-scenario-load-not-found state/apply-scenario-load-not-found)
(def apply-scenario-load-error state/apply-scenario-load-error)
(def apply-scenario-archive-success state/apply-scenario-archive-success)
(def apply-scenario-archive-error state/apply-scenario-archive-error)
(def apply-scenario-duplicate-success state/apply-scenario-duplicate-success)
(def apply-scenario-duplicate-error state/apply-scenario-duplicate-error)
(def apply-manual-tracking-success state/apply-manual-tracking-success)
(def apply-manual-tracking-error state/apply-manual-tracking-error)

(def ^:private manual-tracking-source-statuses
  #{:saved :computed})

(defn- load-scenario-index-command
  [{:keys [address scenario-id started-at-ms source]}]
  (cond-> {:command/type :optimizer.workflow/load-scenario-index
           :address address
           :started-at-ms started-at-ms}
    scenario-id (assoc :scenario-id scenario-id)
    source (assoc :source source)))

(defn- load-scenario-command
  [{:keys [scenario-id started-at-ms source duplicated-scenario-id]}]
  (cond-> {:command/type :optimizer.workflow/load-scenario
           :scenario-id scenario-id
           :started-at-ms started-at-ms}
    source (assoc :source source)
    duplicated-scenario-id (assoc :duplicated-scenario-id duplicated-scenario-id)))

(defn- load-tracking-command
  [{:keys [scenario-id started-at-ms]}]
  {:command/type :optimizer.workflow/load-tracking
   :scenario-id scenario-id
   :started-at-ms started-at-ms})

(defn- save-scenario-command
  [{:keys [scenario-id scenario-record started-at-ms source]}]
  (cond-> {:command/type :optimizer.workflow/save-scenario
           :scenario-id scenario-id
           :scenario-record scenario-record
           :started-at-ms started-at-ms}
    source (assoc :source source)))

(defn- save-scenario-index-command
  [{:keys [address scenario-id scenario-index started-at-ms source]}]
  (cond-> {:command/type :optimizer.workflow/save-scenario-index
           :address address
           :scenario-index scenario-index
           :scenario-id scenario-id
           :started-at-ms started-at-ms}
    source (assoc :source source)))

(defn advance-command-result
  [result]
  (update result :commands #(vec (rest %))))

(defn begin-index-load
  [{:keys [state address started-at-ms]}]
  (if address
    {:state (assoc-in state
                      contracts/scenario-index-load-state-path
                      (begin-scenario-index-load-state started-at-ms))
     :commands [(load-scenario-index-command
                 {:address address
                  :started-at-ms started-at-ms})]}
    {:state (apply-scenario-index-load-error
             state
             started-at-ms
             started-at-ms
             {:message "Cannot load scenario index without an address."})
     :commands []}))

(defn complete-index-load
  [{:keys [state loaded-index started-at-ms completed-at-ms error]}]
  {:state (if error
            (apply-scenario-index-load-error state
                                             started-at-ms
                                             completed-at-ms
                                             error)
            (apply-scenario-index-load-success
             state
             (or loaded-index (default-scenario-index))
             started-at-ms
             completed-at-ms))
   :commands []})

(defn begin-load
  [{:keys [state scenario-id started-at-ms]}]
  (if scenario-id
    {:state (assoc-in state
                      contracts/scenario-load-state-path
                      (begin-scenario-load-state scenario-id started-at-ms))
     :commands [(load-scenario-command
                 {:scenario-id scenario-id
                  :started-at-ms started-at-ms})]}
    {:state (apply-scenario-load-error
             state
             scenario-id
             started-at-ms
             started-at-ms
             {:message "Cannot load scenario without a scenario id."})
     :commands []}))

(defn continue-load-after-record
  [{:keys [state scenario-id scenario-record started-at-ms completed-at-ms]}]
  (if (map? scenario-record)
    {:state state
     :commands [(load-tracking-command
                 {:scenario-id scenario-id
                  :started-at-ms started-at-ms})]}
    {:state (apply-scenario-load-not-found state
                                           scenario-id
                                           started-at-ms
                                           completed-at-ms)
     :commands []}))

(defn complete-load-after-tracking
  [{:keys [state scenario-id scenario-record tracking-record started-at-ms completed-at-ms]}]
  {:state (apply-scenario-load-success state
                                       scenario-id
                                       scenario-record
                                       tracking-record
                                       started-at-ms
                                       completed-at-ms)
   :commands []})

(defn fail-load
  [{:keys [state scenario-id started-at-ms completed-at-ms error]}]
  {:state (apply-scenario-load-error state
                                     scenario-id
                                     started-at-ms
                                     completed-at-ms
                                     error)
   :commands []})

(defn begin-save
  [{:keys [state address scenario-id started-at-ms]}]
  (if (and address scenario-id)
    {:state (assoc-in state
                      contracts/scenario-save-state-path
                      (begin-scenario-save-state scenario-id started-at-ms))
     :commands [(load-scenario-index-command
                 {:address address
                  :scenario-id scenario-id
                  :started-at-ms started-at-ms})]}
    {:state (assoc-in state
                      contracts/scenario-save-state-path
                      (failed-scenario-save-state
                       scenario-id
                       started-at-ms
                       started-at-ms
                       {:message "Cannot save scenario without a connected wallet."}))
     :commands []}))

(defn- attachable-saved-run
  "The results snapshot is stored on the scenario only when it is honest: a
  solved run whose inputs still match the draft being saved. A missing or
  stale run saves a setup-only scenario instead of freezing outdated numbers."
  [state draft]
  (let [last-successful-run (get-in state contracts/last-successful-run-path)
        running? (or (= :running (get-in state contracts/run-state-status-path))
                     (= :running (get-in state contracts/optimization-progress-status-path)))]
    (when (run-identity/current-solved-run?
           {:draft draft
            :readiness (setup-readiness/build-readiness state)
            :run-state (get-in state contracts/run-state-path)
            :running? running?
            :last-successful-run last-successful-run})
      last-successful-run)))

(defn continue-save-after-index
  [{:keys [state address scenario-id scenario-name started-at-ms loaded-index]}]
  (let [base-draft (or (get-in state contracts/draft-path)
                       (optimizer-defaults/default-draft))
        draft (cond-> base-draft
                scenario-name (assoc :name scenario-name))
        last-successful-run (attachable-saved-run state base-draft)
        existing-index (or (get-in state contracts/scenario-index-path)
                           (default-scenario-index))
        scenario-record (scenario-records/build-saved-scenario-record
                         {:address address
                          :scenario-id scenario-id
                          :draft draft
                          :last-successful-run last-successful-run
                          :saved-at-ms started-at-ms})
        scenario-index (scenario-records/upsert-scenario-index
                        (or loaded-index existing-index (default-scenario-index))
                        (scenario-records/scenario-summary scenario-record))]
    {:state state
     :scenario-record scenario-record
     :scenario-index scenario-index
     :commands [(save-scenario-command
                 {:scenario-id scenario-id
                  :scenario-record scenario-record
                  :started-at-ms started-at-ms
                  :source :save})
                (save-scenario-index-command
                 {:address address
                  :scenario-index scenario-index
                  :scenario-id scenario-id
                  :started-at-ms started-at-ms
                  :source :save})]}))

(defn complete-save
  [{:keys [state scenario-index scenario-record started-at-ms completed-at-ms]}]
  {:state (apply-scenario-save-success state
                                       scenario-index
                                       scenario-record
                                       started-at-ms
                                       completed-at-ms)
   :commands []})

(defn fail-save
  [{:keys [state scenario-id started-at-ms completed-at-ms error]}]
  {:state (apply-scenario-save-error state
                                     scenario-id
                                     started-at-ms
                                     completed-at-ms
                                     error)
   :commands []})

(defn begin-archive
  [{:keys [state address scenario-id started-at-ms]}]
  (if (and address scenario-id)
    {:state (assoc-in state
                      contracts/scenario-archive-state-path
                      (begin-scenario-archive-state scenario-id started-at-ms))
     :commands [(load-scenario-command
                 {:scenario-id scenario-id
                  :started-at-ms started-at-ms
                  :source :archive})]}
    {:state (apply-scenario-archive-error
             state
             scenario-id
             started-at-ms
             started-at-ms
             {:message "Cannot archive scenario without an address and scenario id."})
     :commands []}))

(defn continue-archive-after-record
  [{:keys [state address scenario-id scenario-record started-at-ms completed-at-ms]}]
  (if (map? scenario-record)
    {:state state
     :commands [(load-scenario-index-command
                 {:address address
                  :scenario-id scenario-id
                  :started-at-ms started-at-ms
                  :source :archive})]}
    {:state (apply-scenario-archive-error
             state
             scenario-id
             started-at-ms
             completed-at-ms
             {:message (str "Scenario " scenario-id " was not found.")})
     :commands []}))

(defn continue-archive-after-index
  [{:keys [state address scenario-id scenario-record started-at-ms loaded-index]}]
  (let [archived-record (scenario-records/archive-scenario-record
                         scenario-record
                         started-at-ms)
        scenario-index (scenario-records/refresh-scenario-index-summary
                        (or loaded-index
                            (get-in state contracts/scenario-index-path)
                            (default-scenario-index))
                        (scenario-records/scenario-summary archived-record))]
    {:state state
     :scenario-record archived-record
     :scenario-index scenario-index
     :commands [(save-scenario-command
                 {:scenario-id scenario-id
                  :scenario-record archived-record
                  :started-at-ms started-at-ms
                  :source :archive})
                (save-scenario-index-command
                 {:address address
                  :scenario-index scenario-index
                  :scenario-id scenario-id
                  :started-at-ms started-at-ms
                  :source :archive})]}))

(defn complete-archive
  [{:keys [state scenario-index scenario-record started-at-ms completed-at-ms]}]
  {:state (apply-scenario-archive-success state
                                          scenario-index
                                          scenario-record
                                          started-at-ms
                                          completed-at-ms)
   :commands []})

(defn fail-archive
  [{:keys [state scenario-id started-at-ms completed-at-ms error]}]
  {:state (apply-scenario-archive-error state
                                        scenario-id
                                        started-at-ms
                                        completed-at-ms
                                        error)
   :commands []})

(defn begin-duplicate
  [{:keys [state address scenario-id duplicated-scenario-id started-at-ms]}]
  (if (and address scenario-id duplicated-scenario-id)
    {:state (assoc-in state
                      contracts/scenario-duplicate-state-path
                      (begin-scenario-duplicate-state scenario-id started-at-ms))
     :commands [(load-scenario-command
                 {:scenario-id scenario-id
                  :started-at-ms started-at-ms
                  :duplicated-scenario-id duplicated-scenario-id
                  :source :duplicate})]}
    {:state (apply-scenario-duplicate-error
             state
             scenario-id
             started-at-ms
             started-at-ms
             {:message "Cannot duplicate scenario without an address and scenario id."})
     :commands []}))

(defn continue-duplicate-after-record
  [{:keys [state address scenario-id scenario-record started-at-ms completed-at-ms]}]
  (if (map? scenario-record)
    {:state state
     :commands [(load-scenario-index-command
                 {:address address
                  :scenario-id scenario-id
                  :started-at-ms started-at-ms
                  :source :duplicate})]}
    {:state (apply-scenario-duplicate-error
             state
             scenario-id
             started-at-ms
             completed-at-ms
             {:message (str "Scenario " scenario-id " was not found.")})
     :commands []}))

(defn continue-duplicate-after-index
  [{:keys [state address scenario-id duplicated-scenario-id scenario-record started-at-ms loaded-index]}]
  (let [duplicated-record (scenario-records/duplicate-scenario-record
                           {:source-record scenario-record
                            :scenario-id duplicated-scenario-id
                            :duplicated-at-ms started-at-ms})
        scenario-index (scenario-records/upsert-scenario-index
                        (or loaded-index
                            (get-in state contracts/scenario-index-path)
                            (default-scenario-index))
                        (scenario-records/scenario-summary duplicated-record))]
    {:state state
     :scenario-record duplicated-record
     :scenario-index scenario-index
     :commands [(save-scenario-command
                 {:scenario-id duplicated-scenario-id
                  :scenario-record duplicated-record
                  :started-at-ms started-at-ms
                  :source :duplicate})
                (save-scenario-index-command
                 {:address address
                  :scenario-index scenario-index
                  :scenario-id duplicated-scenario-id
                  :started-at-ms started-at-ms
                  :source :duplicate})]}))

(defn complete-duplicate
  [{:keys [state scenario-index scenario-record source-scenario-id started-at-ms completed-at-ms]}]
  {:state (apply-scenario-duplicate-success state
                                            scenario-index
                                            scenario-record
                                            source-scenario-id
                                            started-at-ms
                                            completed-at-ms)
   :commands []})

(defn fail-duplicate
  [{:keys [state scenario-id started-at-ms completed-at-ms error]}]
  {:state (apply-scenario-duplicate-error state
                                          scenario-id
                                          started-at-ms
                                          completed-at-ms
                                          error)
   :commands []})

(defn reset-workspace-state
  "Fresh-start projection for the New-scenario action: default draft, no active
  scenario, no retained run or save/load context, no autosave note. The
  wallet's libraries (return views, history assumptions, constraint profiles)
  and the saved-scenario index survive — forgetting remembered inputs is each
  library's own Clear affordance, not New's."
  [state]
  (-> state
      (assoc-in contracts/draft-path (optimizer-defaults/default-draft))
      (assoc-in contracts/active-scenario-path
                {:loaded-id nil
                 :status :idle
                 :read-only? false})
      (assoc-in contracts/last-successful-run-path nil)
      (assoc-in contracts/run-state-path (optimizer-defaults/default-run-state))
      (assoc-in contracts/optimization-progress-path
                (optimizer-defaults/default-optimization-progress-state))
      (assoc-in contracts/scenario-save-state-path
                (optimizer-defaults/default-scenario-save-state))
      (assoc-in contracts/scenario-save-modal-path
                (optimizer-defaults/default-scenario-save-modal-state))
      (assoc-in contracts/scenario-load-state-path
                (optimizer-defaults/default-scenario-load-state))
      (assoc-in contracts/tracking-path (optimizer-defaults/default-tracking-state))
      (assoc-in contracts/draft-persist-path nil)))

(defn begin-manual-tracking
  [{:keys [state address scenario-id started-at-ms]}]
  (if (and address scenario-id)
    {:state state
     :commands [(load-scenario-command
                 {:scenario-id scenario-id
                  :started-at-ms started-at-ms
                  :source :manual-tracking})]}
    {:state state
     :commands []}))

(defn continue-manual-tracking-after-record
  [{:keys [state address scenario-id scenario-record updated-at-ms]}]
  (if (and (map? scenario-record)
           (contains? manual-tracking-source-statuses
                      (:status scenario-record)))
    {:state state
     :scenario-record scenario-record
     :commands [(load-scenario-index-command
                 {:address address
                  :scenario-id scenario-id
                  :started-at-ms updated-at-ms
                  :source :manual-tracking})]}
    {:state state
     :scenario-record scenario-record
     :commands []}))

(defn continue-manual-tracking-after-index
  [{:keys [state address scenario-id scenario-record loaded-index updated-at-ms]}]
  (let [updated-record (scenario-records/mark-tracking-enabled
                        scenario-record
                        updated-at-ms)
        scenario-index (scenario-records/refresh-scenario-index-summary
                        (or loaded-index
                            (get-in state contracts/scenario-index-path)
                            (default-scenario-index))
                        (scenario-records/scenario-summary updated-record))]
    {:state state
     :scenario-record updated-record
     :scenario-index scenario-index
     :commands [(save-scenario-command
                 {:scenario-id scenario-id
                  :scenario-record updated-record
                  :started-at-ms updated-at-ms
                  :source :manual-tracking})
                (save-scenario-index-command
                 {:address address
                  :scenario-index scenario-index
                  :scenario-id scenario-id
                  :started-at-ms updated-at-ms
                  :source :manual-tracking})]}))

(defn complete-manual-tracking
  [{:keys [state scenario-index scenario-record]}]
  {:state (apply-manual-tracking-success state
                                         scenario-index
                                         scenario-record)
   :commands []})

(defn fail-manual-tracking
  [{:keys [state error]}]
  {:state (apply-manual-tracking-error state error)
   :commands []})
