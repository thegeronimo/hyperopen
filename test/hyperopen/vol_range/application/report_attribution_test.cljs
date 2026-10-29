(ns hyperopen.vol-range.application.report-attribution-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def markets (attribution/spot-markets fx/spot-meta))

(defn- findings
  ([fills] (findings fills []))
  ([fills updates] (findings fills updates [(fx/grid)]))
  ([fills updates grids]
   (attribution/campaign-findings {:campaign (fx/campaign-record) :ended-at-ms nil
                                   :fills (map fx/fill fills) :updates updates :markets markets
                                   :index (attribution/attribution-index grids)})))

(defn- reasons [f] (mapv :reason (:breaks f)))

(deftest index-maps-cloid-and-oid-to-the-ladder-test
  (let [idx (attribution/attribution-index [(fx/grid)])
        entry (get-in idx [:by-oid "7001"])]
    (is (= {:grid-id "grid-vrt" :operation-id "op-vrt" :logical-id "buy-0" :side :buy :rung-index 0
            :campaign-key "hyperliquid-mainnet|0xabc|spot|9001|9001" :recovered? false
            :sent-after-ms (+ fx/t0 200)}
           entry))
    (is (= entry (get-in idx [:by-cloid fx/cloid-buy])))))

(deftest tracked-by-oid-and-by-cloid-test
  (let [by-oid (findings [fx/buy-7-row])
        by-cloid (findings [(-> fx/buy-7-row (dissoc :oid) (assoc :cloid (.toUpperCase fx/cloid-buy)))])]
    (doseq [f [by-oid by-cloid]]
      (is (= [] (:breaks f)))
      (is (= 1 (count (:events f))))
      (is (= {:tracked? true :campaign-id "cmp-vrt" :grid-id "grid-vrt" :logical-id "buy-0"}
             (select-keys (first (:events f)) [:tracked? :campaign-id :grid-id :logical-id]))))))

(deftest recovered-ladder-fills-are-untracked-test
  (let [f (findings [fx/buy-7-row] [] [(fx/grid {:recovered? true})])]
    (is (= [] (:events f)))
    (is (= [:break/untracked-same-base-trade] (reasons f)))))

(deftest same-pair-and-other-pair-trades-break-test
  (is (= [:break/untracked-same-base-trade] (reasons (findings [fx/manual-row]))))
  (let [f (findings [fx/other-pair-row])]
    (is (= [:break/other-pair-trade] (reasons f)))
    (is (= (:time fx/other-pair-row) (:at-ms (first (:breaks f)))))))

(deftest perp-and-hip3-coins-are-ignored-test
  (doseq [coin ["BTC" "xyz:GOLD" "HYPE"]]
    (let [row (assoc fx/manual-row :coin coin)]
      (is (= {:events [] :breaks []} (findings [row])) coin)
      (is (= :ignored (:status (attribution/classify-fill markets {} (fx/fill row))))))))

