(ns hyperopen.vol-range.application.live-evidence
  "Pure handling of WebSocket order and fill evidence for Vol Range ladders
  (HO-SPEC-014 v2.1 sections 9.1, 9.2; VR-14, VR-18, VR-19).

  A raw `userFills` or `openOrders` message becomes a spec 9.1 envelope, its
  rows are routed to the ladders they prove something about, and routed rows
  are applied through the same `lifecycle-evidence` functions the REST paths
  use, so the one fill key deduplicates across every source.

  Rules:
  - A ladder is a candidate only for its own network and account; a row
    reaches it only for its own coin and a known oid or CLOID (VR-18).
  - Rows are taken whole: no display truncation and no cancel-guard pruning
    apply here (spec 9.2, VR-19).
  - Stream open orders are presence-only evidence (`:complete? false`). A row
    is applied only when informative: it proves an unknown placement was
    accepted, or it shows a strictly smaller remaining size for a resting
    leg. It never touches a pending, unconfirmed or terminal leg and never
    raises a remaining size. Absence is reported (`:absent`), never applied.
  - Fills for a leg whose placement is still pending (no HTTP response yet)
    are left to the reconciliation that follows the response.
  - Nothing here reads a clock or performs I/O."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]))

(def absence-margin-ms
  "A resting leg missing from a stream frame is reported absent only when its
  newest evidence is at least this much older than the frame."
  2000)

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn- blank->nil [x] (some-> x str str/trim not-empty))

;; ---------------------------------------------------------------------------
;; Envelopes (spec 9.1)
;; ---------------------------------------------------------------------------

(defn- message-address
  "The same fields `websocket.user-runtime.common/message-address` checks."
  [msg]
  (let [data (when (map? (:data msg)) (:data msg))]
    (some lower [(:user msg) (:address msg) (:walletAddress msg)
                 (:user data) (:address data) (:walletAddress data) (:wallet data)])))

