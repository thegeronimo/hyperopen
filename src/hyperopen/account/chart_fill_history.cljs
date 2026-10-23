(ns hyperopen.account.chart-fill-history
  "The price chart's own copy of the effective account's fills, kept at
  `[:chart-trades]` so the chart can show history that the shared
  `[:orders :fills]` vector loses: that vector is capped at 200 rows the moment
  a live websocket fill arrives, and the REST `userFills` snapshot stops at the
  newest 2000 fills.

  State shape:
    {:address         \"0x…\"  ; account the rows belong to
     :fills           [...]    ; newest first, deduplicated, <= max-rows
     :covered-from-ms n|nil    ; every fill at or after this time is loaded
     :rest-newest-ms  n|nil    ; newest time a REST (aggregated) page covers
     :window-ms       n        ; width of the next older window to fetch
     :exhausted?      bool     ; nothing older can be fetched
     :loading-end-ms  n|nil}   ; endTime of the in-flight older window

  Hyperliquid serves at most the newest 10000 fills per user and at most 2000
  rows per `userFills` / `userFillsByTime` response. `userFillsByTime`
  returns the OLDEST fills at or after `startTime`, so older history is
  fetched as time windows ending at `covered-from-ms`, each paged forward
  until complete (see chart-fill-history-effects). Coverage, not the oldest
  fill time, decides what is missing: a window can legitimately be empty.

  REST `userFills` rows are aggregated by time (one row per crossing order)
  while websocket rows are per trade. Mixing both for the same period would
  double-count, so a REST page is authoritative for [its oldest, its newest]
  and websocket rows inside that span are dropped."
  (:require [clojure.string :as str]))

(def max-rows 10000)
(def page-size 2000)
(def ^:private hour-ms (* 60 60 1000))
(def min-window-ms hour-ms)
(def max-window-ms (* 90 24 hour-ms))
;; Hyperliquid mainnet trading began in 2023: nothing older can exist
(def history-floor-ms 1672531200000)
;; aggregated pages may hold fewer than 2000 rows while more exist, so only a
;; clearly short REST page proves the history complete
(def ^:private complete-page-max-rows (quot page-size 2))

(defn- parse-ms
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/Number value)
            :else js/NaN)]
    (when (and (number? n) (js/isFinite n)) n)))

(defn fill-time-ms
  [fill]
  (or (parse-ms (:time fill))
      (parse-ms (:timestamp fill))
      (parse-ms (:ts fill))
      (parse-ms (:t fill))))

(defn- fill-key
  "Trade id when present; otherwise the raw fields that make a fill unique.
  Never the order id alone: one order fills in several trades."
  [fill]
  (let [tid (:tid fill)]
    (if (and (some? tid) (not= "" (str tid)))
      (str "tid:" tid)
      [(:oid fill) (:coin fill) (fill-time-ms fill) (:side fill) (:px fill) (:sz fill)
       (:startPosition fill) (:hash fill)])))

(defn normalize-address
  [address]
  (some-> address str str/trim str/lower-case not-empty))

