(ns hyperopen.api.projections.vaults
  (:require [clojure.string :as str]
            [hyperopen.api.errors :as api-errors]))

(defn- normalized-error
  [err]
  (api-errors/normalize-error err))

(defn- normalize-vault-address
  [value]
  (some-> value str str/trim str/lower-case))

(defn- rows->vec
  [rows]
  (if (sequential? rows)
    (vec rows)
    []))

(defn- dedupe-vault-rows
  [rows]
  (reduce (fn [{:keys [order by-address]} row]
            (let [address (normalize-vault-address (:vault-address row))]
              (if (seq address)
                (if (contains? by-address address)
                  {:order order
                   :by-address (assoc by-address address row)}
                  {:order (conj order address)
                   :by-address (assoc by-address address row)})
                {:order order
                 :by-address by-address})))
          {:order []
           :by-address {}}
          (if (sequential? rows) rows [])))

(defn- merge-vault-rows
  [index-rows summary-rows]
  (let [{:keys [order by-address]} (dedupe-vault-rows (concat (or index-rows [])
                                                              (or summary-rows [])))]
    (mapv by-address order)))

(defn- update-vault-index-cache
  [state {:keys [hydrated?
                 saved-at-ms
                 etag
                 last-modified
                 live-response-status]
          :as metadata}]
  (let [current (or (get-in state [:vaults :index-cache]) {})]
    (assoc-in state
              [:vaults :index-cache]
              {:hydrated? (if (contains? metadata :hydrated?)
                            (boolean hydrated?)
                            (boolean (:hydrated? current)))
               :saved-at-ms (if (contains? metadata :saved-at-ms)
                              saved-at-ms
                              (:saved-at-ms current))
               :etag (if (contains? metadata :etag)
                       (or etag (:etag current))
                       (:etag current))
               :last-modified (if (contains? metadata :last-modified)
                                (or last-modified (:last-modified current))
                                (:last-modified current))
               :live-response-status (if (contains? metadata :live-response-status)
                                       live-response-status
                                       (:live-response-status current))})))

(defn- vault-list-loading?
  [state]
  (or (true? (get-in state [:vaults :loading :index?]))
      (true? (get-in state [:vaults :loading :summaries?]))))

(defn- vault-detail-loading?
  [state vault-address]
  (or (true? (get-in state [:vaults :loading :details-by-address vault-address]))
      (true? (get-in state [:vaults :loading :webdata-by-vault vault-address]))))

(defn begin-vault-index-load
  [state]
  (let [state* (-> state
                   (assoc-in [:vaults :loading :index?] true)
                   (assoc-in [:vaults :errors :index] nil)
                   (update-vault-index-cache {:live-response-status nil}))]
    (assoc-in state* [:vaults-ui :list-loading?] (vault-list-loading? state*))))

(defn apply-vault-index-cache-hydration
  [state cache-record]
  (let [live-response-status (get-in state [:vaults :index-cache :live-response-status])]
    (if (= :ok live-response-status)
      state
      (let [rows* (if (sequential? (:rows cache-record))
                    (vec (:rows cache-record))
                    [])
            summaries (get-in state [:vaults :recent-summaries] [])
            saved-at-ms (:saved-at-ms cache-record)
            state* (cond-> (-> state
                               (assoc-in [:vaults :index-rows] rows*)
                               (assoc-in [:vaults :merged-index-rows] (merge-vault-rows rows* summaries))
                               (update-vault-index-cache {:hydrated? (seq rows*)
                                                          :saved-at-ms saved-at-ms
                                                          :etag (:etag cache-record)
                                                          :last-modified (:last-modified cache-record)}))
                     (not= :error live-response-status) (assoc-in [:vaults :errors :index] nil)
                     (number? saved-at-ms) (assoc-in [:vaults :loaded-at-ms :index] saved-at-ms))]
        (assoc-in state* [:vaults-ui :list-loading?] (vault-list-loading? state*))))))

