(ns hyperopen.vol-range.application.report-windows-test
  "Report windows and window figures (HO-SPEC-014 v2.1 sections 12.3, 12.4,
  12.6; 13.5 rows 1 to 3, 6 and 7): Since tracking began values its start
  at P0; 7D before tracking is unavailable, never clipped; `[a,b)` is
  exact; a fill at `b` belongs to the next window; an external transfer
  hides the figures while activity stays. Synthetic fixtures only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.application.report-windows :as windows]
            [hyperopen.vol-range.domain.report-equations :as eq]
            [hyperopen.vol-range.domain.report-marks :as marks]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def hour fx/hour)
(def day (* 24 hour))
(def h0 (* hour (quot fx/t0 hour)))
(def markets (attribution/spot-markets fx/spot-meta))

(defn- closes-entry
  "Stored marks with `closes` ({open close}) proven over a wide span."
  [closes]
  {:closes closes :covered [[(- h0 (* 40 day)) (+ h0 (* 40 day))]]})

(def fixture-marks (closes-entry (marks/normalize-candles fx/candles (+ h0 (* 10 hour)))))

(defn- cov-records [from to]
  {:fills (fx/coverage-record :fills "9001" [[from to]] [])
   :ledger (fx/coverage-record :ledger "9001" [[from to]] [])})

(defn figures-for
  "Window figures of `campaign` with fills `rows` and ledger `updates`,
  coverage proven over [T0 - 1 min, `covered-to`)."
  [{:keys [campaign rows updates window now-ms range coin-marks covered-to]
    :or {campaign (fx/campaign-record) updates [] coin-marks fixture-marks}}]
  (let [found (attribution/campaign-findings {:campaign campaign :fills (map fx/fill rows) :updates updates
                                              :markets markets :index (attribution/attribution-index [(fx/grid)])})
        rng (or range (windows/resolve-window {:window window :campaign campaign :now-ms now-ms}))
        cov (when (= :ok (:status rng))
              (coverage/campaign-coverage {:campaign campaign
                                           :coverage (cov-records (- (:t0-ms campaign) 60000)
                                                                  (or covered-to (+ (:b-ms rng) hour)))
                                           :breaks (:breaks found) :until-ms (:b-ms rng)}))
        r (windows/campaign-window-figures {:campaign campaign :range rng :events (:events found)
                                            :coverage cov :coin-marks coin-marks})]
    (when (:figures r) (is (eq/identity-holds? (:figures r))))
    r))

(def ^:private at-3h (+ h0 (* 3 hour) 300000))

(deftest thirteen-five-rows-since-tracking-began-test
  (testing "row 1: buy 7 at 76.4, mark 70"
    (let [r (figures-for {:rows [fx/buy-7-row] :window :since-start :now-ms at-3h})]
      (is (= :ok (:status r)))
      (is (= [fx/t0 (+ h0 (* 3 hour))] [(:a-ms r) (:b-ms r)]))
      (is (= {:net-pnl "-1544.8" :hold-pnl "-1500" :contribution "-44.8"} (:figures r)))
      (is (= {:status :ok :price "80" :source :inception-midpoint :observed-at-ms (+ fx/t0 120)} (:a-mark r)))
      (is (= {:status :ok :price "70" :source :hourly-close :candle-open-ms (+ h0 (* 2 hour))} (:b-mark r)))
      (is (= ["150" "0" "157" "-534.8"] ((juxt :q-a :c-a :q-b :c-b) r)))
      (is (= 1 (get-in r [:activity :fills])))))
  (testing "row 2: sell 30 for 2,652, mark 100"
    (is (= {:net-pnl "2652" :hold-pnl "3000" :contribution "-348"}
           (:figures (figures-for {:rows [fx/sell-30-row] :window :since-start :now-ms (+ at-3h hour)})))))
  (testing "row 3: buy 10 at 80 with a 0.004 base fee, mark 80"
    (let [r (figures-for {:rows [fx/base-fee-row] :window :since-start :now-ms (- at-3h hour)})]
      (is (= "159.996" (:q-b r)))
      (is (= {:net-pnl "-0.32" :hold-pnl "0" :contribution "-0.32"} (:figures r))))))

(deftest windows-resolve-exactly-test
  (testing "Since tracking began before the first completed hour"
    (let [r (windows/resolve-window {:window :since-start :campaign (fx/campaign-record) :now-ms (+ fx/t0 60000)})]
      (is (= [:pending :window/no-completed-mark] ((juxt :status :reason) r)))))
  (testing "7D and 30D before tracking are unavailable, not clipped"
    (doseq [[w days] [[:7d 7] [:30d 30]]]
      (let [r (windows/resolve-window {:window w :campaign (fx/campaign-record) :now-ms at-3h})]
        (is (= [:unavailable :window/before-tracking] ((juxt :status :reason) r)))
        (is (= (+ h0 (* 3 hour)) (:b-ms r)))
        (is (= (- (+ h0 (* 3 hour)) (* days day)) (:a-ms r))))))
  (testing "strings and unknown windows"
    (is (= :7d (windows/normalize-window "7d")))
    (is (nil? (windows/normalize-window "1y")))
    (is (= :since-start (windows/selected-window {:window "nonsense"})))))

