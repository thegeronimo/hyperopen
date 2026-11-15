(ns hyperopen.test-runner-support
  (:require [cljs.test :as cljs-test]))

(def ^:private process-exit-reporter
  ::process-exit)

(defn exit-code-for-results
  [results]
  (if (zero? (+ (or (:fail results) 0)
                (or (:error results) 0)))
    0
    1))

(defn apply-process-exit!
  [results]
  (let [exit-code (exit-code-for-results results)]
    (when (exists? js/process)
      (set! (.-exitCode js/process) exit-code)
      (js/setTimeout
       (fn []
         (.exit js/process exit-code))
       0)))
  results)

(defn completion-reporting-env
  [{:keys [summary-heading completion-handler]}]
  (cond-> (cljs-test/empty-env process-exit-reporter)
    summary-heading (assoc ::summary-heading summary-heading)
    completion-handler (assoc ::completion-handler completion-handler)))

(defn process-exit-reporting-env
  [summary-heading]
  (completion-reporting-env {:summary-heading summary-heading
                             :completion-handler apply-process-exit!}))

(defn- delegate-default-report!
  [m]
  (let [env (cljs-test/get-current-env)]
    (cljs-test/set-env! (assoc env :reporter ::cljs-test/default))
    (cljs-test/report m)
    (cljs-test/update-current-env! [:reporter] (constantly process-exit-reporter))))

(defmethod cljs-test/report [process-exit-reporter :pass] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :fail] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :error] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :summary] [m]
  (when-let [heading (::summary-heading (cljs-test/get-current-env))]
    (println heading))
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :begin-test-ns] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :end-test-ns] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :begin-test-var] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :end-test-var] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :end-test-all-vars] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :end-test-vars] [m]
  (delegate-default-report! m))

(defmethod cljs-test/report [process-exit-reporter :end-run-tests] [m]
  (when-let [completion-handler (::completion-handler (cljs-test/get-current-env))]
    (completion-handler m)))
