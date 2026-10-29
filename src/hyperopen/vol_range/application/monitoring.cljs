(ns hyperopen.vol-range.application.monitoring
  "Pure live monitoring model for Vol Range (HO-SPEC-014 v2.1 sections 9.3,
  9.4). It says, per active trading account, whether live stream evidence is
  complete enough to allow a new placement or repair.

  Monitoring is `:live` only after (a) the transport is live and both the
  `userFills` and `openOrders` streams are usable for the account, (b) a
  `userFills` snapshot for the account arrived after the last socket close,
  and (c) a resync with complete reads finished in the current epoch. Any
  disconnect, socket close, re-subscription snapshot, account change, loss of
  the account's streams, or a watcher tick gap over the policy's
  `:suspend-gap-ms` starts a new epoch; a resync result counts only for the
  epoch (and account) it started in.

  Statuses: `:stale` (not usable, or usable but no snapshot yet), `:resyncing`
  (usable; a resync is `:required`, `:running` or `:failed` with a retry time)
  and `:live`. Reasons: `:awaiting-streams`, `:transport-down`,
  `:streams-elsewhere`, `:streams-not-ready` (stale); `:catching-up`,
  `:reconnected`, `:resubscribed`, `:suspended` (resyncing); `[]` (live).

  Every function takes and returns the monitoring map. Time and jitter are
  arguments: nothing here reads a clock, a random source or the store."
  (:require [clojure.string :as str]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.websocket.health-projection :as health-projection]))

(def ^:private policy contracts/live-monitoring-policy)

(def ^:private stream-topics ["userFills" "openOrders"])

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn- same-account? [m account]
  (and (some? m) (= (lower (:account-address m)) (lower account))))

(def ^:private idle-resync {:state :idle :attempt 0})

(def ^:private required-resync {:state :required :attempt 0})

(defn initial
  "Monitoring for `account` before any stream evidence: stale, epoch 1."
  [account now-ms]
  {:account-address (lower account)
   :status :stale
   :reasons [:awaiting-streams]
   :epoch 1
   :stale-since-ms now-ms
   :live-since-ms nil
   :fills-snapshot-at-ms nil
   :last-close-at-ms nil
   :last-tick-ms now-ms
   :resync idle-resync})

(defn- next-epoch [m] (inc (or (:epoch m) 0)))

(defn- start-resync
  "A new epoch that needs a resync before placement."
  [m reason now-ms]
  (assoc m
         :status :resyncing
         :reasons [reason]
         :epoch (next-epoch m)
         :stale-since-ms (or (when-not (= :live (:status m)) (:stale-since-ms m)) now-ms)
         :live-since-ms nil
         :resync required-resync))

(defn- go-stale [m reasons now-ms]
  (if (= :stale (:status m))
    (assoc m :reasons reasons)
    (assoc m
           :status :stale
           :reasons reasons
           :epoch (next-epoch m)
           :stale-since-ms (if (= :live (:status m)) now-ms (or (:stale-since-ms m) now-ms))
           :live-since-ms nil
           :resync idle-resync)))

(defn- marker-valid?
  "The `userFills` snapshot marker counts only when it is newer than the last
  socket close, so a snapshot arriving before or after the health projection
  reports the close never deadlocks."
  [{:keys [fills-snapshot-at-ms last-close-at-ms]}]
  (and (number? fills-snapshot-at-ms)
       (or (not (number? last-close-at-ms))
           (> fills-snapshot-at-ms last-close-at-ms))))

;; ---------------------------------------------------------------------------
;; Stream usability
;; ---------------------------------------------------------------------------

(defn- transport-live? [health]
  (and (= :connected (get-in health [:transport :state]))
       (= :live (get-in health [:transport :freshness]))))

(defn- stream-topic [stream]
  (or (:topic stream) (get-in stream [:descriptor :type])))

(defn- subscribed-elsewhere?
  "A subscribed stream of `topic` exists for a user other than `account`."
  [health topic account]
  (boolean
   (some (fn [[_ stream]]
           (and (map? stream)
                (true? (:subscribed? stream))
                (= topic (stream-topic stream))
                (some-> (get-in stream [:descriptor :user]) lower (not= account))))
         (:streams health))))

(defn- unusable-reason
  "nil when both account streams are usable, else the stale reason."
  [health account]
  (let [selector {:user account}]
    (cond
      (nil? account) :streams-not-ready
      (not (transport-live? health)) :transport-down
      (every? #(health-projection/topic-stream-usable? health % selector) stream-topics) nil
      (some #(and (not (health-projection/topic-stream-subscribed? health % selector))
                  (subscribed-elsewhere? health % account))
            stream-topics) :streams-elsewhere
      :else :streams-not-ready)))

;; ---------------------------------------------------------------------------
;; Transitions
;; ---------------------------------------------------------------------------

(defn- adopt-account
  "A different account starts over from `initial`, with a higher epoch so no
  result of the previous account's epoch can ever count."
  [m account now-ms]
  (if (same-account? m account)
    m
    (assoc (initial account now-ms) :epoch (next-epoch m))))

