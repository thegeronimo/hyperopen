(ns hyperopen.api.default.vaults
  (:require [hyperopen.api.gateway.vaults :as vault-gateway]))

(defn request-vault-index!
  [opts]
  (vault-gateway/request-vault-index! {:fetch-fn js/fetch} opts))

(defn request-vault-index-response!
  [opts]
  (vault-gateway/request-vault-index-response! {:fetch-fn js/fetch} opts))

(defn request-vault-summaries!
  [{:keys [post-info!]} opts]
  (vault-gateway/request-vault-summaries! {:post-info! post-info!} opts))

(defn request-merged-vault-index!
  [{:keys [request-vault-index! request-vault-summaries!]} opts]
  (vault-gateway/request-merged-vault-index!
   {:request-vault-index! request-vault-index!
    :request-vault-summaries! request-vault-summaries!}
   opts))

(defn request-user-vault-equities!
  [{:keys [post-info!]} address opts]
  (vault-gateway/request-user-vault-equities! {:post-info! post-info!} address opts))

(defn request-vault-details!
  [{:keys [post-info!]} vault-address opts]
  (vault-gateway/request-vault-details! {:post-info! post-info!} vault-address opts))

(defn request-vault-webdata2!
  [{:keys [post-info!]} vault-address opts]
  (vault-gateway/request-vault-webdata2! {:post-info! post-info!} vault-address opts))
