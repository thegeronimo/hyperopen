(ns hyperopen.vaults.detail.position-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vaults.detail.position :as position]))

(def ^:private vault
  "0x1e37a337ed460039d1b15bd3bc489de789768d5e")

(def ^:private day-ms
  86400000)

(defn- deposit-row
  [time-ms usdc]
  {:time time-ms
   :hash (str "0xdep" time-ms)
   :delta {:type "vaultDeposit" :vault vault :usdc usdc}})

(defn- withdraw-row
  [time-ms {:keys [requested commission basis net]}]
  {:time time-ms
   :hash (str "0xwd" time-ms)
   :delta {:type "vaultWithdraw"
           :vault vault
           :requestedUsd requested
           :commission commission
           :closingCost "0.0"
           :basis basis
           :netWithdrawnUsd net}})

;; Real follower 0x0183…3834 of vault 0x1e37…8d5e on 2026-10-04: two deposits,
;; a FULL withdrawal, then one re-deposit. `vaultEntryTime` still points at the
;; first deposit.
(def ^:private live-ledger
  [(deposit-row 1774416974173 "11000.0")
   (deposit-row 1777965182591 "10087.99")
   (withdraw-row 1786041424115 {:requested "22436.594874"
                                :commission "134.860487"
                                :basis "21087.989999"
                                :net "22301.734387"})
   (deposit-row 1788034752230 "22609.800001")
   ;; Another vault's transfer and a non-vault ledger row are ignored.
   {:time 1788034752231 :delta {:type "vaultDeposit" :vault "0xother" :usdc "5"}}
   {:time 1788034752232 :delta {:type "deposit" :usdc "100"}}])

(def ^:private live-follower
  {:vault-equity 23265.6756799182
   :pnl 655.8756779182
   :all-time-pnl 1869.6200659182
   :vault-entry-time-ms 1774416974173
   :lockup-until-ms 1788121152230})

(defn- near?
  [expected actual]
  (and (number? actual)
       (< (js/Math.abs (- expected actual)) 0.0001)))

(deftest vault-transfers-normalizes-only-this-vault-oldest-first-test
  (let [transfers (position/vault-transfers (reverse live-ledger) (.toUpperCase vault))]
    (is (= [:deposit :deposit :withdraw :deposit] (mapv :kind transfers)))
    (is (= 11000 (:amount (first transfers))))
    (let [withdraw (nth transfers 2)]
      (is (near? 22301.734387 (:amount withdraw)))
      (is (near? 134.860487 (:commission withdraw)))
      (is (near? 1213.744388 (:realized withdraw))))))

(deftest live-full-withdraw-then-redeposit-test
  (let [now-ms (+ 1788034752230 (* 10 day-ms))
        summary (position/position-summary {:vault-address vault
                                            :follower live-follower
                                            :ledger-rows live-ledger
                                            :ledger-status :ready
                                            :returns-rows []
                                            :now-ms now-ms})]
    (testing "money comes from followerState"
      (is (= :open (:status summary)))
      (is (near? 22609.8000020000 (:cost-basis summary)))
      (is (near? 655.8756779182 (:unrealized summary)))
      (is (near? 2.900849 (:unrealized-pct summary)))
      (is (near? 1213.744388 (:realized summary)))
      (is (near? 1869.6200659182 (:all-time-earned summary))))
    (testing "the current position starts at the re-deposit, not vaultEntryTime"
      (is (= 1788034752230 (:position-start-ms summary)))
      (is (true? (:position-start-exact? summary)))
      (is (= 1774416974173 (:first-deposit-ms summary)))
      (is (= 10 (:days-held summary))))
    (testing "transfers are newest first and flagged by position"
      (is (= [true false false false] (mapv :current-position? (:transfers summary))))
      (is (= 4 (:transfer-count summary))))
    (testing "lockup ended before now"
      (is (false? (:locked? summary))))))

(deftest top-up-and-partial-withdraw-keep-one-position-test
  (let [t0 (* 1000 day-ms)
        rows [(deposit-row t0 "25000")
              (deposit-row (+ t0 (* 83 day-ms)) "10000")
              (withdraw-row (+ t0 (* 161 day-ms)) {:requested "5000" :commission "0"
                                                   :basis "4587.2" :net "5000"})]
        returns-rows [[t0 0] [(+ t0 (* 83 day-ms)) 5.0] [(+ t0 (* 206 day-ms)) 8.66]]
        summary (position/position-summary {:vault-address vault
                                            :follower {:vault-equity 32486.20
                                                       :pnl 2073.40
                                                       :all-time-pnl 2486.20
                                                       :vault-entry-time-ms t0}
                                            :ledger-rows rows
                                            :ledger-status :ready
                                            :returns-rows returns-rows
                                            :now-ms (+ t0 (* 206 day-ms))})]
    (is (= t0 (:position-start-ms summary)))
    (is (= 206 (:days-held summary)))
    (is (near? 412.80 (:realized summary)))
    (is (near? 8.66 (:vault-return-since-start-pct summary)))
    (is (every? :current-position? (:transfers summary)))
    (let [[withdraw top-up first-deposit] (:transfers summary)]
      (is (nil? (:vault-return-since-pct withdraw)))
      (is (near? 3.4857 (:vault-return-since-pct top-up)))
      (is (near? 8.66 (:vault-return-since-pct first-deposit))))
    (is (= :gain (get-in summary [:composition :direction])))))

