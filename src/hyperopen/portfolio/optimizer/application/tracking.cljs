(ns hyperopen.portfolio.optimizer.application.tracking)

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- positive-number?
  [value]
  (and (finite-number? value)
       (pos? value)))

(defn- current-weight-by-instrument
  [current-snapshot nav-usdc]
  (into {}
        (keep (fn [exposure]
                (let [instrument-id (:instrument-id exposure)
                      notional (:signed-notional-usdc exposure)]
                  (when (and instrument-id
                             (finite-number? notional))
                    [instrument-id {:current-weight (/ notional nav-usdc)
                                    :signed-notional-usdc notional}]))))
        (:exposures current-snapshot)))

(defn- not-trackable
  [scenario-id as-of-ms code]
  {:scenario-id scenario-id
   :as-of-ms as-of-ms
   :status :not-trackable
   :warnings [{:code code}]})

(defn- realized-return
  [baseline-nav current-nav fallback]
  (if (and (positive-number? baseline-nav)
           (positive-number? current-nav))
    (dec (/ current-nav baseline-nav))
    fallback))

(defn build-tracking-snapshot
  [{:keys [scenario-id as-of-ms saved-run current-snapshot]}]
  (let [result (:result saved-run)
        nav-usdc (get-in current-snapshot [:capital :nav-usdc])]
    (cond
      (not= :solved (:status result))
      (not-trackable scenario-id as-of-ms :missing-solved-run)

      (not (positive-number? nav-usdc))
      (not-trackable scenario-id as-of-ms :missing-current-nav)

      :else
      (let [ids (vec (:instrument-ids result))
            target-weights (vec (:target-weights result))
            current-by-id (current-weight-by-instrument current-snapshot nav-usdc)
            rows (mapv (fn [instrument-id target-weight]
                         (let [{:keys [current-weight signed-notional-usdc]}
                               (get current-by-id instrument-id)
                               current-weight* (or current-weight 0)
                               target-weight* (or target-weight 0)
                               drift (- current-weight* target-weight*)]
                           {:instrument-id instrument-id
                            :current-weight current-weight*
                            :target-weight target-weight*
                            :weight-drift drift
                            :signed-notional-usdc (or signed-notional-usdc 0)}))
                       ids
                       target-weights)
            squared-drifts (map #(* (:weight-drift %) (:weight-drift %)) rows)
            drift-rms (if (seq rows)
                        (js/Math.sqrt (/ (reduce + 0 squared-drifts)
                                         (count rows)))
                        0)
            max-drift (reduce max 0 (map #(js/Math.abs (:weight-drift %)) rows))]
        {:scenario-id scenario-id
         :as-of-ms as-of-ms
         :status :tracked
         :nav-usdc nav-usdc
         :weight-drift-rms drift-rms
         :max-abs-weight-drift max-drift
         :predicted-return (:expected-return result)
         :predicted-volatility (:volatility result)
         :realized-return nil
         :rows rows
         :warnings []}))))

(defn append-tracking-snapshot
  [tracking-record snapshot]
  (let [scenario-id (:scenario-id snapshot)
        snapshots (vec (:snapshots tracking-record))
        baseline-nav (some :nav-usdc snapshots)
        snapshot* (assoc snapshot
                         :realized-return
                         (realized-return baseline-nav
                                          (:nav-usdc snapshot)
                                          (:realized-return snapshot)))]
    {:scenario-id scenario-id
     :updated-at-ms (:as-of-ms snapshot*)
     :snapshots (conj snapshots snapshot*)}))
