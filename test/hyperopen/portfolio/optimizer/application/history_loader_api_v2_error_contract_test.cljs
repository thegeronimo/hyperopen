(ns hyperopen.portfolio.optimizer.application.history-loader-api-v2-error-contract-test
  "Frontend consumption of the history-bundle error contract additions
  (2026-07-09): excluded-from-alignment, common-window-empty / status error, and
  serve-time stale-history escalation. See API_CONTRACT.md in the
  hyperopen_data_service repo ('Status Values', 'Warning Codes', aligned-returns)."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]))

(defn- warning-by-code
  [body code]
  (some #(when (= code (:code %)) %) (:warnings body)))

(def ^:private error-bundle-body
  {:contract_version "optimizer-history-api-v2"
   :request_id "rid-error"
   :dataset_version "dv"
   :status "error"
   :common_calendar []
   :return_calendar []
   :series_by_instrument
   {"perp:X" {:instrument_id "hl:perp:X"
              :lineage_kind "native"
              :points [{:time_ms 1000 :close 1.0 :return nil}
                       {:time_ms 2000 :close 1.1 :return 0.1}]}}
   :aligned_returns_by_instrument {}
   :warnings [{:code "excluded_from_alignment"
               :severity "warning"
               :details {:client_instrument_id "perp:X"
                         :reason "window_disjoint_from_majority"
                         :window_start_ms 1000
                         :window_end_ms 2000}}
              {:code "common_window_empty"
               :severity "error"
               :details {:instrument_ids ["hl:perp:X"]
                         :excluded_instrument_ids ["hl:perp:X"]}}
              {:code "stale_history"
               :severity "error"
               :instrument_id "perp:Y"
               :details {:served_end_ms 111 :serve_age_days 9}}]})

(deftest error-status-is-decoded-and-series-still-served-test
  (let [body (api-v2/normalize-history-body error-bundle-body)]
    (is (= :error (:status body)))
    (is (contains? (:series-by-instrument body) "perp:X"))
    (is (= 2 (count (get-in body [:series-by-instrument "perp:X" :points]))))))

(deftest excluded-from-alignment-warning-is-attributed-and-reason-keywordized-test
  (let [warning (warning-by-code (api-v2/normalize-history-body error-bundle-body)
                                 :excluded-from-alignment)]
    (is (some? warning))
    (is (= "perp:X" (:instrument-id warning)))
    (is (= :window-disjoint-from-majority (get-in warning [:details :reason])))
    (is (= 1000 (get-in warning [:details :window-start-ms])))))

(deftest common-window-empty-and-stale-details-survive-normalization-test
  (let [body (api-v2/normalize-history-body error-bundle-body)
        common (warning-by-code body :common-window-empty)
        stale (warning-by-code body :stale-history)]
    (is (= ["hl:perp:X"] (get-in common [:details :instrument-ids])))
    (is (= ["hl:perp:X"] (get-in common [:details :excluded-instrument-ids])))
    (is (= 9 (get-in stale [:details :serve-age-days])))
    (is (= 111 (get-in stale [:details :served-end-ms])))))
