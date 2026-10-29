(ns hyperopen.vol-range.application.report-coverage-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def t0 fx/t0)
(def hour fx/hour)
(def start (- t0 fx/skew))

(defn- read [r rows & {:as more}]
  (merge {:rows rows :complete? true :requested-at-ms r :received-at-ms (+ r 200) :error nil :reason nil} more))

(defn- fills-delta [rd tokens]
  (coverage/fills-read->coverage {:read rd :start-ms start :base-token-ids tokens
                                  :network fx/network :account-address fx/account}))

(defn- record [stream deltas]
  (reduce (fn [rec d] (coverage/merge-coverage rec d {:account-address fx/account :now-ms 1}))
          nil (filter #(= stream (:stream %)) deltas)))

(defn- covered
  "Coverage records for both streams from complete reads at R."
  [r]
  {:fills (record :fills (fills-delta (read r []) #{"9001"}))
   :ledger (record :ledger (coverage/ledger-read->coverage {:read (read r []) :start-ms start
                                                            :base-token-ids #{"9001"}}))})

(defn- state [cov until & [breaks checks]]
  (coverage/campaign-coverage {:campaign (fx/campaign-record) :coverage cov :breaks breaks
                               :balance-checks checks :until-ms until}))

(deftest complete-read-covers-up-to-the-settle-for-tracked-tokens-only-test
  (let [r (+ t0 (* 5 hour))
        deltas (fills-delta (read r []) #{"9001" "9002"})]
    (is (= [{:stream :fills :base-token-id "9001" :complete [[start (- r 5000)]] :failure nil}
            {:stream :fills :base-token-id "9002" :complete [[start (- r 5000)]] :failure nil}]
           deltas))
    (is (= [] (fills-delta (read r []) #{})) "a read that classified no token covers none")
    (is (= "coverage:0xabc|fills|9001" (:record-id (record :fills (take 1 deltas)))))))

(defn- rows [n coin-fn]
  (mapv (fn [i] {:coin (coin-fn i) :time (+ t0 i) :tid i :oid i :px "1" :sz "1" :side "B"}) (range n)))

(deftest retention-counts-distinct-fill-keys-of-all-coins-test
  (let [r (+ t0 (* 5 hour))
        many (rows 10000 #(if (even? %) "@9001" "BTC"))
        [d] (fills-delta (read r many) #{"9001"})]
    (is (= [] (:complete d)))
    (is (= :coverage/retention (get-in d [:failure :reason])) "perp rows count toward the venue's 10,000"))
  (let [r (+ t0 (* 5 hour))
        dupes (into (rows 9999 (constantly "@9001")) [(first (rows 1 (constantly "@9001")))])
        [d] (fills-delta (read r dupes) #{"9001"})]
    (is (= 10000 (count dupes)))
    (is (= [[start (- r 5000)]] (:complete d)) "overlap duplicates are counted once")))

(deftest incomplete-reads-record-gaps-test
  (let [r (+ t0 (* 5 hour))]
    (doseq [[reason expected] [[:fills/no-progress :coverage/no-progress]
                               [:fills/page-cap :coverage/page-cap]
                               [:fills/read-error :coverage/read-error]
                               [nil :coverage/read-error]]]
      (let [[d] (fills-delta (read r [] :complete? false :reason reason) #{"9001"})
            cov (assoc (covered (+ t0 hour)) :fills (coverage/merge-coverage
                                                    (:fills (covered (+ t0 hour))) d
                                                    {:account-address fx/account :now-ms 2}))]
        (is (= [] (:complete d)))
        (is (= {:from-ms start :to-ms r :reason expected :at-ms (+ r 200)} (:failure d)))
        (is (= :gap (:state (state cov r))))
        (is (= expected (:reason (state cov r))))))))

(deftest never-read-is-partial-test
  (let [s (state {} (+ t0 hour))]
    (is (= :partial (:state s)))
    (is (= :coverage/backfill-pending (:reason s)))
    (is (false? (:headline-allowed? s))))
  (let [s (state (covered (+ t0 (* 2 hour))) (+ t0 (* 3 hour)))]
    (is (= :partial (:state s)) "the part after the last complete read is still pending")
    (is (= [{:from-ms start :to-ms (- (+ t0 (* 2 hour)) 5000) :state :complete :reason nil}
            {:from-ms (- (+ t0 (* 2 hour)) 5000) :to-ms (+ t0 (* 3 hour)) :state :partial
             :reason :coverage/backfill-pending}]
           (:intervals s)))))

(deftest complete-coverage-allows-the-headline-test
  (let [s (state (covered (+ t0 (* 6 hour))) (+ t0 (* 5 hour)))]
    (is (= :complete (:state s)))
    (is (nil? (:reason s)))
    (is (true? (:headline-allowed? s)))))

(deftest a-later-complete-read-heals-a-gap-test
  (let [r1 (+ t0 (* 2 hour))
        r2 (+ t0 (* 6 hour))
        [failed] (fills-delta (read r1 [] :complete? false :reason :fills/page-cap) #{"9001"})
        [healed] (fills-delta (read r2 []) #{"9001"})
        base (covered r2)
        gap-only (assoc base :fills (coverage/merge-coverage nil failed {:account-address fx/account :now-ms 1}))
        both (assoc base :fills (coverage/merge-coverage (:fills gap-only) healed {:account-address fx/account :now-ms 2}))]
    (is (= :gap (:state (state gap-only (+ t0 (* 5 hour))))))
    (is (= :complete (:state (state both (+ t0 (* 5 hour))))))
    (is (= 1 (count (get-in both [:fills :failures]))) "the failure stays as history")))

(deftest failures-keep-the-newest-twenty-test
  (let [rec (reduce (fn [rec i]
                      (coverage/merge-coverage rec {:stream :fills :base-token-id "9001" :complete []
                                                    :failure {:from-ms i :to-ms (inc i) :reason :coverage/read-error
                                                              :at-ms i}}
                                               {:account-address fx/account :now-ms i}))
                    nil (range 25))]
    (is (= (range 5 25) (map :from-ms (:failures rec))))))

(deftest row-8-feed-gap-with-unchanged-ending-base-stays-a-gap-test
  ;; A possible missing buy and sell round trip leaves the ending base
  ;; unchanged: the balance check matches, but coverage is still a gap.
  (let [r1 (+ t0 (* 2 hour))
        [failed] (fills-delta (read r1 [] :complete? false :reason :fills/no-progress) #{"9001"})
        cov (-> (covered (+ t0 hour))
                (update :fills coverage/merge-coverage failed {:account-address fx/account :now-ms 1}))
        match {:kind :balance-check :campaign-id "cmp-vrt" :state :match :requested-at-ms (+ t0 (* 3 hour))
               :observed-total "150" :expected "150"}
        s (state cov (+ t0 (* 3 hour)) [] [match])]
    (is (= :gap (:state s)))
    (is (false? (:headline-allowed? s)) "balance equality never proves complete P&L")))

(deftest ambiguous-boundary-is-an-attribution-break-test
  (let [markets (attribution/spot-markets fx/spot-meta)
        found (attribution/campaign-findings {:campaign (fx/campaign-record) :fills [(fx/fill (assoc fx/manual-row :time t0))]
                                              :updates [] :markets markets :index {}})
        s (state (covered (+ t0 (* 6 hour))) (+ t0 (* 5 hour)) (:breaks found))]
    (is (= :attribution-break (:state s)))
    (is (= :break/ambiguous-boundary (:reason s)))
    (is (false? (:headline-allowed? s)))))

(deftest a-break-suppresses-later-windows-only-test
  (let [t (+ t0 (* 3 hour))
        brk [{:at-ms t :time t :reason :break/base-transfer :source :ledger :event-id "x"}]
        cov (covered (+ t0 (* 6 hour)))]
    (is (true? (:headline-allowed? (state cov t brk))) "a window ending at the break (b = t) is still available")
    (is (true? (:headline-allowed? (state cov (- t hour) brk))))
    (let [s (state cov (inc t) brk)]
      (is (false? (:headline-allowed? s)) "b > t is suppressed")
      (is (= :attribution-break (:state s)))
      (is (= {:from-ms t :to-ms (inc t) :state :attribution-break :reason :break/base-transfer}
             (last (:intervals s)))))))

(deftest unavailable-states-test
  (is (= :baseline/price-unavailable
         (:reason (coverage/campaign-coverage {:campaign (fx/campaign-record {:bbo* {:bid nil :ask nil}})
                                               :coverage {} :until-ms (+ t0 hour)}))))
  (is (= :storage/unavailable
         (:reason (coverage/campaign-coverage {:campaign (fx/campaign-record) :coverage {} :until-ms (+ t0 hour)
                                               :storage-ok? false}))))
  (is (= :unavailable (:state (state {} (- t0 fx/skew))))))

;; ---------------------------------------------------------------------------
;; Balance checks
;; ---------------------------------------------------------------------------

(def r (+ t0 (* 3 hour)))

(defn- pending [total]
  (first (coverage/pending-balance-checks {:snapshot (fx/barrier-read r total) :campaigns [(fx/campaign-record)]
                                           :account-address fx/account :now-ms r})))

(defn- tracked-events []
  (:events (attribution/campaign-findings {:campaign (fx/campaign-record) :fills [(fx/fill fx/buy-7-row)]
                                           :updates [] :markets (attribution/spot-markets fx/spot-meta)
                                           :index (attribution/attribution-index [(fx/grid)])})))

(defn- evaluate [check & {:as opts}]
  (coverage/evaluate-balance-check
   check
   (merge {:campaign (fx/campaign-record) :events (tracked-events) :breaks []
           :coverage (covered (+ r 40000)) :fills-read-requested-at-ms (+ r 40000) :now-ms (+ r 41000)}
          opts)))

(deftest pending-checks-come-from-complete-barrier-reads-test
  (is (= {:record-id (str "balance-check:cmp-vrt|" r) :kind :balance-check :campaign-id "cmp-vrt"
          :observed-total "157" :state :pending :requested-at-ms r :account-address "0xabc"}
         (select-keys (pending "157") [:record-id :kind :campaign-id :observed-total :state :requested-at-ms
                                       :account-address])))
  (is (= [] (coverage/pending-balance-checks {:snapshot (assoc (fx/barrier-read r) :complete? false)
                                              :campaigns [(fx/campaign-record)] :now-ms r})))
  (is (= [] (coverage/pending-balance-checks {:snapshot (fx/barrier-read (+ r 1)) :campaigns [(fx/campaign-record)]
                                              :existing [(pending "157")] :now-ms r}))
      "one pending check per campaign at a time"))

(deftest balance-check-match-and-mismatch-test
  (let [m (evaluate (pending "157"))]
    (is (= :match (:state m)))
    (is (= "157" (:expected m)))
    (is (= (+ r 41000) (:evaluated-at-ms m))))
  (let [m (evaluate (pending "157.0"))]
    (is (= :match (:state m)) "exact decimal comparison, not text"))
  (let [m (evaluate (pending "156.99") :latest-match {:requested-at-ms (+ t0 hour)})]
    (is (= :mismatch (:state m)))
    (is (= (+ t0 hour) (:last-match-at-ms m)))
    (testing "a mismatch is a break from the last match onward"
      (is (= [{:at-ms (+ t0 hour) :time r :reason :break/unexplained-balance-change :source :balance-check
               :event-id (:record-id m)}]
             (coverage/balance-check-breaks (fx/campaign-record) [m]))))))

(deftest balance-check-waits-for-the-settle-and-coverage-test
  (let [p (pending "157")]
    (is (= p (evaluate p :fills-read-requested-at-ms (+ r 29999))) "a read requested under 30 s after R waits")
    (is (= p (evaluate p :fills-read-requested-at-ms nil)) "no complete read yet")
    (is (= p (evaluate p :coverage (covered (- r hour)))) "fills before R are not all covered yet")))

(deftest balance-check-inconclusive-cases-test
  (let [p (pending "157")
        near (fn [dt] (assoc (first (tracked-events)) :time (+ r dt)))]
    (is (= :inconclusive (:state (evaluate p :events [(near -5000)]))) "a tracked fill within skew of R")
    (is (= :inconclusive (:state (evaluate p :events [(near 4000)]))))
    (is (= :match (:state (evaluate (pending "157") :events [(near -5001)]))))
    (is (= :inconclusive (:state (evaluate p :breaks [{:at-ms (+ t0 hour) :time (+ t0 hour)
                                                       :reason :break/untracked-same-base-trade}])))
        "the campaign already has an untracked event")))
