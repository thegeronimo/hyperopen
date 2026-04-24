(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-scenario-state
  (:require [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]))

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

(defn apply-scenario-save-success
  [state scenario-index scenario-record started-at-ms completed-at-ms]
  (-> state
      (assoc-in [:portfolio :optimizer :draft] (:config scenario-record))
      (assoc-in [:portfolio :optimizer :active-scenario]
                {:loaded-id (:id scenario-record)
                 :status :saved
                 :read-only? false})
      (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
      (assoc-in [:portfolio :optimizer :scenario-save-state]
                (saved-scenario-save-state (:id scenario-record)
                                           started-at-ms
                                           completed-at-ms))))

(defn apply-scenario-save-error
  [state scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            [:portfolio :optimizer :scenario-save-state]
            (failed-scenario-save-state scenario-id
                                        started-at-ms
                                        completed-at-ms
                                        err)))

(defn apply-scenario-index-load-success
  [state scenario-index started-at-ms completed-at-ms]
  (-> state
      (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
      (assoc-in [:portfolio :optimizer :scenario-index-load-state]
                (loaded-scenario-index-load-state started-at-ms completed-at-ms))))

(defn apply-scenario-index-load-error
  [state started-at-ms completed-at-ms err]
  (assoc-in state
            [:portfolio :optimizer :scenario-index-load-state]
            (failed-scenario-index-load-state started-at-ms
                                             completed-at-ms
                                             err)))

(defn apply-scenario-load-success
  [state scenario-id scenario-record started-at-ms completed-at-ms]
  (let [scenario-index (scenario-records/refresh-scenario-index-summary
                        (or (get-in state [:portfolio :optimizer :scenario-index])
                            (default-scenario-index))
                        (scenario-records/scenario-summary scenario-record))]
    (-> state
        (assoc-in [:portfolio :optimizer :draft] (:config scenario-record))
        (assoc-in [:portfolio :optimizer :last-successful-run] (:saved-run scenario-record))
        (assoc-in [:portfolio :optimizer :active-scenario]
                  {:loaded-id scenario-id
                   :status (:status scenario-record)
                   :read-only? false})
        (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
        (assoc-in [:portfolio :optimizer :scenario-load-state]
                  (loaded-scenario-load-state scenario-id
                                              started-at-ms
                                              completed-at-ms)))))

(defn apply-scenario-load-not-found
  [state scenario-id started-at-ms completed-at-ms]
  (assoc-in state
            [:portfolio :optimizer :scenario-load-state]
            (not-found-scenario-load-state scenario-id
                                           started-at-ms
                                           completed-at-ms)))

(defn apply-scenario-load-error
  [state scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            [:portfolio :optimizer :scenario-load-state]
            (failed-scenario-load-state scenario-id
                                        started-at-ms
                                        completed-at-ms
                                        err)))

(defn apply-scenario-archive-success
  [state scenario-index archived-record started-at-ms completed-at-ms]
  (let [scenario-id (:id archived-record)
        active? (= scenario-id
                   (get-in state [:portfolio :optimizer :active-scenario :loaded-id]))]
    (cond-> (-> state
                (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
                (assoc-in [:portfolio :optimizer :scenario-archive-state]
                          (archived-scenario-archive-state scenario-id
                                                           started-at-ms
                                                           completed-at-ms)))
      active?
      (assoc-in [:portfolio :optimizer :active-scenario :status] :archived)

      active?
      (assoc-in [:portfolio :optimizer :draft] (:config archived-record)))))

(defn apply-scenario-archive-error
  [state scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            [:portfolio :optimizer :scenario-archive-state]
            (failed-scenario-archive-state scenario-id
                                           started-at-ms
                                           completed-at-ms
                                           err)))

(defn apply-scenario-duplicate-success
  [state scenario-index duplicated-record source-scenario-id started-at-ms completed-at-ms]
  (-> state
      (assoc-in [:portfolio :optimizer :scenario-index] scenario-index)
      (assoc-in [:portfolio :optimizer :scenario-duplicate-state]
                (duplicated-scenario-duplicate-state source-scenario-id
                                                     (:id duplicated-record)
                                                     started-at-ms
                                                     completed-at-ms))))

(defn apply-scenario-duplicate-error
  [state source-scenario-id started-at-ms completed-at-ms err]
  (assoc-in state
            [:portfolio :optimizer :scenario-duplicate-state]
            (failed-scenario-duplicate-state source-scenario-id
                                             started-at-ms
                                             completed-at-ms
                                             err)))
