(ns hyperopen.views.l2-orderbook.depth-transition-test
  "Guards the depth-bar transition directly on `order-row` rather than by walking a
   rendered view tree, so the assertions stay readable and stay out of the oversized
   l2-orderbook-view suite."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.l2-orderbook.depth :as depth]
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

(deftest depth-bar-transition-is-width-only-short-and-non-overshooting-test
  ;; The incremental `l2` feed retargets these bars roughly every 550ms. The previous
  ;; 300ms ease-[cubic-bezier(0.68,-0.6,0.32,1.6)] curve is an easeInOutBack: its control
  ;; points sit below 0 and above 1, so each bar undershoots then overshoots. At a 550ms
  ;; cadence that reads as a wobble. `transition-all` also animated properties that never
  ;; change on these nodes.
  (testing "the class list itself"
    (is (= ["transition-[width]" "duration-100" "ease-out"]
           styles/depth-bar-transition-classes)))
  (testing "as rendered onto an animated row"
    (let [classes (class-set (depth/order-row row :base true))]
      (is (contains? classes "transition-[width]"))
      (is (contains? classes "duration-100"))
      (is (contains? classes "ease-out"))
      (is (not (contains? classes "transition-all")))
      (is (not (contains? classes "duration-300")))
      (is (not (contains? classes "ease-[cubic-bezier(0.68,-0.6,0.32,1.6)]"))))))

(deftest depth-bar-transition-is-absent-when-animation-is-disabled-test
  (let [classes (class-set (depth/order-row row :base false))]
    (is (not (contains? classes "transition-[width]")))
    (is (not (contains? classes "duration-100")))
    (is (not (contains? classes "ease-out")))))
