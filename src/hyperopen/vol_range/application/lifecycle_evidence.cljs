(ns hyperopen.vol-range.application.lifecycle-evidence
  "Pure application of exchange evidence to a Vol Range grid (HO-SPEC-014
  v2.1 sections 8.4-8.6, 9.1, 9.2, 10.1; VR-12 to VR-15, VR-18).

  Rules that hold for every kind of evidence:
  - Evidence for another account or market is ignored (VR-18).
  - Positive evidence (a resting order, a fill, a venue status) can resolve an
    unknown leg. Absence never proves non-placement: an order missing from a
    snapshot, `unknownOid`, a timeout or a missing-order cancel reply only
    makes the leg's current state unconfirmed.
  - A terminal leg never becomes resting again, and an accepted leg never
    becomes rejected or unknown.
  - Fills are keyed by network, account, coin, time, trade id and order id and
    ingested once; a fill already reported in an order response is never
    counted again (the response only floors the filled size).
  - `:settled-evidence-at-ms` records when the leg's venue commitment last
    changed, for reservation release."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]))

(def ^:private zero (precision/parse-decimal "0"))
(defn- d [x] (or (some-> x str precision/parse-decimal) zero))
(defn- ds [x] (precision/decimal->string x))
(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(def oid-string lifecycle/oid-string)

(defn- scope-account [grid] (lower (get-in grid [:scope :account-address])))
(defn- scope-coin [grid] (get-in grid [:scope :coin]))

(defn- leg-for
  "The leg for an oid or CLOID identity."
  [grid {:keys [oid cloid]}]
  (let [oid (oid-string oid)
        cloid (lower cloid)]
    (or (when cloid (get-in grid [:legs cloid]))
        (when oid (first (filter #(= oid (:oid %)) (vals (:legs grid))))))))

(defn- commitment
  "What the venue holds for a leg: placement, order state, filled floor and
  effective remaining. A snapshot repeating the known remaining is no change."
  [leg]
  (let [r (precision/sub (d (:original-base leg)) (d (:reported-filled-base leg)))
        r (if-let [o (:observed-remaining leg)] (precision/dec-min r (d o)) r)]
    [(:placement-state leg) (:order-state leg) (:reported-filled-base leg) (ds r)]))

(defn- touch
  "Store `leg*` and stamp evidence times; the settled stamp moves only when the
  venue commitment changed."
  [grid before leg* at-ms]
  (let [changed? (not= (commitment before) (commitment leg*))]
    (assoc-in grid [:legs (:cloid leg*)]
              (cond-> (assoc leg* :last-evidence-at-ms (max (or (:last-evidence-at-ms before) 0) at-ms)
                             :updated-at-ms at-ms)
                changed? (assoc :settled-evidence-at-ms at-ms)))))

(defn- accept [leg oid]
  (cond-> (assoc leg :placement-state :accepted)
    (and oid (nil? (:oid leg))) (assoc :oid oid)))

(defn- floor-filled [leg filled]
  (let [f (precision/dec-min (d (:original-base leg)) (precision/dec-max (d (:reported-filled-base leg)) (d filled)))]
    (assoc leg :reported-filled-base (ds f))))

(defn- set-cancel [leg state at-ms] (assoc leg :cancel-state state :cancel-updated-at-ms at-ms))

(defn- terminal [leg reason at-ms]
  (cond-> (assoc leg :order-state :terminal :terminal-reason reason)
    (contains? #{:requested :acknowledged :not-resting :unknown :rejected :failed} (:cancel-state leg))
    (set-cancel (if (= :canceled reason) :confirmed :resolved) at-ms)))

(defn refresh-operations
  "An unresolved operation becomes resolved once none of its legs is."
  [grid]
  (reduce (fn [g {:keys [operation-id state cloids]}]
            (if (and (= :unresolved state)
                     (not-any? #(let [l (get-in g [:legs %])]
                                  (or (lifecycle/leg-unresolved? l) (= :pending (:placement-state l))))
                               cloids))
              (assoc-in g [:operations operation-id :state] :resolved)
              g))
          grid
          (vals (:operations grid))))

;; ---------------------------------------------------------------------------
;; Placement delivery (spec 8.4)
;; ---------------------------------------------------------------------------

(defn- apply-leg-result [leg {:keys [placement-state order-state oid filled-base reason]} at-ms]
  (let [oid (oid-string oid)
        current (:placement-state leg)]
    (cond
      (= :accepted current)
      (cond-> leg
        (and oid (nil? (:oid leg))) (assoc :oid oid)
        (= :rejected placement-state) (assoc :reason (str "Contradicting rejection: " reason)))

      (= :accepted placement-state)
      (let [leg (accept leg oid)]
        (if (= :filled order-state)
          (let [leg (floor-filled leg filled-base)]
            (if (>= (precision/cmp (d (:reported-filled-base leg)) (d (:original-base leg))) 0)
              (terminal leg :filled at-ms)
              (assoc leg :order-state :unconfirmed)))
          (if (= :terminal (:order-state leg)) leg (assoc leg :order-state :resting))))

      (and (= :rejected placement-state) (contains? #{:pending :unknown} current))
      (assoc leg :placement-state :rejected :order-state :terminal :terminal-reason :rejected
             :reason (str reason))

      (= :pending current) (assoc leg :placement-state :unknown :reason (some-> reason str))
      :else leg)))

(defn apply-placement-delivery
  "Apply a delivery result ({:delivery :not-sent|:response|:unknown
  :leg-results [...]}) for a placement or repair operation."
  [grid operation-id {:keys [delivery leg-results error]} at-ms]
  (let [cloids (get-in grid [:operations operation-id :cloids])
        by-cloid (into {} (map (fn [r] [(lower (:cloid r)) r])) leg-results)
        grid (reduce
              (fn [g c]
                (let [leg (get-in g [:legs c])
                      leg* (case delivery
                             :not-sent (if (= :pending (:placement-state leg))
                                         (assoc leg :placement-state :not-sent :order-state :terminal
                                                :terminal-reason :not-sent :reason error)
                                         leg)
                             :response (apply-leg-result leg (or (get by-cloid c)
                                                                 {:placement-state :unknown
                                                                  :reason :missing-leg-result})
                                                         at-ms)
                             (if (= :pending (:placement-state leg))
                               (assoc leg :placement-state :unknown :reason error)
                               leg))]
                  (touch g leg leg* at-ms)))
              grid cloids)
        unresolved? (some #(lifecycle/leg-unresolved? (get-in grid [:legs %])) cloids)]
    (cond-> (assoc-in grid [:operations operation-id :state]
                      (cond (= :not-sent delivery) :not-sent
                            unresolved? :unresolved
                            :else :resolved))
      ;; The delivery path proves a not-sent outcome (its HTTP call never
      ;; started), unlike a recovery decision.
      (= :not-sent delivery) (assoc-in [:operations operation-id :not-sent-source] :delivery))))

;; ---------------------------------------------------------------------------
;; Orders snapshot (spec 8.6, 9.1)
;; ---------------------------------------------------------------------------

(defn apply-orders-snapshot
  "Apply raw open orders read for the grid's account. Presence accepts the leg
  and refreshes its remaining size. Absence from a COMPLETE snapshot that was
  requested after the leg's last evidence and after its send only makes a
  resting leg unconfirmed (it filled or was canceled; a status lookup decides)."
  [grid {:keys [account-address requested-at-ms received-at-ms complete? open-orders]}]
  (if (not= (lower account-address) (scope-account grid))
    grid
    (let [rows (filter #(= (scope-coin grid) (:coin %)) open-orders)
          by-cloid (into {} (keep (fn [r] (when-let [c (lower (:cloid r))] [c r]))) rows)
          by-oid (into {} (keep (fn [r] (when-let [o (oid-string (:oid r))] [o r]))) rows)
          sent-at (fn [leg] (get-in grid [:operations (:operation-id leg) :sent-at-ms]))]
      (refresh-operations
       (reduce
        (fn [g leg]
          (let [row (or (get by-cloid (:cloid leg)) (some->> (:oid leg) (get by-oid)))]
            (cond
              (= :terminal (:order-state leg)) g
              (= :pending (:placement-state leg)) g

              row
              (let [sz (d (:sz row))
                    orig (d (or (:origSz row) (:sz row)))
                    leg* (-> (accept leg (oid-string (:oid row)))
                             (assoc :order-state :resting :observed-remaining (ds sz))
                             (floor-filled (ds (precision/sub orig sz))))]
                (touch g leg leg* received-at-ms))

              (and complete?
                   (= :accepted (:placement-state leg))
                   (= :resting (:order-state leg))
                   (> requested-at-ms (or (:last-evidence-at-ms leg) 0))
                   (> requested-at-ms (or (sent-at leg) 0)))
              (touch g leg (assoc leg :order-state :unconfirmed) received-at-ms)

              :else g)))
        grid
        (vals (:legs grid)))))))

;; ---------------------------------------------------------------------------
;; Order status lookups (spec 8.6)
;; ---------------------------------------------------------------------------

(defn- status-kind [s]
  (cond (= "open" s) :open
        (= "filled" s) :filled
        (or (= "canceled" s) (= "scheduledCancel" s) (and (string? s) (str/ends-with? s "Canceled"))) :canceled
        (and (string? s) (str/ends-with? s "Rejected")) :rejected
        (= "unknownOid" s) :unknown-oid
        :else nil))

(defn- cancel-expired?
  "A cancel whose outcome left the order possibly canceled (unknown,
  acknowledged, or not resting) cannot take effect any more once the NEWEST
  cancel operation for that order is past its deadline plus the HTTP timeout,
  so an authoritative open status after that means the order is still live:
  the cancel failed."
  [grid leg at-ms]
  (and (contains? #{:unknown :acknowledged :not-resting} (:cancel-state leg))
       (let [latest (->> (vals (:operations grid))
                         (filter #(and (= :cancel (:kind %)) (some #{(:cloid leg)} (:cloids %))))
                         (sort-by :created-at-ms)
                         last)]
         (and latest
              (number? (:expires-after-ms latest))
              (> at-ms (+ (:expires-after-ms latest) (:sending lifecycle/recovery-margin-ms)))))))

(defn apply-order-statuses
  "Apply `read-order-statuses!` results ({:id :status :order :error})."
  [grid statuses received-at-ms]
  (refresh-operations
   (reduce
    (fn [g {:keys [id status order]}]
      (let [inner (or (:order order) order)
            leg (leg-for g (if (oid-string id) {:oid id} {:cloid id}))
            kind (status-kind status)
            coin-ok? (or (nil? (:coin inner)) (= (scope-coin g) (:coin inner)))
            orig (d (or (:origSz inner) (:original-base leg)))
            sz (some-> (:sz inner) d)
            filled (when sz (ds (precision/sub orig sz)))]
        (if (or (nil? leg) (not coin-ok?) (= :terminal (:order-state leg)))
          g
          (case kind
            :open (touch g leg (-> (accept leg (oid-string (:oid inner)))
                                   (assoc :order-state :resting :observed-remaining (some-> sz ds))
                                   (cond-> filled (floor-filled filled))
                                   (cond-> (cancel-expired? g leg received-at-ms) (set-cancel :failed received-at-ms)))
                         received-at-ms)
            :filled (touch g leg (-> (accept leg (oid-string (:oid inner)))
                                     (floor-filled (:original-base leg))
                                     (terminal :filled received-at-ms))
                           received-at-ms)
            :canceled (touch g leg (-> (accept leg (oid-string (:oid inner)))
                                       (cond-> filled (floor-filled filled))
                                       (terminal :canceled received-at-ms))
                             received-at-ms)
            :rejected (if (contains? #{:pending :unknown} (:placement-state leg))
                        (touch g leg (assoc leg :placement-state :rejected :order-state :terminal
                                            :terminal-reason :rejected :reason status)
                               received-at-ms)
                        g)
            :unknown-oid (assoc-in g [:legs (:cloid leg) :unknown-oid-at-ms] received-at-ms)
            g))))
    grid
    statuses)))

;; ---------------------------------------------------------------------------
;; Fills (spec 9.2, VR-14)
;; ---------------------------------------------------------------------------

(defn int-text
  "Venue time and trade id as integer text, whether they arrive as numbers
  (WebSocket and REST rows today) or numeric strings, so both sources give
  the same key. Integer numbers render exactly as `str` did before. Public so
  `fill-records` renders `:tid` exactly as the key does."
  [x]
  (cond (and (number? x) (js/isFinite x)) (.toFixed (js/Math.trunc x) 0)
        (and (string? x) (re-matches #"\d+" x)) x
        :else (str x)))

(defn fill-key
  "Durable fill identity. Never the display timestamp, price or tid alone.
  Time and tid are canonicalized to integer text (`int-text`)."
  [network account-address row]
  (str (name (or network :hyperliquid-mainnet)) "|" (lower account-address) "|" (:coin row) "|"
       (int-text (:time row)) "|" (int-text (:tid row)) "|"
       (or (oid-string (:oid row)) (lower (:cloid row)))))

(defn- normalize-fill [k leg row]
  {:fill-key k
   :cloid (:cloid leg)
   :oid (or (oid-string (:oid row)) (:oid leg))
   :side (:side row)
   :px (some-> (:px row) str)
   :sz (some-> (:sz row) str)
   :fee (some-> (:fee row) str)
   :fee-token (:feeToken row)
   :builder-fee (some-> (:builderFee row) str)
   :time (:time row)
   :tid (some-> (:tid row) str)
   :crossed (boolean (:crossed row))})

(defn ingest-fills
  "Idempotently ingest raw fill rows (snapshot or increment, REST or
  WebSocket) for the grid's account. A fill proves its order was accepted."
  [grid {:keys [account-address rows received-at-ms]}]
  (if (not= (lower account-address) (scope-account grid))
    grid
    (refresh-operations
     (reduce
      (fn [g row]
        (let [leg (when (= (scope-coin g) (:coin row)) (leg-for g row))
              k (fill-key (get-in g [:scope :network]) account-address row)]
          (if (or (nil? leg) (nil? (precision/parse-decimal (str (:sz row)))) (contains? (:fills g) k))
            g
            (let [g (assoc-in g [:fills k] (normalize-fill k leg row))
                  leg* (accept leg (oid-string (:oid row)))
                  {:keys [filled]} (lifecycle/leg-quantities g leg*)
                  leg* (cond
                         (= :terminal (:order-state leg*)) leg*
                         (>= (precision/cmp (d filled) (d (:original-base leg*))) 0) (terminal leg* :filled received-at-ms)
                         (nil? (:order-state leg*)) (assoc leg* :order-state :unconfirmed)
                         :else leg*)]
              ;; A new fill always changes the commitment, even when the leg
              ;; record itself looks the same.
              (assoc-in (touch g leg leg* received-at-ms)
                        [:legs (:cloid leg) :settled-evidence-at-ms] received-at-ms)))))
      grid
      rows))))

;; ---------------------------------------------------------------------------
;; Cancel delivery (spec 10.1)
;; ---------------------------------------------------------------------------

(defn apply-cancel-delivery
  "Apply a cancel delivery ({:delivery ... :results [{:cloid :cancel-state}]}).
  A cancel that was provably not sent restores the previous cancel state."
  [grid operation-id {:keys [delivery results]} at-ms]
  (let [cloids (get-in grid [:operations operation-id :cloids])
        by-cloid (into {} (map (fn [r] [(lower (:cloid r)) r])) results)
        grid (reduce
              (fn [g c]
                (let [leg (get-in g [:legs c])]
                  (if (not= :requested (:cancel-state leg))
                    g
                    (update-in g [:legs c] assoc :updated-at-ms at-ms :cancel-updated-at-ms at-ms :cancel-state
                              (case delivery
                                :not-sent (:cancel-prior leg)
                                :response (case (:cancel-state (get by-cloid c))
                                            :cancel-acknowledged :acknowledged
                                            :not-resting :not-resting
                                            :rejected :rejected
                                            :unknown)
                                :unknown)))))
              grid cloids)
        unresolved? (some #(lifecycle/leg-unresolved? (get-in grid [:legs %])) cloids)]
    (assoc-in grid [:operations operation-id :state]
              (cond (= :not-sent delivery) :not-sent
                    unresolved? :unresolved
                    :else :resolved))))
