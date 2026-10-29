(ns hyperopen.views.trade.vol-range.dock
  "The \"Vol ladders\" tab of the trade account dock (spec 8.5, 11.2). Renders
  the `dock-model/dock` projection and dispatches only already-registered
  ladder actions. Counts and status always appear together, an attention state
  is never color-only, and fills are labeled as execution detail, not P&L.
  The live monitoring line (spec 9.3, 11.2) is one line at the top of the
  tab, not a live region: outcome announcements stay in the status message
  (spec 11.4)."
  (:require [clojure.string :as str]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.dock-model :as dock-model]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.views.account-info.tab-registry :as tab-registry]
            [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.trade.vol-range.repair-dialog :as repair-dialog]))

(def tab-id :vol-ladders)

(def ^:private button-classes
  ["min-h-[24px]" "rounded-md" "border" "border-ho-border" "px-2.5" "py-1" "text-xs" "text-ho-text"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"
   "disabled:cursor-not-allowed" "disabled:opacity-50"])

(defn short-address
  "0x1234…abcd for a long address; shorter values pass through."
  [address]
  (let [a (some-> address str)]
    (if (and a (> (count a) 12))
      (str (subs a 0 6) "…" (subs a (- (count a) 4)))
      a)))

(defn- side-class [side] (if (= :buy side) "text-ho-buy" "text-ho-sell"))
(defn- side-text [side] (if (= :buy side) "Buy" "Sell"))
(defn- dash [x] (if (some? x) x "—"))
(defn- count-label [n word] (str n " " word))

(defn- created-text [ms]
  (when (number? ms)
    (str (display/utc-date ms) " " (display/utc-clock ms))))

(defn- status-badge
  [{:keys [status status-label attention? recovered?]}]
  [:span {:class (into ["inline-flex" "items-center" "gap-1" "rounded" "border" "px-1.5" "py-0.5"
                        "text-xs" "font-medium"]
                       (if attention? ["border-ho-warn" "text-ho-warn"] ["border-ho-border" "text-ho-text"]))
          :data-role "vol-ladder-status"
          :data-status (some-> status name)
          :data-recovered (when recovered? "true")}
   (when attention? [:span {:aria-hidden true} "⚠"])
   [:span status-label]])

(defn- counts-line
  [{:keys [live filled rejected checking canceled]}]
  [:p {:class ["text-xs" "text-ho-text-secondary" "num"] :data-role "vol-ladder-counts"}
   (str/join " · " [(count-label live "live") (count-label filled "filled")
                    (count-label rejected "rejected") (count-label checking "checking")
                    (count-label canceled "canceled")])])

(defn- units-line [{:keys [live-buy-units live-sell-units]}]
  [:p {:class ["text-xs" "text-ho-text-secondary" "num"] :data-role "vol-ladder-units"}
   [:span {:class [(side-class :buy)]} (str "Live buy units " (dash live-buy-units))]
   " · "
   [:span {:class [(side-class :sell)]} (str "Live sell units " (dash live-sell-units))]])

(defn- fees-text [fees]
  (if (seq fees)
    (str/join ", " (map (fn [[token amount]] (str amount " " token)) fees))
    "none"))

(defn- filled-block [{:keys [filled]}]
  [:div {:class ["flex" "flex-col" "gap-0.5"] :data-role "vol-ladder-filled"}
   [:p {:class ["text-xs" "font-medium" "text-ho-text-secondary"]} "Execution detail (not P&L)"]
   (for [side [:buy :sell]
         :let [{:keys [qty vwap fees]} (get filled side)]]
     ^{:key side}
     [:p {:class ["text-xs" "text-ho-text-secondary" "num"] :data-role (str "vol-ladder-filled-" (name side))}
      [:span {:class [(side-class side)]} (str (side-text side) " filled ")]
      (str (dash qty) " · VWAP " (dash vwap) " · fees " (fees-text fees))])])

