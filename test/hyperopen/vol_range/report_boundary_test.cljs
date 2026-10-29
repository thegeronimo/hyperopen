(ns hyperopen.vol-range.report-boundary-test
  "Reporting is reads and its own ledger store only: no reporting namespace
  may reach order construction, signing, the nonce coordinator, delivery,
  placement or a timer of its own (HO-SPEC-014 sections 9.3, 12)."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]))

(def ^:private fs (js/require "fs"))

(def reporting-sources
  ["src/hyperopen/vol_range/domain/report_intervals.cljs"
   "src/hyperopen/vol_range/domain/report_ledger.cljs"
   "src/hyperopen/vol_range/application/report_attribution.cljs"
   "src/hyperopen/vol_range/application/report_coverage.cljs"
   "src/hyperopen/vol_range/infrastructure/ledger_store.cljs"
   "src/hyperopen/vol_range/infrastructure/report_reads.cljs"
   "src/hyperopen/vol_range/infrastructure/report_executor.cljs"])

(def ^:private forbidden-requires
  ["hyperopen.api.trading" "hyperopen.order." "hyperopen.utils.hl-signing" "hyperopen.api.gateway.orders"
   "hyperopen.vol-range.infrastructure.executor" "hyperopen.vol-range.infrastructure.journal"
   "hyperopen.vol-range.infrastructure.entropy" "hyperopen.vol-range.application.wire"])

(defn- ns-form [text]
  (let [end (.indexOf text "\n(def")]
    (if (neg? end) text (subs text 0 end))))

(deftest reporting-namespaces-require-no-execution-path-test
  (doseq [file reporting-sources
          :let [text (.readFileSync fs file "utf8")
                form (ns-form text)]]
    (is (str/starts-with? form "(ns ") file)
    (doseq [needle forbidden-requires]
      (is (not (str/includes? form needle)) (str file " requires " needle)))
    (doseq [timer ["set-interval!" "js/setInterval" "js/setTimeout"]]
      (is (not (str/includes? text timer)) (str file " starts its own timer")))))
