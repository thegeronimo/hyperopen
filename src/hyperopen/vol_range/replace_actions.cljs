(ns hyperopen.vol-range.replace-actions
  "Vol Range ladder update action handlers (HO-SPEC-014 v2.1 sections 10.2,
  11.2, 13.4; VR-15, VR-16, VR-18): preview an update in the ticket, cancel
  the old ladder, check the cancellation, choose rejected members, load the
  replacement quantities into the ticket, and stop updating.

  Pure: each handler returns effects, only `:effects/save-many` and
  `[:effects/vol-range-execute op params]` (`:replace-cancel`,
  `:replace-confirm`). Nothing here places a replacement: that happens only
  through the ordinary review and its commit click. The update lives in
  memory at `[:vol-range :replace]` (see `replace-state`). Every handler
  first checks that the update is current (same market and account, ladder
  still in the store); a stale update only offers Stop updating. A second
  click while an update operation is in flight emits nothing."
  (:require [hyperopen.order.actions :as order-actions]
            [hyperopen.trading.vol-range-transitions :as transitions]
            [hyperopen.vol-range.actions :as vol-range-actions]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.replace-flow :as replace-flow]
            [hyperopen.vol-range.application.replace-state :as replace-state]
            [hyperopen.vol-range.application.ticket-model :as ticket-model]))

(defn- now [] (.now js/Date))
(defn- busy? [state k] (true? (get-in state [:vol-range :execution :busy k])))
(defn- grid [state grid-id] (get-in state [:vol-range :execution :grids grid-id]))
(defn- update-of [state] (replace-state/update-state state))

(defn- update-busy? [state grid-id]
  (or (busy? state [:replace-cancel grid-id]) (busy? state [:replace-confirm grid-id])))

(defn- save-update [u] [[[:vol-range :replace] u]])

(defn- apply-path-values [state path-values]
  (reduce (fn [s [path value]] (assoc-in s path value)) state path-values))

(defn- save-many-path-values [effects]
  (into [] (mapcat (fn [[effect-id path-values]] (when (= :effects/save-many effect-id) path-values))) effects))

;; ---------------------------------------------------------------------------
;; Preview update (spec 10.2 step 1)
;; ---------------------------------------------------------------------------

(defn- show-ticket
  "[state path-values] with the Vol Range ticket showing: Scale order type
  and Volatility range mode, written by the same transitions their own
  actions run. One dispatch expands every action against the SAME state, so
  the preview composes them itself instead of relying on earlier actions in
  the click. The daily candles are fetched by the freshness watcher once the
  ticket shows."
  [state]
  (if (ticket-model/visible? state)
    [state []]
    (let [pv1 (save-many-path-values (order-actions/select-pro-order-type state :scale))
          s1 (apply-path-values state pv1)
          pv2 (some->> (transitions/select-scale-mode s1 :volatility-range)
                       (vol-range-actions/transition->path-values s1))
          s2 (apply-path-values s1 pv2)]
      [s2 (into pv1 pv2)])))

(defn- previewable?
  "Placement is enabled, the ladder is live or partially deployed, placed in
  this browser's scope (not recovered), not being canceled or repaired, and
  no update is already past its preview."
  [state grid-id]
  (let [g (grid state grid-id)
        u (update-of state)]
    (boolean
     (and (get-in state [:vol-range :execution :enabled?])
          g
          (not (:recovered? g))
          (not (:cancel-requested-at-ms g))
          (contains? #{:live :partially-deployed} (lifecycle/status g))
          (= (:scope-key g) (draft-scope/current-scope-key state))
          (not= grid-id (get-in state [:vol-range :repair :grid-id]))
          (not (busy? state [:repair grid-id]))
          (not (and u (replace-state/current? state) (not= :preview (:phase u))))
          (not (and u (update-busy? state (:grid-id u))))))))

(defn preview-vol-ladder-update
  "Load the deployed ladder into the ticket (its recorded draft, or the
  current one when none was recorded) with each side's quantity set to its
  live remainder, and open the update dialog."
  ([state grid-id] (preview-vol-ladder-update state grid-id (now)))
  ([state grid-id now-ms]
   (if-not (previewable? state grid-id)
     []
     (let [g (grid state grid-id)
           [shown ticket-path-values] (show-ticket state)]
       (if-not (ticket-model/visible? shown)
         []
         (let [patch (replace-flow/preview-patch (:settings g) (lifecycle/live-remaining g))
               draft-path-values (vol-range-actions/transition->path-values
                                  shown (transitions/apply-patch shown patch))]
           [[:effects/save-many
             (-> ticket-path-values
                 (into draft-path-values)
                 (into (save-update {:grid-id grid-id
                                     :scope-key (:scope-key g)
                                     :phase :preview
                                     :dialog-open? true
                                     :started-at-ms now-ms
                                     :settings-recorded? (map? (get-in g [:settings :draft]))
                                     :frozen-cloids nil
                                     :filled-at-freeze nil
                                     :basis nil
                                     :include-rejected #{}
                                     :refreshed nil
                                     :confirmed-at-ms nil})))]]))))))