(defn merge-fills
  "Union of `existing` and `incoming` fill rows, deduplicated, newest first,
  capped at `max-rows`."
  [existing incoming]
  (->> (concat incoming existing)
       (filter map?)
       (reduce (fn [{:keys [seen rows] :as acc} fill]
                 (let [k (fill-key fill)]
                   (if (contains? seen k)
                     acc
                     {:seen (conj seen k) :rows (conj rows fill)})))
               {:seen #{} :rows []})
       :rows
       (sort-by #(- (or (fill-time-ms %) 0)))
       (take max-rows)
       vec))

(defn- oldest-row-ms
  [rows]
  (some->> rows (keep fill-time-ms) seq (apply min)))

(defn history-for
  "The chart history when it belongs to `address`, else nil."
  [state address]
  (let [history (:chart-trades state)]
    (when (and (map? history)
               (some? (normalize-address address))
               (= (normalize-address address) (:address history)))
      history)))

(defn fills-for
  [state address]
  (:fills (history-for state address)))

(defn- initial-window-ms
  "Size the first older window like the span the newest page covered: an
  account that filled 2000 times in an hour gets hour-wide windows."
  [rows]
  (let [times (keep fill-time-ms rows)]
    (if (seq times)
      (-> (- (apply max times) (apply min times))
          (max min-window-ms)
          (min max-window-ms))
      (* 24 hour-ms))))

(defn- newest-row-ms
  [rows]
  (some->> rows (keep fill-time-ms) seq (apply max)))

(defn- outside-rest-span
  "Websocket rows not already represented by an aggregated REST page."
  [history rows]
  (let [rest-newest (:rest-newest-ms history)]
    (if (number? rest-newest)
      (filterv #(> (or (fill-time-ms %) 0) rest-newest) rows)
      (vec rows))))

(defn apply-snapshot
  "A newest-first snapshot for `address`, merged into what is loaded. It is
  contiguous from its oldest row to now. `:rest-page?` marks a REST
  `userFills` page: it replaces whatever is loaded inside its span, and one
  clearly shorter than a page holds the whole history. Websocket snapshots
  carry no such guarantee."
  ([state address rows]
   (apply-snapshot state address rows {}))
  ([state address rows {:keys [rest-page?]}]
   (let [address* (normalize-address address)
         rows* (vec (filter map? rows))
         complete? (and rest-page? (< (count rows*) complete-page-max-rows))]
     (if-not address*
       state
       (let [current (history-for state address*)
             snapshot-from (oldest-row-ms rows*)
             existing (if (and rest-page? (number? snapshot-from))
                        (filterv #(< (or (fill-time-ms %) 0) snapshot-from) (:fills current))
                        (:fills current))
             incoming (if rest-page? rows* (outside-rest-span current rows*))
             ;; a cut-off page may end inside one millisecond: keep that
             ;; millisecond uncovered so the next window fetches the rest of it
             snapshot-covered (when (number? snapshot-from) (inc snapshot-from))
             covered-from (cond
                            complete? 0
                            (number? (:covered-from-ms current))
                            (min (:covered-from-ms current) (or snapshot-covered js/Infinity))
                            :else snapshot-covered)]
         (assoc state :chart-trades
                {:address address*
                 :fills (merge-fills existing incoming)
                 :covered-from-ms covered-from
                 :rest-newest-ms (if rest-page?
                                   (max (or (:rest-newest-ms current) 0) (or (newest-row-ms rows*) 0))
                                   (:rest-newest-ms current))
                 :window-ms (or (:window-ms current) (initial-window-ms rows*))
                 :exhausted? (boolean (or (:exhausted? current) complete?))
                 :loading-end-ms (:loading-end-ms current)}))))))

(defn apply-live
  "Newly streamed fills for `address`; ignored until a snapshot established
  the history for that address."
  [state address rows]
  (if-let [current (history-for state address)]
    (assoc-in state [:chart-trades :fills]
              (merge-fills (:fills current) (outside-rest-span current (filter map? rows))))
    state))

(defn next-older-end-ms
  "endTime for the next older window when the chart shows candles older than
  the covered history, else nil."
  [state address oldest-candle-ms]
  (when-let [history (history-for state address)]
    (let [covered-from (:covered-from-ms history)]
      (when (and (number? oldest-candle-ms)
                 (number? covered-from)
                 (pos? covered-from)
                 (not (:exhausted? history))
                 (nil? (:loading-end-ms history))
                 (< (count (:fills history)) max-rows)
                 (< oldest-candle-ms covered-from))
        (dec covered-from)))))

(defn begin-older-page
  [state address end-ms]
  (if (history-for state address)
    (assoc-in state [:chart-trades :loading-end-ms] end-ms)
    state))

(defn apply-older-window
  "Merge the complete window [start-ms, end-ms], but only if it answers the
  request in flight for the same account (a late response after an account
  switch is dropped). `:truncated?` means paging stopped before the window
  was complete, so coverage cannot be extended and paging ends."
  [state address end-ms start-ms rows {:keys [truncated?]}]
  (let [history (history-for state address)]
    (if-not (and history (= end-ms (:loading-end-ms history)))
      state
      (let [rows* (vec (filter map? rows))
            ;; the window is authoritative for [start-ms, end-ms]: its end
            ;; millisecond may hold an aggregated REST row it now returns per trade
            kept (filterv #(let [t (or (fill-time-ms %) 0)] (or (< t start-ms) (> t end-ms)))
                          (:fills history))
            merged (merge-fills kept rows*)
            window (or (:window-ms history) (* 24 hour-ms))
            ;; an empty window widens the next one; a busy one keeps its size
            next-window (if (empty? rows*) (* 2 window) window)
            covered (:covered-from-ms history)]
        (assoc state :chart-trades
               (assoc history
                      :fills merged
                      :loading-end-ms nil
                      ;; coverage only grows: a complete snapshot may have landed
                      ;; while this window was in flight
                      :covered-from-ms (cond
                                         truncated? covered
                                         (number? covered) (min covered start-ms)
                                         :else start-ms)
                      :window-ms (min next-window max-window-ms)
                      :exhausted? (boolean (or (:exhausted? history)
                                               truncated?
                                               (>= (count merged) max-rows)
                                               (<= start-ms history-floor-ms)))))))))

(defn fail-older-page
  [state address end-ms]
  (let [history (history-for state address)]
    (if (and history (= end-ms (:loading-end-ms history)))
      ;; stop paging after an error rather than retrying every frame
      (assoc state :chart-trades (assoc history :loading-end-ms nil :exhausted? true))
      state)))
