(ns hyperopen.funding.effects.hyperunit-runtime
  (:require [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.application.hyperunit-query :as hyperunit-query]
            [hyperopen.funding.application.lifecycle-guards :as lifecycle-guards]
            [hyperopen.funding.application.lifecycle-polling :as lifecycle-polling]
            [hyperopen.funding.domain.lifecycle :as funding-lifecycle]
            [hyperopen.funding.domain.lifecycle-operations :as lifecycle-ops]
            [hyperopen.funding.effects.common :as common]
            [hyperopen.funding.infrastructure.hyperunit-client :as hyperunit-client]))

(def request-hyperunit-operations!
  hyperunit-client/request-hyperunit-operations!)

(def request-hyperunit-estimate-fees!
  hyperunit-client/request-hyperunit-estimate-fees!)

(def request-hyperunit-withdrawal-queue!
  hyperunit-client/request-hyperunit-withdrawal-queue!)

(defonce hyperunit-lifecycle-poll-tokens
  (atom {}))

(def lifecycle-poll-key
  lifecycle-guards/lifecycle-poll-key)

(def modal-active-for-lifecycle?
  lifecycle-guards/modal-active-for-lifecycle?)

(def modal-active-for-fee-estimate?
  lifecycle-guards/modal-active-for-fee-estimate?)

(def op-sort-ms
  lifecycle-ops/op-sort-ms)

(def select-operation
  lifecycle-ops/select-operation)

(defn install-lifecycle-poll-token!
  [poll-key token]
  (lifecycle-guards/install-lifecycle-poll-token!
   hyperunit-lifecycle-poll-tokens
   poll-key
   token))

(defn clear-lifecycle-poll-token!
  [poll-key token]
  (lifecycle-guards/clear-lifecycle-poll-token!
   hyperunit-lifecycle-poll-tokens
   poll-key
   token))

(defn lifecycle-poll-token-active?
  [poll-key token]
  (lifecycle-guards/lifecycle-poll-token-active?
   hyperunit-lifecycle-poll-tokens
   poll-key
   token))

(defn modal-active-for-withdraw-queue?
  ([store]
   (lifecycle-guards/modal-active-for-withdraw-queue? store))
  ([store expected-asset-key]
   (lifecycle-guards/modal-active-for-withdraw-queue? store expected-asset-key)))

(defn select-existing-hyperunit-deposit-address
  [operations-response source-chain asset destination-address]
  (hyperunit-query/select-existing-hyperunit-deposit-address
   {:canonical-chain-token common/canonical-chain-token
    :canonical-token common/canonical-token
    :same-chain-token? common/same-chain-token?
    :op-sort-ms-fn op-sort-ms
    :non-blank-text common/non-blank-text
    :protocol-address-matches-source-chain? common/protocol-address-matches-source-chain?
    :known-source-chain-tokens common/known-source-chain-tokens}
   operations-response
   source-chain
   asset
   destination-address))

(defn request-existing-hyperunit-deposit-address!
  [base-url base-urls destination-address source-chain asset]
  (hyperunit-query/request-existing-hyperunit-deposit-address!
   {:request-hyperunit-operations! request-hyperunit-operations!
    :select-existing-hyperunit-deposit-address select-existing-hyperunit-deposit-address}
   base-url
   base-urls
   destination-address
   source-chain
   asset))

(defn prefetch-selected-hyperunit-deposit-address!
  [store]
  (hyperunit-query/prefetch-selected-hyperunit-deposit-address!
   {:funding-modal-view-model-fn funding-actions/funding-modal-view-model
    :normalize-asset-key common/normalize-asset-key
    :non-blank-text common/non-blank-text
    :canonical-chain-token common/canonical-chain-token
    :normalize-address common/normalize-address
    :resolve-hyperunit-base-urls common/resolve-hyperunit-base-urls
    :request-existing-hyperunit-deposit-address! request-existing-hyperunit-deposit-address!}
   store))

(defn lifecycle-next-delay-ms
  [now-ms lifecycle]
  (lifecycle-ops/lifecycle-next-delay-ms
   {:default-delay-ms common/hyperunit-operations-poll-default-delay-ms
    :min-delay-ms common/hyperunit-operations-poll-min-delay-ms
    :max-delay-ms common/hyperunit-operations-poll-max-delay-ms}
   now-ms
   lifecycle))

(defn operation->lifecycle
  [operation direction asset-key now-ms]
  (lifecycle-ops/operation->lifecycle
   funding-lifecycle/normalize-hyperunit-lifecycle
   operation
   direction
   asset-key
   now-ms))

(defn awaiting-lifecycle
  [direction asset-key now-ms]
  (lifecycle-ops/awaiting-lifecycle
   funding-lifecycle/normalize-hyperunit-lifecycle
   direction
   asset-key
   now-ms))

(defn awaiting-deposit-lifecycle
  [asset-key now-ms]
  (lifecycle-ops/awaiting-deposit-lifecycle
   funding-lifecycle/normalize-hyperunit-lifecycle
   asset-key
   now-ms))

(defn awaiting-withdraw-lifecycle
  [asset-key now-ms]
  (lifecycle-ops/awaiting-withdraw-lifecycle
   funding-lifecycle/normalize-hyperunit-lifecycle
   asset-key
   now-ms))

(defn fetch-hyperunit-withdrawal-queue!
  [opts]
  (hyperunit-query/fetch-hyperunit-withdrawal-queue!
   {:modal-active-for-withdraw-queue? modal-active-for-withdraw-queue?
    :normalize-hyperunit-withdrawal-queue funding-lifecycle/normalize-hyperunit-withdrawal-queue
    :resolve-hyperunit-base-urls common/resolve-hyperunit-base-urls
    :non-blank-text common/non-blank-text
    :fallback-runtime-error-message common/fallback-runtime-error-message}
   opts))

(defn- lifecycle-polling-defaults
  []
  {:lifecycle-poll-key-fn lifecycle-poll-key
   :install-lifecycle-poll-token! install-lifecycle-poll-token!
   :clear-lifecycle-poll-token! clear-lifecycle-poll-token!
   :lifecycle-poll-token-active? lifecycle-poll-token-active?
   :modal-active-for-lifecycle? modal-active-for-lifecycle?
   :normalize-hyperunit-lifecycle funding-lifecycle/normalize-hyperunit-lifecycle
   :select-operation select-operation
   :operation->lifecycle operation->lifecycle
   :awaiting-lifecycle awaiting-lifecycle
   :lifecycle-next-delay-ms lifecycle-next-delay-ms
   :hyperunit-lifecycle-terminal? funding-lifecycle/hyperunit-lifecycle-terminal?
   :fetch-hyperunit-withdrawal-queue! fetch-hyperunit-withdrawal-queue!
   :non-blank-text common/non-blank-text
   :default-poll-delay-ms common/hyperunit-operations-poll-default-delay-ms
   :runtime-error-message common/fallback-runtime-error-message})

(defn start-hyperunit-lifecycle-polling!
  [opts]
  (lifecycle-polling/start-hyperunit-lifecycle-polling!
   (merge (lifecycle-polling-defaults) opts)))

(defn start-hyperunit-deposit-lifecycle-polling!
  [opts]
  (lifecycle-polling/start-hyperunit-deposit-lifecycle-polling!
   (merge (lifecycle-polling-defaults) opts)))

(defn start-hyperunit-withdraw-lifecycle-polling!
  [opts]
  (lifecycle-polling/start-hyperunit-withdraw-lifecycle-polling!
   (merge (lifecycle-polling-defaults) opts)))
