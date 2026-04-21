(ns hyperopen.order.cancel-guard-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.cancel-guard :as cancel-guard]))

(deftest prune-open-order-payload-keeps-same-oid-row-when-rich-identity-conflicts-test
  (let [guard #{:ignored
                {:oid 22
                 :asset-id 0
                 :dex "dex-a"}}
        matching-row {:coin "BTC"
                      :oid 22
                      :asset-id 0
                      :dex "dex-a"}
        conflicting-row {:coin "ETH"
                         :oid 22
                         :asset-id 1
                         :dex "dex-b"}
        oid-only-row {:coin "UNKNOWN"
                      :oid 22}
        unrelated-row {:coin "SOL"
                       :oid 23
                       :asset-id 0
                       :dex "dex-a"}]
    (is (= {:openOrders [conflicting-row unrelated-row]}
           (cancel-guard/prune-open-order-payload
            {:openOrders [matching-row
                          conflicting-row
                          oid-only-row
                          unrelated-row]}
            guard))
        "guarded rows with matching rich identity and oid-only fallback rows are pruned; same-oid rows with conflicting asset/dex identity remain")))
