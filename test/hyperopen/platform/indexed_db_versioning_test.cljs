(ns hyperopen.platform.indexed-db-versioning-test
  "Opening the shared database when another branch on the same origin chose
  the same version number with other stores, or a higher version."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.test-support.async :as async-support]))

(defn- with-indexed-db-api
  [indexed-db-value f]
  (let [original (.-indexedDB js/globalThis)
        restore! (fn []
                   (indexed-db/clear-open-db-cache!)
                   (set! (.-indexedDB js/globalThis) original))]
    (set! (.-indexedDB js/globalThis) indexed-db-value)
    (indexed-db/clear-open-db-cache!)
    (.finally (f) restore!)))

(defn- versioned-indexed-db
  "A fake that keeps one database's version and store names, raises
  VersionError for a lower requested version and upgrades for a higher one."
  [db-state]
  (letfn [(make-db []
            (let [{:keys [version stores]} @db-state
                  db #js {}]
              (aset db "version" version)
              (aset db "close" (fn [] nil))
              (aset db "objectStoreNames" #js {:contains (fn [n] (contains? (:stores @db-state) n))})
              (aset db "createObjectStore" (fn [n] (swap! db-state update :stores conj n) #js {}))
              db))]
    #js {:open (fn [_db-name & [version]]
                 (let [request #js {}]
                   (js/setTimeout
                    (fn []
                      (let [current (:version @db-state)]
                        (cond
                          (and version (< version current))
                          (do (set! (.-error request) #js {:name "VersionError"})
                              ((.-onerror request) #js {}))

                          :else
                          (do (when (and version (> version current))
                                (swap! db-state assoc :version version)
                                ((.-onupgradeneeded request) #js {:target #js {:result (make-db)}}))
                              ((.-onsuccess request) #js {:target #js {:result (make-db)}})))))
                    0)
                   request))}))

(deftest open-db-adds-stores-another-branch-left-out-at-the-same-version-test
  (async done
    (let [db-state (atom {:version 8 :stores #{"alpha" "chat"}})
          fail! (async-support/unexpected-error done)]
      (with-indexed-db-api
        (versioned-indexed-db db-state)
        (fn []
          (-> (indexed-db/open-db! {:db-name "shared-db" :db-version 8 :store-names ["alpha" "journal"]})
              (.then (fn [db]
                       (is (= 9 (.-version db)))
                       (is (.contains (.-objectStoreNames db) "journal"))
                       (is (= #{"alpha" "chat" "journal"} (:stores @db-state))
                           "the other branch's stores are kept")
                       (done)))
              (.catch fail!)))))))

(deftest open-db-opens-a-database-another-branch-already-upgraded-test
  (async done
    (let [db-state (atom {:version 10 :stores #{"alpha" "journal"}})
          fail! (async-support/unexpected-error done)]
      (with-indexed-db-api
        (versioned-indexed-db db-state)
        (fn []
          (-> (indexed-db/open-db! {:db-name "shared-db" :db-version 8 :store-names ["alpha" "journal"]})
              (.then (fn [db]
                       (is (= 10 (.-version db)) "no needless upgrade")
                       (swap! db-state update :stores disj "journal")
                       (indexed-db/clear-open-db-cache!)
                       (indexed-db/open-db! {:db-name "shared-db" :db-version 8 :store-names ["alpha" "journal"]})))
              (.then (fn [db]
                       (is (= 11 (.-version db)) "a higher version missing our store is upgraded")
                       (is (contains? (:stores @db-state) "journal"))
                       (done)))
              (.catch fail!)))))))

(def ^:private pre-ledger-stores
  #{"asset-selector-markets-cache" "funding-history-cache" "chart-visible-range-cache" "vault-index-cache"
    "leaderboard-preferences" "leaderboard-cache" "agent-locked-session" "portfolio-optimizer"
    "vol-range-journal"})

(deftest app-db-adds-the-vol-range-ledger-store-test
  (async done
    (let [from-8 (atom {:version 8 :stores pre-ledger-stores})
          at-9 (atom {:version 9 :stores (conj pre-ledger-stores "other-branch-store")})
          fail! (async-support/unexpected-error done)]
      (is (= 9 indexed-db/app-db-version))
      (-> (with-indexed-db-api
            (versioned-indexed-db from-8)
            (fn []
              (.then (indexed-db/open-db!)
                     (fn [db]
                       (is (= 9 (.-version db)) "a version 8 database upgrades to 9")
                       (is (contains? (:stores @from-8) indexed-db/vol-range-ledger-store))
                       (is (contains? (:stores @from-8) indexed-db/vol-range-journal-store) "the journal is kept")))))
          (.then (fn [_]
                   (with-indexed-db-api
                     (versioned-indexed-db at-9)
                     (fn []
                       (.then (indexed-db/open-db!)
                              (fn [db]
                                (is (= 10 (.-version db))
                                    "a version 9 database without the ledger store is upgraded past it")
                                (is (contains? (:stores @at-9) indexed-db/vol-range-ledger-store))
                                (is (contains? (:stores @at-9) "other-branch-store"))))))))
          (.then (fn [_] (done)))
          (.catch fail!)))))
