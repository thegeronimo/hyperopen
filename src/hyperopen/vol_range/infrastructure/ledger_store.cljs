(ns hyperopen.vol-range.infrastructure.ledger-store
  "The Vol Range reporting ledger in the shared IndexedDB boundary
  (`vol-range-ledger` store, HO-SPEC-014 v2.1 sections 7.4, 12.2, 12.6).

  Records: `:ledger-fill` (one per fill key, embedding the canonical
  `fill-records/normalize-fill` record under `:fill`), `:ledger-update`,
  `:coverage` (one per account, stream and base token) and
  `:balance-check`. Writes resolve only on transaction COMPLETION. Coverage
  merges and balance-check updates are read-merge-written under the
  per-account lock `hyperopen:vol-range-ledger:<account>`, so concurrent
  backfills in two tabs can only grow coverage.

  The codec is this store's own (EDN with print limits off, strict read,
  `ledger-record-valid?`); the journal's codec is not reused. Nothing here
  is on the placement path: an unavailable ledger only makes reports
  `:storage/unavailable`.

  Every function takes an optional `io` first ({:put! :get-all! :delete!});
  the default is the shared IndexedDB boundary."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [hyperopen.platform.indexed-db :as idb]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.contracts :as contracts]))

(def default-io
  {:put! (fn [entries] (idb/put-many-committed! idb/vol-range-ledger-store entries))
   :get-all! (fn [] (idb/get-all-json-strict! idb/vol-range-ledger-store))
   :delete! (fn [k] (idb/delete-key! idb/vol-range-ledger-store k))})

(def record-kinds #{:ledger-fill :ledger-update :coverage :balance-check})

(def ^:private forbidden-keys
  #{:private-key :signature :session :agent-key :secret :unlocked-session})

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))

(defn ledger-record-valid?
  "Versioned, keyed, timestamped, of a known kind, scoped to an account and
  free of signing material at any depth."
  [record]
  (letfn [(has-forbidden? [x]
            (cond (map? x) (or (some forbidden-keys (keys x)) (some has-forbidden? (vals x)))
                  (sequential? x) (some has-forbidden? x)
                  :else false))]
    (boolean (and (map? record)
                  (= contracts/ledger-schema-version (:schema-version record))
                  (string? (:record-id record))
                  (seq (:record-id record))
                  (contains? record-kinds (:kind record))
                  (number? (:saved-at-ms record))
                  (string? (:account-address record))
                  (not (has-forbidden? record))))))

(defn fill-record
  "The ledger record for a canonical fill."
  [fill now-ms]
  {:schema-version contracts/ledger-schema-version
   :record-id (str "fill:" (:fill-key fill))
   :kind :ledger-fill
   :account-address (:account-address fill)
   :fill fill
   :saved-at-ms now-ms})

(defn- encode [record]
  {:record-id (:record-id record)
   :schema-version (:schema-version record)
   :account-address (:account-address record)
   :saved-at-ms (:saved-at-ms record)
   :edn (binding [*print-length* nil *print-level* nil *print-meta* false]
          (pr-str record))})

(defn- decode [row]
  (try
    (let [r (reader/read-string (:edn row))]
      (when (ledger-record-valid? r) r))
    (catch :default _ nil)))

(defn lock-name [account] (str "hyperopen:vol-range-ledger:" (lower account)))

(defn put-records!
  "Commit `records` in one transaction. Resolves true; rejects (writing
  nothing) when any record is invalid or the write fails."
  ([records] (put-records! default-io records))
  ([io records]
   (if-let [bad (first (remove ledger-record-valid? records))]
     (js/Promise.reject (ex-info "Refusing to store an invalid ledger record" {:record-id (:record-id bad)}))
     (if (empty? records)
       (js/Promise.resolve true)
       ((:put! io) (mapv (fn [r] [(:record-id r) (encode r)]) records))))))

(defn- load-all! [io]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] ((:get-all! io))))
      (.then (fn [rows]
               (reduce (fn [acc row]
                         (if-let [r (decode row)]
                           (update acc :records conj r)
                           (update acc :unreadable conj (:record-id row))))
                       {:records [] :unreadable []}
                       rows)))))

(defn group-records
  "{:fills {fill-key fill} :updates {record-id record}
   :coverage {:fills {base-token-id record} :ledger {base-token-id record}}
   :balance-checks [record ...]} for loaded records."
  [records]
  (reduce (fn [acc r]
            (case (:kind r)
              :ledger-fill (assoc-in acc [:fills (get-in r [:fill :fill-key])] (:fill r))
              :ledger-update (assoc-in acc [:updates (:record-id r)] r)
              :coverage (assoc-in acc [:coverage (:stream r) (:base-token-id r)] r)
              :balance-check (update acc :balance-checks conj r)
              acc))
          {:fills {} :updates {} :coverage {:fills {} :ledger {}} :balance-checks []}
          records))

(defn load-account!
  "{:records [...] :unreadable [...]} for `account` (compared lower-cased).
  Rejects when the store cannot be read."
  ([account] (load-account! default-io account))
  ([io account]
   (let [account (lower account)]
     (.then (load-all! io)
            (fn [{:keys [records unreadable]}]
              {:records (filterv #(= account (lower (:account-address %))) records)
               :unreadable unreadable})))))

(defn merge-coverage!
  "Merge coverage `deltas` ({:stream :base-token-id :complete :failure}) into
  the account's records under the account lock; resolves to the merged
  records. Monotonic: complete intervals never shrink."
  ([account deltas now-ms] (merge-coverage! default-io account deltas now-ms))
  ([io account deltas now-ms]
   (let [account (lower account)]
     (if (empty? deltas)
       (js/Promise.resolve [])
       (locks/with-exclusive-lock!
        (lock-name account)
        (fn []
          (-> (load-account! io account)
              (.then (fn [{:keys [records]}]
                       (let [existing (into {} (comp (filter #(= :coverage (:kind %)))
                                                     (map (juxt :record-id identity)))
                                            records)
                             merged (vals (reduce (fn [acc d]
                                                    (let [id (coverage/coverage-record-id account (:stream d)
                                                                                          (:base-token-id d))]
                                                      (assoc acc id (coverage/merge-coverage
                                                                     (get acc id) d
                                                                     {:account-address account :now-ms now-ms}))))
                                                  existing
                                                  deltas))
                             changed (filterv #(not= (get existing (:record-id %)) %) merged)]
                         (.then (put-records! io changed) (fn [_] (vec merged)))))))))))))

(defn put-balance-check!
  "Store an evaluated or new balance check under the account lock. :pending
  and :mismatch checks are kept at their own id (mismatches permanently);
  a :match replaces the campaign's `latest-match` record and removes the
  pending one; an :inconclusive check is removed. Resolves to the stored
  record or nil."
  ([check] (put-balance-check! default-io check))
  ([io check]
   (let [account (lower (:account-address check))
         pending-id (:record-id check)]
     (locks/with-exclusive-lock!
      (lock-name account)
      (fn []
        (case (:state check)
          (:pending :mismatch) (.then (put-records! io [check]) (fn [_] check))
          :match (let [stored (assoc check :record-id (coverage/latest-match-id (:campaign-id check)))]
                   (-> (put-records! io [stored])
                       (.then (fn [_] (when-not (= pending-id (:record-id stored)) ((:delete! io) pending-id))))
                       (.then (fn [_] stored))))
          (.then (js/Promise.resolve ((:delete! io) pending-id)) (fn [_] nil))))))))
