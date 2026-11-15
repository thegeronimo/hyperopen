(ns hyperopen.views.portfolio.optimize.execution-order-table-reconcile-test
  "Reconciliation coverage for the order list across a Resume.

  Resume demotes every already-live row to :skipped (:already-resting / :already-filled),
  so a list filtered to WORKING empties out and the body swaps its <table> for an
  empty-state <p>. Replicant walks children positionally: a tag swap it cannot reuse
  desyncs the walk from the old vdom, and the keyed skipped <details> then lines up
  against the nil left by the (post-run, always nil) auto-exit strip. Reconciling a keyed
  child against a nil calls remove-child with a nil vdom node, which reads (aget nil 9)
  and throws.

  A throw inside replicant.dom/render leaves :rendering? true forever, so every later
  render only queues — the whole surface freezes, toasts included. These tests render the
  real view functions through Replicant's DOM-free mutation-log renderer."
  (:require [cljs.test :refer [deftest is testing]]
            [hyperopen.views.portfolio.optimize.execution-order-table :as order-table]
            [replicant.core :as replicant-core]
            [replicant.mutation-log :as mlog]))

(defn- reconcile-frames
  "Reconciles `frames` in order against one element, threading vdom + element state the
  way replicant.dom/render does. Returns {:ok? true} or {:ok? false :error e :step n}."
  [frames]
  (loop [[hiccup & more] frames
         element nil
         vdom nil
         step 0]
    (if (nil? hiccup)
      {:ok? true}
      (let [result (try
                     (binding [replicant-core/*dispatch* (fn [& _] nil)]
                       (mlog/render element hiccup vdom))
                     (catch :default e {::error e}))]
        (if-let [error (::error result)]
          {:ok? false :error error :step step}
          (recur more (:element (:el result)) (:vdom result) (inc step)))))))

(defn- row
  [order-no instrument-id status]
  {:row-id instrument-id
   :instrument-id instrument-id
   :instrument-label instrument-id
   :instrument-type :perp
   :order-no order-no
   :status status
   :reason (when (= :skipped status) :already-resting)
   :side :sell
   :quantity 1
   :order-type :limit
   :delta-notional-usd -100})

(def ^:private post-run-model
  {:phase :partial
   :order-filter :working
   :labels-by-instrument {}})

(deftest working-list-emptied-by-resume-still-reconciles-test
  (testing "the WORKING list going empty must not break the render"
    (let [before (order-table/order-table post-run-model
                                          [(row 21 "perp:BTC" :resting)
                                           (row 22 "perp:ETH" :skipped)])
          after (order-table/order-table post-run-model
                                         [(row 21 "perp:BTC" :skipped)
                                          (row 22 "perp:ETH" :skipped)])
          {:keys [ok? error step]} (reconcile-frames [before after])]
      (is (true? ok?)
          (str "render " step " threw: " (some-> error .-message))))))

(deftest order-list-body-keeps-one-tag-test
  (testing "table and empty state render under the same always-present slot"
    (let [tag-of (fn [rows]
                   (->> (order-table/order-table post-run-model rows)
                        (drop 2)
                        (filter #(= "portfolio-optimizer-execution-order-list-body"
                                    (get-in % [1 :data-role])))
                        first
                        first))]
      (is (= :div (tag-of [(row 21 "perp:BTC" :resting)])))
      (is (= :div (tag-of [(row 21 "perp:BTC" :skipped)])))
      (is (= :div (tag-of []))))))

(deftest keyed-child-landing-on-nil-hole-still-throws-test
  (testing "a keyed sibling swapping places with a nil hole crashes reconciliation"
    (let [{:keys [ok? error]}
          (reconcile-frames [[:div nil [:p {:replicant/key "k"} "keyed"]]
                             [:div [:p {:replicant/key "k"} "keyed"] nil]])]
      (is (false? ok?))
      (is (some? error)))))
