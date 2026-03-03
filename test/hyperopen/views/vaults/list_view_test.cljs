(ns hyperopen.views.vaults.list-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.vaults.list-view :as vaults-view]))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(def sample-state
  {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
   :vaults-ui {:search-query ""
               :filter-leading? true
               :filter-deposited? true
               :filter-others? true
               :filter-closed? false
               :snapshot-range :month
               :user-vaults-page-size 10
               :user-vaults-page 1
               :sort {:column :tvl
                      :direction :desc}}
   :vaults {:loading {:index? false
                      :summaries? false}
            :errors {:index nil
                     :summaries nil}
            :user-equity-by-address {"0x2222222222222222222222222222222222222222" {:equity 25}}
            :merged-index-rows [{:name "Alpha"
                                 :vault-address "0x1111111111111111111111111111111111111111"
                                 :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                 :tvl 120
                                 :apr 0.12
                                 :is-closed? false
                                 :create-time-ms (- (.now js/Date) (* 3 24 60 60 1000))
                                 :snapshot-by-key {:month [0.1 0.2]}}
                                {:name "Beta"
                                 :vault-address "0x2222222222222222222222222222222222222222"
                                 :leader "0x3333333333333333333333333333333333333333"
                                 :tvl 80
                                 :apr 0.08
                                 :is-closed? false
                                 :create-time-ms (- (.now js/Date) (* 8 24 60 60 1000))
                                 :snapshot-by-key {:month [0.05 0.09]}}]}})

(deftest vaults-view-renders-shell-toolbar-and-sections-test
  (let [view (vaults-view/vaults-view sample-state)
        root (find-first-node view #(= "vaults-root" (get-in % [1 :data-parity-id])))
        search-input (find-first-node view #(= "vaults-search-input" (get-in % [1 :id])))
        text (set (collect-strings view))]
    (is (some? root))
    (is (some? search-input))
    (is (contains? text "Vaults"))
    (is (contains? text "Total Value Locked"))
    (is (contains? text "Protocol Vaults"))
    (is (contains? text "User Vaults"))
    (is (contains? text "3M"))
    (is (contains? text "6M"))
    (is (contains? text "1Y"))
    (is (contains? text "2Y"))))

(deftest vaults-view-rows-navigate-to-detail-route-test
  (let [view (vaults-view/vaults-view sample-state)
        row-link-node (find-first-node view
                                       (fn [candidate]
                                         (and (= :a (first candidate))
                                              (= "vault-row-link" (get-in candidate [1 :data-role]))
                                              (= "/vaults/0x1111111111111111111111111111111111111111"
                                                 (get-in candidate [1 :href])))))]
    (is (some? row-link-node))))

(deftest vaults-view-renders-user-pagination-controls-test
  (let [view (vaults-view/vaults-view sample-state)
        page-size-select (find-first-node view #(= "vaults-user-page-size" (get-in % [1 :id])))
        text (set (collect-strings view))]
    (is (some? page-size-select))
    (is (contains? text "Rows"))
    (is (contains? text "Prev"))
    (is (contains? text "Next"))))

(deftest vaults-view-renders-skeleton-rows-when-loading-test
  (let [view (vaults-view/vaults-view (-> sample-state
                                          (assoc-in [:vaults :loading :index?] true)
                                          (assoc-in [:vaults :loading :summaries?] true)
                                          (assoc-in [:vaults :merged-index-rows] [])))
        loading-row (find-first-node view #(= "vault-loading-row" (get-in % [1 :data-role])))
        text (set (collect-strings view))]
    (is (some? loading-row))
    (is (not (contains? text "Loading vaults...")))))
