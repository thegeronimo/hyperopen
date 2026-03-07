#!/usr/bin/env bb

(ns dev.crap-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [tools.crap.analyzer :as analyzer]
            [tools.crap.cli-options :as cli]
            [tools.crap.complexity :as complexity]
            [tools.crap.coverage :as coverage]
            [tools.crap.report-output :as report-output]))

(def sample-source
  (str "(ns sample.core)\n\n"
       "(defn simple\n"
       "  []\n"
       "  :ok)\n\n"
       "(defn branching\n"
       "  [x y]\n"
       "  (if x\n"
       "    (when y\n"
       "      :both)\n"
       "    (cond\n"
       "      (= y 1) :one\n"
       "      :else :other)))\n\n"
       "(def handler\n"
       "  (fn [items]\n"
       "    (doseq [item items\n"
       "            :when (:ok item)]\n"
       "      (when item\n"
       "        item))))\n\n"
       "(defn overloaded\n"
       "  ([] :zero)\n"
       "  ([x]\n"
       "   (if x\n"
       "     :one\n"
       "     :none)))\n\n"
       "(defmulti classify identity)\n\n"
       "(defmethod classify :num\n"
       "  [x]\n"
       "  (case x\n"
       "    1 :one\n"
       "    2 :two\n"
       "    :many))\n"))

(def sample-lcov
  (str "TN:\n"
       "SF:.shadow-cljs/builds/test/dev/out/cljs-runtime/sample/core.cljs\n"
       "FN:3,sample$core$simple\n"
       "FN:7,sample$core$branching\n"
       "FN:15,sample$core$handler\n"
       "FN:21,sample$core$overloaded\n"
       "FN:29,sample$core$classify\n"
       "FNDA:1,sample$core$simple\n"
       "FNDA:0,sample$core$branching\n"
       "FNDA:1,sample$core$handler\n"
       "FNDA:1,sample$core$overloaded\n"
       "FNDA:1,sample$core$classify\n"
       "DA:3,1\n"
       "DA:4,1\n"
       "DA:5,1\n"
       "DA:7,0\n"
       "DA:8,0\n"
       "DA:9,0\n"
       "DA:10,0\n"
       "DA:11,0\n"
       "DA:12,0\n"
       "DA:13,0\n"
       "DA:15,1\n"
       "DA:16,1\n"
       "DA:17,0\n"
       "DA:18,0\n"
       "DA:21,1\n"
       "DA:22,1\n"
       "DA:23,1\n"
       "DA:24,1\n"
       "DA:29,1\n"
       "DA:30,1\n"
       "DA:31,1\n"
       "DA:32,1\n"
       "LF:22\n"
       "LH:13\n"
       "end_of_record\n"
       "TN:\n"
       "SF:.shadow-cljs/builds/ws-test/dev/out/cljs-runtime/sample/core.cljs\n"
       "FN:7,sample$core$branching\n"
       "FNDA:1,sample$core$branching\n"
       "DA:7,1\n"
       "DA:8,1\n"
       "DA:9,1\n"
       "DA:10,1\n"
       "DA:11,0\n"
       "DA:12,1\n"
       "DA:13,1\n"
       "LF:7\n"
       "LH:6\n"
       "end_of_record\n"))

(defn delete-recursive!
  [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (.delete child))))

(defn with-temp-root
  [f]
  (let [tmp-path (java.nio.file.Files/createTempDirectory "crap-tool" (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile tmp-path)]
    (try
      (f (.getCanonicalPath root))
      (finally
        (delete-recursive! root)))))

(defn write-file!
  [root relative-path text]
  (let [file (io/file root relative-path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file text)))

(defn approx=
  [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 0.000001))

(deftest crap-score-matches-anchor-examples
  (is (approx= 1.0 (analyzer/crap-score 1 1.0)))
  (is (approx= 72.0 (analyzer/crap-score 8 0.0)))
  (is (approx= 30.0 (analyzer/crap-score 12 0.5))))

(deftest complexity-analyzer-counts-source-level-branches
  (with-temp-root
    (fn [root]
      (write-file! root "src/sample/core.cljs" sample-source)
      (let [records (complexity/analyze-file root (str (io/file root "src/sample/core.cljs")))
            by-name (into {} (map (juxt :display-name identity) records))]
        (is (= 5 (count records)))
        (is (= 1 (:complexity (get by-name "sample.core/simple"))))
        (is (= 4 (:complexity (get by-name "sample.core/branching"))))
        (is (= 4 (:complexity (get by-name "sample.core/handler"))))
        (is (= 3 (:complexity (get by-name "sample.core/overloaded"))))
        (is (= 3 (:complexity (get by-name "sample.core/classify[:num]"))))))))

(deftest lcov-reader-merges-test-and-ws-test-without-double-counting-lines
  (with-temp-root
    (fn [root]
      (write-file! root "src/sample/core.cljs" sample-source)
      (write-file! root "coverage/lcov.info" sample-lcov)
      (let [records (coverage/read-lcov root "coverage/lcov.info")
            merged (coverage/merge-file-coverage records {:build :merged})
            test-only (coverage/merge-file-coverage records {:build :test})]
        (is (= 2 (count records)))
        (is (= {7 1, 8 1, 9 1, 10 1, 11 0, 12 1, 13 1}
               (select-keys (get-in merged ["src/sample/core.cljs" :lines]) [7 8 9 10 11 12 13])))
        (is (approx= 0.0
                     (coverage/function-coverage test-only {:file "src/sample/core.cljs"
                                                            :line 7
                                                            :end-line 13})))
        (is (approx= (/ 6.0 7.0)
                     (coverage/function-coverage merged {:file "src/sample/core.cljs"
                                                         :line 7
                                                         :end-line 13})))))))

(deftest analyzer-builds-sorted-report-and-json-output
  (with-temp-root
    (fn [root]
      (write-file! root "src/sample/core.cljs" sample-source)
      (write-file! root "coverage/lcov.info" sample-lcov)
      (let [report (analyzer/build-report {:root root
                                           :scope "src"
                                           :module nil
                                           :coverage-file "coverage/lcov.info"
                                           :build :merged
                                           :top-functions 3
                                           :top-modules 2
                                           :threshold 30.0})
            json-output (with-out-str (report-output/print-json report))
            parsed-json (json/parse-string json-output true)]
        (is (= 5 (get-in report [:summary :functions-scanned])))
        (is (= "sample.core/handler" (get-in report [:functions 0 :display-name])))
        (is (= "src/sample/core.cljs" (get-in report [:modules 0 :file])))
        (is (= 3 (count (:functions report))))
        (is (string? (get-in parsed-json [:functions 0 :display-name])))))))

(deftest cli-parse-args-validates-supported-values
  (let [opts (cli/parse-args ["--module" "src/sample/core.cljs"
                              "--format" "json"
                              "--build" "ws-test"
                              "--top-functions" "5"
                              "--top-modules" "2"
                              "--threshold" "12.5"])]
    (is (= "src" (:scope opts)))
    (is (= "src/sample/core.cljs" (:module opts)))
    (is (= "json" (:format opts)))
    (is (= :ws-test (:build opts)))
    (is (= 5 (:top-functions opts)))
    (is (= 2 (:top-modules opts)))
    (is (= 12.5 (:threshold opts))))
  (is (thrown? Exception
               (cli/parse-args ["--format" "yaml"]))))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (run-tests 'dev.crap-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
