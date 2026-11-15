(ns hyperopen.portfolio.optimizer.domain.equal-risk-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.equal-risk :as equal-risk]
            [hyperopen.portfolio.optimizer.domain.equal-risk-plan :as equal-risk-plan]
            [hyperopen.portfolio.optimizer.domain.equal-risk-presolve :as equal-risk-presolve]))

(defn- near?
  ([expected actual] (near? expected actual 1e-9))
  ([expected actual tolerance]
   (and (number? actual)
        (< (js/Math.abs (- expected actual)) tolerance))))

(defn- gross-of
  [weights]
  (reduce + 0 (map js/Math.abs weights)))

(defn- perp
  [id side & {:as extra}]
  (merge {:instrument-id id
          :market-type :perp
          :coin id
          :shortable? true
          :position-side side}
         extra))

(defn- encoded
  ([universe engine-constraints]
   (encoded universe engine-constraints {}))
  ([universe engine-constraints current-weights]
   (constraints/encode-constraints
    {:universe universe
     :current-weights current-weights
     :constraints engine-constraints})))

(def ^:private diag-covariance
  [[0.01 0.0]
   [0.0 0.04]])

(defn- violation-codes
  [presolve-result]
  (set (map :code (:violations presolve-result))))

(deftest exposure-targets-for-equal-risk-derive-gross-only-target-test
  (testing "gross target still follows canonical midpoint policy"
    (let [enc (encoded [(perp "perp:A" :long) (perp "perp:B" :short)]
                       {:gross-leverage 3.0
                        :gross-floor 1.0
                        :net-exposure {:min 2.5 :max 2.5}
                        :max-asset-weight 3.0})
          targets (equal-risk/exposure-targets enc)]
      (is (near? 2.0 (:gross targets)))
      (is (not (contains? targets :net)))
      (is (not (contains? targets :long-gross)))
      (is (not (contains? targets :short-gross))))))

(deftest book-split-follows-encoded-bounds-test
  (let [enc (encoded [(perp "perp:A" :long)
                      (perp "perp:B" :short)
                      (dissoc (perp "perp:C" :long) :position-side)]
                     {:gross-leverage 2.0
                      :net-exposure {:min 0.0 :max 0.0}})
        books (equal-risk/book-split enc)]
    (is (= [0] (:long books)))
    (is (= [1] (:short books)))
    (is (= [2] (:two-sided books)))))

(deftest presolve-accepts-feasible-mixed-books-test
  (let [enc (encoded [(perp "perp:A" :long) (perp "perp:B" :short)]
                     {:gross-leverage 2.0
                      :net-exposure {:min 0.0 :max 0.0}
                      :max-asset-weight 1.5})
        result (equal-risk-presolve/presolve enc diag-covariance)]
    (is (= :ok (:status result)))
    (is (near? 2.0 (get-in result [:targets :gross])))
    (is (not (contains? (:targets result) :long-gross)))
    (is (not (contains? (:targets result) :short-gross)))
    (is (= {:long [0] :short [1]} (:books result)))))

(deftest presolve-ignores-stored-net-target-outside-gross-test
  (let [enc (encoded [(perp "perp:A" :long) (perp "perp:B" :short)]
                     {:gross-leverage 3.0
                      :gross-floor 1.0
                      :net-exposure {:min 2.5 :max 2.5}
                      :max-asset-weight 3.0})
        result (equal-risk-presolve/presolve enc diag-covariance)]
    (is (= :ok (:status result)))
    (is (= {:long [0] :short [1]} (:books result)))
    (is (near? 2.0 (get-in result [:targets :gross])))
    (is (not (contains? (:targets result) :net)))
    (is (not (contains? (:targets result) :long-gross)))
    (is (not (contains? (:targets result) :short-gross)))
    (is (not (contains? (violation-codes result)
                        :equal-risk-net-exceeds-gross)))
    (is (not (contains? (violation-codes result)
                        :equal-risk-long-book-empty)))
    (is (not (contains? (violation-codes result)
                        :equal-risk-short-book-empty)))))

