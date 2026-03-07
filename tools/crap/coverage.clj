(ns tools.crap.coverage
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tools.crap.filesystem :as fs]))

(def build-path-pattern
  #"\.shadow-cljs/builds/([^/]+)/dev/out/cljs-runtime/(.+)$")

(defn parse-int
  [value]
  (Integer/parseInt (str value)))

(defn detect-build
  [source-file]
  (some->> source-file
           (re-find build-path-pattern)
           second
           keyword))

(defn logical-path
  [source-file]
  (if-let [[_ _ logical] (re-find build-path-pattern source-file)]
    logical
    source-file))

(defn parse-fn-entry
  [line]
  (when-let [[_ row name] (re-matches #"FN:(\d+),(.*)" line)]
    {:line (parse-int row)
     :name name}))

(defn parse-fnda-entry
  [line]
  (when-let [[_ hits name] (re-matches #"FNDA:(\d+),(.*)" line)]
    {:hits (parse-int hits)
     :name name}))

(defn parse-da-entry
  [line]
  (when-let [[_ row hits] (re-matches #"DA:(\d+),(\d+)(?:,.*)?$" line)]
    {:line (parse-int row)
     :hits (parse-int hits)}))

(defn add-line-hit
  [acc {:keys [line hits]}]
  (update acc :lines assoc line hits))

(defn add-function-start
  [acc {:keys [line name]}]
  (update acc :function-starts assoc name line))

(defn add-function-hit
  [acc {:keys [hits name]}]
  (update acc :function-hits assoc name hits))

(defn finalize-record
  [root record]
  (when-let [source-file (:source-file record)]
    (let [logical (logical-path source-file)
          function-lines (reduce-kv (fn [acc name line]
                                      (assoc acc line (get-in record [:function-hits name] 0)))
                                    {}
                                    (:function-starts record))]
      {:source-file source-file
       :logical-path logical
       :build (detect-build source-file)
       :file (fs/resolve-source-path root logical)
       :lines (:lines record)
       :function-lines function-lines})))

(defn parse-record
  [root chunk]
  (let [lines (str/split-lines chunk)
        record (reduce (fn [acc line]
                         (cond
                           (str/starts-with? line "SF:")
                           (assoc acc :source-file (subs line 3))

                           (parse-fn-entry line)
                           (add-function-start acc (parse-fn-entry line))

                           (parse-fnda-entry line)
                           (add-function-hit acc (parse-fnda-entry line))

                           (parse-da-entry line)
                           (add-line-hit acc (parse-da-entry line))

                           :else acc))
                       {:lines {}
                        :function-starts {}
                        :function-hits {}}
                       lines)]
    (finalize-record root record)))

(defn coverage-file-path
  [root coverage-file]
  (let [candidate (io/file coverage-file)]
    (if (.isAbsolute candidate)
      (.getPath candidate)
      (.getPath (io/file root coverage-file)))))

(defn read-lcov
  [root coverage-file]
  (let [path (coverage-file-path root coverage-file)
        file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info (str "Missing " coverage-file ". Run npm run coverage first.") {})))
    (let [chunks (str/split (slurp file) #"end_of_record\s*")]
      (->> chunks
           (keep #(parse-record root %))
           vec))))

(defn build-matches?
  [selected-build record]
  (or (= selected-build :merged)
      (= selected-build (:build record))))

(defn merge-counts
  [a b]
  (merge-with max a b))

(defn merge-file-coverage
  [records {:keys [build]}]
  (reduce (fn [acc {:keys [file lines function-lines] :as record}]
            (if (and file (build-matches? build record))
              (update acc
                      file
                      (fn [existing]
                        {:lines (merge-counts (:lines existing {}) lines)
                         :function-lines (merge-counts (:function-lines existing {}) function-lines)}))
              acc))
          {}
          records))

(defn relevant-line-hits
  [line-hits start-line end-line]
  (->> line-hits
       (filter (fn [[line _]]
                 (<= start-line line end-line)))))

(defn function-coverage
  [coverage-by-file {:keys [file line end-line]}]
  (let [{:keys [lines function-lines]} (get coverage-by-file file)
        relevant (relevant-line-hits lines line end-line)
        total (count relevant)
        covered (count (filter (comp pos? val) relevant))]
    (cond
      (pos? total) (/ covered (double total))
      (pos? (get function-lines line 0)) 1.0
      :else 0.0)))
