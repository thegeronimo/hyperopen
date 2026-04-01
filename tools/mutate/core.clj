(ns tools.mutate.core
  (:require [clojure.string :as str]
            [tools.mutate.coverage :as coverage]
            [tools.mutate.filesystem :as fs]
            [tools.mutate.manifest :as manifest]
            [tools.mutate.report-output :as report-output]
            [tools.mutate.runner :as runner]
            [tools.mutate.source :as source]))

(def suite-order
  [:test :ws-test])

(defn suite-command
  [suite]
  (case suite
    :test "npx shadow-cljs compile test && node out/test.js"
    :ws-test "npx shadow-cljs compile ws-test && node out/ws-test.js"))

(defn clean-suite-command
  [suite]
  (case suite
    :test "rm -rf .shadow-cljs/builds/test out/test.js && npx shadow-cljs --force-spawn compile test && node out/test.js"
    :ws-test "rm -rf .shadow-cljs/builds/ws-test out/ws-test.js && npx shadow-cljs --force-spawn compile ws-test && node out/ws-test.js"))

(defn suite-compile-command
  [suite]
  (case suite
    :test "npx shadow-cljs compile test"
    :ws-test "npx shadow-cljs compile ws-test"))

(defn clean-suite-compile-command
  [suite]
  (case suite
    :test "rm -rf .shadow-cljs/builds/test out/test.js && npx shadow-cljs --force-spawn compile test"
    :ws-test "rm -rf .shadow-cljs/builds/ws-test out/ws-test.js && npx shadow-cljs --force-spawn compile ws-test"))

(defn prepare-test-runner!
  []
  (let [result (runner/run-command "node tools/generate-test-runner.mjs" {})]
    (when-not (runner/success? result)
      (throw (ex-info "Failed to generate the test runner before mutation testing."
                      {:output (:output result)})))))

(defn effective-since-last-run?
  [opts prior-manifest]
  (and (nil? (:lines opts))
       (not (:mutate-all opts))
       (or (:since-last-run opts)
           (some? prior-manifest))))

(defn should-write-manifest?
  [opts]
  (and (not (:scan opts))
       (not (:update-manifest opts))
       (nil? (:lines opts))))

(defn suites-label
  [suites]
  (report-output/suites-label suites))

(defn ordered-suites
  [suites]
  (vec (filter (set suites) suite-order)))

(defn select-mutation-sites
  [sites opts effective-since-last-run changed-form-indices module-unchanged?]
  (cond
    (:lines opts)
    (source/filter-by-lines sites (:lines opts))

    effective-since-last-run
    (if module-unchanged?
      []
      (source/filter-by-form-indices sites changed-form-indices))

    :else
    sites))

(defn count-changed-sites
  [all-sites prior-manifest module-unchanged? changed-form-indices]
  (cond
    (nil? prior-manifest) (count all-sites)
    module-unchanged? 0
    :else (count (source/filter-by-form-indices all-sites changed-form-indices))))

