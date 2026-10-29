(ns hyperopen.vol-range.infrastructure.ledger-store-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform.indexed-db :as idb]
            [hyperopen.vol-range.infrastructure.ledger-store :as store]
            [hyperopen.vol-range.report-fixtures :as fx]
            [hyperopen.test-support.async :as async-support]))

(defn memory-io
  "An in-memory stand-in for the IndexedDB store: rows are kept exactly as
  they would be stored (the encoded form), keyed by record id."
  ([] (memory-io (atom {})))
  ([rows]
   {:rows rows
    :put! (fn [entries] (swap! rows into entries) (js/Promise.resolve true))
    :get-all! (fn [] (js/Promise.resolve (vec (vals @rows))))
    :delete! (fn [k] (swap! rows dissoc k) (js/Promise.resolve true))}))

(defn- fill-rec [row] (store/fill-record (fx/fill row) 10))

(deftest record-validity-test
  (let [r (fill-rec fx/buy-7-row)]
    (is (store/ledger-record-valid? r))
    (is (= (str "fill:" (:fill-key (fx/fill fx/buy-7-row))) (:record-id r)))
    (is (not (store/ledger-record-valid? (assoc r :schema-version 2))))
    (is (not (store/ledger-record-valid? (assoc r :kind :grid))))
    (is (not (store/ledger-record-valid? (dissoc r :account-address))))
    (is (not (store/ledger-record-valid? (dissoc r :saved-at-ms))))
    (is (not (store/ledger-record-valid? (assoc-in r [:fill :signature] "0x"))) "signing material at any depth")
    (is (= idb/vol-range-ledger-store "vol-range-ledger"))))

(deftest invalid-records-are-refused-test
  (async done
    (let [io (memory-io)
          fail! (async-support/unexpected-error done)]
      (-> (store/put-records! io [(fill-rec fx/buy-7-row) (dissoc (fill-rec fx/sell-30-row) :kind)])
          (.then (fn [_] (is false "should reject") (done))
                 (fn [e]
                   (is (re-find #"invalid ledger record" (.-message e)))
                   (is (= {} @(:rows io)) "nothing is written")
                   (done)))
          (.catch fail!)))))

(deftest records-round-trip-and-load-filters-by-account-test
  (async done
    (let [io (memory-io)
          other (assoc-in (fill-rec (assoc fx/buy-7-row :tid 9)) [:account-address] "0xother")
          fail! (async-support/unexpected-error done)]
      (-> (store/put-records! io [(fill-rec fx/buy-7-row) other])
          (.then (fn [_]
                   (swap! (:rows io) assoc "junk" {:record-id "junk" :edn "{:not"}
                          "other-junk" {:record-id "other-junk" :account-address "0xother" :edn "{:not"}
                          "mine-junk" {:record-id "mine-junk" :account-address "0xabc" :edn "{:not"})
                   (store/load-account! io "0xABC")))
          (.then (fn [{:keys [records unreadable]}]
                   (is (= [(fill-rec fx/buy-7-row)] records) "EDN round-trips exactly; other accounts are left out")
                   (is (= #{"junk" "mine-junk"} (set unreadable))
                       "rows that no longer decode are the account's unless their envelope names another one")
                   (is (= {(:fill-key (fx/fill fx/buy-7-row)) (fx/fill fx/buy-7-row)}
                          (:fills (store/group-records records))))
                   (done)))
          (.catch fail!)))))

(defn- delta [complete failure]
  {:stream :fills :base-token-id "9001" :complete complete :failure failure})

(deftest coverage-merge-is-monotonic-under-the-lock-test
  (async done
    (let [io (memory-io)
          fail! (async-support/unexpected-error done)
          load-cov (fn [] (.then (store/load-account! io fx/account)
                                 (fn [{:keys [records]}] (get-in (store/group-records records) [:coverage :fills "9001"]))))]
      (-> (js/Promise.all
           #js [(store/merge-coverage! io "0xABC" [(delta [[0 100]] nil)] 1)
                (store/merge-coverage! io "0xabc" [(delta [[100 200]] nil)] 2)
                (store/merge-coverage! io "0xabc" [(delta [] {:from-ms 300 :to-ms 400 :reason :coverage/page-cap
                                                             :at-ms 3})] 3)])
          (.then (fn [_] (load-cov)))
          (.then (fn [rec]
                   (is (= [[0 200]] (:complete rec)) "concurrent merges never lose an interval")
                   (is (= [:coverage/page-cap] (map :reason (:failures rec))))
                   (is (= "coverage:0xabc|fills|9001" (:record-id rec)))
                   (store/merge-coverage! io fx/account [(delta [] nil)] 4)))
          (.then (fn [_] (load-cov)))
          (.then (fn [rec]
                   (is (= [[0 200]] (:complete rec)) "an empty delta never shrinks coverage")
                   (done)))
          (.catch fail!)))))

(defn- check [state]
  {:schema-version 1 :record-id "balance-check:cmp-vrt|5000" :kind :balance-check :account-address fx/account
   :campaign-id "cmp-vrt" :requested-at-ms 5000 :received-at-ms 5100 :observed-total "157" :expected nil
   :state state :last-match-at-ms nil :evaluated-at-ms nil :saved-at-ms 1})

(deftest balance-check-retention-test
  (async done
    (let [io (memory-io)
          fail! (async-support/unexpected-error done)
          ids (fn [] (set (keys @(:rows io))))]
      (-> (store/put-balance-check! io (check :pending))
          (.then (fn [_]
                   (is (= #{"balance-check:cmp-vrt|5000"} (ids)))
                   (store/put-balance-check! io (check :match))))
          (.then (fn [stored]
                   (is (= "balance-check:cmp-vrt|latest-match" (:record-id stored)))
                   (is (= #{"balance-check:cmp-vrt|latest-match"} (ids)) "a match replaces the pending check")
                   (store/put-balance-check! io (assoc (check :pending) :record-id "balance-check:cmp-vrt|6000"))))
          (.then (fn [_] (store/put-balance-check! io (assoc (check :inconclusive) :record-id "balance-check:cmp-vrt|6000"))))
          (.then (fn [stored]
                   (is (nil? stored))
                   (is (= #{"balance-check:cmp-vrt|latest-match"} (ids)) "inconclusive checks are removed")
                   (store/put-balance-check! io (assoc (check :mismatch) :record-id "balance-check:cmp-vrt|7000"))))
          (.then (fn [_]
                   (is (= #{"balance-check:cmp-vrt|latest-match" "balance-check:cmp-vrt|7000"} (ids))
                       "mismatches are kept")
                   (done)))
          (.catch fail!)))))

(deftest storage-failures-reject-test
  (async done
    (let [io {:put! (fn [_] (js/Promise.reject (js/Error. "QuotaExceededError")))
              :get-all! (fn [] (js/Promise.reject (js/Error. "IndexedDB is unavailable.")))
              :delete! (fn [_] (js/Promise.resolve nil))}]
      (-> (store/put-records! io [(fill-rec fx/buy-7-row)])
          (.then (fn [_] (is false "should reject"))
                 (fn [e] (is (= "QuotaExceededError" (.-message e)))))
          (.then (fn [_] (store/load-account! io fx/account)))
          (.then (fn [_] (is false "an unreadable ledger never looks empty") (done))
                 (fn [e] (is (re-find #"unavailable" (.-message e))) (done)))))))