(defn- time-ms [x]
  (cond (and (number? x) (js/isFinite x)) x
        (and (string? x) (re-matches #"\d+" x)) (js/Number x)
        :else nil))

(defn- envelope
  [msg {:keys [received-at-ms network fallback-address revision]} attrs]
  (let [message (message-address msg)
        fallback (lower fallback-address)
        account (or message fallback)]
    (when account
      (merge {:scope {:network network :account-address account}
              :source :websocket
              :request-id nil
              :received-at-ms received-at-ms
              :complete? false
              :address-source (if message :message :subscription)
              :account-event-revision revision
              :dex nil}
             attrs))))

(defn fills-envelope
  "The envelope for a raw `userFills` message, or nil when neither the
  message nor the live subscription names an account. Empty snapshots are
  envelopes too (monitoring needs them)."
  [msg opts]
  (let [data (:data msg)
        rows (cond (sequential? data) (vec data)
                   (and (map? data) (sequential? (:fills data))) (vec (:fills data))
                   :else [])
        times (keep (comp time-ms :time) rows)]
    (envelope msg opts
              {:kind :fills
               :channel "userFills"
               :observed-at-ms (when (seq times) (apply max times))
               :snapshot? (boolean (or (when (map? data) (:isSnapshot data)) (:isSnapshot msg)))
               :rows rows})))

(defn open-orders-envelope
  "The envelope for a raw `openOrders` message (a snapshot of unknown
  completeness), or nil when no account is known."
  [msg opts]
  (let [data (:data msg)
        rows (cond (sequential? data) (vec data)
                   (map? data) (vec (some #(when (sequential? (get data %)) (get data %))
                                          [:orders :openOrders :data]))
                   :else [])]
    (envelope msg opts
              {:kind :orders-snapshot
               :channel "openOrders"
               :observed-at-ms nil
               :snapshot? true
               :dex (when (map? data) (blank->nil (:dex data)))
               :rows rows})))

;; ---------------------------------------------------------------------------
;; Routing
;; ---------------------------------------------------------------------------

(defn- candidate? [envelope grid]
  (and (= (get-in envelope [:scope :network]) (get-in grid [:scope :network]))
       (some? (get-in envelope [:scope :account-address]))
       (= (get-in envelope [:scope :account-address]) (lower (get-in grid [:scope :account-address])))))

(defn- row-leg
  "The grid leg a row names by CLOID or lossless oid, on the grid's coin."
  [grid row]
  (when (= (get-in grid [:scope :coin]) (:coin row))
    (let [cloid (lower (:cloid row))
          oid (lifecycle/oid-string (:oid row))]
      (or (when cloid (get-in grid [:legs cloid]))
          (when oid (some #(when (= oid (:oid %)) %) (vals (:legs grid))))))))

(defn- smaller? [a b]
  (let [a (some-> a str precision/parse-decimal)
        b (some-> b str precision/parse-decimal)]
    (boolean (and a b (neg? (precision/cmp a b))))))

(defn- informative-order? [grid leg row]
  (and (some? (some-> (:sz row) str precision/parse-decimal))
       (not= :terminal (:order-state leg))
       (not= :unconfirmed (:order-state leg))
       (case (:placement-state leg)
         :unknown true
         :accepted (and (= :resting (:order-state leg))
                        (smaller? (:sz row) (:remaining (lifecycle/leg-quantities grid leg))))
         false)))

(defn- informative-fill? [grid envelope leg row]
  (and (not= :pending (:placement-state leg))
       (some? (some-> (:sz row) str precision/parse-decimal))
       (not (contains? (:fills grid)
                       (evidence/fill-key (get-in grid [:scope :network])
                                          (get-in envelope [:scope :account-address]) row)))))

(defn informative-rows
  "The envelope rows that would change `grid` (empty unless it is a
  candidate). Repeats, other coins, unknown identities and stale stream rows
  are dropped here, before anything touches the store."
  [grid envelope]
  (if-not (candidate? envelope grid)
    []
    (let [fills? (= :fills (:kind envelope))]
      (into []
            (filter (fn [row]
                      (when-let [leg (row-leg grid row)]
                        (if fills?
                          (informative-fill? grid envelope leg row)
                          (informative-order? grid leg row)))))
            (:rows envelope)))))

(defn- absent-cloids
  "Resting legs that a default-dex open-orders frame does not list and whose
  newest evidence is older than the absence margin."
  [grid envelope]
  (let [rows (:rows envelope)
        oids (into #{} (keep #(lifecycle/oid-string (:oid %))) rows)
        cloids (into #{} (keep #(lower (:cloid %))) rows)
        at (:received-at-ms envelope)]
    (into #{}
          (comp (filter lifecycle/live-leg?)
                (remove #(contains? cloids (:cloid %)))
                (remove #(and (:oid %) (contains? oids (:oid %))))
                (filter #(<= (+ (or (:last-evidence-at-ms %) 0) absence-margin-ms) at))
                (map :cloid))
          (vals (:legs grid)))))

(defn route
  "Route an envelope's rows to the store's ladders (`grids` is `{grid-id
  grid}`). Returns:
  - `:by-grid` `{grid-id {:fills [rows] :orders [rows]}}`, informative rows only;
  - `:absent` `{grid-id #{cloid}}` for default-dex open-order frames (a hint
    for the caller's rate-limited reconciliation, never applied);
  - `:checking` routed grids whose status is Checking;
  - `:order-coins` the coins a default-dex open-order frame listed."
  [envelope grids]
  (let [orders? (= :orders-snapshot (:kind envelope))
        default-dex? (and orders? (nil? (:dex envelope)))
        candidates (filter #(candidate? envelope (val %)) grids)
        by-grid (into {}
                      (keep (fn [[gid grid]]
                              (let [rows (informative-rows grid envelope)]
                                (when (seq rows)
                                  [gid (if orders? {:fills [] :orders rows} {:fills rows :orders []})]))))
                      candidates)]
    {:by-grid by-grid
     :absent (if default-dex?
               (into {}
                     (keep (fn [[gid grid]]
                             (let [cloids (absent-cloids grid envelope)]
                               (when (seq cloids) [gid cloids]))))
                     candidates)
               {})
     :checking (into #{} (filter #(= :checking (lifecycle/status (get grids %)))) (keys by-grid))
     :order-coins (if default-dex? (into #{} (keep :coin) (:rows envelope)) #{})}))

;; ---------------------------------------------------------------------------
;; Application
;; ---------------------------------------------------------------------------

(defn apply-envelope
  "Apply `rows` of `envelope` to `grid` through `lifecycle-evidence`. Rows are
  re-checked against this grid (it may have moved on since routing). Returns
  `grid` itself (identical?) when nothing applied, so callers skip a store
  write."
  [grid envelope rows]
  (let [rows (let [ok (set (informative-rows grid (assoc envelope :rows rows)))]
               (filterv ok rows))
        account (get-in envelope [:scope :account-address])
        at (:received-at-ms envelope)]
    (if (empty? rows)
      grid
      (let [grid* (if (= :fills (:kind envelope))
                    (evidence/ingest-fills grid {:account-address account :rows rows :received-at-ms at})
                    (evidence/apply-orders-snapshot grid {:account-address account :requested-at-ms at
                                                          :received-at-ms at :complete? false
                                                          :open-orders rows}))]
        (if (= grid* grid) grid grid*)))))
