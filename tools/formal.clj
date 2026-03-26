#!/usr/bin/env bb

(ns tools.formal
  (:require [tools.formal.core :as core]))

(defn -main
  [& args]
  (try
    (core/run! args)
    (catch Throwable t
      (binding [*out* *err*]
        (println (.getMessage t))
        (when-let [usage-text (:usage (ex-data t))]
          (println usage-text)))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
