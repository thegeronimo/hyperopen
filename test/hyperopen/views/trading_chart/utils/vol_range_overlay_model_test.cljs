(ns hyperopen.views.trading-chart.utils.vol-range-overlay-model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.trading-chart.utils.vol-range-overlay-model :as model]))

(defn- price->y [price] (- 400 (* 4 price)))

(def chart-model
  {:stage :draft :stage-label "Draft"
   :anchor {:price "80" :label "Anchor 80"}
   :shifted-anchor nil
   :bands [{:sigma 1 :lower "75.2" :upper "84.8"}
           {:sigma 2 :lower "70.4" :upper "89.6"}]
   :legs [{:leg-id "b1" :side :buy :price "76.4" :size "7" :weight 0.5 :label "Buy 7 @ 76.4"}
          {:leg-id "s1" :side :sell :price "86" :size "10" :weight 1 :label "Sell 10 @ 86"}]})

(deftest bands-are-ordered-and-top-is-above-bottom-test
  (let [layout (model/overlay-layout chart-model price->y 300 800)]
    (is (= [2 1] (mapv :sigma (:bands layout))) "wider band paints first")
    (is (every? #(< (:top %) (:bottom %)) (:bands layout)))
    (is (= "Draft" (:stage-label layout)))
    (is (= {:y 80 :label "Anchor 80"} (:anchor layout)))))

(deftest shifted-anchor-only-when-present-test
  (is (nil? (:shifted-anchor (model/overlay-layout chart-model price->y 300 800))))
  (let [shifted (assoc chart-model :shifted-anchor {:price "79" :label "Shifted anchor 79"})
        layout (model/overlay-layout shifted price->y 300 800)]
    (is (= {:y 84 :label "Shifted anchor 79"} (:shifted-anchor layout)))))

(deftest off-screen-items-are-culled-test
  (let [layout (model/overlay-layout chart-model (fn [p] (if (< p 80) 1000 (price->y p))) 300 800)]
    (is (= ["s1"] (mapv :leg-id (:legs layout))))
    (is (= [] (mapv :sigma (filter #(> (:top %) 330) (:bands layout))))))
  (testing "non-finite coordinates drop the item"
    (let [layout (model/overlay-layout chart-model (fn [_] js/NaN) 300 800)]
      (is (= [] (:legs layout)))
      (is (= [] (:bands layout)))
      (is (nil? (:anchor layout)))))
  (testing "no model, no layout"
    (is (nil? (model/overlay-layout nil price->y 300 800)))))

(deftest thickness-encodes-weight-within-one-to-three-px-test
  (let [by-id (into {} (map (juxt :leg-id identity))
                    (:legs (model/overlay-layout chart-model price->y 300 800)))]
    (is (= 2 (:thickness (get by-id "b1"))))
    (is (= 3 (:thickness (get by-id "s1")))))
  (is (= 1 (model/thickness-for-weight 0)))
  (is (= 3 (model/thickness-for-weight 7)))
  (is (= 3 (model/thickness-for-weight nil))))

(deftest labels-sit-above-lines-and-do-not-overlap-test
  (let [close (assoc chart-model :legs
                     [{:leg-id "a" :side :buy :price "76.4" :size "1" :weight 1 :label "A"}
                      {:leg-id "b" :side :buy :price "76.3" :size "1" :weight 1 :label "B"}
                      {:leg-id "c" :side :buy :price "76.2" :size "1" :weight 1 :label "C"}])
        legs (:legs (model/overlay-layout close price->y 300 800))
        ys (map :label-y legs)]
    (is (every? #(< (:label-y %) (:y %)) (take 1 legs)) "first label sits above its line")
    (is (every? (fn [[a b]] (>= (- b a) 14)) (partition 2 1 ys)))))
