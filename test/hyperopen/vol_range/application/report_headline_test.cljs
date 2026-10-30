(ns hyperopen.vol-range.application.report-headline-test
  "The report view model's headline (HO-SPEC-014 v2.1 sections 12.3, 12.4,
  12.6, 12.7): the three figures in USDC, the benchmark and exact window,
  each suppression message, and All tracked markets with the covered
  subset. Synthetic fixtures only."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.vol-range-report-contracts :as contracts]
            [hyperopen.vol-range.application.fill-records :as fill-records]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-headline :as headline]
            [hyperopen.vol-range.application.report-model :as model]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def account "0x1234567890abcdef1234567890abcdef12345678")
(def hour fx/hour)
(def day (* 24 hour))
(def h0 (* hour (quot fx/t0 hour)))
(def now (+ h0 (* 3 hour) 300000))
(def b (+ h0 (* 3 hour)))
(def a7 (- b (* 7 day)))

(defn- scope [& args] (assoc (apply fx/scope args) :account-address account))

(defn- campaign [{:keys [id at sc bbo]}]
  (fx/campaign-record {:campaign-id id :at at :scope* sc :bbo* bbo}))

(def vrt (campaign {:id "cmp-vrt" :at fx/t0 :sc (scope)}))
(def vrt-old (campaign {:id "cmp-vrt" :at (- fx/t0 (* 8 day)) :sc (scope)}))
(def vrs-old (campaign {:id "cmp-vrs" :at (- fx/t0 (* 8 day)) :sc (scope "@9002" 9002 "VRS")
                        :bbo {:bid "9.99" :ask "10.01" :observed-at-ms fx/t0}}))

(def grids [(fx/grid {:scope* (scope)})
            (fx/grid {:grid-id "grid-vrs" :scope* (scope "@9002" 9002 "VRS") :oids ["7101" "7102"]})])

(def vrs-sell (fx/fill-row {:coin "@9002" :side "A" :px "11" :sz "10" :time (+ fx/t0 (* 2 hour)) :tid 701
                            :oid 7102}))

(defn- fill [row]
  (fill-records/normalize-fill {:network fx/network :account-address account :row row :source :rest
                                :received-at-ms (+ (:time row) 300)}))

(defn- cov [stream token complete failures]
  (assoc (fx/coverage-record stream token complete failures) :account-address account))

(def wide [[(- fx/t0 (* 9 day)) (+ b hour)]])

(def default-marks
  {"@9001" {:closes {(+ h0 (* 2 hour)) "70" (- a7 hour) "80"} :covered [[(- a7 day) (+ b day)]]}
   "@9002" {:closes {(- b hour) "12" (- a7 hour) "10"} :covered [[(- a7 day) (+ b day)]]}})

(defn state
  [{:keys [campaigns rows coverage marks ui failures]
    :or {campaigns [vrt] rows [fx/buy-7-row] marks default-marks ui {}}}]
  (let [coverage (or coverage {"9001" wide "9002" wide})]
    {:wallet {:address account}
     :spot {:meta fx/spot-meta}
     :vol-range {:report {:account-address account :journal-ok? true :storage-ok? true
                          :campaigns (into {} (map (juxt :campaign-id identity)) campaigns) :closures {}
                          :attribution (attribution/attribution-index grids)
                          :ledger {:fills (into {} (map (fn [r] (let [f (fill r)] [(:fill-key f) f]))) rows)
                                   :updates {}
                                   :coverage {:fills (into {} (for [[t c] coverage] [t (cov :fills t c (get failures t))]))
                                              :ledger (into {} (for [[t c] coverage] [t (cov :ledger t c [])]))}
                                   :balance-checks []}
                          :marks marks
                          :ui ui}}}))

(defn- vm [s] (let [v (model/report-view-model s now)] (is (contracts/view-model-valid? v) (pr-str (:headline v))) v))
(defn- hl [s] (:headline (vm s)))

