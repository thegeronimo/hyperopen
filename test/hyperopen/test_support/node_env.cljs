(ns hyperopen.test-support.node-env
  "The Node environment every test bundle runs in. Loaded for its side
  effects: every namespace of a bundle is loaded before its first test runs,
  so one require per bundle is enough. `test-runner` requires it for the main
  bundle, `hyperopen.vol-range.simulator` for `vol-range-test` and
  `hyperopen.startup.runtime-test` for `ws-test`. (A custom `:main` on the
  `:node-test` builds is not an option: shadow-cljs then drops the test
  namespaces it recompiled in that run.)

  1. No Web Locks. Node 24 added `navigator.locks`; CI's Node 22 has none.
     The suites are written for the in-tab queue of `hyperopen.platform.locks`
     (`reset-in-tab-state!` between tests). With real Web Locks a lock one test
     leaves held is still held in the next test, which then waits forever.
  2. No silent early exit. When an async test never finishes, Node's event
     loop drains and the process exits 0 before the summary, so a truncated
     run looked like a pass. A run that ends without its summary now fails."
  (:require [cljs.test :as cljs-test]))

(defonce ^:private summary-reported? (atom false))

(defn hide-web-locks!
  "Make `navigator.locks` read as undefined in this process."
  []
  (when-let [navigator (when (exists? js/navigator) js/navigator)]
    (when (some? (.-locks navigator))
      (js/Object.defineProperty navigator "locks"
                                #js {:value js/undefined :configurable true :writable true}))))

(defn unfinished-run-exit-code
  "The exit code of a process that is exiting with `code`: 1 when a clean
  exit comes before the test summary, else `code`."
  [code summary-reported?]
  (if (and (zero? (or code 0)) (not summary-reported?)) 1 code))

(defn- install-summary-guard! []
  (let [default-summary (get-method cljs-test/report [::cljs-test/default :summary])]
    (defmethod cljs-test/report [::cljs-test/default :summary] [m]
      (reset! summary-reported? true)
      (default-summary m)))
  (when (exists? js/process)
    (.on js/process "exit"
         (fn [code]
           (let [code* (unfinished-run-exit-code code @summary-reported?)]
             (when (not= code code*)
               (.error js/console
                       (str "\nTest run ended before its summary: an async test never finished. "
                            "The last \"Testing ...\" line above names the namespace."))
               (set! (.-exitCode js/process) code*)))))))

(defonce ^:private installed
  (do (hide-web-locks!)
      (install-summary-guard!)
      true))
