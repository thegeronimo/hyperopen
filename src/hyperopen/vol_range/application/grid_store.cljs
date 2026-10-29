(ns hyperopen.vol-range.application.grid-store
  "Pure, monotonic writes of ladder (grid) copies into the app store under
  `[:vol-range :execution :grids]` (HO-SPEC-014 v2.1 sections 8.2, 9.1).

  Several flows hold copies of the same grid at once: placement, cancel,
  repair, the REST reconciliation, the journal sync and (later) WebSocket
  evidence applied in memory. A flow that started before another one changed
  the store copy must never erase that change when it finishes, so every
  writer merges through `lifecycle-recovery/merge-grids` (membership,
  operations, fills and releases are unions; a leg keeps its most advanced
  evidence) instead of replacing the copy wholesale.

  WebSocket evidence lives only in the store copy. When it closes a ladder
  (its last resting leg filled), the journal copy is still open, and every
  background REST pass skips closed store copies, so the grid id is kept in
  `[:vol-range :execution :unpersisted-closes]` until a REST reconciliation
  persists a closed copy (`merge-stream-grid`, `reconcile-targets`,
  `note-persisted`). Nothing here reads a clock or performs I/O."
  (:require [clojure.string :as str]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-recovery :as recovery]))

(def ^:private grids-path [:vol-range :execution :grids])
(def ^:private unpersisted-path [:vol-range :execution :unpersisted-closes])

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
    (cond-> (assoc-in state grids-path grids)
      (seq (get-in state unpersisted-path))
      (update-in unpersisted-path #(into #{} (filter (partial contains? grids)) %)))))

;; ---------------------------------------------------------------------------
;; Ladders closed by stream evidence only
;; ---------------------------------------------------------------------------

(defn merge-stream-grid
  "`merge-grid-into-state` for a copy changed by WebSocket evidence. When the
  merge closes a store copy that was open, its grid id is remembered in
  `:unpersisted-closes`: the journal copy is still open, so the ladder must
  still get a REST reconciliation that persists it."
  [state grid]
  (let [gid (:grid-id grid)
        before (get-in state (conj grids-path gid))
        state* (merge-grid-into-state state grid)]
    (if (and before
             (not (lifecycle/closed? before))
             (lifecycle/closed? (get-in state* (conj grids-path gid))))
      (update-in state* unpersisted-path (fnil conj #{}) gid)
      state*)))

(defn unpersisted-closes
  "Ids of store ladders that stream evidence closed and no REST pass has
  persisted closed yet."
  [state]
  (let [grids (get-in state grids-path)]
    (into #{} (filter (partial contains? grids)) (get-in state unpersisted-path))))

(defn reconcile-target?
  "True when the background REST reconciliation must still run for `grid`:
  it is not closed, or stream evidence closed it only in the store."
  [state grid]
  (or (not (lifecycle/closed? grid))
      (contains? (unpersisted-closes state) (:grid-id grid))))

(defn reconcile-targets
  "The store ladders the background REST reconciliation runs for."
  [state]
  (filterv (partial reconcile-target? state) (vals (get-in state grids-path))))

(defn note-persisted
  "Forget an unpersisted close once a reconciliation `result` persisted a
  closed copy of its grid; any other result leaves `state` unchanged."
  [state {:keys [persisted? grid]}]
  (if (and persisted? grid (lifecycle/closed? grid)
           (contains? (get-in state unpersisted-path) (:grid-id grid)))
    (update-in state unpersisted-path disj (:grid-id grid))
    state))
