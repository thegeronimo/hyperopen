(ns hyperopen.vol-range.application.report-model-test
  "The Vol Range report view model (HO-SPEC-014 v2.1 sections 11.3, 12.1,
  12.6): exact keys, the baseline line, Cost basis unavailable, coverage
  states with reasons, execution activity that stays visible across a
  break, empty states and read-only mode. Synthetic fixtures only."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.vol-range-report-contracts :as contracts]
            [hyperopen.vol-range.application.fill-records :as fill-records]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-model :as model]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def account "0x1234567890abcdef1234567890abcdef12345678")
(def t0 fx/t0)
(def hour fx/hour)
(def scope (assoc (fx/scope) :account-address account))
(def c (fx/campaign-record {:scope* scope}))
(def k (:campaign-key c))
(def grid (fx/grid {:scope* scope}))

(defn- fill [row]
  (fill-records/normalize-fill {:network fx/network :account-address account :row row :source :rest
                                :received-at-ms (+ (:time row) 300)}))

(defn- cov [stream complete]
  (assoc (fx/coverage-record stream "9001" complete []) :account-address account
         :record-id (str "coverage:" account "|" (name stream) "|9001")))

(def covered [[(- t0 5000 60000) (+ t0 (* 5 hour))]])

(defn state
  [{:keys [rows coverage closures ui wallet extra-campaigns]
    :or {rows [fx/buy-7-row] coverage covered wallet account}}]
  (let [fills (into {} (map (fn [r] (let [f (fill r)] [(:fill-key f) f]))) rows)]
    {:wallet {:address wallet}
     :spot {:meta fx/spot-meta}
     :vol-range {:report {:account-address account :journal-ok? true :storage-ok? true
                          :campaigns (merge {"cmp-vrt" c} extra-campaigns) :closures (or closures {})
                          :attribution (attribution/attribution-index [grid])
                          :ledger {:fills fills :updates {}
                                   :coverage {:fills (if coverage {"9001" (cov :fills coverage)} {})
                                              :ledger (if coverage {"9001" (cov :ledger coverage)} {})}
                                   :balance-checks []}
                          :ui (or ui {})}}}))

(def now (+ t0 (* 6 hour)))

(defn- vm [s & [at]] (model/report-view-model s (or at now)))
(defn- only [v] (first (:campaigns v)))

(defn- strings [x]
  (cond (string? x) [x] (map? x) (mapcat strings (vals x)) (coll? x) (mapcat strings x) :else []))

(deftest the-view-model-has-exact-keys-test
  (doseq [s [(state {}) (state {:coverage nil}) (state {:rows [fx/buy-7-row fx/manual-row]})
             (assoc-in (state {}) [:vol-range :report :campaigns] {})
             (assoc (state {}) :wallet {})]]
    (is (contracts/view-model-valid? (vm s)) (pr-str (vm s)))))

