(ns hyperopen.vol-range.infrastructure.report-reads-test
  "Bounded reporting reads (HO-SPEC-014 section 9.2): overlap
  de-duplication, no-progress and page-cap guards, low priority, and reads
  that resolve incomplete instead of rejecting."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.vol-range.infrastructure.reads :as reads]
            [hyperopen.vol-range.infrastructure.report-reads :as report-reads]))

(defn- ledger-venue [rows calls]
  (fn [body opts]
    (swap! calls conj [body opts])
    (js/Promise.resolve
     (->> rows
          (filter #(<= (get body "startTime") (:time %) (get body "endTime")))
          (sort-by :time)
          (take 2000)
          vec))))

(defn- ledger-rows [n time-fn]
  (mapv (fn [i] {:time (time-fn i) :hash (str "0x" i) :delta {:type "spotTransfer" :token "VRT" :amount "1"}})
        (range n)))

(defn- read-ledger! [post-info!]
  (report-reads/read-ledger-window! post-info! {:account-address "0xABC" :start-ms 0 :end-ms 1000000
                                                :read-id "r1" :now-ms! (constantly 50)}))

(deftest ledger-pages-forward-with-overlap-and-de-duplicates-test
  (async done
    (let [calls (atom [])]
      (-> (read-ledger! (ledger-venue (ledger-rows 4500 #(quot % 3)) calls))
          (.then (fn [{:keys [rows complete? reason requested-at-ms]}]
                   (is complete?)
                   (is (nil? reason))
                   (is (= 50 requested-at-ms))
                   (is (= 4500 (count rows)) "overlap rows appear once")
                   (is (= 4 (count @calls)) "the last page only repeats the overlap row: nothing is left")
                   (is (every? #(= :low (:priority (second %))) @calls) "background reads are low priority")
                   (is (every? #(= 0 (:cache-ttl-ms (second %))) @calls))
                   (is (= "0xabc" (get (ffirst @calls) "user")))
                   (is (= "userNonFundingLedgerUpdates" (get (ffirst @calls) "type")))
                   (done)))))))

(deftest ledger-short-pages-do-not-end-the-read-test
  ;; The venue's page size is not known: a page shorter than the assumed
  ;; 2,000 rows is not taken as the end of the window.
  (async done
    (let [calls (atom [])
          venue (fn [body opts]
                  (swap! calls conj [body opts])
                  (js/Promise.resolve
                   (->> (ledger-rows 1200 identity)
                        (filter #(<= (get body "startTime") (:time %) (get body "endTime")))
                        (take 500)
                        vec)))]
      (-> (read-ledger! venue)
          (.then (fn [{:keys [rows complete?]}]
                   (is complete?)
                   (is (= 1200 (count rows)) "every row after the 500th is read too")
                   (is (= [0 499 998] (take 3 (map #(get (first %) "startTime") @calls))))
                   (done)))))))

(deftest ledger-no-progress-and-page-cap-are-incomplete-test
  (async done
    (-> (read-ledger! (ledger-venue (ledger-rows 2001 (constantly 7)) (atom [])))
        (.then (fn [{:keys [complete? reason]}]
                 (is (false? complete?))
                 (is (= :ledger/no-progress reason))))
        (.then (fn [_] (read-ledger! (ledger-venue (ledger-rows 25000 identity) (atom [])))))
        (.then (fn [{:keys [complete? reason rows]}]
                 (is (false? complete?))
                 (is (= :ledger/page-cap reason))
                 (is (= 19991 (count rows)) "ten pages, overlap removed")
                 (done))))))

(deftest ledger-failures-resolve-incomplete-test
  (async done
    (-> (read-ledger! (fn [_ _] (js/Promise.reject (js/Error. "boom"))))
        (.then (fn [{:keys [complete? reason error rows]}]
                 (is (false? complete?))
                 (is (= :ledger/read-error reason))
                 (is (= "boom" error))
                 (is (= [] rows))))
        (.then (fn [_] (read-ledger! (fn [_ _] (js/Promise.resolve {:data "nope"})))))
        (.then (fn [{:keys [complete? reason]}]
                 (is (false? complete?) "an unexpected shape never looks like an empty ledger")
                 (is (= :ledger/read-error reason))))
        (.then (fn [_] (report-reads/read-ledger-window! (fn [_ _] (throw (js/Error. "unused")))
                                                         {:account-address nil :start-ms 0 :end-ms 1})))
        (.then (fn [{:keys [complete?]}]
                 (is (false? complete?))
                 (done))))))

(def hour 3600000)

(defn- candle-venue [calls]
  (fn [body opts]
    (swap! calls conj [body opts])
    (let [{:strs [startTime endTime]} (get body "req")]
      (js/Promise.resolve
       (vec (for [t (range (* hour (js/Math.ceil (/ startTime hour))) endTime hour)]
              {:t t :T (+ t hour -1) :c "80" :s "@9001" :i "1h"}))))))

(deftest candles-page-backward-test
  (async done
    (let [calls (atom [])
          to (* 20000 hour)
          from (- to (* 12000 hour))]
      (-> (report-reads/read-hourly-candles! (candle-venue calls) {:coin "@9001" :from-ms from :to-ms to
                                                                   :now-ms! (constantly to)})
          (.then (fn [{:keys [candles complete?]}]
                   (is complete?)
                   (is (= 3 (count @calls)) "5,000 + 5,000 + 2,000 bars")
                   (is (= [5000 5000 2000]
                          (mapv (fn [[b _]] (/ (- (get-in b ["req" "endTime"]) (get-in b ["req" "startTime"])) hour))
                                @calls)))
                   (is (every? #(= :low (:priority (second %))) @calls))
                   (is (= "1h" (get-in (ffirst @calls) ["req" "interval"])))
                   (is (= 12000 (count candles)))
                   (is (= (sort (map :t candles)) (map :t candles)))
                   (done)))))))

(deftest candles-stop-at-the-page-cap-and-on-failure-test
  (async done
    (let [to (* 30000 hour)]
      (-> (report-reads/read-hourly-candles! (candle-venue (atom [])) {:coin "@9001" :from-ms 0 :to-ms to})
          (.then (fn [{:keys [complete? candles error]}]
                   (is (false? complete?))
                   (is (= "Candle page cap reached" error))
                   (is (= 20000 (count candles)))))
          (.then (fn [_] (report-reads/read-hourly-candles! (fn [_ _] (js/Promise.reject (js/Error. "down")))
                                                            {:coin "@9001" :from-ms 0 :to-ms to})))
          (.then (fn [{:keys [complete? error]}]
                   (is (false? complete?))
                   (is (= "down" error))
                   (done)))))))

(deftest fills-reader-priority-is-optional-test
  (async done
    (let [calls (atom [])
          venue (fn [body opts] (swap! calls conj opts) (js/Promise.resolve []))]
      (-> (reads/read-fills-window! venue {:account-address "0xabc" :barrier-id "b" :start-ms 0 :end-ms 5})
          (.then (fn [r]
                   (is (= :high (:priority (first @calls))) "the default is unchanged")
                   (is (nil? (:reason r)))
                   (reads/read-fills-window! venue {:account-address "0xabc" :barrier-id "b" :start-ms 0 :end-ms 5
                                                    :priority :low})))
          (.then (fn [_]
                   (is (= :low (:priority (second @calls))))
                   (done)))))))
