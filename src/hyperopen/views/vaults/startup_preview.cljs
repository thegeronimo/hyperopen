(ns hyperopen.views.vaults.startup-preview
  (:require [hyperopen.platform :as platform]
            [hyperopen.vaults.infrastructure.preview-cache :as vault-preview-cache]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]))

(defn- vault-list-route-active?
  [store]
  (= :list
     (:kind (vault-routes/parse-vault-route
             (get-in @store [:router :path] "")))))

(defn- live-vault-rows-present?
  [store]
  (or (seq (get-in @store [:vaults :merged-index-rows]))
      (seq (get-in @store [:vaults :index-rows]))))

(defn restore-startup-preview!
  [store]
  (when (and (vault-list-route-active? store)
             (not (live-vault-rows-present? store))
             (nil? (get-in @store [:vaults :startup-preview])))
    (when-let [preview-record (vault-preview-cache/load-vault-startup-preview-record!)]
      (let [restored-preview (vault-preview-cache/restore-vault-startup-preview
                              preview-record
                              {:snapshot-range (get-in @store [:vaults-ui :snapshot-range])
                               :wallet-address (get-in @store [:wallet :address])
                               :now-ms (platform/now-ms)})]
        (if restored-preview
          (swap! store assoc-in [:vaults :startup-preview] restored-preview)
          (vault-preview-cache/clear-vault-startup-preview!))))))

(defn persist-startup-preview-record!
  [state]
  (vault-preview-cache/persist-vault-startup-preview-record! state))
