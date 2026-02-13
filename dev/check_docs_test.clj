#!/usr/bin/env bb

(ns dev.check-docs-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [dev.check-docs :as docs]))

(def test-today
  (java.time.LocalDate/of 2026 2 13))

(defn test-config
  []
  {:required-files ["AGENTS.md" "docs/product-specs/index.md"]
   :governed-explicit ["AGENTS.md"]
   :governed-dirs ["docs/product-specs"]
   :index-rules [{:index "docs/product-specs/index.md" :dir "docs/product-specs"}]
   :agents-required-links ["docs/product-specs/index.md"]
   :today test-today})

(defn delete-recursive!
  [file]
  (when (.exists file)
    (doseq [child (reverse (file-seq file))]
      (.delete child))))

(defn with-temp-repo
  [f]
  (let [tmp-path (java.nio.file.Files/createTempDirectory "docs-check" (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile tmp-path)]
    (try
      (f (.getCanonicalPath root))
      (finally
        (delete-recursive! root)))))

(defn write-file!
  [root rel-path text]
  (let [f (io/file root rel-path)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f text)))

(defn with-front-matter
  [meta-lines body]
  (str "---\n"
       (str/join "\n" meta-lines)
       "\n---\n\n"
       body))

(def default-meta
  ["owner: platform"
   "status: canonical"
   "last_reviewed: 2026-02-13"
   "review_cycle_days: 90"
   "source_of_truth: true"])

(def spec-meta
  ["owner: product"
   "status: supporting"
   "last_reviewed: 2026-02-13"
   "review_cycle_days: 90"
   "source_of_truth: true"])

(defn baseline-files!
  [root]
  (write-file! root
               "AGENTS.md"
               (with-front-matter default-meta
                                  "# Agents\n\n- [Specs](/hyperopen/docs/product-specs/index.md)\n"))
  (write-file! root
               "docs/product-specs/index.md"
               (with-front-matter spec-meta
                                  "# Specs\n\n- [Spec A](/hyperopen/docs/product-specs/spec-a.md)\n"))
  (write-file! root
               "docs/product-specs/spec-a.md"
               (with-front-matter spec-meta
                                  "# Spec A\n\ncontent\n")))

(deftest passing-repo-has-no-errors
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (is (empty? (docs/check-repo root (test-config)))))))

(deftest missing-front-matter-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root "docs/product-specs/spec-a.md" "# Missing front matter\n")
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :front-matter-parse))))))

(deftest stale-doc-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "AGENTS.md"
                   (with-front-matter
                     ["owner: platform"
                      "status: canonical"
                      "last_reviewed: 2025-01-01"
                      "review_cycle_days: 30"
                      "source_of_truth: true"]
                     "# Agents\n\n- [Specs](/hyperopen/docs/product-specs/index.md)\n"))
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :stale-doc))))))

(deftest broken-link-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "AGENTS.md"
                   (with-front-matter default-meta
                                      "# Agents\n\n- [Specs](/hyperopen/docs/product-specs/index.md)\n- [Broken](/hyperopen/docs/missing.md)\n"))
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :broken-link))))))

(deftest machine-specific-path-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/product-specs/spec-a.md"
                   (with-front-matter spec-meta
                                      "# Spec A\n\nPath: `/Users/example/work/repo/file.md`\n"))
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :machine-specific-path))))))

(deftest missing-index-coverage-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/product-specs/spec-b.md"
                   (with-front-matter spec-meta "# Spec B\n"))
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :missing-index-link))))))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (run-tests 'dev.check-docs-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
