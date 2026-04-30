(ns hyperopen.portfolio.optimizer.application.black-litterman-preview)

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- instrument-ids
  [request]
  (mapv :instrument-id (:universe request)))

(defn- prior-returns-by-instrument
  [request]
  (let [prior (get-in request [:black-litterman-prior :weights-by-instrument])]
    (into {}
          (map (fn [instrument-id]
                 [instrument-id (or (get prior instrument-id) 0)]))
          (instrument-ids request))))

(defn- view-weights
  [view]
  (or (:weights view)
      (case (:kind view)
        :absolute
        (when-let [instrument-id (:instrument-id view)]
          {instrument-id 1})
        :relative
        (let [instrument-id (:instrument-id view)
              comparator-id (:comparator-instrument-id view)]
          (when (and instrument-id comparator-id)
            (case (:direction view)
              :underperform {instrument-id -1
                             comparator-id 1}
              {instrument-id 1
               comparator-id -1})))
        nil)))

(defn- view-current-return
  [returns view]
  (reduce-kv (fn [acc instrument-id weight]
               (+ acc (* weight (or (get returns instrument-id) 0))))
             0
             (or (view-weights view) {})))

(defn- apply-view
  [returns view]
  (let [weights (view-weights view)
        q (:return view)
        omega (or (:confidence-variance view) 1)]
    (if (and (map? weights)
             (seq weights)
             (finite-number? q))
      (let [current (view-current-return returns view)
            denominator (+ (reduce + (map #(js/Math.abs %) (vals weights)))
                           (max 0 omega))
            adjustment (if (pos? denominator)
                         (/ (- q current) denominator)
                         0)]
        (reduce-kv (fn [acc instrument-id weight]
                     (update acc instrument-id (fnil + 0) (* weight adjustment)))
                   returns
                   weights))
      returns)))

(defn- posterior-returns-by-instrument
  [request]
  (reduce apply-view
          (prior-returns-by-instrument request)
          (get-in request [:return-model :views])))

(defn build-preview
  [readiness]
  (let [request (:request readiness)]
    (cond
      (not= :ready (:status readiness))
      {:status :unavailable
       :reason :no-eligible-request}

      (not= :black-litterman (get-in request [:return-model :kind]))
      {:status :unavailable
       :reason :not-black-litterman}

      (empty? (get-in request [:return-model :views]))
      {:status :empty
       :view-count 0}

      :else
      (let [prior (prior-returns-by-instrument request)
            posterior (posterior-returns-by-instrument request)
            ids (instrument-ids request)]
        {:status :ready
         :view-count (count (get-in request [:return-model :views]))
         :rows (mapv (fn [instrument-id]
                       {:instrument-id instrument-id
                        :prior-return (get prior instrument-id)
                        :posterior-return (get posterior instrument-id)})
                     ids)}))))
