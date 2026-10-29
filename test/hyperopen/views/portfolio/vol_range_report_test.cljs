(ns hyperopen.views.portfolio.vol-range-report-test
  "The Portfolio Vol Range tab (HO-SPEC-014 v2.1 section 11.3): its data
  roles render from the view model, it is hidden with reporting off and on
  another trader's portfolio, read-only mode has no Start tracking, and the
  restart needs an explicit inline confirmation."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.config :as config]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vol-range.application.report-headline-test :as headline-test]
            [hyperopen.vol-range.application.report-model :as model]
            [hyperopen.vol-range.application.report-model-test :as model-test]
            [hyperopen.views.portfolio.account-tabs :as account-tabs]
            [hyperopen.views.portfolio.vol-range-report :as view]
            [hyperopen.vol-range.report-fixtures :as fx]))

(defn- render [state] (view/vol-range-report (model/report-view-model state model-test/now)))
(defn- role [node r] (hiccup/find-by-data-role node r))
(defn- text [node r] (some-> (role node r) hiccup/node-text))
(defn- on-click [node r] (get-in (hiccup/node-attrs (role node r)) [:on :click]))

(deftest the-report-renders-its-data-roles-test
  (let [node (render (model-test/state {}))]
    (is (some? (role node "vol-range-report")))
    (doseq [r ["vol-range-report-market" "vol-range-report-refresh" "vol-range-report-status"
               "vol-range-report-coverage" "vol-range-report-coverage-detail" "vol-range-report-activity"
               "vol-range-report-baseline" "vol-range-report-cost-basis" "vol-range-report-start-tracking"]]
      (is (some? (role node r)) r))
    (is (= "Tracking began Oct 21, 13:42 UTC with 150 VRT at 80.00 USDC (book midpoint)."
           (text node "vol-range-report-baseline")))
    (is (= "Cost basis unavailable" (text node "vol-range-report-cost-basis")))
    (is (= "1" (text node "vol-range-report-activity-fills")))
    (is (= [[:actions/refresh-vol-range-report]] (on-click node "vol-range-report-refresh")))
    (is (= [[:actions/request-start-vol-range-tracking model-test/k]]
           (on-click node "vol-range-report-start-tracking")))
    (is (= "Checking fills… Figures appear once every fill in this window is confirmed."
           (text node "vol-range-report-suppressed"))
        "fills not yet confirmed through the window end")
    (is (nil? (role node "vol-range-report-net-pnl")) "no figure while suppressed")
    (is (= "true" (:aria-pressed (hiccup/node-attrs (role node "vol-range-report-window-since-start")))))
    (is (= "false" (:aria-pressed (hiccup/node-attrs (role node "vol-range-report-window-7d")))))
    (is (= [[:actions/select-vol-range-report-window :7d]] (on-click node "vol-range-report-window-7d")))
    (is (= [[:actions/select-vol-range-report-campaign [:event.target/value]]]
           (get-in (hiccup/node-attrs (role node "vol-range-report-market")) [:on :change])))))