(deftest one-market-shows-its-three-figures-test
  (let [h (hl (state {}))]
    (is (= :ok (:status h)))
    (is (= "VRT/USDC" (:title h)))
    (is (= {:net-pnl {:label "Net tracked asset P&L" :display "−1,544.80 USDC" :tone :negative}
            :hold-pnl {:label "Hold P&L" :display "−1,500.00 USDC" :tone :negative}
            :contribution {:label "Ladder contribution versus hold" :display "−44.80 USDC" :tone :negative}}
           (:figures h)))
    (is (= "Hold inventory at the selected window start" (:benchmark-label h)))
    (is (= "Oct 21, 13:42 UTC → Oct 21, 16:00 UTC · start valued at the book midpoint" (:range-text h)))
    (is (nil? (:message h)))
    (is (nil? (:no-total h)))
    (is (= {:selected :since-start
            :options [{:id :since-start :label "Since tracking began" :selected? true}
                      {:id :7d :label "7D" :selected? false}
                      {:id :30d :label "30D" :selected? false}]}
           (:window (vm (state {})))))
    (is (not-any? #(re-find #"(?i)\bgrid|equity|%" %)
                  (filter string? (tree-seq coll? seq (vm (state {}))))))))

(deftest usdc-text-test
  (is (= {:display "+2,652.00 USDC" :tone :positive} (headline/usdc-text "2652")))
  (is (= {:display "0.00 USDC" :tone :neutral} (headline/usdc-text "-0.004")))
  (is (= {:display "−0.32 USDC" :tone :negative} (headline/usdc-text "-0.32"))))

(deftest suppression-messages-test
  (testing "7D before tracking"
    (is (= {:status :unavailable :figures nil :range-text nil
            :message "7D needs tracking to have started by Oct 14, 16:00 UTC. Tracking began Oct 21, 13:42 UTC."}
           (select-keys (hl (state {:ui {:window :7d}})) [:status :figures :range-text :message]))))
  (testing "a missing boundary close"
    (let [h (hl (state {:marks {"@9001" {:closes {} :covered [[h0 b]]}}}))]
      (is (= [:unavailable "No completed hourly price for Oct 21, 16:00 UTC, so figures for this window are unavailable."]
             ((juxt :status :message) h)))
      (is (some? (:range-text h)) "the window is still shown")))
  (testing "a break"
    (is (= [:suppressed "Dollar figures are hidden from Oct 21, 14:42 UTC: a VRT trade that wasn't a Vol Range order. Orders, fills and fees are still shown below."]
           ((juxt :status :message) (hl (state {:rows [fx/buy-7-row (assoc fx/manual-row :time (+ fx/t0 hour))]}))))))
  (testing "a gap"
    (let [s (state {:coverage {"9001" [[(- fx/t0 60000) (+ fx/t0 hour)] [(+ fx/t0 (* 2 hour)) (+ b hour)]]}
                    :failures {"9001" [{:from-ms (+ fx/t0 hour) :to-ms (+ fx/t0 (* 2 hour)) :reason :coverage/page-cap
                                        :at-ms 1}]}})]
      (is (= [:suppressed "Fills between Oct 21, 14:42 UTC and Oct 21, 15:42 UTC couldn't be confirmed, so dollar figures for this window are hidden."]
             ((juxt :status :message) (hl s))))))
  (testing "a gap before a 7D window hides its starting inventory, and a retention gap points to new tracking"
    (let [gap-from (+ (:t0-ms vrt-old) hour)
          gap-to (+ (:t0-ms vrt-old) (* 2 hour))
          s (fn [reason]
              (state {:campaigns [vrt-old] :ui {:window :7d}
                      :coverage {"9001" [[(- (:t0-ms vrt-old) 60000) gap-from] [gap-to (+ b hour)]]}
                      :failures {"9001" [{:from-ms gap-from :to-ms gap-to :reason reason :at-ms 1
                                          :permanent? (= :coverage/retention reason)}]}}))]
      (is (< gap-to a7) "the gap lies before the 7D window")
      (is (= [:suppressed "Fills between Oct 13, 14:42 UTC and Oct 13, 15:42 UTC couldn't be confirmed, so the inventory at this window's start isn't known and its dollar figures are hidden."]
             ((juxt :status :message) (hl (s :coverage/page-cap)))))
      (is (= [:suppressed (str "Fills between Oct 13, 14:42 UTC and Oct 13, 15:42 UTC couldn't be confirmed, so the inventory at this window's start isn't known and its dollar figures are hidden."
                               " The venue no longer serves these fills, so dollar figures return only after Start new tracking from now.")]
             ((juxt :status :message) (hl (s :coverage/retention)))))))
  (testing "fills still being checked"
    (is (= [:pending "Checking fills… Figures appear once every fill in this window is confirmed."]
           ((juxt :status :message) (hl (state {:coverage {"9001" [[(- fx/t0 60000) (- b hour)]]}}))))))
  (testing "hourly closes not read yet"
    (is (= [:pending "Loading hourly prices…"] ((juxt :status :message) (hl (state {:marks {}})))))))

