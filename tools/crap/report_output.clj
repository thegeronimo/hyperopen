(ns tools.crap.report-output
  (:require [cheshire.core :as json]))

(defn format-number
  [value]
  (format "%.2f" (double value)))

(defn print-report
  [{:keys [summary functions modules parse-errors]}]
  (println (str "scope=" (or (:module summary) (:scope summary))))
  (println (str "build=" (:build summary)))
  (println (str "coverage_file=" (:coverage-file summary)))
  (println (str "scanned_files=" (:scanned-files summary)))
  (println (str "modules_scanned=" (:modules-scanned summary)))
  (println (str "functions_scanned=" (:functions-scanned summary)))
  (println (str "crappy_functions=" (:crappy-functions summary)))
  (println (str "parse_errors=" (:parse-errors summary)))
  (println (str "threshold=" (format-number (:threshold summary))))
  (println (str "project_crapload=" (format-number (:project-crapload summary))))
  (when (seq parse-errors)
    (doseq [{:keys [file error]} parse-errors]
      (println (str "  parse_error file=" file " msg=" error))))
  (println "")
  (println "top_functions:")
  (doseq [{:keys [crap coverage complexity file display-name line]} functions]
    (println (str "  crap=" (format-number crap)
                  " coverage=" (format-number coverage)
                  " complexity=" complexity
                  " file=" file
                  " line=" line
                  " fn=" display-name)))
  (println "")
  (println "top_modules:")
  (doseq [{:keys [crapload max-crap function-count crappy-function-count avg-coverage file]} modules]
    (println (str "  crapload=" (format-number crapload)
                  " max_crap=" (format-number max-crap)
                  " functions=" function-count
                  " crappy_functions=" crappy-function-count
                  " avg_coverage=" (format-number avg-coverage)
                  " file=" file))))

(defn print-json
  [report]
  (println (json/generate-string report {:pretty true})))
