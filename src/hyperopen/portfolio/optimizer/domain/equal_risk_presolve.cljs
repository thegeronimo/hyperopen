(ns hyperopen.portfolio.optimizer.domain.equal-risk-presolve
  "Explicit feasibility screening for the Equal Risk (:equal-risk) objective,
  run BEFORE the nonlinear solver: gross-target sanity, fixed sides (no
  two-sided bounds, no silently long-flipped shorts), aggregate gross
  minimum/capacity, and covariance shape/symmetry. Stored net constraints are
  ignored by Equal Risk. Every violation carries a specific :equal-risk-* code
  plus a user-facing :message rendered by the infeasible banner. Book/target
  primitives and tolerances live in domain.equal-risk."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.equal-risk :as equal-risk]
            [hyperopen.portfolio.optimizer.domain.risk-contributions :as risk-contributions]))

(def ^:private finite-number? coercion/finite-number?)

(defn- fmt
  [value]
  (if (finite-number? value)
    (let [rounded (/ (js/Math.round (* 100 value)) 100)]
      (str rounded "x"))
    "?"))

(defn- aggregate-gross-capacity
  [books lower-bounds upper-bounds]
  (reduce (fn [acc [book indexes]]
            (reduce (fn [acc* idx]
                      (let [[magnitude-lower magnitude-upper]
                            (equal-risk/magnitude-bounds book
                                                         (nth lower-bounds idx)
                                                         (nth upper-bounds idx))]
                        (-> acc*
                            (update :minimum + magnitude-lower)
                            (update :capacity + magnitude-upper))))
                    acc
                    indexes))
          {:minimum 0 :capacity 0}
          (select-keys books [:long :short])))

(defn- side-violations
  [{:keys [instrument-ids side-metadata]} books objective-label]
  (let [two-sided (seq (:two-sided books))
        flipped-shorts (->> side-metadata
                            (keep-indexed (fn [idx {:keys [requested-side shortable?]}]
                                            (when (and (= :short requested-side)
                                                       (not shortable?))
                                              (nth instrument-ids idx))))
                            seq)]
    (cond-> []
      two-sided
      (conj {:code :equal-risk-requires-fixed-sides
             :instrument-ids (mapv #(nth instrument-ids %) (:two-sided books))
             :message (str objective-label " needs every asset fixed to one side, but "
                           (count (:two-sided books))
                           (if (= 1 (count (:two-sided books)))
                             " asset can"
                             " assets can")
                           " still take either side. Set each asset Long or Short.")})

      flipped-shorts
      (conj {:code :equal-risk-short-not-shortable
             :instrument-ids (vec flipped-shorts)
             :message (str (count flipped-shorts)
                           (if (= 1 (count flipped-shorts))
                             " asset is set Short but cannot legally be shorted."
                             " assets are set Short but cannot legally be shorted."))}))))

(defn- aggregate-gross-violations
  [books lower-bounds upper-bounds gross tolerance]
  (let [{:keys [minimum capacity]} (aggregate-gross-capacity books
                                                             lower-bounds
                                                             upper-bounds)]
    (cond-> []
      (> minimum (+ gross tolerance))
      (conj {:code :equal-risk-gross-minimum-above-target
             :minimum minimum
             :target gross
             :message (str "Locked or minimum positions total " (fmt minimum)
                           ", above the selected " (fmt gross)
                           " gross target.")})

      (< capacity (- gross tolerance))
      (conj {:code :equal-risk-gross-capacity-below-target
             :capacity capacity
             :target gross
             :message (str "Per-asset caps allow at most " (fmt capacity)
                           " gross exposure, below the selected " (fmt gross)
                           " gross target. Raise caps or lower gross.")}))))

(defn- exposure-violations
  [encoded-constraints gross tolerance objective-label]
  (let [gross-max (get-in encoded-constraints [:gross-exposure :max])
        gross-floor (get-in encoded-constraints [:gross-floor :min])]
    (cond-> []
      (not (finite-number? gross))
      (conj {:code :equal-risk-invalid-exposure-targets :gross gross
             :message (str objective-label " needs a finite gross exposure target.")})

      (and (finite-number? gross) (<= gross tolerance))
      (conj {:code :equal-risk-gross-target-not-positive :gross gross
             :message (str objective-label " needs a positive gross leverage target.")})

      (and (finite-number? gross) (finite-number? gross-max)
           (> gross (+ gross-max tolerance)))
      (conj {:code :equal-risk-gross-target-above-max :gross gross :gross-max gross-max
             :message (str "The gross target " (fmt gross)
                           " sits above the gross ceiling " (fmt gross-max) ".")})

      (and (finite-number? gross) (finite-number? gross-floor)
           (< gross (- gross-floor tolerance)))
      (conj {:code :equal-risk-gross-target-below-floor :gross gross :gross-floor gross-floor
             :message (str "The gross target " (fmt gross)
                           " sits below the gross floor " (fmt gross-floor) ".")}))))

(defn- unsupported-constraint-violations
  "Every hard channel the constraint encoder can currently produce (bounds,
  locks, book equalities, net band, gross floor/cap, turnover) is supported by
  the sequential solver, so this returns []. The seam exists so a FUTURE
  encoded channel gets an explicit :equal-risk-unsupported-constraint
  violation here instead of being silently dropped."
  [_encoded-constraints]
  [])

