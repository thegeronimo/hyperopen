(ns hyperopen.vol-range.application.report-aggregate-test
  "All tracked markets (HO-SPEC-014 v2.1 section 12.7, 13.5 row 9): two
  disjoint assets sum their own cash-flow contributions once and never the
  account's USDC balance; one uncovered campaign suppresses the total and
  the covered subset is offered and named; Since tracking began has no
  total. Synthetic fixtures only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.report-aggregate :as aggregate]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.application.report-windows :as windows]
            [hyperopen.vol-range.domain.report-equations :as eq]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def hour fx/hour)
(def day (* 24 hour))
(def h0 (* hour (quot fx/t0 hour)))
(def now (+ h0 (* 3 hour) 300000))
(def b (+ h0 (* 3 hour)))
(def a (- b (* 7 day)))
(def markets (attribution/spot-markets fx/spot-meta))

(def vrt (fx/campaign-record {:at (- fx/t0 (* 8 day))}))
(def vrs (fx/campaign-record {:campaign-id "cmp-vrs" :at (- fx/t0 (* 8 day)) :scope* (fx/scope "@9002" 9002 "VRS")
                              :bbo* {:bid "9.99" :ask "10.01" :observed-at-ms fx/t0}}))
(def index (attribution/attribution-index
            [(fx/grid) (fx/grid {:grid-id "grid-vrs" :scope* (fx/scope "@9002" 9002 "VRS") :oids ["7101" "7102"]})]))

(def vrs-sell (fx/fill-row {:coin "@9002" :side "A" :px "11" :sz "10" :time (+ fx/t0 (* 2 hour)) :tid 701
                            :oid 7102}))

(def marks
  {"@9001" {:closes {(- a hour) "80" (- b hour) "70"} :covered [[(- a day) (+ b day)]]}
   "@9002" {:closes {(- a hour) "10" (- b hour) "12"} :covered [[(- a day) (+ b day)]]}})

(defn- item
  [campaign rows & [{:keys [covered-to window] :or {window :7d}}]]
  (let [token (get-in campaign [:scope :base-token-id])
        found (attribution/campaign-findings {:campaign campaign :fills (map fx/fill rows) :updates []
                                              :markets markets :index index})
        rng (windows/resolve-window {:window window :campaign campaign :now-ms now})
        rec (fn [s] (fx/coverage-record s token [[(- (:t0-ms campaign) 60000) (or covered-to (+ b hour))]] []))
        cov (when (= :ok (:status rng))
              (coverage/campaign-coverage {:campaign campaign :coverage {:fills (rec :fills) :ledger (rec :ledger)}
                                           :breaks (:breaks found) :until-ms (:b-ms rng)}))]
    {:symbol (get-in campaign [:scope :symbol])
     :network (get-in campaign [:scope :network])
     :result (windows/campaign-window-figures {:campaign campaign :range rng :events (:events found)
                                               :coverage cov :coin-marks (get marks (get-in campaign [:scope :coin]))})}))

(defn- d [x] (precision/parse-decimal x))

