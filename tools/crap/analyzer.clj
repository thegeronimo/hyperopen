(ns tools.crap.analyzer
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tools.crap.complexity :as complexity]
            [tools.crap.coverage :as coverage]
            [tools.crap.filesystem :as fs]))

(defn parse-errors
  [root results]
  (->> results
       (filter :error)
       (map (fn [{:keys [file error]}]
              {:file (fs/relativize root file)
               :error error}))
       vec))

(defn source-records
  [results]
  (->> results
       (remove :error)
       (mapcat :records)
       vec))

(defn scope-directories
  [scope]
  (case scope
    "src" ["src"]
    "test" ["test"]
    "all" ["src" "test"]
    (throw (ex-info (str "Unsupported scope: " scope) {}))))

(defn module-file
  [root module]
  (or (fs/resolve-source-path root module)
      (throw (ex-info (str "Unknown module path: " module) {}))))

(defn source-files
  [root {:keys [scope module]}]
  (if module
    [(str (io/file root (module-file root module)))]
    (->> (scope-directories scope)
         (map #(str (io/file root %)))
         (mapcat fs/cljs-files-under)
         sort
         vec)))

(defn load-source-file
  [root file]
  (try
    {:file file
     :records (complexity/analyze-file root file)}
    (catch Throwable t
      {:file file
       :error (.getMessage t)})))

(defn crap-score
  [complexity coverage]
  (+ complexity
     (* complexity complexity (Math/pow (- 1.0 coverage) 3.0))))

(defn crapload
  [threshold score]
  (max 0.0 (- score threshold)))

(defn enrich-function
  [coverage-by-file threshold record]
  (let [coverage (coverage/function-coverage coverage-by-file record)
        score (crap-score (:complexity record) coverage)]
    (assoc record
           :coverage coverage
           :crap score
           :crappy (> score threshold))))

(defn sort-functions
  [records]
  (sort-by (juxt (comp - :crap)
                 (comp - :complexity)
                 :file
                 :line)
           records))

(defn safe-average
  [values]
  (if (seq values)
    (/ (reduce + 0.0 values) (double (count values)))
    0.0))

(defn module-summary
  [threshold [file records]]
  (let [scores (map :crap records)
        coverages (map :coverage records)
        complexities (map :complexity records)]
    {:file file
     :function-count (count records)
     :crappy-function-count (count (filter :crappy records))
     :covered-function-count (count (filter #(pos? (:coverage %)) records))
     :avg-coverage (safe-average coverages)
     :avg-complexity (safe-average complexities)
     :avg-crap (safe-average scores)
     :max-crap (reduce max 0.0 scores)
     :crapload (reduce + 0.0 (map #(crapload threshold %) scores))}))

(defn sort-modules
  [records]
  (sort-by (juxt (comp - :crapload)
                 (comp - :max-crap)
                 (comp - :crappy-function-count)
                 :file)
           records))

(defn build-report
  [{:keys [root
           scope
           module
           coverage-file
           build
           top-functions
           top-modules
           threshold]}]
  (let [files (source-files root {:scope scope :module module})
        source-results (map #(load-source-file root %) files)
        parse-errors (parse-errors root source-results)
        source-records (source-records source-results)
        lcov-records (coverage/read-lcov root coverage-file)
        coverage-by-file (coverage/merge-file-coverage lcov-records {:build build})
        enriched-records (->> source-records
                              (map #(enrich-function coverage-by-file threshold %))
                              sort-functions
                              vec)
        modules (->> enriched-records
                     (group-by :file)
                     (map #(module-summary threshold %))
                     sort-modules
                     vec)
        summary {:scope scope
                 :module module
                 :build (name build)
                 :coverage-file coverage-file
                 :scanned-files (count files)
                 :modules-scanned (count modules)
                 :functions-scanned (count source-records)
                 :crappy-functions (count (filter :crappy enriched-records))
                 :parse-errors (count parse-errors)
                 :threshold threshold
                 :project-crapload (reduce + 0.0 (map #(crapload threshold (:crap %)) enriched-records))}]
    {:summary summary
     :functions (->> enriched-records (take top-functions) vec)
     :modules (->> modules (take top-modules) vec)
     :parse-errors parse-errors}))
