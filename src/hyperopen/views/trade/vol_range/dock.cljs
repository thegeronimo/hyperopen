(ns hyperopen.views.trade.vol-range.dock
  "The \"Vol ladders\" tab of the trade account dock (spec 8.5, 11.2). Renders
  the `dock-model/dock` projection and dispatches only already-registered
  ladder actions. Active ladders are cards (header, attention callout, a
  four-cell summary, then orders on demand); closed ladders are one aligned
  table below them. Counts and status always appear together, an attention
  state is never color-only, and fills are labeled as execution detail, not
  P&L. The live monitoring line (spec 9.3, 11.2) is one line at the top of
  the tab, not a live region: outcome announcements stay in the status
  message (spec 11.4)."
  (:require [hyperopen.vol-range.application.dock-model :as dock-model]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.views.account-info.tab-registry :as tab-registry]
            [hyperopen.views.trade.vol-range.ladders :as ladders]
            [hyperopen.views.trade.vol-range.repair-dialog :as repair-dialog]))

(def tab-id :vol-ladders)

;; ---------------------------------------------------------------------------
;; Panel
;; ---------------------------------------------------------------------------

(defn- section-heading [id label n]
  [:h2 {:id id :class ["mb-2" "text-xs" "font-semibold" "uppercase" "tracking-wider" "text-ho-text-muted"]}
   (str label " · " n)])

(defn- monitoring-line
  "Whether live order and fill updates are on, paused or catching up. The ⚠
  cue makes the stale state non-color-only."
  [{:keys [status reason text attention?]}]
  [:p {:class (into ["flex" "items-center" "gap-1.5" "text-xs"]
                    (if attention? ["text-ho-warn"] ["text-ho-text-muted"]))
       :data-role "vol-ladders-monitoring"
       :data-status (name status)
       :data-reason (some-> reason name)}
   (if attention?
     [:span {:aria-hidden true} "⚠"]
     (when (= :live status) [:span {:aria-hidden true :class ["h-2" "w-2" "rounded-full" "bg-ho-buy"]}]))
   [:span text]])

(defn dock-panel
  "The tab body for a `dock-model/dock` model. The rejected-order review
  dialog mounts here; its model is frozen state only."
  [{:keys [rows active-rows closed-rows message monitoring] dialog :repair-dialog}]
  [:div {:class ["flex" "h-full" "min-h-0" "flex-col" "overflow-y-auto" "bg-base-100"]
         :data-role "vol-ladder-dock"}
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-3" "gap-y-1" "px-3" "py-2"]}
    (when monitoring (monitoring-line monitoring))
    [:div {:class ["text-xs" "text-ho-text"]
           :role "status"
           :aria-live "polite"
           :data-role "vol-ladder-status-message"}
     message]
    (when (seq rows)
      [:span {:class ["ml-auto" "text-xs" "text-ho-text-muted"]}
       (str (count active-rows) " active · " (count closed-rows) " closed")])]
   (if (seq rows)
     [:div {:class ["flex" "flex-col" "gap-5" "px-3" "pb-4"]}
      [:section {:aria-labelledby "vol-ladders-active-heading"}
       (section-heading "vol-ladders-active-heading" "Active" (count active-rows))
       (if (seq active-rows)
         [:div {:class ["flex" "flex-col" "gap-3"]}
          (for [row active-rows] ^{:key (:grid-id row)} (ladders/ladder-card row))]
         [:p {:class ["text-xs" "text-ho-text-secondary"]} "No active ladders."])]
      (when (seq closed-rows)
        [:section {:aria-labelledby "vol-ladders-closed-heading"}
         (section-heading "vol-ladders-closed-heading" "Closed" (count closed-rows))
         (ladders/closed-table closed-rows)])]
     [:p {:class ["p-3" "text-sm" "text-ho-text-secondary"] :data-role "vol-ladder-empty"}
      "No Vol Range ladders yet."])
   (repair-dialog/repair-dialog dialog)])

;; ---------------------------------------------------------------------------
;; Injection into the account-info panel (trade route)
;; ---------------------------------------------------------------------------

(defn view-state
  "The slice the trade account panel needs to build the tab: the execution
  root, the open repair review, the update in progress, the tracking
  campaigns and their closures, and the ticket's scope key (only computed
  while a ladder exists), so the dock repaints when any of them changes."
  [state]
  (let [execution (get-in state [:vol-range :execution])]
    {:vol-range {:execution execution
                 :repair (get-in state [:vol-range :repair])
                 :replace (get-in state [:vol-range :replace])
                 :campaigns (get-in state [:vol-range :campaigns])
                 :campaign-closures (get-in state [:vol-range :campaign-closures])}
     :vol-range-dock {:current-scope-key (when (seq (:grids execution))
                                           (draft-scope/current-scope-key state))}}))

(def ^:private background-ops
  "Busy keys of background operations; they flip on every watcher tick and
  nothing in the ticket reads them."
  #{:sync :reconcile-open :resync})

(defn- order-form-execution [execution]
  (-> execution
      (select-keys [:enabled? :max-ladder-usdc :busy :last])
      (update :busy #(into {} (remove (fn [[k _]] (contains? background-ops (first k)))) %))
      (assoc :monitoring (some-> (:monitoring execution) (dissoc :last-tick-ms)))))

(defn- with-update-grid
  "The ladder under update, which the update dialog's Current column, the
  ticket banner and the review lineage read (and `replace-state/current?`
  requires)."
  [narrowed execution grid-id]
  (if-let [g (when grid-id (get-in execution [:grids grid-id]))]
    (assoc narrowed :grids {grid-id g})
    narrowed))

(defn order-form-slice
  "The order-form panel's `:vol-range` slice: everything under `:vol-range`
  (review, frozen context, ui, repair, replace) except the execution root,
  which is narrowed to what the review's Place button reads, so stream fills,
  ladder copies and watcher ticks never repaint the ticket. Monitoring keeps
  its status but not `:last-tick-ms`. While a ladder update is in progress,
  or a review is frozen with an update's lineage, that one ladder also
  passes through (its fills then repaint the ticket; other ladders' do not)."
  [state]
  (if-let [vol-range (:vol-range state)]
    {:vol-range (cond-> vol-range
                  (contains? vol-range :execution)
                  (update :execution
                          (fn [execution]
                            (with-update-grid (order-form-execution execution) execution
                              (or (get-in vol-range [:replace :grid-id])
                                  (get-in vol-range [:frozen-context :replace :predecessor :grid-id]))))))}
    {}))

(defn tab-visible?
  [state]
  (or (true? (get-in state [:vol-range :execution :enabled?]))
      (boolean (seq (get-in state [:vol-range :execution :grids])))))

(defn extra-tab
  "The extra-tab entry, or nil when placement is off and no grid exists."
  [state]
  (when (tab-visible? state)
    {:id tab-id
     :label "Vol ladders"
     :content (dock-panel (dock-model/dock state))}))

(defn with-tab
  "Adds the Vol ladders tab (after the base tabs) to account-info `opts`."
  [opts state]
  (if-let [tab (extra-tab state)]
    (-> opts
        (update :extra-tabs (fnil conj []) tab)
        (assoc :tab-order (conj (vec tab-registry/available-tabs) tab-id)))
    opts))
