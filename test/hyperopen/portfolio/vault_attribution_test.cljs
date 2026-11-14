(ns hyperopen.portfolio.vault-attribution-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.vault-attribution :as attribution]
            [hyperopen.portfolio.vault-attribution-selector :as selector]
            [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]))

(def ^:private now fx/now)
(def ^:private day fx/day)
(def ^:private near? fx/near?)

(defn- final-return-pct
  [result]
  (second (peek (fx/all-time-returns (get-in result [:summary-entries :vault-all-time])))))

(defn- vault-row
  [result address]
  (some #(when (= address (:vault-address %)) %) (:vaults result)))

(deftest empty-input-test
  (doseq [input [{:ledger-rows nil :equity-by-vault {} :vaults {} :now-ms now}
                 {:ledger-rows [nil "x" 5 {:delta nil} {:time 1 :delta {:type "deposit" :usdc "5"}}]
                  :now-ms now}]]
    (let [result (attribution/attribution input)]
      (is (= :empty (:status result)))
      (is (= [] (:vaults result) (:series result) (:markers result)))
      (is (= {} (:summary-entries result)))
      (is (nil? (:daily-from-ms result)))
      (is (nil? (get-in result [:totals :pnl])) "no complete vault: P&L unknown, not $0")
      (is (= 0 (get-in result [:totals :incomplete-count]))))))

(deftest single-open-vault-test
  (let [start (- now (* 100 day))
        index #(+ 1 (* 0.001 (/ (- % start) day)))
        result (attribution/attribution
                {:ledger-rows (conj fx/noise (fx/deposit start fx/vault-a "1000"))
                 :equity-by-vault {fx/vault-a 1100}
                 :vaults {fx/vault-a {:name "HLP"
                                      :returns-candidates [nil [] (fx/index-rows start now day index)]}}
                 :now-ms now})
        row (vault-row result fx/vault-a)
        series (:series result)]
    (is (= :ready (:status result)))
    (is (= {:name "HLP" :status :open :deposited 1000 :withdrawn 0 :net-deposited 1000
            :value 1100 :flows-complete? true :first-deposit-ms start :last-activity-ms start
            :held-ms (* 100 day)}
           (select-keys row [:name :status :deposited :withdrawn :net-deposited :value
                             :flows-complete? :first-deposit-ms :last-activity-ms :held-ms])))
    (is (near? 100 (:pnl row)))
    (is (near? 0 (:realized row)))
    (is (near? 100 (:unrealized row)))
    (is (near? 10 (:vault-return-while-held-pct row)))
    (is (near? (- (js/Math.pow 1.1 (/ 365.2425 100)) 1) (:money-weighted-return row) 1e-6))
    (is (= [{:time-ms start :value 0 :net-deposited 0 :pnl 0}
            {:time-ms (inc start) :value 1000 :net-deposited 1000 :pnl 0}]
           (subvec series 0 2)))
    (is (apply < (map :time-ms series)))
    (is (= now (:time-ms (peek series))))
    (is (near? 1100 (:value (peek series))))
    ;; zero model drift: the midpoint sits exactly on the index
    (let [mid (some #(when (= (:time-ms %) (+ start (* 50 day))) %) series)]
      (is (near? 1050 (:value mid))))
    (is (= [{:time-ms start :kind :deposit :amount 1000 :vault-address fx/vault-a :name "HLP"}]
           (:markers result)))
    (is (= start (:daily-from-ms result)))
    (is (= 1 (get-in result [:totals :open-count])))
    (fx/assert-series-invariants result)))

(deftest partial-withdrawal-realized-pnl-test
  (let [start (- now (* 100 day))
        wd (+ start (* 50 day))
        index #(+ 1 (* 0.004 (/ (- % start) day)))
        result (attribution/attribution
                {:ledger-rows [(fx/withdraw wd fx/vault-a {:requested "600" :commission "6"
                                                           :basis "500" :net "594"})
                               (fx/deposit start fx/vault-a "1000")]
                 :equity-by-vault {fx/vault-a 700}
                 :vaults {fx/vault-a {:returns-candidates [(fx/index-rows start now day index)]}}
                 :now-ms now})
        row (vault-row result fx/vault-a)]
    (is (= :open (:status row)))
    (is (= 594 (:withdrawn row)))
    (is (near? 294 (:pnl row)))
    (is (near? 94 (:realized row)))
    (is (near? 200 (:unrealized row)))
    (is (near? 294 (get-in result [:totals :pnl])))
    (testing "the point pair at the withdrawal moves exactly the net received"
      (let [[before after] (filter #(#{wd (inc wd)} (:time-ms %)) (:series result))]
        (is (near? 594 (- (:value before) (:value after))))
        (is (near? (:pnl before) (:pnl after) 1e-9))))
    (fx/assert-series-invariants result)))

(deftest fully-closed-vault-test
  (let [start (- now (* 100 day))
        wd (+ start (* 60 day))
        index #(+ 1 (* 0.001 (/ (- % start) day)))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (fx/withdraw wd fx/vault-a {:requested "1050" :commission "0"
                                                           :basis "1000" :net "1050"})]
                 :equity-by-vault {}
                 :vaults {fx/vault-a {:returns-candidates [(fx/index-rows start now day index)]}}
                 :now-ms now})
        row (vault-row result fx/vault-a)
        series (:series result)
        before (some #(when (= wd (:time-ms %)) %) series)]
    (is (= {:status :closed :value 0 :held-ms (* 60 day) :last-activity-ms wd}
           (select-keys row [:status :value :held-ms :last-activity-ms])))
    (is (near? 50 (:pnl row)))
    (is (near? 50 (:realized row)))
    (is (near? 0 (:unrealized row)))
    (is (near? 5 (:vault-return-while-held-pct row))
        "the exact ledger P&L (1050 back on 1000), not the index's 6%")
    (is (near? (final-return-pct result) (:vault-return-while-held-pct row)))
    (is (near? 1050 (:value before)) "value just before closing = net received")
    (is (near? 0 (:value (peek series))))
    (is (near? 50 (:pnl (peek series))))
    (is (= 1 (get-in result [:totals :closed-count])))
    (testing "windows without a position are omitted"
      (is (= #{:vault-all-time} (set (keys (:summary-entries result))))))
    (fx/assert-series-invariants result)))

(deftest two-overlapping-vaults-reconcile-test
  (let [a-start (- now (* 200 day))
        b-start (- now (* 90 day))
        a-index #(+ 1 (* 0.0005 (/ (- % a-start) day)))
        b-index #(+ 1 (* 0.002 (/ (- % b-start) day)))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit a-start fx/vault-a "5000")
                               (fx/deposit b-start fx/vault-b "2000")
                               (fx/withdraw (- now (* 20 day)) fx/vault-b
                                            {:requested "2350" :commission "35"
                                             :basis "2000" :net "2315"})
                               (fx/deposit (- now (* 10 day)) fx/vault-a "1000")]
                 ;; deliberately off-model equity: drift must be absorbed
                 :equity-by-vault {(.toUpperCase fx/vault-a) 6700}
                 :vaults {fx/vault-a {:name "A" :returns-candidates
                                      [(fx/index-rows (- now (* 30 day)) now day a-index)
                                       (fx/index-rows a-start now (* 12 day) a-index)]}
                          fx/vault-b {:name "B" :returns-candidates
                                      [(fx/index-rows b-start now day b-index)]}}
                 :now-ms now})
        totals (:totals result)]
    (is (= [fx/vault-a fx/vault-b] (mapv :vault-address (:vaults result))))
    (is (= [:open :closed] (mapv :status (:vaults result))))
    (is (near? 700 (:pnl (vault-row result fx/vault-a))))
    (is (near? 315 (:pnl (vault-row result fx/vault-b))))
    (is (near? 1015 (:pnl totals)))
    (is (near? 6700 (:value totals)))
    (is (= {:deposited 8000 :withdrawn 2315 :net-deposited 5685 :open-count 1
            :closed-count 1 :first-deposit-ms a-start}
           (select-keys totals [:deposited :withdrawn :net-deposited :open-count
                                :closed-count :first-deposit-ms])))
    (is (number? (:money-weighted-return totals)))
    (is (near? 6700 (:value (peek (:series result)))))
    ;; vault A is only sampled ~daily for the last 30 days, B is closed by then
    (is (= (- now (* 30 day)) (:daily-from-ms result)))
    (fx/assert-series-invariants result)))

(deftest vault-without-history-test
  (let [start (- now (* 100 day))
        wd (+ start (* 40 day))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (fx/withdraw wd fx/vault-a {:requested "300" :commission "0"
                                                           :basis "250" :net "300"})]
                 :equity-by-vault {fx/vault-a 800}
                 :vaults {}
                 :now-ms now})
        row (vault-row result fx/vault-a)]
    (is (near? 100 (:pnl row)))
    ;; No index: the sleeve holds cost and recognises P&L at each withdrawal
    ;; and at the end; the row reads that sleeve, like the total.
    (is (near? (final-return-pct result) (:vault-return-while-held-pct row)))
    (is (= [[start 0 0] [(inc start) 1000 0] [wd 1050 50] [(inc wd) 750 50] [now 800 100]]
           (mapv (juxt :time-ms :value :pnl) (:series result))))
    (is (= now (:daily-from-ms result)))
    (fx/assert-series-invariants result)
    (testing "a top-up stamped at now keeps a separate end point"
      (let [result (attribution/attribution
                    {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                                   (fx/deposit now fx/vault-a "500")]
                     :equity-by-vault {fx/vault-a 1600}
                     :now-ms now})]
        (is (= [[now 1000 0] [(inc now) 1500 0] [(+ 2 now) 1600 100]]
               (mapv (juxt :time-ms :value :pnl) (take-last 3 (:series result)))))
        (fx/assert-series-invariants result)))))

