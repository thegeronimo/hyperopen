(ns hyperopen.portfolio.optimizer.application.engine.solver-health
  "Run-level diagnostics about which solver actually produced a result.

  The optimizer's primary solver is OSQP, an Emscripten WebAssembly build. When
  a solve throws -- a Content-Security-Policy that forbids WebAssembly
  compilation, an exhausted shared heap, a malformed problem --
  infrastructure/fallback.cljs quietly re-solves the same problem with the
  pure-JavaScript quadprog and still reports :status :solved.

  Falling back is the right behaviour, and the answer is not suspect:
  engine/target-selection independently re-validates every returned weight
  vector against the problem's own bounds, equalities, inequalities and L1
  rows, so a degraded solve cannot smuggle an infeasible portfolio into a
  result. What was missing is that nothing ever said it had happened. The
  fallback is roughly an order of magnitude slower, so an entire deployment can
  sit on the slow path indefinitely while every run still reports success --
  which is exactly what a CSP without 'wasm-unsafe-eval' does.

  This namespace turns that silence into one result warning."
  (:require [clojure.string :as str]))

;; A run solves two separate batches: the selection problems, and the display
;; frontier sweep. The sweep is by far the larger -- tens of solves against the
;; selection's one -- and a min-variance selection often takes the closed-form
;; path and never touches the QP solver at all. Reading only :solver-results
;; would therefore miss the ordinary case completely.

(def ^:private fallback-solver-ids
  "Solver ids that exist only because the primary solver failed.
  fallback.cljs relabels a recovered solve :quadprog-fallback rather than
  leaving it as a plain :quadprog run, so the two stay distinguishable."
  #{:quadprog-fallback})

(def ^:private max-error-characters
  ;; Enough to carry the operative clause of a browser CompileError -- the
  ;; "violates the following Content Security policy directive" phrasing lands
  ;; about 90 characters in -- without pasting a whole stack into the rail.
  200)

(defn- fallback-result?
  [result]
  (contains? fallback-solver-ids (:solver result)))

(defn- truncate
  [text]
  (if (> (count text) max-error-characters)
    (str (subs text 0 max-error-characters) "...")
    text))

(defn- first-error
  [results]
  (some->> results
           (keep :fallback-message)
           (remove str/blank?)
           first
           truncate))

(defn- message
  [fallback-count total-count error]
  (str (if (= fallback-count total-count)
         (str "Every solve in this run (" total-count ")")
         (str fallback-count " of " total-count " solves"))
       " fell back to the backup JavaScript solver because the primary"
       " WebAssembly solver failed. Weights are still validated against every"
       " constraint before use, so this result stands, but the run was far"
       " slower than it should be."
       (when error
         (str " Solver error: " error))))

(defn warnings
  "Result warnings naming any solve that used the backup solver. Returns nil
  when every solve used the primary solver, so callers can splice this into a
  warning concat unconditionally.

  Takes the raw solver results rather than the reshaped frontier points on
  purpose: the raw maps still carry :fallback-message (the underlying error,
  which is the sentence that tells someone what to fix), and aliased
  constrained/unconstrained frontiers collapse to one plan here, so the counts
  are real.

  Deliberately carries only :code and :message. :code is one of the
  enum-value-keys the worker-boundary codec keywordizes; a :solver key would
  arrive on the main thread as a bare string instead."
  [solver-results display-frontier-results]
  (let [results (filter map? (concat solver-results
                                     (mapcat val display-frontier-results)))
        fallbacks (filter fallback-result? results)]
    (when (seq fallbacks)
      [{:code :solver-fallback-used
        :message (message (count fallbacks)
                          (count results)
                          (first-error fallbacks))}])))
