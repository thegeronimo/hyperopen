(ns tools.crap.cli-options)

(def default-top-functions 25)
(def default-top-modules 10)
(def default-threshold 30.0)

(defn usage
  []
  (str "Usage: bb tools/crap_report.clj [--scope <src|test|all> | --module <repo-relative-path>]"
       " [--coverage-file path] [--build <merged|test|ws-test>]"
       " [--top-functions N] [--top-modules N] [--threshold N]"
       " [--format <text|json>]\n"
       "Example: bb tools/crap_report.clj --scope src --top-functions 20\n"
       "Example: bb tools/crap_report.clj --module src/hyperopen/websocket/application/runtime_reducer.cljs --format json"))

(defn parse-int
  [value fallback]
  (try
    (Integer/parseInt (str value))
    (catch Throwable _
      fallback)))

(defn parse-double
  [value fallback]
  (try
    (Double/parseDouble (str value))
    (catch Throwable _
      fallback)))

(defn validate-option
  [value allowed label]
  (when-not (contains? allowed value)
    (throw (ex-info (str "Unsupported " label ": " value)
                    {:usage (usage)})))
  value)

(defn normalize-build
  [build]
  (keyword (validate-option build #{"merged" "test" "ws-test"} "build")))

(defn parse-args
  [args]
  (loop [remaining args
         opts {:scope "src"
               :module nil
               :coverage-file "coverage/lcov.info"
               :build :merged
               :top-functions default-top-functions
               :top-modules default-top-modules
               :threshold default-threshold
               :format "text"}]
    (if (empty? remaining)
      (do
        (validate-option (:scope opts) #{"src" "test" "all"} "scope")
        (validate-option (:format opts) #{"text" "json"} "format")
        opts)
      (let [[key value & tail] remaining]
        (cond
          (= key "--scope")
          (recur tail (assoc opts :scope value))

          (= key "--module")
          (recur tail (assoc opts :module value))

          (= key "--coverage-file")
          (recur tail (assoc opts :coverage-file value))

          (= key "--build")
          (recur tail (assoc opts :build (normalize-build value)))

          (= key "--top-functions")
          (recur tail (assoc opts :top-functions (parse-int value default-top-functions)))

          (= key "--top-modules")
          (recur tail (assoc opts :top-modules (parse-int value default-top-modules)))

          (= key "--threshold")
          (recur tail (assoc opts :threshold (parse-double value default-threshold)))

          (= key "--format")
          (recur tail (assoc opts :format value))

          (= key "--help")
          (throw (ex-info (usage) {:usage (usage)}))

          :else
          (throw (ex-info (str "Unknown argument: " key)
                          {:usage (usage)})))))))
