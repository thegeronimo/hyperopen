(ns hyperopen.vol-range.repair-actions
  "Vol Range repair action handlers (HO-SPEC-014 v2.1 sections 8.6, 11.2,
  13.4): open the review of a ladder's definitively rejected orders, refresh
  its prices, choose which rejected orders to send, close it, and send the
  frozen selection.

  Pure: each handler returns effects. The proposal is computed by the
  `:repair-preview` operation from awaited reads and frozen at
  `[:vol-range :repair]`; submit sends exactly the frozen selected legs
  through `[:effects/vol-range-execute :repair ...]`, never amended. A
  second click while the same operation is in flight emits nothing."
  (:require [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.repair-plan :as repair-plan]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- busy? [state k] (true? (get-in state [:vol-range :execution :busy k])))
(defn- grid [state grid-id] (get-in state [:vol-range :execution :grids grid-id]))
(defn- repair [state] (get-in state [:vol-range :repair]))

(defn- in-current-scope?
  "The ladder belongs to the ticket's active market and account."
  [state g]
  (= (:scope-key g) (contracts/scope-key (snapshots/current-scope state))))

(defn- repairable?
  [state grid-id]
  (let [g (grid state grid-id)]
    (boolean
     (and (get-in state [:vol-range :execution :enabled?])
          g
          (not (:recovered? g))
          (not (:cancel-requested-at-ms g))
          (seq (lifecycle/repair-candidates g))
          (in-current-scope? state g)
          (not= grid-id (get-in state [:vol-range :replace :grid-id]))
          (not (busy? state [:repair-preview grid-id]))
          (not (busy? state [:repair grid-id]))))))

(defn open-vol-ladder-repair
  "Open the rejected-order review for `grid-id` and read a fresh proposal."
  [state grid-id]
  (if (repairable? state grid-id)
    [[:effects/save-many [[[:vol-range :repair] {:grid-id grid-id :status :loading}]]]
     [:effects/vol-range-execute :repair-preview {:grid-id grid-id}]]
    []))

(defn refresh-vol-ladder-repair
  "Re-read the book and balances and replace the proposal."
  [state]
  (if-let [grid-id (:grid-id (repair state))]
    (open-vol-ladder-repair state grid-id)
    []))

(defn toggle-vol-ladder-repair-leg
  "Include or exclude one repairable leg. Unknown ids, a proposal that is not
  ready and a repair in flight are no-ops."
  [state logical-id]
  (let [{:keys [grid-id status legs selected] :as r} (repair state)]
    (if (and r
             (= :ready status)
             (some #(= logical-id (:logical-id %)) legs)
             (not (busy? state [:repair grid-id])))
      (let [selected (set selected)]
        [[:effects/save-many [[[:vol-range :repair :selected]
                               (if (contains? selected logical-id)
                                 (disj selected logical-id)
                                 (conj selected logical-id))]]]])
      [])))

(defn close-vol-ladder-repair
  [_state]
  [[:effects/save-many [[[:vol-range :repair] nil]]]])

(defn handle-vol-ladder-repair-keydown
  "Escape closes the review; every other key is a no-op, so Enter never
  submits."
  [state key]
  (if (= key "Escape")
    (close-vol-ladder-repair state)
    []))

(defn submit-vol-ladder-repair
  "Send the frozen selected legs, once."
  [state]
  (let [{:keys [grid-id proposal-id status] :as r} (repair state)
        legs (when r (repair-plan/selected-legs r))]
    (if (and (get-in state [:vol-range :execution :enabled?])
             (= :ready status)
             (some? proposal-id)
             (seq legs)
             (= :ready (:status (repair-plan/proposal-status r)))
             (not (busy? state [:repair grid-id])))
      [[:effects/vol-range-execute :repair {:grid-id grid-id :proposal-id proposal-id :legs legs}]]
      [])))
