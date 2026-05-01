(ns hyperopen.api.default.account
  (:require [hyperopen.api.compat :as api-compat]
            [hyperopen.api.gateway.account :as account-gateway]
            [hyperopen.domain.funding-history :as funding-history]))

(defn request-user-funding-history-data!
  [{:keys [post-info! normalize-funding-history-filters]} address opts]
  (account-gateway/request-user-funding-history-data!
   {:post-info! post-info!
    :normalize-funding-history-filters normalize-funding-history-filters
    :normalize-info-funding-rows funding-history/normalize-info-funding-rows
    :sort-funding-history-rows funding-history/sort-funding-history-rows}
   address
   opts))

(defn request-user-funding-history!
  [{:keys [request-user-funding-history-data!]} address opts]
  (account-gateway/request-user-funding-history!
   {:request-user-funding-history-data! request-user-funding-history-data!}
   address
   opts))

(defn request-extra-agents!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-extra-agents! {:post-info! post-info!} address opts))

(defn request-user-webdata2!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-user-webdata2! {:post-info! post-info!} address opts))

(defn request-staking-validator-summaries!
  [{:keys [post-info!]} opts]
  (account-gateway/request-staking-validator-summaries! {:post-info! post-info!} opts))

(defn request-staking-delegator-summary!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-staking-delegator-summary! {:post-info! post-info!} address opts))

(defn request-staking-delegations!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-staking-delegations! {:post-info! post-info!} address opts))

(defn request-staking-delegator-rewards!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-staking-delegator-rewards! {:post-info! post-info!} address opts))

(defn request-staking-delegator-history!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-staking-delegator-history! {:post-info! post-info!} address opts))

(defn fetch-spot-clearinghouse-state!
  [{:keys [log-fn request-spot-clearinghouse-state!]} store address opts]
  (api-compat/fetch-spot-clearinghouse-state!
   {:log-fn log-fn
    :request-spot-clearinghouse-state! request-spot-clearinghouse-state!}
   store
   address
   opts))

(defn request-spot-clearinghouse-state!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-spot-clearinghouse-state! {:post-info! post-info!} address opts))

(defn fetch-user-abstraction!
  [{:keys [log-fn request-user-abstraction!]} store address opts]
  (api-compat/fetch-user-abstraction!
   {:log-fn log-fn
    :request-user-abstraction! request-user-abstraction!}
   store
   address
   opts))

(defn request-user-abstraction!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-user-abstraction! {:post-info! post-info!} address opts))

(defn request-portfolio!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-portfolio! {:post-info! post-info!} address opts))

(defn request-user-fees!
  [{:keys [post-info!]} address opts]
  (account-gateway/request-user-fees! {:post-info! post-info!} address opts))

(defn request-user-non-funding-ledger-updates!
  [{:keys [post-info!]} address start-time-ms end-time-ms opts]
  (account-gateway/request-user-non-funding-ledger-updates!
   {:post-info! post-info!}
   address
   start-time-ms
   end-time-ms
   opts))

(defn request-clearinghouse-state!
  [{:keys [post-info!]} address dex opts]
  (account-gateway/request-clearinghouse-state! {:post-info! post-info!} address dex opts))

(defn fetch-clearinghouse-state!
  [{:keys [log-fn request-clearinghouse-state!]} store address dex opts]
  (api-compat/fetch-clearinghouse-state!
   {:log-fn log-fn
    :request-clearinghouse-state! request-clearinghouse-state!}
   store
   address
   dex
   opts))

(defn fetch-perp-dex-clearinghouse-states!
  [{:keys [fetch-clearinghouse-state!]} store address dex-names opts]
  (api-compat/fetch-perp-dex-clearinghouse-states!
   {:fetch-clearinghouse-state! fetch-clearinghouse-state!}
   store
   address
   dex-names
   opts))
