(ns hyperopen.portfolio.vault-attribution-edge-test
  "Distributions, unknown equity, account row filtering, totals honesty and
   money-weighted-return edge cases."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.vault-attribution :as attribution]
            [hyperopen.portfolio.vault-attribution.model :as model]
            [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]))

(def ^:private now fx/now)
(def ^:private day fx/day)
(def ^:private near? fx/near?)

(def ^:private account "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- vault-row
  [result address]
  (some #(when (= address (:vault-address %)) %) (:vaults result)))

(defn- distribution
  [time-ms vault usdc]
  {:time time-ms
   :hash (str "0xdist" time-ms)
   :delta {:type "vaultDistribution" :vault vault :usdc usdc}})

(deftest vault-distribution-closes-position-test
  (let [start (- now (* 100 day))
        paid (- now (* 10 day))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (distribution paid fx/vault-a "1100")]
                 :equity-by-vault {}
                 :now-ms now})
        row (vault-row result fx/vault-a)]
    (is (= {:status :closed :value 0 :withdrawn 1100 :flows-complete? true
            :held-ms (- paid start)}
           (select-keys row [:status :value :withdrawn :flows-complete? :held-ms])))
    (is (near? 100 (:pnl row)) "a distribution is money back, not a total loss")
    (is (near? 100 (:realized row)))
    (is (near? 0 (:unrealized row)))
    (is (= [:deposit :withdraw] (mapv :kind (:markers result))))
    (is (= :distribution (:source (peek (:markers result)))))
    (fx/assert-series-invariants result)))

(deftest partial-vault-distribution-keeps-position-open-test
  (let [start (- now (* 100 day))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (distribution (- now (* 50 day)) fx/vault-a "200")]
                 :equity-by-vault {fx/vault-a 900}
                 :now-ms now})
        row (vault-row result fx/vault-a)]
    (is (= :open (:status row)))
    (is (near? 100 (:pnl row)))
    (is (near? 200 (:withdrawn row)))
    (fx/assert-series-invariants result)))

(deftest ledger-addresses-include-distributions-test
  (is (= #{fx/vault-a} (model/ledger-vault-addresses [(distribution 5 fx/vault-a "1")]))))

(deftest unknown-equity-is-not-zero-test
  (let [start (- now (* 100 day))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (fx/deposit start fx/vault-b "500")]
                 :equity-by-vault {fx/vault-b 550}
                 :equity-unknown #{fx/vault-a}
                 :now-ms now})
        row (vault-row result fx/vault-a)
        totals (:totals result)]
    (is (= {:status :open :value nil :equity-known? false :pnl nil
            :flows-complete? false :money-weighted-return nil}
           (select-keys row [:status :value :equity-known? :pnl :flows-complete?
                             :money-weighted-return])))
    (testing "totals leave the unknown vault out and say so"
      (is (near? 50 (:pnl totals)))
      (is (near? 550 (:value totals)))
      (is (near? 500 (:deposited totals)))
      (is (= 1 (:incomplete-count totals))))
    (is (every? #(= fx/vault-b (:vault-address %)) (:markers result)))
    (fx/assert-series-invariants result)))

(deftest unknown-equity-of-a-fully-withdrawn-vault-is-closed-test
  (let [start (- now (* 100 day))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (fx/withdraw (- now (* 40 day)) fx/vault-a
                                            {:requested "1080" :commission "0"
                                             :basis "1000" :net "1080"})]
                 :equity-unknown #{fx/vault-a}
                 :now-ms now})
        row (vault-row result fx/vault-a)]
    (is (= {:status :closed :value 0 :equity-known? true :flows-complete? true}
           (select-keys row [:status :value :equity-known? :flows-complete?])))
    (is (near? 80 (:pnl row)))))

(deftest no-complete-vault-totals-are-nil-test
  (let [result (attribution/attribution
                {:ledger-rows [(fx/deposit (- now day) fx/vault-a "100")]
                 :equity-unknown #{fx/vault-a}
                 :now-ms now})
        totals (:totals result)]
    (is (= :ready (:status result)))
    (is (= {:pnl nil :realized nil :unrealized nil :money-weighted-return nil
            :incomplete-count 1 :value 0 :deposited 0}
           (select-keys totals [:pnl :realized :unrealized :money-weighted-return
                                :incomplete-count :value :deposited])))))

(deftest account-row-filtering-test
  (let [start (- now (* 100 day))
        follower "0x2222222222222222222222222222222222222222"]
    (testing "a vault account's followers' deposits into it are not its own"
      (let [result (attribution/attribution
                    {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                                   (fx/deposit start fx/vault-b "300")]
                     :account-address (.toUpperCase fx/vault-a)
                     :equity-by-vault {fx/vault-b 330}
                     :now-ms now})]
        (is (= [fx/vault-b] (mapv :vault-address (:vaults result))))))
    (testing "withdrawals naming another user are dropped"
      (let [rows [(fx/deposit start fx/vault-a "1000")
                  (assoc-in (fx/withdraw (- now day) fx/vault-a
                                         {:requested "500" :commission "0" :basis "500" :net "500"})
                            [:delta :user] follower)]
            result (attribution/attribution
                    {:ledger-rows rows :account-address account
                     :equity-by-vault {fx/vault-a 1100} :now-ms now})]
        (is (= 0 (:withdrawn (vault-row result fx/vault-a))))
        (is (near? 100 (:pnl (vault-row result fx/vault-a))))))
    (testing "own withdrawals (same user, any case) and rows without a user stay"
      (let [rows [(fx/deposit start fx/vault-a "1000")
                  (assoc-in (fx/withdraw (- now day) fx/vault-a
                                         {:requested "500" :commission "0" :basis "500" :net "500"})
                            [:delta :user] (.toUpperCase account))]]
        (is (= 2 (count (model/account-rows rows account))))
        (is (= rows (model/account-rows rows nil)))))))