(deftest a-fixed-window-reconstructs-its-starting-inventory-test
  (let [campaign (fx/campaign-record {:at (- fx/t0 (* 8 day))})
        b (+ h0 (* 3 hour))
        a (- b (* 7 day))
        entry (closes-entry {(- a hour) "80" (- b hour) "70"})
        r (figures-for {:campaign campaign :rows [fx/buy-7-row] :window :7d :now-ms at-3h :coin-marks entry})]
    (is (= [:ok a b] ((juxt :status :a-ms :b-ms) r)))
    (is (= :hourly-close (get-in r [:a-mark :source])))
    (is (= {:net-pnl "-1544.8" :hold-pnl "-1500" :contribution "-44.8"} (:figures r)))
    (testing "a missing boundary close is unavailable, never carried forward"
      (let [r (figures-for {:campaign campaign :rows [fx/buy-7-row] :window :7d :now-ms at-3h
                            :coin-marks (closes-entry {(- b hour) "70"})})]
        (is (= [:unavailable :marks/missing-boundary-close nil] ((juxt :status :reason :figures) r)))
        (is (= :missing (get-in r [:a-mark :status])))))
    (testing "an unread close is pending"
      (is (= [:pending :marks/pending]
             ((juxt :status :reason) (figures-for {:campaign campaign :rows [fx/buy-7-row] :window :7d
                                                   :now-ms at-3h :coin-marks nil})))))))

(deftest row-6-a-fill-at-b-belongs-to-the-next-window-test
  (let [b (+ h0 (* 3 hour))
        at-b (assoc fx/buy-7-row :time b)
        r (figures-for {:rows [at-b] :window :since-start :now-ms at-3h})]
    (is (= "150" (:q-b r)) "excluded from [a,b)")
    (is (= 0 (get-in r [:activity :fills])))
    (is (= {:net-pnl "-1500" :hold-pnl "-1500" :contribution "0"} (:figures r)))
    (let [next-r (figures-for {:rows [at-b]
                               :range {:window :7d :a-ms b :b-ms (+ b hour) :a-source :hourly-close :status :ok}
                               :covered-to (+ b (* 2 hour))})]
      (is (= ["150" "157"] [(:q-a next-r) (:q-b next-r)]) "included in [b,c)")
      (is (= 1 (get-in next-r [:activity :fills])))
      (is (= {:net-pnl "4665.2" :hold-pnl "4500" :contribution "165.2"} (:figures next-r))))))

(deftest row-7-an-external-transfer-hides-the-figures-test
  (let [updates (attribution/ledger-update-records {:markets markets :tracked #{"9001"} :account-address fx/account
                                                    :row fx/spot-transfer-row :now-ms (+ fx/t0 (* 5 hour))})
        entry (closes-entry {(+ h0 (* 4 hour)) "90" (+ h0 (* 2 hour)) "70"})
        after (figures-for {:rows [fx/buy-7-row] :updates updates :window :since-start
                            :now-ms (+ at-3h (* 2 hour)) :coin-marks entry})]
    (is (= [:suppressed :break/base-transfer nil] ((juxt :status :reason :figures) after)))
    (is (= :attribution-break (get-in after [:coverage :state])))
    (is (= 1 (get-in after [:activity :fills])) "known execution activity stays")
    (testing "a window ending before the transfer stays available"
      (is (= :ok (:status (figures-for {:rows [fx/buy-7-row] :updates updates :window :since-start
                                        :now-ms at-3h})))))))

(deftest coverage-gates-the-figures-test
  (testing "fills not yet confirmed through b"
    (is (= [:pending :coverage/backfill-pending]
           ((juxt :status :reason) (figures-for {:rows [fx/buy-7-row] :window :since-start :now-ms at-3h
                                                 :covered-to (+ h0 (* 2 hour))})))))
  (testing "an untracked same-base trade before b"
    (let [manual (assoc fx/manual-row :time (+ fx/t0 hour))
          r (figures-for {:rows [fx/buy-7-row manual] :window :since-start :now-ms at-3h})]
      (is (= [:suppressed :break/untracked-same-base-trade] ((juxt :status :reason) r))))))

(deftest mark-requests-ask-only-for-missing-hours-test
  (let [c (fx/campaign-record)
        args {:campaigns {"cmp-vrt" c} :closures {} :window :since-start :now-ms at-3h}]
    (is (= [{:coin "@9001" :from-ms h0 :to-ms (+ h0 (* 3 hour))}] (windows/mark-requests args)))
    (is (= [] (windows/mark-requests (assoc args :marks {"@9001" fixture-marks}))))
    (is (= [{:coin "@9001" :from-ms (+ h0 (* 2 hour)) :to-ms (+ h0 (* 3 hour))}]
           (windows/mark-requests (assoc args :marks {"@9001" {:closes {} :covered [[h0 (+ h0 (* 2 hour))]]}}))))
    (is (= [] (windows/mark-requests (assoc args :window :7d))) "nothing for a window before tracking")
    (is (= [] (windows/mark-requests (assoc args :closures {"cmp-vrt" {:ended-at-ms 1}}))) "only open tracking")
    (is (= [] (windows/mark-requests (assoc args :selected-key "other"))))))
