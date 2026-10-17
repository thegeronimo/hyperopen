(ns hyperopen.portfolio.optimizer.application.vault-funding.run
  "Pure state of a vault-funding run: one row per signed `vaultTransfer`,
  withdrawals before deposits, each queued → sending → done | failed |
  unknown, or skipped when the run is stopped or a withdrawal fails (the
  deposits may need its cash).

  :failed means the transfer certainly did not happen (the exchange refused
  it, or a check stopped it before sending), so Retry may send it again.
  :unknown means it may have landed (the request broke off mid-flight), so it
  is never resent: the user re-plans from refreshed holdings instead.

  A run remembers the wallet that signs it (`:owner`) and the optimizer
  result it was planned from (`:source`); it is only resumable while both
  still match."
  (:require [hyperopen.portfolio.optimizer.application.vault-funding.plan :as plan]))

(def terminal-statuses
  #{:done :partial :failed})

(defn step->row
  [{:keys [kind address label usd]}]
  {:row-id (str (name kind) ":" address)
   :kind kind
   :address address
   :label label
   :usd usd
   :usd-micros (plan/usd->micros usd)
   :status :queued})

(defn rows-from-plan
  [funding-plan]
  (mapv step->row (:steps funding-plan)))

(defn transfer-action
  "The exchange action, keys in the order the vaultTransfer contract checks."
  [{:keys [kind address usd-micros]}]
  (array-map :type "vaultTransfer"
             :vaultAddress address
             :isDeposit (= kind :deposit)
             :usd usd-micros))

(defn new-run
  [rows attempt-id now-ms & [{:keys [owner source]}]]
  {:attempt-id attempt-id
   :owner owner
   :source source
   :status :running
   :rows (vec rows)
   :started-at-ms now-ms
   :completed-at-ms nil
   :error nil
   :abort-requested? false})

(defn update-row
  [run row-id f]
  (update run :rows (fn [rows] (mapv #(if (= row-id (:row-id %)) (f %) %) rows))))

(defn mark-sending
  [run row-id]
  (update-row run row-id #(-> % (assoc :status :sending) (dissoc :error :reason))))

(defn mark-done
  [run row-id]
  (update-row run row-id #(-> % (assoc :status :done) (dissoc :error :reason))))

(defn mark-failed
  [run row-id message]
  (update-row run row-id #(assoc % :status :failed :error (or message "Transfer failed."))))

(defn mark-unknown
  [run row-id message]
  (update-row run row-id #(assoc % :status :unknown :error (or message "No answer from the exchange."))))

(defn claim
  "Move `row-id` from :queued to :sending, or leave the run as it is when the
  row is no longer queued (another chain already took it)."
  [run row-id]
  (update-row run row-id #(if (= :queued (:status %))
                            (-> % (assoc :status :sending) (dissoc :error :reason))
                            %)))

(defn row-by-id
  [run row-id]
  (some #(when (= row-id (:row-id %)) %) (:rows run)))

(defn mark-skipped
  [run row-id reason]
  (update-row run row-id #(assoc % :status :skipped :reason reason)))

(defn withdrawal-failed?
  [run]
  (boolean (some #(and (= :withdraw (:kind %)) (= :failed (:status %))) (:rows run))))

(defn next-action
  "What the runner does with `row` now: :send, :skip-aborted, :skip-halted or
  :pass (already settled)."
  [run row]
  (cond
    (not= :queued (:status row)) :pass
    (:abort-requested? run) :skip-aborted
    (and (= :deposit (:kind row)) (withdrawal-failed? run)) :skip-halted
    :else :send))

(defn final-status
  [rows]
  (let [statuses (map :status rows)]
    (cond
      (and (seq statuses) (every? #{:done} statuses)) :done
      (some #{:done} statuses) :partial
      :else :failed)))

(defn finish
  [run now-ms]
  (assoc run
         :status (final-status (:rows run))
         :completed-at-ms now-ms
         :abort-requested? false))

(defn fail-run
  "The run could not start or broke off: unsent rows stay queued for retry."
  [run message now-ms]
  (-> run
      (update :rows (fn [rows] (mapv #(if (= :sending (:status %)) (assoc % :status :queued) %) rows)))
      (assoc :status :failed :error message :completed-at-ms now-ms :abort-requested? false)))

(defn retryable-rows
  "Rows Retry may send: refused, skipped or never started. Never :unknown."
  [run]
  (filterv #(contains? #{:failed :skipped :queued} (:status %)) (:rows run)))

(defn matches?
  "Whether a run still belongs to this wallet and this optimizer result."
  [run owner source]
  (and (some? (:owner run)) (= (:owner run) owner) (= (:source run) source)))

(defn retry
  "Re-queue failed and skipped rows; landed rows stay done and are not resent."
  [run attempt-id now-ms]
  (-> run
      (update :rows (fn [rows]
                      (mapv #(if (contains? #{:failed :skipped} (:status %))
                               (-> % (assoc :status :queued) (dissoc :error :reason))
                               %)
                            rows)))
      (assoc :attempt-id attempt-id
             :status :running
             :started-at-ms now-ms
             :completed-at-ms nil
             :error nil
             :abort-requested? false)))

(defn summary
  [run]
  (let [rows (:rows run)
        landed (filter #(= :done (:status %)) rows)
        sum (fn [kind] (reduce + 0 (map :usd (filter #(= kind (:kind %)) landed))))]
    {:total (count rows)
     :done (count landed)
     :failed (count (filter #(= :failed (:status %)) rows))
     :skipped (count (filter #(= :skipped (:status %)) rows))
     :unknown (count (filter #(= :unknown (:status %)) rows))
     :sending (count (filter #(= :sending (:status %)) rows))
     :queued (count (filter #(= :queued (:status %)) rows))
     :withdrawn (sum :withdraw)
     :deposited (sum :deposit)}))
