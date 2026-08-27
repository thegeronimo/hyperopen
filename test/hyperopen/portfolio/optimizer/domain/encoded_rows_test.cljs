(ns hyperopen.portfolio.optimizer.domain.encoded-rows-test
  "The coupled percentage net-band rows, as `domain.encoded-rows` builds them for
  every plan. Split out of objectives-test with the builders themselves: these
  assert the ROW ALGEBRA (the band scales with realized gross, a zero band is the
  exact equality, a full band restricts nothing), not the plan selection around
  it."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]))

(defn- net-band-encoded
  ;; The gross floor is load-bearing for the net-0.0 fixtures, not incidental: a
  ;; band centred on zero net is SATISFIED by cash, so without a floor these
  ;; Minimum risk requests collapse to an all-cash optimum and build-solver-plan
  ;; now rejects them before any problem exists. It never touches the :net-band
  ;; rows these tests inspect.
  [{:keys [pct net-min net-max]}]
  (constraints/encode-constraints
   {:universe [{:instrument-id "A" :market-type :perp :position-side :long}
               {:instrument-id "B" :market-type :perp :position-side :short}]
    :constraints {:long-only? false
                  :gross-floor 1.0
                  :gross-leverage 20.0
                  :net-band-pct pct
                  :net-exposure {:min net-min :max net-max}
                  :max-asset-weight 20.0}}))

(defn- net-band-problem
  [encoded]
  (get-in (objectives/build-solver-plan
           {:objective {:kind :minimum-variance}
            :instrument-ids ["A" "B"]
            :expected-returns [0.1 0.1]
            :covariance [[1 0] [0 1]]
            :encoded-constraints encoded})
          [:problems 0]))

(defn- row-satisfied?
  [{:keys [coefficients lower upper]} weights]
  (let [v (reduce + 0 (map * coefficients weights))]
    (and (or (nil? lower) (>= v (- lower 1e-9)))
         (or (nil? upper) (<= v (+ upper 1e-9))))))

(defn- net-band-rows-allow?
  [problem weights]
  (every? #(row-satisfied? % weights)
          (filter #(= :net-band (:code %)) (:inequalities problem))))

(deftest net-band-pct-replaces-net-equality-with-coupled-rows-test
  (let [problem (net-band-problem (net-band-encoded {:pct 0.05 :net-min 0.0 :net-max 0.0}))
        band-rows (filterv #(= :net-band (:code %)) (:inequalities problem))]
    (is (empty? (:equalities problem))
        "an active percentage band must not pin net to the exact target")
    (is (= 2 (count band-rows)) "one upper and one lower coupled row")
    ;; signs [1 -1], q = 0.05: upper row (1−q·s) = [0.95 1.05] ≤ 0;
    ;; lower row (1+q·s) = [1.05 0.95] ≥ 0.
    (is (= [[0.95 1.05] [1.05 0.95]] (mapv :coefficients band-rows)))))

(deftest net-band-pct-scales-with-realized-gross-not-the-target-test
  ;; Zero net target, 5% band. The SAME rows must allow ±0.75x net at 15x
  ;; realized gross but only ±0.10x at 2x realized gross.
  (let [problem (net-band-problem (net-band-encoded {:pct 0.05 :net-min 0.0 :net-max 0.0}))]
    (is (net-band-rows-allow? problem [7.875 -7.125])
        "gross 15, net +0.75 = 5% of realized gross: allowed")
    (is (not (net-band-rows-allow? problem [7.95 -7.05]))
        "gross 15, net +0.90 > 5% of realized gross: rejected")
    (is (net-band-rows-allow? problem [1.05 -0.95])
        "gross 2, net +0.10 = 5% of realized gross: allowed")
    (is (not (net-band-rows-allow? problem [1.125 -0.875]))
        "gross 2, net +0.25 would need a fixed 0.75x band — the tolerance must
         scale DOWN with the smaller realized gross")
    (is (net-band-rows-allow? problem [7.875 -7.125]))
    (is (not (net-band-rows-allow? problem [1.375 -0.625]))
        "two portfolios in one solve each get their own permitted deviation")))

(deftest net-band-pct-zero-keeps-the-exact-net-equality-test
  (let [problem (net-band-problem (net-band-encoded {:pct 0.0 :net-min 1.0 :net-max 1.0}))]
    (is (= [{:code :net-exposure :coefficients [1 1] :target 1.0}]
           (:equalities problem))
        "0% reproduces the previous absolute 0.00x band (net equality)")
    (is (not-any? #(= :net-band (:code %)) (:inequalities problem)))))

(deftest net-band-pct-nonzero-target-shifts-the-band-test
  ;; Net target +1.00x, 10% band, realized gross 10 ⇒ permitted net 0.00–2.00x.
  (let [problem (net-band-problem (net-band-encoded {:pct 0.1 :net-min 1.0 :net-max 1.0}))]
    (is (net-band-rows-allow? problem [6.0 -4.0]) "gross 10, net +2.0: upper edge")
    (is (net-band-rows-allow? problem [5.0 -5.0]) "gross 10, net 0.0: lower edge")
    (is (not (net-band-rows-allow? problem [6.1 -3.9])) "net +2.2 is out")
    (is (not (net-band-rows-allow? problem [4.9 -5.1])) "net −0.2 is out")))

(deftest net-band-pct-full-band-adds-no-restriction-test
  ;; q = 100% with a zero target: |net| ≤ gross is structurally true for any
  ;; single-signed portfolio, so every such portfolio satisfies the rows.
  (let [problem (net-band-problem (net-band-encoded {:pct 1.0 :net-min 0.0 :net-max 0.0}))]
    (is (net-band-rows-allow? problem [10.0 0.0]) "fully long (net/gross +100%)")
    (is (net-band-rows-allow? problem [0.0 -10.0]) "fully short (net/gross −100%)")
    (is (net-band-rows-allow? problem [3.0 -7.0]))))

(deftest net-band-pct-disqualifies-closed-form-test
  (let [encoded (net-band-encoded {:pct 0.05 :net-min 1.0 :net-max 1.0})
        plan (objectives/build-solver-plan
              {:objective {:kind :minimum-variance}
               :instrument-ids ["A" "B"]
               :expected-returns [0.1 0.1]
               :covariance [[1 0] [0 1]]
               :encoded-constraints encoded})]
    (is (= :single-qp (:strategy plan))
        "the coupled band rows cannot ride the closed-form equality core")))
