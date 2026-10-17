(ns hyperopen.vaults.infrastructure.screener-cache
  "IndexedDB persistence for vault-screener histories and saved views.

  One record per vault (`vault-screener:<address>`) holds the vault's
  day/week/month/all-time portfolio, and one small index record maps each
  cached address to its `:saved-at-ms` so the builder can estimate a run
  without reading every vault record."
  (:require [hyperopen.platform.indexed-db :as indexed-db]))

(def cache-version
  1)

(def index-key
  "vault-screener:index")

(defn record-key
  [vault-address]
  (str "vault-screener:" vault-address))

(defn- normalize-index
  [record]
  (if (and (map? record)
           (= cache-version (:version record))
           (map? (:saved-at-by-address record)))
    (into {}
          (keep (fn [[address saved-at-ms]]
                  (when (number? saved-at-ms)
                    [(name address) saved-at-ms])))
          (:saved-at-by-address record))
    {}))

(defn load-index!
  []
  (-> (indexed-db/get-json! indexed-db/vault-index-store index-key)
      (.then normalize-index (fn [_error] {}))))

(defn persist-index!
  [saved-at-by-address now-ms]
  (-> (indexed-db/put-json! indexed-db/vault-index-store
                            index-key
                            {:version cache-version
                             :saved-at-ms now-ms
                             :saved-at-by-address saved-at-by-address})
      (.catch (fn [_error] false))))

(defn- normalize-record
  [record]
  (when (and (map? record)
             (= cache-version (:version record))
             (number? (:saved-at-ms record))
             (map? (:portfolio record)))
    (cond-> {:saved-at-ms (:saved-at-ms record)
             :portfolio (:portfolio record)}
      (boolean? (:deposits-closed? record)) (assoc :deposits-closed? (:deposits-closed? record)))))

(defn load-record!
  [vault-address]
  (-> (indexed-db/get-json! indexed-db/vault-index-store (record-key vault-address))
      (.then normalize-record (fn [_error] nil))))

(defn persist-record!
  [vault-address {:keys [saved-at-ms portfolio deposits-closed?]}]
  (-> (indexed-db/put-json! indexed-db/vault-index-store
                            (record-key vault-address)
                            {:version cache-version
                             :saved-at-ms saved-at-ms
                             :portfolio portfolio
                             :deposits-closed? (true? deposits-closed?)})
      (.catch (fn [_error] false))))

(def saved-views-key
  "vault-saved-views")

(defn load-saved-views!
  "Resolves the stored saved views (raw; normalize with
  `hyperopen.vaults.screener.saved-views/normalize-views`), or []."
  []
  (-> (indexed-db/get-json! indexed-db/vault-index-store saved-views-key)
      (.then (fn [record]
               (if (and (map? record) (= cache-version (:version record)))
                 (or (:views record) [])
                 []))
             (fn [_error] []))))

(defn persist-saved-views!
  [views now-ms]
  (-> (indexed-db/put-json! indexed-db/vault-index-store
                            saved-views-key
                            {:version cache-version
                             :saved-at-ms now-ms
                             :views (vec views)})
      (.catch (fn [_error] false))))
