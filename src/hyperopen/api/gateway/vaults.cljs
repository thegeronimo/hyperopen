(ns hyperopen.api.gateway.vaults
  (:require [hyperopen.api.endpoints.vaults :as vault-endpoints]))

(defn request-vault-index!
  ([deps]
   (request-vault-index! deps {}))
  ([{:keys [fetch-fn vault-index-url]} opts]
   (vault-endpoints/request-vault-index! (or fetch-fn js/fetch)
                                         (or vault-index-url
                                             vault-endpoints/default-vault-index-url)
                                         opts)))

(defn request-vault-summaries!
  [{:keys [post-info!]}
   opts]
  (vault-endpoints/request-vault-summaries! post-info! opts))

(defn request-merged-vault-index!
  ([deps]
   (request-merged-vault-index! deps {}))
  ([deps opts]
   (let [request-index! (or (:request-vault-index! deps)
                            (fn [request-opts]
                              (request-vault-index! deps request-opts)))
         request-summaries! (or (:request-vault-summaries! deps)
                                (fn [request-opts]
                                  (request-vault-summaries! deps request-opts)))]
     (-> (js/Promise.all #js [(request-index! opts)
                              (request-summaries! opts)])
         (.then (fn [results]
                  (let [[index-rows summary-rows] (array-seq results)]
                    (vault-endpoints/merge-vault-index-with-summaries index-rows
                                                                      summary-rows))))))))

(defn request-user-vault-equities!
  [{:keys [post-info!]}
   address
   opts]
  (vault-endpoints/request-user-vault-equities! post-info! address opts))

(defn request-vault-details!
  [{:keys [post-info!]}
   vault-address
   opts]
  (vault-endpoints/request-vault-details! post-info! vault-address opts))

(defn request-vault-webdata2!
  [{:keys [post-info!]}
   vault-address
   opts]
  (vault-endpoints/request-vault-webdata2! post-info! vault-address opts))
