#!/usr/bin/env bb

(ns dev.check-delimiters
  (:require [dev.delimiter-preflight :as preflight]))

(defn- parse-args
  [args]
  (let [args (vec args)]
    {:changed? (some #{"--changed"} args)
     :paths (vec (remove #{"--changed"} args))}))

(defn -main
  [& args]
  (let [report (preflight/run-preflight (parse-args args))]
    (preflight/print-report! report)
    (System/exit (:exit-code report))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