(defn- apply-health
  "A changed `[:transport :last-close :at-ms]` is a connection change. Losing
  usability goes stale (one new epoch unless already stale); a connection
  change with usable streams starts a new epoch that needs a resync; usable
  streams plus a valid snapshot marker take a stale account to resyncing."
  [m health now-ms]
  (let [close-at (get-in health [:transport :last-close :at-ms])
        closed? (not= close-at (:last-close-at-ms m))
        m (assoc m :last-close-at-ms close-at)
        reason (unusable-reason health (:account-address m))]
    (cond
      reason (go-stale m [reason] now-ms)
      (and closed? (not= :stale (:status m))) (start-resync m :reconnected now-ms)
      (not= :stale (:status m)) m
      (marker-valid? m) (assoc m :status :resyncing :reasons [:catching-up] :resync required-resync)
      :else (assoc m :reasons [:awaiting-streams]))))

(defn observe
  "Fold the current account and `[:websocket :health]` into `m`. Returns `m`
  itself when nothing changed."
  [m {:keys [account health now-ms]}]
  (let [account (lower account)
        m* (-> (adopt-account m account now-ms)
               (apply-health health now-ms))]
    (if (= m* m) m m*)))

(defn on-fills-snapshot
  "A `userFills` snapshot for `account` arrived at `now-ms`. With a valid
  marker already present while live or resyncing this is a re-subscription:
  the stream had a gap, so a new epoch starts (a resync already running may
  have read before the gap ended). Snapshots for another account are
  ignored: callers `observe` the current trading account first."
  [m {:keys [account now-ms]}]
  (if-not (same-account? m account)
    m
    (cond-> m
      (and (contains? #{:live :resyncing} (:status m)) (marker-valid? m))
      (start-resync :resubscribed now-ms)
      true (assoc :fills-snapshot-at-ms now-ms))))

(defn on-tick
  "A watcher tick at `now-ms`. A gap over `:suspend-gap-ms` while live or
  resyncing means the page was suspended; a negative gap (clock moved back)
  is ignored. A failed resync whose retry time passed becomes required."
  [m {:keys [now-ms]}]
  (if (nil? m)
    m
    (let [last-tick (:last-tick-ms m)
          gap (when (number? last-tick) (- now-ms last-tick))
          suspended? (and (number? gap)
                          (> gap (:suspend-gap-ms policy))
                          (contains? #{:live :resyncing} (:status m)))
          m* (if suspended? (start-resync m :suspended now-ms) m)
          {:keys [state next-at-ms]} (:resync m*)]
      (cond-> (assoc m* :last-tick-ms now-ms)
        (and (= :failed state) (number? next-at-ms) (>= now-ms next-at-ms))
        (assoc-in [:resync :state] :required)))))

(defn resync-due? [m]
  (and (= :resyncing (:status m))
       (= :required (get-in m [:resync :state]))))

(defn on-resync-started
  "Marks the required resync of `epoch` as running."
  [m {:keys [epoch now-ms]}]
  (if (and (some? m) (= epoch (:epoch m)) (resync-due? m))
    (-> m
        (assoc-in [:resync :state] :running)
        (assoc-in [:resync :started-at-ms] now-ms))
    m))

(defn- backoff-ms
  "Retry delay for failed attempt `n` (1-based), scaled by `1 + jitter` with
  jitter clamped to the policy bound."
  [n jitter]
  (let [steps (:resync-backoff-ms policy)
        base (nth steps (min (dec n) (dec (count steps))))
        bound (:resync-jitter policy)
        j (if (number? jitter) (max (- bound) (min bound jitter)) 0)]
    (js/Math.round (* base (+ 1 j)))))

(defn on-resync-finished
  "A resync for `account` and `epoch` finished. It counts only while that
  epoch's resync is still running for the same account."
  [m {:keys [epoch account ok? error now-ms jitter]}]
  (if-not (and (same-account? m account)
               (= epoch (:epoch m))
               (= :resyncing (:status m))
               (= :running (get-in m [:resync :state])))
    m
    (if ok?
      (assoc m
             :status :live
             :reasons []
             :live-since-ms now-ms
             :stale-since-ms nil
             :resync idle-resync)
      (let [n (inc (or (get-in m [:resync :attempt]) 0))]
        (assoc m :resync {:state :failed
                          :attempt n
                          :error error
                          :next-at-ms (+ now-ms (backoff-ms n jitter))})))))

;; ---------------------------------------------------------------------------
;; Blockers
;; ---------------------------------------------------------------------------

(defn view-blocker
  "The status and account rules of `placement-blocker`, without the tick gap
  (view slices carry no `:last-tick-ms`)."
  [m account]
  (cond
    (or (nil? (lower account)) (not (same-account? m account))) :monitoring/stale
    (= :live (:status m)) nil
    (= :resyncing (:status m)) :monitoring/resync-required
    :else :monitoring/stale))

(defn placement-blocker
  "nil only when `m` is live for `account` and the last watcher tick is at
  most `:suspend-gap-ms` before `now-ms`; otherwise the refusal code."
  [m {:keys [account now-ms]}]
  (let [last-tick (:last-tick-ms m)]
    (cond
      (or (nil? (lower account)) (not (same-account? m account))) :monitoring/stale
      (or (not (number? last-tick))
          (> (- now-ms last-tick) (:suspend-gap-ms policy))) :monitoring/suspended
      :else (view-blocker m account))))

(defn revision
  "Envelope revision `\"<epoch>:<seq>\"` for diagnostics and race tests."
  [m seq-n]
  (str (or (:epoch m) 0) ":" seq-n))
