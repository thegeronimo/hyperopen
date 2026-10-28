(ns hyperopen.vol-range.application.grid-store
  "Pure, monotonic writes of ladder (grid) copies into the app store under
  `[:vol-range :execution :grids]` (HO-SPEC-014 v2.1 sections 8.2, 9.1).

  Several flows hold copies of the same grid at once: placement, cancel,
  repair, the REST reconciliation, the journal sync and (later) WebSocket
  evidence applied in memory. A flow that started before another one changed
  the store copy must never erase that change when it finishes, so every
  writer merges through `lifecycle-recovery/merge-grids` (membership,
  operations, fills and releases are unions; a leg keeps its most advanced
  evidence) instead of replacing the copy wholesale. Nothing here reads a
  clock or performs I/O."
  (:require [clojure.string :as str]
            [hyperopen.vol-range.application.lifecycle-recovery :as recovery]))

(def ^:private grids-path [:vol-range :execution :grids])

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn- grid-account [grid] (lower (get-in grid [:scope :account-address])))

(defn merge-grid-into-state
  "Merge `grid` into the store copy of the same grid id (stored = the
  current store copy, ours = `grid`). A grid not yet in the store is added
  as is; a nil grid returns `state` unchanged."
  [state grid]
  (if (nil? grid)
    state
    (update-in state (conj grids-path (:grid-id grid)) #(recovery/merge-grids % grid))))

(defn merge-account-grids
  "Replace the store's `:grids` map with the journal's grids for `account`
  (compared lower-cased), each merged with the store copy of the same grid
  id when that copy belongs to the same account, so evidence only the store
  has seen survives a journal sync. Grids of other accounts and store-only
  grid ids are dropped. A nil account yields no grids."
  [state account journal-grids]
  (let [account (lower account)
        current (get-in state grids-path)
        grids (if (nil? account)
                {}
                (into {}
                      (comp (filter #(= account (grid-account %)))
                            (map (fn [journal-copy]
                                   (let [gid (:grid-id journal-copy)
                                         store-copy (get current gid)]
                                     [gid (if (and store-copy (= account (grid-account store-copy)))
                                            (recovery/merge-grids journal-copy store-copy)
                                            journal-copy)]))))
                      journal-grids))]
    (assoc-in state grids-path grids)))