(deftest ledger-not-ready-falls-back-to-entry-time-test
  (let [summary (position/position-summary {:vault-address vault
                                            :follower live-follower
                                            :ledger-rows nil
                                            :ledger-status :loading
                                            :returns-rows []
                                            :now-ms 1788121152230})]
    (is (= 1774416974173 (:position-start-ms summary)))
    (is (false? (:position-start-exact? summary)))
    (is (= [] (:transfers summary)))
    (is (= :loading (:ledger-status summary)))))

(deftest locked-and-underwater-test
  (let [now-ms 2000000000000
        summary (position/position-summary {:vault-address vault
                                            :follower {:vault-equity 9412.07
                                                       :pnl -587.93
                                                       :all-time-pnl -587.93
                                                       :vault-entry-time-ms (- now-ms day-ms)
                                                       :lockup-until-ms (+ now-ms (* 3 day-ms))}
                                            :ledger-status :idle
                                            :now-ms now-ms})]
    (is (true? (:locked? summary)))
    (is (= (* 3 day-ms) (:lockup-remaining-ms summary)))
    (is (near? -5.8793 (:unrealized-pct summary)))
    (is (= :loss (get-in summary [:composition :direction])))
    (is (near? 0.941207 (get-in summary [:composition :basis-share])))))

(deftest fresher-equity-overrides-value-but-not-basis-test
  (let [summary (position/position-summary {:vault-address vault
                                            :follower {:vault-equity 1000 :pnl 100 :all-time-pnl 100}
                                            :equity 1010
                                            :ledger-status :idle
                                            :now-ms 0})]
    (is (= 1010 (:value summary)))
    (is (= 900 (:cost-basis summary)))
    (is (= 110 (:unrealized summary)))))

(deftest no-follower-and-closed-states-test
  (is (= :none (:status (position/position-summary {:vault-address vault :now-ms 0}))))
  (is (= :closed (:status (position/position-summary {:vault-address vault
                                                      :follower {:vault-equity 0
                                                                 :pnl 0
                                                                 :all-time-pnl 1204.55}
                                                      :now-ms 0})))))

(deftest vault-return-since-needs-history-covering-the-start-test
  (let [rows [[100 1.0] [200 3.0] [300 6.0]]]
    (is (nil? (position/vault-return-since rows 50)))
    (is (nil? (position/vault-return-since [] 150)))
    (is (near? (* 100 (- (/ 1.06 1.02) 1)) (position/vault-return-since rows 150)))
    (is (near? 0 (position/vault-return-since rows 400)))))

(deftest position-series-buys-units-at-the-vault-index-and-steps-on-transfers-test
  (let [t0 (* 2000 day-ms)
        rows [[t0 0] [(+ t0 (* 5 day-ms)) 5.0] [(+ t0 (* 8 day-ms)) 8.0] [(+ t0 (* 10 day-ms)) 10.0]]
        series (position/position-series
                {:transfers [{:kind :deposit :time-ms t0 :amount 1000}
                             {:kind :deposit :time-ms (+ t0 (* 5 day-ms)) :amount 500}
                             {:kind :withdraw :time-ms (+ t0 (* 8 day-ms)) :amount 216 :requested 216 :basis 200}]
                 ;; Coarser fallback is ignored when the finer set covers the start.
                 :returns-candidates [rows [[0 0] [t0 50]]]
                 :now-ms (+ t0 (* 10 day-ms))
                 ;; Equal to the model's own value at now, so no calibration.
                 :value (* (+ 1000 (/ 500 1.05) (- (/ 216 1.08))) 1.10)
                 :cost-basis 1300})
        points (:points series)]
    (is (= t0 (:start-ms series)))
    (is (= [:deposit :deposit :withdraw] (mapv :kind (:markers series))))
    (testing "start, then a before/after pair per later transfer, then the live point"
      (is (= [t0
              (+ t0 (* 5 day-ms)) (+ t0 (* 5 day-ms))
              (+ t0 (* 8 day-ms)) (+ t0 (* 8 day-ms))
              (+ t0 (* 10 day-ms))]
             (mapv :time-ms points))))
    (let [[start before-top-up after-top-up before-withdraw after-withdraw live] points]
      (is (near? 1000 (:value start)))
      (is (near? 1050 (:value before-top-up)))
      (is (= 1000 (:basis before-top-up)))
      (is (near? 1550 (:value after-top-up)))
      (is (= 1500 (:basis after-top-up)))
      (is (near? 1594.2857 (:value before-withdraw)))
      (is (near? 1378.2857 (:value after-withdraw)))
      (is (= 1300 (:basis after-withdraw)))
      (is (near? 1403.8095 (:value live)))
      (is (= 1300 (:basis live))))))

