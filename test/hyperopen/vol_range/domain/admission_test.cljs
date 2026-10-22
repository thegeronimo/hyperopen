(ns hyperopen.vol-range.domain.admission-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.precision :as p]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.admission :as admission]
            [hyperopen.vol-range.fixtures :as fx]))

(defn- pd [s] (some-> s p/parse-decimal))
(def policy contracts/product-policy)
(defn- codes [errors] (set (map :code errors)))
(defn- validate [draft] (admission/validate-inputs draft policy))
(defn- paths [errors code] (set (map #(get-in % [:data :path]) (filter #(= code (:code %)) errors))))

(deftest validate-inputs-happy-path-test
  (let [{:keys [errors resolved]} (validate fx/draft-13-2)]
    (is (empty? errors))
    (is (= 4 (get-in resolved [:buy :count])))
    (is (= "0.060000000000" (:manual-daily-fraction resolved)))))

(deftest validate-inputs-ranges-test
  (testing "no side enabled"
    (is (contains? (codes (:errors (validate (-> fx/draft-13-2 (assoc-in [:buy :enabled?] false)
                                                 (assoc-in [:sell :enabled?] false)))))
                   :input/no-side-enabled)))
  (testing "quantity"
    (let [errs (:errors (validate (assoc-in fx/draft-13-2 [:buy :quantity] "")))]
      (is (= #{[:buy :quantity]} (paths errs :input/quantity-required))))
    (is (contains? (codes (:errors (validate (assoc-in fx/draft-13-2 [:buy :quantity] "0"))))
                   :input/quantity-required))
    (let [errs (:errors (validate (assoc-in fx/draft-13-2 [:sell :quantity] "abc")))]
      (is (= #{[:sell :quantity]} (paths errs :input/invalid-number)))))
  (testing "counts"
    (doseq [c [0 13 "13" "0"]]
      (is (= #{[:buy :count]} (paths (:errors (validate (assoc-in fx/draft-13-2 [:buy :count] c)))
                                     :input/count-out-of-range))))
    (is (empty? (:errors (validate (assoc-in fx/draft-13-2 [:buy :count] "12"))))))
  (testing "distances"
    (is (= #{[:buy :start-sigma]}
           (paths (:errors (validate (assoc-in fx/draft-13-2 [:buy :start-sigma] "0.09")))
                  :input/distance-out-of-range)))
    (is (= #{[:buy :end-sigma]}
           (paths (:errors (validate (assoc-in fx/draft-13-2 [:buy :end-sigma] "4.01")))
                  :input/distance-out-of-range)))
    (is (= #{[:buy :start-sigma]}
           (paths (:errors (validate (assoc-in fx/draft-13-2 [:buy :start-sigma] "2.50")))
                  :input/start-above-end))))
  (testing "skew"
    (doseq [s ["0.99" "3.51"]]
      (is (= #{[:sell :skew]} (paths (:errors (validate (assoc-in fx/draft-13-2 [:sell :skew] s)))
                                     :input/skew-out-of-range)))))
  (testing "tilt strength only when tilt on"
    (let [bad (assoc-in fx/draft-13-2 [:inventory :tilt-strength] "1.5")]
      (is (empty? (:errors (validate bad))))
      (is (= #{[:inventory :tilt-strength]}
             (paths (:errors (validate (assoc-in bad [:inventory :tilt-enabled?] true)))
                    :input/tilt-strength-out-of-range)))))
  (testing "manual vol and custom anchor"
    (is (= #{[:volatility :manual-daily-pct]}
           (paths (:errors (validate (assoc-in fx/draft-13-2 [:volatility :manual-daily-pct] "60")))
                  :input/manual-vol-out-of-range)))
    (is (= #{[:anchor :custom-price]}
           (paths (:errors (validate (assoc-in fx/draft-13-2 [:anchor :custom-price] "0")))
                  :input/custom-anchor-invalid))))
  (testing "buys need cap and maximum"
    (let [errs (:errors (validate (-> fx/draft-13-2 (assoc-in [:buy :max-usdc-debit] "")
                                      (assoc-in [:inventory :maximum] ""))))]
      (is (= #{[:buy :max-usdc-debit]} (paths errs :input/max-debit-required)))
      (is (= #{[:inventory :maximum]} (paths errs :input/maximum-required))))
    (is (empty? (:errors (validate (-> fx/draft-13-2 (assoc-in [:buy :enabled?] false)
                                       (assoc-in [:inventory :maximum] "")))))))
  (testing "limits order; blank core and target are fine"
    (is (contains? (codes (:errors (validate (assoc-in fx/draft-13-2 [:inventory :protected-core] "170"))))
                   :input/inventory-limits-order))
    (is (empty? (:errors (validate (-> fx/draft-13-2 (assoc-in [:inventory :protected-core] "")
                                       (assoc-in [:inventory :target] ""))))))))

(deftest disabled-side-ignored-test
  (let [draft (-> fx/draft-13-2 (assoc-in [:sell :enabled?] false) (assoc-in [:sell :quantity] "junk"))]
    (is (empty? (:errors (validate draft))))))

(defn- leg [id side price size]
  {:leg-id id :side side :price price :size size
   :notional (p/decimal->string (p/mul (pd price) (pd size)))})

(defn- legs-check [buys sells bbo]
  (codes (admission/check-legs {:buy-legs buys :sell-legs sells :bbo bbo :policy policy})))

(def bbo {:bid "79.99" :ask "80.01"})

(deftest check-legs-test
  (testing "post-only crossing names the touch"
    (let [errs (admission/check-legs {:buy-legs [(leg "buy-0" :buy "76.4" "7")] :sell-legs []
                                      :bbo {:bid "75.89" :ask "75.91"} :policy policy})
          e (first (filter #(= :plan/post-only-cross (:code %)) errs))]
      (is (= "Buy at 76.4 is above the best ask 75.91" (:message e)))
      (is (= "buy-0" (get-in e [:data :leg-id])))
      (is (= "75.91" (get-in e [:data :touch-price]))))
    (let [errs (admission/check-legs {:buy-legs [] :sell-legs [(leg "sell-0" :sell "79" "10")]
                                      :bbo bbo :policy policy})]
      (is (= "Sell at 79 is below the best bid 79.99" (:message (first errs))))))
  (testing "price equal to the touch crosses"
    (is (contains? (legs-check [(leg "buy-0" :buy "80.01" "10")] [] bbo) :plan/post-only-cross))
    (is (contains? (legs-check [] [(leg "sell-0" :sell "79.99" "10")] bbo) :plan/post-only-cross)))
  (testing "self cross and duplicates"
    (is (contains? (legs-check [(leg "buy-0" :buy "79" "20")] [(leg "sell-0" :sell "79" "20")]
                               {:bid "70" :ask "90"})
                   :plan/self-cross))
    (let [errs (admission/check-legs {:buy-legs [(leg "buy-0" :buy "70" "20") (leg "buy-1" :buy "70" "20")]
                                      :sell-legs [] :bbo bbo :policy policy})
          e (first (filter #(= :plan/duplicate-price (:code %)) errs))]
      (is (= :buy (get-in e [:data :side])))))
  (testing "distance guard is inclusive at 50%"
    (is (not (contains? (legs-check [(leg "buy-0" :buy "40" "10")] [] bbo) :plan/distance-guard)))
    (is (contains? (legs-check [(leg "buy-0" :buy "39.99" "10")] [] bbo) :plan/distance-guard)))
  (testing "min notional on every leg"
    (let [errs (admission/check-legs {:buy-legs [(leg "buy-0" :buy "100" "0.11") (leg "buy-1" :buy "50" "0.11")]
                                      :sell-legs [] :bbo {:bid "99" :ask "101"} :policy {:max-distance-from-mid-fraction "0.9" :min-leg-notional-usdc "10.50"}})
          mn (filter #(= :plan/min-notional (:code %)) errs)]
      (is (= ["buy-1"] (map #(get-in % [:data :leg-id]) mn)))))
  (testing "bbo problems"
    (is (contains? (legs-check [] [] nil) :data/bbo-unavailable))
    (is (contains? (legs-check [] [] {:bid "80" :ask "79"}) :data/bbo-crossed))))

(deftest corrective-mode-test
  (is (= :above-maximum (admission/corrective-mode {:q (pd "210") :core (pd "120") :maximum (pd "200")})))
  (is (= :below-core (admission/corrective-mode {:q (pd "100") :core (pd "120") :maximum (pd "200")})))
  (is (nil? (admission/corrective-mode {:q (pd "150") :core (pd "120") :maximum (pd "200")}))))

(defn- bounds [m]
  (let [dd (fn [k] (some-> (get m k) pd))]
    (codes (admission/check-bounds
            {:q (dd :q) :core (dd :core) :maximum (dd :maximum) :b-existing (dd :b-existing)
             :s-existing (dd :s-existing) :b-new (dd :b-new) :s-new (dd :s-new)
             :base-credit-bound (dd :credit) :base-debit-bound (dd :debit) :usdc-debit-bound (dd :usdc-bound)
             :buy-notional (dd :buy-notional) :cap (dd :cap) :available-usdc (dd :avail-usdc)
             :available-base (dd :avail-base)}))))

(def base-case {:q "150" :core "120" :maximum "200" :b-new "0" :s-new "0" :avail-usdc "3000" :avail-base "150"})

(deftest check-bounds-test
  (testing "inside bounds: asks cannot offset a maximum breach"
    (is (= #{:plan/max-inventory-breach}
           (bounds (assoc base-case :q "190" :b-new "50" :s-new "30" :buy-notional "3500" :avail-usdc "9999" :cap "9999"))))
    (is (= #{:plan/protected-core-breach}
           (bounds (assoc base-case :q "150" :s-new "40")))))
  (testing "above maximum: sell-only allowed, buys not"
    (is (= #{} (bounds (assoc base-case :q "210" :s-new "30" :avail-base "210"))))
    (is (= #{:plan/corrective-direction}
           (bounds (assoc base-case :q "210" :b-new "5" :buy-notional "400" :cap "9999"))))
    (is (= #{:plan/protected-core-breach}
           (bounds (assoc base-case :q "210" :s-new "100" :avail-base "210")))))
  (testing "below core: buy-only allowed, sells not"
    (is (= #{} (bounds (assoc base-case :q "100" :core "120" :b-new "10" :buy-notional "800" :cap "9999"))))
    (is (= #{:plan/corrective-direction}
           (bounds (assoc base-case :q "100" :s-new "10" :avail-base "100"))))
    (is (= #{:plan/max-inventory-breach}
           (bounds (assoc base-case :q "100" :b-new "150" :buy-notional "100" :cap "9999")))))
  (testing "cash and base checks apply to corrective plans too"
    (is (= #{:plan/insufficient-usdc}
           (bounds (assoc base-case :q "100" :b-new "10" :buy-notional "2500" :usdc-bound "1" :avail-usdc "2000" :cap "9999"))))
    (is (= #{:plan/usdc-cap-exceeded}
           (bounds (assoc base-case :b-new "10" :buy-notional "2889" :usdc-bound "1.1552" :cap "2800"))))
    (is (= #{:plan/insufficient-base}
           (bounds (assoc base-case :q "210" :s-new "160" :avail-base "150" :core "10"))))
    (is (= #{:plan/insufficient-base}
           (bounds (assoc base-case :core "100" :s-new "30" :debit "0.5" :avail-base "30"))))))

(deftest existing-orders-count-once-test
  (is (= #{:plan/max-inventory-breach}
         (bounds (assoc base-case :q "190" :b-existing "20" :b-new "0"))))
  (let [out (admission/inventory-outcomes {:q (pd "150") :b-existing (pd "5") :s-existing (pd "7")
                                           :b-new (pd "40") :s-new (pd "30") :base-credit-bound (pd "0.5")
                                           :base-debit-bound (pd "0.25") :core (pd "120") :target (pd "160")
                                           :maximum (pd "190")})]
    (is (= "195.5" (:all-buys-first out)))
    (is (= "112.75" (:all-sells-first out)))
    (is (= "160" (:all-fill out)))
    (is (= "5" (:existing-buy-exposure out)))
    (is (nil? (:starting-breach out)))))
