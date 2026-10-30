(ns hyperopen.platform.indexed-db-committed-test
  "Committed IndexedDB writes (HO-SPEC-014 section 7.4, VR-11): request-level
  success is never durability, and a late abort leaves nothing written."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform.indexed-db :as idb]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.infrastructure.journal :as journal]))

(defn- fake-indexed-db
  "Minimal IndexedDB whose transactions end with `outcome` (:complete or
  :abort) AFTER every request has already reported success."
  [committed outcome]
  (letfn [(later [f] (js/setTimeout f 0))
          (make-db []
            (let [db #js {}]
              (aset db "close" (fn [] nil))
              (aset db "createObjectStore" (fn [_] nil))
              (aset db "objectStoreNames" #js {:contains (fn [name] (contains? #{idb/vol-range-journal-store} name))})
              (aset db "transaction"
                    (fn [_names _mode]
                      (let [tx #js {}
                            pending (atom {})]
                        (aset tx "abort" (fn [] (aset tx "aborted" true)))
                        (aset tx "objectStore"
                              (fn [_]
                                #js {:put (fn [v k]
                                            (when (and (= outcome :sync-throw) (= k "bad"))
                                              (throw (js/Error. "DataError: bad key")))
                                            (let [req #js {}]
                                              (swap! pending assoc k v)
                                              (later #(when-let [h (.-onsuccess req)] (h #js {:target #js {:result k}})))
                                              req))
                                     :getAll (fn []
                                               (let [req #js {}]
                                                 (later #(when-let [h (.-onsuccess req)]
                                                           (h #js {:target #js {:result (clj->js (vec (vals @committed)))}})))
                                                 req))}))
                        ;; The transaction settles after its requests succeeded.
                        (js/setTimeout
                         (fn []
                           (cond
                             (.-aborted tx) (some-> (.-onabort tx) (apply [#js {}]))
                             (= outcome :complete) (do (swap! committed merge @pending)
                                                       (some-> (.-oncomplete tx) (apply [#js {}])))
                             (= outcome :sync-throw) (do (swap! committed merge @pending)
                                                         (some-> (.-oncomplete tx) (apply [#js {}])))
                             ;; :abort and :request-error: a failed request aborts the
                             ;; whole transaction unless its error is cancelled.
                             :else (do (aset tx "error" (js/Error. (if (= outcome :abort)
                                                                      "QuotaExceededError"
                                                                      "ConstraintError")))
                                       (some-> (.-onabort tx) (apply [#js {}])))))
                         5)
                        tx)))
              db))]
    #js {:open (fn [_ _]
                 (let [req #js {}]
                   (later (fn []
                            (let [db (make-db)]
                              (set! (.-result req) db)
                              (some-> (.-onupgradeneeded req) (apply [#js {:target #js {:result db}}]))
                              (some-> (.-onsuccess req) (apply [#js {:target #js {:result db}}])))))
                   req))}))

(defn- with-fake [outcome f]
  (let [orig (.-indexedDB js/globalThis)
        committed (atom {})]
    (set! (.-indexedDB js/globalThis) (fake-indexed-db committed outcome))
    (idb/clear-open-db-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f committed)))
        (.finally (fn []
                    (idb/clear-open-db-cache!)
                    (set! (.-indexedDB js/globalThis) orig))))))

(def ^:private record
  (jr/intent-record {:operation-id "op1" :scope {:network :hyperliquid-mainnet :account-address "0xa"}
                     :scope-key "k" :plan-id "p" :input-fingerprint "f" :action-digest "d"
                     :legs [{:leg-id "buy-0" :cloid "0x484f5652000000000000000000000001"
                             :side :buy :price "76.4" :size "7"}]
                     :reservations {:usdc "534.8" :base "0"} :now-ms 1}))

(deftest committed-write-resolves-only-on-transaction-complete-test
  (async done
    (-> (with-fake :complete
          (fn [committed]
            (-> (idb/put-many-committed! idb/vol-range-journal-store [["a" #js {:x 1}] ["b" #js {:x 2}]])
                (.then (fn [ok]
                         (is (true? ok))
                         (is (= #{"a" "b"} (set (keys @committed))) "both records, one transaction"))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest late-abort-after-request-success-rejects-and-writes-nothing-test
  (async done
    (-> (with-fake :abort
          (fn [committed]
            (-> (idb/put-many-committed! idb/vol-range-journal-store [["a" #js {:x 1}]])
                (.then (fn [_] (is false "a late abort must reject")))
                (.catch (fn [e]
                          (is (re-find #"Quota" (.-message e)))
                          (is (empty? @committed) "nothing became durable"))))))
        (.finally done))))

(deftest request-error-aborts-the-whole-batch-test
  (async done
    (-> (with-fake :request-error
          (fn [committed]
            (-> (idb/put-many-committed! idb/vol-range-journal-store [["a" #js {}] ["b" #js {}]])
                (.then (fn [_] (is false "must reject")))
                (.catch (fn [e]
                          (is (re-find #"Constraint" (.-message e)))
                          (is (empty? @committed) "no partial commit"))))))
        (.finally done))))

(deftest synchronous-put-failure-aborts-earlier-puts-test
  ;; Review M2: a later put that throws must not let earlier puts auto-commit.
  (async done
    (-> (with-fake :sync-throw
          (fn [committed]
            (-> (idb/put-many-committed! idb/vol-range-journal-store [["a" #js {}] ["bad" #js {}]])
                (.then (fn [_] (is false "must reject")))
                (.catch (fn [e]
                          (is (re-find #"DataError" (.-message e)))
                          (-> (js/Promise. (fn [res _] (js/setTimeout res 20)))
                              (.then (fn [_] (is (empty? @committed) "the transaction was aborted")))))))))
        (.finally done))))

(deftest missing-indexed-db-rejects-instead-of-pretending-test
  (async done
    (let [orig (.-indexedDB js/globalThis)]
      (set! (.-indexedDB js/globalThis) nil)
      (idb/clear-open-db-cache!)
      (-> (idb/put-many-committed! idb/vol-range-journal-store [["a" #js {}]])
          (.then (fn [_] (is false "must reject")) (fn [e] (is (re-find #"unavailable" (.-message e)))))
          (.then (fn [_] (idb/get-all-json-strict! idb/vol-range-journal-store)))
          (.then (fn [_] (is false "an unreadable journal must not look empty"))
                 (fn [e] (is (re-find #"unavailable" (.-message e)))))
          (.finally (fn []
                      (set! (.-indexedDB js/globalThis) orig)
                      (idb/clear-open-db-cache!)
                      (done)))))))

(deftest journal-roundtrip-restores-keywords-test
  (async done
    (-> (with-fake :complete
          (fn [_]
            (let [spec-shaped (assoc record
                                     :scope-key [:hyperliquid-mainnet "0xa" 107]
                                     :scope {:network :hyperliquid-mainnet :market-type :spot}
                                     :cloid-map {"0x484f5652000000000000000000000001" :buy-0}
                                     :reason :status-count-mismatch)]
              (-> (journal/commit! [spec-shaped])
                  (.then (fn [_] (journal/load-all!)))
                  (.then (fn [{:keys [records unreadable]}]
                           (is (= [spec-shaped] records)
                               "keyword scope vectors, nested keywords and string-keyed maps round-trip")
                           (is (= [] unreadable))))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest journal-refuses-invalid-records-without-writing-test
  (async done
    (-> (with-fake :complete
          (fn [committed]
            (-> (journal/commit! [record (assoc record :record-id "x" :signature "0xsig")])
                (.then (fn [_] (is false "must reject")))
                (.catch (fn [e]
                          (is (re-find #"invalid record" (.-message e)))
                          (is (empty? @committed)))))))
        (.finally done))))

(deftest corrupt-or-foreign-rows-are-unreadable-not-records-test
  (async done
    (-> (with-fake :complete
          (fn [_]
            (-> (idb/put-many-committed! idb/vol-range-journal-store
                                         [["bad-edn" (clj->js {:record-id "bad-edn" :edn "{:a"})]
                                          ["empty" (clj->js {:record-id "empty" :edn "{}"})]
                                          ["future" (clj->js {:record-id "future"
                                                              :edn (pr-str (assoc record :schema-version 99))})]])
                (.then (fn [_] (journal/load-all!)))
                (.then (fn [{:keys [records unreadable]}]
                         (is (= [] records))
                         (is (= #{"bad-edn" "empty" "future"} (set unreadable))
                             "callers must treat these as unresolved, never as resolved records"))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))
