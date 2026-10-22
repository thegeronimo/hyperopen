(ns hyperopen.vol-range.domain.grid-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.precision :as p]
            [hyperopen.vol-range.domain.grid :as grid]
            [hyperopen.vol-range.fixtures :as fx]))

(defn- pd [s] (p/parse-decimal s))
(defn- ds [x] (p/decimal->string x))

(defn- tilt [{:keys [q core target maximum tilt?]}]
  (let [delta (grid/inventory-delta {:q (pd q) :core (pd core) :target (pd target) :maximum (pd maximum)})
        shifted (grid/shifted-anchor {:anchor (pd "80") :v (pd "0.06") :kappa (pd "0.45")
                                      :delta delta :tilt-enabled? tilt?})]
    {:delta (ds delta) :price (some-> (:price shifted) ds) :shifted shifted}))

(deftest inventory-tilt-test
  (is (= "80" (:price (tilt {:q "150" :core "120" :target "160" :maximum "190" :tilt? false}))))
  (let [r (tilt {:q "150" :core "120" :target "160" :maximum "190" :tilt? true})]
    (is (= "-0.25" (:delta r)))
    (is (= "80.54" (:price r))))
  (let [r (tilt {:q "190" :core "120" :target "160" :maximum "190" :tilt? true})]
    (is (= "1" (:delta r)))
    (is (= "77.84" (:price r))))
  (is (= "0" (:delta (tilt {:q "170" :core "120" :target "170" :maximum "170" :tilt? true}))))
  (is (= "1" (:delta (tilt {:q "171" :core "120" :target "170" :maximum "170" :tilt? true}))))
  (is (= "-1" (:delta (tilt {:q "100" :core "160" :target "160" :maximum "190" :tilt? true}))))
  (is (= "1" (:delta (tilt {:q "500" :core "120" :target "160" :maximum "190" :tilt? true}))))
  (is (= "-1" (:delta (tilt {:q "10" :core "120" :target "160" :maximum "190" :tilt? true}))))
  (is (= "0.1125" (grid/shift-sigma (pd "0.45") (pd "-0.25")))))

(deftest shifted-anchor-nonpositive-test
  (is (= :plan/nonpositive-price
         (:error (grid/shifted-anchor {:anchor (pd "80") :v (pd "3") :kappa (pd "1") :delta (pd "1")
                                       :tilt-enabled? true})))))

(deftest bands-and-excursion-test
  (is (= {:minus-2 "70.4" :minus-1 "75.2" :plus-1 "84.8" :plus-2 "89.6"}
         (grid/bands {:anchor (pd "80") :v (pd "0.06")})))
  ;; (75.92/80 - 1)/0.06 = -0.85 (spec section 5.5 example text)
  (is (= "-0.85" (grid/excursion-sigma {:mid (pd "75.92") :anchor (pd "80") :v (pd "0.06")})))
  (is (= "-1" (ds (pd (grid/excursion-sigma {:mid (pd "75.2") :anchor (pd "80") :v (pd "0.06")})))))
  (is (nil? (grid/excursion-sigma {:mid (pd "75") :anchor (pd "80") :v (pd "0")})))
  (is (nil? (grid/excursion-sigma {:mid (pd "75") :anchor (pd "80") :v nil}))))

(defn- seven-side [side]
  (let [in fx/seven-order-inputs
        s (get in side)]
    (grid/build-side-legs {:side side :r (pd (:anchor in)) :v (pd (:daily-fraction in))
                           :z-start (pd (:z-start s)) :z-end (pd (:z-end s))
                           :count (:count s) :quantity (pd (:quantity s)) :skew (pd (:skew s))
                           :sz-decimals (:sz-decimals in) :market-type (:market-type in)})))

(defn- row [leg] [(:price leg) (:size leg) (:notional leg) (:actual-sigma leg)])

(deftest seven-order-legs-test
  (let [{:keys [legs errors]} (seven-side :buy)]
    (is (empty? errors))
    (is (= [["76.4" "7" "534.8" "-0.75"] ["74" "9" "666" "-1.25"]
            ["71.6" "11" "787.6" "-1.75"] ["69.2" "13" "899.6" "-2.25"]]
           (mapv row legs)))
    (is (= ["buy-0" "buy-1" "buy-2" "buy-3"] (mapv :leg-id legs)))
    (is (= "76.4" (:raw-price (first legs)))))
  (let [{:keys [legs errors]} (seven-side :sell)]
    (is (empty? errors))
    (is (= [["86" "10" "860" "1.25"] ["88.4" "10" "884" "1.75"] ["90.8" "10" "908" "2.25"]]
           (mapv row legs)))
    (is (= :sell (:side (first legs))))))

(deftest single-rung-test
  (let [r (grid/raw-rung-price {:r (pd "80") :v (pd "0.06") :z-start (pd "0.75") :z-end (pd "0.75")
                                :count 1 :index 0 :side :buy})]
    (is (= "76.4" (ds r))))
  (let [{:keys [legs errors]} (grid/build-side-legs
                               {:side :buy :r (pd "80") :v (pd "0.06") :z-start (pd "0.75")
                                :z-end (pd "0.75") :count 1 :quantity (pd "5") :skew (pd "1")
                                :sz-decimals 2 :market-type :spot})]
    (is (empty? errors))
    (is (= [["76.4" "5" "382" "-0.75"]] (mapv row legs)))))

(deftest nonpositive-price-test
  (let [{:keys [legs errors]} (grid/build-side-legs
                               {:side :buy :r (pd "80") :v (pd "0.5") :z-start (pd "2.25")
                                :z-end (pd "2.25") :count 1 :quantity (pd "5") :skew (pd "1")
                                :sz-decimals 2 :market-type :spot})]
    (is (= [:plan/nonpositive-price] (mapv :code errors)))
    (is (= {:rung-index 0 :side :buy} (:data (first errors))))
    (is (empty? legs))))

(defn- alloc [q count skew]
  (grid/allocate-lots {:quantity (pd q) :sz-decimals 2 :count count :skew (pd skew)}))

(deftest allocation-test
  (is (= ["0.34" "0.33" "0.33"] (mapv ds (:sizes (alloc "1" 3 "1")))))
  (is (= [:plan/zero-size-rung] (distinct (mapv :code (:errors (alloc "0.02" 3 "1"))))))
  (let [e (first (:errors (alloc "0.295" 3 "1")))]
    (is (= :input/quantity-not-lot-multiple (:code e)))
    (is (= "0.29" (get-in e [:data :suggested-down])))
    (is (= "0.3" (get-in e [:data :suggested-up]))))
  (is (= ["7" "9" "11" "13"] (mapv ds (:sizes (alloc "40" 4 "1.857142857143")))))
  (doseq [[q k s] [["40" 4 "1.857142857143"] ["1" 7 "3.5"] ["12.34" 12 "2.1"] ["0.07" 5 "1"]]]
    (let [r (alloc q k s)
          total (reduce p/add (pd "0") (:sizes r))]
      (is (empty? (:errors r)))
      (is (= q (ds total)) (str q " " k)))))
