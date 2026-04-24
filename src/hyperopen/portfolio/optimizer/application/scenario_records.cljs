(ns hyperopen.portfolio.optimizer.application.scenario-records)

(defn- result-summary
  [saved-run & ks]
  (get-in saved-run (into [:result] ks)))

(defn- with-saved-metadata
  [draft saved-at-ms]
  (-> draft
      (assoc-in [:metadata :dirty?] false)
      (assoc-in [:metadata :saved-at-ms] saved-at-ms)
      (assoc-in [:metadata :updated-at-ms] saved-at-ms)))

(defn build-saved-scenario-record
  [{:keys [address scenario-id draft last-successful-run saved-at-ms]}]
  (let [config (-> (with-saved-metadata draft saved-at-ms)
                   (assoc :id scenario-id
                          :status :saved))
        created-at-ms (or (get-in draft [:metadata :created-at-ms])
                          saved-at-ms)]
    {:schema-version 1
     :id scenario-id
     :name (or (:name draft) "Untitled Optimization")
     :address address
     :status :saved
     :config config
     :saved-run last-successful-run
     :created-at-ms created-at-ms
     :updated-at-ms saved-at-ms}))

(defn scenario-summary
  [scenario-record]
  (let [config (:config scenario-record)
        saved-run (:saved-run scenario-record)]
    {:id (:id scenario-record)
     :name (:name scenario-record)
     :status (:status scenario-record)
     :objective-kind (get-in config [:objective :kind])
     :return-model-kind (get-in config [:return-model :kind])
     :risk-model-kind (get-in config [:risk-model :kind])
     :expected-return (result-summary saved-run :expected-return)
     :volatility (result-summary saved-run :volatility)
     :rebalance-status (result-summary saved-run :rebalance-preview :status)
     :updated-at-ms (:updated-at-ms scenario-record)}))

(defn upsert-scenario-index
  [scenario-index summary]
  (let [scenario-id (:id summary)
        ordered-ids (vec (cons scenario-id
                               (remove #(= scenario-id %)
                                       (:ordered-ids scenario-index))))]
    {:ordered-ids ordered-ids
     :by-id (assoc (:by-id scenario-index) scenario-id summary)}))