(defn- normalize-vault-index-success-payload
  [payload]
  (if (and (map? payload)
           (contains? #{:ok :not-modified} (:status payload)))
    payload
    {:status :ok
     :rows payload}))

(defn apply-vault-index-success
  [state payload]
  (let [{:keys [status rows etag last-modified]} (normalize-vault-index-success-payload payload)
        now-ms (.now js/Date)]
    (case status
      :not-modified
      (let [state* (-> state
                       (assoc-in [:vaults :loading :index?] false)
                       (assoc-in [:vaults :errors :index] nil)
                       (assoc-in [:vaults :loaded-at-ms :index] now-ms)
                       (update-vault-index-cache {:saved-at-ms now-ms
                                                  :etag etag
                                                  :last-modified last-modified
                                                  :live-response-status :not-modified}))]
        (assoc-in state* [:vaults-ui :list-loading?] (vault-list-loading? state*)))

      (let [rows* (if (sequential? rows) (vec rows) [])
            summaries (get-in state [:vaults :recent-summaries] [])
            state* (-> state
                       (assoc-in [:vaults :index-rows] rows*)
                       (assoc-in [:vaults :merged-index-rows] (merge-vault-rows rows* summaries))
                       (assoc-in [:vaults :loading :index?] false)
                       (assoc-in [:vaults :errors :index] nil)
                       (assoc-in [:vaults :loaded-at-ms :index] now-ms)
                       (update-vault-index-cache {:hydrated? false
                                                  :saved-at-ms now-ms
                                                  :etag etag
                                                  :last-modified last-modified
                                                  :live-response-status :ok}))]
        (assoc-in state* [:vaults-ui :list-loading?] (vault-list-loading? state*))))))

(defn apply-vault-index-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (let [state* (-> state
                     (assoc-in [:vaults :loading :index?] false)
                     (assoc-in [:vaults :errors :index] message)
                     (update-vault-index-cache {:live-response-status :error}))]
      (assoc-in state* [:vaults-ui :list-loading?] (vault-list-loading? state*)))))

(defn begin-vault-summaries-load
  [state]
  (let [state* (-> state
                   (assoc-in [:vaults :loading :summaries?] true)
                   (assoc-in [:vaults :errors :summaries] nil))]
    (assoc-in state* [:vaults-ui :list-loading?] (vault-list-loading? state*))))

(defn apply-vault-summaries-success
  [state rows]
  (let [rows* (if (sequential? rows) (vec rows) [])
        index-rows (get-in state [:vaults :index-rows] [])
        state* (-> state
                   (assoc-in [:vaults :recent-summaries] rows*)
                   (assoc-in [:vaults :merged-index-rows] (merge-vault-rows index-rows rows*))
                   (assoc-in [:vaults :loading :summaries?] false)
                   (assoc-in [:vaults :errors :summaries] nil)
                   (assoc-in [:vaults :loaded-at-ms :summaries] (.now js/Date)))]
    (assoc-in state* [:vaults-ui :list-loading?] (vault-list-loading? state*))))

(defn apply-vault-summaries-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (let [state* (-> state
                     (assoc-in [:vaults :loading :summaries?] false)
                     (assoc-in [:vaults :errors :summaries] message))]
      (assoc-in state* [:vaults-ui :list-loading?] (vault-list-loading? state*)))))

(defn begin-user-vault-equities-load
  [state]
  (-> state
      (assoc-in [:vaults :loading :user-equities?] true)
      (assoc-in [:vaults :errors :user-equities] nil)))

(defn apply-user-vault-equities-success
  [state rows]
  (let [rows* (if (sequential? rows) (vec rows) [])
        by-address (reduce (fn [acc row]
                             (if-let [address (normalize-vault-address (:vault-address row))]
                               (assoc acc address row)
                               acc))
                           {}
                           rows*)]
    (-> state
        (assoc-in [:vaults :user-equities] rows*)
        (assoc-in [:vaults :user-equity-by-address] by-address)
        (assoc-in [:vaults :loading :user-equities?] false)
        (assoc-in [:vaults :errors :user-equities] nil)
        (assoc-in [:vaults :loaded-at-ms :user-equities] (.now js/Date)))))

(defn apply-user-vault-equities-error
  [state err]
  (let [{:keys [message]} (normalized-error err)]
    (-> state
        (assoc-in [:vaults :loading :user-equities?] false)
        (assoc-in [:vaults :errors :user-equities] message))))

(def ^:private user-scoped-vault-detail-keys
  #{:follower-state
    :max-distributable
    :max-withdrawable})

(defn- public-vault-details-payload
  [payload viewer-address]
  (if (and (map? payload)
           (normalize-vault-address viewer-address))
    (apply dissoc payload user-scoped-vault-detail-keys)
    payload))

(defn begin-vault-details-load
  ([state vault-address]
   (begin-vault-details-load state vault-address nil))
  ([state vault-address _viewer-address]
   (if-let [vault-address* (normalize-vault-address vault-address)]
     (let [state* (-> state
                      (assoc-in [:vaults :loading :details-by-address vault-address*] true)
                      (assoc-in [:vaults :errors :details-by-address vault-address*] nil))]
       (assoc-in state* [:vaults-ui :detail-loading?] (vault-detail-loading? state* vault-address*)))
     state)))