(deftest reopened-vault-live-ledger-test
  ;; Real follower ledger (see position_test): two deposits, a FULL withdrawal
  ;; with commission, then a re-deposit. Hyperliquid's followerState for it
  ;; reported allTimePnl 1869.6200659182 and pnl 655.8756779182.
  (let [[t1 t2 t3 t4] [1774416974173 1777965182591 1786041424115 1788034752230]
        origin (- t1 day)
        index #(+ 1 (* 0.0004 (/ (- % origin) day)))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit t1 fx/vault-b "11000.0")
                               (fx/deposit t2 fx/vault-b "10087.99")
                               (fx/withdraw t3 fx/vault-b {:requested "22436.594874"
                                                           :commission "134.860487"
                                                           :basis "21087.989999"
                                                           :net "22301.734387"})
                               (fx/deposit t4 fx/vault-b "22609.800001")]
                 :equity-by-vault {fx/vault-b 23265.6756799182}
                 :vaults {fx/vault-b {:returns-candidates [(fx/index-rows origin now day index)]}}
                 :now-ms now})
        row (vault-row result fx/vault-b)]
    (is (near? 1869.6200659182 (:pnl row)))
    (is (near? 655.8756779182 (:unrealized row)))
    (is (= (+ (- t3 t1) (- now t4)) (:held-ms row)))
    (is (near? (final-return-pct result) (:vault-return-while-held-pct row))
        "one vault: the row reads the same sleeve as the total")
    (let [gap (filter #(<= (inc t3) (:time-ms %) t4) (:series result))]
      ;; only the close's after-point and the re-deposit's before-point
      (is (= [(inc t3) t4] (mapv :time-ms gap)))
      (is (every? #(near? 0 (:value %)) gap) "nothing held between the periods")
      (is (every? #(near? (- 22301.734387 21087.99) (:pnl %)) gap)))
    (fx/assert-series-invariants result)))

(def ^:private account "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- orphan-state
  "Production shape: the ledger names only vault-b (details loaded); vault-a
   is in the account's live `userVaultEquities` rows only."
  [& {:keys [vaults-extra live-for]}]
  {:wallet {:address account}
   :portfolio {:vault-attribution
               {:status :ready
                :for-address account
                :ledger-rows [(fx/deposit (- now day) fx/vault-b "10")]
                :details-by-vault {fx/vault-b {:name "B" :follower-state {:vault-equity 10}}}
                :errors-by-vault {}
                :loaded-at-ms now}}
   :vaults (merge {:user-equities [{:vault-address fx/vault-a :equity 500}
                                   {:vault-address fx/vault-b :equity 10}]
                   :user-equities-for-address (or live-for account)}
                  vaults-extra)})

(deftest equity-without-ledger-deposits-test
  (let [result (:attribution (selector/model
                              (orphan-state :vaults-extra
                                            {:merged-index-rows [{:vault-address fx/vault-a
                                                                  :name "Orphan"}]})))
        row (vault-row result fx/vault-a)]
    (is (= :ready (:status result)))
    (is (= {:name "Orphan" :status :open :value 500 :deposited 0 :flows-complete? false
            :pnl nil :realized nil :unrealized nil :money-weighted-return nil
            :vault-return-while-held-pct nil}
           (select-keys row [:name :status :value :deposited :flows-complete? :pnl :realized
                             :unrealized :money-weighted-return
                             :vault-return-while-held-pct])))
    (testing "totals cover the complete vaults only and count the rest"
      (is (near? 10 (get-in result [:totals :value])))
      (is (near? 10 (get-in result [:totals :deposited])))
      (is (near? 0 (get-in result [:totals :pnl])))
      (is (= 1 (get-in result [:totals :incomplete-count]))))
    (is (near? 10 (:value (peek (:series result)))) "orphan equity stays out of the series")
    (testing "without a name in state the row has none (the view shows the short address)"
      (is (nil? (:name (vault-row (:attribution (selector/model (orphan-state))) fx/vault-a))))))
  (testing "live rows of another account add nothing"
    (is (nil? (vault-row (:attribution (selector/model (orphan-state :live-for "0x1111111111111111111111111111111111111111")))
                         fx/vault-a)))))

(deftest vault-create-counts-as-deposit-test
  (let [start (- now (* 10 day))
        result (attribution/attribution
                {:ledger-rows [{:time start :hash "0xc"
                                :delta {:type "vaultCreate" :vault fx/vault-a :usdc "100.0" :fee "100.0"}}
                               {:time (inc start)
                                :delta {:type "vaultLeaderCommission" :vault fx/vault-a :usdc "3"}}]
                 :equity-by-vault {fx/vault-a 120}
                 :now-ms now})]
    (is (= 100 (:deposited (vault-row result fx/vault-a))))
    (is (near? 20 (:pnl (vault-row result fx/vault-a))))))
