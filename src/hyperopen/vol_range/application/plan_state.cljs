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
            [hyperopen.vol-range.application.remediations :as remediations]
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

(defn- error-signature
  "Which errors the plan has, by code, side and leg. Book movement that keeps
  the same legs crossing (or not) leaves the signature unchanged."
  [result]
  (mapv (fn [e] [(:code e) (get-in e [:data :side]) (get-in e [:data :leg-id])]) (:errors result)))

(def ^:private book-sensitive-codes
  #{:plan/post-only-cross :plan/distance-guard :plan/self-cross})

(defn- remediation-key
  "The remediation search re-runs the whole planner many times, so it is keyed
  on the planner inputs WITHOUT provenance times and on the error signature.
  The live book is part of the key whenever offers depend on it: a market-price
  anchor (every price moves with the mid) or any crossing/distance error.
  Otherwise a book tick that changes no failing leg reuses the offers, and
  `offer-still-ready?` re-checks an offer against the live book on apply."
  [input result]
  (let [book-sensitive? (or (= :market-price (get-in input [:draft :anchor :mode]))
                            (some #(contains? book-sensitive-codes (:code %)) (:errors result)))
        base (plan-core/without-provenance (dissoc input :plan-id :clock))]
    [(if book-sensitive?
       base
       (-> base
           (assoc-in [:market :bbo] nil)
           (assoc-in [:market :anchors :market-price] nil)))
     (error-signature result)]))

(def ^:private remediation-cache (atom nil))

(defn- cached-offers [input result]
  (let [k (remediation-key input result)
        cached @remediation-cache]
    (if (and cached (= k (:key cached)))
      (:offers cached)
      (let [offers (remediations/offers input result)]
        (reset! remediation-cache {:key k :offers offers})
        offers))))

(defn- prepare
  "`planner/prepare-plan`, with the remediation search memoized (above)."
  [input]
  (let [result (plan-core/prepare-plan-core input)]
    (if (= :invalid (:status result))
      (assoc result :remediations (cached-offers input result))
      result)))

(defn- compute [inputs now-ms]
  (let [input (plan-input inputs now-ms)
        fingerprint (plan-core/fingerprint input)
        plan-id (str "vr-plan-" (subs fingerprint (count "vr1-")))]
    {:input (assoc input :plan-id plan-id)
     :plan (prepare (assoc input :plan-id plan-id))}))

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
        ;; Provenance times alone never re-plan; they are captured separately
        ;; when a review is frozen (`snapshots/provenance`).
        key [(plan-core/without-provenance inputs) (capabilities) now*]
        cached @cache]
    (if (and cached (= key (:key cached)))
      (:value cached)
      (let [value (assoc (merge inputs (compute inputs now*)) :now-ms now*)]
        (reset! cache {:key key :value value})
        value))))

(defn offer-still-ready?
  "VR-23 at the moment of use: does `offer`'s own patch still yield a ready plan
  against the LIVE inputs? Guards against an offer cached before the book moved."
  [state now-ms offer]
  (let [{:keys [input]} (context-for-state state now-ms)]
    (remediations/patch-ready? input (:patch offer))))

(defn plan-for-state
  "The Vol Range plan (::plan-result) for the current state."
  [state now-ms]
  (:plan (context-for-state state now-ms)))
