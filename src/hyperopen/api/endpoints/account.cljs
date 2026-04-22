(ns hyperopen.api.endpoints.account
  (:require [hyperopen.api.endpoints.account.clearinghouse :as clearinghouse]
            [hyperopen.api.endpoints.account.funding-history :as funding-history]
            [hyperopen.api.endpoints.account.metadata :as metadata]
            [hyperopen.api.endpoints.account.portfolio :as portfolio]
            [hyperopen.api.endpoints.account.staking :as staking]))

(defn request-user-funding-history!
  [post-info! normalize-info-funding-rows-fn sort-funding-history-rows-fn
   address start-time-ms end-time-ms opts]
  (funding-history/request-user-funding-history! post-info!
                                                 normalize-info-funding-rows-fn
                                                 sort-funding-history-rows-fn
                                                 address
                                                 start-time-ms
                                                 end-time-ms
                                                 opts))

(defn request-spot-clearinghouse-state!
  [post-info! address opts]
  (clearinghouse/request-spot-clearinghouse-state! post-info! address opts))

(defn request-extra-agents!
  [post-info! address opts]
  (metadata/request-extra-agents! post-info! address opts))

(defn request-user-webdata2!
  [post-info! address opts]
  (metadata/request-user-webdata2! post-info! address opts))

(defn request-staking-validator-summaries!
  [post-info! opts]
  (staking/request-staking-validator-summaries! post-info! opts))

(defn request-staking-delegator-summary!
  [post-info! address opts]
  (staking/request-staking-delegator-summary! post-info! address opts))

(defn request-staking-delegations!
  [post-info! address opts]
  (staking/request-staking-delegations! post-info! address opts))

(defn request-staking-delegator-rewards!
  [post-info! address opts]
  (staking/request-staking-delegator-rewards! post-info! address opts))

(defn request-staking-delegator-history!
  [post-info! address opts]
  (staking/request-staking-delegator-history! post-info! address opts))

(defn normalize-user-abstraction-mode
  [abstraction]
  (clearinghouse/normalize-user-abstraction-mode abstraction))

(defn request-user-abstraction!
  [post-info! address opts]
  (clearinghouse/request-user-abstraction! post-info! address opts))

(defn request-clearinghouse-state!
  [post-info! address dex opts]
  (clearinghouse/request-clearinghouse-state! post-info! address dex opts))

(defn normalize-portfolio-summary
  [payload]
  (portfolio/normalize-portfolio-summary payload))

(defn request-portfolio!
  [post-info! address opts]
  (portfolio/request-portfolio! post-info! address opts))

(defn request-user-fees!
  [post-info! address opts]
  (portfolio/request-user-fees! post-info! address opts))

(defn request-user-non-funding-ledger-updates!
  [post-info! address start-time-ms end-time-ms opts]
  (portfolio/request-user-non-funding-ledger-updates! post-info!
                                                      address
                                                      start-time-ms
                                                      end-time-ms
                                                      opts))
