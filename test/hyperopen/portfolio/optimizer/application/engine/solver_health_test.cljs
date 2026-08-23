(ns hyperopen.portfolio.optimizer.application.engine.solver-health-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.engine :as engine]
            [hyperopen.portfolio.optimizer.application.engine.solver-health :as solver-health]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(def ^:private csp-error
  (str "CompileError: WebAssembly.instantiate(): Compiling or instantiating "
       "WebAssembly module violates the following Content Security policy "
       "directive because 'unsafe-eval' is not an allowed source of script."))

(defn- osqp-result
  []
  {:status :solved :solver :osqp :weights [0.5 0.5]})

(defn- fallback-result
  ([] (fallback-result csp-error))
  ([error]
   {:status :solved
    :solver :quadprog-fallback
    :fallback-from :osqp
    :fallback-reason :solver-error
    :fallback-message error
    :weights [0.5 0.5]}))

(deftest no-warning-when-every-solve-used-the-primary-solver-test
  ;; The healthy path must stay silent: a warning on every run trains people to
  ;; ignore the rail, which is the failure mode this surface exists to fix.
  (is (nil? (solver-health/warnings [(osqp-result) (osqp-result)] nil)))
  (is (nil? (solver-health/warnings [] nil)))
  (is (nil? (solver-health/warnings nil nil)))
  (is (nil? (solver-health/warnings [(osqp-result)]
                                    {:unconstrained [(osqp-result)]})))
  (is (nil? (solver-health/warnings [{:status :solved :solver :quadprog}] nil))
      "an explicitly configured quadprog run is not a fallback"))

(deftest counts-frontier-solves-not-just-selection-solves-test
  ;; The frontier sweep is where nearly every solve happens, and a
  ;; min-variance selection often takes the closed-form path and never touches
  ;; the QP solver at all -- so reading only :solver-results missed the
  ;; ordinary case entirely.
  (let [warnings (solver-health/warnings
                  [{:status :solved :solver :closed-form :weights [0.5 0.5]}]
                  {:unconstrained [(fallback-result) (fallback-result)]})
        warning (first warnings)]
    (is (= 1 (count warnings)) "one warning per run, not one per solve")
    (is (= :solver-fallback-used (:code warning)))
    (is (str/starts-with? (:message warning) "2 of 3 solves"))
    (is (= #{:code :message} (set (keys warning)))
        "only :code survives the worker boundary as a keyword, so carry nothing else")))

(deftest says-every-solve-when-nothing-reached-the-primary-solver-test
  (let [message (:message (first (solver-health/warnings
                                  [(fallback-result)]
                                  {:unconstrained [(fallback-result)]})))]
    (is (str/starts-with? message "Every solve in this run (2)"))))

(deftest carries-the-underlying-solver-error-test
  ;; The whole point: the CSP CompileError is the sentence that tells someone
  ;; what to actually fix, so it has to reach the rail.
  (let [message (:message (first (solver-health/warnings
                                  nil
                                  {:unconstrained [(fallback-result)]})))]
    (is (str/includes? message "Content Security policy"))
    (is (str/includes? message "Solver error:"))))

(deftest truncates-a-long-solver-error-test
  (let [message (:message (first (solver-health/warnings
                                  [(fallback-result (apply str (repeat 900 "x")))]
                                  nil)))]
    (is (str/includes? message "..."))
    (is (< (count message) 500) "a stack trace must not flood the diagnostics rail")))

(deftest tolerates-blank-and-malformed-solver-results-test
  (let [warning (first (solver-health/warnings
                        [nil
                         "not-a-map"
                         (assoc (fallback-result) :fallback-message "  ")]
                        nil))]
    (is (= :solver-fallback-used (:code warning)))
    (is (not (str/includes? (:message warning) "Solver error:"))
        "a blank error adds no sentence")
    (is (str/starts-with? (:message warning) "Every solve in this run (1)")
        "non-map entries are not counted")))

(defn- stub-solve
  "Returns weights satisfying the fixture's long-only sum-to-one constraints for
  whatever universe a problem carries, so the run reaches the solved payload."
  [calls extras]
  (fn [problem]
    (swap! calls inc)
    (let [n (count (:instrument-ids problem))]
      (merge {:status :solved
              :weights (vec (repeat n (/ 1.0 n)))}
             extras))))

(deftest fallback-warning-reaches-the-engine-result-payload-test
  (let [calls (atom 0)
        result (engine/run-optimization
                (fixtures/sample-engine-request)
                {:solve-problem (stub-solve calls
                                            {:solver :quadprog-fallback
                                             :fallback-from :osqp
                                             :fallback-reason :solver-error
                                             :fallback-message csp-error})})
        warning (first (filter #(= :solver-fallback-used (:code %))
                               (:warnings result)))]
    ;; Assert the solved path was actually taken and the stub actually ran --
    ;; a request that quietly fails, or a problem kind that bypasses the
    ;; injected solver, would otherwise let this pass while proving nothing.
    (is (= :solved (:status result)))
    (is (pos? @calls))
    (is (some? warning))
    (is (str/includes? (:message warning) "Content Security policy"))))

(deftest healthy-run-payload-carries-no-solver-warning-test
  (let [calls (atom 0)
        result (engine/run-optimization
                (fixtures/sample-engine-request)
                {:solve-problem (stub-solve calls {:solver :osqp})})
        codes (set (map :code (:warnings result)))]
    (is (= :solved (:status result)))
    (is (pos? @calls))
    (is (not (contains? codes :solver-fallback-used)))))
