(ns hyperopen.views.leaderboard-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.leaderboard-view :as view]))

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

(defn- with-viewport-width
  [width f]
  (let [original-inner-width (.-innerWidth js/globalThis)]
    (set! (.-innerWidth js/globalThis) width)
    (try
      (f)
      (finally
        (set! (.-innerWidth js/globalThis) original-inner-width)))))

(def sample-state
  {:wallet {:address "0x2222222222222222222222222222222222222222"}
   :leaderboard-ui {:query ""
                    :timeframe :month
                    :sort {:column :pnl
                           :direction :desc}
                    :page 1}
   :leaderboard {:rows [{:eth-address "0x1111111111111111111111111111111111111111"
                         :account-value 1000
                         :display-name "Alpha"
                         :window-performances {:day {:pnl 10 :roi 0.01 :volume 100}
                                               :week {:pnl 20 :roi 0.02 :volume 200}
                                               :month {:pnl 30 :roi 0.03 :volume 300}
                                               :all-time {:pnl 40 :roi 0.04 :volume 400}}}
                        {:eth-address "0x2222222222222222222222222222222222222222"
                         :account-value 2000
                         :display-name "Bravo"
                         :window-performances {:day {:pnl 5 :roi 0.005 :volume 90}
                                               :week {:pnl 15 :roi 0.015 :volume 190}
                                               :month {:pnl 25 :roi 0.025 :volume 290}
                                               :all-time {:pnl 35 :roi 0.035 :volume 390}}}]
                 :excluded-addresses #{}
                 :loading? false
                 :error nil
                 :loaded-at-ms 1700000000000}})

(deftest leaderboard-view-renders-shell-controls-and-methodology-test
  (with-viewport-width
    1280
    (fn []
      (let [view-node (view/leaderboard-view sample-state)
            root (find-first-node view-node #(= "leaderboard-root" (get-in % [1 :data-parity-id])))
            search-input (find-first-node view-node #(= "leaderboard-search" (get-in % [1 :id])))
            pinned-row (find-first-node view-node #(= "leaderboard-pinned-row" (get-in % [1 :data-role])))
            table-node (find-first-node view-node #(= "leaderboard-table" (get-in % [1 :data-role])))
            methodology (find-first-node view-node #(= "leaderboard-methodology" (get-in % [1 :data-role])))
            control-shell (find-first-node view-node #(= ["rounded-xl" "border" "border-base-300/80" "bg-base-100/95" "p-2.5" "md:p-3"]
                                                         (get-in % [1 :class])))
            text (set (collect-strings view-node))]
        (is (some? root))
        (is (some? search-input))
        (is (some? pinned-row))
        (is (some? table-node))
        (is (some? methodology))
        (is (some? control-shell))
        (is (string? (get-in root [1 :style :background-image])))
        (is (contains? text "Leaderboard"))
        (is (contains? text "Methodology"))
        (is (contains? text "Read-only ranking surface"))
        (is (contains? text "Pinned separately from paginated results."))))))

(deftest leaderboard-view-timeframe-and-retry-actions-are-wired-test
  (let [view-node (view/leaderboard-view (assoc-in sample-state [:leaderboard :error] "Network issue"))
        month-button (find-first-node view-node
                                      (fn [node]
                                        (and (= :button (first node))
                                             (contains? (set (collect-strings node)) "Month"))))
        retry-button (find-first-node view-node
                                      (fn [node]
                                        (and (= :button (first node))
                                             (contains? (set (collect-strings node)) "Retry"))))]
    (is (= [[:actions/set-leaderboard-timeframe :month]]
           (get-in month-button [1 :on :click])))
    (is (= [[:actions/load-leaderboard]]
           (get-in retry-button [1 :on :click])))))

(deftest leaderboard-view-mobile-layout-renders-cards-instead-of-table-test
  (with-viewport-width
    430
    (fn []
      (let [view-node (view/leaderboard-view sample-state)
            table-node (find-first-node view-node #(= "leaderboard-table" (get-in % [1 :data-role])))
            mobile-list (find-first-node view-node #(= "leaderboard-mobile-list" (get-in % [1 :data-role])))
            mobile-links (find-nodes view-node #(= "leaderboard-address-link" (get-in % [1 :data-role])))]
        (is (nil? table-node))
        (is (some? mobile-list))
        (is (pos? (count mobile-links)))))))
