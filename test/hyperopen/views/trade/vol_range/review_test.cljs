(ns hyperopen.views.trade.vol-range.review-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.vol-range-contracts :as contracts]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vol-range.application.projections :as projections]
            [hyperopen.vol-range.application.review-model :as review-model]
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

(deftest review-dialog-traps-focus-and-returns-it-test
  (let [html (review/review-dialog (open-review-model))
        attrs (second (hiccup/find-by-data-role html "vol-range-review"))]
    (is (fn? (:replicant/on-render attrs))
        "the shared dialog-focus hook traps Tab and restores focus on close")))

(deftest review-details-report-real-source-ages-test
  (let [m (open-review-model)
        detail (fn [label] (:value (first (filter #(= label (:label %)) (:details m)))))]
    (is (= "13:59:59 UTC · 0.4 s before plan" (detail "Book last changed")))
    (is (= "Custom anchor" (detail "Anchor observed")))
    (is (str/ends-with? (detail "Fee quote loaded") "· stale") "the synthetic quote is far older than 5 minutes")
    (testing "an old source is flagged stale at the frozen plan time"
      (is (= "13:59:50 UTC · 10 s before plan · stale"
             (review-model/source-age-text (- sf/now-ms 10000) sf/now-ms 2000))))))

(def ^:private replacement
  {:lineage-text "Replaces the ladder created 2026-10-28 14:02 UTC."
   :remaining-text "Unfilled remainder: buys 40 VRT, sells 27 VRT."
   :filled-text "Filled while canceling: sells 3 VRT."
   :rejected-text "Rejected orders from the old ladder are not included."
   :edited-text nil})

(deftest review-model-has-no-lineage-and-validates-test
  (let [m (open-review-model)]
    (is (nil? (:replacement m)) "the A0 review model never reads lineage")
    (is (contracts/review-model-valid? m))
    (is (contracts/review-model-valid? (assoc m :replacement replacement)))
    (is (contracts/review-model-valid? (assoc m :replacement (assoc replacement :filled-text nil
                                                                    :edited-text "You changed the quantities from the remainder."))))
    (is (not (contracts/review-model-valid? (assoc m :replacement (assoc replacement :extra 1)))))
    (is (not (contracts/review-model-valid? (assoc m :replacement (dissoc replacement :edited-text)))))))

(deftest review-shows-the-replacement-lineage-above-the-orders-test
  (let [html (review/review-dialog (assoc (open-review-model) :replacement replacement))
        section (hiccup/find-by-data-role html "vol-range-review-lineage")
        roles (keep #(:data-role (hiccup/node-attrs %)) (hiccup/find-all-nodes html vector?))]
    (is (= (str "Replaces the ladder created 2026-10-28 14:02 UTC. Unfilled remainder: buys 40 VRT, sells 27 VRT."
                " Filled while canceling: sells 3 VRT.")
           (str/join "" (hiccup/collect-strings (first (hiccup/node-children section))))))
    (is (= "Rejected orders from the old ladder are not included."
           (str/join "" (hiccup/collect-strings (hiccup/find-by-data-role html "vol-range-review-rejected")))))
    (is (nil? (hiccup/find-by-data-role html "vol-range-review-edited")))
    (is (< (.indexOf (vec roles) "vol-range-review-lineage") (.indexOf (vec roles) "vol-range-review-legs"))
        "the section sits above the orders table")
    (is (str/includes? (str/join " " (hiccup/collect-strings
                                      (hiccup/find-by-data-role
                                       (review/review-dialog (assoc (open-review-model) :replacement
                                                                    (assoc replacement :edited-text "You changed the quantities from the remainder.")))
                                       "vol-range-review-edited")))
                       "You changed the quantities")))
  (is (nil? (hiccup/find-by-data-role (review/review-dialog (open-review-model)) "vol-range-review-lineage"))
      "an ordinary review has no lineage section"))