(defn summarize-results
  [results]
  (let [executed (count results)
        killed (count (filter #(= :killed (:result %)) results))
        survived (count (filter #(= :survived (:result %)) results))
        pct (if (zero? executed)
              0.0
              (* 100.0 (/ killed (double executed))))]
    {:executed-sites executed
     :killed killed
     :survived survived
     :kill-pct pct}))

(defn- baseline-suite!
  [suite timeout-factor]
  (let [result (runner/run-command (clean-suite-command suite) {})]
    (when-not (runner/success? result)
      (throw
       (ex-info
        (str "Baseline failed for suite " (name suite) ".")
        {:suite suite
         :output (:output result)})))
    {:elapsed-ms (:elapsed-ms result)
     :timeout-ms (* timeout-factor (:elapsed-ms result))}))

(defn baseline-suites!
  [suites timeout-factor]
  (into {}
        (map (fn [suite]
               [suite (baseline-suite! suite timeout-factor)]))
        suites))

(defn run-suite!
  [suite timeout-ms]
  (runner/run-command (suite-command suite) {:timeout-ms timeout-ms}))

(defn- evaluate-mutant!
  [module-path original-content site baseline]
  (spit module-path (source/mutate-source-text original-content site))
  (try
    (loop [remaining (:required-suites site)
           suite-results []]
      (if-let [suite (first remaining)]
        (let [{:keys [timeout-ms]} (get baseline suite)
              result (run-suite! suite timeout-ms)
              suite-result {:suite suite
                            :exit (:exit result)
                            :timeout? (:timeout? result)
                            :elapsed-ms (:elapsed-ms result)
                            :output (:output result)}]
          (if (runner/success? result)
            (recur (rest remaining) (conj suite-results suite-result))
            {:site site
             :result :killed
             :timeout? (:timeout? result)
             :suite-results (conj suite-results suite-result)}))
        {:site site
         :result :survived
         :timeout? false
         :suite-results suite-results}))
    (finally
      (spit module-path original-content))))

(defn- restore-compiled-suites!
  [suites]
  (when (some #{:test} suites)
    (prepare-test-runner!))
  (doseq [suite suites]
    (let [result (runner/run-command (clean-suite-compile-command suite) {})]
      (when-not (runner/success? result)
        (throw
         (ex-info
          (str "Failed to restore compiled artifacts for suite " (name suite) ".")
          {:suite suite
           :output (:output result)}))))))

(defn- print-progress!
  [idx total {:keys [site result timeout?]}]
  (println
   (format "[%3d/%d] %-8s L%-4d %-12s %s"
           (inc idx)
           total
           (cond
             timeout? "TIMEOUT"
             (= :killed result) "KILLED"
             :else "SURVIVED")
           (or (:line site) 0)
           (suites-label (:required-suites site))
           (:description site))))

(defn run-mutations!
  [module-path original-content covered-sites baseline]
  (mapv (fn [idx site]
          (let [result (evaluate-mutant! module-path original-content site baseline)]
            (print-progress! idx (count covered-sites) result)
            result))
        (range)
        covered-sites))

(defn- maybe-load-coverage
  [root coverage-file]
  (coverage/load-optional-records root coverage-file))

(defn- required-suites
  [covered-sites]
  (ordered-suites (distinct (mapcat :required-suites covered-sites))))

(defn- manifest-summary
  [opts summary]
  {:mode (cond
           (:update-manifest opts) :update-manifest
           (:scan opts) :scan
           :else :run)
   :suite (name (:suite opts))
   :selected-sites (:selected-sites summary)
   :covered-sites (:covered-sites summary)
   :uncovered-sites (:uncovered-sites summary)
   :killed (:killed summary)
   :survived (:survived summary)
   :executed-sites (:executed-sites summary)
   :kill-pct (:kill-pct summary)
   :recorded-at (:recorded-at summary)})

(defn- build-summary
  [opts module manifest-path prior-manifest total-sites selected-sites changed-sites coverage-available? covered-sites uncovered-sites]
  {:mode (cond
           (:update-manifest opts) :update-manifest
           (:scan opts) :scan
           :else :run)
   :module module
   :manifest-path manifest-path
   :artifact-path nil
   :suite (:suite opts)
   :prior-manifest? (some? prior-manifest)
   :coverage-file (:coverage-file opts)
   :coverage-available? coverage-available?
   :total-sites total-sites
   :selected-sites (count selected-sites)
   :changed-sites changed-sites
   :covered-sites (count covered-sites)
   :uncovered-sites (count uncovered-sites)
   :recorded-at (fs/now-stamp)})

(defn- update-manifest-report
  [root opts module forms prior-manifest]
  (let [timestamp (fs/now-stamp)
        path (manifest/write-manifest! root module
                                       (manifest/build-manifest module
                                                                forms
                                                                timestamp
                                                                (:last-run prior-manifest)))
        report {:summary {:mode :update-manifest
                          :module module
                          :manifest-path path
                          :artifact-path nil
                          :suite (:suite opts)
                          :prior-manifest? (some? prior-manifest)
                          :coverage-file (:coverage-file opts)
                          :coverage-available? false
                          :total-sites 0
                          :selected-sites 0
                          :changed-sites 0
                          :covered-sites 0
                          :uncovered-sites 0
                          :recorded-at timestamp}
                :results []
                :uncovered-sites []}]
    (report-output/save-report! root module report)))

(defn execute-command
  [opts]
  (let [root (fs/repo-root)
        restored-backups (fs/restore-stale-backups! root)
        _ (doseq [module restored-backups]
            (println (str "Restored stale backup for " module ".")))
        module (fs/require-module-path root (:module opts))
        module-path (str (java.io.File. root module))
        manifest-path (fs/manifest-path root module)
        original-content (slurp module-path)
        forms (source/read-source-forms original-content)
        prior-manifest (manifest/load-manifest root module)
        effective-since-last-run (effective-since-last-run? opts prior-manifest)
        {:keys [module-unchanged? changed-form-indices]}
        (manifest/changed-form-indices forms prior-manifest)
        all-sites (source/discover-all-mutations forms)
        selected-sites (select-mutation-sites all-sites
                                              opts
                                              effective-since-last-run
                                              changed-form-indices
                                              module-unchanged?)
        changed-sites (count-changed-sites all-sites
                                           prior-manifest
                                           module-unchanged?
                                           changed-form-indices)]
    (cond
      (:update-manifest opts)
      (update-manifest-report root opts module forms prior-manifest)

      (:scan opts)
      (let [records (maybe-load-coverage root (:coverage-file opts))
            coverage-available? (some? records)
            line-build-map (when records (coverage/line-builds records))
            [covered-sites uncovered-sites]
            (if coverage-available?
              (coverage/partition-sites module selected-sites line-build-map (:suite opts))
              [[] []])
            report {:summary (build-summary opts
                                            module
                                            manifest-path
                                            prior-manifest
                                            (count all-sites)
                                            selected-sites
                                            changed-sites
                                            coverage-available?
                                            covered-sites
                                            uncovered-sites)
                    :results []
                    :uncovered-sites uncovered-sites}]
        (report-output/save-report! root module report))

      :else
      (let [records (coverage/load-records root (:coverage-file opts))
            line-build-map (coverage/line-builds records)
            [covered-sites uncovered-sites]
            (coverage/partition-sites module selected-sites line-build-map (:suite opts))
            summary-base (build-summary opts
                                        module
                                        manifest-path
                                        prior-manifest
                                        (count all-sites)
                                        selected-sites
                                        changed-sites
                                        true
                                        covered-sites
                                        uncovered-sites)
            suites (required-suites covered-sites)]
        (if (empty? covered-sites)
          (let [summary (merge summary-base
                               {:baseline {}
                                :executed-sites 0
                                :killed 0
                                :survived 0
                                :kill-pct 0.0})
                timestamp (:recorded-at summary)
                _ (when (should-write-manifest? opts)
                    (manifest/write-manifest! root
                                              module
                                              (manifest/build-manifest module
                                                                       forms
                                                                       timestamp
                                                                       (manifest-summary opts summary))))
                report {:summary summary
                        :results []
                        :uncovered-sites uncovered-sites}]
            (report-output/save-report! root module report))
          (do
            (when (some #{:test} suites)
              (prepare-test-runner!))
            (let [backup-path (fs/backup-path root module)]
              (spit backup-path original-content)
              (try
                (let [baseline (baseline-suites! suites (:timeout-factor opts))
                      results (run-mutations! module-path original-content covered-sites baseline)
                      result-summary (summarize-results results)
                      summary (merge summary-base
                                     {:baseline baseline}
                                     result-summary)
                      timestamp (:recorded-at summary)
                      _ (when (should-write-manifest? opts)
                          (manifest/write-manifest! root
                                                    module
                                                    (manifest/build-manifest module
                                                                             forms
                                                                             timestamp
                                                                             (manifest-summary opts summary))))
                      report {:summary summary
                              :results results
                              :uncovered-sites uncovered-sites}]
                  (report-output/save-report! root module report))
                (finally
                  (spit module-path original-content)
                  (restore-compiled-suites! suites)
                  (fs/delete-file! backup-path))))))))))