(deftest money-weighted-return-edge-cases-test
  (testing "a hold under 30 days is not annualised"
    (let [start (- now (* 10 day))
          result (attribution/attribution
                  {:ledger-rows [(fx/deposit start fx/vault-a "1000")]
                   :equity-by-vault {fx/vault-a 1010}
                   :now-ms now})]
      (is (near? 10 (:pnl (vault-row result fx/vault-a))))
      (is (nil? (:money-weighted-return (vault-row result fx/vault-a))))
      (is (nil? (get-in result [:totals :money-weighted-return])))))
  (testing "a total loss is -100%, even over a short hold"
    (let [start (- now (* 10 day))
          result (attribution/attribution
                  {:ledger-rows [(fx/deposit start fx/vault-a "1000")]
                   :equity-by-vault {}
                   :now-ms now})]
      (is (near? -1000 (:pnl (vault-row result fx/vault-a))))
      (is (= -1 (:money-weighted-return (vault-row result fx/vault-a))))
      (is (= -1 (get-in result [:totals :money-weighted-return])))))
  (testing "irr itself"
    (is (= -1 (model/irr [[0 -100]])))
    (is (nil? (model/irr [[0 100]])))
    (is (nil? (model/irr [])))))

(deftest concurrent-short-holds-are-not-annualised-test
  ;; Three vaults held side by side for 12 days: 36 vault-days, but only 12
  ;; days of calendar time, so the total is not annualised either.
  (let [start (- now (* 12 day))
        vault-c "0x3333333333333333333333333333333333333333"
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (fx/deposit start fx/vault-b "1000")
                               (fx/deposit start vault-c "1000")]
                 :equity-by-vault {fx/vault-a 1010 fx/vault-b 1010 vault-c 1010}
                 :now-ms now})]
    (is (near? 30 (get-in result [:totals :pnl])))
    (is (nil? (get-in result [:totals :money-weighted-return])))))

(deftest withdrawals-after-basis-returned-keep-the-period-open-test
  ;; A distribution returns the whole basis, then two withdrawals take out
  ;; profit while 250 is still held: one holding period, not a leading
  ;; withdrawal that drops the vault from the totals.
  (let [start (- now (* 100 day))
        result (attribution/attribution
                {:ledger-rows [(fx/deposit start fx/vault-a "1000")
                               (distribution (- now (* 80 day)) fx/vault-a "1000")
                               (fx/withdraw (- now (* 60 day)) fx/vault-a
                                            {:requested "500" :commission "0" :basis "0" :net "500"})
                               (fx/withdraw (- now (* 40 day)) fx/vault-a
                                            {:requested "250" :commission "0" :basis "0" :net "250"})]
                 :equity-by-vault {fx/vault-a 250}
                 :now-ms now})
        row (vault-row result fx/vault-a)]
    (is (= {:status :open :value 250 :withdrawn 1750 :flows-complete? true
            :held-ms (- now start)}
           (select-keys row [:status :value :withdrawn :flows-complete? :held-ms])))
    (is (near? 1000 (:pnl row)) "value + withdrawn - deposited")
    (is (near? 1000 (get-in result [:totals :pnl])))
    (is (= 0 (get-in result [:totals :incomplete-count])))
    (fx/assert-series-invariants result)))

(deftest dust-after-a-full-withdrawal-keeps-the-vault-complete-test
  ;; A full withdrawal leaves 0.005 of basis (under epsilon: closed), then a
  ;; sub-cent dust payout lands later.
  (let [start (- now (* 100 day))
        wd (- now (* 50 day))
        dust (- now (* 10 day))
        full (fx/withdraw wd fx/vault-a {:requested "1010" :commission "0"
                                         :basis "999.995" :net "1010"})]
    (doseq [[label dust-row] [["distribution" (distribution dust fx/vault-a "0.004")]
                              ["withdrawal" (fx/withdraw dust fx/vault-a
                                                         {:requested "0.004" :commission "0"
                                                          :basis "0.005" :net "0.004"})]]]
      (testing label
        (let [result (attribution/attribution
                      {:ledger-rows [(fx/deposit start fx/vault-a "1000") full dust-row]
                       :equity-by-vault {}
                       :now-ms now})
              row (vault-row result fx/vault-a)]
          (is (= {:status :closed :value 0 :flows-complete? true}
                 (select-keys row [:status :value :flows-complete?])))
          (is (near? 10.004 (:pnl row)))
          (is (= 0 (get-in result [:totals :incomplete-count])))
          (fx/assert-series-invariants result))))))

(deftest a-withdrawal-with-no-deposit-at-all-is-still-incomplete-test
  (let [result (attribution/attribution
                {:ledger-rows [(fx/withdraw (- now day) fx/vault-a
                                            {:requested "5" :commission "0" :basis "5" :net "5"})
                               (fx/deposit now fx/vault-b "10")]
                 :equity-by-vault {fx/vault-a 1 fx/vault-b 10}
                 :now-ms now})]
    (is (false? (:flows-complete? (vault-row result fx/vault-a))))))
