(ns hyperopen.views.portfolio.optimize.infeasible-panel-remediation-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions.draft :as draft-actions]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.constraints :as constraints]
            [hyperopen.portfolio.optimizer.domain.objectives :as objectives]
            [hyperopen.views.portfolio.optimize.infeasible-panel :as panel]))

(def ^:private draft-constraints-path contracts/draft-constraints-path)

(defn- engine-constraints
  "The draft -> engine constraint rename `request_builder/normalize-constraints`
  performs (it is private; this mirrors it for the keys these fixes write)."
  [constraints]
  (let [net (cond-> {}
              (some? (:net-min constraints)) (assoc :min (:net-min constraints))
              (some? (:net-max constraints)) (assoc :max (:net-max constraints)))]
    (cond-> (dissoc constraints :gross-min :gross-max :net-min :net-max :asset-overrides)
      (contains? constraints :gross-max)
      (assoc :gross-leverage (:gross-max constraints))

      (contains? constraints :gross-min)
      (assoc :gross-floor (:gross-min constraints))

      (contains? constraints :asset-overrides)
      (assoc :per-asset-overrides (:asset-overrides constraints))

      (seq net)
      (assoc :net-exposure net))))

(defn- diagonal-covariance
  [n]
  (mapv (fn [row]
          (mapv (fn [col] (if (= row col) (+ 0.04 (* 0.001 row)) 0)) (range n)))
        (range n)))