(defn presolve
  "Explicit feasibility screening before the nonlinear solver. Returns
  {:status :ok :targets {:gross} :books {...} :warnings []}
  or {:status :infeasible :violations [...]} with specific codes.
  The optional opts map carries :objective-label (default \"Equal Risk\") so
  objectives that reuse this screening (Risk-weighted sizing) produce
  violation messages naming themselves; the :equal-risk-* violation CODES are
  shared deliberately — the infeasible panel maps codes to setup controls."
  ([encoded-constraints covariance]
   (presolve encoded-constraints covariance nil))
  ([{:keys [instrument-ids lower-bounds upper-bounds] :as encoded-constraints}
    covariance
    {:keys [objective-label] :or {objective-label "Equal Risk"}}]
  (let [tolerance (:exposure-feasibility equal-risk/tolerances)
        n (count instrument-ids)
        covariance-result (risk-contributions/validate-covariance covariance n)
        covariance-violations (when (= :error (:status covariance-result))
                                [{:code (case (:reason covariance-result)
                                          :covariance-asymmetric :equal-risk-covariance-asymmetric
                                          :equal-risk-covariance-shape)
                                  :details (:details covariance-result)
                                  :message (case (:reason covariance-result)
                                             :covariance-asymmetric
                                             (str "The covariance matrix is materially asymmetric; "
                                                  objective-label
                                                  " will not silently repair invalid risk input.")
                                             "The covariance matrix is missing, non-finite, or misaligned with the selected assets.")}])
        {:keys [gross]} (equal-risk/exposure-targets encoded-constraints)
        exposure-violations* (exposure-violations encoded-constraints gross tolerance
                                                  objective-label)
        books (equal-risk/book-split encoded-constraints)
        side-violations* (side-violations encoded-constraints books objective-label)
        targets-valid? (finite-number? gross)
        gross-violations* (when (and targets-valid?
                                     (empty? exposure-violations*)
                                     (empty? side-violations*))
                            (aggregate-gross-violations books
                                                        lower-bounds
                                                        upper-bounds
                                                        gross
                                                        tolerance))
        violations (vec (concat covariance-violations
                                exposure-violations*
                                side-violations*
                                gross-violations*
                                (unsupported-constraint-violations encoded-constraints)))]
    (if (seq violations)
      {:status :infeasible
       :violations violations}
      {:status :ok
       :covariance (:covariance covariance-result)
       :targets {:gross gross}
       :books (select-keys books [:long :short])
       :warnings []}))))