(deftest row-9-two-assets-share-account-usdc-test
  (let [items [(item vrt [fx/buy-7-row]) (item vrs [vrs-sell])]
        agg (aggregate/aggregate {:window :7d :items items})]
    (is (= {:net-pnl "-1544.8" :hold-pnl "-1500" :contribution "-44.8"} (get-in items [0 :result :figures])))
    (is (= {:net-pnl "70" :hold-pnl "80" :contribution "-10"} (get-in items [1 :result :figures])))
    (is (= [:ok :all] ((juxt :status :scope) agg)))
    (is (= {:net-pnl "-1474.8" :hold-pnl "-1420" :contribution "-54.8"} (:figures agg)))
    (is (eq/identity-holds? (:figures agg)))
    (testing "the total contribution is each fill's cash counted once plus the inventory change at P_b"
      (let [cash (precision/add (d "-534.8") (d "110"))
            inventory (precision/add (precision/mul (d "7") (d "70")) (precision/mul (d "-10") (d "12")))]
        (is (zero? (precision/cmp (d (get-in agg [:figures :contribution])) (precision/add cash inventory))))))
    (testing "no USDC balance enters any campaign"
      (is (= ["0" "0"] (mapv #(get-in % [:result :c-a]) items)))
      (is (not-any? #(= "3000" %) (mapcat (comp vals :figures :result) items))))))

(deftest one-uncovered-campaign-suppresses-the-total-test
  (let [items [(item vrt [fx/buy-7-row]) (item vrs [vrs-sell] {:covered-to (- b hour)})]
        agg (aggregate/aggregate {:window :7d :items items})]
    (is (= :pending (get-in items [1 :result :status])))
    (is (= [:no-total :aggregate/incomplete nil] ((juxt :status :reason :figures) agg)))
    (is (= [["VRT"] ["VRS"]] [(:included agg) (:excluded agg)]))
    (is (true? (:subset-offer? agg)))
    (testing "the explicitly chosen covered subset is summed and named"
      (let [subset (aggregate/aggregate {:window :7d :items items :covered-subset? true})]
        (is (= [:ok true ["VRT"] ["VRS"]] ((juxt :status :subset? :included :excluded) subset)))
        (is (= {:net-pnl "-1544.8" :hold-pnl "-1500" :contribution "-44.8"} (:figures subset)))))
    (testing "no covered market: nothing to offer"
      (let [none (aggregate/aggregate {:window :7d :items [(assoc-in (first items) [:result :status] :suppressed)
                                                           (second items)]})]
        (is (false? (:subset-offer? none)))))))

(deftest a-pending-or-missing-close-is-not-a-coverage-gap-test
  (let [covered (item vrt [fx/buy-7-row])
        priced (item vrs [vrs-sell])
        with-mark (fn [status reason] (update priced :result assoc :status status :reason reason :figures nil))]
    (testing "a close still loading: no total yet, nothing named, no subset even when chosen"
      (doseq [subset? [false true]]
        (is (= [:pending :marks/pending nil [] false false]
               ((juxt :status :reason :figures :excluded :subset-offer? :subset?)
                (aggregate/aggregate {:window :7d :items [covered (with-mark :pending :marks/pending)]
                                      :covered-subset? subset?}))))))
    (testing "a missing or unreadable close is named as a price problem, not as uncovered"
      (let [agg (aggregate/aggregate {:window :7d :items [covered (with-mark :unavailable :marks/missing-boundary-close)]})]
        (is (= [:no-total :aggregate/incomplete [] ["VRS"] [] true]
               ((juxt :status :reason :excluded :unpriced :price-errors :subset-offer?) agg))))
      (let [agg (aggregate/aggregate {:window :7d :items [covered (with-mark :unavailable :marks/read-error)]})]
        (is (= [[] [] ["VRS"]] ((juxt :excluded :unpriced :price-errors) agg)))))))

(deftest since-tracking-began-has-no-total-test
  (let [items [(item vrt [fx/buy-7-row] {:window :since-start}) (item vrs [vrs-sell] {:window :since-start})]
        agg (aggregate/aggregate {:window :since-start :items items})]
    (is (every? #(= :ok (get-in % [:result :status])) items) "each market alone has figures")
    (is (= [:no-total :aggregate/different-windows nil] ((juxt :status :reason :figures) agg))))
  (testing "one market is shown as it is"
    (let [one (item vrt [fx/buy-7-row] {:window :since-start})
          agg (aggregate/aggregate {:window :since-start :items [one]})]
      (is (= [:ok :single] ((juxt :status :scope) agg)))
      (is (= (get-in one [:result :figures]) (:figures agg)))))
  (testing "different intervals or networks never sum"
    (let [items [(item vrt [fx/buy-7-row]) (assoc (item vrs [vrs-sell]) :network :hyperliquid-testnet)]]
      (is (= :no-total (:status (aggregate/aggregate {:window :7d :items items}))))))
  (is (= :none (:status (aggregate/aggregate {:window :7d :items []})))))
