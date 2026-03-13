#!/usr/bin/env bb

(ns dev.delimiter-preflight-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [dev.delimiter-preflight :as preflight]))

(defn delete-recursive!
  [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (.delete child))))

(defn with-temp-root
  [f]
  (let [tmp-path (java.nio.file.Files/createTempDirectory "delimiter-preflight" (make-array java.nio.file.attribute.FileAttribute 0))
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

(deftest unmatched-closing-delimiter-reports-row-and-col
  (let [result (preflight/parse-source-text "(defn broken []))")]
    (is (false? (:ok? result)))
    (is (= "Unmatched delimiter: )" (:message result)))
    (is (= 1 (get-in result [:data :row])))
    (is (= 17 (get-in result [:data :col])))))

(deftest eof-reader-failure-reports-expected-and-opened-loc
  (let [result (preflight/parse-source-text "(defn broken []")]
    (is (false? (:ok? result)))
    (is (= ")" (get-in result [:data :edamame/expected-delimiter])))
    (is (= "(" (get-in result [:data :edamame/opened-delimiter])))
    (is (= {:row 1 :col 1}
           (get-in result [:data :edamame/opened-delimiter-loc])))))

(deftest valid-cljs-reader-forms-parse-cleanly
  (let [text (str "#?(:cljs (def once #js {:id #uuid \"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\"})\n"
                  "   :clj (def once {:id #inst \"2026-03-13T00:00:00.000-00:00\"}))\n"
                  "\"literal with ) ] } inside\"\n"
                  "; comment with ] ) }\n")]
    (is (= {:ok? true}
           (preflight/parse-source-text text)))))

(deftest delimiter-depths-ignore-strings-and-comments
  (let [text (str "(def text \"ignore ) ] }\" ; comment ) ] }\n"
                  "  [1 {:ok true}])")
        rows (preflight/delimiter-depths-by-line text)]
    (is (= [{:line 1
             :text "(def text \"ignore ) ] }\" ; comment ) ] }"
             :parens 1
             :brackets 0
             :braces 0}
            {:line 2
             :text "  [1 {:ok true}])"
             :parens 0
             :brackets 0
             :braces 0}]
           rows))))

(deftest delimiter-depths-stay-stable-across-multiline-mixed-delimiters
  (let [text (str "(def config\n"
                  "  [{:name \"one\"}\n"
                  "   {:items [1 2]}])")
        rows (preflight/delimiter-depths-by-line text)]
    (is (= [{:line 1
             :text "(def config"
             :parens 1
             :brackets 0
             :braces 0}
            {:line 2
             :text "  [{:name \"one\"}"
             :parens 1
             :brackets 1
             :braces 0}
            {:line 3
             :text "   {:items [1 2]}])"
             :parens 0
             :brackets 0
             :braces 0}]
           rows))))

(deftest explicit-paths-override-changed-mode
  (with-temp-root
    (fn [root]
      (write-file! root "src/app/core.cljs" "(ns app.core)\n(defn ok [] :ok)\n")
      (write-file! root "test/app/core_test.cljs" "(ns app.core-test)\n")
      (let [selection (preflight/resolve-candidate-files
                       {:root-path root
                        :changed? true
                        :paths ["src/app/core.cljs"]
                        :changed-paths-fn (fn [_] ["test/app/core_test.cljs"])})]
        (is (= :explicit (:mode selection)))
        (is (= [(preflight/canonical-path (io/file root "src/app/core.cljs"))]
               (:files selection)))
        (is (empty? (:errors selection)))))))

(deftest changed-mode-filters-to-existing-supported-files
  (with-temp-root
    (fn [root]
      (write-file! root "src/app/core.cljs" "(ns app.core)\n(defn ok [] :ok)\n")
      (write-file! root "notes.txt" "not source\n")
      (let [selection (preflight/resolve-candidate-files
                       {:root-path root
                        :changed? true
                        :changed-paths-fn (fn [_]
                                            ["src/app/core.cljs"
                                             "notes.txt"
                                             "missing.cljs"])})]
        (is (= :changed (:mode selection)))
        (is (= [(preflight/canonical-path (io/file root "src/app/core.cljs"))]
               (:files selection)))
        (is (empty? (:errors selection)))))))

(deftest no-candidate-files-in-changed-mode-skips-cleanly
  (with-temp-root
    (fn [root]
      (let [report (preflight/run-preflight
                    {:root-path root
                     :changed? true
                     :changed-paths-fn (constantly [])})]
        (is (true? (:skipped? report)))
        (is (= 0 (:exit-code report)))
        (is (empty? (:files report)))
        (is (empty? (:selection-errors report)))))))

(deftest no-candidate-files-in-default-mode-skips-cleanly
  (with-temp-root
    (fn [root]
      (let [report (preflight/run-preflight
                    {:root-path root
                     :default-files-fn (constantly [])})]
        (is (true? (:skipped? report)))
        (is (= 0 (:exit-code report)))
        (is (empty? (:files report)))))))

(deftest explicit-missing-path-fails-fast
  (with-temp-root
    (fn [root]
      (let [report (preflight/run-preflight
                    {:root-path root
                     :paths ["src/missing.cljs"]})]
        (is (= 1 (:exit-code report)))
        (is (= ["Explicit path not found: src/missing.cljs"]
               (:selection-errors report)))))))

(deftest run-preflight-fails-broken-file-with-context
  (with-temp-root
    (fn [root]
      (write-file! root
                   "src/app/broken.cljs"
                   (str "(ns app.broken)\n"
                        "(defn broken []\n"
                        "  [1 2)))\n"))
      (let [report (preflight/run-preflight
                    {:root-path root
                     :paths ["src/app/broken.cljs"]})
            failure (first (:failures report))]
        (is (= 1 (:exit-code report)))
        (is (= "src/app/broken.cljs" (:relative-path failure)))
        (is (= 3 (:row failure)))
        (is (= 7 (:col failure)))
        (is (= 3 (count (:context failure))))
        (is (= [1 2 3] (mapv :line (:context failure))))
        (is (true? (:highlight? (last (:context failure)))))))))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (run-tests 'dev.delimiter-preflight-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
