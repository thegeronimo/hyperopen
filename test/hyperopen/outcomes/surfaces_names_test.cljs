(ns hyperopen.outcomes.surfaces-names-test
  "Names and money on every surface outside the market list (board 4e)."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.app.document-title :as document-title]
            [hyperopen.asset-selector.query :as query]
            [hyperopen.views.account-equity.metrics :as equity-metrics]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.projections.coins :as coins]
            [hyperopen.views.account-info.projections.outcomes :as outcome-projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.websocket.user-runtime.fills :as fills]))

(def ^:private champion
  {:key "question:1"
   :coin "#10"
   :symbol "NBA champion 2026/2027"
   :title "NBA champion 2026/2027"
   :market-type :outcome
   :outcome-shape :option
   :outcome-sides [{:side-index 0 :side-name "Yes" :coin "#10" :outcome-option-label "Thunder"}]
   :question-options [{:label "Thunder"
                       :sides [{:side-index 0 :side-name "Yes" :coin "#10"}
                               {:side-index 1 :side-name "No" :coin "#11"}]}]})

(def ^:private game
  {:key "outcome:27037"
   :coin "#270370"
   :symbol "Detroit Pistons vs Washington Wizards"
   :title "Detroit Pistons vs Washington Wizards"
   :market-type :outcome
   :outcome-shape :head-to-head
   :outcome-venue "Hyperliquid"
   :outcome-sides [{:side-index 0 :side-name "DET" :coin "#270370" :mark 0.8}
                   {:side-index 1 :side-name "WAS" :coin "#270371" :mark 0.2}]})

(def ^:private catalog {"question:1" champion "outcome:27037" game})

(deftest browser-tab-title-reads-answer-price-and-event-test
  (let [state {:active-asset "#10"
               :active-market champion
               :asset-selector {:market-by-key catalog}
               :active-assets {:contexts {"#10" {:mark 0.156325 :markRaw "0.156325"}}}}
        title (document-title/title-for-state state)]
    (is (str/starts-with? title "Thunder Yes 15.6¢ · NBA champion 2026/2027 | "))
    (is (not (str/includes? title "0.156325")))
    (is (not (str/includes? title "#10")))))

(deftest fill-toast-names-side-price-and-short-event-test
  (let [[payload] (fills/fill-toast-payloads
                   [{:coin "#270370" :side "B" :sz "122" :px "0.81902" :time 1 :tid 1}]
                   catalog)]
    (is (= "Bought 122 DET at 81.9¢ · Pistons vs Wizards" (:headline payload)))
    (is (nil? (:subline payload)))))

(deftest fill-toast-never-prints-a-coin-id-for-unknown-markets_test
  (let [[payload] (fills/fill-toast-payloads
                   [{:coin "#999990" :side "A" :sz "5" :px "0.4" :time 1 :tid 2}]
                   {})]
    (is (= "Sold 5 shares at 40.0¢" (:headline payload)))))

(deftest coin-display-is-event-answer-side-test
  (is (= "Detroit Pistons vs Washington Wizards · DET"
         (:base-label (coins/resolve-coin-display "#270370" catalog))))
  (is (= "NBA champion 2026/2027 · Thunder No"
         (:base-label (coins/resolve-coin-display "+11" catalog))))
  (is (= "Outcome" (:base-label (coins/resolve-coin-display "#555" catalog)))))

(deftest order-and-trade-tables-print-cents-and-shares-test
  (is (= "81.9¢" (shared/format-outcome-price "0.81902")))
  (is (= "122 shares" (shared/format-outcome-shares "122")))
  (is (shared/outcome-coin? "#270370"))
  (is (not (shared/outcome-coin? "BTC"))))

(def ^:private balances
  {:clearinghouse-state
   {:balances [{:coin "+270370" :token 102703700 :total "122" :entryNtl "99.9204"}
               {:coin "+270371" :token 102703710 :total "40" :entryNtl "7.4"}]}})

(deftest rows-carry-bid-after-fee-values-and-group-into-events_test
  (let [rows (projections/build-outcome-rows
              balances catalog
              {:orderbooks {"#270370" {:bids [{:px "0.81886" :sz "500"}] :asks [{:px "0.82" :sz "10"}]}}})
        [det was] rows
        groups (outcome-projections/group-by-event rows)
        g (first groups)]
    (is (= 0.81886 (:bid-price det)))
    (is (= :bid (:value-basis det)))
    (is (= :mark (:value-basis was)))
    (is (< (js/Math.abs (- 99.76 (:value-after-fees det))) 0.01))
    (is (< (js/Math.abs (- 121.83 (:if-right det))) 0.01))
    (is (= "USDC" (:quote det)))
    (is (= "DET" (:position-label det)))
    (is (= 1 (count groups)))
    (is (= 40 (get-in g [:pair :pairs])))
    (is (< (js/Math.abs (- 39.94 (get-in g [:pair :merge :net]))) 0.01))))

(deftest account-value-counts-shares-at-the-bid-test
  (let [rows (projections/build-outcome-rows
              balances catalog
              {:orderbooks {"#270370" {:bids [{:px "0.8" :sz "500"}]}}})]
    (is (< (js/Math.abs (- (+ 97.6 8.0) (projections/outcome-shares-value rows))) 0.001)))
  (is (nil? (projections/outcome-shares-value []))))

(deftest account-equity-shares-metric-test
  (let [state {:spot balances
               :asset-selector {:market-by-key catalog}
               :orderbooks {"#270370" {:bids [{:px "0.8" :sz "500"}]}
                            "#270371" {:bids [{:px "0.2" :sz "500"}]}}}]
    (is (= 105.6 (equity-metrics/account-equity-outcome-shares state)))
    (is (nil? (equity-metrics/account-equity-outcome-shares {})))))

(deftest search-finds-company-names-test
  (is (query/matches-search? {:symbol "xyz:NVDA" :base "NVDA" :market-type :perp} "nvidia" false))
  (is (query/matches-search? {:title "NVIDIA above $200" :outcome-subject "xyz:NVDA" :market-type :outcome}
                             "nvidia" false))
  (is (not (query/matches-search? {:symbol "BTC" :base "BTC"} "nvidia" false))))