(deftest all-tracked-markets-test
  (let [both {:campaigns [vrt-old vrs-old] :rows [fx/buy-7-row vrs-sell]}]
    (testing "7D: both covered, one total"
      (let [h (hl (state (assoc both :ui {:window :7d})))]
        (is (= [:ok "All tracked markets"] ((juxt :status :title) h)))
        (is (= ["−1,474.80 USDC" "−1,420.00 USDC" "−54.80 USDC"]
               (mapv #(get-in h [:figures % :display]) [:net-pnl :hold-pnl :contribution])))
        (is (= "Oct 14, 16:00 UTC → Oct 21, 16:00 UTC · valued at hourly closes" (:range-text h)))))
    (testing "one market not covered: no total, the covered subset is offered and named"
      (let [s (state (assoc both :ui {:window :7d} :coverage {"9001" wide "9002" [[(- fx/t0 (* 9 day)) (- b hour)]]}))
            h (hl s)]
        (is (= [:no-total nil] ((juxt :status :figures) h)))
        (is (= "Totals are hidden because VRS isn't fully covered for this window." (:no-total h)))
        (is (= {:label "Show total for covered markets (VRT)" :active? false} (:subset-offer h)))
        (let [h (hl (assoc-in s [:vol-range :report :ui :covered-subset?] true))]
          (is (= [:ok "Covered markets: VRT"] ((juxt :status :title) h)))
          (is (= "−1,544.80 USDC" (get-in h [:figures :net-pnl :display])))
          (is (= {:label "Show total for covered markets (VRT)" :active? true} (:subset-offer h))))))
    (testing "a market whose hourly close is still loading: loading, not uncovered, and no subset"
      (let [marks (assoc default-marks "@9002" {:closes {(- a7 hour) "10"} :covered [[(- a7 day) (- b hour)]]})
            s (state (assoc both :ui {:window :7d} :marks marks))]
        (doseq [h [(hl s) (hl (assoc-in s [:vol-range :report :ui :covered-subset?] true))]]
          (is (= [:pending "All tracked markets" nil "Loading hourly prices…" nil nil]
                 ((juxt :status :title :figures :message :no-total :subset-offer) h))))))
    (testing "a market without a completed hourly close is named as such"
      (let [marks (assoc default-marks "@9002" {:closes {(- a7 hour) "10"} :covered [[(- a7 day) (+ b day)]]})
            h (hl (state (assoc both :ui {:window :7d} :marks marks)))]
        (is (= [:no-total nil] ((juxt :status :figures) h)))
        (is (= "Totals are hidden because VRS has no completed hourly price for this window." (:no-total h)))
        (is (= {:label "Show total for covered markets (VRT)" :active? false} (:subset-offer h)))))
    (testing "a market whose hourly close couldn't be read"
      (let [marks (assoc default-marks "@9002" {:closes {(- a7 hour) "10"} :covered [[(- a7 day) (- b hour)]]
                                                :error "offline"})
            h (hl (state (assoc both :ui {:window :7d} :marks marks)))]
        (is (= "Totals are hidden because the hourly price for VRS couldn't be loaded. Refresh to try again."
               (:no-total h)))))
    (testing "Since tracking began: no total"
      (let [h (hl (state both))]
        (is (= [:no-total nil "Totals need the same window for every market. Choose 7D or 30D."]
               ((juxt :status :figures :no-total) h)))))
    (testing "one market selected shows it alone"
      (let [h (hl (state (assoc both :ui {:window :7d :selected-campaign-key (:campaign-key vrs-old)})))]
        (is (= [:ok "VRS/USDC" "+70.00 USDC"] [(:status h) (:title h) (get-in h [:figures :net-pnl :display])]))))
    (testing "every market started too recently for 7D"
      (let [h (hl (state {:campaigns [vrt (campaign {:id "cmp-vrs" :at fx/t0 :sc (scope "@9002" 9002 "VRS")})]
                          :ui {:window :7d}}))]
        (is (= :unavailable (:status h)))
        (is (str/starts-with? (:message h) "7D needs tracking to have started by"))
        (is (nil? (:no-total h)))))))
