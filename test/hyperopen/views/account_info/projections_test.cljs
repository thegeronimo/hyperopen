(ns hyperopen.views.account-info.projections-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.projections :as projections]))

(deftest open-order-for-active-asset-matches-base-and-namespaced-coin-forms-test
  (is (true? (projections/open-order-for-active-asset? "SOL"
                                                    {:coin "SOL"})))
  (is (true? (projections/open-order-for-active-asset? "SOL"
                                                    {:coin "perp:SOL"})))
  (is (true? (projections/open-order-for-active-asset? "perp:SOL"
                                                    {:coin "SOL"})))
  (is (false? (projections/open-order-for-active-asset? "SOL"
                                                     {:coin "BTC"})))
  (is (false? (projections/open-order-for-active-asset? nil
                                                     {:coin "SOL"}))))

(deftest normalized-open-orders-for-active-asset-filters-and-dedupes-by-coin-and-oid-test
  (let [live-orders [{:coin "SOL"
                      :oid 11
                      :side "B"
                      :sz "1.00"
                      :limitPx "60.0"
                      :timestamp 1700000000000}
                     {:coin "SOL"
                      :oid 11
                      :side "B"
                      :sz "1.00"
                      :limitPx "60.0"
                      :timestamp 1700000000001}
                     {:coin "BTC"
                      :oid 91
                      :side "A"
                      :sz "0.10"
                      :limitPx "100000.0"
                      :timestamp 1700000000002}
                     {:order {:coin "perp:SOL"
                              :oid 33
                              :side "A"
                              :sz "2.50"
                              :px "61.5"
                              :timestamp 1700000000003}}]
        snapshot nil
        snapshot-by-dex {:dex-a [{:coin "SOL"
                                  :oid 11
                                  :side "B"
                                  :sz "1.00"
                                  :limitPx "60.0"
                                  :timestamp 1700000009999}]
                         :dex-b [{:coin "perp:SOL"
                                  :oid 33
                                  :side "A"
                                  :sz "2.50"
                                  :limitPx "61.5"
                                  :timestamp 1700000010000}]}
        orders (projections/normalized-open-orders-for-active-asset live-orders
                                                                     snapshot
                                                                     snapshot-by-dex
                                                                     "SOL")]
    (is (= ["11" "33"] (mapv :oid orders)))
    (is (= ["SOL" "perp:SOL"] (mapv :coin orders)))
    (is (= ["B" "A"] (mapv :side orders)))
    (is (= ["60.0" "61.5"] (mapv :px orders)))))

