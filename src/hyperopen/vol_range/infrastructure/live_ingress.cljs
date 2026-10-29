(ns hyperopen.vol-range.infrastructure.live-ingress
  "Vol Range ingress for live account-stream evidence (HO-SPEC-014 v2.1
  sections 9.1, 9.2, 9.4; VR-14, VR-18, VR-19).

  `user-stream-taps` returns the raw `userFills` / `openOrders` taps that
  startup passes to `hyperopen.websocket.user/init!`. Each tap is called as
  `(f store msg received-at-ms)` with every raw message on its channel, before
  any display filtering, truncation or cancel-guard pruning. In a build that
  includes placement the taps turn the message into a spec 9.1 envelope,
  route its rows to the store's ladders and apply them in memory, in one
  store write, through the monotonic `grid-store` merge.

  WebSocket evidence is never written to the journal from here: durable grid
  state still comes from the REST reconciliation, placement, cancel and sync
  flows. Evidence on a ladder in Checking status, or a resting leg missing
  from a default-dex open-orders frame for a coin this connection has already
  listed, asks for the existing busy-guarded open-ladder reconciliation, at
  most once per `hint-min-interval-ms` per account.

  Nothing here reads REST, signs, or writes IndexedDB, and it never requires
  the runtime effect adapter."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.config :as config]
            [hyperopen.platform :as platform]
            [hyperopen.vol-range.application.grid-store :as grid-store]
            [hyperopen.vol-range.application.live-evidence :as live-evidence]
            [hyperopen.vol-range.contracts :as contracts]))

(def hint-min-interval-ms
  "Stream evidence asks for at most one open-ladder reconciliation per
  account in this window (spec 9.4)."
  (:hint-min-interval-ms contracts/live-monitoring-policy))

(def ^:private grids-path [:vol-range :execution :grids])

(def ^:private initial-ingress
  {:account nil :last-close-at-ms nil :seen-order-coins #{} :last-hint-at-ms nil :seq 0})

(defonce ^:private installed-dispatch (atom nil))
(defonce ^:private ingress (atom initial-ingress))

(defn- active? []
  (and (config/vol-range-preview-enabled?) (config/vol-range-execution-built?)))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn install!
  "Keep `dispatch!` for reconciliation hints and start a fresh ingress state."
  [{:keys [dispatch!]}]
  (reset! installed-dispatch dispatch!)
  (reset! ingress initial-ingress)
  nil)

(defn reset-for-tests!
  "Forget the installed dispatch and the ingress state."
  []
  (reset! installed-dispatch nil)
  (reset! ingress initial-ingress))

;; ---------------------------------------------------------------------------
;; Ingress state (per connection and account)
;; ---------------------------------------------------------------------------

(defn- last-close-at-ms [state]
  (get-in state [:websocket :health :transport :last-close :at-ms]))

(defn- advance-ingress
  "Next sequence number. Seen order coins are forgotten when the trading
  account changes or the socket closed since; the hint clock is per account."
  [ingress-state account close-at]
  (let [account-changed? (not= account (:account ingress-state))
        connection-changed? (not= close-at (:last-close-at-ms ingress-state))]
    (cond-> (-> ingress-state
                (update :seq inc)
                (assoc :account account :last-close-at-ms close-at))
      (or account-changed? connection-changed?) (assoc :seen-order-coins #{})
      account-changed? (assoc :last-hint-at-ms nil))))

(defn- envelope-opts [state received-at-ms seq-n]
  {:received-at-ms received-at-ms
   :network (contracts/current-network)
   :fallback-address (lower (account-context/live-user-stream-address state))
   ;; `<epoch>:<seq>`; live monitoring supplies the epoch (0 until it exists).
   :revision (str (or (get-in state [:vol-range :execution :monitoring :epoch]) 0) ":" seq-n)})

;; ---------------------------------------------------------------------------
;; Application
;; ---------------------------------------------------------------------------

(defn- apply-routed
  "`state` with every routed grid's rows applied and merged into the store
  copy; `state` itself (identical?) when nothing changed."
  [state envelope by-grid]
  (reduce-kv (fn [s gid {:keys [fills orders]}]
               (if-let [grid (get-in s (conj grids-path gid))]
                 (let [grid* (live-evidence/apply-envelope grid envelope (into (vec fills) orders))]
                   (if (identical? grid* grid) s (grid-store/merge-grid-into-state s grid*)))
                 s))
             state
             by-grid))

(defn- write-evidence!
  "One store write for the whole frame, skipped when nothing applied."
  [store envelope by-grid]
  (when (seq by-grid)
    (let [state @store
          state* (apply-routed state envelope by-grid)]
      (when-not (identical? state state*)
        (reset! store state*)))))

(defn- hint-wanted?
  "Checking ladders were touched, or a resting leg is missing from a
  default-dex frame for a coin this connection already listed."
  [routed grids seen-coins]
  (boolean
   (or (seq (:checking routed))
       (some (fn [gid] (contains? seen-coins (get-in grids [gid :scope :coin])))
             (keys (:absent routed))))))

(defn- hint-due? [last-hint-at-ms at]
  (or (nil? last-hint-at-ms)
      (let [gap (- at last-hint-at-ms)]
        ;; A clock that moved back never silences hints for long.
        (or (neg? gap) (>= gap hint-min-interval-ms)))))

(defn- maybe-hint! [store routed grids envelope]
  (let [at (:received-at-ms envelope)
        {:keys [seen-order-coins last-hint-at-ms]} @ingress
        dispatch! @installed-dispatch]
    (when (and dispatch!
               (hint-wanted? routed grids seen-order-coins)
               (hint-due? last-hint-at-ms at))
      (swap! ingress assoc :last-hint-at-ms at)
      (platform/queue-microtask!
       #(dispatch! store nil [[:actions/reconcile-open-vol-ladders]])))))

(defn- observe!
  [store msg received-at-ms build-envelope]
  (let [state @store
        account (lower (account-context/active-trading-account-address state))
        seq-n (:seq (swap! ingress advance-ingress account (last-close-at-ms state)))]
    (when-let [envelope (build-envelope msg (envelope-opts state received-at-ms seq-n))]
      (let [grids (get-in state grids-path)
            routed (live-evidence/route envelope grids)]
        (write-evidence! store envelope (:by-grid routed))
        (maybe-hint! store routed grids envelope)
        (when (seq (:order-coins routed))
          (swap! ingress update :seen-order-coins into (:order-coins routed)))
        envelope))))

(defn observe-user-fills!
  "Raw `userFills` tap: apply the message's fills to the matching ladders in
  memory. Returns the envelope (nil when no account is known)."
  [store msg received-at-ms]
  (observe! store msg received-at-ms live-evidence/fills-envelope))

(defn observe-open-orders!
  "Raw `openOrders` tap: apply informative open-order rows (presence only) to
  the matching ladders in memory and hint a reconciliation for guarded
  absences. Returns the envelope (nil when no account is known)."
  [store msg received-at-ms]
  (observe! store msg received-at-ms live-evidence/open-orders-envelope))

(defn user-stream-taps
  "Raw user-stream taps for the account runtime: the ladder ingress in a
  build that includes placement, none otherwise."
  []
  (if (active?)
    {:on-raw-user-fills observe-user-fills!
     :on-raw-open-orders observe-open-orders!}
    {}))
