(ns hyperopen.platform.indexed-db)

(def app-db-name
  "hyperopen-persistence")

(def app-db-version
  8)

(def asset-selector-markets-store
  "asset-selector-markets-cache")

(def funding-history-store
  "funding-history-cache")

(def chart-visible-range-store
  "chart-visible-range-cache")

(def vault-index-store
  "vault-index-cache")

(def leaderboard-preferences-store
  "leaderboard-preferences")

(def leaderboard-cache-store
  "leaderboard-cache")

(def agent-locked-session-store
  "agent-locked-session")

(def portfolio-optimizer-store
  "portfolio-optimizer")

(def vol-range-journal-store
  "Vol Range intents, send attempts and outcomes (HO-SPEC-014 section 7.4).
  Written only through `put-many-committed!`."
  "vol-range-journal")

(def ^:private app-store-names
  [asset-selector-markets-store
   funding-history-store
   chart-visible-range-store
   vault-index-store
   leaderboard-preferences-store
   leaderboard-cache-store
   agent-locked-session-store
   portfolio-optimizer-store
   vol-range-journal-store])

(defonce ^:private open-db-cache (atom {}))

(defn clear-open-db-cache!
  []
  (reset! open-db-cache {}))

(defn indexed-db-supported?
  []
  (some? (.-indexedDB js/globalThis)))

(defn- request-error
  [request fallback-message]
  (or (some-> request .-error)
      (js/Error. fallback-message)))

(defn- create-object-stores!
  [db store-names]
  (doseq [store-name store-names]
    (when-not (.contains (.-objectStoreNames db) store-name)
      (.createObjectStore db store-name))))

(defn- close-db!
  [db]
  (when (and db (fn? (.-close db)))
    (.close db)))

(defn- close-stale-open-dbs!
  [db-name db-version]
  (let [stale-entries (->> @open-db-cache
                           (filter (fn [[[cached-db-name cached-db-version _store-names]
                                         _db-promise]]
                                     (and (= db-name cached-db-name)
                                          (not= db-version cached-db-version)))))]
    (doseq [[cache-key _db-promise] stale-entries]
      (swap! open-db-cache dissoc cache-key))
    (if (seq stale-entries)
      (js/Promise.all
       (clj->js
        (map (fn [[_cache-key db-promise]]
               (-> db-promise
                   (.then (fn [db]
                            (close-db! db)
                            nil))
                   (.catch (fn [_] nil))))
             stale-entries)))
      (js/Promise.resolve nil))))

(defn open-db!
  ([]
   (open-db! {}))
  ([{:keys [db-name db-version store-names]
     :or {db-name app-db-name
          db-version app-db-version
          store-names app-store-names}}]
   (let [store-names* (vec store-names)
         cache-key [db-name db-version store-names*]]
     (if-let [cached (get @open-db-cache cache-key)]
       cached
       (letfn [(open-request! []
                 (if-not (indexed-db-supported?)
                   (js/Promise.resolve nil)
                   (js/Promise.
                    (fn [resolve reject]
                      (try
                        (let [request (.open ^js (.-indexedDB js/globalThis)
                                             db-name
                                             db-version)]
                          (set! (.-onupgradeneeded request)
                                (fn [event]
                                  (create-object-stores! (.-result (.-target event))
                                                         store-names*)))
                          (set! (.-onsuccess request)
                                (fn [event]
                                  (let [db (.-result (.-target event))]
                                    (set! (.-onversionchange db)
                                          (fn [_]
                                            (swap! open-db-cache dissoc cache-key)
                                            (close-db! db)))
                                    (resolve db))))
                          (set! (.-onerror request)
                                (fn [_]
                                  (reject (request-error request
                                                         (str "IndexedDB open failed for " db-name)))))
                          (set! (.-onblocked request)
                                (fn [_]
                                  (reject (js/Error.
                                           (str "IndexedDB open blocked for " db-name)))))
                          nil)
                        (catch :default e
                          (reject e)))))))]
         (let [open-promise (-> (close-stale-open-dbs! db-name db-version)
                                (.then open-request!))]
         (swap! open-db-cache assoc
                cache-key
                (.catch open-promise
                        (fn [error]
                          (swap! open-db-cache dissoc cache-key)
                          (js/Promise.reject error))))
         (get @open-db-cache cache-key)))))))

(defn- transact-request!
  [db store-name mode request-fn on-success]
  (js/Promise.
   (fn [resolve reject]
     (try
       (if-not (.contains (.-objectStoreNames db) store-name)
         (resolve nil)
         (let [tx (.transaction db (clj->js [store-name]) mode)
               store (.objectStore tx store-name)
               request (request-fn store)]
           (set! (.-onabort tx)
                 (fn [_]
                   (reject (or (some-> tx .-error)
                               (js/Error.
                                (str "IndexedDB transaction aborted for " store-name))))))
           (set! (.-onerror tx)
                 (fn [_]
                   (reject (or (some-> tx .-error)
                               (js/Error.
                                (str "IndexedDB transaction failed for " store-name))))))
           (set! (.-onsuccess request)
                 (fn [event]
                   (resolve (on-success (.-result (.-target event))))))
           (set! (.-onerror request)
                 (fn [_]
                   (reject (request-error request
                                          (str "IndexedDB request failed for " store-name)))))))
       (catch :default e
         (reject e))))))

