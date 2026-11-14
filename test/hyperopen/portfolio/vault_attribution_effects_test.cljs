(ns hyperopen.portfolio.vault-attribution-effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.projections.portfolio-vault-attribution :as projections]
            [hyperopen.portfolio.vault-attribution-effects :as effects]
            [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]))

(def ^:private address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest ledger-vault-addresses-test
  (is (= [fx/vault-a fx/vault-b]
         (effects/ledger-vault-addresses
          (concat fx/noise
                  [(fx/deposit 10 fx/vault-a "5")
                   (fx/deposit 11 (.toUpperCase fx/vault-b) "5")
                   (fx/withdraw 12 fx/vault-a {:net "1"})
                   {:time 13 :delta {:type "vaultCreate" :vault fx/vault-a :usdc "1"}}]))))
  (is (= [] (effects/ledger-vault-addresses nil)))
  (is (= [] (effects/ledger-vault-addresses [{:delta {:type "vaultDeposit"}}]))))

(defn- deps
  [store overrides]
  (merge {:store store
          :address address
          :request-user-non-funding-ledger-updates! (fn [& _] (js/Promise.resolve []))
          :request-vault-details! (fn [& _] (js/Promise.resolve nil))
          :begin-load projections/begin-load
          :apply-success projections/apply-success
          :apply-error projections/apply-error
          :now-ms (constantly 777)}
         overrides))

(defn- new-store
  []
  (atom {:wallet {:address address}
         :portfolio {:vault-attribution {:status :idle}}}))

(defn- slot [store] (get-in @store [:portfolio :vault-attribution]))

(deftest fetch-loads-ledger-then-details-for-each-vault-test
  (async done
    (let [store (new-store)
          ledger-calls (atom [])
          detail-calls (atom [])
          rows [(fx/deposit 10 fx/vault-a "5") (fx/deposit 11 fx/vault-b "6")]]
      (-> (effects/fetch-vault-attribution!
           (deps store
                 {:request-user-non-funding-ledger-updates!
                  (fn [& args] (swap! ledger-calls conj args) (js/Promise.resolve rows))
                  :request-vault-details!
                  (fn [vault opts]
                    (swap! detail-calls conj [vault opts])
                    (js/Promise.resolve {:name vault}))}))
          (.then (fn [_]
                   (is (= [[address 0 nil {:priority :normal :cache-ttl-ms 0}]
                           [address 11 nil {:priority :normal :cache-ttl-ms 0}]
                           [address 12 nil {:priority :normal :cache-ttl-ms 0}]]
                          @ledger-calls)
                       "a page that adds nothing new is probed once past its newest time, then the read ends")
                   (is (= #{[fx/vault-a {:user address :priority :low :cache-ttl-ms 0}]
                            [fx/vault-b {:user address :priority :low :cache-ttl-ms 0}]}
                          (set @detail-calls)))
                   (is (= {:status :ready
                           :for-address address
                           :ledger-rows rows
                           :ledger-partial? false
                           :details-by-vault {fx/vault-a {:name fx/vault-a}
                                              fx/vault-b {:name fx/vault-b}}
                           :errors-by-vault {}
                           :loaded-at-ms 777
                           :live-vaults-at-load #{}
                           :stale? false
                           :error nil}
                          (slot store)))
                   (done)))))))

(deftest fetch-marks-loading-synchronously-test
  (let [store (new-store)
        pending (js/Promise. (fn [_ _] nil))]
    (effects/fetch-vault-attribution!
     (deps store {:request-user-non-funding-ledger-updates! (fn [& _] pending)}))
    (is (= :loading (:status (slot store))))
    (is (= address (:for-address (slot store))))))

(deftest fetch-treats-null-ledger-as-empty-and-ready-test
  (async done
    (let [store (new-store)
          detail-calls (atom 0)]
      (-> (effects/fetch-vault-attribution!
           (deps store {:request-user-non-funding-ledger-updates! (fn [& _] (js/Promise.resolve nil))
                        :request-vault-details! (fn [& _] (swap! detail-calls inc) (js/Promise.resolve nil))}))
          (.then (fn [_]
                   (is (= 0 @detail-calls))
                   (is (= :ready (:status (slot store))))
                   (is (= [] (:ledger-rows (slot store))))
                   (done)))))))

