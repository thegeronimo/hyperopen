(ns hyperopen.platform.indexed-db)

(def app-db-name
  "hyperopen-persistence")

(def app-db-version
  6)

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

(def ^:private app-store-names
  [asset-selector-markets-store
   funding-history-store
   chart-visible-range-store
   vault-index-store
   leaderboard-preferences-store
   leaderboard-cache-store
   agent-locked-session-store
   portfolio-optimizer-store])

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
       (let [open-promise
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
                              (resolve (.-result (.-target event)))))
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
                      (reject e))))))]
         (swap! open-db-cache assoc
                cache-key
                (.catch open-promise
                        (fn [error]
                          (swap! open-db-cache dissoc cache-key)
                          (js/Promise.reject error))))
         (get @open-db-cache cache-key))))))

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
