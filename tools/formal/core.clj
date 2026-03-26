(ns tools.formal.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream File]))

(def ^:private supported-surfaces
  {"vault-transfer" {:lean-module "Hyperopen.Formal.VaultTransfer"
                     :manifest "generated/vault-transfer.edn"}
   "order-request-standard" {:lean-module "Hyperopen.Formal.OrderRequest.Standard"
                             :manifest "generated/order-request-standard.edn"}
   "order-request-advanced" {:lean-module "Hyperopen.Formal.OrderRequest.Advanced"
                             :manifest "generated/order-request-advanced.edn"}})

(def ^:private install-message
  (str "Lean 4 is required for the formal toolchain.\n"
       "Install it with elan, then retry:\n"
       "  curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh -s -- -y --no-modify-path\n"
       "  export PATH=\"$HOME/.elan/bin:$PATH\""))

(defn- usage
  []
  (str "Usage: bb tools/formal.clj <verify|sync> --surface <vault-transfer|order-request-standard|order-request-advanced>\n"
       "Examples:\n"
       "  bb tools/formal.clj verify --surface vault-transfer\n"
       "  bb tools/formal.clj sync --surface order-request-standard\n"
       "Notes:\n"
       "  - `verify` builds the Lean workspace and checks the selected surface manifest.\n"
       "  - `sync` refreshes the deterministic generated manifest for the selected surface.\n"
       "  - Lean is expected under `~/.elan/bin` or on PATH.\n"))

(defn- fail!
  [message]
  (throw (ex-info message {:usage (usage)})))

(defn- repo-root
  []
  (let [script-file (some-> (System/getProperty "babashka.file") io/file .getCanonicalFile)
        tools-dir (or (some-> script-file .getParentFile)
                      (io/file "."))]
    (some-> tools-dir .getParentFile .getCanonicalFile)))

(defn- formal-root
  []
  (io/file (repo-root) "tools" "formal"))

(defn- lean-root
  []
  (io/file (formal-root) "lean"))

(defn- generated-root
  []
  (io/file (formal-root) "generated"))

(defn- shell-argv
  [command args]
  (into [command] args))

(defn- start-output-drainer!
  [process output-buffer]
  (doto (Thread.
         (fn []
           (try
             (with-open [stream (.getInputStream process)]
               (io/copy stream output-buffer))
             (catch Exception _)))
         "formal-output-drainer")
    (.setDaemon true)
    (.start)))

(defn- run-command
  [command args {:keys [dir]}]
  (let [builder (doto (ProcessBuilder. ^java.util.List (vec (shell-argv command args)))
                  (.redirectErrorStream true))
        _ (when dir
            (.directory builder ^File dir))
        process (.start builder)
        output-buffer (ByteArrayOutputStream.)
        drainer (start-output-drainer! process output-buffer)
        exit-code (.waitFor process)]
    (.join drainer 1000)
    {:command (cons command args)
     :exit exit-code
     :output (.toString output-buffer "UTF-8")}))

(defn- tool-exists?
  [name]
  (let [elan-path (io/file (System/getProperty "user.home") ".elan" "bin" name)]
    (or (.exists elan-path)
        (zero? (:exit (run-command "/bin/sh" ["-lc" (str "command -v " name " >/dev/null 2>&1")] {}))))))

(defn- ensure-lean-tools!
  []
  (when-not (and (tool-exists? "lean")
                 (tool-exists? "lake"))
    (fail! install-message)))

(defn- lean-binary
  [name]
  (let [elan-path (io/file (System/getProperty "user.home") ".elan" "bin" name)]
    (if (.exists elan-path)
      (.getAbsolutePath elan-path)
      name)))

(defn- normalize-surface
  [value]
  (when-let [surface (some-> value str/trim)]
    (when-let [surface* (get supported-surfaces surface)]
      (assoc surface* :id surface))))

(defn- parse-args
  [args]
  (when (empty? args)
    (fail! (usage)))
  (let [[command & tail] args]
    (cond
      (contains? #{"--help" "-h" "help"} command)
      {:help true}

      (not (contains? #{"verify" "sync"} command))
      (fail! (str "Unsupported command: " command "\n" (usage)))

      :else
      (loop [remaining tail
             opts {:command command
                   :surface nil}]
        (if (empty? remaining)
          (if (:surface opts)
            opts
            (fail! (str "Missing --surface value.\n" (usage))))
          (let [[key value & more] remaining]
            (cond
              (= key "--help")
              {:help true}

              (= key "--surface")
              (if value
                (if-let [surface (normalize-surface value)]
                  (recur more (assoc opts :surface surface))
                  (fail! (str "Unsupported surface: " value "\n" (usage))))
                (fail! (str "Missing value for --surface.\n" (usage))))

              :else
              (fail! (str "Unknown argument: " key "\n" (usage))))))))))

(defn- manifest-content
  [surface-id lean-module]
  (str "{:surface \"" surface-id "\""
       " :module \"" lean-module "\""
       " :status \"bootstrap\"}\n"))

(defn- manifest-path
  [surface-id]
  (io/file (generated-root) (str surface-id ".edn")))

(defn- write-manifest!
  [{:keys [id lean-module]}]
  (let [file (manifest-path id)
        content (manifest-content id lean-module)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    {:path file
     :content content}))

(defn- verify-manifest!
  [{:keys [id lean-module]}]
  (let [file (manifest-path id)
        expected (manifest-content id lean-module)]
    (if (.exists file)
      (let [actual (slurp file)]
        (when-not (= actual expected)
          (fail! (str "Stale generated manifest: " (.getPath file) "\n"
                      "Run `bb tools/formal.clj sync --surface " id "` to refresh it."))))
      (fail! (str "Missing generated manifest: " (.getPath file) "\n"
                  "Run `bb tools/formal.clj sync --surface " id "` first.")))
    {:path file
     :content expected}))

(defn- lake-command
  []
  (lean-binary "lake"))

(defn- build-lean-workspace!
  []
  (let [result (run-command (lake-command) ["build"] {:dir (lean-root)})]
    (when-not (zero? (:exit result))
      (fail! (str "Lean build failed.\n" (:output result))))
    result))

(defn- run-lean-entrypoint!
  [command surface-id]
  (let [result (run-command (lake-command)
                            ["exe" "formal" command "--surface" surface-id]
                            {:dir (lean-root)})]
    (when-not (zero? (:exit result))
      (fail! (str "Lean entrypoint failed.\n" (:output result))))
    result))

(defn- surface-record
  [surface-id]
  (let [surface (get supported-surfaces surface-id)]
    (when-not surface
      (fail! (str "Unsupported surface: " surface-id "\n" (usage))))
    (assoc surface :id surface-id)))

(defn run!
  [args]
  (let [opts (parse-args args)]
    (if (:help opts)
      (println (usage))
      (do
        (ensure-lean-tools!)
        (build-lean-workspace!)
        (let [{:keys [command surface]} opts
              surface* (surface-record (:id surface))]
          (case command
            "verify"
            (do
              (verify-manifest! surface*)
              (run-lean-entrypoint! command (:id surface))
              (println (str "Verified " (:id surface) " and confirmed the checked-in manifest is current.")))

            "sync"
            (do
              (write-manifest! surface*)
              (run-lean-entrypoint! command (:id surface))
              (println (str "Synced " (:id surface) " into " (.getPath (manifest-path (:id surface))))))))))))
