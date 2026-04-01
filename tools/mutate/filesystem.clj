(ns tools.mutate.filesystem
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [tools.crap.filesystem :as shared-fs])
  (:import [java.time OffsetDateTime ZoneOffset]))

(def target-root "target/mutation")
(def manifest-root (str target-root "/manifests"))
(def backup-root (str target-root "/backups"))
(def report-root (str target-root "/reports"))

(defn repo-root
  []
  (shared-fs/canonical-path "."))

(defn normalize-relative-path
  [path]
  (shared-fs/normalize-relative-path path))

(defn repo-relative-path
  [root candidate]
  (let [file (io/file (str candidate))]
    (normalize-relative-path
     (if (.isAbsolute file)
       (shared-fs/relativize root (.getPath file))
       (str candidate)))))

(defn valid-module-path?
  [path]
  (and (string? path)
       (str/starts-with? path "src/hyperopen/")
       (str/ends-with? path ".cljs")))

(defn resolve-module-path
  [root candidate]
  (let [relative (repo-relative-path root candidate)
        resolved (or (shared-fs/resolve-source-path root relative)
                     (when (.exists (io/file root relative))
                       relative))
        normalized (some-> resolved normalize-relative-path)]
    (when (valid-module-path? normalized)
      normalized)))

(defn require-module-path
  [root candidate]
  (or (resolve-module-path root candidate)
      (throw
       (ex-info
        (str "Module must exist under src/hyperopen/ and end in .cljs: " candidate)
        {}))))

(defn ensure-parent!
  [path]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))
    path))

(defn delete-file!
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (.delete file))))

(defn- backup-file->module
  [root backup-file]
  (let [relative (shared-fs/relativize root (.getPath ^java.io.File backup-file))
        prefix (str backup-root "/")]
    (when (and (str/starts-with? relative prefix)
               (str/ends-with? relative ".bak"))
      (normalize-relative-path
       (subs relative (count prefix) (- (count relative) 4))))))

(defn- restore-backup-file!
  [root backup-file]
  (when-let [module (backup-file->module root backup-file)]
    (let [module-path (str (io/file root module))]
      (ensure-parent! module-path)
      (spit module-path (slurp backup-file))
      (delete-file! (.getPath ^java.io.File backup-file))
      module)))

(defn manifest-path
  [root module]
  (let [path (str (io/file root manifest-root (str (normalize-relative-path module) ".edn")))]
    (ensure-parent! path)
    path))

(defn backup-path
  [root module]
  (let [path (str (io/file root backup-root (str (normalize-relative-path module) ".bak")))]
    (ensure-parent! path)
    path))

(defn sanitize-for-filename
  [value]
  (-> value
      str
      (str/replace #"[^A-Za-z0-9._-]+" "-")
      (str/replace #"-+" "-")
      (str/replace #"(^-|-$)" "")))

(defn now-stamp
  []
  (str (OffsetDateTime/now ZoneOffset/UTC)))

(defn report-path
  [root module timestamp]
  (let [safe-module (sanitize-for-filename (normalize-relative-path module))
        safe-stamp (sanitize-for-filename timestamp)
        path (str (io/file root report-root (str safe-stamp "-" safe-module ".edn")))]
    (ensure-parent! path)
    path))

(defn restore-stale-backup!
  [root module]
  (let [backup (backup-path root module)
        backup-file (io/file backup)]
    (when (.exists backup-file)
      (restore-backup-file! root backup-file))))

(defn restore-stale-backups!
  [root]
  (let [dir (io/file root backup-root)]
    (when (.exists dir)
      (->> (file-seq dir)
           (filter #(.isFile ^java.io.File %))
           (filter #(str/ends-with? (.getName ^java.io.File %) ".bak"))
           (sort-by #(.getPath ^java.io.File %))
           (keep #(restore-backup-file! root %))
           vec))))