(deftest presolve-rejects-missing-or-nonpositive-gross-target-test
  (let [enc (encoded [(perp "perp:A" :long) (perp "perp:B" :long)]
                     {:net-exposure {:min 0.0 :max 0.0}})
        result (equal-risk-presolve/presolve enc diag-covariance)]
    (is (= :infeasible (:status result)))
    (is (contains? (violation-codes result) :equal-risk-gross-target-not-positive))))

(deftest presolve-accepts-one-sided-books-with-opposite-stored-net-test
  (testing "all-long books derive resulting net from gross and side"
    (let [enc (encoded [(perp "perp:A" :long) (perp "perp:B" :long)]
                       {:gross-leverage 2.0
                        :net-exposure {:min -1.5 :max -1.5}
                        :max-asset-weight 2.0})
          result (equal-risk-presolve/presolve enc diag-covariance)]
      (is (= :ok (:status result)))
      (is (= {:long [0 1] :short []} (:books result)))
      (is (not (contains? (violation-codes result)
                          :equal-risk-short-book-empty)))))
  (testing "all-short books derive resulting net from gross and side"
    (let [enc (encoded [(perp "perp:A" :short) (perp "perp:B" :short)]
                       {:gross-leverage 2.0
                        :net-exposure {:min 1.5 :max 1.5}
                        :max-asset-weight 2.0})
          result (equal-risk-presolve/presolve enc diag-covariance)]
      (is (= :ok (:status result)))
      (is (= {:long [] :short [0 1]} (:books result)))
      (is (not (contains? (violation-codes result)
                          :equal-risk-long-book-empty))))))

(deftest presolve-rejects-aggregate-gross-capacity-and-locked-minimum-test
  (testing "aggregate caps below the gross target still block Equal Risk"
    (let [enc (encoded [(perp "perp:A" :long)
                        (perp "perp:B" :short)]
                       {:gross-leverage 1.0
                        :net-exposure {:min 0.0 :max 0.0}
                        :per-asset-overrides {"perp:A" {:max-weight 0.4}
                                              "perp:B" {:max-weight 0.4}}})
          result (equal-risk-presolve/presolve enc diag-covariance)]
      (is (= :infeasible (:status result)))
      (is (contains? (violation-codes result)
                     :equal-risk-gross-capacity-below-target))
      (is (not (contains? (violation-codes result)
                          :equal-risk-long-book-capacity-below-target)))
      (is (not (contains? (violation-codes result)
                          :equal-risk-short-book-capacity-below-target)))))
  (testing "locked absolute exposure above gross still blocks Equal Risk"
    (let [enc (encoded [(perp "perp:A" :long)
                        (perp "perp:B" :short)]
                       {:gross-leverage 1.0
                        :net-exposure {:min 0.0 :max 0.0}
                        :held-position-locks ["perp:A"]
                        :max-asset-weight 1.5}
                       {"perp:A" 1.25})
          result (equal-risk-presolve/presolve enc diag-covariance)]
      (is (= :infeasible (:status result)))
      (is (contains? (violation-codes result)
                     :equal-risk-gross-minimum-above-target))
      (is (not (contains? (violation-codes result)
                          :equal-risk-long-book-minimum-above-target)))
      (is (not (contains? (violation-codes result)
                          :equal-risk-short-book-minimum-above-target))))))

(deftest presolve-rejects-two-sided-assets-test
  (let [enc (encoded [(perp "perp:A" :long)
                      (dissoc (perp "perp:B" :long) :position-side)]
                     {:gross-leverage 1.0
                      :net-exposure {:min 1.0 :max 1.0}})
        result (equal-risk-presolve/presolve enc diag-covariance)]
    (is (= :infeasible (:status result)))
    (let [violation (first (filter #(= :equal-risk-requires-fixed-sides (:code %))
                                   (:violations result)))]
      (is (some? violation))
      (is (= ["perp:B"] (:instrument-ids violation))))))

