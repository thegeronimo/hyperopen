(ns hyperopen.vol-range.infrastructure.report-executor-test
  "Reporting backfill with fakes only: no request here can reach an
  exchange, and nothing signs, sends or reserves a nonce."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.infrastructure.ledger-store :as store]
            [hyperopen.vol-range.infrastructure.ledger-store-test :as store-test]
            [hyperopen.vol-range.infrastructure.report-executor :as executor]
            [hyperopen.vol-range.report-fixtures :as fx]
            [hyperopen.test-support.async :as async-support]))

(def t0 fx/t0)
(def hour fx/hour)

(defn- world
  "Fake venue, journal and ledger. `:fills`, `:ledger-rows` and `:vrt-total`
  are atoms the test may change between runs."
  [& {:keys [fills ledger-rows vrt-total fills-hook io] :or {vrt-total "157"}}]
  (let [clock (atom (+ t0 (* 6 hour)))
        calls (atom [])
        journal (atom [(fx/campaign-record) (jr/grid-record (fx/grid) (+ t0 500))])
        fills (atom (or fills []))
        ledger-rows (atom (or ledger-rows []))
        vrt-total (atom vrt-total)
        io (or io (store-test/memory-io))
        ids (atom 0)
        post-info! (fn [body opts]
                     (swap! calls conj [(get body "type") opts body])
                     (js/Promise.resolve
                      (case (get body "type")
                        "spotClearinghouseState" {:balances [{:coin "VRT" :token 9001 :total @vrt-total :hold "0"}]}
                        "frontendOpenOrders" []
                        "userFillsByTime" (do (when fills-hook (fills-hook journal))
                                              (->> @fills
                                                   (filter #(<= (get body "startTime") (:time %) (get body "endTime")))
                                                   (sort-by :time) (take 2000) vec))
                        "userNonFundingLedgerUpdates" (->> @ledger-rows
                                                           (filter #(<= (get body "startTime") (:time %)
                                                                        (get body "endTime")))
                                                           vec))))]
    {:clock clock :calls calls :journal journal :fills fills :ledger-rows ledger-rows :vrt-total vrt-total :io io
     :deps {:post-info! post-info!
            :journal {:load-all! (fn [] (js/Promise.resolve {:records @journal :unreadable []}))}
            :ledger {:put-records! (partial store/put-records! io)
                     :load-account! (partial store/load-account! io)
                     :merge-coverage! (partial store/merge-coverage! io)
                     :put-balance-check! (partial store/put-balance-check! io)}
            :spot-meta! (constantly fx/spot-meta)
            :now-ms! (fn [] @clock)
            :random-id! (fn [p] (str p "-" (swap! ids inc)))
            :network fx/network}}))

(defn- backfill! [w] (executor/backfill! (:deps w) {:account-address "0xABC"}))

(defn- campaign-state [w campaign until]
  (.then (executor/load! (:deps w) fx/account)
         (fn [loaded]
           (let [found (attribution/campaign-findings
                        {:campaign campaign :fills (vals (get-in loaded [:ledger :fills]))
                         :updates (vals (get-in loaded [:ledger :updates]))
                         :markets (attribution/spot-markets fx/spot-meta) :index (:attribution loaded)
                         :unreadable (:unreadable loaded)})
                 token (get-in campaign [:scope :base-token-id])]
             (coverage/campaign-coverage {:campaign campaign :breaks (:breaks found) :until-ms until
                                          :unreadable (:unreadable found)
                                          :balance-checks (get-in loaded [:ledger :balance-checks])
                                          :coverage {:fills (get-in loaded [:ledger :coverage :fills token])
                                                     :ledger (get-in loaded [:ledger :coverage :ledger token])}})))))

(defn- fills-calls [w] (keep (fn [[t _ body]] (when (= "userFillsByTime" t) body)) @(:calls w)))

