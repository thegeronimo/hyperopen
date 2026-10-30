(ns hyperopen.vol-range.application.report-matched-test
  "The report view model's Gross spread on matched fills and chart
  (HO-SPEC-014 v2.1 sections 12.4, 12.5, 13.5 row 5): the disclosure shows
  99 / 2 / −10 / 91 and reconciles to the headline contribution; it and
  the chart follow the headline's market and window; a hidden headline
  hides the disclosure. Synthetic fixtures only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.vol-range-report-contracts :as contracts]
            [hyperopen.vol-range.application.report-headline-test :as h]
            [hyperopen.vol-range.application.report-model :as model]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def marks-100 {"@9001" {:closes {(- h/b fx/hour) "100"} :covered [[(- h/a7 h/day) (+ h/b h/day)]]}})

(defn- vm [s] (let [v (model/report-view-model s h/now)] (is (contracts/view-model-valid? v) (pr-str (:matched v))) v))

(defn- rows [m] (into {} (map (juxt :id identity)) (get-in m [:markets 0 :rows])))

(deftest thirteen-five-row-5-in-the-report-test
  (let [v (vm (h/state {:rows [fx/matched-buy-row fx/matched-sell-row] :marks marks-100}))
        m (:matched v)
        r (rows m)]
    (is (= "+91.00 USDC" (get-in v [:headline :figures :contribution :display])))
    (is (= {:open? false :title "Gross spread on matched fills"
            :note "Pairs the oldest opposite fills within this window. It isn't a completed cycle or a cost basis and isn't added to the headline."}
           (dissoc m :markets)))
    (is (= "VRT/USDC" (get-in m [:markets 0 :market])))
    (is (= [:matched-qty :gross :unmatched-buys :unmatched-sells :fee-impact :total]
           (mapv :id (get-in m [:markets 0 :rows]))))
    (is (= {:id :matched-qty :label "Matched quantity 9.9 VRT in 1 matched pair" :value nil} (:matched-qty r)))
    (is (= ["Gross matched spread" "+99.00 USDC"] [(get-in r [:gross :label]) (get-in r [:gross :value :display])]))
    (is (= ["Unmatched buys marked at 100.00" "+2.00 USDC"]
           [(get-in r [:unmatched-buys :label]) (get-in r [:unmatched-buys :value :display])]))
    (is (= ["Sold inventory not reaccumulated" "0.00 USDC"]
           [(get-in r [:unmatched-sells :label]) (get-in r [:unmatched-sells :value :display])]))
    (is (= ["Fee and rebate impact at the report mark" "−10.00 USDC"]
           [(get-in r [:fee-impact :label]) (get-in r [:fee-impact :value :display])]))
    (is (= ["= Ladder contribution versus hold" "+91.00 USDC"]
           [(get-in r [:total :label]) (get-in r [:total :value :display])]))
    (is (= "Fees paid: 0 USDC · 0.1 VRT" (get-in m [:markets 0 :fees-paid])))
    (testing "the chart of the same window ends at the headline"
      (is (= "+3,091.00 USDC" (get-in v [:headline :figures :net-pnl :display])))
      (is (= {:display "+3,091.00 USDC" :tone :positive} (:net (peek (get-in v [:chart :tooltips]))))))
    (is (true? (get-in (vm (h/state {:rows [fx/matched-buy-row fx/matched-sell-row] :marks marks-100
                                     :ui {:matched-open? true}}))
                       [:matched :open?])))))

(deftest the-disclosure-follows-the-headline-test
  (testing "a hidden headline hides the matched disclosure; the chart keeps what was confirmed"
    (let [v (vm (h/state {:rows [fx/buy-7-row (assoc fx/manual-row :time (+ fx/t0 (* 2 fx/hour)))]
                          :marks {"@9001" {:closes (into {} (for [t (range h/h0 h/b fx/hour)] [t "70"]))
                                           :covered [[(- h/a7 h/day) (+ h/b h/day)]]}}}))]
      (is (= :suppressed (get-in v [:headline :status])))
      (is (nil? (:matched v)))
      (is (= "Oct 21, 15:00 UTC" (:time-text (peek (get-in v [:chart :tooltips])))))))
  (testing "7D before tracking: neither"
    (let [v (vm (h/state {:ui {:window :7d}}))]
      (is (nil? (:matched v)))
      (is (nil? (:chart v)))))
  (testing "All tracked markets: only the markets of a shown total"
    (let [both {:campaigns [h/vrt-old h/vrs-old] :rows [fx/buy-7-row h/vrs-sell]}
          total (vm (h/state (assoc both :ui {:window :7d})))
          none (vm (h/state both))]
      (is (= ["VRT/USDC" "VRS/USDC"] (mapv :market (get-in total [:matched :markets]))))
      (is (some? (:chart total)))
      (is (= {:display "−1,474.80 USDC" :tone :negative} (:net (peek (get-in total [:chart :tooltips])))))
      (is (nil? (:matched none)) "Since tracking began has no total")
      (is (nil? (:chart none))))))