(deftest presolve-rejects-non-shortable-short-request-test
  (let [enc (encoded [(perp "perp:A" :long)
                      (perp "perp:B" :short :shortable? false)]
                     {:gross-leverage 1.0
                      :net-exposure {:min 1.0 :max 1.0}})
        result (equal-risk-presolve/presolve enc diag-covariance)]
    (is (= :infeasible (:status result)))
    (let [violation (first (filter #(= :equal-risk-short-not-shortable (:code %))
                                   (:violations result)))]
      (is (some? violation))
      (is (= ["perp:B"] (:instrument-ids violation))))))

(deftest presolve-rejects-invalid-covariance-test
  (let [enc (encoded [(perp "perp:A" :long) (perp "perp:B" :long)]
                     {:gross-leverage 1.0
                      :net-exposure {:min 1.0 :max 1.0}})]
    (testing "misaligned covariance"
      (let [result (equal-risk-presolve/presolve enc [[0.01]])]
        (is (= :infeasible (:status result)))
        (is (contains? (violation-codes result) :equal-risk-covariance-shape))))
    (testing "materially asymmetric covariance"
      (let [result (equal-risk-presolve/presolve enc [[0.01 0.005] [0.001 0.04]])]
        (is (= :infeasible (:status result)))
        (is (contains? (violation-codes result) :equal-risk-covariance-asymmetric))))))

(deftest project-book-magnitudes-hits-target-within-bounds-test
  (let [projected (equal-risk/project-book-magnitudes
                   [0.5 0.5 0.5]
                   [0.0 0.0 0.0]
                   [0.4 1.0 1.0]
                   1.2)]
    (is (near? 1.2 (reduce + 0 projected) 1e-9))
    (is (<= (nth projected 0) (+ 0.4 1e-12)))
    (doseq [value projected]
      (is (>= value 0)))))

(deftest seed-weights-hit-gross-target-and-respect-fixed-sides-locks-test
  (let [enc (encoded [(perp "perp:A" :long)
                      (perp "perp:B" :long)
                      (perp "perp:C" :short)]
                     {:gross-leverage 2.0
                      :net-exposure {:min 1.5 :max 1.5}
                      :held-position-locks ["perp:A"]
                      :max-asset-weight 1.5}
                     {"perp:A" 0.25})
        presolve-result (equal-risk-presolve/presolve enc [[0.01 0.0 0.0]
                                                  [0.0 0.04 0.0]
                                                  [0.0 0.0 0.02]])
        {:keys [books targets]} presolve-result]
    (is (= :ok (:status presolve-result)))
    (doseq [kind equal-risk/seed-kinds]
      (let [seed (equal-risk/seed-weights kind enc books targets
                                          (:covariance presolve-result))]
        (is (some? seed) (str kind " seed should exist"))
        (is (near? 2.0 (gross-of seed) 1e-6)
            (str kind " gross target"))
        (is (>= (nth seed 0) -1e-9) (str kind " long A side"))
        (is (>= (nth seed 1) -1e-9) (str kind " long B side"))
        (is (<= (nth seed 2) 1e-9) (str kind " short C side"))
        (is (near? 0.25 (nth seed 0) 1e-9) (str kind " lock preserved"))))))