(def perp-row (fx/fill-row {:coin "BTC" :side "B" :px "60000" :sz "0.1" :time (+ t0 hour) :tid 900 :oid 9900}))
(def other-token-row (fx/fill-row {:coin "@9002" :side "B" :px "10" :sz "1" :time (+ t0 hour) :tid 901 :oid 9901}))

(deftest backfill-reads-balances-first-and-persists-relevant-rows-once-test
  (async done
    (let [w (world :fills [fx/buy-7-row perp-row other-token-row fx/manual-row]
                   :ledger-rows [fx/usdc-deposit-row])
          fail! (async-support/unexpected-error done)]
      (-> (backfill! w)
          (.then (fn [result]
                   (is (= :ok (:status result)))
                   (is (= ["spotClearinghouseState" "frontendOpenOrders" "userFillsByTime" "userNonFundingLedgerUpdates"
                           "userNonFundingLedgerUpdates"]
                          (mapv first @(:calls w)))
                       "the balance read comes before the fills read, as in reconcile!")
                   (is (every? #(= :low (:priority (second %))) (drop 2 @(:calls w))) "history reads are low priority")
                   (is (= 2 (:persisted result)) "the VRT ladder fill and the manual VRT trade; not BTC or VRS")
                   (is (= #{"9001"} (:tracked-base-token-ids result)))
                   (swap! (:clock w) + 60000)
                   (backfill! w)))
          (.then (fn [result]
                   (is (= 0 (:persisted result)) "overlapping runs store nothing twice")
                   (executor/load! (:deps w) fx/account)))
          (.then (fn [loaded]
                   (is (= #{(:fill-key (fx/fill fx/buy-7-row)) (:fill-key (fx/fill fx/manual-row))}
                          (set (keys (get-in loaded [:ledger :fills])))))
                   (is (= :rest (:source (get-in loaded [:ledger :fills (:fill-key (fx/fill fx/buy-7-row))]))))
                   (is (= {} (get-in loaded [:ledger :updates])) "a USDC deposit is not stored")
                   (done)))
          (.catch fail!)))))

(deftest complete-backfill-covers-the-campaign-test
  (async done
    (let [w (world :fills [fx/buy-7-row])
          fail! (async-support/unexpected-error done)
          now (+ t0 (* 6 hour))]
      (-> (backfill! w)
          (.then (fn [_] (campaign-state w (fx/campaign-record) (- now 5000))))
          (.then (fn [s]
                   (is (= :complete (:state s)))
                   (is (true? (:headline-allowed? s)))
                   (done)))
          (.catch fail!)))))

(deftest incomplete-and-truncated-reads-are-gaps-until-healed-test
  (async done
    (let [stuck (mapv #(fx/fill-row {:coin "BTC" :side "B" :px "1" :sz "1" :time (+ t0 hour) :tid % :oid %})
                      (range 2001))
          many (mapv #(fx/fill-row {:coin "BTC" :side "B" :px "1" :sz "1"
                                    :time (+ t0 hour %) :tid % :oid (+ 100000 %)})
                     (range 10000))
          w (world :fills stuck)
          fail! (async-support/unexpected-error done)
          until (+ t0 (* 5 hour))]
      (-> (backfill! w)
          (.then (fn [result]
                   (is (= :fills/no-progress (get-in result [:reads :fills :reason])))
                   (campaign-state w (fx/campaign-record) until)))
          (.then (fn [s]
                   (is (= :gap (:state s)))
                   (is (= :coverage/no-progress (:reason s)))
                   (reset! (:fills w) many)
                   (swap! (:clock w) + 60000)
                   (backfill! w)))
          (.then (fn [_] (campaign-state w (fx/campaign-record) until)))
          (.then (fn [s]
                   (is (= :gap (:state s)))
                   (is (= :coverage/retention (:reason s))
                       "10,000 distinct fills prove nothing before the oldest, even when none is a VRT fill")
                   (is (= [[(+ t0 hour) (+ t0 hour 1)]]
                          (mapv (juxt :from-ms :to-ms) (filter #(= :gap (:state %)) (:intervals s))))
                       "the no-progress gap healed; only the time the venue no longer serves is left")
                   (reset! (:fills w) [fx/buy-7-row])
                   (swap! (:clock w) + 60000)
                   (backfill! w)))
          (.then (fn [_] (campaign-state w (fx/campaign-record) until)))
          (.then (fn [s]
                   (is (= :coverage/retention (:reason s)) "lost time never heals")
                   (is (< (+ t0 (* 5 hour)) (get (last (fills-calls w)) "startTime"))
                       "and is not read again")
                   (done)))
          (.catch fail!)))))

(deftest coverage-only-for-tokens-tracked-when-the-read-was-requested-test
  (async done
    (let [vrs (fx/vrs-campaign)
          w (world :fills [fx/buy-7-row other-token-row]
                   :fills-hook (fn [journal] (when-not (some #(= "cmp-vrs" (:campaign-id %)) @journal)
                                               (swap! journal conj vrs))))
          fail! (async-support/unexpected-error done)
          until (+ t0 (* 5 hour))]
      (-> (backfill! w)
          (.then (fn [result]
                   (is (= #{"9001"} (:tracked-base-token-ids result)))
                   (is (= #{"9001"} (set (map :base-token-id (:coverage result)))))
                   (campaign-state w vrs until)))
          (.then (fn [s]
                   (is (= :partial (:state s)) "a campaign created during the read starts unconfirmed")
                   (executor/load! (:deps w) fx/account)))
          (.then (fn [loaded]
                   (is (not (contains? (get-in loaded [:ledger :fills]) (:fill-key (fx/fill other-token-row))))
                       "its rows were filtered out as irrelevant, so no coverage was claimed for them")
                   (swap! (:clock w) + 60000)
                   (backfill! w)))
          (.then (fn [result]
                   (is (= #{"9001" "9002"} (:tracked-base-token-ids result)))
                   (campaign-state w vrs until)))
          (.then (fn [s]
                   (is (= :attribution-break (:state s))
                       "the next backfill reads VRS from its own start and finds the untracked VRS trade")
                   (is (= :break/untracked-same-base-trade (:reason s)))
                   (done)))
          (.catch fail!)))))

(deftest balance-checks-are-created-then-evaluated-test
  (async done
    (let [w (world :fills [fx/buy-7-row])
          fail! (async-support/unexpected-error done)]
      (-> (backfill! w)
          (.then (fn [result]
                   (is (= [:pending] (map :state (:balance-checks result)))
                       "the read in the same backfill is too early to decide")
                   (swap! (:clock w) + 60000)
                   (backfill! w)))
          (.then (fn [result]
                   (is (= [:match] (map :state (:balance-checks result)))
                       "no second check while one is pending; the first one is now decided")
                   (is (= "157" (:expected (first (:balance-checks result)))))
                   (reset! (:vrt-total w) "156")
                   (swap! (:clock w) + 60000)
                   (backfill! w)))
          (.then (fn [_] (swap! (:clock w) + 60000) (backfill! w)))
          (.then (fn [result]
                   (is (some #(= :mismatch (:state %)) (:balance-checks result)))
                   (campaign-state w (fx/campaign-record) (+ t0 (* 7 hour)))))
          (.then (fn [s]
                   (is (= :attribution-break (:state s)))
                   (is (= :break/unexplained-balance-change (:reason s)))
                   (done)))
          (.catch fail!)))))

(deftest storage-failures-are-storage-unavailable-test
  (async done
    (let [broken {:put! (fn [_] (js/Promise.reject (js/Error. "QuotaExceededError")))
                  :get-all! (fn [] (js/Promise.resolve []))
                  :delete! (fn [_] (js/Promise.resolve nil))}
          unreadable (assoc broken :get-all! (fn [] (js/Promise.reject (js/Error. "IndexedDB is unavailable."))))
          w1 (world :fills [fx/buy-7-row] :io broken)
          w2 (world :io unreadable)
          fail! (async-support/unexpected-error done)]
      (-> (backfill! w1)
          (.then (fn [result]
                   (is (= :storage/unavailable (:status result)))
                   (backfill! w2)))
          (.then (fn [result]
                   (is (= :storage/unavailable (:status result)))
                   (is (= [] @(:calls w2)) "nothing is read when nothing can be stored")
                   (executor/load! (:deps w2) fx/account)))
          (.then (fn [loaded]
                   (is (false? (:storage-ok? loaded)))
                   (is (true? (:journal-ok? loaded)))
                   (done)))
          (.catch fail!)))))

(deftest backfill-never-rejects-test
  (async done
    (let [w (world)
          broken-journal (assoc-in (:deps w) [:journal :load-all!] (fn [] (js/Promise.reject (js/Error. "blocked"))))
          no-meta (assoc (:deps w) :spot-meta! (constantly nil))
          fail! (async-support/unexpected-error done)]
      (-> (executor/backfill! broken-journal {:account-address "0xabc"})
          (.then (fn [result]
                   (is (= :error (:status result)))
                   (is (= "blocked" (:error result)))
                   (executor/backfill! no-meta {:account-address "0xabc"})))
          (.then (fn [result]
                   (is (= :error (:status result)))
                   (is (= [] @(:calls w)) "without spot metadata nothing is read or classified")
                   (executor/backfill! (assoc-in (:deps w) [:journal :load-all!]
                                                 (fn [] (js/Promise.resolve {:records [] :unreadable []})))
                                       {:account-address "0xabc"})))
          (.then (fn [result]
                   (is (= :skipped (:status result)))
                   (done)))
          (.catch fail!)))))

;; ---------------------------------------------------------------------------
;; Review fixes (Reporting 1)
;; ---------------------------------------------------------------------------

(def busy-perp-fills
  "10,000 perp fills an hour after T0: more than the venue keeps."
  (mapv #(fx/fill-row {:coin "BTC" :side "B" :px "1" :sz "1" :time (+ t0 hour %) :tid % :oid (+ 100000 %)})
        (range 10000)))

(deftest a-closed-campaign-lost-to-retention-does-not-block-the-new-one-test
  (async done
    (let [c1 (fx/campaign-record)
          c2 (fx/campaign-record {:campaign-id "cmp-vrt-2" :at (+ t0 (* 3 hour))})
          w (world :fills busy-perp-fills)
          fail! (async-support/unexpected-error done)
          until (+ t0 (* 5 hour))]
      (swap! (:journal w) conj c2 (campaign/closure-record {:campaign c1 :successor c2 :now-ms (+ t0 (* 3 hour))}))
      (-> (backfill! w)
          (.then (fn [result]
                   (is (= :incomplete (:status result)))
                   (is (= "the venue keeps only its most recent 10,000 fills" (:error result)))
                   (campaign-state w c1 (+ t0 (* 3 hour)))))
          (.then (fn [s]
                   (is (= :gap (:state s)))
                   (is (= :coverage/retention (:reason s)) "the closed tracking keeps its permanent gap")
                   (campaign-state w c2 until)))
          (.then (fn [s]
                   (is (= :complete (:state s)) "the new tracking is proven by the fills the venue still serves")
                   (is (true? (:headline-allowed? s)))
                   (swap! (:clock w) + 60000)
                   (backfill! w)))
          (.then (fn [result]
                   (is (= :ok (:status result)))
                   (is (< (+ t0 (* 5 hour)) (get (last (fills-calls w)) "startTime"))
                       "the next read starts after the covered time, not at the closed campaign's start")
                   (done)))
          (.catch fail!)))))

(deftest unreadable-stored-rows-are-unresolved-until-rewritten-test
  (async done
    (let [w (world :fills [fx/buy-7-row fx/manual-row])
          id (str "fill:" (:fill-key (fx/fill fx/manual-row)))
          fail! (async-support/unexpected-error done)
          until (+ t0 (* 5 hour))]
      (-> (backfill! w)
          (.then (fn [_]
                   ;; Another build (or corruption) leaves a row this build cannot decode.
                   (swap! (:rows (:io w)) assoc-in [id :edn] "{:schema-version 99")
                   (executor/load! (:deps w) fx/account)))
          (.then (fn [loaded]
                   (is (= [id] (:unreadable loaded)))
                   (is (not (contains? (get-in loaded [:ledger :fills]) (:fill-key (fx/fill fx/manual-row)))))
                   (campaign-state w (fx/campaign-record) until)))
          (.then (fn [s]
                   (is (= :gap (:state s)) "coverage is never complete over a row that could not be read")
                   (is (= :storage/unreadable-rows (:reason s)))
                   (is (false? (:headline-allowed? s)))
                   (swap! (:clock w) + 60000)
                   (backfill! w)))
          (.then (fn [_]
                   (is (= (- (:time fx/manual-row) 60000) (get (last (fills-calls w)) "startTime"))
                       "the backfill reads again from the unreadable row")
                   (executor/load! (:deps w) fx/account)))
          (.then (fn [loaded]
                   (is (= [] (:unreadable loaded)) "the row was rewritten from the venue")
                   (campaign-state w (fx/campaign-record) until)))
          (.then (fn [s]
                   (is (= :attribution-break (:state s)))
                   (is (= :break/untracked-same-base-trade (:reason s)) "the trade it held is seen again")
                   (done)))
          (.catch fail!)))))

(deftest relevant-rows-that-cannot-be-parsed-end-coverage-at-their-time-test
  (async done
    (let [bad-spot (fx/fill-row {:side "B" :px "abc" :sz "1" :time (+ t0 (* 3 hour)) :tid 700 :oid 8890})
          bad-perp (fx/fill-row {:coin "BTC" :side "B" :px "abc" :sz "1" :time (+ t0 hour) :tid 701 :oid 8891})
          bad-ledger {:time (+ t0 (* 4 hour)) :hash "0xbad" :delta nil}
          w (world :fills [fx/buy-7-row bad-spot bad-perp] :ledger-rows [bad-ledger])
          fail! (async-support/unexpected-error done)]
      (-> (backfill! w)
          (.then (fn [result]
                   (is (= :incomplete (:status result)))
                   (is (= "some fills or account updates couldn't be read" (:error result)))
                   (campaign-state w (fx/campaign-record) (+ t0 (* 5 hour)))))
          (.then (fn [s]
                   (is (= :gap (:state s)))
                   (is (= :coverage/unparsed-row (:reason s)))
                   (is (= [(- t0 fx/skew) (+ t0 (* 3 hour))]
                          ((juxt :from-ms :to-ms) (first (:intervals s))))
                       "complete up to the unparsed VRT fill; the unparsed perp fill does not matter")
                   (is (= :complete (:state (first (:intervals s)))))
                   (done)))
          (.catch fail!)))))

(deftest a-failed-read-is-not-reported-as-updated-test
  (async done
    (let [w (world :fills [fx/buy-7-row])
          deps (update (:deps w) :post-info!
                       (fn [f] (fn [body opts]
                                 (if (= "userFillsByTime" (get body "type"))
                                   (js/Promise.reject (js/Error. "HTTP 429"))
                                   (f body opts)))))
          fail! (async-support/unexpected-error done)]
      (-> (executor/backfill! deps {:account-address "0xabc"})
          (.then (fn [result]
                   (is (= :incomplete (:status result)))
                   (is (= "HTTP 429" (:error result)))
                   (is (false? (get-in result [:reads :fills :complete?])))
                   (done)))
          (.catch fail!)))))
