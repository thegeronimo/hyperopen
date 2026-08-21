(ns hyperopen.websocket.orderbook-l2
  "Pure policy for Hyperliquid's incremental `l2` order book channel.

   The documented `l2Book` channel is throttled server-side to roughly one
   snapshot every 5.4s. The `l2` channel carries the same book at roughly
   550ms: one full snapshot followed by compressed deltas.

   Wire shapes (`:data` of an `l2` frame):
     snapshot  {:s {:coin \"BTC\" :time 1787316486464 :levels [[bid ...] [ask ...]]}}
     delta     {:c \"<base64 raw-DEFLATE>\"}

   A decoded delta uses short keys:
     {:c \"BTC\" :t 1787316532470
      :l [[{:p \"77392.0\" :s \"5.74322\"} ...] [...]]
      :r [[19] [1]]}

   `:l` upserts levels by price, `:r` removes levels by INDEX into the current
   sorted side. Removals are indexed against the pre-upsert ordering, so they
   must be applied first -- see `apply-delta`.

   This namespace is pure: no network, DOM, timers, or atoms."
  (:require [hyperopen.websocket.orderbook-policy :as policy]))

(def channel "l2")

(def ^:private bid-side 0)
(def ^:private ask-side 1)

(def empty-sides
  [[] []])

;; ---------------------------------------------------------------- subscription

(defn build-subscription
  "The `l2` channel requires the short key `:c`. Sending `:coin` is rejected by
   the server with a JSON parse error."
  [coin]
  {:type channel :c coin})

(defn eligible?
  "True when the fast `l2` channel can serve this aggregation config.

   `l2` silently ignores `nSigFigs`, so it can only back the default full-precision
   mode. This mirrors the allow-list in `orderbook-policy/normalize-aggregation-config`
   so the two paths agree on what counts as an aggregated subscription."
  [aggregation-config]
  (not (contains? #{2 3 4 5} (:nSigFigs aggregation-config))))

;; ------------------------------------------------------------------- envelopes

(defn snapshot-payload
  "The snapshot map from an `l2` frame's `:data`, or nil when the frame is a delta."
  [data]
  (when (map? data)
    (let [snapshot (:s data)]
      (when (map? snapshot) snapshot))))

(defn delta-blob
  "The base64 raw-DEFLATE payload from an `l2` frame's `:data`, or nil when the
   frame is a snapshot. Note `:c` means the compressed blob on the wire frame but
   the coin inside the decoded delta -- they are different keys at different depths."
  [data]
  (when (map? data)
    (let [blob (:c data)]
      (when (and (string? blob) (seq blob)) blob))))

(defn- side-at [levels index]
  (vec (or (get levels index) [])))

(defn- sort-side
  "Canonical order: bids best-first (descending price), asks best-first (ascending).
   Delta removal indices are expressed against exactly this ordering."
  [levels side]
  (vec (sort-by #(or (policy/level-price %) 0)
                (if (= side bid-side) > <)
                levels)))

(defn snapshot-sides
  "`[bids asks]` from a decoded `l2` snapshot, in the level shape `l2Book` uses,
   canonically sorted (bids descending, asks ascending)."
  [snapshot]
  (let [levels (:levels snapshot)]
    [(sort-side (side-at levels bid-side) bid-side)
     (sort-side (side-at levels ask-side) ask-side)]))

;; ----------------------------------------------------------------- delta parse

(defn- normalize-upserts [side-levels]
  (into []
        (keep (fn [level]
                (when (map? level)
                  (let [px (:p level)
                        sz (:s level)]
                    (when (and (some? px) (some? sz))
                      {:px px :sz sz})))))
        (or side-levels [])))

(defn- normalize-removals [side-indices]
  (into []
        (keep (fn [index]
                (when (int? index) index)))
        (or side-indices [])))

(defn parse-delta
  "Decoded-delta JSON text -> `{:coin :time :upserts [[bid ...] [ask ...]]
   :removals [[idx ...] [idx ...]]}`. Returns nil when the text is not a delta."
  [json-text]
  (when (and (string? json-text) (seq json-text))
    (let [raw (js->clj (js/JSON.parse json-text) :keywordize-keys true)]
      (when (map? raw)
        {:coin (:c raw)
         :time (:t raw)
         :upserts [(normalize-upserts (get-in raw [:l bid-side]))
                   (normalize-upserts (get-in raw [:l ask-side]))]
         :removals [(normalize-removals (get-in raw [:r bid-side]))
                    (normalize-removals (get-in raw [:r ask-side]))]}))))

;; ----------------------------------------------------------------- delta apply

(defn- remove-indices [levels indices]
  (if (empty? indices)
    levels
    (let [dropped (set indices)]
      (into []
            (keep-indexed (fn [index level]
                            (when-not (contains? dropped index) level)))
            levels))))

(defn- index-of-price [levels price]
  (first (keep-indexed (fn [index level]
                         (when (= price (policy/level-price level)) index))
                       levels)))

(defn- upsert-levels [levels upserts]
  (if (empty? upserts)
    levels
    (reduce (fn [acc {:keys [px sz]}]
              ;; Deltas carry no order count, so a touched level drops the stale
              ;; `:n` it may have arrived with rather than reporting a wrong one.
              (let [next-level {:px px :sz sz}]
                (if-let [index (index-of-price acc (policy/parse-number px))]
                  (assoc acc index next-level)
                  (conj acc next-level))))
            (vec levels)
            upserts)))

(defn- apply-side [levels removals upserts side]
  (-> (vec levels)
      (remove-indices removals)
      (upsert-levels upserts)
      (sort-side side)))

(defn apply-delta
  "Apply a parsed delta to canonically sorted `[bids asks]`.

   Removals run before upserts because `:r` indexes the pre-upsert ordering.
   Verified against `l2Book` snapshots at identical `time` values: remove-first
   matched 19/19 across BTC and ETH, upsert-first matched 2/19."
  [sides delta]
  (let [{:keys [upserts removals]} delta]
    [(apply-side (get sides bid-side)
                 (get removals bid-side)
                 (get upserts bid-side)
                 bid-side)
     (apply-side (get sides ask-side)
                 (get removals ask-side)
                 (get upserts ask-side)
                 ask-side)]))
