(ns tools.crap-report
  (:require [clojure.java.io :as io]
            [tools.crap.analyzer :as analyzer]
            [tools.crap.cli-options :as cli]
            [tools.crap.report-output :as report-output]))

(defn -main
  [& args]
  (try
    (let [opts (cli/parse-args args)
          report (analyzer/build-report (assoc opts :root (.getCanonicalPath (io/file "."))))]
      (case (:format opts)
        "json" (report-output/print-json report)
        (report-output/print-report report)))
    (catch Throwable t
      (binding [*out* *err*]
        (println (.getMessage t))
        (when-let [usage-text (:usage (ex-data t))]
          (println usage-text)))
      (System/exit 1))))

(apply -main *command-line-args*)
