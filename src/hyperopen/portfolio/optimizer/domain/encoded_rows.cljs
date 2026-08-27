(ns hyperopen.portfolio.optimizer.domain.encoded-rows
  "The translation from `encode-constraints`' output to the linear rows a solver
  problem carries: the net equality or the net inequalities, the coupled
  percentage-band rows, the signed-linear gross floor, the target-return floor,
  and the L1 (split-variable) gross/turnover caps.

  Split out of `domain.objectives` (whose size exception names this move) so the
  plan builders and the cash-collapse gate read the SAME answer to \"what is the
  solver actually told?\". They disagreed once - the gate tested the stored net
  bounds while the plan encoded the coupled band rows - and a request whose only
  net row was a band then read as forbidding cash when it did not."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def ^:private finite-number? coercion/finite-number?)

(defn- ones
  [n]
  (vec (repeat n 1)))

(defn net-equality-target
  "The single net-exposure equality target the plan encodes, or nil when it
  encodes none. A finite :net-target wins; an active percentage net band
  replaces the exact-net equality with the coupled band inequalities below
  (q = 0 keeps the equality unchanged); otherwise an equal finite net
  min/max pins net. Shared with the cash-collapse gate so the two can never
  disagree about what the solver is actually told."
  [encoded-constraints]
  (let [net-exposure (:net-exposure encoded-constraints)
        net-target (:net-target encoded-constraints)]
    (cond
      (finite-number? net-target) net-target
      (some? (:net-band-spec encoded-constraints)) nil

      (and (map? net-exposure)
           (= (:min net-exposure) (:max net-exposure))
           (finite-number? (:min net-exposure)))
      (:min net-exposure))))

(defn equality-constraints
  [encoded-constraints n]
  (if-let [target (net-equality-target encoded-constraints)]
    [{:code :net-exposure
      :coefficients (ones n)
      :target target}]
    []))

(defn net-inequalities
  [encoded-constraints n]
  (let [net-exposure (:net-exposure encoded-constraints)]
    (if (and (map? net-exposure)
             (nil? (:net-band-spec encoded-constraints))
             (not= (:min net-exposure) (:max net-exposure)))
      (vec (concat
            (when (finite-number? (:min net-exposure))
              [{:code :net-exposure
                :coefficients (ones n)
                :lower (:min net-exposure)}])
            (when (finite-number? (:max net-exposure))
              [{:code :net-exposure
                :coefficients (ones n)
                :upper (:max net-exposure)}])))
      [])))

(defn net-band-inequalities
  "The percentage-of-gross net band as signed-linear rows in weight space:
     net(w) − q·gross(w) ≤ net-max   →  sum((1 − q·sign_i)·w_i) ≤ net-max
     net(w) + q·gross(w) ≥ net-min   →  sum((1 + q·sign_i)·w_i) ≥ net-min
   Realized gross rides the same single-signed representation as the gross
   floor (sum(sign_i·w_i) = sum|w_i| on the fixed-sign box), so the tolerance
   scales with the portfolio the solver actually picks — never the gross
   target. Empty when no band spec is encoded."
  [encoded-constraints]
  (let [{:keys [pct signs] :as spec} (:net-band-spec encoded-constraints)]
    (if-not spec
      []
      (vec (concat
            (when (finite-number? (:max spec))
              [{:code :net-band
                :coefficients (mapv #(- 1 (* pct %)) signs)
                :upper (:max spec)}])
            (when (finite-number? (:min spec))
              [{:code :net-band
                :coefficients (mapv #(+ 1 (* pct %)) signs)
                :lower (:min spec)}]))))))

(defn target-return-inequality
  [expected-returns objective]
  (when (and (= :target-return (:kind objective))
             (finite-number? (:target-return objective)))
    {:code :target-return
     :coefficients expected-returns
     :lower (:target-return objective)}))

(defn gross-floor-inequality
  "A gross-leverage FLOOR as a signed-linear inequality in weight space:
   sum(sign_i * w_i) >= G. Because each asset is single-signed (its bounds keep
   w_i on one side of zero), sum(sign_i * w_i) equals true gross sum|w_i|, so this
   is a convex linear lower bound. It rides the generic :inequalities channel
   (NOT the L1 split-variable :gross-exposure channel, where a >= bound is
   unenforceable because both split legs could inflate). nil when no floor is set
   or the universe is not single-signed (see constraints/gross-floor-spec)."
  [encoded-constraints]
  (let [floor (:gross-floor encoded-constraints)]
    (when (and (map? floor)
               (finite-number? (:min floor))
               (sequential? (:signs floor)))
      {:code :gross-floor
       :coefficients (:signs floor)
       :lower (:min floor)})))

(defn l1-constraints
  [encoded-constraints]
  (let [max-gross (get-in encoded-constraints [:gross-exposure :max])
        max-turnover (:max-turnover encoded-constraints)]
    (cond-> []
      (finite-number? max-gross)
      (conj {:code :gross-exposure
             :max max-gross
             :requires-split-variables? true})

      (finite-number? max-turnover)
      (conj {:code :turnover
             :max (* 2 max-turnover)
             :current-weights (:current-weights encoded-constraints)
             :requires-split-variables? true}))))

(defn net-row-bounds
  "The net floor/ceiling the plan encodes as INEQUALITIES: the coupled
  percentage-band rows when a band is active, the plain net rows otherwise
  (mirrors net-band-inequalities / net-inequalities). nil when neither family
  is emitted."
  [encoded-constraints]
  (or (:net-band-spec encoded-constraints)
      (let [net-exposure (:net-exposure encoded-constraints)]
        (when (and (map? net-exposure)
                   (not= (:min net-exposure) (:max net-exposure)))
          net-exposure))))
