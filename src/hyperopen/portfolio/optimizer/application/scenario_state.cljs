(ns hyperopen.portfolio.optimizer.application.scenario-state
  (:require [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(defn error-message
  [err]
  (or (when (map? err)
        (:message err))
      (some-> err .-message)
      (str err)))

(defn default-scenario-index
  []
  {:ordered-ids []
   :by-id {}})

(defn begin-scenario-save-state
  [scenario-id started-at-ms]
  {:status :saving
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil})

(defn saved-scenario-save-state
  [scenario-id started-at-ms completed-at-ms]
  {:status :saved
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil})

(defn failed-scenario-save-state
  [scenario-id started-at-ms completed-at-ms err]
  {:status :failed
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}})

(defn closed-scenario-save-modal-state
  []
  {:open? false
   :name ""
   :error nil})

(defn begin-scenario-index-load-state
  [started-at-ms]
  {:status :loading
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil})

(defn loaded-scenario-index-load-state
  [started-at-ms completed-at-ms]
  {:status :loaded
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil})

(defn failed-scenario-index-load-state
  [started-at-ms completed-at-ms err]
  {:status :failed
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}})

(defn begin-scenario-load-state
  [scenario-id started-at-ms]
  {:status :loading
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil})

(defn loaded-scenario-load-state
  [scenario-id started-at-ms completed-at-ms]
  {:status :loaded
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil})

(defn not-found-scenario-load-state
  [scenario-id started-at-ms completed-at-ms]
  {:status :not-found
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (str "Scenario " scenario-id " was not found.")}})

(defn failed-scenario-load-state
  [scenario-id started-at-ms completed-at-ms err]
  {:status :failed
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}})

(defn cleared-tracking-state
  [scenario-id]
  {:status :idle
   :scenario-id scenario-id
   :updated-at-ms nil
   :snapshots []
   :error nil})

(defn begin-scenario-archive-state
  [scenario-id started-at-ms]
  {:status :archiving
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil})

(defn archived-scenario-archive-state
  [scenario-id started-at-ms completed-at-ms]
  {:status :archived
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil})

(defn failed-scenario-archive-state
  [scenario-id started-at-ms completed-at-ms err]
  {:status :failed
   :scenario-id scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}})

(defn begin-scenario-duplicate-state
  [source-scenario-id started-at-ms]
  {:status :duplicating
   :source-scenario-id source-scenario-id
   :duplicated-scenario-id nil
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :error nil})

(defn duplicated-scenario-duplicate-state
  [source-scenario-id duplicated-scenario-id started-at-ms completed-at-ms]
  {:status :duplicated
   :source-scenario-id source-scenario-id
   :duplicated-scenario-id duplicated-scenario-id
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error nil})

(defn failed-scenario-duplicate-state
  [source-scenario-id started-at-ms completed-at-ms err]
  {:status :failed
   :source-scenario-id source-scenario-id
   :duplicated-scenario-id nil
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :error {:message (error-message err)}})

(defn- active-scenario-state
  [scenario-id status scenario-record]
  (cond-> {:loaded-id scenario-id
           :status status
           :read-only? false}
    (:address scenario-record)
    (assoc :address (:address scenario-record))))

(defn apply-scenario-save-success
  [state scenario-index scenario-record started-at-ms completed-at-ms]
  (-> state
      (assoc-in contracts/draft-path (:config scenario-record))
      (assoc-in contracts/active-scenario-path
                (active-scenario-state (:id scenario-record)
                                       :saved
                                       scenario-record))
      (assoc-in contracts/scenario-index-path scenario-index)
      (assoc-in contracts/scenario-save-state-path
                (saved-scenario-save-state (:id scenario-record)
                                           started-at-ms
                                           completed-at-ms))
      (assoc-in contracts/scenario-save-modal-path
                (closed-scenario-save-modal-state))))

(defn apply-scenario-save-error
  [state scenario-id started-at-ms completed-at-ms err]
  (let [message (error-message err)]
    (-> state
        (assoc-in contracts/scenario-save-state-path
                  (failed-scenario-save-state scenario-id
                                              started-at-ms
                                              completed-at-ms
                                              err))
        (assoc-in contracts/scenario-save-modal-error-path message))))

(defn apply-scenario-index-load-success
  [state scenario-index started-at-ms completed-at-ms]
  (-> state
      (assoc-in contracts/scenario-index-path scenario-index)
      (assoc-in contracts/scenario-index-load-state-path
                (loaded-scenario-index-load-state started-at-ms completed-at-ms))))