(defn apply-vault-details-success
  ([state vault-address payload]
   (apply-vault-details-success state vault-address nil payload))
  ([state vault-address viewer-address payload]
   (if-let [vault-address* (normalize-vault-address vault-address)]
     (let [viewer-address* (normalize-vault-address viewer-address)
           state* (cond-> (-> state
                              (assoc-in [:vaults :details-by-address vault-address*]
                                        (public-vault-details-payload payload viewer-address*))
                              (assoc-in [:vaults :loading :details-by-address vault-address*] false)
                              (assoc-in [:vaults :errors :details-by-address vault-address*] nil)
                              (assoc-in [:vaults :loaded-at-ms :details-by-address vault-address*] (.now js/Date)))
                    viewer-address*
                    (assoc-in [:vaults :viewer-details-by-address vault-address* viewer-address*] payload))]
       (assoc-in state* [:vaults-ui :detail-loading?] (vault-detail-loading? state* vault-address*)))
     state)))

(defn apply-vault-details-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (let [state* (-> state
                       (assoc-in [:vaults :loading :details-by-address vault-address*] false)
                       (assoc-in [:vaults :errors :details-by-address vault-address*] message))]
        (assoc-in state* [:vaults-ui :detail-loading?] (vault-detail-loading? state* vault-address*))))
    state))

(defn begin-vault-benchmark-details-load
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :loading :benchmark-details-by-address vault-address*] true)
        (assoc-in [:vaults :errors :benchmark-details-by-address vault-address*] nil))
    state))

(defn apply-vault-benchmark-details-success
  [state vault-address payload]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :benchmark-details-by-address vault-address*] payload)
        (assoc-in [:vaults :loading :benchmark-details-by-address vault-address*] false)
        (assoc-in [:vaults :errors :benchmark-details-by-address vault-address*] nil)
        (assoc-in [:vaults :loaded-at-ms :benchmark-details-by-address vault-address*] (.now js/Date)))
    state))

(defn apply-vault-benchmark-details-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (-> state
          (assoc-in [:vaults :loading :benchmark-details-by-address vault-address*] false)
          (assoc-in [:vaults :errors :benchmark-details-by-address vault-address*] message)))
    state))

(defn begin-vault-webdata2-load
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [state* (-> state
                     (assoc-in [:vaults :loading :webdata-by-vault vault-address*] true)
                     (assoc-in [:vaults :errors :webdata-by-vault vault-address*] nil))]
      (assoc-in state* [:vaults-ui :detail-loading?] (vault-detail-loading? state* vault-address*)))
    state))

(defn apply-vault-webdata2-success
  [state vault-address payload]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [state* (-> state
                     (assoc-in [:vaults :webdata-by-vault vault-address*] payload)
                     (assoc-in [:vaults :loading :webdata-by-vault vault-address*] false)
                     (assoc-in [:vaults :errors :webdata-by-vault vault-address*] nil)
                     (assoc-in [:vaults :loaded-at-ms :webdata-by-vault vault-address*] (.now js/Date)))]
      (assoc-in state* [:vaults-ui :detail-loading?] (vault-detail-loading? state* vault-address*)))
    state))

(defn apply-vault-webdata2-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (let [state* (-> state
                       (assoc-in [:vaults :loading :webdata-by-vault vault-address*] false)
                       (assoc-in [:vaults :errors :webdata-by-vault vault-address*] message))]
        (assoc-in state* [:vaults-ui :detail-loading?] (vault-detail-loading? state* vault-address*))))
    state))

(defn begin-vault-fills-load
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :loading :fills-by-vault vault-address*] true)
        (assoc-in [:vaults :errors :fills-by-vault vault-address*] nil))
    state))

(defn apply-vault-fills-success
  [state vault-address rows]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :fills-by-vault vault-address*] (rows->vec rows))
        (assoc-in [:vaults :loading :fills-by-vault vault-address*] false)
        (assoc-in [:vaults :errors :fills-by-vault vault-address*] nil)
        (assoc-in [:vaults :loaded-at-ms :fills-by-vault vault-address*] (.now js/Date)))
    state))

(defn apply-vault-fills-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (-> state
          (assoc-in [:vaults :loading :fills-by-vault vault-address*] false)
          (assoc-in [:vaults :errors :fills-by-vault vault-address*] message)))
    state))