(deftest unknown-spot-market-breaks-test
  (let [row (assoc fx/manual-row :coin "@424242")]
    (is (= :unknown-market (:status (attribution/classify-fill markets {} (fx/fill row)))))
    (is (attribution/relevant-fill? (attribution/classify-fill markets {} (fx/fill row)) #{"9001"}))
    (is (= [:break/unknown-market-fill] (reasons (findings [row]))))))

(deftest third-token-fee-breaks-but-keeps-the-event-test
  (let [f (findings [(assoc fx/buy-7-row :fee "0.01" :feeToken "VRS")])]
    (is (= [:break/unsupported-fee-token] (reasons f)))
    (is (= 1 (count (:events f))) "the known fill is still shown")))

(deftest relevance-depends-on-tracked-tokens-test
  (let [c (fn [row] (attribution/classify-fill markets {} (fx/fill row)))]
    (is (attribution/relevant-fill? (c fx/manual-row) #{"9001"}))
    (is (not (attribution/relevant-fill? (c fx/manual-row) #{"9002"})))
    (is (attribution/relevant-fill? (c fx/other-pair-row) #{"9002"}) "the quote side counts too")
    (is (not (attribution/relevant-fill? (c fx/manual-row) #{})))))

(deftest inception-boundary-test
  (testing "before T0 - skew the event is in q0"
    (is (= {:events [] :breaks []} (findings [(assoc fx/manual-row :time (- fx/t0 fx/skew 1))]))))
  (testing "any untracked same-base event inside the widened boundary is ambiguous"
    (doseq [t [(- fx/t0 fx/skew) fx/t0 (+ fx/t0 180 fx/skew)]]
      (is (= [:break/ambiguous-boundary] (reasons (findings [(assoc fx/manual-row :time t)]))) (str t))))
  (testing "a fill of the ladder admitted after the boundary read is not ambiguous"
    (is (= [] (reasons (findings [(assoc fx/buy-7-row :time (+ fx/t0 1000))])))))
  (testing "a fill of a ladder admitted before T0 inside the boundary is ambiguous"
    (is (= [:break/ambiguous-boundary]
           (reasons (findings [(assoc fx/buy-7-row :time (+ fx/t0 1000))] []
                              [(fx/grid {:admitted-at (- fx/t0 60000)})])))))
  (testing "a tracked fill with a venue time before T0 is ambiguous"
    (is (= [:break/ambiguous-boundary] (reasons (findings [(assoc fx/buy-7-row :time (dec fx/t0))])))))
  (testing "a break before T0 is dated T0"
    (is (= fx/t0 (:at-ms (first (:breaks (findings [(assoc fx/manual-row :time (- fx/t0 10))])))))))
  (testing "after the campaign closed nothing belongs to it"
    (is (= {:events [] :breaks []}
           (attribution/campaign-findings {:campaign (fx/campaign-record) :ended-at-ms (+ fx/t0 fx/hour)
                                           :fills [(fx/fill fx/manual-row)] :updates [] :markets markets
                                           :index {}})))))

(defn- ledger-records [row]
  (attribution/ledger-update-records {:markets markets :tracked #{"9001"} :account-address "0xABC"
                                      :row row :now-ms 1}))

(deftest ledger-classification-table-test
  (testing "USDC-only types are ignored"
    (doseq [t ["deposit" "withdraw" "internalTransfer" "accountClassTransfer" "subAccountTransfer"
               "vaultCreate" "vaultDeposit" "vaultWithdraw" "vaultDistribution" "liquidation" "rewardsClaim"]]
      (is (= [] (ledger-records {:time 5 :hash "0x1" :delta {:type t :token "VRT" :usdc "1"}})) t)))
  (testing "base transfers"
    (doseq [t ["spotTransfer" "send" "spotGenesis" "cStakingTransfer" "borrowLend" "deployGasAuction"]]
      (let [[r & more] (ledger-records {:time 5 :hash "0x1" :delta {:type t :token "VRT" :amount "-5"}})]
        (is (nil? more))
        (is (= {:kind :ledger-update :classification :base-transfer :token "VRT" :base-token-id "9001"
                :delta-type t :amount "-5" :account-address "0xabc"
                :record-id (str "ledger:0xabc|5|0x1|" t "|VRT")}
               (select-keys r [:kind :classification :token :base-token-id :delta-type :amount :account-address
                               :record-id]))
            t)))
    (is (= [] (ledger-records {:time 5 :hash "0x1" :delta {:type "spotTransfer" :token "USDC" :amount "-5"}}))
        "another token's transfer is not this campaign's")
    (is (= 1 (count (ledger-records {:time 5 :hash "0x1" :delta {:type "send" :token "VRT:0x1234" :amount "1"}})))
        "a token label with an id suffix resolves by name"))
  (testing "unknown types break only when they name the base"
    (is (= [:unrecognized-ledger-entry]
           (mapv :classification (ledger-records {:time 5 :hash "0x1"
                                                  :delta {:type "airdrop" :meta {:coin "VRT"} :n "3"}}))))
    (is (= [] (ledger-records {:time 5 :hash "0x1" :delta {:type "airdrop" :coin "PURR"}})))))

(deftest ledger-updates-break-the-campaign-test
  (let [[transfer] (ledger-records fx/spot-transfer-row)
        f (findings [] [transfer])]
    (is (= [{:at-ms (:time fx/spot-transfer-row) :time (:time fx/spot-transfer-row) :reason :break/base-transfer
             :source :ledger :event-id (:record-id transfer)}]
           (:breaks f))))
  (is (= [] (ledger-records fx/usdc-deposit-row))))
