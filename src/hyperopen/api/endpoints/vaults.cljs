(ns hyperopen.api.endpoints.vaults
  (:require [hyperopen.api.endpoints.vaults.common :as common]
            [hyperopen.api.endpoints.vaults.details :as details]
            [hyperopen.api.endpoints.vaults.index :as index]
            [hyperopen.api.endpoints.vaults.snapshots :as snapshots]))

(def default-vault-index-url index/default-vault-index-url)

(defn- normalize-snapshot-key
  [value]
  (snapshots/normalize-snapshot-key value))

(defn- cross-origin-browser-request?
  [url]
  (index/cross-origin-browser-request? url))

(defn- normalize-vault-snapshot-return
  [raw tvl]
  (snapshots/normalize-vault-snapshot-return raw tvl))

(defn- sample-snapshot-preview-series
  [values]
  (snapshots/sample-snapshot-preview-series values))

(defn- boolean-value
  [value]
  (common/boolean-value value))

(defn normalize-vault-snapshot-preview
  [payload tvl]
  (snapshots/normalize-vault-snapshot-preview payload tvl))

(defn normalize-vault-pnls
  [payload]
  (snapshots/normalize-vault-pnls payload))

(defn normalize-vault-relationship
  [relationship]
  (snapshots/normalize-vault-relationship relationship))

(defn normalize-vault-summary
  [payload]
  (snapshots/normalize-vault-summary payload))

(defn normalize-vault-index-row
  [row]
  (snapshots/normalize-vault-index-row row))

(defn normalize-vault-index-rows
  [payload]
  (snapshots/normalize-vault-index-rows payload))

(defn merge-vault-index-with-summaries
  [index-rows summary-rows]
  (snapshots/merge-vault-index-with-summaries index-rows summary-rows))

(defn request-vault-index-response!
  ([fetch-fn opts]
   (index/request-vault-index-response! fetch-fn opts))
  ([fetch-fn url opts]
   (index/request-vault-index-response! fetch-fn url opts cross-origin-browser-request?)))

(defn request-vault-index!
  ([fetch-fn opts]
   (index/request-vault-index! fetch-fn opts))
  ([fetch-fn url opts]
   (index/request-vault-index! fetch-fn url opts cross-origin-browser-request?)))

(defn request-vault-summaries!
  [post-info! opts]
  (index/request-vault-summaries! post-info! opts))

(defn normalize-user-vault-equity
  [row]
  (details/normalize-user-vault-equity row))

(defn normalize-user-vault-equities
  [payload]
  (details/normalize-user-vault-equities payload))

(defn request-user-vault-equities!
  [post-info! address opts]
  (details/request-user-vault-equities! post-info! address opts))

(defn- normalize-follower-state
  [payload]
  (details/normalize-follower-state payload))

(defn- followers-count
  [followers normalized-followers]
  (details/followers-count followers normalized-followers))

(defn normalize-vault-details
  [payload]
  (details/normalize-vault-details payload))

(defn request-vault-details!
  [post-info! vault-address opts]
  (details/request-vault-details! post-info! vault-address opts))

(defn request-vault-webdata2!
  [post-info! vault-address opts]
  (details/request-vault-webdata2! post-info! vault-address opts))