(defn apply-scenario-index-load-error
  [state started-at-ms completed-at-ms err]
  (assoc-in state
            contracts/scenario-index-load-state-path
            (failed-scenario-index-load-state started-at-ms
                                             completed-at-ms
                                             err)))

(defn apply-scenario-load-success
  ([state scenario-id scenario-record started-at-ms completed-at-ms]
   (apply-scenario-load-success state
                                scenario-id
                                scenario-record
                                nil
                                started-at-ms
                                completed-at-ms))
  ([state scenario-id scenario-record tracking-record started-at-ms completed-at-ms]
   (let [scenario-index (scenario-records/refresh-scenario-index-summary
                         (or (get-in state contracts/scenario-index-path)
                             (default-scenario-index))
                         (scenario-records/scenario-summary scenario-record))]
     (-> state
         (assoc-in contracts/draft-path (:config scenario-record))
         (assoc-in contracts/ui-target-sigma-draft-path nil)
         (assoc-in contracts/ui-objective-menu-target-sigma-path nil)
         (assoc-in contracts/last-successful-run-path (:saved-run scenario-record))
         (assoc-in contracts/active-scenario-path
                   (active-scenario-state scenario-id
                                          (:status scenario-record)
                                          scenario-record))
         (assoc-in contracts/scenario-index-path scenario-index)
         (assoc-in contracts/scenario-load-state-path
                   (loaded-scenario-load-state scenario-id
                                               started-at-ms
                                               completed-at-ms))
         (assoc-in contracts/tracking-path
                   (if (map? tracking-record)
                     tracking-record
                     (cleared-tracking-state scenario-id)))))))

(defn apply-scenario-load-not-found
  [state scenario-id started-at-ms completed-at-ms]
  (assoc-in state
            contracts/scenario-load-state-path
            (not-found-scenario-load-state scenario-id
                                           started-at-ms
                                           completed-at-ms)))

(defn apply-scenario-load-error
  [state scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            contracts/scenario-load-state-path
            (failed-scenario-load-state scenario-id
                                        started-at-ms
                                        completed-at-ms
                                        err)))

(defn apply-scenario-archive-success
  [state scenario-index archived-record started-at-ms completed-at-ms]
  (let [scenario-id (:id archived-record)
        active? (= scenario-id
                   (get-in state contracts/active-scenario-loaded-id-path))]
    (cond-> (-> state
                (assoc-in contracts/scenario-index-path scenario-index)
                (assoc-in contracts/scenario-archive-state-path
                          (archived-scenario-archive-state scenario-id
                                                           started-at-ms
                                                           completed-at-ms)))
      active?
      (assoc-in contracts/active-scenario-status-path :archived)

      active?
      (assoc-in contracts/draft-path (:config archived-record)))))

(defn apply-scenario-archive-error
  [state scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            contracts/scenario-archive-state-path
            (failed-scenario-archive-state scenario-id
                                           started-at-ms
                                           completed-at-ms
                                           err)))

(defn apply-scenario-duplicate-success
  [state scenario-index duplicated-record source-scenario-id started-at-ms completed-at-ms]
  (-> state
      (assoc-in contracts/scenario-index-path scenario-index)
      (assoc-in contracts/scenario-duplicate-state-path
                (duplicated-scenario-duplicate-state source-scenario-id
                                                     (:id duplicated-record)
                                                     started-at-ms
                                                     completed-at-ms))))

(defn apply-scenario-duplicate-error
  [state source-scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            contracts/scenario-duplicate-state-path
            (failed-scenario-duplicate-state source-scenario-id
                                             started-at-ms
                                             completed-at-ms
                                             err)))

(defn apply-manual-tracking-success
  [state scenario-index scenario-record]
  (let [scenario-id (:id scenario-record)]
    (-> state
        (assoc-in contracts/scenario-index-path scenario-index)
        (assoc-in contracts/draft-path (:config scenario-record))
        (assoc-in contracts/active-scenario-path
                  (active-scenario-state scenario-id
                                         (:status scenario-record)
                                         scenario-record))
        (assoc-in contracts/tracking-path
                  (cleared-tracking-state scenario-id)))))

(defn apply-manual-tracking-error
  [state err]
  (assoc-in state
            contracts/tracking-error-path
            {:message (error-message err)}))
