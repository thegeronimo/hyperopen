(ns hyperopen.portfolio.optimizer.application.view-model.equal-risk-results-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-results
             :as equal-risk-results]))

(defn- result-with-shares
  "Solved-result skeleton for n assets with the given signed shares."
  [shares & {:keys [current-shares solver diagnostics quality]
             :or {quality :approximate}}]
  (let [n (count shares)
        ids (mapv #(str "perp:A" %) (range n))
        targets (vec (repeat n (/ 1 n)))]
    (cond-> {:risk-contributions
             {:instrument-ids ids
              :relative-contributions (vec shares)
              :target-relative-contributions targets
              :rms-error 0.01
              :max-absolute-error 0.02
              :negative-contribution-count (count (filter neg? shares))
              :quality quality}
             :target-weights-by-instrument (zipmap ids (repeat 0.1))
             :labels-by-instrument (zipmap ids ids)}
      current-shares
      (assoc :current-risk-contributions
             {:relative-contributions-by-instrument (zipmap ids current-shares)
              :rms-error 0.2
              :max-absolute-error 0.3})

      solver (assoc :equal-risk-solver solver)
      diagnostics (assoc :diagnostics diagnostics))))

(deftest balance-model-caps-by-deviation-and-displays-by-share-test
  (testing "display order is signed share descending with current shares attached"
    (let [model (equal-risk-results/balance-model
                 (result-with-shares [0.5 0.1 0.4]
                                     :current-shares [0.9 0.05 0.05]))]
      (is (= ["perp:A0" "perp:A2" "perp:A1"]
             (mapv :instrument-id (:rows model)))
          "longs high-to-low: 0.5 > 0.4 > 0.1")
      (is (= 0.9 (:current-share (first (filter #(= "perp:A0" (:instrument-id %))
                                                (:rows model))))))
      (is (zero? (:hidden-count model)))))
  (testing "hedges display at the bottom regardless of deviation size"
    (let [model (equal-risk-results/balance-model
                 (result-with-shares [0.4 -0.1 0.7]))]
      (is (= ["perp:A2" "perp:A0" "perp:A1"]
             (mapv :instrument-id (:rows model))))))
  (testing "the cap still keeps the WORST deviations, then re-orders by share"
    (let [shares (into [0.9] (repeat 24 (/ 0.1 24)))
          model (equal-risk-results/balance-model (result-with-shares shares))]
      (is (= equal-risk-results/display-row-cap (count (:rows model))))
      (is (= "perp:A0" (:instrument-id (first (:rows model)))))
      (is (= (- 25 equal-risk-results/display-row-cap) (:hidden-count model)))
      (is (number? (:hidden-max-pts model)))))
  (testing "negative shares flag as hedges"
    (let [model (equal-risk-results/balance-model
                 (result-with-shares [1.1 -0.1]))]
      (is (true? (:negative? (first (filter #(= "perp:A1" (:instrument-id %))
                                            (:rows model)))))))))

(deftest balance-model-carries-per-row-targets-and-largest-test
  (testing "each row carries its own target (uniform fallback)"
    (let [model (equal-risk-results/balance-model (result-with-shares [0.6 0.4]))]
      (is (= [0.5 0.5] (mapv :target-share (:rows model))))))
  (testing "per-instrument targets win over the uniform target"
    (let [result (-> (result-with-shares [0.625 -0.375])
                     (assoc-in [:risk-contributions
                                :target-relative-contributions-by-instrument]
                               {"perp:A0" 0.5 "perp:A1" -0.5}))
          model (equal-risk-results/balance-model result)
          by-id (into {} (map (juxt :instrument-id identity)) (:rows model))]
      (is (= -0.5 (:target-share (get by-id "perp:A1"))))
      (is (= 12.5 (:deviation-pts (get by-id "perp:A1")))
          "-0.375 against a -0.5 target is +12.5 pts")))
  (testing "largest contributor is picked over ALL rows, not just the visible cap"
    (let [shares (into [0.05] (repeat 24 0.02))
          model (equal-risk-results/balance-model (result-with-shares shares))]
      (is (not-any? #(= "perp:A0" (:instrument-id %)) (:rows model))
          "A0's small deviation loses the cap to the 2-pt rows")
      (is (= 0.05 (:share (:largest model)))))))

(deftest balance-model-shift-mode-flips-on-exact-with-current-test
  (testing "an exact fit with current shares flips to :shift display mode,
            ordered by current share descending with shift-pts on every row"
    (let [model (equal-risk-results/balance-model
                 (result-with-shares [0.25 0.25 0.25 0.25]
                                     :quality :exact
                                     :current-shares [0.1 0.5 0.3 0.1]))]
      (is (= :shift (:display-mode model)))
      (is (= ["perp:A1" "perp:A2" "perp:A0" "perp:A3"]
             (mapv :instrument-id (:rows model)))
          "current shares high-to-low: 0.5 > 0.3 > 0.1 = 0.1")
      (is (= -25.0 (:shift-pts (first (:rows model))))
          "the biggest donor sheds 25 pts (25% target − 50% current)")
      (is (= 4 (:asset-count model)))))
  (testing "an approximate fit keeps :deviation mode and the share-desc order"
    (let [model (equal-risk-results/balance-model
                 (result-with-shares [0.5 0.1 0.4]
                                     :current-shares [0.9 0.05 0.05]))]
      (is (= :deviation (:display-mode model)))
      (is (= ["perp:A0" "perp:A2" "perp:A1"]
             (mapv :instrument-id (:rows model))))))
  (testing "an exact fit WITHOUT current shares stays in :deviation mode"
    (let [model (equal-risk-results/balance-model
                 (result-with-shares [0.5 0.5] :quality :exact))]
      (is (= :deviation (:display-mode model)))
      (is (nil? (:current model))))))

(deftest balance-model-shift-mode-caps-by-shift-and-reports-movers-test
  (testing "the cap keeps the largest |shift| rows and the remainder reports
            the largest hidden |shift|"
    (let [n 32
          shares (vec (repeat n 0.03125))
          current (assoc (vec (repeat n 0.046875)) 31 0.53125)
          model (equal-risk-results/balance-model
                 (result-with-shares shares
                                     :quality :exact
                                     :current-shares current))]
      (is (= :shift (:display-mode model)))
      (is (= equal-risk-results/display-row-cap (count (:rows model))))
      (is (= "perp:A31" (:instrument-id (first (:rows model))))
          "biggest current share displays first")
      (is (= (- n equal-risk-results/display-row-cap) (:hidden-count model)))
      (is (= 1.5625 (:hidden-max-pts model))
          "hidden rows all shifted 1.5625 pts (4.6875% -> 3.125%)")
      (is (= {:instrument-id "perp:A31"
              :label "perp:A31"
              :shift-pts -50.0}
             (get-in model [:current :biggest-shift]))))))

(deftest format-signed-pts-never-renders-signed-zero-test
  (is (= "0.0 pts" (equal-risk-results/format-signed-pts -0.04)))
  (is (= "0.0 pts" (equal-risk-results/format-signed-pts 0.04)))
  (is (= "0.0 pts" (equal-risk-results/format-signed-pts 0)))
  (is (= "-0.6 pts" (equal-risk-results/format-signed-pts -0.6)))
  (is (= "+1.2 pts" (equal-risk-results/format-signed-pts 1.23)))
  (is (= "—" (equal-risk-results/format-signed-pts nil))))

(deftest deviation-tone-grades-against-the-target-test
  (is (= :good (equal-risk-results/deviation-tone 1.8 20.0)))
  (is (= :good (equal-risk-results/deviation-tone -4.0 20.0)))
  (is (= :caution (equal-risk-results/deviation-tone 6.0 20.0)))
  (is (= :bad (equal-risk-results/deviation-tone 15.0 20.0)))
  (is (nil? (equal-risk-results/deviation-tone nil 20.0)))
  (is (nil? (equal-risk-results/deviation-tone 1.0 nil)))
  (is (nil? (equal-risk-results/deviation-tone 1.0 0))))

(deftest freedom-card-view-copy-test
  (is (= "Limited · 2 binding caps"
         (:value (equal-risk-results/freedom-card-view
                  {:status :limited :binding-count 2}))))
  (is (= "Limited · 1 binding cap"
         (:value (equal-risk-results/freedom-card-view
                  {:status :limited :binding-count 1}))))
  (is (false? (:locked? (equal-risk-results/freedom-card-view {:status :open}))))
  (is (= "Fully determined"
         (:value (equal-risk-results/freedom-card-view
                  {:status :fully-determined}))))
  (testing "persisted pre-redesign results degrade honestly"
    (is (str/includes? (:sub (equal-risk-results/freedom-card-view nil))
                       "Not recorded"))))

(deftest kpi-risk-balance-reads-current-and-target-deviations-test
  (let [with-current (equal-risk-results/kpi-risk-balance
                      (result-with-shares [0.5 0.5] :current-shares [0.9 0.1]))
        without-current (equal-risk-results/kpi-risk-balance
                         (result-with-shares [0.5 0.5]))]
    (is (= 2.0 (:target-max-pts with-current)))
    (is (= 30.0 (:current-max-pts with-current)))
    (is (= -28.0 (:delta-pts with-current)))
    (is (nil? (:current-max-pts without-current)))
    (is (nil? (:delta-pts without-current)))))

(deftest freedom-view-labels-every-status-test
  (is (= "Fully determined"
         (:label (equal-risk-results/freedom-view {:status :fully-determined}))))
  (is (str/includes? (:detail (equal-risk-results/freedom-view
                               {:status :limited :binding-count 2}))
                     "2 binding caps"))
  (is (= :ok (:status (equal-risk-results/freedom-view {:status :open}))))
  (testing "persisted pre-redesign results degrade honestly"
    (is (str/includes? (:detail (equal-risk-results/freedom-view nil))
                       "Not recorded"))))

(deftest solution-stability-classifies-initialization-agreement-test
  (let [stability #(equal-risk-results/solution-stability
                    {:equal-risk-solver {:initializations %}})]
    (is (= :unknown (:status (stability nil))))
    (is (= "Single start"
           (:label (stability [{:status :completed :objective 1e-4}]))))
    (is (= "High"
           (:label (stability [{:status :completed :objective 1.00e-4}
                               {:status :completed :objective 1.02e-4}]))))
    (is (= :caution
           (:status (stability [{:status :completed :objective 1e-4}
                                {:status :completed :objective 9e-3}]))))))

(deftest stop-reason-maps-solver-terminations-test
  (is (= "Projected step tolerance reached"
         (:label (equal-risk-results/stop-reason
                  {:equal-risk-solver {:termination-reason :step-tolerance}}))))
  (is (= "Iteration limit reached"
         (:label (equal-risk-results/stop-reason
                  {:equal-risk-solver {:termination-reason :max-iterations}}))))
  (testing "fully-determined overrides with the constraint explanation"
    (is (= "Allocation fully determined by constraints"
           (:label (equal-risk-results/stop-reason
                    {:equal-risk-solver
                     {:termination-reason :step-tolerance
                      :allocation-freedom {:status :fully-determined}}}))))))

(deftest verdict-body-branches-on-allocation-freedom-test
  (let [base (result-with-shares [0.5 0.5]
                                 :diagnostics {:gross-exposure 2.0
                                               :net-exposure -0.5})]
    (testing "open books get the balance sentence"
      (let [body (equal-risk-results/verdict-body base)]
        (is (str/includes? body "Balances 2 selected positions toward 50.0%"))
        (is (str/includes? body "2.00x gross"))
        (is (str/includes? body "-0.50x net"))
        (is (str/includes? body "Max contribution deviation 2.0 pts"))))
    (testing "limited books name the binding caps"
      (let [body (equal-risk-results/verdict-body
                  (assoc-in base [:equal-risk-solver :allocation-freedom]
                            {:status :limited :binding-count 2}))]
        (is (str/includes? body "2 binding caps limit exact equality"))))
    (testing "fully-determined books say the optimizer could not choose"
      (let [body (equal-risk-results/verdict-body
                  (assoc-in base [:equal-risk-solver :allocation-freedom]
                            {:status :fully-determined}))]
        (is (str/includes? body "fully determine"))
        (is (str/includes? body "cannot improve it"))))))

(deftest verdict-body-labels-gross-as-selected-and-net-as-resulting-test
  (let [body (equal-risk-results/verdict-body
              (result-with-shares [0.5 0.5]
                                  :diagnostics {:gross-exposure 2.0
                                                :net-exposure -0.35}))]
    (is (str/includes? body "selected 2.00x gross")
        "Equal Risk should frame gross as the selected target the trader controls.")
    (is (str/includes? body "resulting -0.35x net")
        "Equal Risk should frame net as an output determined by risk balance.")
    (is (not (str/includes? (str/lower-case body) "preserving"))
        "Equal Risk result copy must not imply stored net policy was enforced.")))
