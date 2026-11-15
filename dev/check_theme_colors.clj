#!/usr/bin/env bb

(ns dev.check-theme-colors
  "Ratchet gate for hardcoded color literals outside the theme token layer.

   Counts raw color literals (#hex, rgb()/rgba()/hsl() that do not resolve a
   --ho token) per file across view code and style surfaces, and compares
   against the committed baseline in dev/theme_color_baseline.edn. A file
   exceeding its baseline fails the gate: new colors belong in the token
   vocabulary (docs/THEMING.md), not inline. Lowering a count is always
   allowed; run with --update after cleanups to tighten the baseline."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def baseline-path "dev/theme_color_baseline.edn")

(def scan-roots
  ["src/hyperopen" "src/styles"])

(def scan-extensions
  #{".cljs" ".css"})

(def exempt-path-patterns
  [#"^src/styles/themes/"
   #"^src/styles/surfaces/optimizer"
   #"^src/hyperopen/portfolio/optimizer/"
   #"^src/hyperopen/views/portfolio/optimize/"
   #"^src/hyperopen/views/trading_chart/utils/theme_colors\.cljs$"])

(def ^:private hex-pattern
  #"#[0-9a-fA-F]{8}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{4}|#[0-9a-fA-F]{3}(?![0-9a-fA-F])")

(def ^:private fn-color-pattern
  #"(?:rgba?|hsla?)\(")

(def ^:private token-fn-color-pattern
  #"(?:rgba?|hsla?)\(\s*var\(")

(defn- exempt?
  [path]
  (some #(re-find % path) exempt-path-patterns))

(defn- count-color-literals
  [content]
  (+ (count (re-seq hex-pattern content))
     (- (count (re-seq fn-color-pattern content))
        (count (re-seq token-fn-color-pattern content)))))

(defn- scan-files
  []
  (->> scan-roots
       (mapcat (fn [root]
                 (->> (file-seq (io/file root))
                      (filter #(.isFile ^java.io.File %))
                      (map #(.getPath ^java.io.File %)))))
       (filter (fn [path]
                 (some #(str/ends-with? path %) scan-extensions)))
       (remove exempt?)
       sort))

(defn current-counts
  []
  (->> (scan-files)
       (keep (fn [path]
               (let [n (count-color-literals (slurp path))]
                 (when (pos? n)
                   [path n]))))
       (into (sorted-map))))

(defn- read-baseline
  []
  (let [file (io/file baseline-path)]
    (if (.exists file)
      (edn/read-string (slurp file))
      {})))

(defn- write-baseline!
  [counts]
  (with-open [writer (io/writer baseline-path)]
    (.write writer ";; Hardcoded color literal counts per file (upper bounds).\n")
    (.write writer ";; Maintained by: bb -m dev.check-theme-colors --update\n")
    (.write writer ";; See docs/THEMING.md - do not raise counts by hand.\n")
    (.write writer "{")
    (doseq [[idx [path n]] (map-indexed vector counts)]
      (when (pos? idx)
        (.write writer "\n "))
      (.write writer (str (pr-str path) " " n)))
    (.write writer "}\n")))

(defn check!
  []
  (let [baseline (read-baseline)
        counts (current-counts)
        regressions (->> counts
                         (keep (fn [[path n]]
                                 (let [allowed (get baseline path 0)]
                                   (when (> n allowed)
                                     [path allowed n]))))
                         vec)
        slack (->> baseline
                   (keep (fn [[path allowed]]
                           (let [n (get counts path 0)]
                             (when (< n allowed)
                               [path allowed n]))))
                   vec)]
    (doseq [[path allowed n] regressions]
      (println (str "[theme-colors] " path
                    " has " n " raw color literals (baseline " allowed ")."
                    " Use --ho-* tokens instead (docs/THEMING.md).")))
    (when (seq slack)
      (println (str "[theme-colors] " (count slack)
                    " file(s) are below baseline; run"
                    " `bb -m dev.check-theme-colors --update` to ratchet down.")))
    (if (seq regressions)
      1
      (do
        (println (str "No new hardcoded colors ("
                      (reduce + (vals counts)) " literals across "
                      (count counts) " files remain to migrate)."))
        0))))

(defn -main
  [& args]
  (if (some #{"--update"} args)
    (let [counts (current-counts)]
      (write-baseline! counts)
      (println (str "Wrote " baseline-path " with " (count counts) " entries ("
                    (reduce + (vals counts)) " literals).")))
    (System/exit (check!))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