(defn begin-vault-funding-history-load
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :loading :funding-history-by-vault vault-address*] true)
        (assoc-in [:vaults :errors :funding-history-by-vault vault-address*] nil))
    state))

(defn apply-vault-funding-history-success
  [state vault-address rows]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :funding-history-by-vault vault-address*] (rows->vec rows))
        (assoc-in [:vaults :loading :funding-history-by-vault vault-address*] false)
        (assoc-in [:vaults :errors :funding-history-by-vault vault-address*] nil)
        (assoc-in [:vaults :loaded-at-ms :funding-history-by-vault vault-address*] (.now js/Date)))
    state))

(defn apply-vault-funding-history-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (-> state
          (assoc-in [:vaults :loading :funding-history-by-vault vault-address*] false)
          (assoc-in [:vaults :errors :funding-history-by-vault vault-address*] message)))
    state))

(defn begin-vault-order-history-load
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :loading :order-history-by-vault vault-address*] true)
        (assoc-in [:vaults :errors :order-history-by-vault vault-address*] nil))
    state))

(defn apply-vault-order-history-success
  [state vault-address rows]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :order-history-by-vault vault-address*] (rows->vec rows))
        (assoc-in [:vaults :loading :order-history-by-vault vault-address*] false)
        (assoc-in [:vaults :errors :order-history-by-vault vault-address*] nil)
        (assoc-in [:vaults :loaded-at-ms :order-history-by-vault vault-address*] (.now js/Date)))
    state))

(defn apply-vault-order-history-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (-> state
          (assoc-in [:vaults :loading :order-history-by-vault vault-address*] false)
          (assoc-in [:vaults :errors :order-history-by-vault vault-address*] message)))
    state))

(defn begin-vault-ledger-updates-load
  [state vault-address]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :loading :ledger-updates-by-vault vault-address*] true)
        (assoc-in [:vaults :errors :ledger-updates-by-vault vault-address*] nil))
    state))

(defn apply-vault-ledger-updates-success
  [state vault-address rows]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (-> state
        (assoc-in [:vaults :ledger-updates-by-vault vault-address*] (rows->vec rows))
        (assoc-in [:vaults :loading :ledger-updates-by-vault vault-address*] false)
        (assoc-in [:vaults :errors :ledger-updates-by-vault vault-address*] nil)
        (assoc-in [:vaults :loaded-at-ms :ledger-updates-by-vault vault-address*] (.now js/Date)))
    state))

(defn begin-vault-viewer-ledger-load
  [state vault-address viewer-address]
  (let [vault-address* (normalize-vault-address vault-address)
        viewer-address* (normalize-vault-address viewer-address)]
    (if (and vault-address* viewer-address*)
      (-> state
          (assoc-in [:vaults :loading :viewer-ledger-by-address vault-address* viewer-address*] true)
          (assoc-in [:vaults :errors :viewer-ledger-by-address vault-address* viewer-address*] nil))
      state)))

(defn apply-vault-viewer-ledger-success
  [state vault-address viewer-address rows]
  (let [vault-address* (normalize-vault-address vault-address)
        viewer-address* (normalize-vault-address viewer-address)]
    (if (and vault-address* viewer-address*)
      (-> state
          (assoc-in [:vaults :viewer-ledger-by-address vault-address* viewer-address*] (rows->vec rows))
          (assoc-in [:vaults :loading :viewer-ledger-by-address vault-address* viewer-address*] false)
          (assoc-in [:vaults :errors :viewer-ledger-by-address vault-address* viewer-address*] nil))
      state)))

(defn apply-vault-viewer-ledger-error
  [state vault-address viewer-address err]
  (let [vault-address* (normalize-vault-address vault-address)
        viewer-address* (normalize-vault-address viewer-address)]
    (if (and vault-address* viewer-address*)
      (let [{:keys [message]} (normalized-error err)]
        (-> state
            (assoc-in [:vaults :loading :viewer-ledger-by-address vault-address* viewer-address*] false)
            (assoc-in [:vaults :errors :viewer-ledger-by-address vault-address* viewer-address*] message)))
      state)))

(defn apply-vault-ledger-updates-error
  [state vault-address err]
  (if-let [vault-address* (normalize-vault-address vault-address)]
    (let [{:keys [message]} (normalized-error err)]
      (-> state
          (assoc-in [:vaults :loading :ledger-updates-by-vault vault-address*] false)
          (assoc-in [:vaults :errors :ledger-updates-by-vault vault-address*] message)))
    state))
