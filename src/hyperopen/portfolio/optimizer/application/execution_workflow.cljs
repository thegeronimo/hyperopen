(ns hyperopen.portfolio.optimizer.application.execution-workflow
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.execution :as execution]
            [hyperopen.portfolio.optimizer.application.execution-carryover :as carryover]
            [hyperopen.portfolio.optimizer.application.scenario-records :as scenario-records]
            [hyperopen.portfolio.optimizer.application.scenario-workflow :as scenario-workflow]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(defn- non-blank
  [value]
  (when (string? value)
    (let [text (str/trim value)]
      (when (seq text) text))))

(defn order-error-message
  "Human-readable error from a rejected order response or a thrown error, for the execution
   ledger Detail column — never a pr-str'd map. Hyperliquid carries the per-order rejection
   reason at [:response :data :statuses 0 :error] (the same shape an accepted order uses for
   :resting/:filled), so prefer that; then a top-level :error/:message, then a plain string
   :response, an exception message, or a bare string. Falls back to a generic line so a
   structured value never leaks to the UI."
  [value]
  (or (when (map? value)
        (or (non-blank (:error (first (execution/response-statuses value))))
            (non-blank (:error value))
            (non-blank (:message value))
            (non-blank (:response value))))
      (non-blank (some-> value .-message))
      (non-blank value)
      "The exchange rejected this order."))

(defn error-message
  [err]
  (order-error-message err))

(defn begin-execution-state
  [attempt started-at-ms]
  {:status :submitting
   :attempt attempt
   :run-attempt attempt
   :started-at-ms started-at-ms
   :completed-at-ms nil
   :history []
   :error nil})

(defn set-run-attempt-row-status
  "Updates one in-flight run-attempt row (by :row-id) with new status fields, so the
  running view animates orders as they are submitted. No-op when there is no live
  run-attempt (e.g. the no-wallet branch never seeds one)."
  [state row-id status-update]
  (update-in state (conj contracts/execution-run-attempt-path :rows)
             (fn [rows]
               (mapv (fn [row]
                       (if (= row-id (:row-id row))
                         (merge row status-update)
                         row))
                     (or rows [])))))

(defn execution-ledger
  [attempt started-at-ms completed-at-ms rows]
  {:attempt-id (str "exec_" started-at-ms)
   :scenario-id (:scenario-id attempt)
   :status (execution/final-ledger-status rows)
   :started-at-ms started-at-ms
   :completed-at-ms completed-at-ms
   :rows rows})

(defn apply-execution-ledger
  [state ledger]
  (let [history (conj (vec (get-in state contracts/execution-history-path))
                      ledger)
        scenario-status (:status ledger)]
    (cond-> (-> state
                (assoc-in contracts/execution-path
                          {:status scenario-status
                           :attempt nil
                           :history history
                           :error (when (= :failed scenario-status)
                                    {:message "Execution failed before any rows submitted."})})
                (update-in contracts/execution-resting-carryover-path
                           carryover/record-resting-carryover ledger)
                (assoc-in contracts/execution-modal-submitting-path false)
                (assoc-in contracts/execution-modal-error-path
                          (when (= :failed scenario-status)
                            "Execution failed before any rows submitted.")))
      (contains? #{:executed :partially-executed} scenario-status)
      (assoc-in contracts/active-scenario-status-path scenario-status))))

(defn- load-scenario-command
  [scenario-id]
  {:command/type :optimizer.workflow/load-scenario
   :source :execution-ledger
   :scenario-id scenario-id})

(defn- load-scenario-index-command
  [address scenario-id]
  {:command/type :optimizer.workflow/load-scenario-index
   :source :execution-ledger
   :address address
   :scenario-id scenario-id})

(defn- save-scenario-command
  [scenario-id scenario-record]
  {:command/type :optimizer.workflow/save-scenario
   :source :execution-ledger
   :scenario-id scenario-id
   :scenario-record scenario-record})

(defn- save-scenario-index-command
  [address scenario-id scenario-index]
  {:command/type :optimizer.workflow/save-scenario-index
   :source :execution-ledger
   :address address
   :scenario-id scenario-id
   :scenario-index scenario-index})

(defn begin-ledger-persistence
  [{:keys [state address ledger]}]
  (let [scenario-id (:scenario-id ledger)]
    {:state state
     :commands (if (and address scenario-id)
                 [(load-scenario-command scenario-id)]
                 [])}))

(defn continue-ledger-persistence-after-record
  [{:keys [state address ledger scenario-record]}]
  (let [scenario-id (:scenario-id ledger)]
    {:state state
     :scenario-record scenario-record
     :commands (if (and address scenario-id (map? scenario-record))
                 [(load-scenario-index-command address scenario-id)]
                 [])}))

(defn continue-ledger-persistence-after-index
  [{:keys [state address ledger scenario-record loaded-index]}]
  (let [scenario-id (:scenario-id ledger)
        updated-record (scenario-records/append-execution-ledger
                        scenario-record
                        ledger)
        scenario-index (scenario-records/refresh-scenario-index-summary
                        (or loaded-index
                            (get-in state contracts/scenario-index-path)
                            (scenario-workflow/default-scenario-index))
                        (scenario-records/scenario-summary updated-record))]
    {:state state
     :scenario-record updated-record
     :scenario-index scenario-index
     :commands [(save-scenario-command scenario-id updated-record)
                (save-scenario-index-command address scenario-id scenario-index)]}))

(defn complete-ledger-persistence
  [{:keys [state scenario-index scenario-record]}]
  {:state (-> state
              (assoc-in contracts/scenario-index-path scenario-index)
              (assoc-in contracts/draft-path (:config scenario-record))
              (assoc-in contracts/active-scenario-status-path
                        (:status scenario-record)))
   :commands []})

(defn fail-ledger-persistence
  [{:keys [state error]}]
  {:state (assoc-in state
                    contracts/execution-persistence-error-path
                    {:message (error-message error)})
   :commands []})
