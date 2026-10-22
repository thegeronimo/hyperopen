#!/usr/bin/env bb

(ns dev.check-input-numeric-parsing
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def root
  (.getCanonicalPath (io/file ".")))

(def guarded-relative-files
  ["src/hyperopen/trading/order_form_transitions.cljs"
   "src/hyperopen/account/history/actions.cljs"
   "src/hyperopen/account/history/position_margin.cljs"
   "src/hyperopen/account/history/position_reduce.cljs"
   "src/hyperopen/account/history/position_tpsl_policy.cljs"
   "src/hyperopen/account/history/position_tpsl_transitions.cljs"
   "src/hyperopen/chart/settings.cljs"
   "src/hyperopen/vaults/actions.cljs"
   "src/hyperopen/asset_selector/actions.cljs"
   "src/hyperopen/views/account_info/position_tpsl_modal.cljs"
   "src/hyperopen/views/active_asset_view.cljs"
   "src/hyperopen/trading/vol_range_transitions.cljs"
   "src/hyperopen/vol_range/actions.cljs"])

(def prohibited-rules
  [{:id :js-parse-float
    :pattern #"js/parseFloat"
    :message "Use hyperopen.utils.parse locale-aware helpers for numeric input parsing."}])

(defn normalize-path
  [path]
  (str/replace path "\\" "/"))

(defn relative-path
  [root-path file-path]
  (-> (.. (io/file root-path) toPath (relativize (.. (io/file file-path) toPath)))
      str
      normalize-path))

(defn violations-in-file
  [root-path relative-file]
  (let [target (io/file root-path relative-file)]
    (if-not (.exists target)
      [{:file relative-file
        :line 1
        :rule-id :missing-file
        :message "Guarded file is missing."}]
      (let [lines (str/split-lines (slurp target))]
        (->> (map-indexed vector lines)
             (mapcat (fn [[idx line]]
                       (for [{:keys [id pattern message]} prohibited-rules
                             :when (re-find pattern line)]
                         {:file (relative-path root-path (.getCanonicalPath target))
                          :line (inc idx)
                          :rule-id id
                          :message message
                          :line-text (str/trim line)})))
             vec)))))

(defn check-input-numeric-parsing!
  []
  (let [violations (->> guarded-relative-files
                        (mapcat #(violations-in-file root %))
                        vec)]
    (if (seq violations)
      (do
        (binding [*out* *err*]
          (println "Found forbidden numeric parsing patterns in guarded input-boundary files:")
          (doseq [{:keys [file line rule-id message line-text]} violations]
            (println (str file ":" line
                          " [" (name rule-id) "] "
                          message))
            (when (seq line-text)
              (println (str "  " line-text)))))
        1)
      (do
        (println "Input-boundary numeric parsing check passed.")
        0))))

(defn -main
  [& _args]
  (System/exit (check-input-numeric-parsing!)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
