(ns hyperopen.vol-range.application.remediations-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.remediations :as remediations]
            [hyperopen.vol-range.fixtures :as fx]))

(deftest apply-draft-patch-test
  (let [draft fx/draft-13-2
        patched (remediations/apply-draft-patch draft [[[:anchor :mode] :market-price]
                                                       [[:buy :start-sigma] "0.90"]])]
    (is (= :market-price (get-in patched [:anchor :mode])))
    (is (= "0.90" (get-in patched [:buy :start-sigma])))
    (is (= (dissoc patched :anchor :buy) (dissoc draft :anchor :buy)))
    (is (= :custom (get-in draft [:anchor :mode])))
    (is (= (-> patched (update :buy dissoc :start-sigma) (update :anchor dissoc :mode))
           (-> draft (update :buy dissoc :start-sigma) (update :anchor dissoc :mode)))
        "only the patched keys differ")
    (is (= draft (remediations/apply-draft-patch draft [])))))

(deftest offers-empty-for-ready-plan-test
  (let [input (fx/plan-input)
        result (planner/prepare-plan input)]
    (is (= :ready (:status result)))
    (is (= [] (remediations/offers input result)))))

(deftest offers-do-not-mutate-input-test
  (let [input (fx/plan-input {:market (fx/market-snapshot {:bbo {:bid "75.89" :ask "75.91" :observed-at-ms 1}})})
        snapshot (pr-str input)
        result (planner/prepare-plan (assoc input :remediations? false))
        offers (remediations/offers input result)]
    (is (= [] (:remediations result)))
    (is (= 2 (count offers)))
    (is (= snapshot (pr-str input)))))