(defn- model-block [{:keys [model created-at-ms]}]
  (let [{:keys [anchor volatility tilt]} model]
    [:p {:class ["text-xs" "text-ho-text-muted" "num"] :data-role "vol-ladder-model"}
     (if model
       (str "At deployment: anchor " (dash (display/exact (:price anchor)))
            (when-let [m (:mode anchor)] (str " (" (name m) ")"))
            " · daily volatility " (dash (display/percent (:daily-fraction volatility)))
            " · tilt " (if (:enabled? tilt) "on" "off"))
       "Original settings unavailable")
     (when-let [t (created-text created-at-ms)] (str " · created " t))]))

(defn- legs-table [legs]
  [:div {:class ["overflow-x-auto"] :data-role "vol-ladder-detail"}
   [:table {:class ["w-full" "text-xs" "num"]}
    [:thead
     [:tr {:class ["text-ho-text-secondary"]}
      (for [[idx [label right?]] (map-indexed vector [["Side"] ["Price" true] ["Size" true] ["Filled" true]
                                                      ["Remaining" true] ["Status"] ["Reason"]])]
        ^{:key idx}
        [:th {:scope "col" :class ["py-1" "font-medium" (if right? "text-right" "text-left")]} label])]]
    [:tbody
     (for [{:keys [cloid side price size filled remaining status-label reason historical?]} legs]
       ^{:key cloid}
       [:tr {:class (into ["border-t" "border-ho-border"] (when historical? ["opacity-60"]))
             :data-role "vol-ladder-leg"
             :data-historical (when historical? "true")}
        [:td {:class ["py-1" (side-class side)]} (side-text side)]
        [:td {:class ["py-1" "text-right" "text-ho-text"]} (dash price)]
        [:td {:class ["py-1" "text-right" "text-ho-text"]} (dash size)]
        [:td {:class ["py-1" "text-right" "text-ho-text"]} (dash filled)]
        [:td {:class ["py-1" "text-right" "text-ho-text"]} (dash remaining)]
        [:td {:class ["py-1" "text-ho-text"]}
         status-label
         (when historical? [:span {:class ["ml-1" "italic" "text-ho-text-muted"]} "earlier attempt"])]
        [:td {:class ["py-1" "text-ho-text-secondary"]} (or reason "")]])]]])

(defn- market-hint-id [grid-id] (str "vol-ladder-market-hint-" grid-id))

(defn- repair-button
  "\"Repair N rejected orders\": opens the rejected-order review. Disabled,
  and described by the market hint, while the ticket is on another market."
  [grid-id {:keys [enabled? busy? label aria-label hint]}]
  [:button {:type "button" :class button-classes :disabled (not enabled?)
            :aria-busy (boolean busy?)
            :aria-label aria-label
            :aria-describedby (when hint (market-hint-id grid-id))
            :data-role "vol-ladder-repair"
            :data-grid-id grid-id
            :on {:click (commands/open-repair-actions grid-id)}}
   label])

(defn- update-button
  "\"Preview update\": loads the ladder into the ticket and opens the update
  dialog. Disabled, and described by the market hint, while the ticket is on
  another market."
  [grid-id {:keys [enabled? label aria-label hint]}]
  [:button {:type "button" :class button-classes :disabled (not enabled?)
            :aria-label aria-label
            :aria-describedby (when hint (market-hint-id grid-id))
            :data-role "vol-ladder-preview-update"
            :data-grid-id grid-id
            :on {:click (commands/preview-update-actions grid-id)}}
   label])

