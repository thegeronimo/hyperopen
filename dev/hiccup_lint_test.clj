#!/usr/bin/env bb

(ns dev.hiccup-lint-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [dev.hiccup-lint :as lint]))

(deftest class-attrs-detects-space-separated-string-literals
  (let [text (str "[:div {:class [\"opacity-0 scale-y-95\" \"opacity-0\"]}]\n"
                  "[:div {:class :some-token}]")
        violations (lint/class-violations-in-text "sample.cljs" text)]
    (is (= [{:file-path "sample.cljs"
             :line 1
             :literal "opacity-0 scale-y-95"}]
           violations))))

(deftest class-attrs-ignores-comments-and-non-space-literals
  (let [text (str "; :class [\"opacity-0 scale-y-95\"]\n"
                  "[:div {:title \":class \\\"opacity-0 scale-y-95\\\"\""
                  "       :class [\"opacity-0\" class-token]}]\n")
        violations (lint/class-violations-in-text "sample.cljs" text)]
    (is (empty? violations))))

(deftest style-map-detects-string-keys-only-in-literal-style-maps
  (let [text (str "[:div {:style {\"--slider-progress\" \"10%\" :color \"red\"}}]\n"
                  "[:div {:style style-map-token}]\n"
                  "[:div {:style {:--order-size-slider-progress \"10%\"}}]\n")
        violations (lint/style-map-string-key-violations-in-text "sample.cljs" text)]
    (is (= [{:file-path "sample.cljs"
             :line 1
             :literal "--slider-progress"}]
           violations))))

(deftest style-map-ignores-comments-and-strings
  (let [text (str "; :style {\"--slider-progress\" \"10%\"}\n"
                  "[:div {:title \":style {\\\"--slider-progress\\\" \\\"10%\\\"}\""
                  "       :style {:color \"red\"}}]\n")
        violations (lint/style-map-string-key-violations-in-text "sample.cljs" text)]
    (is (empty? violations))))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (run-tests 'dev.hiccup-lint-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