(deftest normalized-open-orders-excludes-pending-cancel-oids-test
  (let [orders [{:coin "SOL"
                 :oid "11"
                 :side "B"
                 :sz "1.00"
                 :limitPx "60.0"}
                {:coin "SOL"
                 :oid 12
                 :side "A"
                 :sz "2.00"
                 :limitPx "61.0"}]
        normalized (projections/normalized-open-orders orders nil nil #{11})]
    (is (= ["12"] (mapv :oid normalized)))))

(deftest normalized-open-orders-for-active-asset-excludes-pending-cancel-oids-test
  (let [orders [{:coin "SOL"
                 :oid 11
                 :side "B"
                 :sz "1.00"
                 :limitPx "60.0"}
                {:coin "SOL"
                 :oid 12
                 :side "A"
                 :sz "2.00"
                 :limitPx "61.0"}]
        active-orders (projections/normalized-open-orders-for-active-asset orders
                                                                            nil
                                                                            nil
                                                                            "SOL"
                                                                            #{12})]
    (is (= ["11"] (mapv :oid active-orders)))))

(deftest pending-cancel-oid-set-canonicalizes-order-id-values-test
  (let [pending-set (projections/pending-cancel-oid-set ["123" 123 " 123 " nil ""])]
    (is (= #{"123"} pending-set))))

(deftest normalized-open-orders-dedupes-order-ids-across-string-and-number-forms-test
  (let [orders [{:coin "SOL" :oid "123" :limitPx "50.0" :timestamp 1710000000}
                {:coin "SOL" :oid 123 :limitPx "50.0" :timestamp 1710000000000}]
        normalized (projections/normalized-open-orders-for-active-asset orders nil nil "SOL")]
    (is (= 1 (count normalized)))
    (is (= ["123"] (mapv :oid normalized)))))

(deftest normalize-open-order-coerces-trigger-boolean-strings-test
  (let [false-trigger-order (projections/normalize-open-order {:coin "SOL"
                                                               :oid "1"
                                                               :isTrigger "false"
                                                               :limitPx "0"
                                                               :triggerPx "62.5"
                                                               :timestamp 1710000000})
        true-trigger-order (projections/normalize-open-order {:coin "SOL"
                                                              :oid "2"
                                                              :isTrigger "true"
                                                              :limitPx "0"
                                                              :triggerPx "63.5"
                                                              :timestamp 1710000000})]
    (is (false? (:is-trigger false-trigger-order)))
    (is (= "0" (:px false-trigger-order)))
    (is (true? (:is-trigger true-trigger-order)))
    (is (= "63.5" (:px true-trigger-order)))))

(deftest normalize-open-order-reads-nested-trigger-payload-fields-test
  (let [order (projections/normalize-open-order {:order {:coin "SOL"
                                                          :oid "3"
                                                          :type "Take Profit Market"
                                                          :dex "dex-a"
                                                          :isPositionTpsl true
                                                          :t {:trigger {:triggerPx "64.2"
                                                                        :tpsl "tp"}}}})]
    (is (true? (:is-trigger order)))
    (is (= "64.2" (:trigger-px order)))
    (is (= "64.2" (:px order)))
    (is (= "tp" (:tpsl order)))
    (is (= "dex-a" (:dex order)))))

(deftest open-orders-by-dex-tags-rows-with-dex-when-missing-test
  (let [rows (projections/open-orders-by-dex {:dex-a [{:coin "SOL" :oid 1}]
                                              :dex-b [{:order {:coin "BTC" :oid 2}}]})]
    (is (= #{"dex-a" "dex-b"}
           (set (map #(let [value (or (:dex %)
                                      (get-in % [:order :dex]))]
                        (if (keyword? value)
                          (name value)
                          (str value)))
                     rows))))))

(deftest parse-time-ms-normalizes-seconds-and-milliseconds-test
  (is (= 1710000000000 (projections/parse-time-ms 1710000000)))
  (is (= 1710000000000 (projections/parse-time-ms 1710000000000)))
  (is (= 1710000000000
         (:time-ms (projections/normalize-open-order {:coin "SOL"
                                                      :oid 1
                                                      :timestamp 1710000000}))))
  (is (= 1710000000000
         (projections/trade-history-time-ms {:time 1710000000})))
  (is (= 1710000000000
         (projections/trade-history-time-ms {:time 1710000000000}))))

(deftest build-balance-rows-uses-nil-usdc-value-when-price-is-missing-test
  (let [rows (projections/build-balance-rows
              {:spotAssetCtxs nil}
              {:meta {:tokens [{:index 1
                                :name "MYST"
                                :weiDecimals 6}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "MYST"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "3.0"
                                                 :entryNtl "0"}]}})
        row (first rows)]
    (is (= 1 (count rows)))
    (is (= "MYST" (:coin row)))
    (is (nil? (:usdc-value row)))))

(deftest normalize-order-history-row-returns-status-key-and-display-label-test
  (let [row {:order {:coin "SOL"
                     :oid 1
                     :side "B"
                     :origSz "1.0"
                     :remainingSz "0.0"
                     :limitPx "100.0"
                     :timestamp 1710000000}
             :status "filled"
             :statusTimestamp 1710000000}
        normalized (projections/normalize-order-history-row row)]
    (is (= :filled (:status-key normalized)))
    (is (= "Filled" (:status-label normalized)))
    (is (= 1 (:filled-size normalized)))))

(deftest normalize-order-history-row-uses-sz-as-remaining-size-when-remaining-sz-missing-test
  (let [row {:order {:coin "PUMP"
                     :oid 12
                     :side "A"
                     :origSz "11273"
                     :sz "0.0"
                     :limitPx "0.001772"
                     :timestamp 1710000100}
             :status "filled"
             :statusTimestamp 1710000100}
        normalized (projections/normalize-order-history-row row)]
    (is (= 11273 (:filled-size normalized)))
    (is (= "Filled" (:status-label normalized)))))

(deftest normalize-order-history-row-maps-cancel-reason-statuses-to-canceled-with-tooltip-test
  (let [row {:order {:coin "PUMP"
                     :oid 33
                     :side "A"
                     :origSz "10.0"
                     :sz "10.0"
                     :limitPx "0.002"
                     :timestamp 1710000200}
             :status "reduceOnlyCanceled"
             :statusTimestamp 1710000200}
        normalized (projections/normalize-order-history-row row)]
    (is (= :canceled (:status-key normalized)))
    (is (= "Canceled" (:status-label normalized)))
    (is (= "Canceled due to reduce only."
           (:status-tooltip normalized)))))

(deftest normalize-order-history-row-labels-filled-orders-with-remaining-size-as-partially-filled-test
  (let [row {:order {:coin "HYPE"
                     :oid 44
                     :side "B"
                     :origSz "15.23"
                     :sz "3.0"
                     :limitPx "28.454"
                     :timestamp 1710000300}
             :status "filled"
             :statusTimestamp 1710000300}
        normalized (projections/normalize-order-history-row row)]
    (is (= "Partially Filled" (:status-label normalized)))
    (is (< (js/Math.abs (- 12.23 (:filled-size normalized))) 1e-9))))

(deftest normalized-order-history-prefers-filled-rows-over-open-rows-for-the-same-order-id-test
  (let [open-row {:order {:coin "PUMP"
                          :oid 330007475448
                          :side "A"
                          :origSz "11273"
                          :remainingSz "11273"
                          :limitPx "0.001772"
                          :timestamp 1700000000000}
                  :status "open"
                  :statusTimestamp 1700000000000}
        filled-row {:order {:coin "PUMP"
                            :oid 330007475448
                            :side "A"
                            :origSz "11273"
                            :remainingSz "0.0"
                            :limitPx "0.001772"
                            :timestamp 1700000000000}
                    :status "filled"
                    :statusTimestamp 1700000000000}
        open-first (projections/normalized-order-history [open-row filled-row])
        filled-first (projections/normalized-order-history [filled-row open-row])]
    (doseq [rows [open-first filled-first]]
      (is (= 1 (count rows)))
      (is (= :filled (:status-key (first rows))))
      (is (= "Filled" (:status-label (first rows))))
      (is (= 11273 (:filled-size (first rows)))))))