;; ---------------------------------------------------------------------------
;; Dialog
;; ---------------------------------------------------------------------------

(defn open-vol-ladder-update
  [state]
  (if (replace-state/current? state)
    [[:effects/save-many [[[:vol-range :replace :dialog-open?] true]]]]
    []))

(defn close-vol-ladder-update
  "Close the dialog; the update keeps its phase."
  [state]
  (if (update-of state)
    [[:effects/save-many [[[:vol-range :replace :dialog-open?] false]]]]
    []))

(defn handle-vol-ladder-update-keydown
  "Escape closes the dialog; every other key is a no-op, so Enter never
  cancels or places anything."
  [state key]
  (if (= key "Escape")
    (close-vol-ladder-update state)
    []))

;; ---------------------------------------------------------------------------
;; Cancel and confirm (spec 10.2 steps 2-4)
;; ---------------------------------------------------------------------------

(defn- blocking [u] (set (get-in u [:basis :blocking])))

(defn cancel-vol-ladder-for-update
  "Cancel the old ladder (its membership is frozen by the operation), or,
  while blocked by old orders that are still open, cancel them again."
  [state]
  (let [{:keys [grid-id phase] :as u} (update-of state)]
    (if (and (replace-state/current? state)
             (or (= :preview phase)
                 (and (= :blocked phase) (contains? (blocking u) :replace/legs-still-open)))
             (not (update-busy? state grid-id))
             (not (busy? state [:cancel grid-id])))
      [[:effects/save-many (save-update (assoc u :phase :canceling))]
       [:effects/vol-range-execute :replace-cancel {:grid-id grid-id}]]
      [])))

(defn check-vol-ladder-update
  "Check the canceled ladder again (Check again), or refresh balances once
  the replacement quantities are in the ticket (Refresh balances)."
  [state]
  (let [{:keys [grid-id phase frozen-cloids]} (update-of state)]
    (if (and (replace-state/current? state)
             (contains? #{:blocked :canceled :ready} phase)
             (seq frozen-cloids)
             (not (update-busy? state grid-id)))
      [[:effects/vol-range-execute :replace-confirm {:grid-id grid-id}]]
      [])))

;; ---------------------------------------------------------------------------
;; Replacement (spec 10.2 step 5)
;; ---------------------------------------------------------------------------

(defn toggle-vol-ladder-update-rejected
  "Include or exclude one rejected member of the old ladder. Never included
  silently; unknown ids are no-ops."
  [state logical-id]
  (let [{:keys [phase basis include-rejected]} (update-of state)
        listed? (some #(= logical-id (:logical-id %)) (:rejected basis))
        selected (set include-rejected)]
    (if (and (replace-state/current? state) (= :canceled phase) listed?)
      [[:effects/save-many [[[:vol-range :replace :include-rejected]
                             (if (contains? selected logical-id)
                               (disj selected logical-id)
                               (conj selected logical-id))]]]]
      [])))

(defn review-vol-ladder-replacement
  "Write the replacement quantities (the frozen unfilled remainder plus the
  selected rejected members) into the ticket and close the dialog; the user
  then reviews and places through the ordinary review."
  [state]
  (let [{:keys [grid-id phase basis include-rejected] :as u} (update-of state)]
    (if (and (replace-state/current? state)
             (= :canceled phase)
             (:ready? basis)
             (not (update-busy? state grid-id)))
      (let [patch (replace-flow/quantities-patch basis include-rejected)]
        [[:effects/save-many
          (into (vol-range-actions/transition->path-values state (transitions/apply-patch state patch))
                (save-update (assoc u :phase :ready :dialog-open? false)))]])
      [])))

;; ---------------------------------------------------------------------------
;; Stop updating
;; ---------------------------------------------------------------------------

(defn stop-vol-ladder-update
  "Abandon the update in any phase, current or stale: the old ladder stays as
  it is (canceled if the cancel ran) and nothing is placed. A review frozen
  with this update's lineage closes too. Never an order effect."
  ([state] (stop-vol-ladder-update state (now)))
  ([state now-ms]
   (if-let [{:keys [grid-id phase]} (update-of state)]
     [[:effects/save-many
       (cond-> (save-update nil)
         true (conj [[:vol-range :execution :last]
                     {:op :replace-stop :status :stopped :grid-id grid-id
                      :cancel-started? (not= :preview phase) :at-ms now-ms}])
         (get-in state [:vol-range :frozen-context :replace])
         (into (:path-values (transitions/close-review))))]]
     [])))
