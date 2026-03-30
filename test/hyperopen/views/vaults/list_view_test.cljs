(ns hyperopen.views.vaults.list-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
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

(defn- find-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          child-matches (mapcat #(find-nodes % pred) children)]
      (cond-> child-matches
        (pred node) (conj node)))

    (seq? node)
    (mapcat #(find-nodes % pred) node)

    :else []))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- class-token-set [node]
  (set (class-values (get-in node [1 :class]))))

(defn- with-viewport-width
  [width f]
  (let [original-inner-width (.-innerWidth js/globalThis)]
    (set! (.-innerWidth js/globalThis) width)
    (try
      (f)
      (finally
        (set! (.-innerWidth js/globalThis) original-inner-width)))))

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

(def startup-preview-row
  {:name "Preview Vault"
   :vault-address "0x6666666666666666666666666666666666666666"
   :leader "0x7777777777777777777777777777777777777777"
   :tvl 42
   :apr 15
   :your-deposit 0
   :age-days 2
   :is-closed? false
   :snapshot-series [8 16]})

(def startup-preview-record
  {:saved-at-ms 1700000000000
   :snapshot-range :month
   :wallet-address nil
   :total-visible-tvl 42
   :protocol-rows [startup-preview-row]
   :user-rows []})

(deftest vaults-view-renders-shell-toolbar-and-sections-test
  (let [view (vaults-view/vaults-view sample-state)
        root (find-first-node view #(= "vaults-root" (get-in % [1 :data-parity-id])))
        search-input (find-first-node view #(= "vaults-search-input" (get-in % [1 :id])))
        route-connect (find-first-node view #(= "vaults-route-connect" (get-in % [1 :data-role])))
        text (set (collect-strings view))]
    (is (some? root))
    (is (some? search-input))
    (is (some? route-connect))
    (is (contains? text "Vaults"))
    (is (contains? text "Total Value Locked"))
    (is (contains? text "Protocol Vaults"))
    (is (contains? text "User Vaults"))
    (is (contains? text "Connect"))
    (is (contains? text "3M"))
    (is (contains? text "6M"))
    (is (contains? text "1Y"))
    (is (contains? text "2Y"))))

(deftest vaults-view-route-connect-button-dispatches-wallet-connect-test
  (let [view (vaults-view/vaults-view sample-state)
        route-connect (find-first-node view #(= "vaults-route-connect" (get-in % [1 :data-role])))]
    (is (some? route-connect))
    (is (= [[:actions/connect-wallet]]
           (get-in route-connect [1 :on :click])))))

(deftest vaults-view-rows-navigate-to-detail-route-test
  (let [view (vaults-view/vaults-view sample-state)
        row-link-node (find-first-node view
                                       (fn [candidate]
                                         (and (= :a (first candidate))
                                              (= "vault-row-link" (get-in candidate [1 :data-role]))
                                                 (= "/vaults/0x1111111111111111111111111111111111111111"
                                                     (get-in candidate [1 :href])))))]
    (is (some? row-link-node))))

(deftest vaults-view-focusable-controls-expose-focus-visible-rings-test
  (let [view (vaults-view/vaults-view sample-state)
        route-connect (find-first-node view #(= "vaults-route-connect" (get-in % [1 :data-role])))
        range-trigger (find-first-node view #(= "vaults-range-menu-trigger" (get-in % [1 :data-role])))
        row-link-node (find-first-node view #(= "vault-row-link" (get-in % [1 :data-role])))
        search-input (find-first-node view #(= "vaults-search-input" (get-in % [1 :id])))]
    (is (contains? (class-token-set route-connect) "focus-visible:ring-2"))
    (is (contains? (class-token-set range-trigger) "focus-visible:ring-2"))
    (is (contains? (class-token-set row-link-node) "focus-visible:ring-2"))
    (is (not (contains? (class-token-set row-link-node) "focus:ring-2")))
    (is (contains? (class-token-set search-input) "focus-visible:ring-2"))))

(deftest vaults-view-mobile-navigation-links-use-focus-visible-only-rings-test
  (with-viewport-width
    430
    (fn []
      (let [view (vaults-view/vaults-view sample-state)
            mobile-card (find-first-node view #(= "vault-mobile-card" (get-in % [1 :data-role])))]
        (is (some? mobile-card))
        (is (contains? (class-token-set mobile-card) "focus-visible:ring-2"))
        (is (not (contains? (class-token-set mobile-card) "focus:ring-2")))))))

(deftest vaults-view-renders-user-pagination-controls-test
  (let [view (vaults-view/vaults-view sample-state)
        page-size-select (find-first-node view #(= "vaults-user-page-size" (get-in % [1 :id])))
        text (set (collect-strings view))]
    (is (some? page-size-select))
    (is (contains? text "Rows"))
    (is (contains? text "Prev"))
    (is (contains? text "Next"))))

(deftest vaults-view-range-menu-options-dispatch-snapshot-range-action-test
  (let [view (vaults-view/vaults-view sample-state)
        range-menu (find-first-node view #(= "vaults-range-menu" (get-in % [1 :data-role])))
        range-trigger (find-first-node view #(= "vaults-range-menu-trigger" (get-in % [1 :data-role])))
        range-chevron (find-first-node view #(= "vaults-range-menu-chevron" (get-in % [1 :data-role])))
        range-panel (find-first-node view #(= "vaults-range-menu-panel" (get-in % [1 :data-role])))
        six-month-option (find-first-node view
                                          (fn [candidate]
                                            (and (= :button (first candidate))
                                                 (= [[:actions/set-vaults-snapshot-range :six-month]]
                                                    (get-in candidate [1 :on :click]))
                                                 (some #{"6M"} (collect-strings candidate)))))]
    (is (some? range-menu))
    (is (some? range-trigger))
    (is (some? range-chevron))
    (is (some? range-panel))
    (is (contains? (set (get-in range-chevron [1 :class])) "group-open:rotate-180"))
    (is (contains? (set (get-in range-panel [1 :class])) "ui-dropdown-panel"))
    (is (= "true" (get-in range-panel [1 :data-ui-native-details-panel])))
    (is (some? six-month-option))
    (is (= [[:actions/set-vaults-snapshot-range :six-month]]
           (get-in six-month-option [1 :on :click])))))

(deftest vaults-view-renders-skeleton-rows-when-loading-test
  (let [view (vaults-view/vaults-view (-> sample-state
                                          (assoc-in [:vaults :loading :index?] true)
                                          (assoc-in [:vaults :loading :summaries?] true)
                                          (assoc-in [:vaults :merged-index-rows] [])))
        loading-row (find-first-node view #(= "vault-loading-row" (get-in % [1 :data-role])))
        text (set (collect-strings view))]
    (is (some? loading-row))
    (is (not (contains? text "Loading vaults...")))))

(deftest vaults-view-keeps-startup-preview-visible-during-handoff-test
  (let [view (vaults-view/vaults-view (-> sample-state
                                          (assoc-in [:vaults :merged-index-rows] [])
                                          (assoc-in [:vaults :index-rows] [])
                                          (assoc-in [:vaults :startup-preview] startup-preview-record)
                                          (assoc-in [:vaults :loading :index?] true)))
        root (find-first-node view #(= "vaults-root" (get-in % [1 :data-parity-id])))
        refreshing-banner (find-first-node view #(= "vaults-refreshing-banner" (get-in % [1 :data-role])))
        loading-row (find-first-node view #(= "vault-loading-row" (get-in % [1 :data-role])))
        row-link-node (find-first-node view #(= "vault-row-link" (get-in % [1 :data-role])))
        tvl-node (find-first-node view #(= "vaults-total-visible-tvl" (get-in % [1 :data-role])))
        loading-tvl-node (find-first-node view #(= "vaults-total-visible-tvl-loading" (get-in % [1 :data-role])))
        text (set (collect-strings view))]
    (is (= "startup-preview" (get-in root [1 :data-preview-state])))
    (is (some? refreshing-banner))
    (is (= "Refreshing vaults…" (last (collect-strings refreshing-banner))))
    (is (nil? loading-row))
    (is (some? row-link-node))
    (is (some? tvl-node))
    (is (nil? loading-tvl-node))
    (is (contains? text "Preview Vault"))))

(deftest vaults-view-keeps-stale-rows-visible-while-refreshing-test
  (let [view (vaults-view/vaults-view (-> sample-state
                                          (assoc-in [:vaults :loading :index?] true)
                                          (assoc-in [:vaults :loading :summaries?] false)))
        refreshing-banner (find-first-node view #(= "vaults-refreshing-banner" (get-in % [1 :data-role])))
        loading-row (find-first-node view #(= "vault-loading-row" (get-in % [1 :data-role])))
        row-link-node (find-first-node view #(= "vault-row-link" (get-in % [1 :data-role])))
        text (set (collect-strings view))]
    (is (some? refreshing-banner))
    (is (= "Refreshing vaults…" (last (collect-strings refreshing-banner))))
    (is (nil? loading-row))
    (is (some? row-link-node))
    (is (contains? text "Total Value Locked"))))

(deftest vaults-view-desktop-layout-skips-mobile-card-subtree-test
  (with-viewport-width
    1280
    (fn []
      (let [view (vaults-view/vaults-view sample-state)
            desktop-table (find-first-node view #(= "vaults-user-vaults-table"
                                                    (get-in % [1 :data-role])))
            desktop-rows (find-nodes view #(= "vault-row" (get-in % [1 :data-role])))
            mobile-cards (find-nodes view #(= "vault-mobile-card" (get-in % [1 :data-role])))]
        (is (some? desktop-table))
        (is (= 2 (count desktop-rows)))
        (is (= 0 (count mobile-cards)))))))

(deftest vaults-view-mobile-layout-skips-desktop-table-subtree-test
  (with-viewport-width
    430
    (fn []
      (let [view (vaults-view/vaults-view sample-state)
            desktop-table (find-first-node view #(= "vaults-user-vaults-table"
                                                    (get-in % [1 :data-role])))
            desktop-rows (find-nodes view #(= "vault-row" (get-in % [1 :data-role])))
            mobile-cards (find-nodes view #(= "vault-mobile-card" (get-in % [1 :data-role])))]
        (is (nil? desktop-table))
        (is (= 0 (count desktop-rows)))
        (is (= 2 (count mobile-cards)))))))

(deftest vaults-view-tablet-layout-uses-mobile-cards-to-avoid-overflow-test
  (with-viewport-width
    768
    (fn []
      (let [view (vaults-view/vaults-view sample-state)
            desktop-table (find-first-node view #(= "vaults-user-vaults-table"
                                                    (get-in % [1 :data-role])))
            desktop-rows (find-nodes view #(= "vault-row" (get-in % [1 :data-role])))
            mobile-cards (find-nodes view #(= "vault-mobile-card" (get-in % [1 :data-role])))]
        (is (nil? desktop-table))
        (is (= 0 (count desktop-rows)))
        (is (= 2 (count mobile-cards)))))))