(deftest build-plan-produces-sequential-strategy-with-one-gross-row-test
  (let [enc (encoded [(perp "perp:A" :long) (perp "perp:B" :short)]
                     {:gross-leverage 2.0
                      :net-exposure {:min -1.0 :max -1.0}
                      :max-asset-weight 2.0
                      :max-turnover 0.5})
        plan (equal-risk-plan/build-plan {:instrument-ids ["perp:A" "perp:B"]
                                     :covariance [[0.04 0.02] [0.02 0.04]]
                                     :encoded-constraints enc})
        problem (first (:problems plan))]
    (is (= :ok (:status plan)))
    (is (= :sequential-equal-risk (:strategy plan)))
    (is (= 1 (count (:problems plan))))
    (is (= :sequential-equal-risk (:kind problem)))
    (is (= :equal-risk (:objective-kind problem)))
    (is (= #{:gross-target}
           (set (map :code (:equalities problem)))))
    (is (not-any? #{:net-exposure :equal-risk-long-book :equal-risk-short-book}
                  (map :code (:equalities problem))))
    (is (not-any? #{:net-exposure :equal-risk-long-book :equal-risk-short-book}
                  (map :code (:l1-constraints problem))))
    (is (some #(= :turnover (:code %)) (:l1-constraints problem)))
    (is (near? 1.0 (:max (first (filter #(= :turnover (:code %))
                                        (:l1-constraints problem))))))
    (is (= [(/ 1 2) (/ 1 2)]
           (get-in problem [:targets :relative-contributions])))))

(deftest build-plan-surfaces-presolve-violations-test
  (let [enc (encoded [(perp "perp:A" :long)
                      (dissoc (perp "perp:B" :long) :position-side)]
                     {:gross-leverage 1.0
                      :net-exposure {:min 1.0 :max 1.0}})
        plan (equal-risk-plan/build-plan {:instrument-ids ["perp:A" "perp:B"]
                                     :covariance diag-covariance
                                     :encoded-constraints enc})]
    (is (= :infeasible (:status plan)))
    (is (= :equal-risk-presolve (:reason plan)))
    (is (some #(= :equal-risk-requires-fixed-sides (:code %))
              (get-in plan [:details :violations])))))

(deftest classify-quality-is-truthful-test
  (let [n 4
        tolerance (equal-risk/exactness-tolerance n)]
    (is (= :exact (equal-risk/classify-quality
                   {:max-absolute-error (* 0.5 tolerance)
                    :rms-error (* 0.5 tolerance)}
                   n true)))
    (is (= :approximate (equal-risk/classify-quality
                         {:max-absolute-error (* 10 tolerance)
                          :rms-error (* 5 tolerance)}
                         n true)))
    (is (= :not-converged (equal-risk/classify-quality
                           {:max-absolute-error 0.0 :rms-error 0.0}
                           n false)))))

(deftest bfgs-update-keeps-positive-definiteness-signals-test
  (let [b (equal-risk/identity-hessian 2)
        s [0.1 -0.05]
        y [0.2 -0.02]
        updated (equal-risk/bfgs-update b s y)]
    (doseq [probe [[1 0] [0 1] [1 1] [1 -1]]]
      (let [bp (mapv (fn [row] (reduce + 0 (map * row probe))) updated)]
        (is (pos? (reduce + 0 (map * probe bp))))))
    (is (= b (equal-risk/bfgs-update b [0 0] [0 0])))))

(deftest presolve-does-not-warn-on-stored-net-lopsidedness-test
  (let [covariance [[0.01 0.0 0.0]
                    [0.0 0.04 0.0]
                    [0.0 0.0 0.02]]]
    (testing "stored net near gross does not create a starved-book warning"
      (let [enc (encoded [(perp "perp:A" :long)
                          (perp "perp:B" :short)
                          (perp "perp:C" :short)]
                         {:gross-leverage 2.0
                          :net-exposure {:min 1.9 :max 1.9}
                          :max-asset-weight 2.0})
            result (equal-risk-presolve/presolve enc covariance)
            plan (equal-risk-plan/build-plan
                  {:instrument-ids ["perp:A" "perp:B" "perp:C"]
                   :covariance covariance
                   :encoded-constraints enc})]
        (is (= :ok (:status result)))
        (is (= [] (:warnings result)))
        (is (= :ok (:status plan)))
        (is (= [] (:warnings plan)))))))
