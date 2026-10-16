(ns hyperopen.views.l2-orderbook.depth-transition-test
  "Guards the depth-bar transition directly on `order-row` rather than by walking a
   rendered view tree, so the assertions stay readable and stay out of the oversized
   l2-orderbook-view suite."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.l2-orderbook.depth :as depth]
            [hyperopen.views.l2-orderbook.panel :as panel]
            [hyperopen.views.l2-orderbook.styles :as styles]))

(defn- class-set [node]
  (cond
    (string? node) #{}
    (vector? node) (let [attrs (when (map? (second node)) (second node))
                         own (set (:class attrs))
                         children (if attrs (drop 2 node) (drop 1 node))]
                     (reduce into own (map class-set children)))
    (seq? node) (reduce into #{} (map class-set node))
    :else #{}))

(def ^:private row
  {:px "100" :sz "2" :side :bid :cum-size 2 :cum-value 200})

(defn- find-node [node pred]
  (cond
    (and (vector? node) (pred node)) node
    (vector? node) (some #(find-node % pred) (if (map? (second node)) (drop 2 node) (drop 1 node)))
    (seq? node) (some #(find-node % pred) node)
    :else nil))

(defn- bar-node [node]
  (find-node node #(contains? (set (:class (second %))) "ob-bid-bar")))

(deftest depth-bar-transition-is-transform-only-and-non-overshooting-test
  ;; The incremental `l2` feed retargets these bars roughly every 550ms. The bars are
  ;; full-width fills scaled along x so the animation stays on the compositor. The
  ;; earlier easeInOutBack curve (control points below 0 and above 1) overshot each
  ;; target and read as a wobble at that cadence.
  (testing "the class list itself"
    (is (= ["transition-transform" "duration-200" "ease-out"]
           styles/depth-bar-transition-classes)))
  (testing "as rendered onto an animated row"
    (let [classes (class-set (depth/order-row row :base true))]
      (is (contains? classes "transition-transform"))
      (is (contains? classes "duration-200"))
      (is (contains? classes "ease-out"))
      (is (not (contains? classes "transition-all")))
      (is (not (contains? classes "transition-[width]")))
      (is (not (contains? classes "ease-[cubic-bezier(0.68,-0.6,0.32,1.6)]"))))))

(deftest depth-bar-is-a-scaled-full-width-fill-test
  (let [bar (bar-node (depth/order-row (assoc row :max-total-by-unit {:base 8}) :base true))
        attrs (second bar)]
    (is (= "scaleX(0.25)" (get-in attrs [:style :transform])))
    (is (contains? (set (:class attrs)) "origin-left"))
    (is (contains? (set (:class attrs)) "inset-0"))
    (is (not (contains? (:style attrs) :width)))))

(deftest depth-bar-transition-is-absent-when-animation-is-disabled-test
  (let [classes (class-set (depth/order-row row :base false))]
    (is (not (contains? classes "transition-transform")))
    (is (not (contains? classes "duration-200")))
    (is (not (contains? classes "ease-out")))))

(defn- row-classes [node]
  (set (:class (second node))))

(deftest changed-level-flashes-with-alternating-animation-test
  (testing "a level that changed in an even update"
    (is (contains? (row-classes (depth/order-row row :base true 2)) "ob-flash-bid-a")))
  (testing "the same level changing again in the next update switches animation"
    (is (contains? (row-classes (depth/order-row row :base true 3)) "ob-flash-bid-b")))
  (testing "asks flash in the ask colour"
    (is (contains? (row-classes (depth/order-row (assoc row :side :ask) :base true 4))
                   "ob-flash-ask-a"))))

(deftest unchanged-or-unanimated-level-does-not-flash-test
  (let [flash? (fn [node] (some #(.startsWith % "ob-flash-") (row-classes node)))]
    (is (not (flash? (depth/order-row row :base true nil))))
    (is (not (flash? (depth/order-row row :base true))))
    (is (not (flash? (depth/order-row row :base false 2))))))

(deftest mobile-split-row-flashes-each-side-independently-test
  (let [split {:bid row :ask (assoc row :side :ask :px "101")}
        node (depth/mobile-split-order-row split :base true 6 nil)
        classes (class-set node)]
    (is (contains? classes "ob-flash-bid-a"))
    (is (not (contains? classes "ob-flash-ask-a")))
    (is (not (contains? classes "ob-flash-ask-b")))))

(defn- collect-nodes [node pred]
  (cond
    (vector? node) (concat (when (pred node) [node])
                           (mapcat #(collect-nodes % pred)
                                   (if (map? (second node)) (drop 2 node) (drop 1 node))))
    (seq? node) (mapcat #(collect-nodes % pred) node)
    :else []))

(deftest panel-flashes-only-the-levels-the-book-reports-as-changed-test
  (let [orderbook-data {:bids [{:px "100" :sz "2"} {:px "99" :sz "1"}]
                        :asks [{:px "101" :sz "3"} {:px "102" :sz "4"}]
                        :changed-levels {:bids #{"99"} :asks #{"102"}}
                        :flash-version 5}
        node (panel/l2-orderbook-panel "BTC"
                                       {:market-type :perp :base "BTC" :quote "USDC" :szDecimals 4}
                                       orderbook-data
                                       {:size-unit :base}
                                       nil
                                       false
                                       {:desktop-layout? true}
                                       true)
        rows (collect-nodes node #(= "orderbook-level-row" (:data-role (second %))))
        flash-by-price (into {}
                             (map (fn [r]
                                    [(-> (find-node r #(= "orderbook-level-price-cell" (:data-role (second %))))
                                         last last)
                                     (some #(when (.startsWith % "ob-flash-") %) (row-classes r))]))
                             rows)]
    (is (= {"100" nil "99" "ob-flash-bid-b" "101" nil "102" "ob-flash-ask-b"}
           flash-by-price))))
