(ns hyperopen.views.trade.vol-range.review-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vol-range.application.projections :as projections]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.state-fixtures :as sf]
            [hyperopen.views.trade.vol-range.review :as review]))

(defn- open-review-model []
  (with-redefs [snapshots/fee-snapshot sf/with-synthetic-fees]
    (let [state (sf/base-state)
          plan (projections/plan-for-state state sf/now-ms)
          frozen (-> state
                     (assoc-in [:vol-range :frozen-review] plan)
                     (assoc-in [:vol-range :frozen-context] {:bbo {:bid "79.99" :ask "80.01"
                                                                   :observed-at-ms sf/now-ms}
                                                              :frozen-at-ms sf/now-ms})
                     (assoc-in [:vol-range :ui :review-open?] true))]
      (projections/review-model frozen sf/now-ms))))

(deftest review-dialog-attributes-and-rows-test
  (let [html (review/review-dialog (open-review-model))
        dialog (hiccup/find-by-data-role html "vol-range-review")
        attrs (second dialog)
        t (str/join " " (hiccup/collect-strings html))]
    (is (= "dialog" (:role attrs)))
    (is (true? (:aria-modal attrs)))
    (is (= "vol-range-review-title" (:aria-labelledby attrs)))
    (is (some? (hiccup/find-first-node html #(= "vol-range-review-title" (get-in % [1 :id])))))
    (is (= [[:actions/handle-vol-range-review-keydown [:event/key]]] (get-in attrs [:on :keydown])))
    (is (str/includes? t "Review 7 post-only orders"))
    (is (str/includes? t "Plan as of 14:00:00 UTC"))
    (is (= 7 (count (hiccup/find-all-nodes html #(some? (get-in % [1 :data-leg-id]))))))
    (doseq [s ["Exact orders to sign" "Distance after rounding" "Cash and fees" "Inventory bounds (SYN)" "Size (SYN)"
               "Plan details · sources" "Orders fill independently. Prices stay fixed until you update them."
               "Pressing Enter in a field never places orders."]]
      (is (str/includes? t s) s))
    (is (empty? (hiccup/find-all-nodes html #(= :form (first %)))) "no form element")))

(deftest review-footer-has-no-commit-control-test
  (let [html (review/review-dialog (open-review-model))
        buttons (hiccup/find-all-nodes html #(= :button (first %)))
        placement (hiccup/find-by-data-role html "vol-range-placement-unavailable")]
    (is (= "Placement isn't available yet" (str/join "" (hiccup/collect-strings placement))))
    (is (not= :button (first placement)))
    (is (not-any? #(re-find #"(?i)place|commit|sign|submit" (str/join " " (hiccup/collect-strings %))) buttons))
    (is (= [[:actions/close-vol-range-review]]
           (get-in (hiccup/find-by-data-role html "vol-range-review-back") [1 :on :click])))
    (is (= "Close review" (get-in (hiccup/find-by-data-role html "vol-range-review-close") [1 :aria-label])))
    (is (not (re-find #":actions/(submit|confirm|place)" (pr-str html))))))

(deftest closed-review-renders-nothing-test
  (is (nil? (review/review-dialog (assoc (open-review-model) :open? false)))))

(deftest review-uses-market-base-symbol-test
  (let [model (open-review-model)
        t (str/join " " (hiccup/collect-strings (review/review-dialog model)))]
    (is (= "SYN" (:base-symbol model)))
    (is (not (str/includes? t "BASE")))))
