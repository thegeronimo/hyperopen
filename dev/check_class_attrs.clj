#!/usr/bin/env bb

(ns dev.check-class-attrs
  (:require [dev.hiccup-lint :as lint]))

(defn -main
  [& _args]
  (System/exit (lint/check-class-attrs!)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