(deftest position-series-spreads-drift-to-the-live-balance-over-the-window-test
  (let [t0 (* 2000 day-ms)
        series (position/position-series
                {:transfers [{:kind :deposit :time-ms t0 :amount 1000}]
                 :returns-candidates [[[t0 0] [(+ t0 (* 5 day-ms)) 5.0] [(+ t0 (* 10 day-ms)) 10.0]]]
                 :now-ms (+ t0 (* 10 day-ms))
                 :value 1078
                 :cost-basis 1000})
        [start mid live] (:points series)]
    ;; Model says 1100 at now; live is 1078 (ratio 0.98), ramped from 1 at start.
    (is (near? 1000 (:value start)))
    (is (near? (* 1050 0.99) (:value mid)))
    (is (= 1078 (:value live)))))

(deftest position-series-needs-history-covering-the-first-deposit-test
  (is (nil? (position/position-series {:transfers [{:kind :deposit :time-ms 50 :amount 10}]
                                       :returns-candidates [[[100 0] [200 1]]]
                                       :now-ms 300
                                       :value 10
                                       :cost-basis 10}))))

(deftest vault-returns-wait-for-a-full-day-and-summary-carries-the-series-test
  (let [t0 (* 3000 day-ms)
        rows [[(- t0 day-ms) 0] [t0 1.0] [(+ t0 (* 6 3600000)) 1.1]]
        summary (fn [now-ms]
                  (position/position-summary
                   {:vault-address vault
                    :follower {:vault-equity 1000.5 :pnl 0.5 :all-time-pnl 0.5 :vault-entry-time-ms t0}
                    :ledger-rows [(deposit-row t0 "1000")]
                    :ledger-status :ready
                    :returns-candidates [rows]
                    :now-ms now-ms}))
        same-day (summary (+ t0 (* 6 3600000)))]
    (is (= 0 (:days-held same-day)))
    (is (nil? (:vault-return-since-start-pct same-day)))
    (is (nil? (:vault-return-since-pct (first (:transfers same-day)))))
    (is (= 2 (count (get-in same-day [:series :points]))))
    (is (near? 0.0990 (:vault-return-since-start-pct (summary (+ t0 day-ms)))))))

(deftest window-pnl-grows-held-value-with-the-index-and-treats-transfers-as-cash-test
  (let [t0 (* 4000 day-ms)
        rows [[t0 0] [(+ t0 (* 10 day-ms)) 10.0] [(+ t0 (* 20 day-ms)) 21.0]]
        series {:start-ms t0
                :points [{:time-ms t0 :value 1000 :basis 1000}
                         {:time-ms (+ t0 (* 10 day-ms)) :value 1100 :basis 1000}
                         {:time-ms (+ t0 (* 10 day-ms)) :value 1600 :basis 1500}
                         {:time-ms (+ t0 (* 20 day-ms)) :value 1760 :basis 1500}]}
        window {:series series
                :transfers [{:kind :deposit :time-ms t0 :amount 1000}
                            {:kind :deposit :time-ms (+ t0 (* 10 day-ms)) :amount 500}]
                :returns-candidates [rows]
                :now-ms (+ t0 (* 20 day-ms))}]
    (testing "a window inside the position: 1600 held grows 10%"
      (let [{:keys [pnl pct]} (position/window-pnl window (+ t0 (* 10 day-ms)))]
        (is (near? 160 pnl))
        (is (near? 10 pct))))
    (testing "a window spanning the top-up counts growth, not the deposit"
      (let [{:keys [pnl pct]} (position/window-pnl window (+ t0 (* 5 day-ms)))]
        (is (near? 210 pnl) "50 before the top-up + 160 after")
        (is (near? (* 100 (/ 210 1550)) pct) "over 1050 held + 500 deposited")))
    (testing "a window older than the position starts at the first deposit"
      (is (near? 260 (:pnl (position/window-pnl window (- t0 (* 30 day-ms))))) "100 + 160"))))

(deftest max-drawdown-since-measures-the-index-from-the-start-test
  (let [rows [[0 0] [10 10.0] [20 -1.0] [30 5.0]]]
    (is (near? -10 (position/max-drawdown-since [rows] 0)))
    (is (near? (* 100 (- (/ 0.99 1.10) 1)) (position/max-drawdown-since [rows] 10)))
    (is (nil? (position/max-drawdown-since [rows] -5)))))