(defn- plan-for
  [{:keys [universe constraints current-weights objective]}]
  (let [draft (contracts/migrate-draft {:schema-version 1
                                        :universe universe
                                        :constraints constraints})
        encoded (constraints/encode-constraints
                 {:universe (:universe draft)
                  :current-weights current-weights
                  :constraints (engine-constraints (:constraints draft))})
        ids (:instrument-ids encoded)]
    (objectives/build-solver-plan
     {:objective (or objective {:kind :minimum-variance})
      :instrument-ids ids
      :expected-returns (mapv #(+ 0.05 (* 0.01 %)) (range (count ids)))
      :covariance (diagonal-covariance (count ids))
      :encoded-constraints encoded})))

(defn- apply-dispatch
  "Runs the REAL action the button dispatches and folds its saves back into the
  draft constraints, exactly as nexus + :effects/save-many would."
  [constraints [_action-id constraint-key value]]
  (->> (draft-actions/set-portfolio-optimizer-constraint nil constraint-key value)
       (filter #(= :effects/save-many (first %)))
       (mapcat second)
       (reduce (fn [acc [path saved]]
                 (if (= draft-constraints-path (vec (butlast path)))
                   (assoc acc (last path) saved)
                   acc))
               constraints)))

(defn- after-fix
  [scenario suggestion]
  (plan-for (update scenario :constraints
                    #(reduce apply-dispatch % (:actions suggestion)))))

(defn- long-leg
  [idx]
  {:instrument-id (str "perp:L" idx)
   :market-type :perp
   :shortable? true
   :position-side :long})

(defn- short-leg
  [id]
  {:instrument-id id
   :market-type :perp
   :shortable? true
   :position-side :short})

(def ^:private thin-short-book
  {:label "above band, 2.8% net band"
   :universe (conj (mapv long-leg (range 18))
                   (short-leg "perp:S1")
                   (short-leg "perp:S2"))
   :constraints {:long-only? false
                 :include-spot? false
                 :max-asset-weight 15.45
                 :gross-min 3.995
                 :gross-max 4.005
                 :net-min 1.25
                 :net-max 1.25
                 :net-band-pct 0.028
                 :asset-overrides {"perp:S1" {:max-short-weight 0.03}
                                   "perp:S2" {:max-short-weight 0.05}}}})

(def ^:private thin-short-book-no-band
  (assoc thin-short-book
         :label "above band, no net band set"
         :constraints (assoc (:constraints thin-short-book) :net-band-pct 0.0)))

(def ^:private thin-long-book
  {:label "below band"
   :universe [(long-leg 0) (short-leg "perp:S1")]
   :constraints {:long-only? false
                 :include-spot? false
                 :max-asset-weight 4.0
                 :gross-min 3.5
                 :gross-max 4.0
                 :net-min 1.0
                 :net-max 2.0
                 :net-band-pct 0.0
                 :asset-overrides {"perp:L0" {:max-long-weight 1.5}}}})

(def ^:private cash-collapse
  {:label "minimum risk collapses to cash"
   :universe [(long-leg 0) (short-leg "perp:S1")]
   :current-weights {"perp:L0" 0.9 "perp:S1" -0.4}
   :constraints {:long-only? false
                 :include-spot? false
                 :max-asset-weight 2.0
                 :gross-max 4.0
                 :net-min 0
                 :net-max 0
                 :net-band-pct 0.0
                 :asset-overrides {"perp:S1" {:max-short-weight 0.1}}}})

(def ^:private scenarios
  [thin-short-book thin-short-book-no-band thin-long-book cash-collapse])

(deftest every-one-click-fix-leaves-a-runnable-request-test
  (doseq [scenario scenarios]
    (let [plan (plan-for scenario)
          label (:label scenario)
          fixes (filterv :actions (panel/remediation-suggestions plan))]
      (is (= :infeasible (:status plan))
          (str label ": the scenario must actually be blocked"))
      (is (seq fixes)
          (str label ": a blocked run with no one-click fix is the old failure"))
      (doseq [fix fixes]
        (let [after (after-fix scenario fix)]
          (is (not= :infeasible (:status after))
              (str label " / " (:id fix) " (\"" (:label fix) "\") still infeasible: "
                   (:reason after) " "
                   (mapv :code (get-in after [:details :violations])))))))))

(deftest each-scenario-offers-the-fixes-its-payload-supports-test
  (is (= [:raise-net-target :lower-gross-floor :clear-gross-floor :widen-net-band]
         (mapv :id (filterv :actions (panel/remediation-suggestions
                                      (plan-for thin-short-book))))))
  (is (contains? (set (mapv :id (panel/remediation-suggestions
                                 (plan-for thin-short-book-no-band))))
                 :widen-net-band))
  (is (= [:lower-net-target :lower-gross-floor :clear-gross-floor :widen-net-band]
         (mapv :id (filterv :actions (panel/remediation-suggestions
                                      (plan-for thin-long-book))))))
  (is (= [:set-gross-floor]
         (mapv :id (filterv :actions (panel/remediation-suggestions
                                      (plan-for cash-collapse)))))))

(deftest the-two-banners-fixes-no-longer-cycle-into-each-other-test
  (let [plan (plan-for cash-collapse)
        fix (first (filterv :actions (panel/remediation-suggestions plan)))
        after (after-fix cash-collapse fix)]
    (is (= :objective-collapses-to-cash (:reason plan)))
    (is (= "Set Gross Exposure Min to 0.20x" (:label fix))
        "0.20x = 2 * the 0.1x short cap, NOT the 1.30x book or the 4.00x cap")
    (is (= :ok (:status after)))
    (is (= [] (panel/remediation-suggestions after))
        "and the run it produces has nothing left to remediate")))

(defn- cash-collapse-result
  [violation-overrides]
  {:status :infeasible
   :reason :objective-collapses-to-cash
   :details {:violations [(merge {:code :minimum-risk-without-exposure-floor
                                  :objective-kind :minimum-variance
                                  :current-gross 4.0
                                  :max-feasible-gross-floor 4.0
                                  :suggested-gross-floor 4.0}
                                 violation-overrides)]}})

(defn- copy-of
  [result id]
  (:copy (first (filter #(= id (:id %)) (panel/remediation-suggestions result)))))

(deftest minimum-risk-reads-the-book-branch-off-the-unsnapped-suggestion-test
  (let [copy (copy-of (cash-collapse-result {:current-gross 1.307
                                             :suggested-gross-floor 1.307})
                      :set-gross-floor)]
    (is (str/includes? copy "A floor at the 1.30x your current book already carries"))
    (is (not (str/includes? copy "the most this setup allows")))))

(deftest minimum-risk-says-so-when-no-gross-floor-fits-at-all-test
  (let [result (cash-collapse-result {:current-gross 0
                                      :max-feasible-gross-floor 0
                                      :suggested-gross-floor nil})]
    (is (str/includes? (copy-of result :set-any-gross-floor)
                       "no positive floor fits inside this setup's net exposure window"))
    (is (not (str/includes? (copy-of result :set-any-gross-floor) "Anywhere up to")))
    (is (= #{:net-min :net-max} (panel/highlighted-control-keys result))))
  (let [unknown (cash-collapse-result {:current-gross 0
                                       :max-feasible-gross-floor nil
                                       :suggested-gross-floor nil})]
    (is (str/includes? (copy-of unknown :set-any-gross-floor)
                       "choose the exposure you want the result to carry"))
    (is (contains? (panel/highlighted-control-keys unknown) :gross-min))))
