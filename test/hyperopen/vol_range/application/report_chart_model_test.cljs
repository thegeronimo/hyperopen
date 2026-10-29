(ns hyperopen.vol-range.application.report-chart-model-test
  "The cumulative P&L chart model (HO-SPEC-014 v2.1 section 12.4):
  checkpoints are hourly, or daily for a Since tracking began window longer
  than 30 days; missing closes are gaps that split each series into
  segments; an unconfirmed interval or a break ends the series; the last
  checkpoint equals the headline; All tracked markets sums its markets; no
  label says equity. Synthetic fixtures only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.vol-range-report-contracts :as contracts]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-chart-model :as chart]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.application.report-windows :as windows]
            [hyperopen.vol-range.domain.report-ledger :as ledger]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def hour fx/hour)
(def day (* 24 hour))
(def h0 (* hour (quot fx/t0 hour)))
(def markets (attribution/spot-markets fx/spot-meta))
(def at-3h (+ h0 (* 3 hour) 300000))

(defn- entry [closes] {:closes closes :covered [[(- h0 (* 40 day)) (+ h0 (* 40 day))]]})

(defn- flat [from to close]
  (entry (into {} (for [t (range from to hour)] [t close]))))

(defn item
  "One campaign item as the report model builds it."
  [{:keys [campaign rows updates window now-ms coin-marks covered-to]
    :or {campaign (fx/campaign-record) updates [] window :since-start}}]
  (let [found (attribution/campaign-findings {:campaign campaign :fills (map fx/fill rows) :updates updates
                                              :markets markets :index (attribution/attribution-index [(fx/grid)])})
        rng (windows/resolve-window {:window window :campaign campaign :now-ms now-ms})
        cov (when (= :ok (:status rng))
              (coverage/campaign-coverage
               {:campaign campaign
                :coverage {:fills (fx/coverage-record :fills "9001" [[(- (:t0-ms campaign) 60000)
                                                                     (or covered-to (+ (:b-ms rng) hour))]] [])
                           :ledger (fx/coverage-record :ledger "9001" [[(- (:t0-ms campaign) 60000)
                                                                       (+ (:b-ms rng) hour)]] [])}
                :breaks (:breaks found) :until-ms (:b-ms rng)}))]
    {:campaign campaign :events (:events found) :coverage cov :coin-marks coin-marks
     :result (windows/campaign-window-figures {:campaign campaign :range rng :events (:events found)
                                               :coverage cov :coin-marks coin-marks})}))

(defn- valid [c] (is (contracts/view-model-valid? {:enabled? true :read-only? false :loading? false
                                                   :account-address nil
                                                   :window {:selected :since-start
                                                            :options (for [id [:since-start :7d :30d]]
                                                                       {:id id :label "x" :selected? false})}
                                                   :markets {:selected nil :options []} :coverage nil
                                                   :headline {:status :unavailable :title nil :figures nil
                                                              :benchmark-label nil :range-text nil :message nil
                                                              :no-total nil :subset-offer nil}
                                                   :chart c :matched nil :campaigns [] :status-message nil
                                                   :empty-message nil})
                     (pr-str c))
  c)