(deftest fetch-records-failing-vault-and-continues-test
  (async done
    (let [store (new-store)
          rows [(fx/deposit 10 fx/vault-a "5") (fx/deposit 11 fx/vault-b "6")
                (fx/deposit 12 "0x3333333333333333333333333333333333333333" "7")]]
      (-> (effects/fetch-vault-attribution!
           (deps store
                 {:request-user-non-funding-ledger-updates! (fn [& _] (js/Promise.resolve rows))
                  :request-vault-details!
                  (fn [vault _]
                    (cond
                      (= vault fx/vault-a) (js/Promise.reject (js/Error. "rate limited"))
                      (= vault fx/vault-b) (js/Promise.resolve {:name "B"})
                      :else (js/Promise.resolve nil)))}))
          (.then (fn [_]
                   (let [s (slot store)]
                     (is (= :ready (:status s)))
                     (is (= {fx/vault-b {:name "B"}} (:details-by-vault s)))
                     (is (= #{fx/vault-a "0x3333333333333333333333333333333333333333"}
                            (set (keys (:errors-by-vault s)))))
                     (is (re-find #"rate limited" (get-in s [:errors-by-vault fx/vault-a])))
                     (is (= "Vault details unavailable"
                            (get-in s [:errors-by-vault "0x3333333333333333333333333333333333333333"]))))
                   (done)))))))

(deftest fetch-ledger-failure-sets-error-test
  (async done
    (let [store (new-store)
          detail-calls (atom 0)]
      (-> (effects/fetch-vault-attribution!
           (deps store {:request-user-non-funding-ledger-updates!
                        (fn [& _] (js/Promise.reject (js/Error. "ledger down")))
                        :request-vault-details! (fn [& _] (swap! detail-calls inc) (js/Promise.resolve nil))}))
          (.then (fn [_]
                   (is (= 0 @detail-calls))
                   (is (= :error (:status (slot store))))
                   (is (re-find #"ledger down" (:error (slot store))))
                   (done)))))))

(deftest fetch-limits-concurrent-vault-requests-test
  (async done
    (let [store (new-store)
          addresses (mapv #(str "0x" (apply str (repeat 39 "0")) %) (range 1 10))
          rows (mapv #(fx/deposit % %2 "5") (range 10 20) addresses)
          in-flight (atom 0)
          peak (atom 0)]
      (-> (effects/fetch-vault-attribution!
           (deps store
                 {:request-user-non-funding-ledger-updates! (fn [& _] (js/Promise.resolve rows))
                  :request-vault-details!
                  (fn [vault _]
                    (swap! in-flight inc)
                    (swap! peak max @in-flight)
                    (js/Promise. (fn [resolve _]
                                   (js/setTimeout (fn []
                                                    (swap! in-flight dec)
                                                    (resolve {:name vault}))
                                                  5))))}))
          (.then (fn [_]
                   (is (= 9 (count (:details-by-vault (slot store)))))
                   (is (= 4 @peak))
                   (done)))))))

(deftest fetch-drops-results-when-account-changes-mid-flight-test
  (async done
    (let [store (new-store)
          detail-calls (atom 0)
          rows [(fx/deposit 10 fx/vault-a "5") (fx/deposit 11 fx/vault-b "6")]]
      (-> (effects/fetch-vault-attribution!
           (deps store
                 {:request-user-non-funding-ledger-updates!
                  (fn [& _]
                    (swap! store assoc-in [:wallet :address] "0x1111111111111111111111111111111111111111")
                    (js/Promise.resolve rows))
                  :request-vault-details! (fn [& _] (swap! detail-calls inc) (js/Promise.resolve {}))}))
          (.then (fn [_]
                   (is (= 0 @detail-calls))
                   (is (= :idle (:status (slot store))))
                   (done)))))))

(deftest fetch-ignores-invalid-address-test
  (async done
    (let [store (new-store)]
      (-> (effects/fetch-vault-attribution! (deps store {:address "nope"}))
          (.then (fn [_]
                   (is (= {:status :idle} (slot store)))
                   (done)))))))

(defn- paged-ledger
  "A fake ledger endpoint over `rows` (sorted by time) that answers at most
   `page-size` rows with time >= start."
  [rows page-size calls]
  (fn [_ start _ _]
    (swap! calls conj start)
    (js/Promise.resolve (vec (take page-size (filter #(>= (:time %) start) rows))))))

(deftest fetch-pages-the-whole-ledger-test
  (async done
    (let [store (new-store)
          calls (atom [])
          ;; two rows share time 13 across the page boundary
          rows (vec (concat (map #(fx/deposit % fx/vault-a "1") (range 10 14))
                            [(assoc (fx/deposit 13 fx/vault-b "2") :hash "0xother")]
                            (map #(fx/deposit % fx/vault-b "1") (range 14 18))))]
      (-> (effects/fetch-vault-attribution!
           (deps store {:request-user-non-funding-ledger-updates! (paged-ledger rows 4 calls)
                        :request-vault-details! (fn [v _] (js/Promise.resolve {:name v}))}))
          (.then (fn [_]
                   (is (= (set rows) (set (:ledger-rows (slot store)))))
                   (is (= (count rows) (count (:ledger-rows (slot store)))) "no duplicates")
                   (is (false? (:ledger-partial? (slot store))))
                   (is (= 0 (first @calls)))
                   (is (apply < @calls) "each page starts at the newest time read")
                   (done)))))))

(deftest fetch-marks-ledger-partial-at-the-page-cap-test
  (async done
    (let [store (new-store)
          calls (atom [])
          rows (mapv #(fx/deposit % fx/vault-a "1") (range 1 200))]
      (-> (effects/fetch-vault-attribution!
           (deps store {:request-user-non-funding-ledger-updates! (paged-ledger rows 2 calls)}))
          (.then (fn [_]
                   (is (= effects/max-ledger-pages (count @calls)))
                   (is (true? (:ledger-partial? (slot store))))
                   (is (= :ready (:status (slot store))))
                   (done)))))))

(deftest fetch-skips-vault-details-for-the-account-itself-test
  (async done
    (let [store (new-store)
          detail-calls (atom [])
          rows [(fx/deposit 10 address "5") (fx/deposit 11 fx/vault-b "6")]]
      (-> (effects/fetch-vault-attribution!
           (deps store {:request-user-non-funding-ledger-updates! (fn [& _] (js/Promise.resolve rows))
                        :request-vault-details! (fn [v _] (swap! detail-calls conj v)
                                                  (js/Promise.resolve {:name v}))}))
          (.then (fn [_]
                   (is (= [fx/vault-b] @detail-calls))
                   (done)))))))

(deftest fetch-dedupes-a-second-call-while-in-flight-test
  (async done
    (let [store (new-store)
          calls (atom 0)
          release (atom nil)
          pending (js/Promise. (fn [resolve _] (reset! release resolve)))]
      (let [first-load (effects/fetch-vault-attribution!
                        (deps store {:request-user-non-funding-ledger-updates!
                                     (fn [& _] (swap! calls inc) pending)}))]
        (effects/fetch-vault-attribution!
         (deps store {:request-user-non-funding-ledger-updates!
                      (fn [& _] (swap! calls inc) pending)}))
        (is (= 1 @calls) "one ledger request for two calls")
        (@release [])
        (-> first-load
            (.then (fn [_]
                     (is (= :ready (:status (slot store))))
                     (done))))))))

(deftest fetch-probes-past-a-page-of-one-timestamp-test
  ;; Page size 3; four rows share time 10, so re-reading from 10 returns the
  ;; same page forever. A probe from 11 must find the newer rows (and say the
  ;; ledger may be incomplete: the fourth row at 10 cannot be reached).
  (async done
    (let [store (new-store)
          calls (atom [])
          at-10 (mapv #(assoc (fx/deposit 10 fx/vault-a "1") :hash (str "0xsame" %)) (range 4))
          newer [(fx/deposit 11 fx/vault-b "2") (fx/deposit 12 fx/vault-b "3")]]
      (-> (effects/fetch-vault-attribution!
           (deps store {:request-user-non-funding-ledger-updates!
                        (paged-ledger (into at-10 newer) 3 calls)}))
          (.then (fn [_]
                   (let [rows (set (:ledger-rows (slot store)))]
                     (is (every? rows newer) "the newest rows are not cut")
                     (is (= 5 (count rows))))
                   (is (true? (:ledger-partial? (slot store))))
                   (is (= 11 (nth @calls 2)) "probe from the next millisecond")
                   (done)))))))

(deftest fetch-probe-that-finds-nothing-ends-the-read-test
  (async done
    (let [store (new-store)
          calls (atom [])
          rows (mapv #(assoc (fx/deposit 10 fx/vault-a "1") :hash (str "0xsame" %)) (range 3))]
      (-> (effects/fetch-vault-attribution!
           (deps store {:request-user-non-funding-ledger-updates! (paged-ledger rows 3 calls)}))
          (.then (fn [_]
                   (is (= rows (:ledger-rows (slot store))))
                   (is (false? (:ledger-partial? (slot store))))
                   (is (= [0 10 11] @calls) "one probe, then done")
                   (done)))))))