(deftest a-tracked-campaign-shows-its-baseline-coverage-and-activity-test
  (let [v (vm (state {}))
        row (only v)]
    (is (= "Tracking began Oct 21, 13:42 UTC with 150 VRT at 80.00 USDC (book midpoint)." (:baseline-text row)))
    (is (= "Cost basis unavailable" (:cost-basis-text row)))
    (is (= "VRT/USDC" (:market row)))
    (is (true? (:open? row)))
    (is (= {:market "VRT/USDC" :fills "1" :bought "7 VRT · 534.80 USDC" :buy-vwap "76.40"
            :sold "0 VRT · 0.00 USDC" :sell-vwap "—" :maker-taker "1 / 0" :fees "0 USDC"}
           (:activity row)))
    (is (= [:complete "Complete" "✓"] ((juxt :state :label :icon) (:coverage row))))
    (is (= "Fills checked through Oct 21, 18:42 UTC." (get-in row [:coverage :detail])))
    (is (= ["Oct 21, 13:41 UTC → Oct 21, 18:42 UTC: Complete"] (mapv :text (get-in row [:coverage :intervals]))))
    (is (= {:state :complete :label "Complete" :icon "✓"} (:coverage v)))
    (is (= {:visible? true :confirming? false :busy? false} (:restart row)))
    (is (nil? (:empty-message v)))
    (testing "no dollar figures yet, and never grid, equity or a percentage"
      (is (= {:status :unavailable :message nil} (:headline v)))
      (is (not-any? #(re-find #"(?i)\bgrid|equity|%|P&L" %) (strings v)) (pr-str (strings v))))))

(deftest coverage-states-test
  (testing "never read: checking"
    (let [row (only (vm (state {:coverage nil})))]
      (is (= [:partial "Checking"] ((juxt :state :label) (:coverage row))))
      (is (= "Checking fills…" (get-in row [:coverage :detail])))))
  (testing "an interval within one minute shows seconds"
    (let [row (only (vm (state {:coverage [[(- t0 65000) (- t0 2000)]]}) (+ t0 20000)))]
      (is (= ["Oct 21 13:41:55 UTC → 13:41:58 UTC: Complete"] (mapv :text (get-in row [:coverage :intervals]))))))
  (testing "a gap names its reason"
    (let [s (assoc-in (state {:coverage [[(- t0 65000) (+ t0 hour)] [(+ t0 (* 2 hour)) (+ t0 (* 5 hour))]]})
                      [:vol-range :report :ledger :coverage :fills "9001" :failures]
                      [{:from-ms (+ t0 hour) :to-ms (+ t0 (* 2 hour)) :reason :coverage/page-cap :at-ms 1}])
          row (only (vm s))]
      (is (= :gap (get-in row [:coverage :state])))
      (is (some #(= "Oct 21, 14:42 UTC → Oct 21, 15:42 UTC: Fills couldn't be confirmed (too many fills to read at once)"
                    (:text %))
                (get-in row [:coverage :intervals])))))
  (testing "outside activity breaks attribution but activity stays"
    (let [v (vm (state {:rows [fx/buy-7-row fx/manual-row]}))
          row (only v)]
      (is (= [:attribution-break "Outside activity" "⚠"] ((juxt :state :label :icon) (:coverage row))))
      (is (= "Outside activity from Oct 21, 16:42 UTC: a VRT trade that wasn't a Vol Range order."
             (get-in row [:coverage :detail])))
      (is (some #(str/ends-with? (:text %) "Outside activity: a VRT trade that wasn't a Vol Range order")
                (get-in row [:coverage :intervals])))
      (is (= "1" (get-in row [:activity :fills])) "the tracked fill is still shown")
      (is (= :attribution-break (get-in v [:coverage :state])))))
  (testing "another pair names the pair"
    (let [row (only (vm (state {:rows [fx/other-pair-row]})))]
      (is (= "Outside activity from Oct 21, 16:42 UTC: a VRT trade in VRT/VRS."
             (get-in row [:coverage :detail])))))
  (testing "a stored fill that no longer decodes is a gap, never complete"
    (let [id (str "fill:" (:fill-key (fill fx/manual-row)))
          row (only (vm (assoc-in (state {}) [:vol-range :report :unreadable] [id])))]
      (is (= :gap (get-in row [:coverage :state])))
      (is (some #(str/ends-with? (:text %) "Fills couldn't be confirmed (some saved fill records in this browser can't be read)")
                (get-in row [:coverage :intervals])))))
  (testing "an unreadable ledger is unavailable"
    (let [row (only (vm (assoc-in (state {}) [:vol-range :report :storage-ok?] false)))]
      (is (= :unavailable (get-in row [:coverage :state])))
      (is (= "Unavailable: this browser's fill ledger is unavailable." (get-in row [:coverage :detail]))))))

(deftest the-headline-says-when-figures-can-first-appear-test
  (is (= {:status :pending :message "Figures appear after the first completed hourly close (14:00 UTC)."}
         (:headline (vm (state {}) (+ t0 (* 10 60000))))))
  (is (= {:status :pending :message "Figures appear after the first completed hourly close (14:00 UTC)."}
         (:headline (vm (state {}) (+ t0 (* 18 60000) 30000))))
      "14:00:30 is still inside the one-minute settle")
  (is (= {:status :unavailable :message nil} (:headline (vm (state {}) (+ t0 (* 20 60000))))))
  (is (= :unavailable (get-in (vm (state {:closures {"cmp-vrt" {:ended-at-ms (+ t0 1000)}}}) (+ t0 60000))
                              [:headline :status]))
      "nothing pending without open tracking"))

(deftest markets-and-selection-test
  (let [vrs (fx/vrs-campaign)
        vrs (-> vrs (assoc-in [:scope :account-address] account)
                (assoc :campaign-key (str "hyperliquid-mainnet|" account "|spot|9002|9002")))
        s (state {:extra-campaigns {"cmp-vrs" vrs}})
        v (vm s)]
    (is (= [["all" "All tracked markets" true]
            [k "VRT/USDC" false]
            [(:campaign-key vrs) "VRS/USDC" false]]
           (sort-by #(case (second %) "All tracked markets" 0 "VRT/USDC" 1 2)
                    (mapv (juxt :id :label :selected?) (get-in v [:markets :options])))))
    (is (= 2 (count (:campaigns v))))
    (let [one (vm (assoc-in s [:vol-range :report :ui :selected-campaign-key] k))]
      (is (= [k] (mapv :campaign-key (:campaigns one))))
      (is (= k (get-in one [:markets :selected]))))
    (is (= 2 (count (:campaigns (vm (assoc-in s [:vol-range :report :ui :selected-campaign-key] "gone")))))
        "an unknown selection shows all markets")))

(deftest closed-campaigns-and-restart-controls-test
  (let [closed (vm (state {:closures {"cmp-vrt" {:campaign-id "cmp-vrt" :ended-at-ms (+ t0 (* 3 hour))}}}))
        row (only closed)]
    (is (false? (:open? row)))
    (is (= "Ended Oct 21, 16:42 UTC when new tracking started." (:ended-text row)))
    (is (= "VRT/USDC, until Oct 21, 16:42 UTC" (get-in row [:activity :market]))
        "an ended tracking period is told apart from the current one")
    (is (false? (get-in row [:restart :visible?])) "only open tracking restarts"))
  (is (true? (get-in (only (vm (state {:ui {:confirm-restart-key k}}))) [:restart :confirming?])))
  (testing "read-only accounts never restart"
    (let [s (-> (state {})
                (assoc :account-context {:spectate-mode {:active? true :address account}}))
          v (vm s)]
      (is (true? (:read-only? v)))
      (is (false? (get-in (only v) [:restart :visible?]))))))

(deftest empty-states-test
  (is (= "Connect a wallet to see Vol Range tracking." (:empty-message (vm (assoc (state {}) :wallet {})))))
  (is (= "Loading Vol Range tracking…"
         (:empty-message (vm (state {:wallet "0x9999999999999999999999999999999999999999"}))))
      "a report loaded for another account is not shown")
  (is (= [] (:campaigns (vm (state {:wallet "0x9999999999999999999999999999999999999999"})))))
  (is (= "No Vol Range tracking for this account in this browser yet. Tracking starts when you place your first ladder."
         (:empty-message (vm (assoc-in (state {}) [:vol-range :report :campaigns] {})))))
  (is (= "Couldn't read Vol Range tracking from this browser's storage."
         (:empty-message (vm (-> (state {})
                                 (assoc-in [:vol-range :report :campaigns] {})
                                 (assoc-in [:vol-range :report :journal-ok?] false)))))))

(deftest status-messages-test
  (let [msg (fn [last] (:status-message (vm (assoc-in (state {}) [:vol-range :report :last] last))))]
    (is (= "Report updated." (msg {:op :backfill :status :ok})))
    (is (= "Couldn't load fills: Fill page cap reached." (msg {:op :backfill :status :incomplete
                                                               :error "Fill page cap reached"}))
        "a backfill that confirmed less than it read never says the report was updated")
    (is (= "New tracking started." (msg {:op :start-tracking :status :ok})))
    (is (= "Couldn't load fills: Spot metadata unavailable." (msg {:op :backfill :status :error
                                                                  :error "Spot metadata unavailable"})))
    (is (= "An order placement in this market is still being checked. Try again once it settles."
           (msg {:op :start-tracking :status :refused :errors [:campaign/unresolved-attempt]})))
    (is (re-find #"in progress" (msg {:op :start-tracking :status :busy})))
    (is (nil? (msg nil)))))

(deftest the-model-is-memoized-test
  (let [s (state {})]
    (is (identical? (vm s now) (vm s (+ now 1000))) "same inputs within the minute")
    (is (not (identical? (vm s now) (vm (assoc-in s [:vol-range :report :ui :confirm-restart-key] k) now))))))
