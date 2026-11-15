(ns hyperopen.portfolio.optimizer.application.execution-cloid-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.execution-cloid :as cloid]))

(deftest optimizer-cloid-recognizer-matches-only-the-magic-prefix-test
  (is (cloid/optimizer-cloid? "0x0770c0de1111222233334444555566ff"))
  (is (cloid/optimizer-cloid? "0X0770C0DE1111222233334444555566FF")
      "case-insensitive")
  (is (not (cloid/optimizer-cloid? "0xdeadbeef1111222233334444555566ff"))
      "a manual order's cloid is not ours")
  (is (not (cloid/optimizer-cloid? nil)))
  (is (not (cloid/optimizer-cloid? ""))))

(deftest make-cloid-builds-a-well-formed-tagged-cloid-test
  (let [c (cloid/make-cloid "abc123def456abc123def456")]
    (is (= "0x0770c0deabc123def456abc123def456" c))
    (is (= 34 (count c)) "0x + 32 hex chars")
    (is (cloid/optimizer-cloid? c)))
  (is (= 34 (count (cloid/make-cloid "ab"))))
  (is (= 34 (count (cloid/make-cloid "ffffffffffffffffffffffffffffffff-too-long"))))
  (is (cloid/optimizer-cloid? (cloid/make-cloid "ab"))))

(deftest live-open-orders-normalizes-rows-across-sources-test
  (let [state {:orders {:open-orders-snapshot
                        [{:oid 1 :coin "BTC" :side "B" :sz "0.5" :limitPx "60000"
                          :cloid "0x0770C0DE1111222233334444555566ff"}
                         {:oid 2 :coin "ETH" :side "A" :sz "3"}
                         {:coin "no-oid-dropped"}]}}
        rows (cloid/live-open-orders state)]
    (is (= [1 2] (mapv :oid rows)) "rows without an oid/coin are dropped")
    (is (= "0x0770c0de1111222233334444555566ff" (:cloid (first rows)))
        "cloid normalized to lowercase")
    (is (nil? (:cloid (second rows))) "absent cloid stays nil")))

(deftest live-open-orders-reads-ws-payload-map-and-per-dex-snapshots-test
  (let [state {:orders {:open-orders {:dex "" :user "0xabc"
                                      :orders [{:oid 10 :coin "BTC" :side "B"}]}
                        :open-orders-snapshot-by-dex
                        {"xyz" [{:oid 20 :coin "ORCL" :side "B"
                                 :cloid "0x0770c0deaaaaaaaaaaaaaaaaaaaaaaaa"}]}}}
        rows (cloid/live-open-orders state)
        by-oid (into {} (map (juxt :oid identity)) rows)]
    (is (= #{10 20} (set (keys by-oid))))
    (is (= "xyz:ORCL" (:coin (get by-oid 20)))
        "per-dex bare coin namespaced with its dex")
    (is (= "0x0770c0deaaaaaaaaaaaaaaaaaaaaaaaa" (:cloid (get by-oid 20))))))

(deftest classify-overlap-splits-owned-untagged-and-ignored-test
  (let [snapshot [{:oid 1 :coin "BTC" :cloid "0x0770c0deaaaaaaaaaaaaaaaaaaaaaaaa"}
                  {:oid 2 :coin "ETH" :cloid "0xmanualmanualmanualmanualmanual00"}
                  {:oid 3 :coin "SOL" :cloid nil}
                  {:oid 4 :coin "DOGE" :cloid "0xdeadbeef0000000000000000000000ff"}]
        ready-rows [{:instrument-id "perp:BTC"}
                    {:instrument-id "perp:ETH"}
                    {:instrument-id "perp:SOL"}]
        {:keys [optimizer-owned untagged-overlap]} (cloid/classify-overlap snapshot ready-rows)]
    (is (= [1] (mapv :oid optimizer-owned))
        "our cloid-tagged order -> auto-cancel candidate")
    (is (= [2 3] (mapv :oid untagged-overlap))
        "orders on a traded coin without our tag -> user decides")
    (is (not (some #{4} (map :oid (concat optimizer-owned untagged-overlap))))
        "DOGE isn't traded by any ready row -> ignored")))
