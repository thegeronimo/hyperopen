(ns hyperopen.views.vaults.detail.activity.performance-metrics-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.vaults.detail.activity.performance-metrics :as metrics]))

(def ^:private metrics-card-input
  {:benchmark-selected? true
   :benchmark-label "Bitcoin"
   :benchmark-columns [{:coin "BTC"
                        :label "Bitcoin"}]
   :benchmark-coin "BTC"
   :vault-label "Growi HF"
   :timeframe-options [{:value :month :label "30D"}]
   :timeframe-menu-open? true
   :selected-timeframe :month
   :groups [{:id :risk
             :rows [{:key :sharpe
                     :label "Sharpe"
                     :kind :ratio
                     :value 1.23
                     :benchmark-values {"BTC" 0.98}}
                    {:key :omega
                     :label "Omega"
                     :description "Ratio of gains above the target return to losses below it."
                     :kind :ratio
                     :value 1.11
                     :portfolio-status :low-confidence
                     :portfolio-reason :daily-coverage-gate-failed
                     :benchmark-values {"BTC" 0.94}
                     :benchmark-statuses {"BTC" :low-confidence}
                     :benchmark-reasons {"BTC" :daily-coverage-gate-failed}}
                    {:key :hidden
                     :label "Hidden"
                     :kind :ratio
                     :value nil
                     :benchmark-values {"BTC" nil}}]}]})

(deftest performance-metric-formatting-and-column-helpers-test
  (is (= "+1.23%" (metrics/format-signed-percent-from-decimal 0.01234)))
  (is (= "-3.45%" (metrics/format-signed-percent-from-decimal -0.0345)))
  (is (= "0.00%" (metrics/format-signed-percent-from-decimal -0.000001)))
  (is (= "--" (metrics/format-metric-value :date "   ")))
  (is (= "2026-03-01" (metrics/format-metric-value :date "2026-03-01")))
  (is (= "8" (metrics/format-metric-value :integer 8.4)))
  (is (= [{:coin "BTC" :label "Bitcoin"}]
         (metrics/resolved-benchmark-metric-columns {:benchmark-columns [{:coin " BTC " :label "Bitcoin"}]
                                                     :benchmark-selected? true
                                                     :benchmark-label "Benchmark"
                                                     :benchmark-coin "BTC"})))
  (is (= [{:coin "ETH" :label "Benchmark"}]
         (metrics/resolved-benchmark-metric-columns {:benchmark-columns []
                                                     :benchmark-selected? true
                                                     :benchmark-label nil
                                                     :benchmark-coin "ETH"})))
  (is (= [{:coin "__benchmark__" :label "Benchmark"}]
         (metrics/resolved-benchmark-metric-columns {:benchmark-columns nil
                                                     :benchmark-selected? false
                                                     :benchmark-label nil
                                                     :benchmark-coin nil})))
  (is (= 1.1 (metrics/benchmark-row-value {:benchmark-values {"BTC" 1.1}} "BTC")))
  (is (= 2.2 (metrics/benchmark-row-value {:benchmark-values {"ETH" 3.3}
                                           :benchmark-value 2.2}
                                          "BTC")))
  (is (true? (metrics/performance-metric-row-visible? {:kind :percent
                                                       :value nil
                                                       :benchmark-values {"BTC" 0.2}}
                                                      [{:coin "BTC"}])))
  (is (nil? (metrics/performance-metric-row-visible? {:kind :percent
                                                      :value nil
                                                      :benchmark-values {"BTC" nil}}
                                                     [{:coin "BTC"}]))))

