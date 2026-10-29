(ns hyperopen.vol-range.application.replace-state
  "Read-only predicates over an in-progress ladder update (HO-SPEC-014 v2.1
  section 10.2), kept at `[:vol-range :replace]`:

      {:grid-id :scope-key :phase :dialog-open? :started-at-ms
       :settings-recorded? :frozen-cloids :filled-at-freeze :basis
       :include-rejected #{logical-id} :refreshed {:requested-at-ms :account}
       :confirmed-at-ms}

  `:phase` is one of :preview, :canceling, :blocked, :canceled or :ready.
  The state lives in memory only: a reload abandons the update and nothing is
  placed.

  A0-scan safe: this namespace reads plain maps (the grid's `:legs` map
  directly) and requires nothing from the execution foundations, so the A0
  preview's actions and planner state may call it."
  (:require [hyperopen.vol-range.application.draft-scope :as draft-scope]))

(def ^:private replace-path [:vol-range :replace])

(def review-blocked-phases
  "Phases in which the ticket's ordinary review must not open: the old
  ladder is still being previewed, canceled or confirmed."
  #{:preview :canceling :blocked :canceled})

(defn update-state [state] (get-in state replace-path))

(defn phase [state] (:phase (update-state state)))

(defn- same-scope? [state]
  (let [scope-key (:scope-key (update-state state))]
    (boolean (and scope-key (= scope-key (draft-scope/current-scope-key state))))))

(defn current?
  "True when an update exists for the ticket's current market and account and
  its ladder is still in the store. A stale update only offers Stop updating."
  [state]
  (let [{:keys [grid-id]} (update-state state)]
    (boolean (and grid-id
                  (same-scope? state)
                  (map? (get-in state [:vol-range :execution :grids grid-id]))))))

(defn review-blocked?
  "True while an update for the CURRENT scope is before its replacement
  review (`review-blocked-phases`). An update left behind on another market
  or account does not block that scope's ticket."
  [state]
  (boolean (and (contains? review-blocked-phases (phase state))
                (same-scope? state))))

(defn frozen-lineage
  "The `:predecessor` map a replacement review freezes and the new grid
  records, or nil unless the update is current, `:ready` and its basis is
  ready: {:grid-id :frozen-cloids :remaining :filled-during-update
  :included-rejected [logical-id ...]}."
  [state]
  (let [{:keys [grid-id frozen-cloids basis include-rejected] :as r} (update-state state)]
    (when (and (= :ready (:phase r))
               (current? state)
               (:ready? basis)
               (seq frozen-cloids))
      {:grid-id grid-id
       :frozen-cloids (vec frozen-cloids)
       :remaining (:remaining basis)
       :filled-during-update (:filled-during-update basis)
       :included-rejected (vec (sort (or include-rejected #{})))})))

(defn- old-oids
  "Order ids of every leg the old ladder ever had, as strings."
  [state]
  (let [grid (get-in state [:vol-range :execution :grids (:grid-id (update-state state))])]
    (into #{} (keep #(some-> (:oid %) str)) (vals (:legs grid)))))

(defn account-for-plan
  "The account snapshot the planner should use for the current scope. In
  `:preview` the old ladder's own open orders are left out of existing
  exposure (they will be canceled first); in `:canceled` and `:ready` the
  account read after the cancel was confirmed replaces the snapshot, so no
  replacement is planned from pre-fill inventory. Otherwise, and for any
  other scope, `account` is returned unchanged."
  [state account]
  (if-not (current? state)
    account
    (case (phase state)
      :preview (let [oids (old-oids state)]
                 (cond-> account
                   (sequential? (:open-orders account))
                   (update :open-orders #(vec (remove (fn [o] (contains? oids (str (:oid o)))) %)))))
      (:canceled :ready) (or (get-in (update-state state) [:refreshed :account]) account)
      account)))
