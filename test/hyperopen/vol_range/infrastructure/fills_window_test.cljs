(ns hyperopen.vol-range.infrastructure.fills-window-test
  "Paginated fills reads (HO-SPEC-014 section 9.2): overlap, no-progress and
  page-cap guards never claim coverage they do not have."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.vol-range.infrastructure.reads :as reads]))

(defn- venue [fills]
  (fn [body _]
    (js/Promise.resolve
     (->> fills
          (filter #(<= (get body "startTime") (:time %) (get body "endTime")))
          (sort-by :time)
          (take reads/fills-page-limit)
          vec))))

(defn- fills [n time-fn] (mapv (fn [i] {:tid i :time (time-fn i) :coin "SYN/USDC" :sz "1"}) (range n)))

(defn- read! [rows] (reads/read-fills-window! (venue rows) {:account-address "0xabc" :barrier-id "b"
                                                            :start-ms 0 :end-ms 1000000}))

(deftest pages-forward-with-overlap-until-a-short-page-test
  (async done
    (-> (read! (fills 2500 #(quot % 2)))
        (.then (fn [{:keys [rows complete?]}]
                 (is complete?)
                 (is (= 2500 (count (distinct (map :tid rows)))) "every fill is read; overlap rows repeat")
                 (done))))))

(deftest a-full-page-in-one-millisecond-is-incomplete-test
  (async done
    (-> (read! (fills 2001 (constantly 5)))
        (.then (fn [{:keys [complete? error]}]
                 (is (false? complete?))
                 (is (re-find #"no progress" error))
                 (done))))))
