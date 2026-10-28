(ns hyperopen.vol-range.application.grid-store-test
  "Monotonic store merges for ladder copies (HO-SPEC-014 v2.1 sections 8.2,
  9.1): a flow finishing with an older copy never erases evidence the store
  already holds."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.grid-store :as grid-store]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.live-fixtures :as fx]))

(def full-fill
  "All 5 of buy rung 0."
  (assoc fx/partial-fill :sz "5"))

(defn- with-fill [grid row]
  (evidence/ingest-fills grid {:account-address fx/account :rows [row] :received-at-ms fx/received-at-ms}))

(defn- state-with [& grids]
  {:vol-range {:execution {:busy {} :grids (into {} (map (juxt :grid-id identity)) grids)}}})

(defn- grids [state] (get-in state [:vol-range :execution :grids]))

(defn- buy-0 [grid] (get-in grid [:legs fx/cloid-buy-0]))

(defn- for-account [grid grid-id account]
  (-> grid (assoc :grid-id grid-id) (assoc-in [:scope :account-address] account)))

(deftest merge-grid-into-state-test
  (let [placed (fx/grid)
        filled (with-fill placed full-fill)]
    (testing "fixture: the full fill makes buy rung 0 terminal"
      (is (= :terminal (:order-state (buy-0 filled))))
      (is (= 1 (count (:fills filled)))))
    (testing "a nil grid leaves state untouched"
      (let [s (state-with placed)]
        (is (identical? s (grid-store/merge-grid-into-state s nil)))))
    (testing "a grid not yet in the store is added as is"
      (is (= placed (get (grids (grid-store/merge-grid-into-state (state-with) placed)) "grid-live"))))
    (testing "an older copy finishing later keeps the store's fill and terminal leg"
      (let [g (get (grids (grid-store/merge-grid-into-state (state-with filled) placed)) "grid-live")]
        (is (= (:fills filled) (:fills g)))
        (is (= :terminal (:order-state (buy-0 g))))
        (is (= "5" (:filled (lifecycle/leg-quantities g (buy-0 g)))))))
    (testing "a newer copy adds its evidence"
      (let [g (get (grids (grid-store/merge-grid-into-state (state-with placed) filled)) "grid-live")]
        (is (= (:fills filled) (:fills g)))
        (is (= :terminal (:order-state (buy-0 g))))))
    (testing "the same fill from both copies is counted once"
      (let [partial (with-fill placed fx/partial-fill)
            g (get (grids (grid-store/merge-grid-into-state (state-with partial) partial)) "grid-live")]
        (is (= 1 (count (:fills g))))
        (is (= "2" (:filled (lifecycle/leg-quantities g (buy-0 g)))))))
    (testing "other grids and other execution keys are untouched"
      (let [other (assoc placed :grid-id "grid-other")
            s (assoc-in (state-with filled other) [:vol-range :execution :busy] {[:sync] true})
            s* (grid-store/merge-grid-into-state s placed)]
        (is (identical? other (get (grids s*) "grid-other")))
        (is (= {[:sync] true} (get-in s* [:vol-range :execution :busy])))))))

(deftest merge-account-grids-test
  (let [placed (fx/grid)
        filled (with-fill placed full-fill)
        journal-other (for-account placed "grid-def" "0xdef")
        store-only (assoc placed :grid-id "grid-store-only")]
    (testing "journal grids of the account keep store-only evidence; everything else is dropped"
      (let [s (state-with filled store-only (for-account placed "grid-prev" "0xdef"))
            gs (grids (grid-store/merge-account-grids s "0xABC" [placed journal-other]))]
        (is (= #{"grid-live"} (set (keys gs))))
        (is (= (:fills filled) (:fills (get gs "grid-live"))))
        (is (= :terminal (:order-state (buy-0 (get gs "grid-live")))))))
    (testing "journal evidence the store lacks is kept"
      (let [gs (grids (grid-store/merge-account-grids (state-with placed) fx/account [filled]))]
        (is (= (:fills filled) (:fills (get gs "grid-live"))))))
    (testing "a store copy of the same id for another account is not merged"
      (let [foreign (for-account filled "grid-live" "0xdef")
            gs (grids (grid-store/merge-account-grids (state-with foreign) fx/account [placed]))]
        (is (= placed (get gs "grid-live")))))
    (testing "a grid new to the store comes from the journal as is"
      (is (= {"grid-live" placed} (grids (grid-store/merge-account-grids (state-with) fx/account [placed])))))
    (testing "no account, no grids"
      (is (= {} (grids (grid-store/merge-account-grids (state-with filled) nil [placed])))))))