(deftest checkpoints-are-hourly-or-daily-test
  (let [{:keys [cadence ks]} (chart/checkpoints fx/t0 (+ h0 (* 3 hour)))]
    (is (= :hourly cadence))
    (is (= [(+ h0 hour) (+ h0 (* 2 hour)) (+ h0 (* 3 hour))] ks) "a < k <= b"))
  (let [b (+ h0 (* 30 day))]
    (is (= [:hourly 720] ((juxt :cadence (comp count :ks)) (chart/checkpoints (- b (* 30 day)) b))) "30D stays hourly"))
  (testing "Since tracking began older than 30 days: one per UTC day, then b"
    (let [b (+ h0 (* 31 day) (* 3 hour))
          {:keys [cadence ks]} (chart/checkpoints fx/t0 b)
          midnight (* day (inc (quot fx/t0 day)))]
      (is (= :daily cadence))
      (is (= midnight (first ks)))
      (is (every? #(zero? (mod % day)) (butlast ks)))
      (is (= b (last ks)))
      (is (= 32 (count ks)) "31 midnights and b"))))

(deftest states-at-agrees-with-state-before-test
  (let [c (fx/campaign-record)
        events (:events (item {:rows [fx/buy-7-row fx/base-fee-row fx/sell-30-row] :now-ms at-3h}))
        ks [(+ fx/t0 hour) (+ fx/t0 hour 1) (+ fx/t0 (* 2 hour)) (+ fx/t0 (* 2 hour) 1) (+ fx/t0 (* 5 hour))]]
    (is (= (mapv #(ledger/state-before c events %) ks) (chart/states-at c events ks)))))

(deftest a-missing-close-is-a-gap-and-the-end-is-the-headline-test
  ;; closes 80, 70 and 100 for the hours opening 14:00, 15:00 and 16:00 UTC; none for 13:00
  (let [it (item {:rows [fx/buy-7-row] :now-ms at-3h
                  :coin-marks (entry {(+ h0 hour) "80" (+ h0 (* 2 hour)) "70"})})
        c (valid (chart/chart-view {:items [it]}))]
    (is (= :ok (get-in it [:result :status])))
    (is (= :hourly (:cadence c)))
    (testing "origin, a gap at 14:00, then 15:00 and 16:00"
      (is (= ["Oct 21, 13:42 UTC" "Oct 21, 15:00 UTC" "Oct 21, 16:00 UTC"] (mapv :time-text (:tooltips c))))
      (is (= [:net-0 :net-1 :hold-0 :hold-1] (mapv :id (:series c))))
      (is (= 2 (count (:points (first (:series c))))) "a lone point is drawn as a dot, never stretched")
      (is (= (first (:points (first (:series c)))) (second (:points (first (:series c)))))))
    (testing "the last checkpoint equals the headline figures"
      (is (= {:display "−1,544.80 USDC" :tone :negative} (:net (peek (:tooltips c)))))
      (is (= {:display "−1,500.00 USDC" :tone :negative} (:hold (peek (:tooltips c)))))
      (is (= "-1544.8" (get-in it [:result :figures :net-pnl]))))
    (is (= {:display "0.00 USDC" :tone :neutral} (:net (first (:tooltips c)))) "starts at zero")
    (is (= [0 1] [(:x-ratio (first (:points c))) (:x-ratio (peek (:points c)))]))
    (is (nil? (:end-text c)))
    (is (= "Cumulative P&L (USDC)" (:title c)))
    (is (= ["Net tracked asset P&L" "Hold P&L"] (mapv :label (:legend c))))
    (is (not (re-find #"(?i)equity|%|\bgrid" (pr-str c))))))

(deftest the-series-ends-at-a-break-test
  (let [updates (attribution/ledger-update-records {:markets markets :tracked #{"9001"} :account-address fx/account
                                                    :row fx/spot-transfer-row :now-ms (+ fx/t0 (* 5 hour))})
        it (item {:rows [fx/buy-7-row] :updates updates :now-ms (+ at-3h (* 3 hour))
                  :coin-marks (flat h0 (+ h0 (* 8 hour)) "70")})
        c (valid (chart/chart-view {:items [it]}))]
    (is (= :suppressed (get-in it [:result :status])) "the headline is hidden from the transfer")
    (is (= "Oct 21, 17:00 UTC" (:time-text (peek (:tooltips c)))) "the last checkpoint before 17:42")
    (is (= "The lines end at Oct 21, 17:00 UTC: later fills or prices aren't confirmed." (:end-text c)))
    (is (< (:x-ratio (peek (:points c))) 1))))

(deftest an-unconfirmed-interval-ends-the-series-test
  (let [c (chart/chart-view {:items [(item {:rows [fx/buy-7-row] :now-ms at-3h :covered-to (+ h0 (* 2 hour) 1800000)
                                            :coin-marks (flat h0 (+ h0 (* 4 hour)) "70")})]})]
    (is (= "Oct 21, 15:00 UTC" (:time-text (peek (:tooltips c)))))
    (is (some? (:end-text c))))
  (testing "nothing confirmed past the start draws no chart"
    (is (nil? (chart/chart-view {:items [(item {:rows [fx/buy-7-row] :now-ms at-3h :covered-to (+ h0 hour -1)
                                                :coin-marks (flat h0 (+ h0 (* 4 hour)) "70")})]})))))

(deftest no-chart-without-a-resolved-window-test
  (is (nil? (chart/chart-view {:items []})))
  (is (nil? (chart/chart-view {:items [(item {:rows [fx/buy-7-row] :window :7d :now-ms at-3h
                                              :coin-marks (flat h0 (+ h0 (* 4 hour)) "70")})]}))
      "7D before tracking")
  (is (nil? (chart/chart-view {:items [(item {:rows [fx/buy-7-row] :now-ms (+ fx/t0 60000)})]}))
      "before the first completed hour"))

(deftest all-tracked-markets-sums-each-checkpoint-test
  (let [a (item {:rows [fx/buy-7-row] :now-ms at-3h :coin-marks (flat h0 (+ h0 (* 4 hour)) "70")})
        b (assoc a :coin-marks (entry {(+ h0 hour) "70" (+ h0 (* 2 hour)) "70"}))
        both (chart/chart-view {:items [a a]})
        one (chart/chart-view {:items [a]})]
    (is (= {:display "−3,089.60 USDC" :tone :negative} (:net (peek (:tooltips both)))))
    (is (= (mapv :y-ratio (:points one)) (mapv :y-ratio (:points both))) "the same shape, twice the values")
    (testing "a checkpoint only when every market has it"
      (is (= ["Oct 21, 13:42 UTC" "Oct 21, 15:00 UTC" "Oct 21, 16:00 UTC"]
             (mapv :time-text (:tooltips (chart/chart-view {:items [a b]}))))))))