(defn get-json!
  ([store-name key]
   (get-json! store-name key {}))
  ([store-name key opts]
   (-> (open-db! opts)
       (.then (fn [db]
                (if-not db
                  nil
                  (transact-request!
                   db
                   store-name
                   "readonly"
                   (fn [store]
                     (.get ^js store key))
                   (fn [result]
                     (when (some? result)
                       (js->clj result :keywordize-keys true))))))))))

(defn get-all-json!
  ([store-name]
   (get-all-json! store-name {}))
  ([store-name opts]
   (-> (open-db! opts)
       (.then (fn [db]
                (if-not db
                  []
                  (transact-request!
                   db
                   store-name
                   "readonly"
                   (fn [store]
                     (.getAll ^js store))
                   (fn [result]
                     (if (some? result)
                       (js->clj result :keywordize-keys true)
                       [])))))))))

(defn put-json!
  ([store-name key value]
   (put-json! store-name key value {}))
  ([store-name key value opts]
   (-> (open-db! opts)
       (.then (fn [db]
                (if-not db
                  false
                  (transact-request!
                   db
                   store-name
                   "readwrite"
                   (fn [store]
                     (.put ^js store (clj->js value) key))
                   (fn [_]
                     true))))))))

(defn put-many-committed!
  "Write every [key value] in `entries` to `store-name` in ONE readwrite
  transaction and resolve true only on `transaction.oncomplete`.

  Unlike `put-json!`, request-level success is never treated as durability: a
  transaction that later aborts (quota, constraint, a closing connection)
  rejects, and so does missing IndexedDB, a missing store or a blocked open.
  Either all entries are written or none are."
  ([store-name entries]
   (put-many-committed! store-name entries {}))
  ([store-name entries opts]
   (if-not (indexed-db-supported?)
     (js/Promise.reject (js/Error. "IndexedDB is unavailable; nothing was written."))
     (-> (open-db! opts)
         (.then
          (fn [db]
            (js/Promise.
             (fn [resolve reject]
               (try
                 (cond
                   (nil? db)
                   (reject (js/Error. "IndexedDB is unavailable; nothing was written."))

                   (not (.contains (.-objectStoreNames db) store-name))
                   (reject (js/Error. (str "IndexedDB store " store-name " is missing; nothing was written.")))

                   :else
                   (let [;; "strict": oncomplete only after the data is flushed to disk
                         ;; (browsers default to relaxed durability).
                         tx (.transaction db (clj->js [store-name]) "readwrite" #js {:durability "strict"})
                         store (.objectStore tx store-name)]
                     (set! (.-oncomplete tx) (fn [_] (resolve true)))
                     ;; A request error is NOT cancelled (no preventDefault), so the
                     ;; transaction aborts as a whole and onabort rejects.
                     (set! (.-onabort tx)
                           (fn [_]
                             (reject (or (some-> tx .-error)
                                         (js/Error. (str "IndexedDB transaction aborted for " store-name))))))
                     (try
                       (doseq [[k v] entries]
                         (.put ^js store (clj->js v) k))
                       (catch :default e
                         ;; A synchronous put failure (bad key, uncloneable value)
                         ;; must not let earlier puts auto-commit.
                         (try (.abort tx) (catch :default _ nil))
                         (reject e)))))
                 (catch :default e
                   (reject e)))))))))))

(defn get-all-json-strict!
  "`get-all-json!` that REJECTS when IndexedDB or the store is unavailable,
  instead of returning an empty vector. For journals, an unreadable store must
  block new work rather than look empty."
  ([store-name]
   (get-all-json-strict! store-name {}))
  ([store-name opts]
   (if-not (indexed-db-supported?)
     (js/Promise.reject (js/Error. "IndexedDB is unavailable."))
     (-> (open-db! opts)
         (.then (fn [db]
                  (cond
                    (nil? db) (js/Promise.reject (js/Error. "IndexedDB is unavailable."))
                    (not (.contains (.-objectStoreNames db) store-name))
                    (js/Promise.reject (js/Error. (str "IndexedDB store " store-name " is missing.")))
                    :else (transact-request! db store-name "readonly"
                                             (fn [store] (.getAll ^js store))
                                             (fn [result]
                                               (if (some? result)
                                                 (js->clj result :keywordize-keys true)
                                                 []))))))))))

(defn delete-key!
  ([store-name key]
   (delete-key! store-name key {}))
  ([store-name key opts]
   (-> (open-db! opts)
       (.then (fn [db]
                (if-not db
                  false
                  (transact-request!
                   db
                   store-name
                   "readwrite"
                   (fn [store]
                     (.delete ^js store key))
                   (fn [_]
                     true))))))))
