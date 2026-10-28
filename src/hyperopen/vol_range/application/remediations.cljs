(ns hyperopen.vol-range.application.remediations
  "Typed Vol Range remediations (HO-SPEC-014 v2.1 sections 6.3, 6.4, VR-22,
  VR-23). Every offer is a draft patch whose full re-plan is :ready. Offers
  change only the draft; nothing here builds an action or touches live orders."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.plan-core :as core]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- d [s] (precision/parse-decimal s))
(defn- ds [x] (precision/decimal->string x))

(defn apply-draft-patch
  "Apply a vector of [path value] pairs to the draft only."
  [draft patch]
  (reduce (fn [acc [path value]] (assoc-in acc path value)) draft patch))

(defn- ready? [input patch]
  (= :ready (:status (core/prepare-plan-core
                      (assoc input :draft (apply-draft-patch (:draft input) patch)
                             :remediations? false)))))

(defn patch-ready?
  "Public form of the readiness check used for every offer (VR-23)."
  [input patch]
  (ready? input patch))

(defn- offer [id label patch] {:id id :label label :patch patch})

(defn- blank? [x] (or (nil? x) (and (string? x) (= "" (.trim x)))))

(defn- error-sides [result code]
  (->> (:errors result)
       (filter #(= code (:code %)))
       (keep #(get-in % [:data :side]))
       distinct
       (sort-by {:buy 0 :sell 1})))

(defn- side-word [side] (if (= side :buy) "buy" "sell"))

(defn- next-grid-step
  "Smallest multiple of the remediation step strictly greater than `x`."
  [x]
  (let [step (d (:remediation-step-sigma contracts/product-policy))]
    (precision/mul (precision/add (precision/divide x step 0 :floor) (d "1")) step)))

(defn- raise-start [input side]
  (let [sd (get-in input [:draft side])
        end (d (:end-sigma sd))
        step (d (:remediation-step-sigma contracts/product-policy))]
    (when (and (d (:start-sigma sd)) end)
      (loop [c (next-grid-step (d (:start-sigma sd)))]
        (when (<= (precision/cmp c end) 0)
          (let [v (precision/to-fixed c 2)
                patch [[[side :start-sigma] v]]]
            (if (ready? input patch)
              (offer (if (= side :buy) :start-buys-at :start-sells-at)
                     (str "Start " (side-word side) "s at " v "σ") patch)
              (recur (precision/add c step)))))))))

(defn- fewer-rungs [input side id label-fn]
  (let [n (some-> (get-in input [:draft side :count]) str d ds js/Number)]
    (when (and n (js/Number.isInteger n))
      (some (fn [k]
              (let [patch [[[side :count] k]]]
                (when (ready? input patch) (offer id (label-fn k) patch))))
            (range (dec n) 0 -1)))))

(defn- widen-range [input side]
  (let [sd (get-in input [:draft side])
        max-end (d (get-in contracts/product-policy [:distance-sigma :max]))
        step (d (:remediation-step-sigma contracts/product-policy))]
    (when-let [end (d (:end-sigma sd))]
      (loop [c (next-grid-step end)]
        (when (<= (precision/cmp c max-end) 0)
          (let [v (precision/to-fixed c 2)
                patch [[[side :end-sigma] v]]]
            (if (ready? input patch)
              (offer :widen-range (str "Widen " (side-word side) " range to " v "σ") patch)
              (recur (precision/add c step)))))))))

(defn- one-click [input result]
  (let [draft (:draft input)
        buy-t (get-in result [:totals :buy])
        buys? (and (get-in draft [:buy :enabled?]) (pos? (:rung-count buy-t 0)))
        n (when-let [o (:all-buys-first (:inventory-outcomes result))]
            (when buys?
              (precision/quantize (d o) (:sz-decimals (:market input)) :ceil)))
        ;; USDC limits: compare in base (the plan's converted maximum) and
        ;; write the USDC field, rounded up to the cent so it converts back
        ;; to at least n.
        limits-quote? (= :quote (get-in draft [:inventory :limit-unit]))
        limit-price (when limits-quote? (some-> (get-in result [:inventory-outcomes :limit-price]) d))
        limit-path [:inventory (if limits-quote? :maximum-quote :maximum)]
        maximum (if limit-price
                  (some-> (get-in result [:inventory-outcomes :maximum]) d)
                  (d (str (get-in draft [:inventory :maximum]))))
        ;; A blank maximum is no limit; offer one only where tilt needs it.
        max-blank? (and (blank? (get-in draft limit-path))
                        (some #(= :input/maximum-required-for-tilt (:code %)) (:errors result)))
        needs-max? (and n (or (not limits-quote?) limit-price) (or max-blank? (and maximum (neg? (precision/cmp maximum n)))))
        max-value (when n (if limit-price
                            (precision/to-fixed (precision/quantize (precision/mul n limit-price) 2 :ceil) 2)
                            (ds n)))
        max-label (when n (if limit-price (str max-value " USDC") (ds n)))
        total (when buys?
                (some-> (:max-debit buy-t) d (precision/quantize 2 :ceil)))
        ;; In USDC mode the amount is the cap and the plan is sized to fit it.
        usdc-mode? (= :quote (get-in draft [:buy :amount-unit]))
        cap-raw (get-in draft [:buy :max-usdc-debit])
        cap (d (str cap-raw))
        needs-cap? (and total (not usdc-mode?) (or (blank? cap-raw) (and cap (neg? (precision/cmp cap total)))))
        max-patch (when needs-max? [[limit-path max-value]])
        cap-patch (when needs-cap? [[[:buy :max-usdc-debit] (precision/to-fixed total 2)]])
        joint-patch (when (and max-patch cap-patch) (into max-patch cap-patch))]
    ;; VR-23: every offer's OWN patch must yield a ready plan. When neither
    ;; single patch does but both together do (maximum and cap both missing),
    ;; offer one combined remediation instead of two that each fail alone.
    (let [max-ok? (and max-patch (ready? input max-patch))
          cap-ok? (and cap-patch (ready? input cap-patch))]
      (cond-> []
        max-ok?
        (conj (offer :set-maximum-all-buys (str "Set maximum to " max-label " · all buys filled") max-patch))
        cap-ok?
        (conj (offer :use-plan-total "Use plan total" cap-patch))
        (and joint-patch (not max-ok?) (not cap-ok?) (ready? input joint-patch))
        (conj (offer :fill-required-limits
                     (str "Set maximum to " max-label " and USDC cap to " (precision/to-fixed total 2))
                     joint-patch))))))

(defn offers
  "Remediations for an :invalid plan: crossing, notional, duplicate, then
  one-click. Each candidate re-runs the whole planner with :remediations? false
  and is included only when that plan is :ready (joint rule for blank maximum
  and cap documented in the ExecPlan Decision Log)."
  [plan-input plan-result]
  (if-not (= :invalid (:status plan-result))
    []
    (let [crossing (error-sides plan-result :plan/post-only-cross)
          notional (distinct (concat (error-sides plan-result :plan/min-notional)
                                     (error-sides plan-result :input/quote-amount-too-small)))
          duplicate (error-sides plan-result :plan/duplicate-price)
          anchor-patch [[[:anchor :mode] :market-price]]]
      (vec
       (concat
        (when (seq crossing)
          (concat
           (when (and (not= :market-price (get-in plan-input [:draft :anchor :mode]))
                      (ready? plan-input anchor-patch))
             [(offer :anchor-to-market "Anchor to market price" anchor-patch)])
           (keep #(raise-start plan-input %) crossing)))
        (keep (fn [side]
                (fewer-rungs plan-input side :use-fewer-rungs
                             #(str "Use " % " " (side-word side) " rungs")))
              notional)
        (mapcat (fn [side]
                  (keep identity
                        [(fewer-rungs plan-input side :reduce-rung-count
                                      #(str "Reduce to " % " " (side-word side) " rungs"))
                         (widen-range plan-input side)]))
                duplicate)
        (one-click plan-input plan-result))))))
