(ns hyperopen.vaults.infrastructure.persistence
  (:require [hyperopen.platform :as platform]
            [hyperopen.vaults.domain.ui-state :as ui-state]))

(def ^:private vaults-snapshot-range-storage-key
  "vaults-snapshot-range")

(defn read-vaults-snapshot-range
  []
  (ui-state/normalize-vault-snapshot-range
   (platform/local-storage-get vaults-snapshot-range-storage-key)))

(defn snapshot-range-save-effect
  [snapshot-range]
  [:effects/local-storage-set
   vaults-snapshot-range-storage-key
   (name snapshot-range)])

(defn restore-vaults-snapshot-range!
  [store]
  (swap! store assoc-in [:vaults-ui :snapshot-range] (read-vaults-snapshot-range)))
