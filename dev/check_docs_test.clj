#!/usr/bin/env bb

(ns dev.check-docs-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [dev.check-docs :as docs]))

(def test-today
  (java.time.LocalDate/of 2026 2 13))

(defn test-config
  ([] (test-config {}))
  ([overrides]
   (merge {:required-files ["AGENTS.md" "docs/product-specs/index.md"]
           :governed-explicit ["AGENTS.md"]
           :governed-dirs ["docs/product-specs"]
           :index-rules [{:index "docs/product-specs/index.md" :dir "docs/product-specs"}]
           :agents-required-links ["docs/product-specs/index.md"]
           :active-exec-plan-dir "docs/exec-plans/active"
           :work-tracking-policy-files []
           :today test-today}
          overrides)))

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

(def durable-context-text
  "## Context References\n\nPublic refs:\n- Direct maintainer request captured in this ExecPlan.\n\nRepo artifacts:\n- /hyperopen/docs/exec-plans/active/2026-03-09-sample.md\n\nLocal scratch refs (non-authoritative):\n- None.\n\n")

(defn active-plan
  [{:keys [context-text unchecked-count checked-count]}]
  (str "# Active Plan\n\n"
       "## Purpose / Big Picture\n\n"
       "Explain the user-visible purpose.\n\n"
       (or context-text durable-context-text)
       "## Progress\n\n"
       (apply str (repeat checked-count "- [x] Finished step.\n"))
       (apply str (repeat unchecked-count "- [ ] Remaining step.\n"))
       "\n## Surprises & Discoveries\n\n"
       "- Observation: None yet.\n  Evidence: Initial plan.\n\n"
       "## Decision Log\n\n"
       "- Decision: Keep scope narrow.\n  Rationale: Test fixture.\n  Date/Author: 2026-02-13 / Test\n\n"
       "## Outcomes & Retrospective\n\n"
       "Not complete yet.\n\n"
       "## Validation and Acceptance\n\n"
       "Run the focused validation command and expect it to pass.\n"))

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

(deftest stale-doc-is-advisory-not-blocking
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
      (let [findings (docs/check-repo root (test-config))]
        (is (contains? (set (map :code findings)) :stale-doc))
        (is (seq (docs/advisory-findings findings)))
        (is (empty? (docs/blocking-errors findings)))))))

(deftest broken-link-is-still-blocking
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "AGENTS.md"
                   (with-front-matter default-meta
                                      "# Agents\n\n- [Specs](/hyperopen/docs/product-specs/index.md)\n- [Broken](/hyperopen/docs/missing.md)\n"))
      (let [findings (docs/check-repo root (test-config))]
        (is (seq (docs/blocking-errors findings)))
        (is (contains? (set (map :code (docs/blocking-errors findings))) :broken-link))))))

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

(deftest legacy-prd-ppd-readmes-are-not-required
  (let [required (set docs/default-required-files)]
    (is (not (contains? required "PRDs/README.md")))
    (is (not (contains? required "PPDs/README.md")))))

(deftest active-exec-plan-with-durable-context-and-unchecked-progress-passes
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/exec-plans/active/2026-03-09-sample.md"
                   (active-plan {:checked-count 1
                                 :unchecked-count 1}))
      (is (empty? (docs/check-repo root (test-config)))))))

(deftest active-exec-plan-without-durable-context-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/exec-plans/active/2026-03-09-sample.md"
                   (active-plan {:context-text ""
                                 :checked-count 0
                                 :unchecked-count 1}))
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :active-exec-plan-missing-context-reference))))))

(deftest active-exec-plan-local-bd-ref-alone-is-not-durable-context
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/exec-plans/active/2026-03-09-sample.md"
                   (active-plan {:context-text "## Context References\n\nLocal scratch refs (non-authoritative):\n- bd: `hyperopen-123`, authoritative: false.\n\n"
                                 :checked-count 1
                                 :unchecked-count 1}))
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :active-exec-plan-missing-context-reference))))))

(deftest active-exec-plan-without-unchecked-progress-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/exec-plans/active/2026-03-09-sample.md"
                   (active-plan {:checked-count 2
                                 :unchecked-count 0}))
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :active-exec-plan-no-unchecked-progress))))))

(deftest active-exec-plan-missing-required-section-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/exec-plans/active/2026-03-09-sample.md"
                   (str "# Active Plan\n\n"
                        durable-context-text
                        "## Progress\n\n- [ ] Remaining step.\n"))
      (let [codes (set (map :code (docs/check-repo root (test-config))))]
        (is (contains? codes :active-exec-plan-missing-purpose))
        (is (contains? codes :active-exec-plan-missing-decision-log))
        (is (contains? codes :active-exec-plan-missing-validation))))))

(deftest stale-canonical-bd-requirement-is-reported
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/WORK_TRACKING.md"
                   (str "Use `b" "d` for all backlog, bug, feature, and follow-up issue tracking.\n"))
      (let [codes (set (map :code (docs/check-repo root
                                                   (test-config {:work-tracking-policy-files ["docs/WORK_TRACKING.md"]}))))]
        (is (contains? codes :stale-bd-requirement))))))

(deftest optional-non-authoritative-bd-reference-is-allowed
  (with-temp-repo
    (fn [root]
      (baseline-files! root)
      (write-file! root
                   "docs/WORK_TRACKING.md"
                   "Beads may be used as optional local scratch. Local refs may include bd: hyperopen-123 when authoritative: false.\n")
      (is (empty? (docs/check-repo root
                                   (test-config {:work-tracking-policy-files ["docs/WORK_TRACKING.md"]})))))))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (run-tests 'dev.check-docs-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
