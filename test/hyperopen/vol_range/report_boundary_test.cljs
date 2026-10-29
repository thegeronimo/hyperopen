(ns hyperopen.vol-range.report-boundary-test
  "Reporting is reads, its own ledger store and (Start tracking only) a
  journal commit through injected collaborators: no reporting namespace may
  reach order construction, signing, the nonce coordinator, delivery,
  placement or a timer of its own (HO-SPEC-014 sections 9.3, 12)."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- list-files [dir]
  (if (.existsSync fs dir)
    (vec (mapcat (fn [entry]
                   (let [p (.join path dir entry)]
                     (if (.isDirectory (.statSync fs p)) (list-files p) [p])))
                 (array-seq (.readdirSync fs dir))))
    []))

(def reporting-sources
  (into ["src/hyperopen/vol_range/domain/report_intervals.cljs"
         "src/hyperopen/vol_range/domain/report_ledger.cljs"
         "src/hyperopen/vol_range/domain/report_equations.cljs"
         "src/hyperopen/vol_range/domain/report_marks.cljs"
         "src/hyperopen/vol_range/domain/report_matching.cljs"
         "src/hyperopen/vol_range/application/report_chart_model.cljs"
         "src/hyperopen/vol_range/application/report_matched.cljs"
         "src/hyperopen/vol_range/application/report_windows.cljs"
         "src/hyperopen/vol_range/application/report_aggregate.cljs"
         "src/hyperopen/vol_range/application/report_headline.cljs"
         "src/hyperopen/vol_range/application/report_attribution.cljs"
         "src/hyperopen/vol_range/application/report_coverage.cljs"
         "src/hyperopen/vol_range/application/report_model.cljs"
         "src/hyperopen/vol_range/application/report_copy.cljs"
         "src/hyperopen/vol_range/application/report_export.cljs"
         "src/hyperopen/vol_range/infrastructure/ledger_store.cljs"
         "src/hyperopen/vol_range/infrastructure/report_reads.cljs"
         "src/hyperopen/vol_range/infrastructure/report_executor.cljs"
         "src/hyperopen/vol_range/infrastructure/campaign_executor.cljs"
         "src/hyperopen/vol_range/infrastructure/ledger_ingest.cljs"
         "src/hyperopen/vol_range/infrastructure/report_watcher.cljs"
         "src/hyperopen/vol_range/report_actions.cljs"
         "src/hyperopen/views/portfolio/vol_range_report.cljs"]
        (filter #(str/ends-with? % ".cljs") (list-files "src/hyperopen/views/portfolio/vol_range"))))

(def ^:private forbidden-requires
  ["hyperopen.api.trading" "hyperopen.order." "hyperopen.utils.hl-signing" "hyperopen.api.gateway.orders"
   "hyperopen.vol-range.infrastructure.executor" "hyperopen.vol-range.infrastructure.journal"
   "hyperopen.vol-range.infrastructure.entropy" "hyperopen.vol-range.application.wire"])

(def ^:private adapter "src/hyperopen/runtime/effect_adapters/vol_range_report.cljs")

(def ^:private adapter-forbidden-requires
  "The report adapter wires the journal, entropy and the admission section
  (Start tracking), but never a send, signing or placement path."
  ["hyperopen.api.trading.delivery" "hyperopen.api.trading.nonce-coordinator" "hyperopen.order."
   "hyperopen.utils.hl-signing" "hyperopen.api.gateway.orders" "hyperopen.vol-range.infrastructure.executor "
   "hyperopen.vol-range.infrastructure.executor-place" "hyperopen.vol-range.infrastructure.executor-cancel"
   "hyperopen.vol-range.application.wire"])

(defn- ns-form [text]
  (let [end (.indexOf text "\n(def")]
    (if (neg? end) text (subs text 0 end))))

(deftest reporting-namespaces-require-no-execution-path-test
  (is (>= (count reporting-sources) 30) "the views are scanned too")
  (doseq [file reporting-sources
          :let [text (.readFileSync fs file "utf8")
                form (ns-form text)]]
    (is (str/starts-with? form "(ns ") file)
    (doseq [needle forbidden-requires]
      (is (not (str/includes? form needle)) (str file " requires " needle)))
    (doseq [timer ["set-interval!" "js/setInterval" "js/setTimeout"]]
      (is (not (str/includes? text timer)) (str file " starts its own timer")))))

(deftest the-report-adapter-has-no-send-path-test
  (let [text (.readFileSync fs adapter "utf8")
        form (ns-form text)]
    (testing "Start tracking takes the admission section; nothing else from trading"
      (is (str/includes? form "hyperopen.api.trading.action-coordinator"))
      (is (= 1 (count (re-seq #"hyperopen\.api\.trading\." form)))))
    (doseq [needle adapter-forbidden-requires]
      (is (not (str/includes? form needle)) (str adapter " requires " needle)))
    (doseq [timer ["set-interval!" "js/setInterval"]]
      (is (not (str/includes? text timer)) (str adapter " starts a periodic timer")))))
