(ns hyperopen.portfolio.optimizer.application.progress
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]))

(defn- clamp-percent
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else 0)]
    (-> (if (and (number? n)
                 (not (js/isNaN n))
                 (js/isFinite n))
          n
          0)
        (max 0)
        (min 100))))

(defn- keyword-label
  [value]
  (-> (or value :unknown)
      name
      (str/replace "-" " ")))

(defn- return-model-label
  [return-model]
  (case (:kind return-model)
    :black-litterman "Black-Litterman posterior"
    :ew-mean "EW mean estimator"
    :historical-mean "historical mean estimator"
    "expected return estimator"))

(defn- risk-model-label
  [risk-model]
  (case (:kind risk-model)
    :ledoit-wolf "shrinkage estimator"
    :ledoit-wolf-dense "Ledoit-Wolf estimator"
    :diagonal-shrink "shrinkage estimator"
    :mixed-frequency "mixed-frequency estimator"
    :sample-covariance "sample covariance"
    "risk estimator"))

(defn default-steps
  [request]
  (let [asset-count (count (or (:requested-universe request)
                               (:universe request)))
        return-model (:return-model request)
        risk-model (:risk-model request)
        objective-kind (get-in request [:objective :kind])
        covariance-only? (contains? contracts-constants/covariance-only-objective-kinds
                                    objective-kind)
        frontier-count (or (get-in request [:objective :frontier-points])
                           40)]
    [{:id :fetch-returns
      :label "fetch returns matrix"
      :detail (if (pos? asset-count)
                (str asset-count " assets")
                "selected universe")
     :status :pending
     :percent 0}
     {:id :risk-model
      :label (risk-model-label risk-model)
      :detail (keyword-label (:kind risk-model))
      :status :pending
      :percent 0}
     {:id :return-model
      :label (return-model-label return-model)
      :detail (keyword-label (:kind return-model))
      :status :pending
      :percent 0}
     (case objective-kind
       :equal-risk
       {:id :solve
        :label "equal-risk solve"
        :detail "sequential QP"
        :status :pending
        :percent 0}
       :inverse-volatility
       {:id :solve
        :label "risk-weighted sizing"
        :detail "projection QP"
        :status :pending
        :percent 0}
       {:id :solve
        :label "QP solve"
        :detail "OSQP"
        :status :pending
        :percent 0})
     (if covariance-only?
       {:id :frontier
        :label "target selection"
        :detail "selected point"
        :status :pending
        :percent 0}
       {:id :frontier
        :label "frontier sweep"
        :detail (str frontier-count " points")
        :status :pending
        :percent 0})
     {:id :diagnostics
      :label "diagnostics + rebalance preview"
      :detail "signed exposure"
      :status :pending
      :percent 0}]))

(defn- step-weight
  [_step]
  1)

(defn overall-percent
  [steps]
  (let [steps* (vec steps)
        total-weight (reduce + (map step-weight steps*))]
    (if (pos? total-weight)
      (clamp-percent
       (/ (reduce + (map (fn [step]
                           (* (step-weight step)
                              (clamp-percent (:percent step))))
                         steps*))
          total-weight))
      0)))

(defn- step-index
  [steps step-id]
  (first
   (keep-indexed (fn [idx step]
                   (when (= step-id (:id step))
                     idx))
                 steps)))

(defn mark-step
  [progress step-id attrs]
  (let [steps (vec (:steps progress))
        attrs* (cond-> attrs
                 (contains? attrs :percent)
                 (update :percent clamp-percent))]
    (if-let [idx (step-index steps step-id)]
      (let [steps* (update steps idx merge attrs*)]
        (assoc progress
               :steps steps*
               :active-step (if (= :succeeded (:status attrs*))
                              (:active-step progress)
                              step-id)
               :overall-percent (overall-percent steps*)))
      progress)))

(defn begin-progress
  [{:keys [run-id scenario-id request started-at-ms]}]
  (let [steps (default-steps request)]
    {:status :running
     :run-id run-id
     :scenario-id scenario-id
     :started-at-ms started-at-ms
     :completed-at-ms nil
     :active-step :fetch-returns
     :overall-percent (overall-percent steps)
     :steps steps
     :error nil}))

(defn fail-progress
  [progress completed-at-ms error]
  (assoc progress
         :status :failed
         :completed-at-ms completed-at-ms
         :error error))

(defn succeed-progress
  [progress completed-at-ms]
  (let [steps* (mapv #(assoc % :status :succeeded :percent 100)
                     (:steps progress))]
    (assoc progress
           :status :succeeded
           :completed-at-ms completed-at-ms
           :active-step nil
           :overall-percent 100
           :steps steps*
           :error nil)))

(defn worker-progress
  [progress payload]
  (let [step-id (some-> (:step payload) keyword)]
    (if step-id
      (mark-step progress
                 step-id
                 (select-keys payload [:status :percent :detail :message]))
      progress)))

(def display-trickle-headroom
  "Percent points the smoothed display bar may run ahead of true progress while a
  step's work is in flight. The worker solves each batch synchronously and only
  flushes its progress messages afterwards, so without a trickle the bar freezes
  between bursts; this lets it keep gliding without overstating progress much."
  10)

(def ^:private display-trickle-ease-rate
  "Fraction of the gap to the soft cap closed per ticker frame (~120ms)."
  0.08)

(defn smooth-display-percent
  "Eased trickle value for the progress bar. Monotonic (never below the previous
  display or true progress) and capped just under 100 at true+headroom, so it
  parks near the cap during a long stall and snaps up whenever true progress
  overtakes it."
  [previous real]
  (let [real (clamp-percent real)
        previous (clamp-percent (if (number? previous) previous real))
        target (min 99 (+ real display-trickle-headroom))
        base (max previous real)
        eased (+ base (* (- target base) display-trickle-ease-rate))]
    (clamp-percent (max base eased))))

(defn tick-progress
  "Advance a running run's smoothed display percent and wall clock by one ticker
  frame. Non-running progress is returned unchanged so completed/failed runs keep
  their final numbers."
  [progress now-ms]
  (if (= :running (:status progress))
    (assoc progress
           :display-percent (smooth-display-percent (:display-percent progress)
                                                    (:overall-percent progress))
           :now-ms now-ms)
    progress))
