(ns hyperopen.views.funding-modal.tabindex-attribute-test
  "Replicant writes attribute names exactly as given (`set-attribute` calls
   `setAttribute` with the keyword's name), so `:tab-index` puts an unknown
   `tab-index` attribute in the DOM and the element stays unfocusable. The
   Transfer run views focus their headings and the dialog falls back to
   focusing its panel, so the funding modal's views must spell it
   `:tabindex`. This scans their sources, since a hiccup-level test reads
   back whatever key the view used."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- source-files
  []
  (let [root (.cwd js/process)
        dir (.join path root "src" "hyperopen" "views" "funding_modal")]
    (into [(.join path root "src" "hyperopen" "views" "funding_modal.cljs")]
          (->> (array-seq (.readdirSync fs dir))
               (filter #(str/ends-with? % ".cljs"))
               sort
               (map #(.join path dir %))))))

(deftest funding-modal-views-spell-tabindex-as-the-dom-attribute-test
  (let [files (source-files)]
    (is (< 3 (count files)) "the scan found the funding modal views")
    (doseq [file files]
      (testing file
        (is (not (re-find #":tab-index\b" (.readFileSync fs file "utf8"))))))))
