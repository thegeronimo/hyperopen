(ns hyperopen.vaults.application.list-commands-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.application.list-commands :as list-commands]
            [hyperopen.vaults.infrastructure.routes :as routes]))

(deftest set-vaults-snapshot-range-persists-preference-and-fetches-benchmarks-on-detail-route-test
  (is (= [[:effects/save-many [[[:vaults-ui :snapshot-range] :week]
                               [[:vaults-ui :user-vaults-page] 1]
                               [[:vaults-ui :detail-chart-hover-index] nil]]]
          [:effects/local-storage-set "vaults-snapshot-range" "week"]
          [:effects/fetch-candle-snapshot :coin "BTC" :interval :15m :bars 800]
          [:effects/fetch-candle-snapshot :coin "ETH" :interval :15m :bars 800]]
         (list-commands/set-vaults-snapshot-range
          {:parse-vault-route-fn routes/parse-vault-route
           :snapshot-range-save-effect-fn (fn [snapshot-range]
                                            [:effects/local-storage-set "vaults-snapshot-range" (name snapshot-range)])}
          {:vaults-ui {:detail-chart-series :returns
                       :detail-returns-benchmark-coins ["BTC"
                                                        "vault:0x1234567890abcdef1234567890abcdef12345678"
                                                        "ETH"]}
           :router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}}
          :week))))