(defn- row-actions
  [{:keys [grid-id market can-cancel? canceling? refreshing? selected? counts repair] update-control :update}]
  [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
   [:button {:type "button" :class button-classes :aria-expanded (boolean selected?)
             :data-role "vol-ladder-view-orders"
             :on {:click (commands/select-ladder-actions grid-id)}}
    (if selected? "Hide orders" "View orders")]
   [:button {:type "button" :class button-classes :disabled (boolean refreshing?)
             :aria-busy (boolean refreshing?)
             :aria-label (str "Refresh " market " ladder status")
             :data-role "vol-ladder-refresh"
             :on {:click (commands/refresh-ladder-actions grid-id)}}
    "Refresh status"]
   (when can-cancel?
     [:button {:type "button" :class button-classes :disabled (boolean canceling?)
               :aria-busy (boolean canceling?)
               :aria-label (str "Cancel " market " ladder (" (count-label (:live counts) "live order")
                                (when (not= 1 (:live counts)) "s") ")")
               :data-role "vol-ladder-cancel"
               :on {:click (commands/cancel-ladder-actions grid-id)}}
      "Cancel this ladder"])
   (when (:visible? repair) (repair-button grid-id repair))
   (when (:visible? update-control) (update-button grid-id update-control))
   (when-let [hint (or (when (:visible? repair) (:hint repair))
                       (when (:visible? update-control) (:hint update-control)))]
     [:span {:id (market-hint-id grid-id) :class ["text-xs" "text-ho-text-secondary"]
             :data-role "vol-ladder-market-hint"}
      hint])])

(defn- ladder-row
  [{:keys [grid-id market account counts partial-message selected? legs] :as row}]
  [:article {:class ["flex" "flex-col" "gap-1.5" "border-b" "border-ho-border" "p-3"]
             :data-role "vol-ladder-row"
             :data-grid-id grid-id}
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-3" "gap-y-1"]}
    [:span {:class ["text-sm" "font-semibold" "text-ho-text"] :data-role "vol-ladder-market"} market]
    [:span {:class ["text-xs" "text-ho-text-secondary" "num"] :title account :data-role "vol-ladder-account"}
     (short-address account)]
    (status-badge row)]
   (counts-line counts)
   (units-line counts)
   (when partial-message
     [:p {:class ["text-xs" "text-ho-warn"] :data-role "vol-ladder-partial"} partial-message])
   (filled-block row)
   (model-block row)
   (when-let [lineage (:lineage row)]
     [:p {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-ladder-lineage"} lineage])
   (row-actions row)
   (when selected? (legs-table legs))])

(defn- monitoring-line
  "Whether live order and fill updates are on, paused or catching up. The ⚠
  cue makes the stale state non-color-only."
  [{:keys [status reason text attention?]}]
  [:p {:class (into ["flex" "items-center" "gap-1" "px-3" "pt-2" "text-xs"]
                    (if attention? ["text-ho-warn"] ["text-ho-text-muted"]))
       :data-role "vol-ladders-monitoring"
       :data-status (name status)
       :data-reason (some-> reason name)}
   (when attention? [:span {:aria-hidden true} "⚠"])
   [:span text]])

(defn dock-panel
  "The tab body for a `dock-model/dock` model. The rejected-order review
  dialog mounts here; its model is frozen state only."
  [{:keys [rows message monitoring] dialog :repair-dialog}]
  [:div {:class ["flex" "h-full" "min-h-0" "flex-col" "overflow-y-auto" "bg-base-100"]
         :data-role "vol-ladder-dock"}
   (when monitoring (monitoring-line monitoring))
   [:div {:class ["px-3" "py-1" "text-xs" "text-ho-text-secondary"]
          :role "status"
          :aria-live "polite"
          :data-role "vol-ladder-status-message"}
    message]
   (if (seq rows)
     (for [row rows] ^{:key (:grid-id row)} (ladder-row row))
     [:p {:class ["p-3" "text-sm" "text-ho-text-secondary"] :data-role "vol-ladder-empty"}
      "No Vol Range ladders yet."])
   (repair-dialog/repair-dialog dialog)])

;; ---------------------------------------------------------------------------
;; Injection into the account-info panel (trade route)
;; ---------------------------------------------------------------------------

(defn view-state
  "The slice the trade account panel needs to build the tab: the execution
  root, the open repair review, the update in progress and the ticket's
  scope key (only computed while a ladder exists), so the dock repaints when
  any of them changes."
  [state]
  (let [execution (get-in state [:vol-range :execution])]
    {:vol-range {:execution execution
                 :repair (get-in state [:vol-range :repair])
                 :replace (get-in state [:vol-range :replace])}
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