(deftest performance-metrics-card-renders-low-confidence-contracts-test
  (let [view (metrics/performance-metrics-card metrics-card-input)
        sharpe-row (hiccup/find-by-data-role view "vault-detail-performance-metric-sharpe")
        hidden-row (hiccup/find-by-data-role view "vault-detail-performance-metric-hidden")
        sharpe-benchmark-cell (hiccup/find-by-data-role view "vault-detail-performance-metric-sharpe-benchmark-value-BTC")
        sharpe-vault-cell (hiccup/find-by-data-role view "vault-detail-performance-metric-sharpe-vault-value")
        estimated-banner (hiccup/find-by-data-role view "vault-detail-performance-metrics-estimated-banner")
        estimated-banner-tooltip (hiccup/find-by-data-role view "vault-detail-performance-metrics-estimated-banner-tooltip")
        estimated-banner-panel (hiccup/find-first-node estimated-banner-tooltip
                                                       #(= "tooltip" (get-in % [1 :role])))
        omega-label-tooltip (hiccup/find-by-data-role view "vault-detail-performance-metric-omega-label-tooltip")
        omega-label-tooltip-panel (hiccup/find-first-node omega-label-tooltip
                                                          #(= "tooltip" (get-in % [1 :role])))
        estimated-mark (hiccup/find-by-data-role view "vault-detail-performance-metric-omega-estimated-mark")
        vault-low-confidence-cell (hiccup/find-by-data-role view "vault-detail-performance-metric-omega-vault-value")
        benchmark-low-confidence-cell (hiccup/find-by-data-role view "vault-detail-performance-metric-omega-benchmark-value-BTC")
        badge-node (hiccup/find-by-data-role view "vault-detail-performance-metric-omega-vault-value-status-badge")
        benchmark-label (hiccup/find-by-data-role view "vault-detail-performance-metrics-benchmark-label")
        vault-label (hiccup/find-by-data-role view "vault-detail-performance-metrics-vault-label")
        scroll-region (hiccup/find-by-data-role view "vault-detail-performance-metrics-scroll-region")
        timeframe-trigger (hiccup/find-by-data-role view "vault-detail-performance-metrics-timeframe-trigger")
        timeframe-menu (hiccup/find-by-data-role view "vault-detail-performance-metrics-timeframe-options")
        timeframe-option (hiccup/find-by-data-role view "vault-detail-performance-metrics-timeframe-option-month")]
    (is (some? sharpe-row))
    (is (= {:grid-template-columns "220px 132px 132px"} (get-in sharpe-row [1 :style])))
    (is (nil? hidden-row))
    (is (contains? (set (hiccup/collect-strings sharpe-benchmark-cell)) "0.98"))
    (is (contains? (set (hiccup/collect-strings sharpe-vault-cell)) "1.23"))
    (is (some? estimated-banner))
    (is (= 0 (get-in estimated-banner [1 :tab-index])))
    (is (contains? (set (keys (get-in estimated-banner [1 :style]))) :border-color))
    (is (contains? (set (keys (get-in estimated-banner [1 :style]))) :background))
    (is (contains? (set (hiccup/collect-strings estimated-banner))
                   "Some metrics are estimated from incomplete daily data."))
    (is (contains? (set (hiccup/collect-strings estimated-banner-tooltip))
                   "Estimated rows stay visible when the selected range does not meet the usual reliability gates."))
    (is (contains? (set (hiccup/collect-strings estimated-banner-tooltip))
                   "Estimated from incomplete daily coverage."))
    (is (contains? (hiccup/node-class-set estimated-banner-panel) "border-base-300"))
    (is (contains? (hiccup/node-class-set estimated-banner-panel) "bg-gray-800"))
    (is (contains? (set (hiccup/collect-strings omega-label-tooltip)) "Omega"))
    (is (contains? (set (hiccup/collect-strings omega-label-tooltip))
                   "Ratio of gains above the target return to losses below it."))
    (is (contains? (hiccup/node-class-set omega-label-tooltip-panel) "border-base-300"))
    (is (contains? (hiccup/node-class-set omega-label-tooltip-panel) "bg-gray-800"))
    (is (= "~" (first (hiccup/collect-strings estimated-mark))))
    (is (contains? (hiccup/node-class-set vault-low-confidence-cell) "text-[#9fb4bb]"))
    (is (contains? (hiccup/node-class-set benchmark-low-confidence-cell) "text-[#9fb4bb]"))
    (is (nil? badge-node))
    (is (some? benchmark-label))
    (is (= "Growi HF" (first (hiccup/collect-strings vault-label))))
    (is (= "region" (get-in scroll-region [1 :role])))
    (is (= "Vault performance metrics" (get-in scroll-region [1 :aria-label])))
    (is (= 0 (get-in scroll-region [1 :tab-index])))
    (is (= [[:actions/toggle-vault-detail-performance-metrics-timeframe-dropdown]]
           (get-in timeframe-trigger [1 :on :click])))
    (is (= true (get-in timeframe-trigger [1 :aria-expanded])))
    (is (= "open" (get-in timeframe-menu [1 :data-ui-state])))
    (is (some? timeframe-option))
    (is (nil? (hiccup/find-first-node view #(= :select (first %)))))))

(deftest performance-metrics-card-renders-loading-overlay-copy-test
  (let [view (metrics/performance-metrics-card
              (assoc metrics-card-input :loading? true))
        overlay-node (hiccup/find-by-data-role view "vault-detail-performance-metrics-loading-overlay")
        overlay-strings (set (hiccup/collect-strings overlay-node))
        sharpe-row (hiccup/find-by-data-role view "vault-detail-performance-metric-sharpe")]
    (is (some? overlay-node))
    (is (= "status" (get-in overlay-node [1 :role])))
    (is (= "polite" (get-in overlay-node [1 :aria-live])))
    (is (contains? (hiccup/node-class-set overlay-node) "absolute"))
    (is (contains? (hiccup/node-class-set overlay-node) "inset-0"))
    (is (contains? (hiccup/node-class-set overlay-node) "z-10"))
    (is (contains? (hiccup/node-class-set overlay-node) "backdrop-blur-sm"))
    (is (contains? overlay-strings "Loading benchmark history"))
    (is (contains? overlay-strings
                   "Vault metrics stay visible while benchmark comparisons finish in the background."))
    (is (some? sharpe-row))))

(deftest performance-metrics-benchmark-columns-ignore-invalid-columns-and-preserve-grid-width-test
  (let [view (metrics/performance-metrics-card
              {:benchmark-selected? true
               :benchmark-columns [{:coin " BTC " :label " Bitcoin "}
                                   {:coin "" :label "Blank"}
                                   {:coin nil :label "Nil"}
                                   {:coin "ETH" :label " Ethereum "}]
               :groups [{:id :sample
                         :rows [{:key :sharpe
                                 :label "Sharpe"
                                 :kind :ratio
                                 :value 1.23
                                 :benchmark-values {"BTC" 0.98
                                                    "ETH" 0.87}}]}]})
        sharpe-row (hiccup/find-by-data-role view "vault-detail-performance-metric-sharpe")
        text (set (hiccup/collect-strings view))]
    (is (= [{:coin "__benchmark__" :label "Benchmark"}]
           (metrics/resolved-benchmark-metric-columns {:benchmark-columns [{:coin "" :label "Blank"}]
                                                       :benchmark-selected? false})))
    (is (contains? text "Bitcoin"))
    (is (contains? text "Ethereum"))
    (is (not (contains? text "Blank")))
    (is (= "220px 132px 132px 132px"
           (get-in sharpe-row [1 :style :grid-template-columns])))))

(deftest performance-metrics-low-confidence-reasons-are-visible-deduped-and-stably-ordered-test
  (let [ordered-low-confidence-reasons @#'metrics/ordered-low-confidence-reasons
        view (metrics/performance-metrics-card
              {:benchmark-selected? true
               :benchmark-columns [{:coin "BTC" :label "Bitcoin"}]
               :groups [{:id :sample
                         :rows [{:key :visible
                                 :label "Visible"
                                 :kind :ratio
                                 :value 1.0
                                 :portfolio-status :low-confidence
                                 :portfolio-reason :benchmark-coverage-gate-failed
                                 :benchmark-values {"BTC" 0.9}
                                 :benchmark-statuses {"BTC" :low-confidence}
                                 :benchmark-reasons {"BTC" :daily-coverage-gate-failed}}
                                {:key :hidden
                                 :label "Hidden"
                                 :kind :ratio
                                 :value nil
                                 :portfolio-status :low-confidence
                                 :portfolio-reason :psr-gate-failed
                                 :benchmark-values {"BTC" nil}
                                 :benchmark-statuses {"BTC" :low-confidence}
                                 :benchmark-reasons {"BTC" :drawdown-unavailable}}]}]})
        tooltip (hiccup/find-by-data-role view "vault-detail-performance-metrics-estimated-banner-tooltip")
        tooltip-text (set (hiccup/collect-strings tooltip))]
    (is (= [:daily-coverage-gate-failed :benchmark-coverage-gate-failed :unknown-a :unknown-z]
           (ordered-low-confidence-reasons [:unknown-z
                                            :benchmark-coverage-gate-failed
                                            :daily-coverage-gate-failed
                                            nil
                                            :unknown-a
                                            :daily-coverage-gate-failed])))
    (is (contains? tooltip-text "Estimated from incomplete daily coverage."))
    (is (contains? tooltip-text "Estimated from limited benchmark overlap."))
    (is (not (contains? tooltip-text "Estimated from limited daily history.")))
    (is (not (contains? tooltip-text "Estimated from sparse drawdown observations.")))))
