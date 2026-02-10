#!/usr/bin/env bb

(ns dev.check-style-attr-keys
  (:require [dev.hiccup-lint :as lint]))

(defn -main
  [& _args]
  (System/exit (lint/check-style-map-string-keys!)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
