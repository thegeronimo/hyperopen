(ns hyperopen.vol-range.execution-actions-test
  "Release A action handlers emit only the vol-range-execute effect (or a
  save), never place twice while in flight, and only place a :ready review."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vol-range.execution-actions :as a]))

(def ready-review {:vol-range {:ui {:review-open? true} :frozen-review {:status :ready :legs [{}]}}})

(deftest place-requires-an-open-ready-review-and-is-single-flight-test
  (is (= [[:effects/vol-range-execute :place {}]] (a/place-vol-range-orders ready-review)))
  (is (= [] (a/place-vol-range-orders (assoc-in ready-review [:vol-range :frozen-review :status] :invalid))))
  (is (= [] (a/place-vol-range-orders (assoc-in ready-review [:vol-range :ui :review-open?] false))))
  (is (= [] (a/place-vol-range-orders (assoc-in ready-review [:vol-range :execution :busy [:place]] true)))
      "a double click while placing sends nothing"))

(deftest ladder-operations-target-known-grids-once-test
  (let [s {:vol-range {:execution {:grids {"g1" {:grid-id "g1"}}}}}]
    (is (= [[:effects/vol-range-execute :cancel {:grid-id "g1"}]] (a/cancel-vol-ladder s "g1")))
    (is (= [] (a/cancel-vol-ladder s "nope")))
    (is (= [] (a/cancel-vol-ladder (assoc-in s [:vol-range :execution :busy [:cancel "g1"]] true) "g1")))
    (is (= [[:effects/vol-range-execute :refresh {:grid-id "g1"}]] (a/refresh-vol-ladder s "g1")))
    (is (= [[:effects/save-many [[[:vol-range :execution :selected-grid-id] "g1"]]]] (a/select-vol-ladder s "g1")))
    (is (= [[:effects/save-many [[[:vol-range :execution :selected-grid-id] nil]]]]
           (a/select-vol-ladder (assoc-in s [:vol-range :execution :selected-grid-id] "g1") "g1"))
        "selecting the open ladder closes it")))

(deftest resync-targets-the-current-monitoring-epoch-once-test
  (let [s {:vol-range {:execution {:monitoring {:epoch 4 :status :resyncing}}}}]
    (is (= [[:effects/vol-range-execute :resync {:epoch 4}]] (a/resync-vol-ladders s)))
    (is (= [] (a/resync-vol-ladders (assoc-in s [:vol-range :execution :busy [:resync]] true)))
        "one resync at a time")
    (is (= [] (a/resync-vol-ladders {})) "nothing without live monitoring")))
