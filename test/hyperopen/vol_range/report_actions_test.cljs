(ns hyperopen.vol-range.report-actions-test
  "Vol Range report actions are pure: each returns at most one report effect
  after its projection writes; read-only accounts never start tracking or
  backfill; busy operations emit nothing (HO-SPEC-014 sections 11.3, 12.6)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.config :as config]
            [hyperopen.vol-range.report-actions :as actions]))

(def addr "0x1234567890abcdef1234567890abcdef12345678")
(def k "hyperliquid-mainnet|0x1234567890abcdef1234567890abcdef12345678|spot|9001|9001")

(def base
  {:wallet {:address addr}
   :router {:path "/portfolio"}
   :portfolio-ui {:account-info-tab :vol-range}})

(def spectating
  (assoc base :account-context {:spectate-mode {:active? true :address "0x9999999999999999999999999999999999999999"}}))

(defn- busy [state k] (assoc-in state [:vol-range :report :busy k] true))

(deftest the-report-is-visible-only-on-the-portfolio-vol-range-tab-test
  (is (true? (actions/report-visible? base)))
  (is (false? (actions/report-visible? (assoc-in base [:portfolio-ui :account-info-tab] :balances))))
  (is (false? (actions/report-visible? (assoc-in base [:router :path] "/trade"))))
  (is (false? (actions/report-visible? (assoc-in base [:router :path] (str "/portfolio/trader/" addr))))
      "never for another trader's portfolio")
  (with-redefs [config/config (assoc-in config/config [:vol-range :reporting-enabled?] false)]
    (is (false? (actions/report-visible? base)) "never with reporting off")))

(deftest load-test
  (is (= [[:effects/vol-range-report :load {:account-address addr :backfill? true}]]
         (actions/load-vol-range-report base)))
  (is (= [] (actions/load-vol-range-report (busy base [:load]))) "coalesced while a load runs")
  (is (= [] (actions/load-vol-range-report (dissoc base :wallet))) "no account")
  (testing "Spectate loads read-only: no backfill"
    (let [[[_ op params]] (actions/load-vol-range-report spectating)]
      (is (= :load op))
      (is (false? (:backfill? params)))
      (is (= "0x9999999999999999999999999999999999999999" (:account-address params)))))
  (with-redefs [config/config (assoc-in config/config [:vol-range :reporting-enabled?] false)]
    (is (= [] (actions/load-vol-range-report base)))))

(deftest refresh-test
  (is (= [[:effects/vol-range-report :backfill {:account-address addr}]] (actions/refresh-vol-range-report base)))
  (is (= [] (actions/refresh-vol-range-report (busy base [:load]))))
  (is (= [] (actions/refresh-vol-range-report (busy base [:backfill]))))
  (is (= [] (actions/refresh-vol-range-report spectating)) "read-only accounts never backfill"))

(deftest select-campaign-test
  (is (= [[:effects/save-many [[[:vol-range :report :ui :selected-campaign-key] k]]]]
         (actions/select-vol-range-report-campaign base k)))
  (is (= [[:effects/save-many [[[:vol-range :report :ui :selected-campaign-key] nil]]]]
         (actions/select-vol-range-report-campaign base "all")))
  (is (= [[:effects/save-many [[[:vol-range :report :ui :selected-campaign-key] nil]]]]
         (actions/select-vol-range-report-campaign base nil))))

(deftest start-tracking-needs-an-explicit-confirmation-test
  (is (= [[:effects/save-many [[[:vol-range :report :ui :confirm-restart-key] k]]]]
         (actions/request-start-vol-range-tracking base k)))
  (is (= [[:effects/save-many [[[:vol-range :report :ui :confirm-restart-key] nil]]]]
         (actions/cancel-start-vol-range-tracking base)))
  (is (= [] (actions/confirm-start-vol-range-tracking base)) "nothing to confirm")
  (let [confirming (assoc-in base [:vol-range :report :ui :confirm-restart-key] k)]
    (is (= [[:effects/save-many [[[:vol-range :report :ui :confirm-restart-key] nil]]]
            [:effects/vol-range-report :start-tracking {:campaign-key k :account-address addr}]]
           (actions/confirm-start-vol-range-tracking confirming)))
    (is (= [] (actions/confirm-start-vol-range-tracking (busy confirming [:start-tracking])))
        "a second confirmation while one runs emits nothing")
    (testing "read-only: no Start tracking at all"
      (is (= [] (actions/request-start-vol-range-tracking spectating k)))
      (is (= [] (actions/confirm-start-vol-range-tracking
                 (assoc-in spectating [:vol-range :report :ui :confirm-restart-key] k)))))))

(def ^:private hour 3600000)
(def ^:private t0 1792590120000)
(def ^:private h0 (* hour (quot t0 hour)))

(def loaded
  (assoc-in base [:vol-range :report]
            {:account-address addr :closures {}
             :campaigns {"cmp-1" {:campaign-id "cmp-1" :campaign-key k :t0-ms t0 :scope {:coin "@9001"}}}}))

(deftest select-window-fetches-the-closes-it-needs-test
  (let [now (+ h0 (* 3 hour) 300000)]
    (is (= [[:effects/save-many [[[:vol-range :report :ui :window] :since-start]]]
            [:effects/vol-range-report :marks {:requests [{:coin "@9001" :from-ms h0 :to-ms (+ h0 (* 3 hour))}]}]]
           (actions/select-vol-range-report-window loaded "since-start" now)))
    (is (= [[:effects/save-many [[[:vol-range :report :ui :window] :7d]]]]
           (actions/select-vol-range-report-window loaded :7d now))
        "7D before tracking needs no closes")
    (is (= [[:effects/save-many [[[:vol-range :report :ui :window] :since-start]]]]
           (actions/select-vol-range-report-window (busy loaded [:marks]) :since-start now))
        "one marks read at a time")
    (is (= [[:effects/save-many [[[:vol-range :report :ui :window] :since-start]]]]
           (actions/select-vol-range-report-window
            (assoc-in loaded [:vol-range :report :marks "@9001"] {:closes {} :covered [[h0 (+ h0 (* 3 hour))]]})
            :since-start now))
        "nothing missing")
    (is (= [] (actions/select-vol-range-report-window loaded "1y" now)))
    (testing "the marks refresh asks for the same"
      (is (= [[:effects/vol-range-report :marks {:requests [{:coin "@9001" :from-ms h0 :to-ms (+ h0 (* 3 hour))}]}]]
             (actions/refresh-vol-range-report-marks loaded now)))
      (is (= [] (actions/refresh-vol-range-report-marks base now)) "nothing before the report is loaded"))))

(deftest covered-subset-test
  (is (= [[:effects/save-many [[[:vol-range :report :ui :covered-subset?] true]]]]
         (actions/set-vol-range-report-covered-subset base true)))
  (is (= [[:effects/save-many [[[:vol-range :report :ui :covered-subset?] false]]]]
         (actions/set-vol-range-report-covered-subset base false))))

(deftest ui-flag-test
  (testing "the matched disclosure opens and closes"
    (is (= [[:effects/save-many [[[:vol-range :report :ui :matched-open?] true]]]]
           (actions/set-vol-range-report-ui-flag base :matched-open? true)))
    (is (= [[:effects/save-many [[[:vol-range :report :ui :matched-open?] false]]]]
           (actions/set-vol-range-report-ui-flag base :matched-open? false))))
  (is (= [] (actions/set-vol-range-report-ui-flag base :window true)) "only known flags"))
