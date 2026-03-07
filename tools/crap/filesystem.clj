(ns tools.crap.filesystem
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def source-roots
  ["src" "test" "dev"])

(defn canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn relativize
  [root path]
  (let [root-path (.toPath (io/file (canonical-path root)))
        file-path (.toPath (io/file (canonical-path path)))]
    (str (.relativize root-path file-path))))

(defn cljs-files-under
  [root-dir]
  (let [root (io/file root-dir)]
    (->> (file-seq root)
         (filter #(.isFile ^java.io.File %))
         (map #(.getPath ^java.io.File %))
         (filter #(str/ends-with? % ".cljs"))
         sort)))

(defn normalize-relative-path
  [path]
  (-> path
      (str/replace #"^\./" "")
      (str/replace #"\\" "/")))

(defn existing-relative-path
  [root candidate]
  (let [file (io/file root candidate)]
    (when (.exists file)
      (relativize root (.getPath file)))))

(defn resolve-source-path
  [root logical-path]
  (let [logical (normalize-relative-path logical-path)
        candidates (distinct (concat [logical]
                                     (map #(str % "/" logical) source-roots)))]
    (some #(existing-relative-path root %) candidates)))
