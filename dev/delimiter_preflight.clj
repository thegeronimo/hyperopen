(ns dev.delimiter-preflight
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [edamame.core :as edamame]))

(def root (.getCanonicalPath (io/file ".")))

(def ignored-dirs
  #{"node_modules" ".git" ".shadow-cljs" "out" "output" "tmp" ".cache"})

(def supported-extensions
  #{".clj" ".cljs" ".cljc" ".edn"})

(def default-scan-dirs
  ["src" "test" "dev" "portfolio" "tools" ".clj-kondo"])

(def default-scan-files
  ["deps.edn" "bb.edn" "shadow-cljs.edn" "command-phrases.edn"])

(def parse-opts
  {:all true
   :read-cond :allow
   :features #{:cljs}
   :readers {'js identity
             'inst identity
             'uuid identity}})

(defn canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn relative-path
  [root-path file-path]
  (let [root-file (io/file (canonical-path root-path))
        target-file (io/file (canonical-path file-path))]
    (-> (.. root-file toPath (relativize (.toPath target-file)))
        str
        (str/replace "\\" "/"))))

(defn- supported-file-name?
  [file-name]
  (some #(str/ends-with? file-name %) supported-extensions))

(defn supported-file?
  [candidate]
  (let [file (io/file candidate)]
    (and (.isFile file)
         (supported-file-name? (.getName file)))))

(defn- walk-supported-files
  [candidate]
  (let [file (io/file candidate)]
    (cond
      (not (.exists file))
      []

      (.isFile file)
      (if (supported-file? file)
        [(canonical-path file)]
        [])

      (.isDirectory file)
      (letfn [(walk [^java.io.File dir]
                (if (contains? ignored-dirs (.getName dir))
                  []
                  (->> (or (seq (.listFiles dir)) [])
                       (sort-by #(.getName ^java.io.File %))
                       (mapcat
                        (fn [^java.io.File entry]
                          (cond
                            (.isDirectory entry)
                            (walk entry)

                            (supported-file? entry)
                            [(canonical-path entry)]

                            :else
                            []))))))]
        (walk file))

      :else
      [])))

(defn source-lines
  [text]
  (let [lines (str/split text #"\r?\n" -1)]
    (if (and (> (count lines) 1)
             (str/ends-with? text "\n"))
      (vec (butlast lines))
      (vec lines))))

(defn delimiter-depths-by-line
  [text]
  (let [lines (source-lines text)]
    (second
     (reduce
      (fn [[state rows] [idx line]]
        (let [{:keys [parens brackets braces in-string? escaped?]}
              (loop [chars (seq line)
                     state state]
                (if-let [ch (first chars)]
                  (let [{:keys [parens brackets braces in-string? escaped?]} state]
                    (cond
                      in-string?
                      (cond
                        escaped?
                        (recur (next chars) (assoc state :escaped? false))

                        (= ch \\)
                        (recur (next chars) (assoc state :escaped? true))

                        (= ch \")
                        (recur (next chars) (assoc state :in-string? false))

                        :else
                        (recur (next chars) state))

                      (= ch \;)
                      state

                      (= ch \")
                      (recur (next chars) (assoc state :in-string? true :escaped? false))

                      (= ch \()
                      (recur (next chars) (update state :parens inc))

                      (= ch \))
                      (recur (next chars) (update state :parens dec))

                      (= ch \[)
                      (recur (next chars) (update state :brackets inc))

                      (= ch \])
                      (recur (next chars) (update state :brackets dec))

                      (= ch \{)
                      (recur (next chars) (update state :braces inc))

                      (= ch \})
                      (recur (next chars) (update state :braces dec))

                      :else
                      (recur (next chars) state)))
                  state))
              next-state {:parens parens
                          :brackets brackets
                          :braces braces
                          :in-string? in-string?
                          :escaped? escaped?}]
          [next-state
           (conj rows {:line (inc idx)
                       :text line
                       :parens parens
                       :brackets brackets
                       :braces braces})]))
      [{:parens 0
        :brackets 0
        :braces 0
        :in-string? false
        :escaped? false}
       []]
      (map-indexed vector lines)))))

(defn- line-context-window
  [text row]
  (let [line-count (count (source-lines text))
        focus-line (-> (or row 1)
                       (max 1)
                       (min (max 1 line-count)))
        start-line (max 1 (- focus-line 2))
        end-line (min line-count (+ focus-line 2))]
    (->> (delimiter-depths-by-line text)
         (drop (dec start-line))
         (take (inc (- end-line start-line)))
         (mapv #(assoc % :highlight? (= (:line %) focus-line))))))

(defn parse-source-text
  [text]
  (try
    (edamame/parse-string-all text parse-opts)
    {:ok? true}
    (catch Exception ex
      {:ok? false
       :message (ex-message ex)
       :data (ex-data ex)})))

(defn- parse-file
  [root-path file-path]
  (let [text (slurp file-path)
        result (parse-source-text text)]
    (if (:ok? result)
      {:ok? true
       :file-path file-path
       :relative-path (relative-path root-path file-path)}
      (let [data (:data result)
            row (:row data)
            opened-loc (:edamame/opened-delimiter-loc data)]
        {:ok? false
         :file-path file-path
         :relative-path (relative-path root-path file-path)
         :message (:message result)
         :row row
         :col (:col data)
         :expected-delimiter (:edamame/expected-delimiter data)
         :opened-delimiter (:edamame/opened-delimiter data)
         :opened-delimiter-loc opened-loc
         :context (line-context-window text row)}))))

(defn- shell-lines
  [root-path & args]
  (let [{:keys [exit out err]} (apply shell/sh (concat args [:dir root-path]))]
    (if (zero? exit)
      (->> (str/split-lines out)
           (remove str/blank?)
           vec)
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      {:exit exit
                       :stderr err
                       :args args})))))

(defn changed-relative-paths
  [root-path]
  (->> [(apply shell-lines root-path ["git" "diff" "--name-only" "--diff-filter=ACMRTUXB" "--cached" "--"])
        (apply shell-lines root-path ["git" "diff" "--name-only" "--diff-filter=ACMRTUXB" "--"])
        (apply shell-lines root-path ["git" "ls-files" "--others" "--exclude-standard"])]
       (apply concat)
       distinct
       sort
       vec))

(defn- resolve-supported-file
  [root-path candidate]
  (let [candidate-file (io/file candidate)
        resolved-file (if (.isAbsolute candidate-file)
                        candidate-file
                        (io/file root-path candidate))]
    (when (.exists resolved-file)
      (walk-supported-files resolved-file))))

(defn- explicit-candidate-files
  [root-path candidates]
  (reduce
   (fn [acc candidate]
     (let [matches (resolve-supported-file root-path candidate)
           absolute-file (let [file (io/file candidate)]
                           (if (.isAbsolute file) file (io/file root-path candidate)))]
       (cond
         (not (.exists absolute-file))
         (update acc :errors conj (str "Explicit path not found: " candidate))

         (seq matches)
         (update acc :files into matches)

         :else
         (update acc :errors conj
                 (str "Explicit path is not a supported Clojure/EDN file or directory: " candidate)))))
   {:files []
    :errors []}
   candidates))

(defn- default-candidate-files
  [root-path]
  (->> (concat default-scan-files default-scan-dirs)
       (mapcat #(walk-supported-files (io/file root-path %)))
       distinct
       sort
       vec))

(defn- relative-candidates->files
  [root-path candidates]
  (->> candidates
       (map #(io/file root-path %))
       (filter #(.exists ^java.io.File %))
       (mapcat walk-supported-files)
       distinct
       sort
       vec))

(defn resolve-candidate-files
  [{:keys [root-path paths changed? changed-paths-fn default-files-fn]
    :or {root-path root
         changed-paths-fn changed-relative-paths
         default-files-fn default-candidate-files}}]
  (cond
    (seq paths)
    (assoc (explicit-candidate-files root-path paths) :mode :explicit)

    changed?
    {:mode :changed
     :files (relative-candidates->files root-path (changed-paths-fn root-path))
     :errors []}

    :else
    {:mode :default
     :files (default-files-fn root-path)
     :errors []}))

(defn run-preflight
  [{:keys [root-path] :as opts}]
  (let [root-path (or root-path root)]
    (try
      (let [{:keys [mode files errors]} (resolve-candidate-files (assoc opts :root-path root-path))
            files (vec (distinct (sort files)))
            failures (->> files
                          (map #(parse-file root-path %))
                          (remove :ok?)
                          vec)
            selection-errors (vec errors)]
        {:mode mode
         :root-path root-path
         :files files
         :failures failures
         :selection-errors selection-errors
         :skipped? (and (empty? files) (empty? selection-errors))
         :exit-code (if (or (seq selection-errors) (seq failures)) 1 0)})
      (catch Exception ex
        {:mode (if (seq (:paths opts)) :explicit (if (:changed? opts) :changed :default))
         :root-path root-path
         :files []
         :failures []
         :selection-errors [(or (ex-message ex) "Delimiter preflight failed before parsing files.")]
         :skipped? false
         :exit-code 1}))))

(defn- format-context-line
  [{:keys [highlight? line parens brackets braces text]}]
  (format "%s %4d | ()=%d []=%d {}=%d | %s"
          (if highlight? ">" " ")
          line
          parens
          brackets
          braces
          text))

(defn print-report!
  [{:keys [files failures selection-errors skipped?]}]
  (cond
    (seq selection-errors)
    (binding [*out* *err*]
      (println "Delimiter preflight could not determine candidate files.")
      (doseq [message selection-errors]
        (println (str "- " message))))

    skipped?
    (println "Delimiter preflight skipped; no candidate Clojure files found.")

    (seq failures)
    (binding [*out* *err*]
      (println (format "Delimiter preflight failed in %d file(s)." (count failures)))
      (doseq [{:keys [relative-path message row col expected-delimiter opened-delimiter opened-delimiter-loc context]}
              failures]
        (println)
        (println (format "%s:%s:%s %s"
                         relative-path
                         (or row "?")
                         (or col "?")
                         message))
        (when (seq expected-delimiter)
          (println (format "  expected delimiter: %s" expected-delimiter)))
        (when-let [opened-row (:row opened-delimiter-loc)]
          (println (format "  opened delimiter: %s at %s:%s"
                           opened-delimiter
                           opened-row
                           (:col opened-delimiter-loc))))
        (println "  delimiter depth by line:")
        (doseq [entry context]
          (println (str "  " (format-context-line entry))))))

    :else
    (println (format "Delimiter preflight passed for %d file(s)." (count files)))))
