(ns dev.hiccup-lint
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def root (.getCanonicalPath (io/file ".")))
(def src-dir (io/file root "src"))

(def ignored-dirs
  #{"node_modules" ".git" ".shadow-cljs" "out" "output" "tmp"})

(def keyword-boundary-chars
  #{\, \( \) \[ \] \{ \} \" \;})

(def token-boundary-chars
  #{\, \( \) \[ \] \{ \} \;})

(def bracket-pairs
  {\( \)
   \[ \]
   \{ \}})

(defn char-at
  [^String text idx]
  (let [length (.length text)]
    (when (and (<= 0 idx) (< idx length))
      (.charAt text idx))))

(defn boundary?
  [ch]
  (or (nil? ch)
      (Character/isWhitespace ^char ch)
      (contains? keyword-boundary-chars ch)))

(defn token-boundary?
  [ch]
  (or (nil? ch)
      (Character/isWhitespace ^char ch)
      (contains? token-boundary-chars ch)))

(defn walk-cljs-files
  [root-dir]
  (letfn [(walk [^java.io.File dir]
            (->> (or (seq (.listFiles dir)) [])
                 (sort-by #(.getName ^java.io.File %))
                 (mapcat
                  (fn [^java.io.File entry]
                    (cond
                      (.isDirectory entry)
                      (if (contains? ignored-dirs (.getName entry))
                        []
                        (walk entry))

                      (and (.isFile entry)
                           (str/ends-with? (.getName entry) ".cljs"))
                      [(.getPath entry)]

                      :else
                      [])))))]
    (vec (walk (io/file root-dir)))))

(defn skip-whitespace-and-comments
  [^String text start]
  (let [length (.length text)]
    (loop [i start]
      (if (>= i length)
        length
        (let [ch (char-at text i)]
          (cond
            (or (Character/isWhitespace ^char ch)
                (= ch \,))
            (recur (inc i))

            (= ch \;)
            (recur
             (loop [j i]
               (if (or (>= j length)
                       (= (char-at text j) \newline))
                 j
                 (recur (inc j)))))

            :else
            i))))))

(defn parse-string-end
  [^String text start]
  (let [length (.length text)]
    (loop [i (inc start) escaped? false]
      (if (>= i length)
        length
        (let [ch (char-at text i)]
          (cond
            escaped?
            (recur (inc i) false)

            (= ch \\)
            (recur (inc i) true)

            (= ch \")
            (inc i)

            :else
            (recur (inc i) false)))))))

(defn parse-form-end
  [^String text start]
  (let [length (.length text)]
    (cond
      (>= start length)
      length

      (= (char-at text start) \")
      (parse-string-end text start)

      (contains? bracket-pairs (char-at text start))
      (loop [i (inc start)
             stack [(get bracket-pairs (char-at text start))]
             in-string? false
             in-comment? false
             escaped? false]
        (if (>= i length)
          length
          (let [ch (char-at text i)]
            (cond
              in-comment?
              (recur (inc i) stack in-string? (not= ch \newline) escaped?)

              in-string?
              (cond
                escaped?
                (recur (inc i) stack true false false)

                (= ch \\)
                (recur (inc i) stack true false true)

                (= ch \")
                (recur (inc i) stack false false false)

                :else
                (recur (inc i) stack true false false))

              (= ch \;)
              (recur (inc i) stack false true false)

              (= ch \")
              (recur (inc i) stack true false false)

              (contains? bracket-pairs ch)
              (recur (inc i) (conj stack (get bracket-pairs ch)) false false false)

              (= ch (peek stack))
              (let [next-stack (pop stack)
                    next-i (inc i)]
                (if (empty? next-stack)
                  next-i
                  (recur next-i next-stack false false false)))

              :else
              (recur (inc i) stack false false false)))))

      :else
      (loop [i start]
        (if (token-boundary? (char-at text i))
          (if (= i start)
            (inc start)
            i)
          (recur (inc i)))))))

(defn find-keyword-positions
  [^String text keyword]
  (let [length (.length text)
        keyword-length (.length ^String keyword)]
    (loop [i 0 in-string? false in-comment? false escaped? false positions []]
      (if (>= i length)
        positions
        (let [ch (char-at text i)]
          (cond
            in-comment?
            (recur (inc i) in-string? (not= ch \newline) escaped? positions)

            in-string?
            (cond
              escaped?
              (recur (inc i) true false false positions)

              (= ch \\)
              (recur (inc i) true false true positions)

              (= ch \")
              (recur (inc i) false false false positions)

              :else
              (recur (inc i) true false false positions))

            (= ch \;)
            (recur (inc i) false true false positions)

            (= ch \")
            (recur (inc i) true false false positions)

            (and (<= (+ i keyword-length) length)
                 (.startsWith text keyword i))
            (let [prev (char-at text (dec i))
                  next (char-at text (+ i keyword-length))
                  next-i (+ i keyword-length)
                  next-positions (if (and (boundary? prev) (boundary? next))
                                   (conj positions i)
                                   positions)]
              (recur next-i false false false next-positions))

            :else
            (recur (inc i) false false false positions)))))))

(defn build-line-starts
  [^String text]
  (let [length (.length text)]
    (loop [i 0 starts [0]]
      (if (>= i length)
        starts
        (if (= (char-at text i) \newline)
          (recur (inc i) (conj starts (inc i)))
          (recur (inc i) starts))))))

(defn line-for-index
  [line-starts index]
  (loop [low 0 high (dec (count line-starts))]
    (if (> low high)
      (inc high)
      (let [mid (quot (+ low high) 2)]
        (if (<= (nth line-starts mid) index)
          (recur (inc mid) high)
          (recur low (dec mid)))))))

(defn collect-class-violations-in-form
  [file-path ^String text line-starts form-start form-end]
  (loop [i form-start in-comment? false violations []]
    (if (>= i form-end)
      violations
      (let [ch (char-at text i)]
        (cond
          in-comment?
          (recur (inc i) (not= ch \newline) violations)

          (= ch \;)
          (recur (inc i) true violations)

          (= ch \")
          (let [string-end (parse-string-end text i)
                literal-start (inc i)
                literal-end (max literal-start (dec string-end))
                literal (subs text literal-start literal-end)
                next-violations (if (re-find #"\s" literal)
                                  (conj violations
                                        {:file-path file-path
                                         :line (line-for-index line-starts i)
                                         :literal literal})
                                  violations)]
            (recur string-end false next-violations))

          :else
          (recur (inc i) false violations))))))

(defn collect-style-map-string-key-violations
  [file-path ^String text line-starts form-start form-end]
  (if (not= (char-at text form-start) \{)
    []
    (loop [i (skip-whitespace-and-comments text (inc form-start))
           violations []]
      (if (>= i (dec form-end))
        violations
        (let [ch (char-at text i)]
          (if (= ch \})
            violations
            (let [key-start i
                  key-end (parse-form-end text key-start)
                  next-violations (if (= (char-at text key-start) \")
                                    (let [literal-start (inc key-start)
                                          literal-end (max literal-start (dec key-end))
                                          literal (subs text literal-start literal-end)]
                                      (conj violations
                                            {:file-path file-path
                                             :line (line-for-index line-starts key-start)
                                             :literal literal}))
                                    violations)
                  value-start (skip-whitespace-and-comments text key-end)]
              (if (>= value-start (dec form-end))
                next-violations
                (let [value-end (parse-form-end text value-start)]
                  (recur (skip-whitespace-and-comments text value-end)
                         next-violations))))))))))

(defn class-violations-in-text
  [file-path ^String text]
  (let [line-starts (build-line-starts text)
        positions (find-keyword-positions text ":class")]
    (vec
     (mapcat
      (fn [keyword-pos]
        (let [form-start (skip-whitespace-and-comments text (+ keyword-pos 6))
              form-end (parse-form-end text form-start)]
          (collect-class-violations-in-form file-path text line-starts form-start form-end)))
      positions))))

(defn style-map-string-key-violations-in-text
  [file-path ^String text]
  (let [line-starts (build-line-starts text)
        positions (find-keyword-positions text ":style")]
    (vec
     (mapcat
      (fn [keyword-pos]
        (let [form-start (skip-whitespace-and-comments text (+ keyword-pos 6))
              form-end (parse-form-end text form-start)]
          (collect-style-map-string-key-violations file-path text line-starts form-start form-end)))
      positions))))

(defn class-violations-in-file
  [file-path]
  (class-violations-in-text file-path (slurp file-path)))

(defn style-map-string-key-violations-in-file
  [file-path]
  (style-map-string-key-violations-in-text file-path (slurp file-path)))

(defn sort-violations
  [violations]
  (sort-by (juxt :file-path :line) violations))

(defn relative-path
  [root-path file-path]
  (-> (.. (io/file root-path) toPath (relativize (.. (io/file file-path) toPath)))
      str
      (str/replace "\\" "/")))

(defn check-class-attrs!
  []
  (if-not (.exists src-dir)
    (do
      (println "No src directory found; skipping class attr check.")
      0)
    (let [violations (->> (walk-cljs-files src-dir)
                          (mapcat class-violations-in-file)
                          sort-violations
                          vec)]
      (if (seq violations)
        (do
          (binding [*out* *err*]
            (println "Found space-separated class strings in :class attrs:")
            (doseq [{:keys [file-path line literal]} violations]
              (println (str (relative-path root file-path)
                            ":" line
                            " "
                            (pr-str literal)))))
          1)
        (do
          (println "No space-separated class strings found in :class attrs.")
          0)))))

(defn check-style-map-string-keys!
  []
  (if-not (.exists src-dir)
    (do
      (println "No src directory found; skipping style key check.")
      0)
    (let [violations (->> (walk-cljs-files src-dir)
                          (mapcat style-map-string-key-violations-in-file)
                          sort-violations
                          vec)]
      (if (seq violations)
        (do
          (binding [*out* *err*]
            (println "Found string keys in literal :style maps:")
            (doseq [{:keys [file-path line literal]} violations]
              (println (str (relative-path root file-path)
                            ":" line
                            " "
                            (pr-str literal))))
            (println "Use keyword keys in :style maps, including CSS custom properties (example: :--slider-progress)."))
          1)
        (do
          (println "No string keys found in literal :style maps.")
          0)))))
