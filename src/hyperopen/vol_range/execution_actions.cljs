(ns hyperopen.vol-range.execution-actions
  "Vol Range Release A action handlers: place the reviewed ladder, cancel or
  refresh a ladder, select a ladder in the dock, and sync ladders from the
  journal (HO-SPEC-014 v2.1 sections 8, 10, 11.2).

  Pure: each handler returns effects. Every operation runs through
  `[:effects/vol-range-execute op params]`; nothing here signs, reserves a
  nonce or writes the journal. A second click while the same operation is in
  flight emits nothing (spec 14.2: double click)."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]))

(defn sync-key
  "What ladder sync depends on: the trading account and the active market."
  [state]
  [(account-context/active-trading-account-address state)
   (get-in state [:active-market :coin])
   (get-in state [:active-market :market-type])])

(defn- busy? [state k] (true? (get-in state [:vol-range :execution :busy k])))

(defn place-vol-range-orders
  "Place the reviewed plan. Only a :ready frozen review places, once."
  [state]
  (let [plan (get-in state [:vol-range :frozen-review])]
    (if (and (get-in state [:vol-range :ui :review-open?])
             (= :ready (:status plan))
             (not (busy? state [:place])))
      [[:effects/vol-range-execute :place {}]]
      [])))

(defn- grid [state grid-id] (get-in state [:vol-range :execution :grids grid-id]))

(defn cancel-vol-ladder
  [state grid-id]
  (if (and (grid state grid-id) (not (busy? state [:cancel grid-id])))
    [[:effects/vol-range-execute :cancel {:grid-id grid-id}]]
    []))

(defn refresh-vol-ladder
  [state grid-id]
  (if (and (grid state grid-id) (not (busy? state [:refresh grid-id])))
    [[:effects/vol-range-execute :refresh {:grid-id grid-id}]]
    []))

(defn select-vol-ladder
  "Open (or close, when already selected) a ladder's order detail."
  [state grid-id]
  (let [current (get-in state [:vol-range :execution :selected-grid-id])]
    [[:effects/save-many [[[:vol-range :execution :selected-grid-id]
                           (when (not= current grid-id) grid-id)]]]]))

(defn sync-vol-ladders
  [state]
  (if (busy? state [:sync]) [] [[:effects/vol-range-execute :sync {}]]))

(defn reconcile-open-vol-ladders
  "Background reconciliation for ladders that are not closed."
  [state]
  (let [open? (some #(not (contains? #{:closed :not-placed} (lifecycle/status %)))
                    (vals (get-in state [:vol-range :execution :grids])))]
    (if (and open? (not (busy? state [:reconcile-open])))
      [[:effects/vol-range-execute :reconcile-open {}]]
      [])))
