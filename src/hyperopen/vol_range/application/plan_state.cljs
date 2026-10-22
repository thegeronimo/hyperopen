(ns hyperopen.vol-range.application.plan-state
  "Plan-for-state: runs the pure planner over snapshots built from app state.

  The result is memoized on [effective draft, scope, market snapshot, account
  snapshot, fee snapshot, floor(now-ms / 60000)] so view-model reads between
  samples reuse one plan. Time is injected: `now-ms` is floored to the minute
  before it enters any snapshot, so snapshots stay stable within a minute.
  VR-24: nothing here builds an exchange action, claims a signing counter or writes an
  intent."
  (:require [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.plan-core :as plan-core]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.contracts :as contracts]))

(def ^:private minute-ms 60000)

(defn minute-floor
  "Floor an epoch-millisecond value to the minute."
  [now-ms]
  (* minute-ms (js/Math.floor (/ now-ms minute-ms))))

(defn capabilities []
  (assoc contracts/capability-matrix :preview? (contracts/preview-enabled?)))

(defn- plan-input [{:keys [draft scope market account fees]} now-ms]
  {:scope scope
   :draft draft
   :market market
   :account account
   :fees fees
   :pending-intents []
   :clock now-ms
   :model-version contracts/model-version
   :precision-version contracts/precision-version
   :capabilities (capabilities)})

(defn- compute [inputs now-ms]
  (let [input (plan-input inputs now-ms)
        fingerprint (plan-core/fingerprint input)
        plan-id (str "vr-plan-" (subs fingerprint (count "vr1-")))]
    {:input (assoc input :plan-id plan-id)
     :plan (planner/prepare-plan (assoc input :plan-id plan-id))}))

(def ^:private cache (atom nil))

(defn context-for-state
  "{:draft :scope :market :account :fees :input :plan :now-ms} for the state at
  `now-ms`, memoized on the exact inputs of the planner."
  [state now-ms]
  (let [now* (minute-floor now-ms)
        inputs {:draft (draft-scope/effective-draft state)
                :scope (snapshots/current-scope state)
                :market (snapshots/market-snapshot state now*)
                :account (snapshots/account-snapshot state now*)
                :fees (snapshots/fee-snapshot state now*)}
        key [inputs (capabilities) now*]
        cached @cache]
    (if (and cached (= key (:key cached)))
      (:value cached)
      (let [value (assoc (merge inputs (compute inputs now*)) :now-ms now*)]
        (reset! cache {:key key :value value})
        value))))

(defn plan-for-state
  "The Vol Range plan (::plan-result) for the current state."
  [state now-ms]
  (:plan (context-for-state state now-ms)))
