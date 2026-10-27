(ns hyperopen.vol-range.infrastructure.journal
  "Committed Vol Range journal writes through the shared IndexedDB boundary
  (HO-SPEC-014 v2.1 section 7.4).

  `commit!` resolves only when the IndexedDB transaction COMPLETES and rejects
  on abort, error, quota failure, blocked open or missing IndexedDB. Callers
  must not reserve a nonce, sign or send unless it resolved.

  Each record is stored as {:record-id :schema-version :saved-at-ms :edn} where
  `:edn` is the record printed as EDN: keywords, vectors (including keyword
  scope keys) and string-keyed maps all round-trip losslessly, so recovery after
  reload sees exactly what was written."
  (:require [cljs.reader :as reader]
            [hyperopen.platform.indexed-db :as idb]
            [hyperopen.vol-range.application.journal-records :as records]))

(defn- encode [record]
  {:record-id (:record-id record)
   :schema-version (:schema-version record)
   :saved-at-ms (:saved-at-ms record)
   ;; Never let REPL/dev print limits truncate a record into a lossy "...".
   :edn (binding [*print-length* nil *print-level* nil *print-meta* false]
          (pr-str record))})

(defn- decode
  "The stored record, or nil for a row that cannot be read back (it is
  reported, never silently treated as resolved)."
  [row]
  (try
    (let [r (reader/read-string (:edn row))]
      ;; Corrupt rows and rows from another schema version are unreadable,
      ;; never quietly treated as known records.
      (when (records/record-valid? r) r))
    (catch :default _ nil)))

(defn commit!
  "Commit `records` atomically in one transaction. Resolves true on completion;
  rejects (without writing anything) when any record is invalid."
  ([recs] (commit! recs {}))
  ([recs opts]
   (if-let [bad (first (remove records/record-valid? recs))]
     (js/Promise.reject (ex-info "Refusing to journal an invalid record" {:record-id (:record-id bad)}))
     (idb/put-many-committed! idb/vol-range-journal-store
                              (mapv (fn [r] [(:record-id r) (encode r)]) recs)
                              opts))))

(defn load-all!
  "{:records [...] :unreadable [record-id ...]}. Rejects when the store cannot
  be read: an unreadable journal must block new placement, not look empty.
  Callers must also treat any :unreadable row as unresolved."
  ([] (load-all! {}))
  ([opts]
   (-> (idb/get-all-json-strict! idb/vol-range-journal-store opts)
       (.then (fn [rows]
                (reduce (fn [acc row]
                          (if-let [r (decode row)]
                            (update acc :records conj r)
                            (update acc :unreadable conj (:record-id row))))
                        {:records [] :unreadable []}
                        rows))))))
