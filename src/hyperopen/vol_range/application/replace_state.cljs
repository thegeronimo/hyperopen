(ns hyperopen.vol-range.application.replace-state
  "Read-only predicates over an in-progress ladder update (HO-SPEC-014 v2.1
  section 10.2), kept at `[:vol-range :replace]`:

      {:grid-id :scope-key :phase :dialog-open? :started-at-ms
       :settings-recorded? :frozen-cloids :filled-at-freeze :basis
       :include-rejected #{logical-id}
       :refreshed {:requested-at-ms :account :store-balances}
       :confirmed-at-ms}

  `:phase` is one of :preview, :canceling, :blocked, :canceled or :ready.
  The state lives in memory only: a reload abandons the update and nothing is
  placed.

  A0-scan safe: this namespace reads plain maps (the grid's `:legs` map
  directly) and requires nothing from the execution foundations, so the A0
  preview's actions and planner state may call it."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]))

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

(defn- d [x] (some-> x str precision/parse-decimal))

(defn- release-hold
  "`balance` ({:total :hold :available}) with `amount` more available and
  less held, never above its total."
  [balance amount]
  (let [total (d (:total balance))
        available (d (:available balance))
        hold (d (:hold balance))]
    (if (and total available hold amount (precision/pos-dec? amount))
      (let [released (precision/dec-min amount hold)]
        (assoc balance
               :available (precision/decimal->string (precision/dec-min total (precision/add available released)))
               :hold (precision/decimal->string (precision/sub hold released))))
      balance)))

(defn- without-old-orders
  "`account` without the old ladder's own open orders and, with
  `release?`, with the venue holds of those orders released: a resting buy
  holds price x remaining quote, a resting sell its remaining base. The
  planner reads `:available`, so leaving the holds of orders about to be
  canceled in would count the old ladder's funds twice."
  [state account release?]
  (if-not (sequential? (:open-orders account))
    account
    (let [oids (old-oids state)
          old? (fn [o] (contains? oids (str (:oid o))))
          removed (filter old? (:open-orders account))
          held (fn [side f] (reduce (fn [acc o] (if-let [x (f o)] (precision/add acc x) acc))
                                    (precision/parse-decimal "0")
                                    (filter #(= side (:side %)) removed)))
          quote-held (held :buy #(when-let [p (d (:price %))] (some->> (d (:remaining %)) (precision/mul p))))
          base-held (held :sell #(d (:remaining %)))]
      (cond-> (assoc account :open-orders (vec (remove old? (:open-orders account))))
        (and release? (map? (:quote account))) (update :quote release-hold quote-held)
        (and release? (map? (:base account))) (update :base release-hold base-held)))))

(defn- balances [account] (select-keys account [:base :quote]))

(defn account-for-plan
  "The account snapshot the planner should use for the current scope.

  In `:preview` the old ladder's own open orders are left out of existing
  exposure and their holds are released (they will be canceled first).

  In `:canceled` and `:ready` the account read after the cancel was
  confirmed replaces the snapshot, so no replacement is planned from
  pre-fill inventory, but only while the store's own balances are still the
  ones it showed at that confirmation (`:store-balances`). Once the store's
  balances change (a transfer, another order, a later fill) that read is
  older than the store, and the live snapshot is used, without the old
  ladder's orders (already canceled, so their holds are not released
  again), so the ticket and the review never show balances older
  than the app's.

  Otherwise, and for any other scope, `account` is returned unchanged."
  [state account]
  (if-not (current? state)
    account
    (case (phase state)
      :preview (without-old-orders state account true)
      (:canceled :ready) (let [{refreshed :account seen :store-balances} (:refreshed (update-state state))]
                           (cond
                             (nil? refreshed) account
                             (and (map? seen) (not= seen (balances account))) (without-old-orders state account false)
                             :else refreshed))
      account)))

(defn store-balances
  "What a confirmation records as `:store-balances`: the base and quote
  balances of the live account snapshot."
  [account]
  (balances account))
