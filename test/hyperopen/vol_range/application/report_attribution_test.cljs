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
            :sent-after-ms (+ fx/t0 200) :predecessor-grid-id nil}
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
      (is (= {:events [] :breaks [] :unreadable []} (findings [row])) coin)
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
    (is (= {:events [] :breaks [] :unreadable []} (findings [(assoc fx/manual-row :time (- fx/t0 fx/skew 1))]))))
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
    (is (= {:events [] :breaks [] :unreadable []}
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

;; ---------------------------------------------------------------------------
;; Review fixes (Reporting 1)
;; ---------------------------------------------------------------------------

(def hype-markets
  (attribution/spot-markets (update fx/spot-meta :tokens conj {:name "HYPE" :index 150 :szDecimals 2})))

(defn- hype-records [row]
  (attribution/ledger-update-records {:markets hype-markets :tracked #{"150" "9001"} :account-address "0xABC"
                                      :row row :now-ms 1}))

(deftest transfer-fees-in-a-tracked-token-break-test
  (testing "a send of another token that pays its fee in the native token changes HYPE"
    (let [rs (hype-records {:time 5 :hash "0x1"
                            :delta {:type "send" :destination "0x20" :sourceDex "spot" :destinationDex "spot"
                                    :token "USDC" :nativeTokenFee "0.00002"}})]
      (is (= [["150" :base-transfer "HYPE"]] (mapv (juxt :base-token-id :classification :token) rs)))
      (is (= "ledger:0xabc|5|0x1|send|HYPE" (:record-id (first rs))))))
  (testing "a zero native fee changes nothing"
    (is (= [] (hype-records {:time 5 :hash "0x1" :delta {:type "send" :token "USDC" :nativeTokenFee "0.0"}}))))
  (testing "a fee in a tracked feeToken counts too, once per token"
    (is (= [["9001" :base-transfer]]
           (mapv (juxt :base-token-id :classification)
                 (hype-records {:time 5 :hash "0x1"
                                :delta {:type "spotTransfer" :token "VRT" :amount "-1" :fee "0.1" :feeToken "VRT"}}))))
    (is (= [["9001" :base-transfer]]
           (mapv (juxt :base-token-id :classification)
                 (hype-records {:time 5 :hash "0x1"
                                :delta {:type "send" :token "USDC" :amount "-1" :fee "0.1" :feeToken "VRT"}}))))))

(deftest unparsed-rows-name-the-tokens-they-could-change-test
  (let [tokens #{"9001" "9002"}]
    (is (= #{} (attribution/unparsed-fill-token-ids markets tokens {:coin "BTC" :px "x"})) "perps never matter")
    (is (= #{"9001"} (attribution/unparsed-fill-token-ids markets tokens {:coin "@9001" :px "x"})))
    (is (= tokens (attribution/unparsed-fill-token-ids markets tokens {:coin "@424242"})))
    (is (= tokens (attribution/unparsed-fill-token-ids markets tokens {:px "1"})) "no coin: any token"))
  (is (attribution/unparsed-ledger-row? {:time "5" :delta {:type "spotTransfer" :token "VRT"}}))
  (is (attribution/unparsed-ledger-row? {:time 5 :delta "?"}))
  (is (not (attribution/unparsed-ledger-row? {:time "5" :delta {:type "deposit" :usdc "1"}})) "USDC-only")
  (is (not (attribution/unparsed-ledger-row? fx/spot-transfer-row))))

(deftest boundary-fills-of-the-ladder-stay-in-the-activity-test
  (let [early-grid [(fx/grid {:admitted-at (- fx/t0 60000)})]
        restart (assoc (fx/campaign-record) :supersedes-campaign-id "cmp-before")
        run (fn [campaign row]
              (attribution/campaign-findings {:campaign campaign :ended-at-ms nil :fills [(fx/fill row)] :updates []
                                              :markets markets :index (attribution/attribution-index early-grid)}))
        after (run restart (assoc fx/buy-7-row :time (+ fx/t0 2000)))
        before (run restart (assoc fx/buy-7-row :time (- fx/t0 2000)))
        first-campaign (run (fx/campaign-record) (assoc fx/buy-7-row :time (- fx/t0 2000)))]
    (is (= [:break/ambiguous-boundary] (reasons after)))
    (is (= 1 (count (:events after))) "a rung filling just after a restart is shown in the new tracking")
    (is (= [:break/ambiguous-boundary] (reasons before)))
    (is (= [] (:events before)) "before a restart's T0 the fill belongs to the tracking that ended there")
    (is (= 1 (count (:events first-campaign))) "the first tracking of a market shows it")))

(deftest unreadable-rows-are-unresolved-for-the-campaign-test
  (let [fill-id (str "fill:" (:fill-key (fx/fill fx/manual-row)))
        btc-id (str "fill:" (:fill-key (fx/fill (assoc fx/manual-row :coin "BTC"))))
        vrs-id (str "fill:" (:fill-key (fx/fill (assoc fx/manual-row :coin "@9002"))))
        start (- fx/t0 fx/skew)
        run (fn [ids] (:unreadable (attribution/campaign-findings
                                    {:campaign (fx/campaign-record) :fills [] :updates [] :markets markets
                                     :index {} :unreadable ids})))]
    (is (= {:kind :fill :time (:time fx/manual-row) :token-ids #{"9001" "0"}}
           (select-keys (attribution/unreadable-row markets fill-id) [:kind :time :token-ids])))
    (is (= [{:time (:time fx/manual-row) :record-id fill-id}] (run [fill-id])))
    (is (= [] (run [btc-id vrs-id "coverage:0xabc|fills|9002" "balance-check:cmp-other|1"]))
        "rows of perps, of other tokens and of other campaigns do not concern it")
    (is (= [{:time start :record-id "ledger:0xabc|x|0x1|send|VRT"}] (run ["ledger:0xabc|x|0x1|send|VRT"]))
        "a row whose time cannot be told concerns the whole campaign")
    (is (= [start start start] (map :time (run ["junk" nil "coverage:0xabc|ledger|9001"]))))))