(deftest the-headline-renders-its-figures-test
  (let [node (view/vol-range-report (model/report-view-model (headline-test/state {}) headline-test/now))]
    (is (= "−1,544.80 USDC" (text node "vol-range-report-net-pnl-value")))
    (is (= "−1,500.00 USDC" (text node "vol-range-report-hold-pnl-value")))
    (is (= "−44.80 USDC" (text node "vol-range-report-contribution-value")))
    (is (= "negative" (:data-tone (hiccup/node-attrs (role node "vol-range-report-net-pnl")))))
    (is (re-find #"^Net tracked asset P&L" (text node "vol-range-report-net-pnl")))
    (is (re-find #"^Benchmark: Hold inventory at the selected window start\." (text node "vol-range-report-benchmark")))
    (is (= "Oct 21, 13:42 UTC → Oct 21, 16:00 UTC · start valued at the book midpoint"
           (text node "vol-range-report-range")))
    (is (nil? (role node "vol-range-report-suppressed")))
    (is (not (re-find #"(?i)\bgrid|equity|%" (hiccup/node-text node))))))

(deftest the-covered-subset-control-test
  (let [s (headline-test/state {:campaigns [headline-test/vrt-old headline-test/vrs-old]
                                :rows [fx/buy-7-row headline-test/vrs-sell]
                                :ui {:window :7d}
                                :coverage {"9001" headline-test/wide "9002" [[(- fx/t0 (* 9 24 fx/hour)) fx/t0]]}})
        node (view/vol-range-report (model/report-view-model s headline-test/now))]
    (is (= "Totals are hidden because VRS isn't fully covered for this window." (text node "vol-range-report-no-total")))
    (is (= "Show total for covered markets (VRT)" (text node "vol-range-report-covered-subset")))
    (is (= "false" (:aria-pressed (hiccup/node-attrs (role node "vol-range-report-covered-subset")))))
    (is (= [[:actions/set-vol-range-report-covered-subset true]] (on-click node "vol-range-report-covered-subset")))
    (let [on (view/vol-range-report (model/report-view-model
                                     (assoc-in s [:vol-range :report :ui :covered-subset?] true) headline-test/now))]
      (is (= "Covered markets: VRT" (text on "vol-range-report-headline-title")))
      (is (= [[:actions/set-vol-range-report-covered-subset false]] (on-click on "vol-range-report-covered-subset"))))))

(deftest the-first-hourly-close-message-test
  (let [node (view/vol-range-report (model/report-view-model (model-test/state {}) (+ model-test/t0 60000)))]
    (is (= "Figures appear after the first completed hourly close (14:00 UTC)."
           (text node "vol-range-report-suppressed")))
    (is (= "status" (:role (hiccup/node-attrs (role node "vol-range-report-suppressed")))))))

(deftest start-tracking-is-confirmed-inline-test
  (let [node (render (model-test/state {:ui {:confirm-restart-key model-test/k}}))]
    (is (nil? (role node "vol-range-report-start-tracking")))
    (is (re-find #"Current tracking is kept as history" (text node "vol-range-report-start-tracking-confirm")))
    (is (= [[:actions/confirm-start-vol-range-tracking]] (on-click node "vol-range-report-start-tracking-yes")))
    (is (= "Start tracking" (text node "vol-range-report-start-tracking-yes")))
    (is (= [[:actions/cancel-start-vol-range-tracking]] (on-click node "vol-range-report-start-tracking-no")))
    (is (= "Keep current tracking" (text node "vol-range-report-start-tracking-no")))))

(deftest read-only-has-no-start-tracking-or-refresh-test
  (let [node (render (assoc (model-test/state {})
                            :account-context {:spectate-mode {:active? true :address model-test/account}}))]
    (is (some? (role node "vol-range-report-baseline")))
    (is (nil? (role node "vol-range-report-start-tracking")))
    (is (nil? (role node "vol-range-report-refresh")))
    (is (some? (role node "vol-range-report-read-only")))))

(deftest empty-state-test
  (let [node (render (assoc-in (model-test/state {}) [:vol-range :report :campaigns] {}))]
    (is (= "No Vol Range tracking for this account in this browser yet. Tracking starts when you place your first ladder."
           (text node "vol-range-report-empty")))
    (is (nil? (role node "vol-range-report-activity")))))

(defn- tab-ids [state trader?]
  (set (map :id (:extra-tabs (account-tabs/account-info-options state {} trader?)))))

(deftest the-portfolio-tab-is-gated-test
  (let [state (model-test/state {})]
    (is (contains? (tab-ids state false) :vol-range))
    (is (not (contains? (tab-ids state true) :vol-range)) "never on another trader's portfolio")
    (with-redefs [config/config (assoc-in config/config [:vol-range :reporting-enabled?] false)]
      (is (not (contains? (tab-ids state false) :vol-range)) "hidden with reporting off")
      (is (nil? (render state)) "the view renders nothing with reporting off"))
    (testing "ordered after Monte Carlo"
      (let [order (:tab-order (account-tabs/account-info-options state {} false))]
        (is (= 1 (- (.indexOf order :vol-range) (.indexOf order :monte-carlo))))))))
