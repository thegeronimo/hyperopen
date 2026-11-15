(ns hyperopen.funding.history-cache
  (:require [clojure.string :as str]
            [hyperopen.api.default :as api]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.platform :as platform]))

(def cache-retention-window-ms
  (* 30 24 60 60 1000))

(def cache-min-refresh-interval-ms
  (* 60 1000))

(def ^:private cache-version
  1)

(def ^:private cache-key-prefix
  "market-funding-history-cache:")

(defn- ->promise
  [result]
  (if (instance? js/Promise result)
    result
    (js/Promise.resolve result)))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-decimal
  [value]
  (cond
    (number? value)
    (when (finite-number? value)
      value)

    (string? value)
    (let [parsed (js/parseFloat value)]
      (when (finite-number? parsed)
        parsed))

    :else
    nil))

(defn- parse-ms
  [value]
  (when-let [parsed (parse-decimal value)]
    (js/Math.floor parsed)))

(defn normalize-coin
  [coin]
  (some-> coin
          str
          str/trim
          not-empty
          (as-> coin*
                (if (str/includes? coin* ":")
                  (let [[dex base] (str/split coin* #":" 2)]
                    (if (seq base)
                      (str (str/lower-case dex) ":" base)
                      coin*))
                  (str/upper-case coin*)))))

(defn cache-key
  [coin]
  (when-let [coin* (normalize-coin coin)]
    (str cache-key-prefix coin*)))

(defn normalize-market-funding-history-row
  [coin row]
  (when (map? row)
    (let [coin* (or (normalize-coin coin)
                    (normalize-coin (:coin row)))
          time-ms (or (parse-ms (:time-ms row))
                      (parse-ms (:time row)))
          funding-rate (parse-decimal (or (:funding-rate-raw row)
                                          (:fundingRate row)
                                          (:funding-rate row)))
          premium (parse-decimal (:premium row))]
      (when (and (seq coin*)
                 (number? time-ms)
                 (number? funding-rate))
        {:coin coin*
         :time-ms time-ms
         :time time-ms
         :funding-rate-raw funding-rate
         :fundingRate funding-rate
         :premium premium}))))

(defn normalize-market-funding-history-rows
  [coin rows]
  (->> (or rows [])
       (keep (fn [row]
               (normalize-market-funding-history-row coin row)))
       (sort-by :time-ms)
       vec))

(defn merge-market-funding-history-rows
  [coin existing incoming]
  (->> (concat (or existing []) (or incoming []))
       (reduce (fn [acc row]
                 (if-let [normalized (normalize-market-funding-history-row coin row)]
                   (assoc acc (:time-ms normalized) normalized)
                   acc))
               {})
       vals
       (sort-by :time-ms)
       vec))

(defn trim-market-funding-history-rows
  ([rows now-ms]
   (trim-market-funding-history-rows rows now-ms cache-retention-window-ms))
  ([rows now-ms retention-window-ms]
   (let [now-ms* (or (parse-ms now-ms) 0)
         retention-ms* (max 0 (or (parse-ms retention-window-ms)
                                  cache-retention-window-ms))
         start-ms (max 0 (- now-ms* retention-ms*))]
     (->> (or rows [])
          (keep (fn [row]
                  (when-let [time-ms (parse-ms (:time-ms row))]
                    (when (and (<= start-ms time-ms)
                               (<= time-ms now-ms*))
                      row))))
          (sort-by :time-ms)
          vec))))

(defn- last-row-time-ms
  [rows]
  (some->> rows
           seq
           last
           :time-ms
           parse-ms))

(defn- empty-cache-snapshot
  [coin now-ms]
  {:version cache-version
   :coin coin
   :rows []
   :last-row-time-ms nil
   :last-sync-ms 0})

(defn normalize-market-funding-history-cache
  [coin raw-cache now-ms retention-window-ms]
  (let [coin* (normalize-coin coin)
        now-ms* (or (parse-ms now-ms) 0)
        normalized-rows (normalize-market-funding-history-rows coin* (:rows raw-cache))
        trimmed-rows (trim-market-funding-history-rows normalized-rows
                                                       now-ms*
                                                       retention-window-ms)
        last-sync-ms (or (parse-ms (:last-sync-ms raw-cache)) 0)]
    {:version cache-version
     :coin coin*
     :rows trimmed-rows
     :last-row-time-ms (last-row-time-ms trimmed-rows)
     :last-sync-ms last-sync-ms}))

(defn- normalize-market-funding-history-cache-record
  [coin raw-record now-ms retention-window-ms]
  (when (map? raw-record)
    {:coin (normalize-coin coin)
     :saved-at-ms (or (parse-ms (:saved-at-ms raw-record)) 0)
     :snapshot (normalize-market-funding-history-cache coin
                                                       (or (:snapshot raw-record)
                                                           raw-record)
                                                       now-ms
                                                       retention-window-ms)}))

(defn- build-market-funding-history-cache-record
  [coin snapshot now-ms retention-window-ms]
  {:coin coin
   :saved-at-ms now-ms
   :snapshot (normalize-market-funding-history-cache coin
                                                     snapshot
                                                     now-ms
                                                     retention-window-ms)})

(defn- newer-cache-record
  [a b]
  (cond
    (and a b)
    (if (>= (:saved-at-ms a 0)
            (:saved-at-ms b 0))
      a
      b)

    a
    a

    b
    b

    :else
    nil))

(defn- load-market-funding-history-cache-record-from-local-storage
  [coin local-storage-get-fn now-ms retention-window-ms]
  (when-let [key (cache-key coin)]
    (try
      (let [raw (local-storage-get-fn key)]
        (when (seq raw)
          (normalize-market-funding-history-cache-record
           coin
           (js->clj (js/JSON.parse raw) :keywordize-keys true)
           now-ms
           retention-window-ms)))
      (catch :default _
        nil))))

(defn- persist-market-funding-history-cache-record-to-local-storage!
  [coin record local-storage-set-fn]
  (when-let [key (cache-key coin)]
    (try
      (local-storage-set-fn key (js/JSON.stringify (clj->js record)))
      true
      (catch :default e
        (js/console.warn "Failed to persist market funding history cache to localStorage:" e)
        false))))

(defn- load-market-funding-history-cache-record-from-indexed-db!
  [coin now-ms retention-window-ms]
  (-> (indexed-db/get-json! indexed-db/funding-history-store coin)
      (.then (fn [record]
               (when record
                 (normalize-market-funding-history-cache-record coin
                                                                record
                                                                now-ms
                                                                retention-window-ms))))))

(defn- persist-market-funding-history-cache-record-to-indexed-db!
  [coin record]
  (indexed-db/put-json! indexed-db/funding-history-store coin record))

(defn load-market-funding-history-cache
  ([coin]
   (load-market-funding-history-cache coin {}))
  ([coin {:keys [local-storage-get-fn
                 load-indexed-db-fn
                 persist-indexed-db-fn
                 now-ms-fn
                 retention-window-ms]
          :or {local-storage-get-fn platform/local-storage-get
               load-indexed-db-fn load-market-funding-history-cache-record-from-indexed-db!
               persist-indexed-db-fn persist-market-funding-history-cache-record-to-indexed-db!
               now-ms-fn platform/now-ms
               retention-window-ms cache-retention-window-ms}}]
   (let [coin* (normalize-coin coin)
         now-ms* (now-ms-fn)]
     (if-not coin*
       (js/Promise.resolve nil)
       (let [local-record (load-market-funding-history-cache-record-from-local-storage
                           coin*
                           local-storage-get-fn
                           now-ms*
                           retention-window-ms)]
         (-> (->promise (load-indexed-db-fn coin* now-ms* retention-window-ms))
             (.catch (fn [error]
                       (js/console.warn "Failed to load market funding history cache from IndexedDB:" error)
                       nil))
             (.then (fn [indexed-db-record]
                      (let [selected-record (newer-cache-record indexed-db-record local-record)
                            snapshot (or (:snapshot selected-record)
                                         (empty-cache-snapshot coin* now-ms*))]
                        (when (and local-record
                                   (not= selected-record indexed-db-record))
                          (-> (->promise (persist-indexed-db-fn coin* local-record))
                              (.catch (fn [_]
                                        nil))))
                        snapshot)))))))))

(defn persist-market-funding-history-cache!
  ([coin snapshot]
   (persist-market-funding-history-cache! coin snapshot {}))
  ([coin snapshot {:keys [local-storage-set-fn
                          persist-indexed-db-fn
                          now-ms-fn]
                   :or {local-storage-set-fn platform/local-storage-set!
                        persist-indexed-db-fn persist-market-funding-history-cache-record-to-indexed-db!
                        now-ms-fn platform/now-ms}}]
   (when-let [coin* (normalize-coin coin)]
     (let [record (build-market-funding-history-cache-record coin*
                                                             snapshot
                                                             (now-ms-fn)
                                                             cache-retention-window-ms)]
       (-> (->promise (persist-indexed-db-fn coin* record))
           (.then (fn [persisted?]
                    (when-not persisted?
                      (persist-market-funding-history-cache-record-to-local-storage!
                       coin*
                       record
                       local-storage-set-fn))
                    persisted?))
           (.catch (fn [e]
                     (js/console.warn "Failed to persist market funding history cache to IndexedDB:" e)
                     (persist-market-funding-history-cache-record-to-local-storage!
                      coin*
                      record
                      local-storage-set-fn)
                     false)))))))

(defn clear-market-funding-history-cache!
  ([coin]
   (clear-market-funding-history-cache! coin {}))
  ([coin {:keys [local-storage-remove-fn
                 delete-indexed-db-fn]
          :or {local-storage-remove-fn platform/local-storage-remove!
               delete-indexed-db-fn indexed-db/delete-key!}}]
   (when-let [key (cache-key coin)]
     (-> (->promise (delete-indexed-db-fn indexed-db/funding-history-store
                                          (normalize-coin coin)))
         (.then (fn [deleted?]
                  (when-not deleted?
                    (local-storage-remove-fn key))
                  deleted?))
         (.catch (fn [error]
                   (js/console.warn "Failed to clear market funding history cache from IndexedDB:" error)
                   (local-storage-remove-fn key)
                   false))))))

(defn rows-for-window
  [rows now-ms window-ms]
  (let [now-ms* (or (parse-ms now-ms) 0)
        window-ms* (max 0 (or (parse-ms window-ms) 0))
        start-ms (max 0 (- now-ms* window-ms*))]
    (->> (or rows [])
         (keep (fn [row]
                 (when-let [time-ms (parse-ms (:time-ms row))]
                   (when (and (<= start-ms time-ms)
                              (<= time-ms now-ms*))
                     row))))
         (sort-by :time-ms)
         vec)))

(defn- default-load-cache
  [coin {:keys [now-ms-fn retention-window-ms]}]
  (load-market-funding-history-cache coin
                                     {:now-ms-fn now-ms-fn
                                      :retention-window-ms retention-window-ms}))

(defn- default-persist-cache!
  [coin snapshot]
  (persist-market-funding-history-cache! coin snapshot))

(defn sync-market-funding-history-cache!
  ([coin]
   (sync-market-funding-history-cache! coin {}))
  ([coin {:keys [now-ms-fn
                 request-market-funding-history!
                 load-cache-fn
                 persist-cache-fn
                 retention-window-ms
                 min-refresh-interval-ms
                 force?
                 priority]
          :or {now-ms-fn platform/now-ms
               request-market-funding-history! api/request-market-funding-history!
               load-cache-fn default-load-cache
               persist-cache-fn default-persist-cache!
               retention-window-ms cache-retention-window-ms
               min-refresh-interval-ms cache-min-refresh-interval-ms
               force? false
               priority :high}}]
   (let [coin* (normalize-coin coin)
         now-ms* (or (parse-ms (now-ms-fn)) 0)
         retention-window-ms* (max 0 (or (parse-ms retention-window-ms)
                                         cache-retention-window-ms))
         min-refresh-interval-ms* (max 0 (or (parse-ms min-refresh-interval-ms)
                                             cache-min-refresh-interval-ms))]
     (if-not coin*
       (js/Promise.resolve {:coin nil
                            :rows []
                            :source :invalid-coin
                            :fetched-count 0
                            :start-time-ms nil
                            :end-time-ms nil
                            :last-sync-ms now-ms*})
       (.then (->promise (load-cache-fn coin* {:now-ms-fn now-ms-fn
                                               :retention-window-ms retention-window-ms}))
              (fn [loaded-cache]
                (let [cached (normalize-market-funding-history-cache coin*
                                                                     (or loaded-cache {})
                                                                     now-ms*
                                                                     retention-window-ms*)
                      retention-start-ms (max 0 (- now-ms* retention-window-ms*))
                      previous-last-time-ms (:last-row-time-ms cached)
                      has-cached-history? (or (number? previous-last-time-ms)
                                              (seq (:rows cached)))
                      start-time-ms (if (number? previous-last-time-ms)
                                      (max retention-start-ms (inc previous-last-time-ms))
                                      retention-start-ms)
                      end-time-ms now-ms*
                      recent-sync? (and has-cached-history?
                                        (not force?)
                                        (number? (:last-sync-ms cached))
                                        (< (- now-ms* (:last-sync-ms cached))
                                           min-refresh-interval-ms*))
                      no-missing-delta? (> start-time-ms end-time-ms)]
                  (cond
                    recent-sync?
                    {:coin coin*
                     :rows (:rows cached)
                     :source :cache
                     :reason :recent-sync
                     :fetched-count 0
                     :start-time-ms start-time-ms
                     :end-time-ms end-time-ms
                     :last-sync-ms (:last-sync-ms cached)}

                    no-missing-delta?
                    (let [snapshot* (assoc cached :last-sync-ms now-ms*)]
                      (.then (->promise (persist-cache-fn coin* snapshot*))
                             (fn [_]
                               {:coin coin*
                                :rows (:rows snapshot*)
                                :source :cache
                                :reason :up-to-date
                                :fetched-count 0
                                :start-time-ms start-time-ms
                                :end-time-ms end-time-ms
                                :last-sync-ms now-ms*})))

                    :else
                    (.then (->promise (request-market-funding-history! coin*
                                                                        {:start-time-ms start-time-ms
                                                                         :end-time-ms end-time-ms
                                                                         :priority priority}))
                           (fn [rows]
                             (let [incoming (normalize-market-funding-history-rows coin* rows)
                                   merged (merge-market-funding-history-rows coin*
                                                                             (:rows cached)
                                                                             incoming)
                                   trimmed (trim-market-funding-history-rows merged
                                                                            now-ms*
                                                                            retention-window-ms*)
                                   snapshot* {:version cache-version
                                              :coin coin*
                                              :rows trimmed
                                              :last-row-time-ms (last-row-time-ms trimmed)
                                              :last-sync-ms now-ms*}]
                               (.then (->promise (persist-cache-fn coin* snapshot*))
                                      (fn [_]
                                        {:coin coin*
                                         :rows trimmed
                                         :source :network
                                         :fetched-count (count incoming)
                                         :start-time-ms start-time-ms
                                         :end-time-ms end-time-ms
                                         :last-sync-ms now-ms*})))))))))))))
